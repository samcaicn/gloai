package com.wangzan101.mika.voice.cascade

import com.wangzan101.mika.voice.Pcm16
import com.wangzan101.mika.voice.StreamingRecorder
import com.wangzan101.mika.voice.Wav
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A recorder whose audio is pushed in from outside (a call's PCM) instead of read from the microphone, so every
 * speech-to-text session (upload or streaming) can listen to a call. Samples are 16 kHz mono ([Wav.RATE]).
 * Streaming sessions read [chunks]; upload sessions get everything fed so far from [stop] (capped at [maxMs]).
 */
class FeedRecorder(private val maxMs: Int = 60_000) : StreamingRecorder {
    private val audio = Channel<ShortArray>(Channel.UNLIMITED)
    override val chunks: ReceiveChannel<ShortArray> = audio
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    private val lock = Any()
    private var started = false
    private var stopped = false
    private var pcm = ShortArray(Wav.RATE * 2)
    private var size = 0

    override fun start(): Boolean = synchronized(lock) {
        if (started || stopped) return false
        started = true
        true
    }

    /** Pushes 16 kHz mono samples; audio fed before [start] is kept (the session may start a moment later), ignored after [stop]/[cancel]. */
    fun feed(samples: ShortArray) {
        if (samples.isEmpty()) return
        synchronized(lock) {
            if (stopped) return
            val cap = Wav.RATE * maxMs / 1000
            val take = minOf(samples.size, cap - size)
            if (take > 0) {
                if (size + take > pcm.size) pcm = pcm.copyOf(minOf(cap, maxOf(size + take, pcm.size * 2)))
                System.arraycopy(samples, 0, pcm, size, take)
                size += take
            }
            _level.value = (Pcm16.level(samples) * 4f).coerceIn(0f, 1f)
            audio.trySend(samples.copyOf())
        }
    }

    override fun stop(): ShortArray = synchronized(lock) {
        stopped = true
        audio.close()
        _level.value = 0f
        pcm.copyOf(size)
    }

    override fun cancel() {
        synchronized(lock) {
            stopped = true
            size = 0
            _level.value = 0f
            audio.cancel()
        }
    }
}
