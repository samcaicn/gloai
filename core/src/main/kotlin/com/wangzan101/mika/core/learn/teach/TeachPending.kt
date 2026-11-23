package com.wangzan101.mika.core.learn.teach

import com.wangzan101.mika.core.learn.skills.PersonalSkill

/**
 * 整理完成、草稿已存,但会话还没来得及写盘时进程被杀:
 * 恢复时按 `learned_from` 找回同一份轨迹的待确认草稿,不重复生成。
 */
object TeachPending {
    fun find(drafts: Map<String, PersonalSkill>, traceId: String): Pair<String, PersonalSkill>? {
        val marker = TeachSkill.PREFIX + traceId
        return drafts.entries.firstOrNull { it.value.learnedFrom == marker }?.toPair()
    }
}
