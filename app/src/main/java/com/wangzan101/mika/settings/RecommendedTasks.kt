package com.wangzan101.mika.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.UUID

/** 首页上的一条推荐任务。点它只把 [prompt] 填进输入框,不自动发送。 */
data class RecommendedTask(val id: String, val title: String, val prompt: String, val fillIn: Boolean = false)

/** 保存没通过时的原因。界面只在写入成功后改列表。 */
enum class RecommendedTaskError { BlankTitle, BlankPrompt, TooLong, TooMany, TooManyFields, SaveFailed, Unreadable }

/**
 * 首页推荐任务。和内置示例是同一份列表:文件还不存在时用 [ensureLoaded] 传入的内容初始化一次,
 * 之后用户的增删改就是这份列表本身。空列表也要写进文件,避免下次启动把删掉的默认任务加回来。
 * 文件读不出来时不覆盖原文件,要等用户明确清空。
 */
class RecommendedTaskStore(private val file: File, private val ids: () -> String = { UUID.randomUUID().toString() }) {
    private val _tasks = MutableStateFlow<List<RecommendedTask>>(emptyList())
    private val _unreadable = MutableStateFlow(false)
    private var loaded = false
    private var attempted = false

    val tasks: StateFlow<List<RecommendedTask>> = _tasks.asStateFlow()

    /** 已有文件读不出来。此时不能增删改,避免用空列表盖掉还没读到的内容。 */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    /**
     * 第一次打开时载入列表:文件还没有时用 [seed] 初始化一次。已有列表时把用户没动过的、
     * 已经下线不再内置的示例([retired])剔掉;改过标题或提示词的、自己加的一条不动。
     */
    @Synchronized
    fun ensureLoaded(retired: () -> List<Pair<String, String>> = { emptyList() }, seed: () -> List<Pair<String, String>>) {
        if (loaded || attempted) return
        attempted = true
        when (val read = read()) {
            Read.Missing -> {
                val seeded = seed().mapNotNull { (title, prompt) ->
                    (normalize(title, prompt) as? Normalized.Ok)?.let { RecommendedTask(ids(), it.title, it.prompt) }
                }
                if (write(seeded)) publish(seeded)
            }
            is Read.Ok -> {
                val gone = retired().toSet()
                publish(if (gone.isEmpty()) read.tasks else read.tasks.filterNot { (it.title to it.prompt) in gone })
            }
            Read.Corrupt, Read.Failed -> {
                _tasks.value = emptyList()
                _unreadable.value = true
                loaded = true
            }
        }
    }

    @Synchronized
    fun add(title: String, prompt: String, fillIn: Boolean = false): RecommendedTaskError? {
        guard()?.let { return it }
        val text = normalize(title, prompt)
        if (text is Normalized.Invalid) return text.error
        if (_tasks.value.size >= MAX_TASKS) return RecommendedTaskError.TooMany
        val ok = text as Normalized.Ok
        if (fillIn && TaskTemplate.fields(ok.prompt).size > TaskTemplate.MAX_FIELDS) return RecommendedTaskError.TooManyFields
        return commit(_tasks.value + RecommendedTask(ids(), ok.title, ok.prompt, fillIn))
    }

    @Synchronized
    fun update(id: String, title: String, prompt: String, fillIn: Boolean? = null): RecommendedTaskError? {
        guard()?.let { return it }
        val text = normalize(title, prompt)
        if (text is Normalized.Invalid) return text.error
        val index = _tasks.value.indexOfFirst { it.id == id }
        if (index < 0) return null
        val ok = text as Normalized.Ok
        val enabled = fillIn ?: _tasks.value[index].fillIn
        if (enabled && TaskTemplate.fields(ok.prompt).size > TaskTemplate.MAX_FIELDS) return RecommendedTaskError.TooManyFields
        return commit(_tasks.value.mapIndexed { i, task -> if (i == index) task.copy(title = ok.title, prompt = ok.prompt, fillIn = enabled) else task })
    }

