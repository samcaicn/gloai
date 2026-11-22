package com.wangzan101.mika.voice.cascade

import com.wangzan101.mika.voice.Pcm16

/**
 * Cuts a stream of 16 kHz mono frames into utterances by loudness: an adaptive noise floor, a short run of loud
 * frames to open a turn, [silenceMs] of quiet to close it, a hard cap per utterance and a little pre-roll so the
 * first syllable is not lost. Not thread-safe; one caller feeds it in order.
 */
class UtteranceDetector(
    private val silenceMs: Int,
    private val sampleRate: Int = 16_000,
    private val minSpeechMs: Int = 120,
    /** Louder and longer speech needed to open a turn while the assistant is talking (barge-in). */
    private val bargeInSpeechMs: Int = 360,
    private val maxUtteranceMs: Int = 30_000,
    private val prerollMs: Int = 300,
) {
    enum class Signal { None, Start, Speech, End }

    var inSpeech = false
        private set
    var floor = INITIAL_FLOOR
        private set

    private val preroll = ArrayDeque<ShortArray>()
    private var prerollSamples = 0
    private var voicedMs = 0
    private var silentMs = 0
    private var utteranceMs = 0

    /**
     * Pushes one frame. [strict] raises the bar (used while the assistant is speaking). On [Signal.Start] the
     * caller takes [takePreroll] (which already includes this frame); on [Signal.Speech] it forwards the frame.
     */
    fun push(frame: ShortArray, strict: Boolean = false): Signal {
        if (frame.isEmpty()) return if (inSpeech) Signal.Speech else Signal.None
        val ms = frame.size * 1000 / sampleRate
        val level = Pcm16.level(frame)
        if (!inSpeech) {
            remember(frame)
            val start = maxOf(floor * START_RATIO, MIN_START) * if (strict) STRICT_RATIO else 1f
            if (level > start) voicedMs += ms else {
                voicedMs = 0
                // Track the room: fall fast, rise slowly, only while nobody is talking.
                floor = if (level < floor) floor * 0.9f + level * 0.1f else floor * 0.995f + level * 0.005f
                floor = floor.coerceIn(MIN_FLOOR, MAX_FLOOR)
            }
            if (voicedMs >= if (strict) bargeInSpeechMs else minSpeechMs) {
                inSpeech = true
                silentMs = 0
                utteranceMs = prerollSamples * 1000 / sampleRate
                return Signal.Start
            }
            return Signal.None
        }
        utteranceMs += ms
        val keep = maxOf(floor * KEEP_RATIO, MIN_KEEP)
        if (level > keep) silentMs = 0 else silentMs += ms
        if (silentMs >= silenceMs || utteranceMs >= maxUtteranceMs) {
            reset()
            return Signal.End
        }
        return Signal.Speech
    }

    /** The frames heard just before (and including) the start of this utterance. */
    fun takePreroll(): ShortArray {
        val out = ShortArray(prerollSamples)
        var at = 0
        for (frame in preroll) { frame.copyInto(out, at); at += frame.size }
        preroll.clear()
        prerollSamples = 0
        return out
    }

    /** Drops any open utterance (muted, interrupted); keeps the learned noise floor. */
    fun reset() {
        inSpeech = false
        voicedMs = 0
        silentMs = 0
        utteranceMs = 0
        preroll.clear()
        prerollSamples = 0
    }

    private fun remember(frame: ShortArray) {
        preroll.addLast(frame.copyOf())
        prerollSamples += frame.size
        val cap = sampleRate * prerollMs / 1000
        while (prerollSamples - preroll.first().size >= cap && preroll.size > 1) {
            prerollSamples -= preroll.removeFirst().size
        }
    }

    private companion object {
        const val INITIAL_FLOOR = 0.003f
        const val MIN_FLOOR = 0.0005f
        const val MAX_FLOOR = 0.05f
        const val START_RATIO = 3.5f
        const val KEEP_RATIO = 2f
        const val STRICT_RATIO = 2f
        const val MIN_START = 0.012f
        const val MIN_KEEP = 0.008f
    }
}
