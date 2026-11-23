package com.wangzan101.mika.core.cap.wechat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 显式「发我微信」意图闸门:中英两套模式都命中,否定句排除,长句不按字数窗口截断。 */
class WechatOwnerRequestTest {
    @Test fun chineseExplicitSendMatches() {
        assertTrue(explicitWechatOwnerRequest("把今天的新闻摘要发到我的微信"))
        assertTrue(explicitWechatOwnerRequest("查一下快递进度,结果发我微信"))
    }

    @Test fun englishExplicitSendMatches() {
        assertTrue(explicitWechatOwnerRequest("summarize my emails and send the result to my WeChat"))
    }

    @Test fun longSentenceStillMatches() {
        assertTrue(explicitWechatOwnerRequest("把结果发到我的微信,顺便订个闹钟明早八点提醒我带上伞出门"))
    }

    @Test fun negatedRequestsDoNotMatch() {
        assertFalse(explicitWechatOwnerRequest("不要把结果推送到我的微信"))
        assertFalse(explicitWechatOwnerRequest("don't send anything to my wechat"))
    }

    @Test fun readingWechatIsNotSending() {
        assertFalse(explicitWechatOwnerRequest("帮我看看微信里有什么新消息"))
    }

    @Test fun unrelatedGoalDoesNotMatch() {
        assertFalse(explicitWechatOwnerRequest("今天天气怎么样"))
    }
}
