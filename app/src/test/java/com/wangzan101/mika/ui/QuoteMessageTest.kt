package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.wangzan101.mika.chat.ui.AttachmentMessage
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.chat.ui.ChatViewModel
import com.wangzan101.mika.chat.ui.ScreenCopy

/** 对话页上的引用:和附件一起时各归各,标题里不带引用;回答复制成纯文字。写进话里的样子见 core 的 QuoteMessageTest。 */
class QuoteMessageTest {
    @get:Rule val texts = ChineseTexts()

    private val mika = QuoteMessage.Quote(QuoteMessage.From.Mika, "明天上午有小雨\n下午转晴")

    // 引用加附件:附件行接在话后面,拆的时候各归各
    @Test fun quotesAndAttachmentsTogether() {
        val goal = ChatViewModel.withAttachments(QuoteMessage.compose(mika, "照这个改"), listOf("/workspace/inbox/a.pdf" to 2048L))
        val q = QuoteMessage.parse(goal)
        assertEquals(mika, q.quote)
        val a = AttachmentMessage.parse(q.words)
        assertEquals("照这个改", a.words)
        assertEquals(listOf("a.pdf"), a.files.map { it.name })
        // 只有引用和附件、没写话
        val only = QuoteMessage.parse(ChatViewModel.withAttachments(QuoteMessage.compose(mika, ""), listOf("/workspace/inbox/a.pdf" to 2048L)))
        assertEquals(mika, only.quote)
        assertEquals("a.pdf", AttachmentMessage.plain(only.words))
    }

    // 标题、通知里称呼这句话时不带引用
    @Test fun titlesSkipTheQuote() {
        val goal = QuoteMessage.compose(mika, "那要带伞吗")
        assertEquals("那要带伞吗", ChatCopy.plainTitle(goal))
        assertEquals("那要带伞吗", ScreenCopy.plain(goal))
    }

    @Test fun answersCopyAsPlainText() {
        assertEquals(
            "上海今天晴,详见 天气\n\n要点\n\n• 早上 12 度\n• 下午 20 度\n\n1. 带伞\n2. 穿外套\n\n> 注意降温\n\nval x = 1\n\n城市\t天气\n上海\t晴",
            MarkdownModel.toPlainText(
                "上海今天**晴**,详见 [天气](https://example.com)\n\n## 要点\n\n- 早上 12 度\n- 下午 *20* 度\n\n1. 带伞\n2. 穿外套\n\n> 注意降温\n\n" +
                    "```kotlin\nval x = 1\n```\n\n---\n\n| 城市 | 天气 |\n| --- | --- |\n| 上海 | 晴 |",
            ),
        )
        assertEquals("", MarkdownModel.toPlainText(""))
    }
}
