package com.wangzan101.mika.voicecall

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr

/**
 * 通话页的文案映射:每类错误、每种结束原因各占一条独立说法;RESUMED 超时落的
 * Ended(Background) 在这里翻成「请回到前台」。打字请求(合成 callId,模型侧没有 function_call)
 * 的结果回到本地:路由的模型指令文案换成本地说法,任务自己的答案/原因原样透传。
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

/** 打字请求的结果文本:路由常量是给模型的英文指令,落本地换成本地说法;其余(任务的答案/原因)原样。 */
fun typedResultText(text: String): String = when (text) {
    VoiceCallRoute.RESULT_BUSY -> tr(R.string.voicecall_task_running)
    VoiceCallRoute.RESULT_START_FAILURE -> tr(R.string.voicecall_task_failed)
    VoiceCallRoute.RESULT_NEEDS_USER -> tr(R.string.voicecall_see_task_page)
    VoiceCallRoute.RESULT_CANCELLED -> tr(R.string.voicecall_task_cancelled)
    VoiceCallRoute.RESULT_DONE_FALLBACK -> tr(R.string.voicecall_task_done)
    VoiceCallRoute.RESULT_FAILED_FALLBACK -> tr(R.string.voicecall_task_failed)
    else -> text
}
