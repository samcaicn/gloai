package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.learn.memory.Change
import com.wangzan101.mika.core.learn.memory.MemoryContext
import com.wangzan101.mika.core.learn.memory.MemoryIndex
import com.wangzan101.mika.core.learn.memory.MemoryKind
import com.wangzan101.mika.core.learn.memory.MemoryRejected
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private const val MEMORY_SEARCH_LIMIT = 8

/**
 * 按规矩不收(不是用户说的、敏感、没明确要改删):这是对这一条的答复,不是操作出了故障。不计入连续失败——
 * 用户让一次记好几条时,其中几条不收很正常,为此把整个任务按「操作连续失败」停掉的话,已经存上的那几条也跟着算成了失败。
 * 仍然标成不可恢复:同一条别再试。存储满了、读不出来这类真的故障不走这里。
 *
 * 有上限:同一条(同样的参数、同样的原因)被原样再提,或者一个任务里不收的超过 [com.wangzan101.mika.core.tool.MAX_QUIET_MEMORY_REFUSALS] 条,
 * 就不再标「不计入失败」,连续三条就由守卫停下(见 [ToolContext.noteMemoryRefusal])。
 */
private fun refused(ctx: ToolContext, a: Args, error: String, message: String): ToolExecution {
    val result = fail(error, message, recoverable = false)
    return if (ctx.noteMemoryRefusal("${a.tool.name}|$error|${a.o}")) ToolExecution(JsonObject(result.content + ("counts_as_failure" to JsonPrimitive(false))))
    else result
}

/** 记忆和用户本次说过的话都给了才提供。 */
private fun memoryOn(ctx: ToolContext) = ctx.config.memory != null && ctx.config.utterances != null

