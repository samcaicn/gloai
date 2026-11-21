package com.wangzan101.mika.calls

import kotlin.math.abs

/** Fixed-size observations. Never retains PCM, event text or wall-clock timestamps. */
internal class CallMediaCounters(private val measureInputSignal: Boolean = true) {
    private var inputFramesAccepted = 0L
    private var inputBytesAccepted = 0L
    private var inputPeakMax = 0L
    private var inputRmsOver200Frames = 0L
    private var inputFirstRmsOver200Frame = 0L
    private var inputLastRmsOver200Frame = 0L
    private var inputLastFrameNanos = 0L
    private var inputMaxGapNanos = 0L
    private var inputUnder10msGapFrames = 0L
    private var userSpeakingEvents = 0L
    private var userSpeechEndedEvents = 0L
    private var userTranscriptFinalEvents = 0L
    private var userTranscriptPartialEvents = 0L
    private var assistantAudioStartedEvents = 0L
    private var assistantAudioEndedEvents = 0L
    private var assistantInterruptedEvents = 0L
    private var assistantTextEndedEvents = 0L
    private var assistantAudioEvents = 0L
    private var assistantAudioBytes = 0L
    private var playbackWrittenBytes = 0L
    private var playbackMaxQueuedBytes = 0L
    private var playbackRejectedPackets = 0L
    private var playbackShellAcceptedBytes = -1L
    private var shellWriteZeroCount = -1L
    private var shellWriteZeroMaxMs = -1L
    private var shellLastWriteAgeMs = -1L
    private var shellPlaybackHeadFrames = -1L
    private var shellFlushMaxMs = -1L
    private var shellPlaybackRebuilds = -1L
    private var shellRebuildDroppedBytes = -1L
    private var providerFailedEvents = 0L
    private var providerClosedEvents = 0L
    private var completeUserTranscripts = 0L
    private var lastObservedTurn = CallAudioReport.Turn.NONE
    private var framesWhenLastAudioEnded: Long? = null
    private var signalFramesWhenLastAudioEnded: Long? = null
    private var captureSilencedMaxMs: Long? = null
    private var recognitionFailures = 0L
    private var emptyRecognitions = 0L
    private var providerInputCommits = 0L

    /** Called only after sendPcm16 accepts the frame locally; not a server delivery acknowledgement. */
    fun inputAccepted(frame: ByteArray, acceptedAtNanos: Long) {
        if (!measureInputSignal) return
        var peak = 0
        var sumSquares = 0L
        var offset = 0
        while (offset + 1 < frame.size) {
            val sample = (frame[offset].toInt() and 0xff) or (frame[offset + 1].toInt() shl 8)
            peak = maxOf(peak, abs(sample))
            sumSquares += sample.toLong() * sample
            offset += 2
        }
        synchronized(this) {
            if (inputFramesAccepted > 0) {
                val gap = (acceptedAtNanos - inputLastFrameNanos).coerceAtLeast(0L)
                inputMaxGapNanos = maxOf(inputMaxGapNanos, gap)
                if (gap < TEN_MS_NANOS) inputUnder10msGapFrames++
            }
            inputLastFrameNanos = acceptedAtNanos
            inputFramesAccepted++
            inputBytesAccepted += frame.size
            inputPeakMax = maxOf(inputPeakMax, peak.toLong())
            // Compare RMS squared to avoid a square root on every 20 ms frame.
            if (sumSquares > (offset / 2) * RMS_THRESHOLD_SQUARED) {
                inputRmsOver200Frames++
                // Successful send indices are one-based; zero means no frame crossed the threshold.
                if (inputFirstRmsOver200Frame == 0L) inputFirstRmsOver200Frame = inputFramesAccepted
                inputLastRmsOver200Frame = inputFramesAccepted
            }
        }
    }

