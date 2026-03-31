package io.legado.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.widget.Toast
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.model.DoubaoDownloadManager
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * 豆包TTS ADB命令接收器
 * 通过 adb shell am broadcast 直接触发豆包TTS相关功能
 *
 * 注意: 由于 Android 8+ 限制静态注册的 BroadcastReceiver 接收隐式广播，
 * 此 Receiver 需要在 App.onCreate() 中通过 registerReceiver() 动态注册。
 */
class DoubaoCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DoubaoCmdReceiver"

        const val ACTION_SET_URL = "io.legado.app.DOUBAO_SET_URL"
        const val ACTION_ENABLE = "io.legado.app.DOUBAO_ENABLE"
        const val ACTION_DOWNLOAD = "io.legado.app.DOUBAO_DOWNLOAD"
        const val ACTION_STATUS = "io.legado.app.DOUBAO_STATUS"
        const val ACTION_PLAY = "io.legado.app.DOUBAO_PLAY"
        const val ACTION_PAUSE = "io.legado.app.DOUBAO_PAUSE"
        const val ACTION_STOP = "io.legado.app.DOUBAO_STOP"
        const val ACTION_CANCEL = "io.legado.app.DOUBAO_CANCEL"

        private val scope = CoroutineScope(Dispatchers.IO)
        private var registered = false

        /**
         * 动态注册 Receiver，在 App.onCreate() 中调用
         */
        fun register() {
            if (registered) return
            try {
                val filter = IntentFilter().apply {
                    addAction(ACTION_SET_URL)
                    addAction(ACTION_ENABLE)
                    addAction(ACTION_DOWNLOAD)
                    addAction(ACTION_STATUS)
                    addAction(ACTION_PLAY)
                    addAction(ACTION_PAUSE)
                    addAction(ACTION_STOP)
                    addAction(ACTION_CANCEL)
                }
                appCtx.registerReceiver(DoubaoCommandReceiver(), filter)
                registered = true
                Log.d(TAG, "DoubaoCommandReceiver registered dynamically")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register receiver", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive called: action=${intent.action}")
        AppLog.put("豆包TTS Receiver收到广播: ${intent.action}")

        when (intent.action) {
            ACTION_SET_URL -> handleSetUrl(context, intent)
            ACTION_ENABLE -> handleEnable(context, intent)
            ACTION_DOWNLOAD -> handleDownload(context, intent)
            ACTION_STATUS -> handleStatus(context)
            ACTION_PLAY -> handlePlay(context)
            ACTION_PAUSE -> handlePause(context)
            ACTION_STOP -> handleStop(context)
            ACTION_CANCEL -> handleCancel(context)
        }
    }

    private fun handleSetUrl(context: Context, intent: Intent) {
        val url = intent.getStringExtra("url")
        if (url.isNullOrBlank()) {
            showToast(context, "缺少参数: url")
            return
        }
        AppConfig.doubaoServerUrl = url.trimEnd('/')
        val currentUrl = AppConfig.doubaoServerUrl
        Log.d(TAG, "URL set to: $currentUrl")
        AppLog.put("豆包TTS地址已设置: $currentUrl")
        showToast(context, "豆包TTS地址已设置: $currentUrl")
    }

    private fun handleEnable(context: Context, intent: Intent) {
        val enabled = intent.getBooleanExtra("enabled", true)
        AppConfig.doubaoTtsEnabled = enabled
        if (enabled) {
            ReadAloud.upReadAloudClass()
        }
        Log.d(TAG, "TTS enabled: $enabled")
        AppLog.put("豆包TTS ${if (enabled) "开启" else "关闭"}")
        showToast(context, "豆包TTS已${if (enabled) "开启" else "关闭"}")
    }

    private fun handleDownload(context: Context, intent: Intent) {
        val bookName = intent.getStringExtra("bookName")
        val bookAuthor = intent.getStringExtra("bookAuthor")
        val chapterStart = intent.getIntExtra("chapterStart", 0)
        val chapterEnd = intent.getIntExtra("chapterEnd", -1)

        if (bookName.isNullOrBlank()) {
            showToast(context, "缺少参数: bookName")
            return
        }
        if (bookAuthor.isNullOrBlank()) {
            showToast(context, "缺少参数: bookAuthor")
            return
        }

        if (!AppConfig.doubaoTtsEnabled) {
            showToast(context, "豆包TTS未开启，请先执行 DOUBAO_ENABLE")
            return
        }
        if (AppConfig.doubaoServerUrl.isBlank()) {
            showToast(context, "豆包TTS地址未设置，请先执行 DOUBAO_SET_URL")
            return
        }

        scope.launch {
            try {
                val book = appDb.bookDao.getBook(bookName, bookAuthor)
                if (book == null) {
                    Log.e(TAG, "Book not found: $bookName / $bookAuthor")
                    AppLog.put("豆包TTS: 未找到书籍 $bookName / $bookAuthor")
                    showToast(context, "未找到书籍: $bookName ($bookAuthor)")
                    return@launch
                }

                val totalChapters = appDb.bookChapterDao.getChapterCount(book.bookUrl)
                val end = if (chapterEnd < 0 || chapterEnd >= totalChapters) totalChapters - 1 else chapterEnd
                val start = if (chapterStart < 0) 0 else chapterStart

                if (start > end) {
                    showToast(context, "章节范围无效: $start > $end")
                    return@launch
                }

                val indices = (start..end).toList()
                Log.d(TAG, "Download: ${book.name}, chapters $start..$end (${indices.size} total)")
                AppLog.put("豆包TTS: 开始下载 ${book.name} 章节 $start..$end")

                val toDownload = indices.filter { !DoubaoDownloadManager.isChapterDownloaded(book, it) }
                if (toDownload.isEmpty()) {
                    showToast(context, "所有指定章节已下载")
                    return@launch
                }

                showToast(context, "开始下载 ${toDownload.size} 章: $start..$end")
                DoubaoDownloadManager.enqueue(book, toDownload)

            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                AppLog.put("豆包TTS下载异常: ${e.localizedMessage}", e)
                showToast(context, "下载异常: ${e.localizedMessage}")
            }
        }
    }

    private fun handleStatus(context: Context) {
        scope.launch {
            val sb = StringBuilder()
            sb.append("豆包TTS状态:\n")
            sb.append("  开关: ${if (AppConfig.doubaoTtsEnabled) "开启" else "关闭"}\n")
            sb.append("  地址: ${AppConfig.doubaoServerUrl.ifBlank { "未设置" }}\n")
            sb.append("  下载中: ${DoubaoDownloadManager.isWorking.value}\n")
            sb.append("  队列: ${DoubaoDownloadManager.downloadQueue.value.size}章\n")

            val book = ReadBook.book
            if (book != null) {
                val downloaded = DoubaoDownloadManager.getDownloadedChapters(book)
                val total = appDb.bookChapterDao.getChapterCount(book.bookUrl)
                sb.append("  当前书: ${book.name}\n")
                sb.append("  已下载: ${downloaded.size}/$total 章\n")
            } else {
                sb.append("  当前书: 未打开")
            }

            val msg = sb.toString()
            Log.d(TAG, msg)
            AppLog.put(msg)
            showToast(context, msg, Toast.LENGTH_LONG)
        }
    }

    private fun handlePlay(context: Context) {
        if (!AppConfig.doubaoTtsEnabled) {
            showToast(context, "豆包TTS未开启")
            return
        }
        ReadAloud.upReadAloudClass()
        val book = ReadBook.book
        if (book == null) {
            showToast(context, "当前未打开书籍")
            return
        }
        if (!DoubaoDownloadManager.isChapterDownloaded(book, ReadBook.durChapterIndex)) {
            showToast(context, "当前章节音频未下载")
            return
        }
        ReadAloud.play(context)
        showToast(context, "开始播放")
    }

    private fun handlePause(context: Context) {
        if (BaseReadAloudService.isRun) {
            ReadAloud.pause(context)
            showToast(context, "已暂停")
        } else {
            showToast(context, "当前未在播放")
        }
    }

    private fun handleStop(context: Context) {
        if (BaseReadAloudService.isRun) {
            ReadAloud.stop(context)
            showToast(context, "已停止")
        } else {
            showToast(context, "当前未在播放")
        }
    }

    private fun handleCancel(context: Context) {
        DoubaoDownloadManager.cancelAll()
        showToast(context, "已取消所有下载")
    }

    private fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        Log.d(TAG, "Toast: $message")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, duration).show()
        }
    }
}
