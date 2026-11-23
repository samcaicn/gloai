package com.wangzan101.mika.core.learn.teach

import com.wangzan101.mika.core.learn.skills.PersonalSkill
import kotlinx.coroutines.CancellationException

/**
 * 一次整理的编排:整理出技能、写进待确认。
 *
 * 用户在总结期间丢弃这次示范、或已经开始新的示范,就什么都不写——模型已经回来的稿也不能落到待确认里,
 * 否则技能清单里会出现一份没人认领的草稿。写盘本身由调用方在同一把锁里再核对一次会话,
 * 这里的两道 [current] 检查只是把「不用再往下走」早一点发现(模型调用也是一笔花费)。
 */
object TeachSummary {
    sealed interface Outcome {
        /** 草稿写进了待确认;[byModel] 说明正文是不是模型写的。 */
        data class Drafted(val draftId: String, val skill: PersonalSkill, val byModel: Boolean) : Outcome

        /** 这次示范还在,但没整理出能用的稿。 */
        data object Failed : Outcome

        /** 会话已经被丢弃或换成了新的一次,什么都没写。 */
        data object Abandoned : Outcome
    }

    /**
     * @param current 现在还是不是这一次示范
     * @param build 交给模型或本机整理
     * @param store 写进待确认并记进会话;会话已经不是这一次时必须返回 false
     * @param newId 新草稿的编号
     */
    suspend fun run(
        current: () -> Boolean,
        build: suspend () -> TeachDraft.Result?,
        store: (String, TeachDraft.Result) -> Boolean,
        newId: () -> String,
    ): Outcome {
        if (!current()) return Outcome.Abandoned
        val result = try {
            build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (result == null) return if (current()) Outcome.Failed else Outcome.Abandoned
        if (!current()) return Outcome.Abandoned
        val id = newId()
        val stored = try {
            store(id, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return when {
            stored -> Outcome.Drafted(id, result.skill, result.byModel)
            current() -> Outcome.Failed
            else -> Outcome.Abandoned
        }
    }
}