    @Synchronized fun outputWritten(bytes: Int) { playbackWrittenBytes += bytes }
    @Synchronized fun outputQueued(bytes: Int) { playbackMaxQueuedBytes = maxOf(playbackMaxQueuedBytes, bytes.toLong()) }
    @Synchronized fun outputRejected() { playbackRejectedPackets++ }
    @Synchronized
    fun outputShellAccepted(
        bytes: Long,
        zeroCount: Long = -1L,
        zeroMaxMs: Long = -1L,
        lastWriteAgeMs: Long = -1L,
        playbackHeadFrames: Long = -1L,
        flushMaxMs: Long = -1L,
        captureSilencedMs: Long = -1L,
        playbackRebuilds: Long = -1L,
        rebuildDroppedBytes: Long = -1L,
    ) {
        playbackShellAcceptedBytes = maxOf(playbackShellAcceptedBytes, bytes)
        shellWriteZeroCount = maxOf(shellWriteZeroCount, zeroCount)
        shellWriteZeroMaxMs = maxOf(shellWriteZeroMaxMs, zeroMaxMs)
        if (lastWriteAgeMs >= 0) shellLastWriteAgeMs = lastWriteAgeMs
        if (playbackHeadFrames >= 0) shellPlaybackHeadFrames = playbackHeadFrames
        shellFlushMaxMs = maxOf(shellFlushMaxMs, flushMaxMs)
        shellPlaybackRebuilds = maxOf(shellPlaybackRebuilds, playbackRebuilds)
        shellRebuildDroppedBytes = maxOf(shellRebuildDroppedBytes, rebuildDroppedBytes)
        if (captureSilencedMs >= 0L) captureSilencedMaxMs = maxOf(captureSilencedMaxMs ?: 0L, captureSilencedMs)
    }

    /** Only numeric fields; a successful pipe or AudioTrack write does not prove the far end heard audio. */
    @Synchronized
    fun playbackDiagnostic(): String? = if (assistantAudioBytes == 0L && playbackShellAcceptedBytes < 0) null else
        "provider_bytes=$assistantAudioBytes pipe_written_bytes=$playbackWrittenBytes " +
            "peak_queued_bytes=$playbackMaxQueuedBytes rejected_packets=$playbackRejectedPackets " +
            "shell_up_bytes=$playbackShellAcceptedBytes shell_write_zero_count=$shellWriteZeroCount " +
            "shell_write_zero_max_ms=$shellWriteZeroMaxMs shell_last_write_age_ms=$shellLastWriteAgeMs " +
            "shell_playback_head_frames=$shellPlaybackHeadFrames shell_flush_max_ms=$shellFlushMaxMs " +
            "shell_playback_rebuilds=$shellPlaybackRebuilds shell_rebuild_dropped_bytes=$shellRebuildDroppedBytes"

    @Synchronized
    fun eventReceived(event: VolcRealtimeEvent) {
        when (event) {
            VolcRealtimeEvent.UserSpeaking -> {
                userSpeakingEvents++
                lastObservedTurn = CallAudioReport.Turn.SPEECH_DETECTED
            }
            VolcRealtimeEvent.UserSpeechEnded -> userSpeechEndedEvents++
            VolcRealtimeEvent.UserTranscriptionFailed -> recognitionFailures++
            VolcRealtimeEvent.UserTranscriptionEmpty -> emptyRecognitions++
            VolcRealtimeEvent.InputAudioCommitted -> providerInputCommits++
            is VolcRealtimeEvent.UserTranscript -> {
                if (event.isFinal) {
                    userTranscriptFinalEvents++
                    if (event.isCompleteFinal) {
                        completeUserTranscripts++
                        if (event.isCurrentTurn) lastObservedTurn = CallAudioReport.Turn.TRANSCRIPT_RECEIVED
                    }
                } else {
                    userTranscriptPartialEvents++
                    if (event.isCurrentTurn) lastObservedTurn = CallAudioReport.Turn.SPEECH_DETECTED
                }
            }
            VolcRealtimeEvent.AssistantAudioStarted -> assistantAudioStartedEvents++
            VolcRealtimeEvent.AssistantAudioEnded -> {
                assistantAudioEndedEvents++
                if (measureInputSignal) {
                    framesWhenLastAudioEnded = inputFramesAccepted
                    signalFramesWhenLastAudioEnded = inputRmsOver200Frames
                }
            }
            VolcRealtimeEvent.AssistantInterrupted -> assistantInterruptedEvents++
            VolcRealtimeEvent.AssistantTextEnded -> assistantTextEndedEvents++
            is VolcRealtimeEvent.Audio -> {
                assistantAudioEvents++
                assistantAudioBytes += event.pcm.size
                if (event.pcm.isNotEmpty() && lastObservedTurn == CallAudioReport.Turn.TRANSCRIPT_RECEIVED) {
                    lastObservedTurn = CallAudioReport.Turn.REPLY_AUDIO_RECEIVED
                }
            }
            is VolcRealtimeEvent.Failed -> providerFailedEvents++
            VolcRealtimeEvent.Closed -> providerClosedEvents++
            else -> Unit
        }
    }

