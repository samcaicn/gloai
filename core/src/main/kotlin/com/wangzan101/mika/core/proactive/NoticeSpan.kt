package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 当日消息归纳的第 3 级(周)和第 4 级(月):把下一级的句子合成这一周(这个月)的一份——周归纳的输入是那几天的当日归纳,
 * 月归纳的输入是那几周的周归纳。放进请求的只有下一级的句子(模型写的、校验和遮过的),没有通知原文,也没有再下一级的。
 * 句子追根到底是从别人写的字里来的,所以照样套 [Untrusted]、不带工具、只请求一次;每句输出必须指向它依据的那几句,
 * 指不上的不要(读法见 [CitedLines])。
 *
 * 下一级的句子里常有「明天」「周五」这种相对的说法,合到一周、一个月里就不对了:每句输入前面写着它出自哪一天(哪一周),
 * 那是我们自己写的(不在不可信那一块里),提示词要求输出里一律写成日期。App 的名字由调用方按包名从系统读([appLabel])。
 */
class NoticeSpan(private val model: ChatModel, private val language: String, private val timeoutMs: Long = LearnTuning.SPAN_TIMEOUT_MS) {
    /** [start] 是这一周的周一、这个月的一号(纪元日)。结果的样子和当日归纳一样([DayResult])。 */
    suspend fun digest(kind: SpanKind, start: Long, input: List<SpanInput>, appLabel: (String) -> String): DayResult {
        require(input.isNotEmpty()) { "empty input" }
        val messages = listOf(Message(Role.User, listOf(Part.Text(buildInput(kind, start, input, language, appLabel))), Author.Mika))
        val reply = try {
            withTimeoutOrNull(timeoutMs) { model.complete(Prompts.role(prompt(kind)), messages, emptyList()) }
                ?: return DayResult.Failed()
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            return DayResult.BudgetExhausted
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return DayResult.Failed()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return DayResult.Failed(unreachable = e is ModelError.Network || e is ModelError.Proxy)
        }
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        return parse(text, input.size) ?: DayResult.Failed()
    }

    companion object {
        const val PROMPT_WEEK = "notice_week"
        const val PROMPT_MONTH = "notice_month"

        fun prompt(kind: SpanKind): String = if (kind == SpanKind.Week) PROMPT_WEEK else PROMPT_MONTH

        fun lineId(index: Int): String = CitedLines.id("d", index)

        private val TAG = Regex("<\\s*/?\\s*untrusted", RegexOption.IGNORE_CASE)
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd (EEE)", Locale.ENGLISH)

        private fun untrusted(s: String): String = Untrusted.wrap(s.replace(TAG, "(untrusted"))

        private fun date(day: Long): String = DATE.format(LocalDate.ofEpochDay(day))

        /**
         * 发给模型的输入:语言、这一周(这个月)是哪几天,然后逐句列出。日期、App 名是我们自己的,句子在不可信那一块里。
         * 周归纳的每句写着它出自哪一天;月归纳的每句写着它出自哪一周(周一到周日)。
         */
        fun buildInput(kind: SpanKind, start: Long, input: List<SpanInput>, language: String, appLabel: (String) -> String): String = buildString {
            appendLine("Language for the digest: $language")
            if (kind == SpanKind.Week) {
                appendLine("Week: ${date(start)} to ${date(start + 6)}")
                appendLine()
                appendLine("Lines from the daily digests of this week (oldest day first):")
            } else {
                appendLine("Month: ${DateTimeFormatter.ofPattern("yyyy-MM", Locale.ENGLISH).format(LocalDate.ofEpochDay(start))}")
                appendLine()
                appendLine("Lines from the weekly digests of this month (oldest week first):")
            }
            input.forEachIndexed { i, it ->
                appendLine()
                val whence = if (kind == SpanKind.Week) "date: ${date(it.from)}" else "week: ${date(it.from)} to ${date(it.from + 6)}"
                val apps = it.line.apps.map { p -> NoticeFilter.appName(appLabel(p), p) }.filter(String::isNotEmpty).distinct()
                appendLine("[${lineId(i)}] $whence" + if (apps.isEmpty()) "" else ", apps: ${apps.joinToString(", ")}")
                appendLine("line: ${untrusted(NoticeFilter.clean(it.line.text, LearnTuning.SPAN_LINE_MAX))}")
            }
        }.trimEnd()

        fun parse(raw: String, count: Int): DayResult.Done? =
            CitedLines.parse(raw, "lines", "d", count, LearnTuning.SPAN_LINES_MAX, LearnTuning.SPAN_LINE_MAX)?.let { DayResult.Done(it.lines, it.dropped, it.brief) }
    }
}
