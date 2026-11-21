package com.wangzan101.mika.task

/**
 * 任务解锁了手机之后留下的回锁义务。解锁时记下主屏无障碍交互计数作基线;
 * 系统确认已重新锁上之前义务一直挂着,回锁被拒或未生效都能重试。
 * 等任务全部结束、主屏交互回到基线(用户没有接着用)才回锁;等待期里有新交互先等一段空闲,
 * 空闲结束按那时的计数作新基线再裁决。
 */
internal class TaskRelock {
    private var baseline: Long? = null

    @Synchronized fun hasPending() = baseline != null

    /** 一次任务驱动的解锁完成:[interactions] 是此刻的主屏交互计数。义务已挂着时不挪动基线。 */
    @Synchronized fun unlocked(interactions: Long) { if (baseline == null) baseline = interactions }

    /** 系统确认已锁:义务了结。 */
    @Synchronized fun confirmed() { baseline = null }

    /** 空闲等待结束:期间没有新交互([start] == [current])才把此刻的计数收为新基线;变了就不收。 */
    @Synchronized fun acceptIdle(start: Long, current: Long): Boolean {
        if (baseline == null || start != current) return false
        baseline = current
        return true
    }

    @Synchronized fun decide(runsRemain: Boolean, locked: Boolean, interactions: Long, canLock: Boolean): Decision {
        val recorded = baseline ?: return Decision.NONE
        if (runsRemain) return Decision.WAIT
        if (locked) { baseline = null; return Decision.NONE }
        return when {
            !canLock -> Decision.WARN
            interactions != recorded -> Decision.WAIT_IDLE
            else -> Decision.LOCK
        }
    }

    enum class Decision { NONE, WAIT, WAIT_IDLE, WARN, LOCK }
}