internal object MemorySearch : AgentTool {
    override val verb = Verb.MemorySearch
    override val label = "查找记忆"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("memory_search",
        Prompts.tool("memory_search"),
        mapOf("query" to str("Words to look for, 1-100 characters.")), listOf("query"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = a.str("query", max = 100)!!
        val found = MemoryIndex(ctx.config.memory!!.items.value).search(q, MEMORY_SEARCH_LIMIT)
        ctx.noteMemorySearch(found.map { (m, _) -> m.id })
        return ok {
            put("memories", JsonArray(found.map { (m, _) ->
                buildJsonObject {
                    put("id", m.id); put("kind", m.kind.name); put("content", m.content)
                    // Mika 自己归纳出来的:不是用户说的,标出来
                    if (m.source.type == MemoryContext.INFERRED) put("inferred", true)
                }
            }))
        }
    }

    /** 记忆里的是用户以前说的:作为用户给的事实交给核查。Mika 自己归纳出来的那几条不是用户说的,另作一条工具查到的事实。 */
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val (own, said) = (r.content["memories"] as? JsonArray).orEmpty().partition { (it as? JsonObject)?.get("inferred") == JsonPrimitive(true) }
        fun lines(list: List<JsonElement>, head: String) =
            list.joinToString("\n", head) { m -> ((m as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty() }
        return listOfNotNull(
            said.takeIf { it.isNotEmpty() }?.let { Observation(ObsKind.UserSaid, lines(it, "记忆检索「${o.argStr("query")}」:\n"), source = "memory") },
            // 归纳的那几条是工具查到的东西,不是用户给的事实:进账本,写明出处
            own.takeIf { it.isNotEmpty() }?.let {
                Observation(ObsKind.Fact, lines(it, "记忆检索「${o.argStr("query")}」查到的、Mika 以前自己归纳的(不是用户说的):\n"), source = "memory")
            },
        )
    }
}

/** 只改 Mika 自己的记忆,不动屏幕和外部世界。 */
internal object MemorySave : AgentTool {
    override val verb = Verb.MemorySave
    override val label = "写入记忆"
    override val risk = Risk.Read
    override val def = ToolDef("memory_save",
        Prompts.tool("memory_save"),
        mapOf(
            "kind" to str("fact = about the user, preference = what the user likes, procedure = how the user wants a kind of task done.", listOf("fact", "preference", "procedure")),
            "content" to str("One sentence in the third person, at most 200 characters, e.g. 用户点外卖不要香菜."),
            "quote" to str("The user's exact words this is based on."),
            "keywords" to buildJsonObject {
                put("type", "array"); put("description", "Optional, up to 8 search words incl. synonyms.")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("kind", "content", "quote"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val kind = a.str("kind")!!.let { k -> MemoryKind.entries.find { it.name == k } ?: throw ArgError("kind", "must be fact, preference or procedure") }
        val content = a.str("content", max = MemoryStore.MAX_CONTENT)!!
        val quote = a.str("quote", max = 1000)!!
        val keywords = a.strList("keywords", 0, MemoryStore.MAX_KEYWORDS, itemMax = 30) ?: emptyList()
        val utterances = ctx.config.utterances!!
        // 引文出自本轮用户原话,或严格登记的上一轮明确确认;屏幕、网页和助手建议本身都不算
        if (!utterances.containsMemoryQuote(quote)) return refused(ctx, a, "quote_not_from_user", Feedback["memory.quote_not_from_user"])
        val store = ctx.config.memory!!
        // 引文对上了,内容里的具体值(地址、号码、名字)也得是用户说过的,不能从屏幕上补。
        // 用户打开了「让 Mika 自己归纳着记」、这一轮又亲口让它记的时候不拒:照存,但来源记成 Mika 归纳的,不冒充用户说的
        val inferred = (listOf(content) + keywords).any { utterances.unsupportedMemorySpecifics(it, quote).isNotEmpty() }
        if (inferred) {
            if (!store.inferenceAllowed()) return refused(ctx, a, "content_not_from_user", Feedback["memory.content_not_from_user"])
            // 开着也不是见什么收什么:引文得落在用户这一轮自己说的、让它记的那半句上。
            // 不然随便一句「帮我看看这个网页」都能被拿来当引文,把屏幕、网页上的字存进来;
            // 「别记我的地址,记住我喜欢喝茶」里引前半句也不行——让记的是后半句
            if (utterances.quotedClauses(quote).none { clauses -> clauses.any(::asksToRemember) }) {
                return refused(ctx, a, "inference_not_requested", Feedback["memory.inference_not_requested"])
            }
        }
        val added = store.add(kind, content, keywords, MemorySource(if (inferred) MemoryContext.INFERRED else "user_said", quote.trim(),
            ctx.config.conversationId, store.clock(), ctx.config.runSpec?.provenance?.origin?.name))
        added.getOrNull()?.let(ctx::noteSaved)
        return added.fold(
            onSuccess = { c ->
                ok {
                    put("id", c.item.id); put("kind", c.item.kind.name); put("status", c.item.status.name)
                    if (inferred) { put("inferred", true); put("note", Feedback["memory.saved_as_inferred"]) }
                }
            },
            onFailure = { e ->
                when ((e as? MemoryRejected)?.code) {
                    "sensitive" -> refused(ctx, a, "sensitive", Feedback["memory.sensitive"])
                    "memory_full" -> fail("memory_full", Feedback["memory.full"], recoverable = false)
                    "unavailable" -> fail("memory_unavailable", Feedback["memory.unavailable"], recoverable = false)
                    "too_long" -> throw ArgError("content", "must be 1-${MemoryStore.MAX_CONTENT} characters")
                    else -> throw e
                }
            },
        )
    }

    /**
     * 这半句让它记:记住、记下、写入记忆、存起来、保存、别忘了、remember、save、add … to memory。只认中文和英文的动词说法
     * (改删记忆的 [MemoryEdit] 也只认这两种),光提到「记忆」「memory」这个词不算,别的语言一律按没让它记处理——
     * 认错了就是把用户没同意的东西存进去,宁可不认。只在收「Mika 自己归纳的」时用:用户原话照旧不需要这些字眼。
     */
    private val SAVE_REQUEST = Regex(
        "[记記](?:住|下|一下|一[条條]|一[笔筆]|[几幾][条條]|到|[进進]|入|[着著]|好|上|吧|[录錄](?:一下|下|到|[进進]|在))|" +
            "[写寫][入进進到]|存[入进進到下一上吧]|存起[来來]|保存|儲存|[别別]忘|不要忘|(?:加|添加|放|收)[到进進入]\\S{0,8}?(?:记忆|記憶)|" +
            "\\b(?:remember|memori[sz]e|save|store|note\\s+(?:that|this|it|down)|keep\\s+in\\s+mind|" +
            "(?:add|write|put|commit)\\b[^,.;!?\\n]{0,40}?\\b(?:to|in|into)\\s+(?:your\\s+|the\\s+|my\\s+)?memor(?:y|ies))\\b",
        RegexOption.IGNORE_CASE,
    )
    /** 问它还记不记得,不是让它记。 */
    private val RECALL_QUESTION = Regex("\\b(?:do|did)\\s+you\\s+(?:still\\s+|even\\s+)?remember\\b|\\bwhat\\s+(?:do|did|can)\\s+you\\s+remember\\b", RegexOption.IGNORE_CASE)
    /** 这半句是不让记:「别记」「不要把这些存进去」「don't save」。「别忘了记下来」「不记得」不是。 */
    private val SAVE_NEGATION = Regex(
        "(?<![能要用可])(?:不|别|別|勿|无需|無需|无须|無須|禁止)\\s*(?:要|用|再|去|必|该|該|应该|應該|许|許|准|可以|能)?\\s*(?:把[^,，。;；!！?？\\n]{0,12}?)?(?:给我|給我|帮我|幫我|替我)?\\s*" +
            "(?:[记記](?![得忆憶性])|[写寫][入进進到下]|存(?![在款货貨量储儲])|保存|儲存)|" +
            "\\b(?:do\\s+not|don'?t|never|no\\s+need\\s+to|not\\s+to|shouldn'?t|should\\s+not|mustn'?t|must\\s+not|won'?t|without)\\s+" +
            "(?:ever\\s+|actually\\s+|please\\s+|you\\s+)?(?:remember|memori[sz]e|sav(?:e|ing)|stor(?:e|ing)|not(?:e|ing)|keep(?:ing)?|add(?:ing)?|writ(?:e|ing)|put(?:ting)?|commit(?:ting)?)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** 这半句话让它记了没有:有让它记的动词说法,而且不是不让记。 */
    private fun asksToRemember(clause: String): Boolean {
        val c = clause.replace('’', '\'')
        return SAVE_REQUEST.containsMatchIn(RECALL_QUESTION.replace(c, " ")) && !SAVE_NEGATION.containsMatchIn(c)
    }
}

/** 用户本轮明确要求时,按 memory_search 返回的稳定编号改或删一条;变更交给现有结果提示与撤销机制。 */
internal object MemoryEdit : AgentTool {
    override val verb = Verb.MemoryEdit
    override val label = "修改记忆"
    override val risk = Risk.Read
    override val def = ToolDef("memory_edit",
        Prompts.tool("memory_edit"),
        mapOf(
            "op" to str("update = replace content and optionally keywords; delete = forget the entry.", listOf("update", "delete")),
            "id" to str("Exact stable id returned by memory_search. Never guess an id or choose between multiple plausible entries."),
            "content" to str("For update: the replacement sentence, at most 200 characters."),
            "quote" to str("The current user message explicitly requesting this update or deletion."),
            "keywords" to buildJsonObject {
                put("type", "array"); put("description", "For update: optional replacement search words, up to 8.")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("op", "id", "quote"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val op = a.str("op")!!
        val id = a.str("id", max = 200)!!
        val quote = a.str("quote", max = 1000)!!
        val content = a.str("content", max = MemoryStore.MAX_CONTENT)
        val keywords = a.strList("keywords", 0, MemoryStore.MAX_KEYWORDS, itemMax = 30)
        if (op == "update" && content == null) throw ArgError("content", "required for update")
        val utterances = ctx.config.utterances!!
        // 用含引文的完整原话判断否定，避免模型只摘出「不要删除」里的「删除」。
        val messages = utterances.matchingCurrentMessages(quote)
        if (messages.isEmpty()) return refused(ctx, a, "quote_not_from_user", Feedback["memory.edit_quote_not_from_user"])
        val (request, negation) = when (op) {
            "update" -> UPDATE_REQUEST to UPDATE_NEGATION
            "delete" -> DELETE_REQUEST to DELETE_NEGATION
            else -> throw ArgError("op", "must be update or delete")
        }
        // 话里明说了要改(删),没说不要,也不是「如果我说……你会怎么做」这种只是问问的
        val requested = request.containsMatchIn(quote) && messages.none(negation::containsMatchIn) && messages.none(::onlyAsking)
        if (!requested) return refused(ctx, a, "edit_not_requested", Feedback["memory.edit_not_requested"])
        val candidates = ctx.memorySearchCandidates()
        if (candidates == null || id !in candidates) {
            return fail("not_found", Feedback["memory.not_found"], recoverable = true)
        }
        if (candidates.size > 1 && !quotesExactId(quote, id)) {
            return fail("ambiguous_target", Feedback["memory.ambiguous_target"], recoverable = false)
        }
        val store = ctx.config.memory!!
        // 这一条原来的样子:里面本来就有的具体值(地址、数字、名字)照样带着不算新补的
        val before = store.items.value.firstOrNull { it.id == id }
        var carried = false
        if (op == "update") {
            val texts = listOf(content!!) + keywords.orEmpty()
            val known = before?.let { listOf(it.content) + it.keywords }.orEmpty()
            if (texts.any { utterances.unsupportedEditSpecifics(it, quote, known).isNotEmpty() }) {
                return refused(ctx, a, "content_not_from_user", Feedback["memory.edit_content_not_from_user"])
            }
            // 新内容里有靠原来那条才站得住的具体值(用户这一轮没说)
            carried = texts.any { utterances.unsupportedSpecifics(it, quote).isNotEmpty() }
        }
        // Mika 自己归纳的那条改写之后还是它归纳的:里面的具体值仍然不是用户说的。用户这一轮把内容都亲口说了的,才改记成用户说的
        val type = if (carried && before?.source?.type == MemoryContext.INFERRED) MemoryContext.INFERRED else "user_said"
        val changed: Result<Change> = when (op) {
            "update" -> store.update(id, content!!, keywords,
                MemorySource(type, quote.trim(), ctx.config.conversationId, store.clock(), ctx.config.runSpec?.provenance?.origin?.name))
            "delete" -> store.forget(id)
            else -> throw ArgError("op", "must be update or delete")
        }
        changed.getOrNull()?.let(ctx::noteSaved)
        return changed.fold(
            onSuccess = { c ->
                ok {
                    put("op", op); put("id", id)
                    when (c) {
                        is Change.Updated -> { put("kind", c.item.kind.name); put("status", c.item.status.name) }
                        is Change.Forgot -> put("deleted", true)
                        else -> Unit
                    }
                }
            },
            onFailure = { e ->
                when ((e as? MemoryRejected)?.code) {
                    "sensitive" -> refused(ctx, a, "sensitive", Feedback["memory.sensitive"])
                    "unavailable" -> fail("memory_unavailable", Feedback["memory.unavailable"], recoverable = false)
                    "not_found" -> fail("not_found", Feedback["memory.not_found"], recoverable = true)
                    "duplicate_id" -> fail("ambiguous_target", Feedback["memory.ambiguous_target"], recoverable = false)
                    "too_long" -> throw ArgError("content", "must be 1-${MemoryStore.MAX_CONTENT} characters")
                    else -> throw e
                }
            },
        )
    }

    private fun quotesExactId(quote: String, id: String): Boolean =
        Regex("(?<![A-Za-z0-9_-])" + Regex.escape(id) + "(?![A-Za-z0-9_-])").containsMatchIn(quote)

    // 要改:「改」字的各种说法(改成、改得简洁一点、改一下;「改天」「改变」这类不算),和精简、合并、整理、换成这些说的就是改的词。
    // 只认这几个固定说法的话,「把那几条改得简洁一点」会被当成没说要改
    private const val UPDATE_WORDS = "修改|改(?![天日期变變革善观觀口])|更新|更正|编辑|編輯|精简|精簡|缩短|縮短|简化|簡化|压缩|壓縮|合并|合併|整理|重写|重寫|换成|換成|替换|替換|调整|調整|润色|潤色"
    private const val UPDATE_WORDS_EN = "update|change|edit|correct|rewrite|reword|rephrase|shorten|simplify|merge|replace|trim|tidy"
    private const val DELETE_WORDS = "删|刪|忘掉|忘记|忘記|去掉|移除|清掉|清除"
    private const val DELETE_WORDS_EN = "delete|forget|remove|erase|drop"
    // 「能不能改」「要不要删」是在问、在请它做,不是不让:前面紧挨着同一个字的「不」不算否定
    private const val NOT = "(?<![能要用可])(?:不|别|別|无需|無需|无须|無須|不用|不必|不要|不能|不准|禁止)\\s*(?:要|用|再|去)?\\s*"
    private const val NOT_EN = "\\b(?:do\\s+not|don't|dont|never|no\\s+need\\s+to)\\s+"
    private val UPDATE_REQUEST = Regex("$UPDATE_WORDS|\\b(?:$UPDATE_WORDS_EN)\\b", RegexOption.IGNORE_CASE)
    private val DELETE_REQUEST = Regex("$DELETE_WORDS|别记|別記|不要记|不要記|取消记忆|取消記憶|\\b(?:$DELETE_WORDS_EN)\\b", RegexOption.IGNORE_CASE)
    private val UPDATE_NEGATION = Regex("$NOT(?:$UPDATE_WORDS)|$NOT_EN(?:$UPDATE_WORDS_EN)\\b", RegexOption.IGNORE_CASE)
    private val DELETE_NEGATION = Regex("$NOT(?:$DELETE_WORDS)|$NOT_EN(?:$DELETE_WORDS_EN)\\b", RegexOption.IGNORE_CASE)

    /**
     * 「如果我说把那条删掉,你会怎么处理」:在问它会怎么做,不是让它现在做。改删记忆不过「讨论还是操作」那一关
     * (见 AgentLoop 的 MEMORY_WRITE_VERBS),这种问法在这里拒。两样都有才算:假设的说法,加上问它会怎么做——
     * 只有假设的条件句常常就是要记的内容(「如果我说老地方,就是公司楼下那家」)。
     */
    private val HYPOTHETICAL = Regex("(?:如果|假如|要是|假设|假設|假使)我(?:说|說|让|讓|叫|要)|\\bif\\s+i\\s+(?:said|asked|told|were\\s+to)\\b", RegexOption.IGNORE_CASE)
    private val ASKING_HOW = Regex("你会(?:怎么|怎样|如何)|你會(?:怎麼|怎樣|如何)|会(?:发生什么|怎么样)|會(?:發生什麼|怎麼樣)|\\b(?:what|how)\\s+would\\s+you\\b|\\bwhat\\s+would\\s+happen\\b", RegexOption.IGNORE_CASE)
    private fun onlyAsking(message: String) = HYPOTHETICAL.containsMatchIn(message) && ASKING_HOW.containsMatchIn(message)
}
