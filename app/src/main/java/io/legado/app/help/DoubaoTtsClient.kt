package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 豆包 TTS HTTP 客户端
 * 负责与豆包 TTS 服务端通信：发送文本、等待音频、下载音频
 */
object DoubaoTtsClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 发送文本到豆包
     * @param serverUrl 服务地址，如 http://192.168.0.103:9527
     * @param text 要朗读的文本
     * @return true 表示发送成功
     */
    suspend fun sendText(serverUrl: String, text: String): Boolean {
        return withContext(Dispatchers.IO) {
            val json = JSONObject().put("message", text)
            val body = json.toString().toByteArray(Charsets.UTF_8)
                .toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$serverUrl/send")
                .post(body)
                .build()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val respBody = response.body?.string() ?: ""
                            LogUtils.d("DoubaoTtsClient", "sendText ok: $respBody")
                            true
                        }
                        503 -> {
                            AppLog.put("豆包TTS: 不在聊天页面")
                            throw DoubaoNotReadyException("豆包不在聊天页面")
                        }
                        else -> {
                            val msg = "豆包TTS发送失败: HTTP ${response.code}"
                            AppLog.put(msg)
                            throw DoubaoSendException(msg)
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                throw DoubaoSendException("连接超时，请检查豆包服务是否运行")
            } catch (e: DoubaoNotReadyException) {
                throw e
            } catch (e: Exception) {
                if (e is DoubaoSendException) throw e
                throw DoubaoSendException("发送失败: ${e.localizedMessage}")
            }
        }
    }

    /**
     * 等待新音频生成（长轮询）
     * @param serverUrl 服务地址
     * @param afterTimestamp 上次已知文件的timestamp，0表示从头开始
     * @return 音频文件信息 (fileName, size, timestamp)
     */
    suspend fun waitForAudio(
        serverUrl: String,
        afterTimestamp: Long
    ): AudioFileInfo? {
        return withContext(Dispatchers.IO) {
            withTimeout(320_000) { // 超时时间略大于服务端300秒
                val request = Request.Builder()
                    .url("$serverUrl/audio/wait?after=$afterTimestamp")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    if (json.optString("status") == "timeout") {
                        return@withContext null
                    }
                    val fileName = json.getString("file")
                    val size = json.getLong("size")
                    val timestamp = json.getLong("timestamp")
                    AudioFileInfo(fileName, size, timestamp)
                }
            }
        }
    }

    /**
     * 获取已生成的音频文件列表
     * @return 最新文件的timestamp，如果没有文件返回0
     */
    suspend fun getLatestTimestamp(serverUrl: String): Long {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/audio/list")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@withContext 0L
                    val json = JSONObject(body)
                    val files = json.getJSONArray("files")
                    if (files.length() == 0) return@withContext 0L
                    files.getJSONObject(files.length() - 1).getLong("timestamp")
                }
            } catch (e: Exception) {
                LogUtils.d("DoubaoTtsClient", "获取音频列表失败: ${e.localizedMessage}")
                0L
            }
        }
    }

    /**
     * 下载音频文件
     * @param serverUrl 服务地址
     * @param fileName 文件名
     * @param destFile 目标文件
     * @return true 表示下载成功
     */
    suspend fun downloadAudio(
        serverUrl: String,
        fileName: String,
        destFile: File
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$serverUrl/audio/download/$fileName")
                .get()
                .build()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.code != 200) {
                        AppLog.put("下载音频失败: HTTP ${response.code}")
                        return@withContext false
                    }
                    val body = response.body ?: return@withContext false
                    FileOutputStream(destFile).use { output ->
                        body.byteStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    true
                }
            } catch (e: Exception) {
                AppLog.put("下载音频异常: ${e.localizedMessage}", e)
                false
            }
        }
    }

    /**
     * 检查服务是否在线
     */
    suspend fun checkServerStatus(serverUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/status")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    data class AudioFileInfo(
        val fileName: String,
        val size: Long,
        val timestamp: Long
    )
}

class DoubaoNotReadyException(message: String) : Exception(message)
class DoubaoSendException(message: String) : Exception(message)
