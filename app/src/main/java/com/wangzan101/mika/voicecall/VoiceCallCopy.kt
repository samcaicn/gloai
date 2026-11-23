package com.wangzan101.mika.voicecall

import com.wangzan101.mika.R
import com.wangzan101.mika.core.frontdesk.DESK_RESULT_MAX_CHARS
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.frontdesk.DeskNotifier
import com.wangzan101.mika.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 通话页的文案映射:每类错误、每种结束原因各占一条独立说法;RESUMED 超时落的
 * Ended(Background) 在这里翻成「请回到前台」。前台的汇报、打字直接派单的结果也在这里变成本地说法。
 */

fun voiceCallErrorText(error: VoiceCallError): String = tr(
    when (error) {
        VoiceCallError.Auth -> R.string.voicecall_error_auth
        VoiceCallError.ConnectTimeout -> R.string.voicecall_error_timeout
        VoiceCallError.Disconnected -> R.string.voicecall_error_disconnected
        VoiceCallError.RateLimited -> R.string.voicecall_error_rate_limited
        VoiceCallError.Provider -> R.string.voicecall_error_provider
        VoiceCallError.MicSilent -> R.string.voicecall_error_mic_silent
        VoiceCallError.AudioFocus -> R.string.voicecall_error_audio_focus
        VoiceCallError.Playback -> R.string.voicecall_error_playback
        VoiceCallError.Busy -> R.string.voicecall_error_busy
    },
)

fun voiceCallEndText(reason: EndReason): String = tr(
    when (reason) {
        EndReason.Hangup -> R.string.voicecall_ended_hangup
        EndReason.Notification -> R.string.voicecall_ended_notification
        EndReason.IncomingCall -> R.string.voicecall_ended_incoming
        EndReason.TimeLimit -> R.string.voicecall_ended_limit
        EndReason.Background -> R.string.voicecall_ended_background
    },
)

/** 顶部状态点旁边那句:进行中是状态词,终态就是结束/错误文案。 */
fun voiceCallStatusText(state: VoiceCallState): String = when (state) {
    VoiceCallState.Connecting -> tr(R.string.voicecall_status_connecting)
    VoiceCallState.MicCheck -> tr(R.string.voicecall_status_mic_check)
    is VoiceCallState.Active -> tr(R.string.voicecall_status_active)
    is VoiceCallState.Ended -> voiceCallEndText(state.reason)
    is VoiceCallState.Failed -> voiceCallErrorText(state.error)
}

/** 前台汇报在通话里说的那句:「某某那件,办好了:……」;正文最多 [DESK_RESULT_MAX_CHARS] 字。 */
fun deskReportSpeech(report: DeskReport): String =
    tr(R.string.voicecall_desk_report, report.title, DeskNotifier.line(report.event).take(DESK_RESULT_MAX_CHARS))

/** 打字直接派单的结果回到本地:派出去了说「正在处理」(之后的进展照常由前台汇报),没派成说「没办成」。 */
fun typedDeskResult(result: JsonObject): String =
    when ((result["status"] as? JsonPrimitive)?.content) {
        "started", "queued", "already_dispatched" -> tr(R.string.voicecall_task_running)
        else -> tr(R.string.voicecall_task_failed)
    }
