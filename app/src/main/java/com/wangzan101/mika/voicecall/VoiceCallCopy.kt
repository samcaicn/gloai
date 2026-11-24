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

/** [idleMinutes] 是静默挂断设的分钟数,只在 [EndReason.Idle] 时用。 */
fun voiceCallEndText(reason: EndReason, idleMinutes: Int = 0): String = when (reason) {
    EndReason.Hangup -> tr(R.string.voicecall_ended_hangup)
    EndReason.Notification -> tr(R.string.voicecall_ended_notification)
    EndReason.IncomingCall -> tr(R.string.voicecall_ended_incoming)
    EndReason.Idle -> tr(R.string.voicecall_ended_idle, idleMinutes)
    EndReason.Background -> tr(R.string.voicecall_ended_background)
}

/**
 * 静默挂断前给模型的提示(不是给用户看的界面文字):能接着回话的会话由模型用通话的语言转述;
 * 不能的照念 [R.string.voicecall_idle_line]。
 */
const val VOICECALL_IDLE_CUE =
    "The call has been quiet for a while. In one short sentence, ask whether the user is still there " +
        "and say you will hang up soon if you hear nothing. Do not call any tool."


/** 顶部状态点旁边那句:进行中是状态词,终态就是结束/错误文案。 */
fun voiceCallStatusText(state: VoiceCallState): String = when (state) {
    VoiceCallState.Connecting -> tr(R.string.voicecall_status_connecting)
    VoiceCallState.MicCheck -> tr(R.string.voicecall_status_mic_check)
    is VoiceCallState.Active -> tr(R.string.voicecall_status_active)
    is VoiceCallState.Ended -> voiceCallEndText(state.reason, state.idleMinutes)
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
