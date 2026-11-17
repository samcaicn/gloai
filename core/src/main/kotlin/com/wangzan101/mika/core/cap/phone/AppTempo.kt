package com.wangzan101.mika.core.cap.phone

/**
 * 一个 App 在任务里实际按哪套节奏操作:设置里「操作节奏」的全局档位和按应用覆盖解析出来的结果
 * (见 [OperatingPace] 与 app 设置里的按应用列表)。和 App 知识里两态的 [com.wangzan101.mika.core.uistate.PlacePace]
 * 分开:知识只说「这个 App 限制浏览快慢」([AppTempo.CAREFUL]),没有「快」——快来自用户的设置。
 */
enum class AppTempo {
    /** 快:这个 App 里动作之间不停留、打字一次写入、不设屏数上限。 */
    FAST,
    /** 标准:同一屏停一小会儿、换屏稍长,打字一段一段。 */
    NORMAL,
    /** 像人/限制浏览快慢:停得更久、按字数读、打字更慢。 */
    CAREFUL;

    companion object {
        /** App 知识头部的 pace 值(`careful`;不写或认不出为 [NORMAL])。 */
        fun of(value: String?): AppTempo = if (value == "careful") CAREFUL else NORMAL
    }
}