    /** An observation of the last provider turn, not proof of what either person heard. */
    @Synchronized
    fun recordReport(): CallAudioReport? {
        if (lastObservedTurn == CallAudioReport.Turn.NONE && assistantAudioBytes == 0L &&
            playbackShellAcceptedBytes < 0L && captureSilencedMaxMs == null &&
            recognitionFailures == 0L && emptyRecognitions == 0L && providerInputCommits == 0L) return null
        return CallAudioReport(
            userSpeakingEvents, completeUserTranscripts, lastObservedTurn, assistantAudioBytes, playbackWrittenBytes,
            playbackShellAcceptedBytes.takeIf { it >= 0L },
            inputFramesAfterLastReply = framesWhenLastAudioEnded?.let { inputFramesAccepted - it },
            inputSignalFramesAfterLastReply = signalFramesWhenLastAudioEnded?.let { inputRmsOver200Frames - it },
            captureSilencedMaxMs = captureSilencedMaxMs,
            recognitionFailures = recognitionFailures,
            emptyRecognitions = emptyRecognitions,
            providerInputCommits = providerInputCommits,
        )
    }

    @Synchronized
    fun snapshot(): Map<String, Long> = mapOf(
        "input_frames_accepted" to inputFramesAccepted,
        "input_bytes_accepted" to inputBytesAccepted,
        "input_peak_max" to inputPeakMax,
        "input_rms_over_200_frames" to inputRmsOver200Frames,
        "input_first_rms_over_200_frame" to inputFirstRmsOver200Frame,
        "input_last_rms_over_200_frame" to inputLastRmsOver200Frame,
        "input_max_gap_us" to inputMaxGapNanos / 1_000,
        "input_under_10ms_gap_frames" to inputUnder10msGapFrames,
        "user_speaking_events" to userSpeakingEvents,
        "user_speech_ended_events" to userSpeechEndedEvents,
        "user_transcript_final_events" to userTranscriptFinalEvents,
        "user_transcript_partial_events" to userTranscriptPartialEvents,
        "assistant_audio_started_events" to assistantAudioStartedEvents,
        "assistant_audio_ended_events" to assistantAudioEndedEvents,
        "assistant_interrupted_events" to assistantInterruptedEvents,
        "assistant_text_ended_events" to assistantTextEndedEvents,
        "assistant_audio_events" to assistantAudioEvents,
        "assistant_audio_bytes" to assistantAudioBytes,
        "playback_written_bytes" to playbackWrittenBytes,
        "playback_max_queued_bytes" to playbackMaxQueuedBytes,
        "playback_rejected_packets" to playbackRejectedPackets,
        "playback_shell_accepted_bytes" to playbackShellAcceptedBytes,
        "playback_shell_write_zero_count" to shellWriteZeroCount,
        "playback_shell_write_zero_max_ms" to shellWriteZeroMaxMs,
        "playback_shell_last_write_age_ms" to shellLastWriteAgeMs,
        "playback_shell_head_frames" to shellPlaybackHeadFrames,
        "playback_shell_flush_max_ms" to shellFlushMaxMs,
        "playback_shell_rebuilds" to shellPlaybackRebuilds,
        "playback_shell_rebuild_dropped_bytes" to shellRebuildDroppedBytes,
        "provider_failed_events" to providerFailedEvents,
        "provider_closed_events" to providerClosedEvents,
    )

    private companion object {
        const val TEN_MS_NANOS = 10_000_000L
        const val RMS_THRESHOLD_SQUARED = 200L * 200L
    }
}
