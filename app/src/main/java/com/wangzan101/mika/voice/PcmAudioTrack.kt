package com.wangzan101.mika.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Writes PCM16LE with backpressure and drains the final frames before release. */
internal class PcmAudioTrack(
    attributes: AudioAttributes,
    val sampleRate: Int,
    val channels: Int,
) {
    private val lock = Any()
    private val frameBytes = channels * Short.SIZE_BYTES
    private var framesWritten = 0L
    private val startFrames: Int
    private var track: AudioTrack?

    init {
        val channelMask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("unsupported PCM channel count: $channels")
        }
        val minBufferBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        check(minBufferBytes > 0) { "unsupported PCM format: $sampleRate Hz, $channels channels" }
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(minBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            check(audioTrack.state == AudioTrack.STATE_INITIALIZED) { "cannot initialize PCM playback" }
            startFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val target = (sampleRate / 25).coerceIn(1, audioTrack.bufferSizeInFrames)
                audioTrack.setStartThresholdInFrames(target)
            } else {
                audioTrack.bufferSizeInFrames
            }
        } catch (e: Exception) {
            audioTrack.release()
            throw e
        }
        track = audioTrack
    }

    fun pause() = synchronized(lock) { track?.pause() }

    fun resume() = synchronized(lock) { track?.play() }

    fun setVolume(volume: Float) = synchronized(lock) { track?.setVolume(volume) }

    fun close() {
        synchronized(lock) {
            val audio = track ?: return
            track = null
            runCatching { audio.pause() }
            runCatching { audio.flush() }
            audio.release()
        }
    }

    suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.size % frameBytes == 0) { "incomplete PCM frame" }
        var offset = 0
        var lastProgressAt = SystemClock.elapsedRealtime()
        while (offset < bytes.size) {
            ensureActive()
            val count = synchronized(lock) {
                val audio = track ?: throw CancellationException()
                audio.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            check(count >= 0 && count % frameBytes == 0) { "PCM write failed: $count" }
            if (count == 0) {
                lastProgressAt = awaitPlaybackProgress(lastProgressAt)
            } else {
                framesWritten += count / frameBytes
                offset += count
                lastProgressAt = SystemClock.elapsedRealtime()
            }
        }
    }

    suspend fun writeSilence(durationMillis: Int) {
        val frames = sampleRate * durationMillis / 1_000
        write(ByteArray(frames * frameBytes))
    }

    suspend fun drain() = withContext(Dispatchers.IO) {
        val finalFrame = framesWritten
        var lastPosition = 0L
        var lastProgressAt = SystemClock.elapsedRealtime()
        while (true) {
            ensureActive()
            val played = playbackHeadFrames()
            if (played >= finalFrame) break
            if (played > lastPosition) {
                lastPosition = played
                lastProgressAt = SystemClock.elapsedRealtime()
            }

            // A restart after an underrun can require the start threshold again.
            val queuedFrames = framesWritten - played
            if (queuedFrames < startFrames) {
                write(ByteArray((startFrames - queuedFrames.toInt()) * frameBytes))
            }
            lastProgressAt = awaitPlaybackProgress(lastProgressAt)
        }
    }

    private fun playbackHeadFrames(): Long = synchronized(lock) {
        val audio = track ?: throw CancellationException()
        audio.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
    }

    private suspend fun awaitPlaybackProgress(lastProgressAt: Long): Long {
        val paused = synchronized(lock) {
            val audio = track ?: throw CancellationException()
            audio.playState == AudioTrack.PLAYSTATE_PAUSED
        }
        val now = SystemClock.elapsedRealtime()
        check(paused || now - lastProgressAt < STALL_TIMEOUT_MS) { "PCM playback stalled" }
        delay(POLL_MS)
        return if (paused) now else lastProgressAt
    }

    private companion object {
        const val POLL_MS = 5L
        const val STALL_TIMEOUT_MS = 5_000L
    }
}
