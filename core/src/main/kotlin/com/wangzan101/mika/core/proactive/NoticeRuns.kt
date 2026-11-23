package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.general.NOTICES_READ
import com.wangzan101.mika.core.learn.memory.TaskMemory
import com.wangzan101.mika.core.learn.memory.TaskMemoryRecorder
import com.wangzan101.mika.core.run.record.RunEvent

/**
 * 一次运行有没有读过通知,以及读过的话跨会话摘要里留什么。纯函数。
 *
 * 「读过」指通知的原文到过模型手里:这次运行调过 notices_read,或者这段对话里有通知提醒那一轮
 * ([com.wangzan101.mika.core.agent.NoticeTurn],提醒的原文就在上文里)。只是开工时带着「来自通知」的那一段
 * 归纳([ObservedContext])不算——那样的话非保守档下几乎每个任务都算,摘要就等于关了。
 */
object NoticeRuns {
    /** [noticeTurnInContext]:这段对话里有通知提醒那一轮。 */
    fun readNotices(events: List<RunEvent>, noticeTurnInContext: Boolean): Boolean =
        noticeTurnInContext || events.any { it is RunEvent.Activity && it.label == NOTICES_READ }

    /**
     * 这次运行的跨会话摘要([TaskMemoryRecorder.record] 记出来的那条)该存成什么样:读过通知的,结论只留结局那个词,
     * 不留回答或理由的文字——回答里多半转述了通知内容,而摘要以后的任务搜得到,那时已经看不出它是从通知来的。
     * 目标(用户自己的话)和步骤照留:读通知那一步不记对象、也不产生读到的原文。
     */
    fun summary(task: TaskMemory, events: List<RunEvent>, noticeTurnInContext: Boolean): TaskMemory =
        if (readNotices(events, noticeTurnInContext)) task.copy(conclusion = TaskMemoryRecorder.outcome(events)) else task
}
