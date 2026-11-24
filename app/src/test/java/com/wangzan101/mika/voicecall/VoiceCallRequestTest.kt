package com.wangzan101.mika.voicecall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 执行安全闸:5 条规则各有正反例,另含跨轮重放、同轮多次 offer、回声按整句判定、回声窗环形边界。 */
class VoiceCallRequestTest {

    // 规则①:无当轮转写 → Reject
    @Test
    fun offerWithoutTranscriptRejects() {
        val gate = VoiceCallRequest(RingBuffer(10))
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开微信"))
    }

    // 规则⑤正例:匹配 → Accept(用户整句原文,非归一化结果)
    @Test
    fun matchingUtteranceAcceptsWithOriginalTranscript() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("Open WeChat, please")
        assertEquals(
            VoiceCallDecision.Accept("Open WeChat, please"),
            gate.offer("c1", "open wechat please"),
        )
    }

    // 规则③正例:标点/空白/大小写归一化后互为子串即可过(CJK 标点也算)
    @Test
    fun punctuationWhitespaceAndCaseAreNormalized() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("你好,世界!")
        assertEquals(VoiceCallDecision.Accept("你好,世界!"), gate.offer("c1", "你好 世界"))
    }

    // 规则③正例:单向包含,不要求等长——utterance 是转写的片段也过,执行的是用户完整的原话
    @Test
    fun partialOverlapAccepts() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("帮我打开微信好吗")
        assertEquals(VoiceCallDecision.Accept("帮我打开微信好吗"), gate.offer("c1", "打开微信"))
    }

    // 规则⑤:模型只摘片段也改不了用户的意思——否定词不会被摘掉,交给后台的永远是整句
    @Test
    fun aFragmentCannotDropANegation() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("千万别帮我给他转账")
        assertEquals(VoiceCallDecision.Accept("千万别帮我给他转账"), gate.offer("c1", "帮我给他转账"))
        assertEquals("千万别帮我给他转账", gate.said("千万别帮我给他转账"))
        assertNull(gate.said("帮我给他转账"))
    }

    // 规则③反例:utterance 比当轮转写还长——模型往里添了用户没说的话 → Reject 且不消费转写
    @Test
    fun utteranceLongerThanTranscriptRejectsAndKeepsTranscript() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开微信再发个红包"))
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c2", "打开微信"))
    }

    // 规则③反例:不匹配 → Reject 且不消费转写,后续匹配的 offer 仍可用这条转写
    @Test
    fun mismatchedUtteranceRejectsAndKeepsTranscript() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开支付宝"))
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c2", "打开微信"))
    }

    // 规则②:offer 过的 callId 即作废——即使上次被拒、这次转写匹配,重放仍 Reject
    @Test
    fun seenCallIdRejectsEvenWithMatchingTranscript() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开支付宝"))
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开微信"))
        // 被拒的重放不消费转写,新 callId 仍可正常通过
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c2", "打开微信"))
    }

    // 规则②跨轮:上一轮回合已接受的 callId,新一轮转写登记后重放仍 Reject
    @Test
    fun replayedCallIdAcrossTurnsRejects() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c1", "打开微信"))
        gate.onUserTranscript("打开支付宝")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开支付宝"))
        assertEquals(VoiceCallDecision.Accept("打开支付宝"), gate.offer("c2", "打开支付宝"))
    }

    // 规则⑤:一句话可以办几件事——同轮转写不随放行消费,各次调用各带自己那段原话
    @Test
    fun oneUtteranceAuthorizesSeveralCalls() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("帮我订晚饭,再叫个车")
        // 各件都带整句(用户原话),哪件办哪部分由前台的标题、备注说明
        assertEquals(VoiceCallDecision.Accept("帮我订晚饭,再叫个车"), gate.offer("c1", "帮我订晚饭"))
        assertEquals(VoiceCallDecision.Accept("帮我订晚饭,再叫个车"), gate.offer("c2", "再叫个车"))
        assertEquals("帮我订晚饭,再叫个车", gate.said("帮我订晚饭,再叫个车"))
        // 用户没说过的话,同轮里照样拒
        assertEquals(VoiceCallDecision.Reject, gate.offer("c3", "顺便转账"))
    }

    // 规则⑤:下一轮用户开口,上一轮转写作废
    @Test
    fun nextUserTurnExpiresTheTranscript() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c1", "打开微信"))
        gate.onUserTurnStart()
        assertEquals(VoiceCallDecision.Reject, gate.offer("c2", "打开微信"))
    }

    // onUserTranscript 每轮覆盖,只留最新:旧转写不能再用来通过校验
    @Test
    fun newerTranscriptOverwritesOlder() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("你好")
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "你好"))
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c2", "打开微信"))
    }

    // 规则④反例:utterance 是回声窗内助手内容的子串 → Reject(标点差异归一化后仍算回声)
    @Test
    fun utteranceInsideEchoWindowRejects() {
        val window = RingBuffer<String>(20)
        val gate = VoiceCallRequest(window)
        window.append("好的,马上帮你打开微信")
        // 助手播音被服务端 VAD 当成用户转写回流,模型原样调 mika_request
        gate.onUserTranscript("马上帮你打开微信")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "马上帮你打开微信"))
    }

    // 规则④正例:回声只盖住这句的一小部分 → 不算回声(用户自己接着说了别的)
    @Test
    fun echoCoveringOnlyPartOfTheTurnDoesNotBlock() {
        val window = RingBuffer<String>(10)
        val gate = VoiceCallRequest(window)
        window.append("你好")
        gate.onUserTranscript("你好世界")
        assertEquals(VoiceCallDecision.Accept("你好世界"), gate.offer("c1", "你好世界"))
    }

    // 规则④正例:刚念过的问题里有选项,用户挑一个回答 → 不算回声
    @Test
    fun answeringWithAnOptionFromTheSpokenQuestionPasses() {
        val window = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(window)
        window.append("订日料那件,在问你:七点还是八点?")
        gate.onUserTranscript("七点吧")
        assertEquals(VoiceCallDecision.Accept("七点吧"), gate.offer("c1", "七点"))
        gate.onUserTranscript("七点")
        assertEquals(VoiceCallDecision.Accept("七点"), gate.offer("c2", "七点"))
    }

    // 规则④反例:整句几乎都是刚念过的话(播音回流成了用户转写)→ Reject,哪怕只摘一段
    @Test
    fun aTurnMostlyMadeOfEchoRejectsEvenForAFragment() {
        val window = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(window)
        window.append("好的,马上帮你打开微信")
        gate.onUserTranscript("马上帮你打开微信吧")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开微信"))
    }

    // 规则④正例:回声窗有无关内容,utterance 与转写匹配且不在窗内 → Accept
    @Test
    fun unrelatedEchoWindowContentDoesNotBlock() {
        val window = RingBuffer<String>(20)
        val gate = VoiceCallRequest(window)
        window.append("今天天气真不错")
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c1", "打开微信"))
    }

    // 回声窗环形边界:恰好写满容量的内容仍完整参与回声判定
    @Test
    fun echoWindowKeepsFullCapacityContent() {
        val window = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(window)
        window.append("甲".repeat(ECHO_WINDOW_CAPACITY_CHARS))
        gate.onUserTranscript("甲甲甲甲")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "甲甲甲甲"))
    }

    // 回声窗 500 字环形边界:超出容量逐条丢最旧,被丢内容不再判回声,留存内容仍判
    @Test
    fun echoWindowEvictsOldestEntryBeyondCapacity() {
        val window = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(window)
        window.append("甲".repeat(ECHO_WINDOW_CAPACITY_CHARS))
        window.append("乙乙乙乙")
        assertEquals(listOf("乙乙乙乙"), window.snapshot())
        gate.onUserTranscript("甲甲甲甲")
        assertEquals(VoiceCallDecision.Accept("甲甲甲甲"), gate.offer("c1", "甲甲甲甲"))
        gate.onUserTranscript("乙乙乙乙")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c2", "乙乙乙乙"))
    }

    // 回声窗环形边界:单条超过容量只留尾部最近的内容
    @Test
    fun oversizedEntryKeepsOnlyTail() {
        val window = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(window)
        window.append("丙".repeat(100) + "丁".repeat(ECHO_WINDOW_CAPACITY_CHARS))
        assertEquals(listOf("丁".repeat(ECHO_WINDOW_CAPACITY_CHARS)), window.snapshot())
        gate.onUserTranscript("丙丙")
        assertEquals(VoiceCallDecision.Accept("丙丙"), gate.offer("c1", "丙丙"))
        gate.onUserTranscript("丁丁丁丁")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c2", "丁丁丁丁"))
    }

    // 加固:归一化后为空的 utterance(纯标点)宁可拒执行——空串是任何串的子串,不能放行
    @Test
    fun emptyNormalizedUtteranceRejects() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("……")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "……"))
    }

    // 取消只认最近放行的那一句:用户又说了一句之后,前一句不能再拿来停任务
    @Test
    fun cancelOnlyTakesTheLatestAcceptedWords() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("别订了")
        assertEquals(VoiceCallDecision.Accept("别订了"), gate.offer("c1", "别订了"))
        assertEquals("别订了", gate.saidNow("别订了"))
        gate.onUserTurnStart()
        gate.onUserTranscript("明天天气怎么样")
        assertEquals(VoiceCallDecision.Accept("明天天气怎么样"), gate.offer("c2", "天气"))
        assertEquals("别订了", gate.said("别订了"))
        assertNull(gate.saidNow("别订了"))
    }
}
