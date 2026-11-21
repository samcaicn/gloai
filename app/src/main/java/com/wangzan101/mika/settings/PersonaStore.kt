package com.wangzan101.mika.settings

import com.wangzan101.mika.core.prompt.PersonaPrompt
import com.wangzan101.mika.core.prompt.PersonaStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 一份人格:名字、简介、一组选项和一段提示词正文。选项和正文由 [com.wangzan101.mika.core.prompt.PersonaPrompt] 拼进系统提示词,
 * 固定头尾由代码写死。人格不带工具、权限、记忆或模型,也不参与它们的选择。
 */
@Serializable
data class Persona(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val prompt: String,
    /** 界面上选的选项;旧文件里没有这一项时全是默认值。 */
    val style: PersonaStyle = PersonaStyle.DEFAULT,
    /** 称呼:助理的自称;空着就是默认的 Mika。进提示词前由 [PersonaPrompt.nickname] 清洗。 */
    val nickname: String = "",
    /** 每次保存 +1;v1 只保留最新正文,旧修订不保留。 */
    val revision: Int = 1,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** v1 不发内置示例;字段预留给以后随包发布。 */
    val builtin: Boolean = false,
)

/** 这一套的自称:清洗过的称呼,没填就是 Mika。 */
val Persona.assistant: String get() = PersonaPrompt.nickname(nickname)

/** 这一套实际上什么都不改:选项全默认、没有指令、称呼也是默认的。 */
val Persona.changesNothing: Boolean
    get() = style.isDefault && prompt.isBlank() && assistant == PersonaPrompt.DEFAULT_NICKNAME

@Serializable
private data class PersonaFile(
    val version: Int = 1,
    val personas: List<Persona> = emptyList(),
    /** 设置里选的、现在在用的那一套的 id;没有为 null(旧文件没有这一项,等于没有在用的)。 */
    val active: String? = null,
)

/**
 * 人格设置:`files/settings/personas.json`。原子写、带版本号,读不懂时把原文件另存成 `.bad-<时间>` 并当空列表。
 * 除了人格列表,还记着设置里选的「在用的那一套」([activeId]);它和列表在同一次写盘里一起落下,
 * 所以不会出现在用的指向一个已经不在的人格。
 * 不含密钥,不加密;同一进程一份,由 [com.wangzan101.mika.MikaApp] 给出。
 */
class PersonaStore(private val dir: File) {
    private val file = File(dir, "personas.json")
    // coerceInputValues:选项里不认识的取值(以后的版本、手改的文件)按默认读,不让整份文件被当成坏文件
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val _personas: MutableStateFlow<List<Persona>>
    private val _activeId: MutableStateFlow<String?>

    init {
        val loaded = load()
        _personas = MutableStateFlow(loaded.personas)
        _activeId = MutableStateFlow(loaded.active)
    }

    val personas: StateFlow<List<Persona>> = _personas.asStateFlow()

    /** 设置里选的在用的那一套的 id;没有选、或选的那一套被删了为 null。 */
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    /** 在用的那一套;没有为 null(Mika 按平常的方式说话)。 */
    fun active(): Persona? = get(_activeId.value)

    /** 选用一套([id] 必须在列表里),或传 null 回到没有在用的。写进去了返回 true。 */
    @Synchronized fun setActive(id: String?): Boolean {
        if (id != null && get(id) == null) return false
        if (id == _activeId.value) return true
        return runCatching { save(_personas.value, id); true }.getOrDefault(false)
    }

    fun get(id: String?): Persona? = id?.takeIf(ID::matches)?.let { key -> _personas.value.firstOrNull { it.id == key } }

    @Synchronized fun add(
        name: String, description: String, prompt: String, style: PersonaStyle = PersonaStyle.DEFAULT,
    ): Result<Persona> = runCatching {
        val now = System.currentTimeMillis()
        val persona = Persona(
            name = name.trim(), description = description.trim(), prompt = prompt.trim(), style = style,
            createdAt = now, updatedAt = now,
        )
        validate(persona)
        checkRoom()
        save(_personas.value + persona)
        persona
    }

    /**
     * 改在用的那一套,当场写下去。还没有在用的时,用 [defaultName] 新建一套并设为在用(同一次写盘),
     * 用户不用先「建一个人格」再调;但改完仍然什么都不改变(选项全默认、指令为空、称呼是默认的)时不建,免得留下一套空的。
     */
    @Synchronized fun editActive(defaultName: String, change: (Persona) -> Persona): Result<Persona> = runCatching {
        val current = active()
        if (current != null) return@runCatching update(change(current)).getOrThrow()
        val now = System.currentTimeMillis()
        val created = change(Persona(name = defaultName.trim().take(PersonaPrompt.MAX_NAME), prompt = "", createdAt = now, updatedAt = now))
        if (created.changesNothing) return@runCatching created
        validate(created)
        checkRoom()
        save(_personas.value + created, created.id)
        created
    }

