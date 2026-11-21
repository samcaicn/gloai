package com.wangzan101.mika.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.wangzan101.mika.core.model.StreamingTtsModel
import com.wangzan101.mika.core.model.TtsAudioPart
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
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
 * 本机朗读。流式 PCM 数据到达后送入 AudioTrack，已合成的音频写缓存文件交给 MediaPlayer。
 * 同一时间只播一条,新的替换旧的:每次开播领一个代次,prepare 在路上的回调、旧播放器上的回调一律不落地。
 * 申请系统音频焦点(语音内容、短暂独占),来电或别的 App 出声时暂停/降低,播放结束、出错或被停下来时释放。
 */
class VoicePlayer(private val context: Context) {
    private val _state = MutableStateFlow<VoicePlayback>(VoicePlayback.Idle)
    val state: StateFlow<VoicePlayback> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var pcmTrack: PcmAudioTrack? = null
    private var pendingPlayback: Job? = null
    private var file: File? = null
    private var focus: AudioFocusRequest? = null
    private val tickets = PlaybackTickets()
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 播一段合成好的音频;格式从 [mime] 猜扩展名,MediaPlayer 按文件内容解码。 */
    suspend fun play(bytes: ByteArray, mime: String) = loadAndPlay { ticket ->
        prepareFile(ticket, bytes, mime)
    }

    /** 在服务支持时边合成边播，其他服务或语速继续使用完整音频响应。 */
    suspend fun play(
        model: TtsModel,
        request: TtsRequest,
        streaming: Boolean,
        onStarted: () -> Unit = {},
    ) = playRequests(model, listOf(request), streaming, onStarted)

    /** 按顺序朗读同一条回答的各段。 */
    internal suspend fun playRequests(
        model: TtsModel,
        requests: List<TtsRequest>,
        streaming: Boolean,
        onStarted: () -> Unit = {},
    ) = loadAndPlay { ticket ->
        val fallback = if (streaming && model is StreamingTtsModel) {
            val response = playStreams(ticket, model, requests, onStarted)
            drainPcm(if (response == null) VoicePlayback.Idle else VoicePlayback.Loading)
            response ?: return@loadAndPlay
        } else {
            null
        }
        // 回退后逐段合成，等当前文件播完再取下一段。
        for (index in (fallback?.index ?: 0)..requests.lastIndex) {
            val audio = if (fallback?.index == index) fallback.response else model.synthesize(requests[index])
            _state.value = VoicePlayback.Loading
            val completion = if (index < requests.lastIndex) CompletableDeferred<VoicePlayback>() else null
            prepareFile(ticket, audio.bytes, audio.mime, { if (index == 0) onStarted() }, completion)
            val result = completion?.await()
            if (result is VoicePlayback.Error) error(result.message)
        }
    }

    private suspend fun loadAndPlay(block: suspend (Long) -> Unit) = withContext(Dispatchers.Main.immediate) {
        stop()
        // 先领代次再挂起:合成/写文件期间来了新请求,本次代次即作废,后续回调一律不落地
        val ticket = tickets.begin()
        val job = currentCoroutineContext().job
        pendingPlayback = job
        _state.value = VoicePlayback.Loading
        try {
            block(ticket)
        } catch (e: CancellationException) {
            if (pendingPlayback === job) release(VoicePlayback.Idle)
            throw e
        } catch (e: Exception) {
            if (pendingPlayback === job) release(VoicePlayback.Error(e.message ?: "cannot play audio"))
            throw e
        } finally {
            if (pendingPlayback === job) pendingPlayback = null
        }
    }

    private sealed interface StreamEvent {
        data class Pcm(val index: Int, val part: TtsAudioPart.Pcm) : StreamEvent
        data class Fallback(val index: Int, val response: TtsResponse) : StreamEvent
        data class Failed(val error: Exception) : StreamEvent
    }

    private suspend fun playStreams(
        ticket: Long,
        model: StreamingTtsModel,
        requests: List<TtsRequest>,
        onStarted: () -> Unit,
    ): StreamEvent.Fallback? {
        var playingIndex = -1
        var fallback: StreamEvent.Fallback? = null
        streamRequests(model, requests).collect { event ->
            when (event) {
                is StreamEvent.Pcm -> {
                    val part = event.part
                    val current = pcmTrack
                    if (event.index != playingIndex && current != null) {
                        if (current.sampleRate == part.sampleRate && current.channels == part.channels) {
                            current.writeSilence(SEGMENT_PAUSE_MS)
                        } else {
                            drainPcm(VoicePlayback.Loading)
                        }
                    }
                    playPcmChunk(ticket, part) { if (event.index == 0) onStarted() }
                    playingIndex = event.index
                }
                is StreamEvent.Fallback -> fallback = event
                is StreamEvent.Failed -> {
                    pcmTrack?.drain()
                    throw event.error
                }
            }
        }
        return fallback
    }

