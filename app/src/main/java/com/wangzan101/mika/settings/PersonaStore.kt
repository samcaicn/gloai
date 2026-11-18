package com.wangzan101.mika.settings

import com.wangzan101.mika.core.prompt.PersonaPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 一份人格:名字、简介和一段提示词正文。正文由 [com.wangzan101.mika.core.prompt.PersonaPrompt] 拼进系统提示词,
 * 固定头尾由代码写死。人格不带工具、权限、记忆或模型,也不参与它们的选择。
 */
@Serializable
data class Persona(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val prompt: String,
    /** 每次保存 +1;v1 只保留最新正文,旧修订不保留。 */
    val revision: Int = 1,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** v1 不发内置示例;字段预留给以后随包发布。 */
    val builtin: Boolean = false,
)

@Serializable
private data class PersonaFile(val version: Int = 1, val personas: List<Persona> = emptyList())

/**
 * 人格设置:`files/settings/personas.json`。原子写、带版本号,读不懂时把原文件另存成 `.bad-<时间>` 并当空列表。
 * 不含密钥,不加密;同一进程一份,由 [com.wangzan101.mika.MikaApp] 给出。
 */
class PersonaStore(private val dir: File) {
    private val file = File(dir, "personas.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _personas = MutableStateFlow(load())
    val personas: StateFlow<List<Persona>> = _personas.asStateFlow()

    fun get(id: String?): Persona? = id?.takeIf(ID::matches)?.let { key -> _personas.value.firstOrNull { it.id == key } }

    @Synchronized fun add(name: String, description: String, prompt: String): Result<Persona> = runCatching {
        val now = System.currentTimeMillis()
        val persona = Persona(
            name = name.trim(), description = description.trim(), prompt = prompt.trim(),
            createdAt = now, updatedAt = now,
        )
        validate(persona)
        save(_personas.value + persona)
        persona
    }

    @Synchronized fun update(persona: Persona): Result<Persona> = runCatching {
        validate(persona)
        val existing = _personas.value.firstOrNull { it.id == persona.id } ?: error("persona not found")
        val next = persona.copy(
            revision = existing.revision + 1,
            createdAt = existing.createdAt,
            updatedAt = System.currentTimeMillis(),
            builtin = existing.builtin,
        )
        save(_personas.value.map { if (it.id == persona.id) next else it })
        next
    }

    @Synchronized fun duplicate(id: String): Result<Persona> = runCatching {
        val source = get(id) ?: error("persona not found")
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = UUID.randomUUID().toString(), name = "${source.name} (copy)".take(PersonaPrompt.MAX_NAME),
            revision = 1, createdAt = now, updatedAt = now, builtin = false,
        )
        validate(copy)
        save(_personas.value + copy)
        copy
    }

    @Synchronized fun delete(id: String): Boolean {
        val existing = get(id) ?: return false
        if (existing.builtin) return false
        val next = _personas.value.filterNot { it.id == id }
        return runCatching { save(next); true }.getOrDefault(false)
    }

    private fun validate(persona: Persona) {
        require(ID.matches(persona.id)) { "invalid persona id" }
        require(persona.name.isNotBlank()) { "name is required" }
        require(persona.name.length <= PersonaPrompt.MAX_NAME) { "name is too long" }
        require(persona.description.length <= MAX_DESCRIPTION) { "description is too long" }
        require(persona.prompt.length <= PersonaPrompt.MAX_PROMPT_CHARS) { "prompt is too long" }
    }

    @Synchronized private fun save(list: List<Persona>) {
        dir.mkdirs()
        val text = json.encodeToString(PersonaFile(personas = list))
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        _personas.value = list
    }

    private fun load(): List<Persona> {
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<PersonaFile>(file.readText()).personas.filter { ID.matches(it.id) }
        }.getOrElse {
            // 读不懂的旧文件留着,别被下一次保存直接盖掉
            runCatching { file.renameTo(File(dir, "${file.name}.bad-${System.currentTimeMillis()}")) }
            emptyList()
        }
    }

    companion object {
        const val MAX_DESCRIPTION = 160
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun isValidId(id: String?): Boolean = id != null && ID.matches(id)
    }
}
