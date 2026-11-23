package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.memory.RunNotes

/** 界面对任务服务能做的事;真机上由 TaskService 实现,测试里换成记录调用。 */
interface TaskActions {
    fun start(goal: String, conversationId: String)
    /** 任务进行中在它的对话里插的话;[messageId] 用来跟这句话的去向。 */
    fun steer(conversationId: String, messageId: String, text: String) = start(text, conversationId)
    /** 任务进行中在这个对话里排下一件;先持久化,失败返回 false。[startIfIdle] 为假只落盘,不抢在原话任务前开始。 */
    fun enqueueFollowUp(conversationId: String, messageId: String, text: String, startIfIdle: Boolean = true): Boolean {
        start(text, conversationId)
        return true
    }
    /** 取消一条还没开始的排队;已经开始就返回 false。 */
    fun cancelFollowUp(conversationId: String, messageId: String): Boolean = false
    /** 打开这个对话时,把排在它下面、又没任务在跑的下一件取出来开始。 */
    fun drainFollowUps(conversationId: String) {}
    fun withdrawSteer(conversationId: String, messageId: String): Boolean = false
    fun cancel()
    fun resume()
    fun takeOver()
    fun handBack()
    fun finishedMyself()
    fun skip()
    fun answer(text: String)
    /** [key] 是卡片所属的那次申报。 */
    fun approve(key: Int)
    fun decline(key: Int)
    /** 并行任务时让服务显示这个对话的任务(只管显示:对外的状态流跟着它)。 */
    fun show(conversationId: String) {}

    // 并行任务时应用内的按钮带着屏上的对话,只作用于该对话的任务;它已经没有任务就作废。默认同不带对话的版本。
    fun cancelIn(conversationId: String) = cancel()
    fun resumeIn(conversationId: String) = resume()
    fun takeOverIn(conversationId: String) = takeOver()
    fun handBackIn(conversationId: String) = handBack()
    fun finishedMyselfIn(conversationId: String) = finishedMyself()
    fun skipIn(conversationId: String) = skip()
    fun answerIn(conversationId: String, text: String) = answer(text)
    fun approveIn(conversationId: String, key: Int) = approve(key)
    fun declineIn(conversationId: String, key: Int) = decline(key)
    /** 切换这个对话任务当前 App 的声音(恢复/再静上);没有任务就不动。 */
    fun togglePipSoundIn(conversationId: String) {}
}

/** 结果卡片上记忆与技能提示的按钮;真机上由记忆存储与个人技能实现(读写在后台线程),测试里换成记录调用。 */
interface LearningActions {
    suspend fun undo(token: String): Boolean
    suspend fun approve(pendingId: String): Boolean
    /** 撤销令牌已失效(重启过)时「不要」直接删掉那条待批准的做法。 */
    suspend fun forget(memoryId: String): Boolean
    fun viewSkill(draftId: String)
    suspend fun restoreSkill(name: String)
    suspend fun deleteSkill(name: String)
    /** 提示改了(撤销、批准后去掉那一行):存进对话并通知界面。 */
    suspend fun save(notes: RunNotes)
}
