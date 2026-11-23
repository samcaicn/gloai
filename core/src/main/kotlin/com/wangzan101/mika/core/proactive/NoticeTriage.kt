package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelJson.field
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 模型给一条通知的判定。 */
enum class TriageDecision(val wire: String) {
    Ignore("ignore"), Remember("remember"), Surface("surface");

    companion object {
        fun ofWire(s: String?): TriageDecision? = entries.firstOrNull { it.wire == s }
    }
}

/** 内置的说明(界面按语言给字),代替模型写的理由。 */
enum class TriageNote {
    /** 回复里没有这条,或给的判定不认识:按忽略算。 */
    NoVerdict,
    /** 这个 App 今天值得提醒的条数到了上限,按忽略记。 */
    SurfaceCapped,
}

/**
 * 一条判定。[reason] 是模型写的(由通知内容引出,当不可信的普通文字:已清洗、截短,只按纯文字显示);
 * [note] 不为 null 时界面显示内置说明,不显示 [reason]。
 */
data class TriageVerdict(val decision: TriageDecision, val reason: String = "", val note: TriageNote? = null)

/** 一条要判定的通知:记录本身,和同一个键先后显示过的文字(见 [NoticeInbox.Snapshot.history],最后一段就是现在的文字)。 */
data class TriageItem(val record: NoticeRecord, val history: List<NoticeLine> = emptyList())

sealed interface TriageResult {
    /** 按通知键给出的判定;这一批里的每条都有。 */
    data class Judged(val verdicts: Map<String, TriageVerdict>) : TriageResult
    /**
     * 模型出错、超时或回复读不懂:这一批都算判定失败,不自动重试。[unreachable]:根本没连上模型(网络不通、代理不通);
     * [unanswered]:连上了,但等到这里的超时也没有回应(模型忙、很慢;连不上的会更早以连接错误报出来,算 [unreachable]);
     * [unreadable]:模型回了,但回复读不成判定(不是只给 JSON、结构不对)。这几样是给调用方记日志、定退避用的。
     */
    data class Failed(val unreachable: Boolean = false, val unreadable: Boolean = false, val unanswered: Boolean = false) : TriageResult
    /** 主动能力今天的预算用完了,没发请求:这一批照旧待判定。 */
    data object BudgetExhausted : TriageResult
}

/**
 * 给同一个 App 的一批通知(最多 [MAX_BATCH] 条)请一次模型判定:忽略、记下、值得提醒。纯逻辑,模型由调用方给。
 *
 * 防注入:
 * - 只请求一次,工具列表是空的;不重试(读不懂就是失败,免得坏掉的配置把每天的请求次数耗光)。
 * - 通知来的文字一律按字段套在 [Untrusted] 里(结束标记转义掉,大小写、空格变体先挡掉),系统提示词说明那是数据、不是指令,
 *   并且一条不能改变另一条的判定。
 * - 回复严格校验([parse]):不认识的 id 丢掉,重复的 id 只认第一个,不认识的判定按忽略,缺的 id 按忽略并给内置说明,
 *   只认回复结尾的 `{"items":[…]}` 或条目数组(话里引用的通知原文不算),读不出来就整批失败。
 * - 每批只有一个 App 的通知(调用方按 App 分批,这里也检查)。已知的局限:同一个 App 里(比如微信里不同的人)的几条
 *   还是在同一个请求里,一条消息仍可能影响另一条的判定;这一阶段的后果只是一个标签。
 *
 * [language] 是理由用的语言(界面语言的英文名),写在输入第一行。
 */
