package com.wangzan101.mika.core.learn.teach

/**
 * 进程重启后怎么接着用硬盘上的会话。
 *
 * 采集、暂停都不能在重启后继续采，改成已停下。
 * 已经在总结、但草稿还没存下来的会话，要退回总结中再整理一次：只有总结中才允许写入草稿和失败原因，
 * 停在总结好会让这次示范一直卡在「没能写成草稿」，草稿即使写成了也显示不出来。
 */
object TeachRecovery {
    fun afterLoad(session: TeachSession): TeachSession = when {
        session.phase == TeachPhase.Recording || session.phase == TeachPhase.Paused ->
            session.copy(phase = TeachPhase.Interrupted)
        session.phase == TeachPhase.Reviewing && session.draftId == null ->
            session.copy(phase = TeachPhase.Drafting)
        else -> session
    }
}
