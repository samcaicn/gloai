package com.wangzan101.mika.calls.briefing

import kotlinx.coroutines.CancellationException

/** A deliberate media stop is child cancellation, not a failure of the owner conversation.
 * stopImmediately sets its flag before acquiring the device lock/cancelling workers; a worker
 * already in a read/write may observe that flag first. It must not poison the parent's close
 * path and revoke an otherwise valid authorization, or obscure an earlier real failure.
 */
internal fun requireVoiceBriefingAudioActive(stopped: Boolean, allowed: () -> Boolean) {
    if (stopped) throw CancellationException("Voice briefing media stopped")
    if (!allowed()) throw VoiceBriefingFailure("语音交代已停止，未拨号")
}
