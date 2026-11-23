package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.tool.FakeScreen
import org.junit.Assert.assertEquals
import org.junit.Test

/** 用户一句话里他自己的话:这次打的字([UserWords.typed])和记忆、技能的引文认的那份([UserWords.said]),按对话页和浏览器页的拼法。 */
class UserWordsTest {
    private val tools = toolDispatcher(FakeScreen(), web = FakeWebPort())
    private val mika = QuoteMessage.Quote(QuoteMessage.From.Mika, "你常用的收货地址是幸福路 8 号")
    private val own = QuoteMessage.Quote(QuoteMessage.From.You, "我家猫叫团子")
    private val pdf = listOf("/workspace/inbox/a.pdf" to "2 KB")

    private fun words(text: String) = UserWords.typed(text, tools) to UserWords.said(text, tools)

    // 引了 Mika 的回答:引的那段两样都不算;附件行也不算,只带了附件、没打字时都为空
    @Test fun aQuotedReplyIsNotTheUserWords() {
        assertEquals("照这个改" to "照这个改", words(AttachmentLines.compose(QuoteMessage.compose(mika, "照这个改"), pdf)))
        assertEquals("" to "", words(AttachmentLines.compose(QuoteMessage.compose(mika, ""), pdf)))
    }

    // 引了自己说过的话:打的字只有这次的,记忆的引文连引的那段一起认,放在前面
    @Test fun anOwnQuoteIsSaidButNotTyped() {
        assertEquals("这个记一下" to "我家猫叫团子\n这个记一下", words(AttachmentLines.compose(QuoteMessage.compose(own, "这个记一下"), pdf)))
        assertEquals("" to "我家猫叫团子", words(AttachmentLines.compose(QuoteMessage.compose(own, ""), pdf)))
    }

    // 浏览器页上问:页面说明照旧去掉
    @Test fun thePageDescriptionIsNotTheUserWords() {
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理")
        assertEquals("这一页的地址对不对" to "这一页的地址对不对", words(AttachedPage.compose(page, "这一页的地址对不对")))
    }

    // 没有引用和附件的话原样;以「>」开头的普通话、引用之后打的以「>」开头的字都不当成引用
    @Test fun plainWordsStayAsTheyAre() {
        assertEquals("查天气" to "查天气", words("查天气"))
        assertEquals("> 这句是我自己写的\n后面" to "> 这句是我自己写的\n后面", words("> 这句是我自己写的\n后面"))
        assertEquals("> 这句什么意思\n解释一下" to "> 这句什么意思\n解释一下", words(QuoteMessage.compose(mika, "> 这句什么意思\n解释一下")))
    }

    // 前台没有工具分发器:同一个口径,引的 Mika 的回答、页面说明、附件行都不算;引的自己说过的话照样认
    @Test fun theDeskReadsTheSameWords() {
        assertEquals("照这个改", UserWords.saidToDesk(AttachmentLines.compose(QuoteMessage.compose(mika, "照这个改"), pdf)))
        assertEquals("我家猫叫团子\n这个记一下", UserWords.saidToDesk(QuoteMessage.compose(own, "这个记一下")))
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理")
        assertEquals("这一页的地址对不对", UserWords.saidToDesk(AttachedPage.compose(page, "这一页的地址对不对")))
        assertEquals("查天气", UserWords.saidToDesk("查天气"))
    }
}
