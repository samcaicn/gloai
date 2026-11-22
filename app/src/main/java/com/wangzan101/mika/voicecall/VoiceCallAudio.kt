package com.wangzan101.mika.voicecall

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.wangzan101.mika.calls.VolcRealtimeClient
import com.wangzan101.mika.voice.PcmRecorder
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 通话音频设备的全部抽象:采集、播放、音频焦点与通话模式。
 * 编排层只依赖本接口,测试用手写 fake,生产实现是 [AndroidVoiceCallAudio]。
 */
interface VoiceCallAudio {
    /** 系统把麦克风静音(被别的组件占用等)时置 true。 */
    val micSilenced: StateFlow<Boolean>

    /** 采集块流:16kHz 单声道 PCM16,约 20ms 一块;采集停止或出错时 channel 关闭。 */
    val captureChunks: ReceiveChannel<ShortArray>

    /** 音频焦点被抢时回调(任意线程);编排层据此结束通话。 */
    var onFocusLost: (() -> Unit)?

    /** 电话功能忙(通话中或响铃)时不允许开始语音通话。 */
    fun isTelephonyBusy(): Boolean

    /** 进入通话音频模式并申请音频焦点;失败返回 false。 */
    fun requestCallAudio(): Boolean

    /** 放弃音频焦点并恢复普通音频模式;可重复调用。 */
    fun abandonCallAudio()

    /**
     * 以指定音源([android.media.MediaRecorder.AudioSource])开始采集;初始化失败返回 false,
     * 是否换音源重试由编排层决定(回退链不放这里)。
     */
    fun startCapture(inputSource: Int): Boolean

    /** 停止采集并释放麦克风;可重复调用。 */
    fun stopCapture()

    /** 建好并启动 24kHz 单声道 PCM16 播放轨;失败返回 false。 */
    fun startPlayback(): Boolean

    /** 阻塞写一整块 24kHz 单声道 PCM16;轨死了抛 [IOException]。 */
    suspend fun writePlayback(pcm: ByteArray, byteCount: Int)

    /** barge-in:pause/flush/play,把已在硬件缓冲里的旧音频立刻丢掉。 */
    fun interruptPlayback()

    /** 停止并释放播放轨;可重复调用。 */
    fun stopPlayback()
}

/** 生产实现:AudioRecord(经 [PcmRecorder])、AudioTrack 与 AudioManager 的直接封装,无任何策略。 */
@SuppressLint("MissingPermission") // RECORD_AUDIO 由通话面板在启动会话前请求
class AndroidVoiceCallAudio(context: Context) : VoiceCallAudio {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val _micSilenced = MutableStateFlow(false)
    override val micSilenced: StateFlow<Boolean> = _micSilenced
    private val noCapture = Channel<ShortArray>(Channel.RENDEZVOUS).also { it.close() }
    override val captureChunks: ReceiveChannel<ShortArray>
        get() = recorder?.chunks ?: noCapture
    @Volatile override var onFocusLost: (() -> Unit)? = null

    private var recorder: PcmRecorder? = null
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private val silencedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var silencedJob: Job? = null

    override fun isTelephonyBusy(): Boolean {
        val mode = manager.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
    }

    override fun requestCallAudio(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    onFocusLost?.invoke()
                }
            }
            .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focusRequest = request
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        return true
    }

    override fun abandonCallAudio() {
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
        manager.mode = AudioManager.MODE_NORMAL
    }

    override fun startCapture(inputSource: Int): Boolean {
        val next = PcmRecorder(streaming = true, unbounded = true, inputSource = inputSource, chunkMs = 20)
        if (!next.start()) return false
        recorder = next
        _micSilenced.value = false
        silencedJob = silencedScope.launch { next.silenced.collect { _micSilenced.value = it } }
        return true
    }

    override fun stopCapture() {
        silencedJob?.cancel()
        silencedJob = null
        recorder?.cancel()
        recorder = null
        _micSilenced.value = false
    }

    override fun startPlayback(): Boolean {
        val minBuffer = AudioTrack.getMinBufferSize(
            VolcRealtimeClient.OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(VolcRealtimeClient.OUTPUT_SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val next = try {
            AudioTrack(attributes, format, maxOf(minBuffer, WRITE_BUFFER_BYTES), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (next.state != AudioTrack.STATE_INITIALIZED) {
            next.release()
            return false
        }
        return try {
            next.play()
            track = next
            true
        } catch (_: IllegalStateException) {
            next.release()
            false
        }
    }

    override suspend fun writePlayback(pcm: ByteArray, byteCount: Int) {
        val output = track ?: throw IOException("Voice call playback is not started")
        var written = 0
        while (written < byteCount) {
            currentCoroutineContext().ensureActive()
            when (val n = output.write(pcm, written, byteCount - written, AudioTrack.WRITE_NON_BLOCKING)) {
                0 -> delay(2)
                in 1..Int.MAX_VALUE -> written += n
                else -> throw IOException("Voice call playback write failed ($n)")
            }
        }
    }

    override fun interruptPlayback() {
        val output = track ?: return
        try {
            output.pause()
        } catch (_: IllegalStateException) {
            return
        }
        output.flush()
        try {
            output.play()
        } catch (_: IllegalStateException) {
            // 下一次 writePlayback 会以轨状态错误失败,由编排层归类为播放错误
        }
    }

    override fun stopPlayback() {
        track?.let { output ->
            try {
                output.pause()
                output.flush()
            } catch (_: IllegalStateException) {
            }
            output.release()
        }
        track = null
    }

    private companion object {
        const val WRITE_BUFFER_BYTES = VolcRealtimeClient.OUTPUT_SAMPLE_RATE * 2 / 25 // 40ms
    }
}
