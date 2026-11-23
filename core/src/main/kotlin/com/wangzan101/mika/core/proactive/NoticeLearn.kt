package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.learn.memory.SensitiveFilter
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 回复里一条归纳被丢掉的原因(给计数用,不含内容)。 */
enum class LearnDrop {
    /** 多出 [LearnTuning.SIGNALS_MAX] 的。 */
    OverLimit,
    /** 不是对象,或者 topic 和 new 都有 / 都没有。 */
    Malformed,
    /** 引用的通知 id 里有不在这一批里的:整条不要(一条记一次)。 */
    UnknownId,
    /** 一个有效的通知 id 都没有。 */
    NoIds,
    /** 引用的话题 id 不是这次给的。 */
    UnknownTopic,
    /** 新话题的种类不认识。 */
    BadKind,
    /** 新话题的名字是空的。 */
    BadLabel,
    /** 名字里像有网址。 */
    UrlLike,
    /** 名字里有一长串数字。 */
    LongDigits,
    /** 名字过不了敏感信息关卡。 */
    Sensitive,
    /** 「敏感类推断」关着,模型却给了标了敏感的新话题。 */
    SensitiveOff,
    /** 校验过了,但用到话题池上时没收:引用的话题途中被删了,或用上时敏感类已关(由调用方记)。 */
    NotApplied,
    /** 新起的话题是用户删掉过的名字(由调用方记)。 */
    Blocked,
}

sealed interface LearnResult {
    /**
     * 读懂了;[signals] 是校验过的(可能是空的),[dropped] 是被丢掉的条数(按原因)。
     * [contacts]、[contactDropped] 是同一份回复里的联系人归类(和话题分开算,一边坏了不连累另一边)。
     */
    data class Learned(
        val signals: List<LearnSignal>,
        val dropped: Map<LearnDrop, Int>,
        val contacts: List<ContactSignal> = emptyList(),
        val contactDropped: Map<ContactDrop, Int> = emptyMap(),
    ) : LearnResult
    /**
     * 没归纳成。[unreachable] 为 true:根本没连上模型(网络不通、代理不通),和这批的内容无关,这一批留着以后再发。
     * 否则是模型回了错、超时或回复读不懂:这一批丢掉,不重试。两种都由调用方停一段再发下一批。
     * [unreadable]:模型回了,但回复读不成结果(不是只给 JSON、结构不对);只是给调用方记日志用的。
     */
    data class Failed(val unreachable: Boolean = false, val unreadable: Boolean = false) : LearnResult
    /** 主动能力今天的预算用完了,没发请求:这一批照旧待归纳。 */
    data object BudgetExhausted : LearnResult
}

/** 给模型看的一个已有话题;[id] 是池里的 id(请求里换成 t1…t30)。 */
data class ExistingTopic(val id: String, val kind: TopicKind, val label: String)

/**
 * 从同一个 App 的一批通知(最多 [LearnTuning.BATCH_MAX] 条)里归纳出用户的兴趣、关注、在买的东西之类的短话题。
 * App 推什么,是它的推荐模型对这个用户的判断:推广、推荐在这里是信号,不是噪音。纯逻辑,模型由调用方给。
 *
 * 防注入,和 [NoticeTriage] 一样:
 * - 只请求一次,工具列表是空的,超时 [LearnTuning.TIMEOUT_MS];这里不重试(没连上模型的那批由调用方留着,见 [LearnResult.Failed])。
 * - 通知文字、以及已有话题的名字(那也是模型早先从不可信的文字里归纳的)都套在 [Untrusted] 里;
 *   用户的动作(点开、划掉)是我们自己看到的,写在不可信那一块外面。
 * - 回复严格校验([parse]):只认这一批的通知 id、这次给的话题 id、固定的几种种类;新话题名清洗、截短,
 *   不许像网址、不许有长串数字、要过 [SensitiveFilter];最多认 [LearnTuning.SIGNALS_MAX] 条。
 * - 模型只能引用或新起话题,不能改名、合并、删除;回复里也没有 App 这一项,证据算在哪个 App 名下由调用方定。
 * - 聊天 App(微信之类)也归纳(用户自己把来源档位调成「低」来排除):提示词只许从对话里取「在聊什么」,
 *   别人对用户的说法、要求、指令都不当用户的事实;也不许拿私人或群的名字当话题名。
 *   这两条机器查不出来(名字和普通名词分不开),只靠提示词;字面上能查的(网址、长串数字、敏感信息)才在 [checkLabel] 里挡。
 * - 联系人与关系也在同一次请求里([contactsAllowed]):模型只能给「这几条通知是一个人 / 一个群发的、和用户什么关系」,
 *   不给名字;谁是一个联系人、叫什么,由 [ContactIdentity] 从通知本身算。类型和关系只认固定的几种,最多 [LearnTuning.CONTACTS_MAX] 条。
 *   已有的联系人和早先的归类不放进请求:每次的票各自独立。
 */
