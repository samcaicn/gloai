package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.user.UserScreenMessage
import com.wangzan101.mika.core.tool.ToolDispatcher

/**
 * 用户一句话里他自己的话(开局、插话、回答追问、前台核对派单的话一个口径)。语言提示和回答的语言检查只看他这次打的字([typed]);
 * 记忆、技能的引文、前台派单时核对的原话还认他引的自己说过的话([said]),引的 Mika 的回答不算。
 */
object UserWords {
    /**
     * [text] 是去掉屏幕说明后的那句(见 [UserScreenTurns]);再先后去掉浏览器页的页面说明([ToolDispatcher.userWords])、
     * 最前面引的那一段([QuoteMessage])和末尾的附件行([AttachmentLines])。都没有时是原话;只引了一段、只选了字、只带了附件时为空。
     */
    fun typed(text: String, tools: ToolDispatcher): String = typed(tools.userWords(text))

    /** [typed] 之外,引的是用户自己说过的话时连引的那段一起算(对话页引的是他当时打的字),放在前面;引的是 Mika 的回答时只有 [typed]。 */
    fun said(text: String, tools: ToolDispatcher): String = said(tools.userWords(text))

    /**
     * 前台用的口径:前台手里没有工具分发器,屏幕的说明([UserScreenMessage])和浏览器页的页面说明([AttachedPage])都先去掉,再同 [said]。
     * 引的 Mika 的回答、附件行都不算用户说的话,前台派单时核对不过。
     */
    fun saidToDesk(text: String): String = said(AttachedPage.words(UserScreenMessage.parse(text).words))

    /** [text] 已去掉各能力的说明。 */
    private fun typed(text: String): String = AttachmentLines.words(QuoteMessage.words(text))

    private fun said(text: String): String {
        val own = QuoteMessage.parse(text).quote?.takeIf { it.from == QuoteMessage.From.You }?.text
        return listOfNotNull(own, typed(text)).filter { it.isNotBlank() }.joinToString("\n")
    }
}