    /** 新建一套全默认的并设为在用:用户新建就是为了马上去调,全默认的一套不会让 Mika 变样。 */
    @Synchronized fun create(name: String): Result<Persona> = runCatching {
        val now = System.currentTimeMillis()
        val persona = Persona(name = name.trim(), prompt = "", createdAt = now, updatedAt = now)
        validate(persona)
        checkRoom()
        save(_personas.value + persona, persona.id)
        persona
    }

    @Synchronized fun rename(id: String, name: String): Result<Persona> = runCatching {
        update((get(id) ?: error("persona not found")).copy(name = name.trim())).getOrThrow()
    }

    @Synchronized fun update(persona: Persona): Result<Persona> = runCatching {
        validate(persona)
        val existing = _personas.value.firstOrNull { it.id == persona.id } ?: error("persona not found")
        // 内容没变就不写、不涨修订号(界面上点了现在已经选着的那一档)
        if (persona.copy(revision = existing.revision, createdAt = existing.createdAt, updatedAt = existing.updatedAt, builtin = existing.builtin) == existing) {
            return@runCatching existing
        }
        val next = persona.copy(
            revision = existing.revision + 1,
            createdAt = existing.createdAt,
            updatedAt = System.currentTimeMillis(),
            builtin = existing.builtin,
        )
        save(_personas.value.map { if (it.id == persona.id) next else it })
        next
    }

    /** 复制一份,名称由界面给(各语言的「副本」写法不一样),太长就截;复制出来的不是在用的。 */
    @Synchronized fun duplicate(id: String, name: (String) -> String = { "$it (copy)" }): Result<Persona> = runCatching {
        val source = get(id) ?: error("persona not found")
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = UUID.randomUUID().toString(), name = name(source.name).trim().take(PersonaPrompt.MAX_NAME),
            revision = 1, createdAt = now, updatedAt = now, builtin = false,
        )
        validate(copy)
        checkRoom()
        save(_personas.value + copy)
        copy
    }

    @Synchronized fun delete(id: String): Boolean {
        val existing = get(id) ?: return false
        if (existing.builtin) return false
        val next = _personas.value.filterNot { it.id == id }
        // 删的正是在用的那一套:同一次写盘里把在用的清掉
        return runCatching { save(next, _activeId.value.takeIf { it != id }); true }.getOrDefault(false)
    }

    private fun checkRoom() = check(_personas.value.size < MAX_PERSONAS) { "too many personas" }

    private fun validate(persona: Persona) {
        require(ID.matches(persona.id)) { "invalid persona id" }
        require(persona.name.isNotBlank()) { "name is required" }
        require(persona.name.length <= PersonaPrompt.MAX_NAME) { "name is too long" }
        require(persona.description.length <= MAX_DESCRIPTION) { "description is too long" }
        require(persona.prompt.length <= PersonaPrompt.MAX_PROMPT_CHARS) { "prompt is too long" }
        require(PersonaPrompt.nicknameAccepted(persona.nickname)) { "nickname has characters that cannot be used" }
    }

    @Synchronized private fun save(list: List<Persona>, active: String? = _activeId.value) {
        dir.mkdirs()
        val text = json.encodeToString(PersonaFile(personas = list, active = active))
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        _personas.value = list
        _activeId.value = active
    }

    private class Loaded(val personas: List<Persona>, val active: String?)

    private fun load(): Loaded {
        if (!file.isFile) return Loaded(emptyList(), null)
        return runCatching {
            val stored = json.decodeFromString<PersonaFile>(file.readText())
            val personas = stored.personas.filter { ID.matches(it.id) }
            // 在用的必须指向列表里还在的一套(手改的文件、旧版本留下的悬空 id 都当没有)
            Loaded(personas, stored.active?.takeIf { id -> personas.any { it.id == id } })
        }.getOrElse {
            // 读不懂的旧文件留着,别被下一次保存直接盖掉
            runCatching { file.renameTo(File(dir, "${file.name}.bad-${System.currentTimeMillis()}")) }
            Loaded(emptyList(), null)
        }
    }

    companion object {
        const val MAX_DESCRIPTION = 160
        /** 最多存这么多套;够用,又不至于让列表没法看。 */
        const val MAX_PERSONAS = 20
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun isValidId(id: String?): Boolean = id != null && ID.matches(id)
    }
}
