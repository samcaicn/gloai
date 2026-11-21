package com.wangzan101.mika.voicecall

/** 回声窗容量:只保留助手最近说过的这么多个字,更早的内容不再参与回声判定。 */
const val ECHO_WINDOW_CAPACITY_CHARS = 500

/**
 * 语音请求执行安全闸:实时会话里模型通过 mika_request 提交的请求,必须先过这五条规则才允许执行,
 * 宁可拒执行,不可误执行。
 *
 * ① 无当轮转写 → Reject;② callId 已见过(含跨轮重放)→ Reject;③ utterance 归一化后不是当轮转写
 * 的子串 → Reject 且不消费转写——单向包含:模型可以只摘用户说的片段,不能往里添用户没说的话;
 * ④ utterance 归一化后与回声窗内助手内容互为子串 → Reject(回声是对称判定,两个方向都算);
 * ⑤ 通过 → Accept(原始转写),转写一次性消费。
 *
 * @param echoWindow 助手已说内容的环形缓冲,容量 [ECHO_WINDOW_CAPACITY_CHARS] 字。
 */
class VoiceCallRequest(
    private val echoWindow: RingBuffer<String> = RingBuffer(ECHO_WINDOW_CAPACITY_CHARS),
) {
    /** offer 过的 callId 即刻作废:即使当时被拒,之后用同一 callId 重放也一律 Reject。 */
    private val seenCallIds = mutableSetOf<String>()

    /** 当轮用户转写,只留最新一条;Accept 时一次性消费。 */
    private var currentTranscript: String? = null

    /** 登记当轮 final+complete 的用户转写;新一轮覆盖旧转写。 */
    @Synchronized
    fun onUserTranscript(text: String) {
        currentTranscript = text
    }

    @Synchronized
    fun offer(callId: String, utterance: String): VoiceCallDecision {
        if (!seenCallIds.add(callId)) return VoiceCallDecision.Reject
        val transcript = currentTranscript ?: return VoiceCallDecision.Reject
        val normalizedUtterance = normalizeForMatch(utterance)
        val normalizedTranscript = normalizeForMatch(transcript)
        // 空串是任何串的子串,归一化后为空宁可拒执行
        if (normalizedUtterance.isEmpty() || normalizedTranscript.isEmpty()) {
            return VoiceCallDecision.Reject
        }
        // ③ 单向包含:模型给的 utterance 必须落在当轮转写里;比转写还长就是把用户没说的话塞进来了
        if (normalizedUtterance !in normalizedTranscript) {
            return VoiceCallDecision.Reject
        }
        val echo = normalizeForMatch(echoWindow.snapshot().joinToString(""))
        if (echo.isNotEmpty() && isMutualSubstring(normalizedUtterance, echo)) {
            return VoiceCallDecision.Reject
        }
        currentTranscript = null
        return VoiceCallDecision.Accept(transcript)
    }
}

sealed interface VoiceCallDecision {
    /** 放行,携带原始(未归一化)的当轮用户转写。 */
    data class Accept(val text: String) : VoiceCallDecision

    data object Reject : VoiceCallDecision
}

/** 归一化:去空白、去标点、统一小写,只留字母与数字(含 CJK 表意字符)。 */
private fun normalizeForMatch(text: String): String =
    text.lowercase().filter(Char::isLetterOrDigit)

private fun isMutualSubstring(a: String, b: String): Boolean = a in b || b in a

/**
 * 按字符计容量的环形缓冲:逐条追加,总字符数超出 [capacityChars] 时从最旧整条丢弃;
 * 单条超过容量只保留尾部最近的内容。
 */
class RingBuffer<T : CharSequence>(private val capacityChars: Int) {
    init {
        require(capacityChars > 0) { "capacityChars must be positive" }
    }

    private val entries = ArrayDeque<T>()
    private var totalChars = 0

    @Synchronized
    fun append(item: T) {
        if (item.isEmpty()) return
        if (item.length >= capacityChars) {
            @Suppress("UNCHECKED_CAST")
            val tail = item.subSequence(item.length - capacityChars, item.length) as T
            entries.clear()
            entries.addLast(tail)
            totalChars = tail.length
            return
        }
        entries.addLast(item)
        totalChars += item.length
        while (totalChars > capacityChars) {
            totalChars -= entries.removeFirst().length
        }
    }

    @Synchronized
    fun snapshot(): List<T> = entries.toList()
}