    @Synchronized
    fun delete(id: String): RecommendedTaskError? {
        guard()?.let { return it }
        if (_tasks.value.none { it.id == id }) return null
        return commit(_tasks.value.filterNot { it.id == id })
    }

    /** 按拖动开始时看到的完整 ID 列表排序，避免并发增删时丢失任务。 */
    @Synchronized
    fun reorder(ids: List<String>): RecommendedTaskError? {
        guard()?.let { return it }
        val current = _tasks.value
        if (ids.size != current.size || ids.toSet() != current.map { it.id }.toSet()) return RecommendedTaskError.SaveFailed
        val byId = current.associateBy { it.id }
        val reordered = ids.map { byId.getValue(it) }
        return if (reordered == current) null else commit(reordered)
    }

    /** 用户明确不要读不出来的旧文件时,换成空列表。 */
    @Synchronized
    fun discardUnreadable(): RecommendedTaskError? {
        if (!_unreadable.value) return null
        return commit(emptyList())
    }

    private fun guard(): RecommendedTaskError? = when {
        _unreadable.value -> RecommendedTaskError.Unreadable
        !loaded && file.exists() -> RecommendedTaskError.Unreadable
        else -> null
    }

    private fun publish(tasks: List<RecommendedTask>) {
        _tasks.value = tasks
        _unreadable.value = false
        loaded = true
    }

    private fun commit(tasks: List<RecommendedTask>): RecommendedTaskError? {
        if (!write(tasks)) return RecommendedTaskError.SaveFailed
        publish(tasks)
        return null
    }

    private fun read(): Read {
        if (!file.exists()) return Read.Missing
        if (!file.isFile) return Read.Failed
        return try {
            val array = Json.parseToJsonElement(file.readText()) as? JsonArray ?: return Read.Corrupt
            val tasks = array.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = text(o, "id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = text(o, "title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val prompt = text(o, "prompt")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RecommendedTask(id, title, prompt, (o["fillIn"] as? JsonPrimitive)?.booleanOrNull == true)
            }.distinctBy { it.id }
            Read.Ok(tasks)
        } catch (_: SerializationException) {
            Read.Corrupt
        } catch (_: IOException) {
            Read.Failed
        }
    }

    private fun write(tasks: List<RecommendedTask>): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        return try {
            val json = JsonArray(tasks.map { task ->
                buildJsonObject {
                    put("id", task.id)
                    put("title", task.title)
                    put("prompt", task.prompt)
                    if (task.fillIn) put("fillIn", true)
                }
            }).toString()
            // 先写临时文件再改名,写到一半被杀不会留下半个文件。目标已存在时改名会失败,这时再覆盖写。
            val tmp = File(parent, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
            file.isFile
        } catch (_: IOException) {
            false
        }
    }

    private sealed interface Read {
        data object Missing : Read
        data class Ok(val tasks: List<RecommendedTask>) : Read
        data object Corrupt : Read
        data object Failed : Read
    }

    private sealed interface Normalized {
        data class Ok(val title: String, val prompt: String) : Normalized
        data class Invalid(val error: RecommendedTaskError) : Normalized
    }

    companion object {
        const val FILE = "recommended-tasks-v2.json"
        const val TITLE_MAX = 500
        const val PROMPT_MAX = 8_000
        const val MAX_TASKS = 50

        private fun text(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** 只去掉首尾空白来判断空;中间的空格和换行留着。 */
        private fun normalize(title: String, prompt: String): Normalized {
            val t = title.trim()
            val p = prompt.trim()
            return when {
                t.isEmpty() -> Normalized.Invalid(RecommendedTaskError.BlankTitle)
                p.isEmpty() -> Normalized.Invalid(RecommendedTaskError.BlankPrompt)
                t.codePointCount(0, t.length) > TITLE_MAX || p.codePointCount(0, p.length) > PROMPT_MAX ->
                    Normalized.Invalid(RecommendedTaskError.TooLong)
                else -> Normalized.Ok(t, p)
            }
        }
    }
}
