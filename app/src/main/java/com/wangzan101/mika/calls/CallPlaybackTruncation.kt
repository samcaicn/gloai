package com.wangzan101.mika.calls

/**
 * Maps the output track's mono 24 kHz playback head since the last flush to offsets in
 * conversation items. Only the session's event collector observes audio and reports a flush.
 */
class CallPlaybackTruncation internal constructor(
    private val truncate: (itemId: String, contentIndex: Int, playedMs: Int) -> Boolean,
) {
    private data class Part(val itemId: String, val index: Int, var frames: Long = 0, var complete: Boolean = false)
    private val parts = mutableListOf<Part>()
    internal val hasAudio get() = parts.isNotEmpty()

    internal fun observe(event: VolcRealtimeEvent) {
        when (event) {
            is VolcRealtimeEvent.Audio -> {
                val id = event.itemId ?: return
                val last = parts.lastOrNull()
                val part = if (last?.itemId == id && last.index == event.contentIndex) last
                    else Part(id, event.contentIndex).also(parts::add)
                part.frames += event.pcm.size / 2
            }
            VolcRealtimeEvent.AssistantAudioEnded -> parts.lastOrNull()?.complete = true
            else -> Unit
        }
    }

    internal fun interrupt(playedFrames: Long): Boolean {
        var remaining = playedFrames.coerceAtLeast(0L)
        val sent = parts.all { part ->
            val heard = minOf(remaining, part.frames)
            remaining -= heard
            // An unfinished part may already have more audio on the server than we received.
            (part.complete && heard == part.frames) || truncate(part.itemId, part.index, (heard / 24L).toInt())
        }
        parts.clear()
        return sent
    }
}
