package com.wangzan101.mika.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Mika 此刻的朗读状态。 */
sealed interface VoicePlayback {
    data object Idle : VoicePlayback
    data object Loading : VoicePlayback
    data object Playing : VoicePlayback
    data class Error(val message: String) : VoicePlayback
}

/**
 * 本机朗读:Mika 的 TTS 音频先写缓存文件,再用 MediaPlayer 播;同一时间只播一条,新的替换旧的。
 * 申请系统音频焦点(语音内容、短暂独占),来电或别的 App 出声时暂停/降低,播放结束、出错或被停下来时释放。
 */
class VoicePlayer(private val context: Context) {
    private val _state = MutableStateFlow<VoicePlayback>(VoicePlayback.Idle)
    val state: StateFlow<VoicePlayback> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var file: File? = null
    private var focus: AudioFocusRequest? = null
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 播一段合成好的音频;格式从 [mime] 猜扩展名,MediaPlayer 按文件内容解码。 */
    suspend fun play(bytes: ByteArray, mime: String) = withContext(Dispatchers.Main) {
        stop()
        _state.value = VoicePlayback.Loading
        val f = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "voice").apply { mkdirs() }
            File(dir, "tts-${System.nanoTime()}.${extensionOf(mime)}").apply { writeBytes(bytes) }
        }
        file = f
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(f.absolutePath)
                setOnPreparedListener {
                    if (requestFocus()) {
                        _state.value = VoicePlayback.Playing
                        it.start()
                    } else {
                        release(VoicePlayback.Error("audio focus is not available"))
                    }
                }
                setOnCompletionListener { release(VoicePlayback.Idle) }
                setOnErrorListener { _, what, extra ->
                    release(VoicePlayback.Error("player error $what/$extra"))
                    true
                }
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            release(VoicePlayback.Error(e.message ?: "cannot play audio"))
        }
    }

    /** 停下来并释放。 */
    fun stop() = release(VoicePlayback.Idle)

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                val mp = player ?: return@setOnAudioFocusChangeListener
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (mp.isPlaying) mp.pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp.setVolume(0.2f, 0.2f)
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        mp.setVolume(1f, 1f)
                        if (!mp.isPlaying) runCatching { mp.start() }
                    }
                }
            }
            .build()
        focus = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun release(state: VoicePlayback) {
        runCatching { player?.release() }
        player = null
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
        file?.delete()
        file = null
        _state.value = state
    }

    private fun extensionOf(mime: String): String = when (mime.substringAfter('/', "").lowercase()) {
        "mpeg", "mp3" -> "mp3"
        "ogg", "opus" -> "ogg"
        "aac" -> "aac"
        "flac" -> "flac"
        "wav", "x-wav" -> "wav"
        else -> "wav"
    }
}
