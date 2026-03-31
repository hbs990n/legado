package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.DoubaoNotReadyException
import io.legado.app.help.DoubaoSendException
import io.legado.app.help.DoubaoTtsClient
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.LogUtils
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.ensureActive
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 豆包TTS下载管理器
 * 管理章节音频的下载队列、文本分段、WAV合并
 */
object DoubaoDownloadManager {

    /** 最大单次发送字节数 (5KB) */
    private const val MAX_SEND_BYTES = 5120

    /** WAV header 大小 */
    private const val WAV_HEADER_SIZE = 44

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    /** 下载队列: bookUrl + chapterIndex → true */
    private val _downloadQueue = MutableStateFlow<Set<String>>(emptySet())
    val downloadQueue: StateFlow<Set<String>> = _downloadQueue

    /** 正在下载的章节 */
    private val _downloadingChapter = MutableStateFlow<String?>(null)
    val downloadingChapter: StateFlow<String?> = _downloadingChapter

    /** 下载进度事件 */
    private val _progressEvent = MutableSharedFlow<DownloadProgress>()
    val progressEvent: SharedFlow<DownloadProgress> = _progressEvent

    /** 当前下载状态 */
    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking

    /** 下载基础目录 */
    private val baseDir: File by lazy {
        File(appCtx.externalCacheDir, "doubaoTts").also { it.mkdirs() }
    }

    /**
     * 获取某本书的音频存储目录
     */
    fun getBookDir(book: Book): File {
        val folderName = MD5Utils.md5Encode16(book.bookUrl)
        return File(baseDir, folderName).also { it.mkdirs() }
    }

    /**
     * 获取某章的音频文件路径
     */
    fun getChapterAudioFile(book: Book, chapterIndex: Int): File {
        val bookDir = getBookDir(book)
        return File(bookDir, "chapter_${chapterIndex}.wav")
    }

    /**
     * 检查某章音频是否已下载
     */
    fun isChapterDownloaded(book: Book, chapterIndex: Int): Boolean {
        val file = getChapterAudioFile(book, chapterIndex)
        return file.exists() && file.length() > WAV_HEADER_SIZE
    }

    /**
     * 将章节加入下载队列
     */
    fun enqueue(book: Book, chapterIndices: List<Int>) {
        val queue = _downloadQueue.value.toMutableSet()
        for (index in chapterIndices) {
            val key = "${book.bookUrl}::$index"
            queue.add(key)
        }
        _downloadQueue.value = queue
        startProcessing(book)
    }

    /**
     * 将章节从下载队列移除
     */
    fun removeFromQueue(bookUrl: String, chapterIndex: Int) {
        val key = "$bookUrl::$chapterIndex"
        val queue = _downloadQueue.value.toMutableSet()
        queue.remove(key)
        _downloadQueue.value = queue
    }

