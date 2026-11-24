package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.frontdesk.DeskWords

/** 回声窗容量:只保留助手最近说过的这么多个字,更早的内容不再参与回声判定。 */
const val ECHO_WINDOW_CAPACITY_CHARS = 500

/**
 * 语音请求执行安全闸:通话里模型调前台工具时带的用户原话(words),必须先过这五条规则才允许执行,
 * 宁可拒执行,不可误执行。
 *
 * ① 无当轮转写 → Reject;② callId 已见过(含跨轮重放)→ Reject;③ utterance 归一化后不是当轮转写
 * 的子串 → Reject——单向包含:模型可以只摘用户说的片段,不能往里添用户没说的话;
 * ④ 当轮整句转写大半是回声窗里助手刚说过的话(播音回流成了用户转写)→ Reject。按整句判,不按模型摘的片段判:
 * 用户从刚念的问题里挑一个选项回答(「七点还是八点?」→「七点吧」)不算回声;太短的一句(不到
 * [ECHO_MIN_CHARS] 个字)分不清是回声还是简短回答,也不算;
 * ⑤ 通过 → Accept(用户整句原文)。交给后台的永远是整句——模型摘片段只用来核对,摘掉否定词、条件改不了用户的意思。
 * 一句话可以办几件事:放行不消费转写,同一轮里各次调用都带整句,各件办哪部分由前台的标题、备注说明;
 * 用户下一轮开口([onUserTurnStart])或新转写登记时,上一轮转写作废。
 *
 * 它也是这通电话给前台执行器的原话核对([DeskWords]):过闸时 words 已换成整句,执行时再核对一遍是放行过的。
 *
 * @param echoWindow 助手已说内容的环形缓冲,容量 [ECHO_WINDOW_CAPACITY_CHARS] 字。
 */
class VoiceCallRequest(
    private val echoWindow: RingBuffer<String> = RingBuffer(ECHO_WINDOW_CAPACITY_CHARS),
) : DeskWords {
    /** offer 过的 callId 即刻作废:即使当时被拒,之后用同一 callId 重放也一律 Reject。 */
    private val seenCallIds = mutableSetOf<String>()

    /** 当轮用户转写,只留最新一条;用户下一轮开口时作废。 */
    private var currentTranscript: String? = null

    /** 最近放行过的原话(过闸的转写、用户打的字),前台执行工具时据此核对;只留最近 [ACCEPTED_KEPT] 条。 */
    private val accepted = ArrayDeque<String>()

    /** 这句是最近放行过的用户原话时原样返回(过闸时已是整句)。 */
    @Synchronized
    override fun said(fragment: String): String? = fragment.takeIf { it in accepted }

    /** 取消只认最近放行的那一句:用户此刻说的,不是更早的。 */
    @Synchronized
    override fun saidNow(fragment: String): String? = fragment.takeIf { it == accepted.lastOrNull() }

    /** 用户打的字本就是亲述(闸防的是模型伪造),直接记成放行过的原话。 */
    @Synchronized
    fun acceptTyped(text: String) {
        remember(text)
    }

    private fun remember(text: String) {
        if (accepted.lastOrNull() == text) return
        accepted.addLast(text)
        while (accepted.size > ACCEPTED_KEPT) accepted.removeFirst()
    }

    /** 用户又开口了:上一轮的转写不再能授权任何调用。 */
    @Synchronized
    fun onUserTurnStart() {
        currentTranscript = null
    }

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
        if (isMostlyEcho(normalizedTranscript, echo)) return VoiceCallDecision.Reject
        remember(transcript)
        return VoiceCallDecision.Accept(transcript)
    }

    private companion object {
        const val ACCEPTED_KEPT = 16
    }
}

sealed interface VoiceCallDecision {
    /** 放行,携带原始(未归一化)的当轮用户整句转写。 */
    data class Accept(val text: String) : VoiceCallDecision

    data object Reject : VoiceCallDecision
}

/** 归一化:去空白、去标点、统一小写,只留字母与数字(含 CJK 表意字符)。 */
private fun normalizeForMatch(text: String): String =
    text.lowercase().filter(Char::isLetterOrDigit)

/** 一句至少这么多个字(归一化后)才可能判成回声。 */
private const val ECHO_MIN_CHARS = 4

/** 回声盖住一句的比例到这个份上,就当这句是播音回流。 */
private const val ECHO_COVER_RATIO = 0.8

/** 归一化后的整句 [turn] 里,和回声 [echo] 最长的一段公共连续内容盖住了这句的大半(且这段不太短)。 */
private fun isMostlyEcho(turn: String, echo: String): Boolean {
    if (turn.length < ECHO_MIN_CHARS || echo.isEmpty()) return false
    val covered = longestCommonRun(turn, echo)
    return covered >= ECHO_MIN_CHARS && covered >= turn.length * ECHO_COVER_RATIO
}

/** 两串最长公共连续子串的长度(按行滚动的动态规划)。 */
private fun longestCommonRun(a: String, b: String): Int {
    var best = 0
    var previous = IntArray(b.length + 1)
    for (i in 1..a.length) {
        val row = IntArray(b.length + 1)
        for (j in 1..b.length) {
            if (a[i - 1] == b[j - 1]) {
                row[j] = previous[j - 1] + 1
                if (row[j] > best) best = row[j]
            }
        }
        previous = row
    }
    return best
}

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
