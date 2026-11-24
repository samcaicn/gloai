package com.wangzan101.mika.voicecall

/** 一通通话挂在哪条会话上:每次打开都要落到同一条,之前通话里说的才接得上。 */
object VoiceCallConversation {
    /**
     * [showing] 是聊天页此刻开着的会话。前台派出的任务会话([parentOf] 给得出前台会话的)不接通话,接回它的前台会话;
     * 聊天页是空白新对话时 [claim] 占下一条(聊天页也停到这条上),下次打开就还是它,不会每次都新开一条。
     */
    fun pick(showing: String?, parentOf: (String) -> String?, claim: () -> String): String =
        showing?.let { parentOf(it) ?: it } ?: claim()
}
