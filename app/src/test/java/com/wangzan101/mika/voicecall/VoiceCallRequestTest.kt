package com.wangzan101.mika.voicecall

import org.junit.Assert.assertEquals
import org.junit.Test

/** 执行安全闸:5 条规则各有正反例,另含跨轮重放、消费后同轮二次 offer、回声窗环形边界。 */
class VoiceCallRequestTest {

    // 规则①:无当轮转写 → Reject
    @Test
    fun offerWithoutTranscriptRejects() {
        val gate = VoiceCallRequest(RingBuffer(10))
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开微信"))
    }

    // 规则⑤正例:匹配 → Accept(原始转写,非归一化结果)
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

    // 规则⑤反例:转写一次性消费——Accept 后同轮换新 callId 再 offer → Reject
    @Test
    fun secondOfferSameTurnAfterAcceptRejects() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c1", "打开微信"))
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

    // 规则④反例(反向):回声窗内容是 utterance 的子串 → 同样 Reject
    @Test
    fun echoWindowInsideUtteranceRejects() {
        val window = RingBuffer<String>(10)
        val gate = VoiceCallRequest(window)
        window.append("你好")
        gate.onUserTranscript("你好世界")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "你好世界"))
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
        gate.onUserTranscript("甲甲")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "甲甲"))
    }

    // 回声窗 500 字环形边界:超出容量逐条丢最旧,被丢内容不再判回声,留存内容仍判
    @Test
    fun echoWindowEvictsOldestEntryBeyondCapacity() {
        val window = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(window)
        window.append("甲".repeat(ECHO_WINDOW_CAPACITY_CHARS))
        window.append("乙")
        assertEquals(listOf("乙"), window.snapshot())
        gate.onUserTranscript("甲甲")
        assertEquals(VoiceCallDecision.Accept("甲甲"), gate.offer("c1", "甲甲"))
        gate.onUserTranscript("乙乙")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c2", "乙乙"))
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
        gate.onUserTranscript("丁丁")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c2", "丁丁"))
    }

    // 加固:归一化后为空的 utterance(纯标点)宁可拒执行——空串是任何串的子串,不能放行
    @Test
    fun emptyNormalizedUtteranceRejects() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("……")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "……"))
    }
}
