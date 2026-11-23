package com.wangzan101.mika.core.agent

/**
 * 前台写进会话的轮次(聊天、通话走前台时):前台自己回的话,不是后台任务跑出的结果。
 * - [STATUS]:用户说了一句,前台回了一句(可能顺手派了单,派出的任务记在 [Turn.deskTasks]);
 * - [REPORT_STATUS]:后台任务有了变化,前台说的一句汇报(没有用户的话)。
 * 两种都是正常的回话,不是「没办完」;交给模型时说成前台回的话(见 [Conversation.context])。
 */
object DeskTurn {
    const val STATUS = "desk"
    const val REPORT_STATUS = "desk_report"

    /** 聊天页前台那一轮的 [Turn.runId] 前缀:只用来认这一轮(界面上的和存下的对上),没有对应的运行记录。 */
    const val RUN_PREFIX = "desk:"

    fun isDesk(t: Turn): Boolean = t.status == STATUS || t.status == REPORT_STATUS

    /** 前台这一轮没回出来、也没派成时记下的原因([Turn.note]);正常回了话的为 null。 */
    fun failure(t: Turn): String? = t.note?.takeIf { t.status == STATUS }
}
