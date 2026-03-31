package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.model.DoubaoDownloadManager
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

/**
 * 豆包TTS音频播放服务
 * 播放已下载到本地的WAV音频文件，不进行文字同步
 */
@SuppressLint("UnsafeOptInUsageError")
class DoubaoAudioService : BaseReadAloudService(), Player.Listener {

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.removeListener(this)
        exoPlayer.release()
    }

    override fun play() {
        exoPlayer.stop()
        if (!requestFocus()) return
        val book = ReadBook.book ?: run {
            toastOnUi("未打开书籍")
            stopSelf()
            return
        }
        val chapterIndex = ReadBook.durChapterIndex
        val wavFile = DoubaoDownloadManager.getChapterAudioFile(book, chapterIndex)
        if (wavFile.exists() && wavFile.length() > 44) {
            super.play()
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(wavFile)))
            exoPlayer.prepare()
        } else {
            toastOnUi("第${chapterIndex + 1}章音频未下载")
            pauseReadAloud()
        }
    }

    override fun playStop() {
        exoPlayer.stop()
    }

    override fun upSpeechRate(reset: Boolean) {
        // 豆包TTS不支持客户端调速，no-op
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        exoPlayer.pause()
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        exoPlayer.play()
    }

    override fun nextChapter() {
        exoPlayer.stop()
        super.nextChapter()
    }

    override fun prevChapter() {
        exoPlayer.stop()
        super.prevChapter()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<DoubaoAudioService>(actionStr)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                if (!pause) {
                    exoPlayer.play()
                }
            }
            Player.STATE_ENDED -> {
                // 当前章节播放完毕，自动播放下一章
                lifecycleScope.launch {
                    ReadBook.upReadTime()
                    AppLog.putDebug("${ReadBook.curTextChapter?.chapter?.title} 豆包TTS播放结束")
                    val moved = ReadBook.moveToNextChapter(true)
                    if (!moved) {
                        AppLog.putDebug("豆包TTS: 最后一章播放完毕")
                        stopSelf()
                    }
                    // moveToNextChapter 内部会通过 EventBus 触发 newReadAloud -> play()
                }
            }
        }
    }
}
