package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.agent.Turn as ChatTurn
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase

/** 点通话页的任务卡时打开哪一次运行的「运行过程」;给不出就退回打开那件任务的会话。 */
object VoiceCallTaskLink {
    /**
     * [liveRun] 是这件任务此刻在跑的那次运行,[savedTurns] 是它的会话里存下的轮次。
     * 排队中的还没开跑,会话里存的是上一次的运行,不拿来冒充;在办的只认在跑的那次;
     * 收尾了的认在跑的那次(刚结束还没撤下),否则是会话里最近一次记下的运行。
     */
    fun runFor(phase: DeskTaskPhase, liveRun: String?, savedTurns: () -> List<ChatTurn>): String? = when (phase) {
        DeskTaskPhase.Queued -> null
        DeskTaskPhase.Running, DeskTaskPhase.NeedsYou -> liveRun
        DeskTaskPhase.Done, DeskTaskPhase.Failed, DeskTaskPhase.Cancelled ->
            liveRun ?: savedTurns().lastOrNull { it.runId != null }?.runId
    }
}