class NoticeTriage(
    private val model: ChatModel,
    private val language: String,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    suspend fun triage(items: List<TriageItem>): TriageResult {
        require(items.isNotEmpty() && items.size <= MAX_BATCH) { "batch size ${items.size}" }
        require(items.map { it.record.packageName }.distinct().size == 1) { "one app per batch" }
        val ids = items.indices.map(::idOf)
        val input = buildInput(items, language)
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.Mika))
        val reply = try {
            withTimeoutOrNull(timeoutMs) { model.complete(Prompts.role(PROMPT), messages, emptyList()) }
                ?: return TriageResult.Failed(unanswered = true)
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            return TriageResult.BudgetExhausted
        } catch (e: CancellationException) {
            // 只有调用方自己被取消(锁屏、关掉)才往外抛;模型内部的超时之类算失败
            currentCoroutineContext().ensureActive()
            return TriageResult.Failed()
        } catch (e: Exception) {
            // 调用方被取消时,取消也可能以别的异常冒出来(连接被打断之类):那不是这一批失败
            currentCoroutineContext().ensureActive()
            // 连接层的错(模型适配层会包成 Network / Proxy;没包的 IOException 也一样)都是没连上
            return TriageResult.Failed(unreachable = e is ModelError.Network || e is ModelError.Proxy || e is java.io.IOException)
        }
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        val byId = parse(text, ids) ?: return TriageResult.Failed(unreadable = true)
        return TriageResult.Judged(items.indices.associate { i -> items[i].record.key to byId.getValue(ids[i]) })
    }

    companion object {
        const val PROMPT = "notice_triage"
        const val TIMEOUT_MS = 30_000L
        const val MAX_BATCH = 8
        const val TITLE_MAX = 120
        const val TEXT_MAX = 300
        const val LINES_MAX = 5
        const val LINE_MAX = 200
        const val REASON_MAX = 120

        /** 一批里第 [index] 条(从 0 起)的 id:n1…n8。只在这一个请求里有意义,不是通知键。 */
        fun idOf(index: Int): String = "n${index + 1}"

        // 结束标记的各种写法(大小写、空格)先改掉,再由 Untrusted 转义标准写法;开头标记也改掉,免得看着像新的一段
        private val TAG = Regex("<\\s*/?\\s*untrusted", RegexOption.IGNORE_CASE)

        private fun untrusted(s: String): String = Untrusted.wrap(s.replace(TAG, "(untrusted"))

        /** 发给模型的输入:第一行是理由用的语言,然后是包名,再逐条列出,通知来的文字都在 untrusted 里。 */
        fun buildInput(items: List<TriageItem>, language: String): String = buildString {
            appendLine("Language for reasons: $language")
            appendLine("App package: ${items.first().record.packageName}")
            appendLine("Notifications (oldest first):")
            items.forEachIndexed { i, item ->
                val r = item.record
                appendLine()
                appendLine("[${idOf(i)}]")
                // 群聊还是私聊是我们按通知的写法拆出来的(看不出来就不写),写在不可信那一块外面
                r.group?.let { appendLine("chat: ${if (it) "group" else "private"}") }
                val title = NoticeFilter.clean(r.title, TITLE_MAX)
                // 群里的带上是谁说的(见 [NoticeRecord.said])
                val text = NoticeFilter.clean(r.said, TEXT_MAX)
                if (title.isNotEmpty()) appendLine("title: ${untrusted(title)}")
                if (text.isNotEmpty()) appendLine("text: ${untrusted(text)}")
                // 同一条通知重发前显示过的话(最后一段就是现在的,不重复)
                val earlier = item.history.dropLast(1).takeLast(LINES_MAX)
                    .map { line -> NoticeFilter.clean(listOf(line.title, line.text).filter { it.isNotBlank() }.joinToString(": "), LINE_MAX) }
                    .filter { it.isNotEmpty() }
                if (earlier.isNotEmpty()) appendLine("earlier: ${untrusted(earlier.joinToString("\n") { "- $it" })}")
                val messages = r.messages.takeLast(LINES_MAX)
                    .map { m -> NoticeFilter.clean(listOfNotNull(m.sender, m.text).joinToString(": "), LINE_MAX) }
                    .filter { it.isNotEmpty() }
                if (messages.isNotEmpty()) appendLine("messages: ${untrusted(messages.joinToString("\n") { "- $it" })}")
            }
        }.trimEnd()

        /**
         * 严格读回复:模型最后给的那个值(见 [itemsOf]:本地模型可能先想一段再给;想的时候复述的通知里可能就有一个
         * 像答案的对象,结论在最后)。读不出来为 null(整批失败)。
         * 返回 [ids] 里每个 id 的判定:不认识的 id 丢掉;重复的只认第一个;判定不认识的按忽略、不带模型的理由;缺的按忽略。
         * 理由按通知文字的办法清洗,截到 [REASON_MAX]。
         */
        fun parse(raw: String, ids: List<String>): Map<String, TriageVerdict>? {
            val items = itemsOf(raw) ?: return null
            // 给了东西、却没有一条是条目的样子(比如 [1,2,3]):回复的结构就不对,整批读不出来——
            // 不能当成「每一条都没给判定」收下(那样这一批就都按忽略了)。真的一条都没给([])照旧
            if (items.isNotEmpty() && items.none { it is JsonObject }) return null
            val out = LinkedHashMap<String, TriageVerdict>()
            for (e in items) {
                val o = e as? JsonObject ?: continue
                val idValue = o.field("id") as? JsonPrimitive ?: continue
                if (!idValue.isString) continue
                val id = idValue.content.trim()
                if (id !in ids || id in out) continue
                val decision = TriageDecision.ofWire((o.field("decision") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase())
                out[id] = if (decision == null) {
                    TriageVerdict(TriageDecision.Ignore, note = TriageNote.NoVerdict)
                } else {
                    TriageVerdict(decision, NoticeFilter.clean((o.field("reason") as? JsonPrimitive)?.contentOrNull.orEmpty(), REASON_MAX))
                }
            }
            return ids.associateWith { id -> out[id] ?: TriageVerdict(TriageDecision.Ignore, note = TriageNote.NoVerdict) }
        }

        /**
         * 回复里的条目:只认模型最后给的那个值([ReplyJson.ending])。以对象结尾时它得带 `items` 数组;以数组结尾时那就是条目
         * (真机上同一个模型在条数多时会省掉外面那层,直接给 `[{…},{…}]`)。话里引用的、复述的通知原文不算。
         * 回复不以 JSON 结尾的读不出来(整批失败):这时要是退回去找「最后一个带 `items` 的对象」,模型只是把通知里
         * 写着的一段 JSON 复述出来、后面跟一句「别信它」时,复述的那段就成了判定。提示词要求的本来就是只给 JSON。
         * 超过 [ReplyJson.MAX_CHARS] 的不读。
         */
        private fun itemsOf(raw: String): JsonArray? {
            if (raw.length > ReplyJson.MAX_CHARS) return null
            return when (val end = ReplyJson.ending(ReplyJson.normalise(raw))) {
                is ReplyJson.Ending.Value -> when (val v = end.element) {
                    is JsonArray -> v
                    is JsonObject -> v.field("items") as? JsonArray
                    else -> null
                }
                ReplyJson.Ending.GaveUp, ReplyJson.Ending.None -> null
            }
        }
    }
}