    private fun streamRequests(model: StreamingTtsModel, requests: List<TtsRequest>): Flow<StreamEvent> =
        flow<StreamEvent> {
            for ((index, request) in requests.withIndex()) {
                var complete: TtsResponse? = null
                var receivedPcm = false
                model.streamAudio(request).collect { part ->
                    check(complete == null) { "unexpected audio after a complete response" }
                    when (part) {
                        is TtsAudioPart.Pcm -> {
                            emit(StreamEvent.Pcm(index, part))
                            receivedPcm = true
                        }
                        is TtsAudioPart.Complete -> {
                            check(!receivedPcm) { "unexpected complete audio after PCM" }
                            complete = part.response
                        }
                    }
                }
                val response = complete
                check(receivedPcm || response != null) { "empty audio stream" }
                if (response != null) {
                    emit(StreamEvent.Fallback(index, response))
                    return@flow
                }
            }
        }.catch { error ->
            // 播完已经收到的 PCM，再报告后续合成失败。
            if (error is CancellationException || error !is Exception) throw error
            emit(StreamEvent.Failed(error))
        }.buffer(PCM_LOOKAHEAD_CHUNKS)

    private suspend fun drainPcm(nextState: VoicePlayback = VoicePlayback.Idle) {
        val audio = pcmTrack ?: return
        audio.drain()
        // 只收 PCM 资源:流式会话可能还在继续(格式变化/回退到整段),代次不能作废
        pcmTrack = null
        runCatching { audio.close() }
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
        _state.value = nextState
    }

    private suspend fun playPcmChunk(ticket: Long, chunk: TtsAudioPart.Pcm, onStarted: () -> Unit) {
        val first = pcmTrack == null
        val audio = pcmTrack ?: PcmAudioTrack(attributes, chunk.sampleRate, chunk.channels).also { track ->
            pcmTrack = track
            check(requestFocus { change ->
                // 代次作废或换了新轨道后,旧轨道上的焦点回调一律忽略
                if (pcmTrack === track && tickets.owns(ticket)) {
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> stop()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> track.pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> track.setVolume(0.2f)
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            track.setVolume(1f)
                            track.resume()
                        }
                    }
                }
            }) { "audio focus is not available" }
            track.resume()
        }
        check(audio.sampleRate == chunk.sampleRate && audio.channels == chunk.channels) { "PCM format changed during playback" }
        audio.write(chunk.bytes)
        if (first) {
            _state.value = VoicePlayback.Playing
            onStarted()
        }
    }

    private suspend fun prepareFile(
        ticket: Long,
        bytes: ByteArray,
        mime: String,
        onStarted: () -> Unit = {},
        completion: CompletableDeferred<VoicePlayback>? = null,
    ) {
        fun finishSegment(result: VoicePlayback) {
            release(if (completion != null && result == VoicePlayback.Idle) VoicePlayback.Loading else result)
            completion?.complete(result)
        }

        val dir = File(context.cacheDir, "voice")
        val f = File(dir, "tts-${System.nanoTime()}.${extensionOf(mime)}")
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                f.writeBytes(bytes)
            }
            // 写文件期间来了新请求:本次代次作废,不建播放器、不碰状态(文件由 finally 清掉)
            if (!tickets.owns(ticket)) return
            file = f
            val mp = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(f.absolutePath)
                setOnPreparedListener { prepared ->
                    if (!tickets.owns(ticket) || player !== prepared) return@setOnPreparedListener
                    if (requestFocus(ticket, prepared)) {
                        _state.value = VoicePlayback.Playing
                        onStarted()
                        prepared.start()
                    } else {
                        finishSegment(VoicePlayback.Error("audio focus is not available"))
                    }
                }
                setOnCompletionListener { if (tickets.owns(ticket) && player === it) finishSegment(VoicePlayback.Idle) }
                setOnErrorListener { failed, what, extra ->
                    if (tickets.owns(ticket) && player === failed) finishSegment(VoicePlayback.Error("player error $what/$extra"))
                    true
                }
            }
            player = mp
            mp.prepareAsync()
        } catch (e: CancellationException) {
            if (tickets.owns(ticket)) release(VoicePlayback.Idle)
            throw e
        } catch (e: Exception) {
            // 代次已作废的旧请求出错时静默收尾,不盖掉新请求的状态
            if (tickets.owns(ticket)) finishSegment(VoicePlayback.Error(e.message ?: "cannot play audio"))
        } finally {
            // 没落地的临时文件(被新请求顶替、中途取消)不留盘上
            if (file !== f) f.delete()
        }
    }

    /** 停下来并释放。 */
    fun stop() = finish(VoicePlayback.Idle)

    fun fail(message: String) = finish(VoicePlayback.Error(message))

    private fun requestFocus(ticket: Long, mp: MediaPlayer): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                // 焦点回调挂在旧播放器上时,代次和实例都对不上,直接忽略
                if (!tickets.owns(ticket) || player !== mp) return@setOnAudioFocusChangeListener
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

    private fun requestFocus(onFocusChange: (Int) -> Unit): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { onFocusChange(it) }
            .build()
        focus = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun finish(state: VoicePlayback) {
        val job = pendingPlayback
        pendingPlayback = null
        job?.cancel()
        release(state)
    }

    private fun release(state: VoicePlayback) {
        // 作废当前代次:prepare 在路上的回调、旧播放器上的回调此后一律丢弃
        tickets.invalidate()
        val oldPlayer = player
        player = null
        runCatching { oldPlayer?.release() }
        runCatching { pcmTrack?.close() }
        pcmTrack = null
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

    private companion object {
        // 每块最多 8 KiB，44.1 kHz 单声道大约可预读 2.2 秒。
        const val PCM_LOOKAHEAD_CHUNKS = 24
        const val SEGMENT_PAUSE_MS = 90
    }
}
