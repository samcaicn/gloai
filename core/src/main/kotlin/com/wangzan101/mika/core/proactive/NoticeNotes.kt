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

/** 校验过的一句分段归纳:[entries] 是它依据的那几段通知(这一批里的下标,至少一个),[text] 是清洗、遮过的那句话。 */
data class NoteSignal(val entries: List<Int>, val text: String)

/** 一句被丢掉的原因(只计数,不含内容)。 */
enum class NoteDrop {
    /** 多出 [LearnTuning.NOTES_MAX] 的。 */
    OverLimit,
    /** 不是对象,或没有文字。 */
    Malformed,
    /** 引用的通知 id 不在这一批里(这句里有一个就记一次)。 */
    UnknownId,
    /** 一个有效的通知 id 都没有:说不出依据的不要。 */
    NoIds,
    /** 过不了敏感信息关卡(密码、验证码、卡号之类)。 */
    Sensitive,
}

sealed interface NotesResult {
    data class Noted(val notes: List<NoteSignal>, val dropped: Map<NoteDrop, Int>) : NotesResult
    /** [unreachable]:根本没连上模型,这一批留着;否则这一批算做过(不再发)。和 [LearnResult.Failed] 一个意思。 */
    data class Failed(val unreachable: Boolean = false) : NotesResult
    data object BudgetExhausted : NotesResult
}

/**
 * 当日消息归纳的第 1 级:同一个 App 的一批通知原文(最多 [LearnTuning.NOTES_BATCH_MAX] 段)→ 几句「发生了什么」。
 * 每句必须指向它依据的那几段通知(这一批里的 id),指不上的不要:上面几级(当日、周)只在这些句子上做,
 * 有依据才能回头核对。只新增句子,不改写以前的(多次改写同一段摘要会把错越传越远)。纯逻辑,模型由调用方给。
 *
 * 防注入和 [NoticeLearn] 一样:只请求一次、工具列表是空的、有超时;通知文字都在 [Untrusted] 里;一批只有一个 App;
 * 回复只认结尾的那个 JSON 值([ReplyJson]),严格校验。句子里的网址、长串数字(单号、卡号、电话)遮掉,
 * 过不了 [SensitiveFilter] 的整句不要。
 */
class NoticeNotes(private val model: ChatModel, private val language: String, private val timeoutMs: Long = LearnTuning.TIMEOUT_MS) {
    suspend fun note(packageName: String, entries: List<NoticeEntry>, zone: ZoneId): NotesResult {
        require(entries.isNotEmpty() && entries.size <= LearnTuning.NOTES_BATCH_MAX) { "batch size ${entries.size}" }
        require(entries.all { it.packageName == packageName }) { "one app per batch" }
        val messages = listOf(Message(Role.User, listOf(Part.Text(buildInput(packageName, entries, language, zone))), Author.Mika))
        val reply = try {
            withTimeoutOrNull(timeoutMs) { model.complete(Prompts.role(PROMPT), messages, emptyList()) }
                ?: return NotesResult.Failed()
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            return NotesResult.BudgetExhausted
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return NotesResult.Failed()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return NotesResult.Failed(unreachable = e is ModelError.Network || e is ModelError.Proxy)
        }
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        return parse(text, entries.size) ?: NotesResult.Failed()
    }

    companion object {
        const val PROMPT = "notice_notes"

        fun entryId(index: Int): String = CitedLines.id("e", index)

        private val TAG = Regex("<\\s*/?\\s*untrusted", RegexOption.IGNORE_CASE)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        private fun untrusted(s: String): String = Untrusted.wrap(s.replace(TAG, "(untrusted"))

        /** 发给模型的输入:语言、包名,然后逐段列出(几点、群聊还是私聊是我们自己的,在不可信那一块外面)。 */
        fun buildInput(packageName: String, entries: List<NoticeEntry>, language: String, zone: ZoneId): String = buildString {
            appendLine("Language for notes: $language")
            appendLine("App package: $packageName")
            appendLine()
            appendLine("Notifications (oldest first):")
            entries.forEachIndexed { i, e ->
                appendLine()
                appendLine("[${entryId(i)}] time: ${TIME.format(Instant.ofEpochMilli(e.atMs).atZone(zone))}")
                e.group?.let { appendLine("chat: ${if (it) "group" else "private"}") }
                val title = NoticeFilter.clean(e.title, LearnTuning.TITLE_MAX)
                val said = NoticeFilter.clean(listOfNotNull(e.sender?.takeIf(String::isNotBlank), e.text).joinToString(": "), LearnTuning.TEXT_MAX)
                if (title.isNotEmpty()) appendLine("title: ${untrusted(title)}")
                if (said.isNotEmpty()) appendLine("text: ${untrusted(said)}")
            }
        }.trimEnd()

        /**
         * 严格读回复:结尾的那个 JSON 值得是 `{"notes":[…]}` 或直接一个数组;别的写法、超长的读不出来(null,整批算失败)。
         * 每句:要有至少一个这一批里的通知 id、要有文字;文字清洗、遮掉网址和长串数字、截到 [LearnTuning.NOTE_MAX]。
         */
        fun parse(raw: String, entryCount: Int): NotesResult.Noted? =
            CitedLines.parse(raw, "notes", "e", entryCount, LearnTuning.NOTES_MAX, LearnTuning.NOTE_MAX)?.let { NotesResult.Noted(it.lines, it.dropped) }
    }
}