    /**
     * 取消所有下载
     */
    fun cancelAll() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadQueue.value = emptySet()
        _downloadingChapter.value = null
        _isWorking.value = false
    }

    /**
     * 获取已下载章节索引集合
     */
    fun getDownloadedChapters(book: Book): Set<Int> {
        val bookDir = getBookDir(book)
        if (!bookDir.exists()) return emptySet()
        return bookDir.listFiles()
            ?.filter { it.name.startsWith("chapter_") && it.name.endsWith(".wav") }
            ?.mapNotNull {
                it.name.removePrefix("chapter_").removeSuffix(".wav").toIntOrNull()
            }
            ?.toSet() ?: emptySet()
    }

    /**
     * 删除某章的已下载音频
     */
    fun deleteChapterAudio(book: Book, chapterIndex: Int) {
        val file = getChapterAudioFile(book, chapterIndex)
        if (file.exists()) file.delete()
    }

    /**
     * 清除某本书的所有下载音频
     */
    fun clearBookAudio(book: Book) {
        val bookDir = getBookDir(book)
        bookDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * 获取音频文件大小
     */
    fun getAudioFileSize(book: Book, chapterIndex: Int): Long {
        val file = getChapterAudioFile(book, chapterIndex)
        return if (file.exists()) file.length() else 0L
    }

    /**
     * 获取音频文件时长（从WAV header中解析），返回毫秒
     */
    fun getAudioDuration(file: File): Long {
        if (!file.exists() || file.length() < WAV_HEADER_SIZE) return 0L
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(22)
                val channels = readLittleEndianShort(raf)
                raf.seek(24)
                val sampleRate = readLittleEndianInt(raf)
                raf.seek(28)
                val bitsPerSample = readLittleEndianShort(raf)
                raf.seek(40)
                val dataSize = readLittleEndianInt(raf)
                val bytesPerSample = (bitsPerSample / 8) * channels
                if (bytesPerSample == 0 || sampleRate == 0) return 0L
                (dataSize.toLong() * 1000) / (sampleRate.toLong() * bytesPerSample)
            }
        } catch (e: Exception) {
            0L
        }
    }

    // ==================== 内部实现 ====================

    private fun startProcessing(book: Book) {
        if (_isWorking.value) return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _isWorking.value = true
            try {
                while (isActive) {
                    val key = _downloadQueue.value.firstOrNull() ?: break
                    val parts = key.split("::")
                    if (parts.size != 2) {
                        _downloadQueue.value = _downloadQueue.value - key
                        continue
                    }
                    val bookUrl = parts[0]
                    val chapterIndex = parts[1].toIntOrNull() ?: run {
                        _downloadQueue.value = _downloadQueue.value - key
                        continue
                    }
                    processChapter(book, chapterIndex, key)
                }
            } finally {
                _isWorking.value = false
                _downloadingChapter.value = null
            }
        }
    }

    private suspend fun processChapter(book: Book, chapterIndex: Int, queueKey: String) {
        val chapterTitle = "第${chapterIndex + 1}章"
        _downloadingChapter.value = queueKey
        _progressEvent.emit(DownloadProgress(chapterIndex, 0, -1, "获取章节内容..."))

        try {
            // 1. 获取章节文本
            val text = getChapterText(book, chapterIndex)
            if (text.isNullOrBlank() || text.trim().length < 10) {
                AppLog.put("豆包TTS: 章节$chapterIndex 内容为空或过短，跳过")
                _progressEvent.emit(DownloadProgress(chapterIndex, 100, 0, "内容为空，跳过"))
                removeFromQueue(book.bookUrl, chapterIndex)
                return
            }

            // 2. 如果已下载则跳过
            if (isChapterDownloaded(book, chapterIndex)) {
                _progressEvent.emit(DownloadProgress(chapterIndex, 100, 0, "已存在"))
                removeFromQueue(book.bookUrl, chapterIndex)
                return
            }

            // 3. 获取服务地址
            val serverUrl = getServerUrl()
            if (serverUrl.isBlank()) {
                _progressEvent.emit(
                    DownloadProgress(chapterIndex, -1, 0, "请先设置豆包TTS服务地址")
                )
                removeFromQueue(book.bookUrl, chapterIndex)
                return
            }

            // 4. 按5KB分段
            val segments = splitText(text)
            val totalSegments = segments.size
            _progressEvent.emit(
                DownloadProgress(chapterIndex, 0, totalSegments, "共${totalSegments}段")
            )

            // 5. 临时目录存放分段WAV
            val bookDir = getBookDir(book)
            val tempDir = File(bookDir, "temp_$chapterIndex")
            tempDir.mkdirs()

            try {
                val wavFiles = mutableListOf<File>()

                for (i in segments.indices) {
                    ensureActive()

                    val segment = segments[i]
                    _progressEvent.emit(
                        DownloadProgress(
                            chapterIndex,
                            (i * 100) / totalSegments,
                            totalSegments,
                            "正在发送第${i + 1}/${totalSegments}段..."
                        )
                    )

                    // 5a. 发送文本
                    try {
                        DoubaoTtsClient.sendText(serverUrl, segment)
                    } catch (e: DoubaoNotReadyException) {
                        _progressEvent.emit(
                            DownloadProgress(chapterIndex, -1, totalSegments, "豆包未就绪: ${e.message}")
                        )
                        throw e
                    } catch (e: DoubaoSendException) {
                        _progressEvent.emit(
                            DownloadProgress(chapterIndex, -1, totalSegments, "发送失败: ${e.message}")
                        )
                        throw e
                    }

                    // 5b. 获取当前最新timestamp
                    val latestTs = DoubaoTtsClient.getLatestTimestamp(serverUrl)

                    // 5c. 等待新音频
                    _progressEvent.emit(
                        DownloadProgress(
                            chapterIndex,
                            (i * 100) / totalSegments,
                            totalSegments,
                            "等待第${i + 1}/${totalSegments}段朗读完成..."
                        )
                    )
                    val audioInfo = DoubaoTtsClient.waitForAudio(serverUrl, latestTs)

                    if (audioInfo == null) {
                        _progressEvent.emit(
                            DownloadProgress(chapterIndex, -1, totalSegments, "等待超时")
                        )
                        throw DoubaoSendException("等待音频超时")
                    }

                    // 5d. 下载音频
                    val tempFile = File(tempDir, "seg_${String.format("%04d", i)}.wav")
                    val downloaded = DoubaoTtsClient.downloadAudio(
                        serverUrl, audioInfo.fileName, tempFile
                    )
                    if (!downloaded || !tempFile.exists()) {
                        throw DoubaoSendException("下载音频失败: ${audioInfo.fileName}")
                    }
                    wavFiles.add(tempFile)

                    _progressEvent.emit(
                        DownloadProgress(
                            chapterIndex,
                            ((i + 1) * 100) / totalSegments,
                            totalSegments,
                            "第${i + 1}/${totalSegments}段完成"
                        )
                    )
                }

                // 6. 合并WAV
                _progressEvent.emit(
                    DownloadProgress(chapterIndex, 90, totalSegments, "合并音频中...")
                )
                val outputFile = getChapterAudioFile(book, chapterIndex)
                mergeWavFiles(wavFiles, outputFile)

                _progressEvent.emit(
                    DownloadProgress(chapterIndex, 100, totalSegments, "完成")
                )
                AppLog.put("豆包TTS: 章节$chapterIndex 下载完成")

            } finally {
                // 清理临时文件
                tempDir.listFiles()?.forEach { it.delete() }
                tempDir.delete()
            }

            removeFromQueue(book.bookUrl, chapterIndex)

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                AppLog.put("豆包TTS: 章节$chapterIndex 下载被取消")
            } else {
                AppLog.put("豆包TTS: 章节$chapterIndex 下载失败: ${e.localizedMessage}", e)
                _progressEvent.emit(
                    DownloadProgress(chapterIndex, -1, 0, "失败: ${e.localizedMessage}")
                )
            }
        }
    }

    /**
     * 获取章节纯文本内容
     */
    private suspend fun getChapterText(book: Book, chapterIndex: Int): String? {
        return try {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                ?: return null
            val content = BookHelp.getContent(book, chapter) ?: return null
            // 去除HTML标签和多余空白
            content.replace(Regex("<[^>]+>"), "")
                .replace(Regex("\\s+"), "\n")
                .trim()
        } catch (e: Exception) {
            AppLog.put("获取章节内容失败: ${e.localizedMessage}", e)
            null
        }
    }

    /**
     * 按段落边界将文本分割为不超过5KB的段
     */
    private fun splitText(text: String): List<String> {
        val segments = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.toByteArray(Charsets.UTF_8).size <= MAX_SEND_BYTES) {
                segments.add(remaining.trim())
                break
            }

            // 找到不超过5KB的最长段落边界
            var splitPos = 0
            var currentSize = 0
            val paragraphs = remaining.split("\n")

            for (i in paragraphs.indices) {
                val paraBytes = (paragraphs[i] + "\n").toByteArray(Charsets.UTF_8).size
                if (currentSize + paraBytes > MAX_SEND_BYTES) {
                    break
                }
                currentSize += paraBytes
                splitPos = i + 1
            }

            // 如果第一个段落就超过5KB，按句号切分
            if (splitPos == 0) {
                val firstPara = paragraphs[0]
                val sentences = firstPara.split(Regex("[。！？；]"))
                var senSplitPos = 0
                var senSize = 0
                for (i in sentences.indices) {
                    val sen = sentences[i] + if (i < sentences.size - 1) "。" else ""
                    val senBytes = sen.toByteArray(Charsets.UTF_8).size
                    if (senSize + senBytes > MAX_SEND_BYTES && senSplitPos > 0) {
                        break
                    }
                    senSize += senBytes
                    senSplitPos = i + 1
                }
                if (senSplitPos == 0) {
                    // 单个字符都超限，强制截断
                    var cutPos = 0
                    var cutSize = 0
                    for (i in remaining.indices) {
                        cutSize += String(remaining[i].toString().toCharArray())
                            .toByteArray(Charsets.UTF_8).size
                        if (cutSize > MAX_SEND_BYTES) break
                        cutPos = i + 1
                    }
                    segments.add(remaining.substring(0, cutPos).trim())
                    remaining = remaining.substring(cutPos)
                } else {
                    val splitText = sentences.take(senSplitPos).joinToString("") { it + "。" }
                    segments.add(splitText.trim())
                    val consumedLen = firstPara.indexOf(sentences[senSplitPos - 1])
                        .let { if (it < 0) firstPara.length else it + sentences[senSplitPos - 1].length }
                    remaining = firstPara.substring(consumedLen) +
                            if (paragraphs.size > 1) "\n" + paragraphs.drop(1).joinToString("\n") else ""
                }
            } else {
                val joined = paragraphs.take(splitPos).joinToString("\n")
                segments.add(joined.trim())
                remaining = paragraphs.drop(splitPos).joinToString("\n")
            }
        }

        return segments.filter { it.isNotBlank() }
    }

    /**
     * 合并多个WAV文件为一个
     * 所有WAV必须格式一致（相同采样率、位深、声道数）
     */
    private fun mergeWavFiles(wavFiles: List<File>, outputFile: File) {
        if (wavFiles.isEmpty()) return

        if (wavFiles.size == 1) {
            wavFiles[0].copyTo(outputFile, overwrite = true)
            return
        }

        // 读取第一个文件的header作为模板
        val firstHeader = ByteArray(WAV_HEADER_SIZE)
        FileInputStream(wavFiles[0]).use { it.read(firstHeader) }

        // 解析WAV参数
        val channels = ByteBuffer.wrap(firstHeader, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val sampleRate = ByteBuffer.wrap(firstHeader, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val bitsPerSample = ByteBuffer.wrap(firstHeader, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)

        // 计算总数据大小
        var totalDataSize = 0L
        for (file in wavFiles) {
            totalDataSize += file.length() - WAV_HEADER_SIZE
        }

        // 写入合并后的WAV
        FileOutputStream(outputFile).use { output ->
            // RIFF header
            output.write("RIFF".toByteArray())
            writeLittleEndianInt(output, (36 + totalDataSize).toInt())
            output.write("WAVE".toByteArray())

            // fmt sub-chunk
            output.write("fmt ".toByteArray())
            writeLittleEndianInt(output, 16) // sub-chunk size
            writeLittleEndianShort(output, 1) // PCM format
            writeLittleEndianShort(output, channels)
            writeLittleEndianInt(output, sampleRate)
            writeLittleEndianInt(output, byteRate)
            writeLittleEndianShort(output, blockAlign)
            writeLittleEndianShort(output, bitsPerSample)

            // data sub-chunk
            output.write("data".toByteArray())
            writeLittleEndianInt(output, totalDataSize.toInt())

            // 追加所有文件的音频数据（跳过header）
            val buffer = ByteArray(8192)
            for (file in wavFiles) {
                FileInputStream(file).use { input ->
                    input.skip(WAV_HEADER_SIZE.toLong())
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun writeLittleEndianInt(output: FileOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 24) and 0xFF)
    }

    private fun writeLittleEndianShort(output: FileOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }

    private fun readLittleEndianShort(raf: RandomAccessFile): Int {
        val b0 = raf.readByte().toInt() and 0xFF
        val b1 = raf.readByte().toInt() and 0xFF
        return b0 or (b1 shl 8)
    }

    private fun readLittleEndianInt(raf: RandomAccessFile): Int {
        val b0 = raf.readByte().toInt() and 0xFF
        val b1 = raf.readByte().toInt() and 0xFF
        val b2 = raf.readByte().toInt() and 0xFF
        val b3 = raf.readByte().toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun getServerUrl(): String {
        return io.legado.app.help.config.AppConfig.doubaoServerUrl
    }

    /**
     * 下载进度信息
     */
    data class DownloadProgress(
        val chapterIndex: Int,
        val percent: Int,       // -1 表示失败，0-100 表示进度
        val totalSegments: Int, // 总分段数，-1 表示未知
        val message: String
    )
}