class NoticeLearn(
    private val model: ChatModel,
    private val language: String,
    /** 「敏感类推断」开着:模型可以给健康、财务等话题,但新话题要标 sensitive。关着时标了的一律丢掉。 */
    private val sensitiveAllowed: Boolean = false,
    /** 「归纳联系人与关系」开着:模型可以给联系人归类;关着时请求里说不要,回复里给了也丢掉。 */
    private val contactsAllowed: Boolean = false,
    private val timeoutMs: Long = LearnTuning.TIMEOUT_MS,
) {
    suspend fun learn(packageName: String, events: List<LearnEvent>, existing: List<ExistingTopic>): LearnResult {
        require(events.isNotEmpty() && events.size <= LearnTuning.BATCH_MAX) { "batch size ${events.size}" }
        require(events.all { it.packageName == packageName }) { "one app per batch" }
        val shown = existing.take(LearnTuning.EXISTING_MAX)
        val input = buildInput(packageName, events, shown, language, sensitiveAllowed, contactsAllowed)
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.Mika))
        val reply = try {
            withTimeoutOrNull(timeoutMs) { model.complete(Prompts.role(PROMPT), messages, emptyList()) }
                ?: return LearnResult.Failed()
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            return LearnResult.BudgetExhausted
        } catch (e: CancellationException) {
            // 只有调用方自己被取消(锁屏、关掉)才往外抛;模型内部的超时之类算失败
            currentCoroutineContext().ensureActive()
            return LearnResult.Failed()
        } catch (e: Exception) {
            // 调用方被取消时,取消也可能以别的异常冒出来(连接被打断之类):那不是这一批失败
            currentCoroutineContext().ensureActive()
            // 连接没建起来(模型只在家里的网络上、出门了之类):请求没到模型那里,这批没有问题
            return LearnResult.Failed(unreachable = e is ModelError.Network || e is ModelError.Proxy)
        }
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        return parse(text, events.size, shown.map { it.id }, sensitiveAllowed, contactsAllowed) ?: LearnResult.Failed(unreadable = true)
    }

    companion object {
        const val PROMPT = "notice_learn"

        fun eventId(index: Int): String = "n${index + 1}"
        fun topicRef(index: Int): String = "t${index + 1}"

        private val TAG = Regex("<\\s*/?\\s*untrusted", RegexOption.IGNORE_CASE)

        private fun untrusted(s: String): String = Untrusted.wrap(s.replace(TAG, "(untrusted"))

        private fun outcomeWire(o: LearnOutcome) = when (o) {
            LearnOutcome.Opened -> "opened"
            LearnOutcome.Dismissed -> "dismissed"
            LearnOutcome.None -> "none"
        }

        /** 发给模型的输入:第一行是话题名用的语言,然后是包名、已有话题、各条通知;别处来的文字都在 untrusted 里。 */
        fun buildInput(
            packageName: String,
            events: List<LearnEvent>,
            existing: List<ExistingTopic>,
            language: String,
            sensitiveAllowed: Boolean = false,
            contactsAllowed: Boolean = false,
        ): String = buildString {
            appendLine("Language for labels: $language")
            appendLine("App package: $packageName")
            // 模式由我们写,在不可信的块外面
            appendLine("Sensitive topics: ${if (sensitiveAllowed) "allowed" else "not allowed"}")
            appendLine("Contacts: ${if (contactsAllowed) "on" else "off"}")
            appendLine()
            if (existing.isEmpty()) {
                appendLine("Existing topics: none")
            } else {
                appendLine("Existing topics (cite one of these ids when it is about the same subject):")
                existing.forEachIndexed { i, t ->
                    appendLine("[${topicRef(i)}] kind: ${t.kind.wire}, label: ${untrusted(NoticeFilter.clean(t.label, LearnTuning.LABEL_MAX))}")
                }
            }
            appendLine()
            appendLine("Notifications (oldest first):")
            events.forEachIndexed { i, e ->
                appendLine()
                appendLine("[${eventId(i)}] user action: ${outcomeWire(e.outcome)}")
                // 群聊还是私聊是我们按通知的写法拆出来的(看不出来就不写),写在不可信那一块外面
                e.group?.let { appendLine("chat: ${if (it) "group" else "private"}") }
                val title = NoticeFilter.clean(e.title, LearnTuning.TITLE_MAX)
                val text = NoticeFilter.clean(e.text, LearnTuning.TEXT_MAX)
                if (title.isNotEmpty()) appendLine("title: ${untrusted(title)}")
                if (text.isNotEmpty()) appendLine("text: ${untrusted(text)}")
            }
        }.trimEnd()

        /**
         * 严格读回复:模型最后给的那个值(见 [itemsOf];模型可能先想一段再给)。
         * 读不出来为 null(整批失败)。[eventCount] 是这一批有几条(n1…);[topicIds] 是这次给模型看的话题(t1… 依次对应的池里的 id)。
         * 每一条按它带的字段分:有 `topic` / `new` 的是话题,有 `type` / `relation`(而没有前两样)的是联系人,不看它在哪个数组里;
         * 两边的上限各算各的。联系人那部分坏了照样用话题,话题那部分坏了照样用联系人。
         */
        fun parse(
            raw: String,
            eventCount: Int,
            topicIds: List<String>,
            sensitiveAllowed: Boolean = false,
            contactsAllowed: Boolean = false,
        ): LearnResult.Learned? {
            val (signalItems, contactItems) = itemsOf(raw) ?: return null
            // 给了东西、却没有一条是对象(比如 [1,2,3]):回复的结构就不对,整批读不出来——不能当成「什么都没归纳出来」收下
            if ((signalItems.isNotEmpty() || contactItems.isNotEmpty()) && (signalItems + contactItems).none { it is JsonObject }) return null
            val dropped = LinkedHashMap<LearnDrop, Int>()
            fun drop(d: LearnDrop) { dropped[d] = (dropped[d] ?: 0) + 1 }
            val out = ArrayList<LearnSignal>()
            signalItems.forEachIndexed { i, e ->
                if (i >= LearnTuning.SIGNALS_MAX) return@forEachIndexed drop(LearnDrop.OverLimit)
                val o = e as? JsonObject ?: return@forEachIndexed drop(LearnDrop.Malformed)
                // 话题和联系人的字段都带的,哪边都不算
                if (present(o, "type") || present(o, "relation")) return@forEachIndexed drop(LearnDrop.Malformed)
                val valid = idsOf(o, eventCount) ?: return@forEachIndexed drop(LearnDrop.UnknownId)
                if (valid.isEmpty()) return@forEachIndexed drop(LearnDrop.NoIds)
                val topic = o.field("topic")
                val new = o.field("new")
                val hasTopic = topic != null && topic !is JsonNull
                val hasNew = new != null && new !is JsonNull
                if (hasTopic == hasNew) return@forEachIndexed drop(LearnDrop.Malformed)
                if (hasTopic) {
                    val ref = (topic as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    val index = topicIds.indices.firstOrNull { topicRef(it) == ref } ?: return@forEachIndexed drop(LearnDrop.UnknownTopic)
                    out += LearnSignal(valid, topicId = topicIds[index])
                } else {
                    val n = new as? JsonObject ?: return@forEachIndexed drop(LearnDrop.Malformed)
                    val kind = TopicKind.ofWire((n.field("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase())
                        ?: return@forEachIndexed drop(LearnDrop.BadKind)
                    // 标记写得不认识的整条不要:不能把一个看不懂的「敏感」标记当成没标收下。
                    // 该写在 new 里面;写在外面那一层(和 ids 并排)的也认,哪一处标了都算标了
                    val inner = flag(n.field("sensitive")) ?: return@forEachIndexed drop(LearnDrop.Malformed)
                    val outer = flag(o.field("sensitive")) ?: return@forEachIndexed drop(LearnDrop.Malformed)
                    val sensitive = inner || outer
                    if (sensitive && !sensitiveAllowed) return@forEachIndexed drop(LearnDrop.SensitiveOff)
                    val rawLabel = (n.field("label") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    when (val r = checkLabel(rawLabel)) {
                        is LabelCheck.Ok -> out += LearnSignal(valid, newKind = kind, newLabel = r.label, sensitive = sensitive)
                        is LabelCheck.Bad -> drop(r.why)
                    }
                }
            }
            val (contacts, contactDropped) = parseContacts(contactItems, eventCount, sensitiveAllowed, contactsAllowed)
            return LearnResult.Learned(out, dropped, contacts, contactDropped)
        }

        /** 联系人那部分:只认这一批的通知 id、固定的类型(不认识的整条丢)和关系(不认识的当不确定);名字一概不读。 */
        private fun parseContacts(
            items: List<JsonElement>,
            eventCount: Int,
            sensitiveAllowed: Boolean,
            contactsAllowed: Boolean,
        ): Pair<List<ContactSignal>, Map<ContactDrop, Int>> {
            val dropped = LinkedHashMap<ContactDrop, Int>()
            fun drop(d: ContactDrop) { dropped[d] = (dropped[d] ?: 0) + 1 }
            if (!contactsAllowed) {
                items.forEach { _ -> drop(ContactDrop.Off) }
                return emptyList<ContactSignal>() to dropped
            }
            val out = ArrayList<ContactSignal>()
            items.forEachIndexed { i, e ->
                if (i >= LearnTuning.CONTACTS_MAX) return@forEachIndexed drop(ContactDrop.OverLimit)
                val o = e as? JsonObject ?: return@forEachIndexed drop(ContactDrop.Malformed)
                val valid = idsOf(o, eventCount) ?: return@forEachIndexed drop(ContactDrop.UnknownId)
                if (valid.isEmpty()) return@forEachIndexed drop(ContactDrop.NoIds)
                val type = ContactType.ofWire(wireOf(o, "type")) ?: return@forEachIndexed drop(ContactDrop.Malformed)
                var relation = ContactRelation.ofWire(wireOf(o, "relation")) ?: ContactRelation.Unknown
                // 「敏感类推断」关着时,「伴侣」当不确定
                if (relation == ContactRelation.Partner && !sensitiveAllowed) relation = ContactRelation.Unknown
                out += ContactSignal(valid, type, relation)
            }
            return out to dropped
        }

        private fun wireOf(o: JsonObject, name: String): String? =
            (o.field(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()

        /**
         * 这一条引用的、这一批里有的通知下标(去重)。里面有一个不是字符串、或不是这一批里的 id,整条不要(返回 null):
         * 依据有一处对不上,这条结论就不能信——不是只把对不上的那个 id 丢掉、结论照收。分段归纳的句子也是整句不要的。
         */
        private fun idsOf(o: JsonObject, eventCount: Int): List<Int>? {
            val indices = (o.field("ids") as? JsonArray).orEmpty()
                .map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.let { id -> eventIndex(id, eventCount) } }
            if (indices.any { it == null }) return null
            return indices.filterNotNull().distinct()
        }

        sealed interface LabelCheck {
            data class Ok(val label: String) : LabelCheck
            data class Bad(val why: LearnDrop) : LabelCheck
        }

        /**
         * 新话题名:按通知文字的办法清洗;像网址、有一长串数字、过不了敏感信息关卡的不要(这几项对没截短的整段查,
         * 截短藏不住);再截到 [LearnTuning.LABEL_MAX];空的不要。
         */
        fun checkLabel(raw: String): LabelCheck {
            val full = NoticeFilter.clean(raw, 1_000)
            if (full.isEmpty()) return LabelCheck.Bad(LearnDrop.BadLabel)
            val normalized = LearnText.nfkc(full)
            if (LearnText.urlLike(normalized)) return LabelCheck.Bad(LearnDrop.UrlLike)
            if (LearnText.longDigits(normalized)) return LabelCheck.Bad(LearnDrop.LongDigits)
            if (LearnText.sensitive(full)) return LabelCheck.Bad(LearnDrop.Sensitive) // 主程序那一关,加上登录码这些别的叫法
            val label = NoticeFilter.cap(full, LearnTuning.LABEL_MAX).trim()
            return if (label.isEmpty()) LabelCheck.Bad(LearnDrop.BadLabel) else LabelCheck.Ok(label)
        }

        private fun eventIndex(id: String, count: Int): Int? {
            if (!id.startsWith("n")) return null
            val n = id.substring(1).toIntOrNull() ?: return null
            // 只认 n1…nN 的标准写法(n01 不算)
            return (n - 1).takeIf { it in 0 until count && eventId(it) == id }
        }

        private fun present(o: JsonObject, name: String): Boolean = o.field(name).let { it != null && it !is JsonNull }

        /**
         * 「敏感」标记:没写、写 null 是没标;JSON 的 true / false 照认,写成字符串的 "true" / "false" 也认(有的模型这样写,
         * 意思是清楚的)。别的写法(别的字符串、数字、数组、对象)认不出来,返回 null。
         * 往严的一边靠:写成字符串的 "true" 要是当成没标,「敏感类推断」关着也会收下、还存成不敏感的。
         */
        private fun flag(e: JsonElement?): Boolean? = when {
            e == null || e is JsonNull -> false
            e is JsonPrimitive -> when (e.content.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }

        /** 带 `topic` 或 `new` 的是话题。 */
        private fun signalShaped(o: JsonObject) = present(o, "topic") || present(o, "new")

        /** 带 `type` 或 `relation`、又不带 `topic` / `new` 的是联系人。 */
        private fun contactShaped(o: JsonObject) = !signalShaped(o) && (present(o, "type") || present(o, "relation"))

        /**
         * 回复里的条目,分成话题和联系人两堆。只认模型最后给的那个值([ReplyJson.ending]):
         * - 以对象结尾:它得带 `signals` 和 / 或 `contacts` 数组(两样都不是数组就读不出来)。
         * - 以数组结尾:同一个模型有时会省掉外面那层,直接给 `[{…},{…}]`;里面的按字段分堆。
         * - 回复不以 JSON 结尾的读不出来(整批失败):这时要是退回去找「最后一个带 `signals` 的对象」,模型把通知里写着的
         *   一段 JSON 复述出来、后面再跟一句话时,复述的那段就会被当成结论。提示词要求的本来就是只给 JSON。
         * 超过 [ReplyJson.MAX_CHARS] 的不读。几种写法下都按字段分堆(见 [signalShaped]、[contactShaped]);
         * 两样都不像的留在原来那堆里,由校验记成坏的。读不出来为 null。
         */
        private fun itemsOf(raw: String): Pair<List<JsonElement>, List<JsonElement>>? {
            if (raw.length > ReplyJson.MAX_CHARS) return null
            val text = ReplyJson.normalise(raw)
            val (sig, con) = when (val end = ReplyJson.ending(text)) {
                is ReplyJson.Ending.Value -> when (val v = end.element) {
                    is JsonArray -> v.filterNot { it is JsonObject && contactShaped(it) } to v.filter { it is JsonObject && contactShaped(it) }
                    is JsonObject -> {
                        val s = v.field("signals") as? JsonArray
                        val c = v.field("contacts") as? JsonArray
                        if (s == null && c == null) return null
                        s.orEmpty().toList() to c.orEmpty().toList()
                    }
                    else -> return null
                }
                ReplyJson.Ending.GaveUp, ReplyJson.Ending.None -> return null
            }
            val sigItems = sig.filterNot { it is JsonObject && contactShaped(it) } + con.filter { it is JsonObject && signalShaped(it) }
            val conItems = con.filterNot { it is JsonObject && signalShaped(it) } + sig.filter { it is JsonObject && contactShaped(it) }
            return sigItems to conItems
        }
    }
}
