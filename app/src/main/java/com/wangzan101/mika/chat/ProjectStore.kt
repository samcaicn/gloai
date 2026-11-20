package com.wangzan101.mika.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 项目分组:一组共用同一个沙箱工作区的对话。
 * v1 只保留核心标识、名字和时间戳;更多配置(默认人格、项目说明)以后按可选字段加进 JSON。
 */
@Serializable
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
private data class ProjectFile(val version: Int = 1, val projects: List<Project> = emptyList())

/**
 * 项目列表:`files/projects/projects-v1.json`。原子写、带版本号,读不懂时把原文件另存成 `.bad-<时间>` 并当空列表。
 * 同一进程一份,由 [com.wangzan101.mika.MikaApp] 给出。
 */
class ProjectStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(File(context.filesDir, "projects"))

    private val file = File(dir, "projects-v1.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _projects = MutableStateFlow(load())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()
    private val _deletions = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val deletions: SharedFlow<String> = _deletions.asSharedFlow()

    fun get(id: String?): Project? = id?.takeIf(Companion::isValidId)?.let { key -> _projects.value.firstOrNull { it.id == key } }

    @Synchronized
    fun create(name: String): Result<Project> = runCatching {
        val trimmed = normalizeName(name)
        val now = clock()
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            createdAt = now,
            updatedAt = now,
        )
        validate(project)
        save(_projects.value + project)
        project
    }

    @Synchronized
    fun rename(id: String, name: String): Result<Project> = runCatching {
        require(isValidId(id)) { "invalid project id" }
        val trimmed = normalizeName(name)
        val existing = _projects.value.firstOrNull { it.id == id } ?: error("project not found")
        val next = existing.copy(name = trimmed, updatedAt = clock())
        validate(next)
        save(_projects.value.map { if (it.id == id) next else it })
        next
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val existing = get(id) ?: return false
        val next = _projects.value.filterNot { it.id == existing.id }
        return runCatching { save(next); _deletions.tryEmit(existing.id); true }.getOrDefault(false)
    }

    private fun normalizeName(raw: String): String = raw.trim().also {
        require(it.isNotEmpty()) { "name is required" }
        require(it.length <= MAX_NAME) { "name is too long" }
    }

    private fun validate(project: Project) {
        require(isValidId(project.id)) { "invalid project id" }
        require(project.name.isNotBlank()) { "name is required" }
        require(project.name.length <= MAX_NAME) { "name is too long" }
    }

    @Synchronized
    private fun save(list: List<Project>) {
        dir.mkdirs()
        val text = json.encodeToString(ProjectFile(projects = list))
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
        _projects.value = list
    }

    private fun load(): List<Project> {
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<ProjectFile>(file.readText())
                .projects
                .filter { isValidId(it.id) && it.name.isNotBlank() }
                .distinctBy { it.id }
        }.getOrElse {
            // 读不懂的旧文件留着,别被下一次保存直接盖掉
            runCatching { file.renameTo(File(dir, "${file.name}.bad-${clock()}")) }
            emptyList()
        }
    }

    companion object {
        const val MAX_NAME = 80
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun isValidId(id: String?): Boolean = id != null && ID.matches(id)
    }
}
