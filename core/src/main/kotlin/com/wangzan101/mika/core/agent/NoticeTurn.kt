package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.observation.Untrusted

/**
 * Mika 自己写进对话的一轮「通知提醒」(主动能力):用户点开提醒时,Mika 把挑出来的几条通知列成一条消息。
 * 这一轮没有用户的话([Turn.userText] 是空的);[Turn.outcome] 里的标题、正文是别的 App、别人写的,理由是模型看着那些字写的:
 * 都是第三方文字,任何时候都不是用户的话,也不是指令。
 *
 * 和定时提醒(`reminder:`)、任务中断(`interrupted:`)那几种 App 写的轮次一样,只靠 [Turn.runId] 的前缀认,不另加字段:
 * 对话文件的格式不动,分支、重启之后也都还认得。
 *
 * 认出来之后各处怎么对待(都在用到的地方写着):交给模型时不说成「用户说」、整段套不可信标记([Conversation.context]、history_read);
 * 有这种轮次的对话([tainted])里,「上一轮确认」不能用来引用别的轮次、任务结束后不自动提取记忆、自主模式下付款也照样先问。
 */
object NoticeTurn {
    /** 通知提醒那一轮的 [Turn.runId] 前缀(后面是随机的编号;没有对应的运行记录)。 */
    const val RUN_PREFIX = "notice:"

    fun isNotice(t: Turn): Boolean = t.runId?.startsWith(RUN_PREFIX) == true

    /** 这段对话里有没有通知提醒。要拿存着的全部轮次来问,不是喂给模型的最近几轮:提醒那一轮滑出窗口之后,对话照样算。 */
    fun tainted(turns: List<Turn>): Boolean = turns.any(::isNotice)

    private val TAG = Regex("<\\s*/?\\s*untrusted", RegexOption.IGNORE_CASE)

    /**
     * 交给模型时套上不可信标记。存的时候是什么样不作数,每次交出去都重新套;文字里自带的标记(开的、合的、中间夹空白的)先弄坏,
     * 免得提前合上这一层([Untrusted.wrap] 自己只管一字不差的结束标记)。
     */
    fun untrusted(text: String): String = Untrusted.wrap(text.replace(TAG, "(untrusted"))
}
