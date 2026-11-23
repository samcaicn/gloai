package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

sealed interface DayResult {
    /** [lines] 里每句的 [NoteSignal.entries] 是它依据的分段归纳(这一次请求里的下标)。 */
    /** [brief]:这一份的一句概述(见 [CitedLines.Parsed.brief]);没有为 null。 */
    data class Done(val lines: List<NoteSignal>, val dropped: Map<NoteDrop, Int>, val brief: String? = null) : DayResult
    data class Failed(val unreachable: Boolean = false) : DayResult
    data object BudgetExhausted : DayResult
}

/**
 * 当日消息归纳的第 2 级:把一天里各个 App 的分段归纳([DigestNote],第 1 级)合成当天的一份。
 * 这是多个 App 第一次放进同一个请求:放进去的只有第 1 级的句子(模型照着通知写的、已经校验和遮过的),没有通知原文。
 * 句子本身仍然是从别人写的字里来的,所以照样套 [Untrusted]、不带工具、只请求一次;每句输出必须指向它依据的那几句分段归纳,
 * 指不上的不要(读法见 [CitedLines])。App 的名字由调用方按包名从系统读([appLabel]),写在不可信那一块外面。
 */
class NoticeDay(private val model: ChatModel, private val language: String, private val timeoutMs: Long = LearnTuning.TIMEOUT_MS) {
    suspend fun digest(notes: List<DigestNote>, zone: ZoneId, appLabel: (String) -> String): DayResult {
        require(notes.isNotEmpty() && notes.size <= LearnTuning.DAY_NOTES_MAX) { "notes ${notes.size}" }
        val messages = listOf(Message(Role.User, listOf(Part.Text(buildInput(notes, language, zone, appLabel))), Author.Mika))
        val reply = try {
            withTimeoutOrNull(timeoutMs) { model.complete(Prompts.role(PROMPT), messages, emptyList()) }
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
        return parse(text, notes.size) ?: DayResult.Failed()
    }

    companion object {
        const val PROMPT = "notice_day"

        fun noteId(index: Int): String = CitedLines.id("s", index)

        private val TAG = Regex("<\\s*/?\\s*untrusted", RegexOption.IGNORE_CASE)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        private fun untrusted(s: String): String = Untrusted.wrap(s.replace(TAG, "(untrusted"))

        /**
         * 发给模型的输入:语言,然后按时间逐句列出。时间是我们自己的;会话名和句子在不可信那一块里;
         * App 名在外面,是那个 App 自己起的名字,只收过一遍([NoticeFilter.appName])。
         */
        fun buildInput(notes: List<DigestNote>, language: String, zone: ZoneId, appLabel: (String) -> String): String = buildString {
            appendLine("Language for the digest: $language")
            appendLine()
            appendLine("Notes from today (oldest first):")
            notes.forEachIndexed { i, n ->
                appendLine()
                val from = TIME.format(Instant.ofEpochMilli(n.fromMs).atZone(zone))
                val to = TIME.format(Instant.ofEpochMilli(n.toMs).atZone(zone))
                appendLine("[${noteId(i)}] app: ${NoticeFilter.appName(appLabel(n.packageName), n.packageName)}, time: ${if (from == to) from else "$from-$to"}")
                n.title?.let { NoticeFilter.clean(it, LearnTuning.CONTACT_NAME_MAX) }?.takeIf(String::isNotEmpty)?.let { appendLine("chat: ${untrusted(it)}") }
                appendLine("note: ${untrusted(NoticeFilter.clean(n.text, LearnTuning.NOTE_MAX))}")
            }
        }.trimEnd()

        fun parse(raw: String, noteCount: Int): DayResult.Done? =
            CitedLines.parse(raw, "lines", "s", noteCount, LearnTuning.DAY_LINES_MAX, LearnTuning.DAY_LINE_MAX)?.let { DayResult.Done(it.lines, it.dropped, it.brief) }
    }
}
