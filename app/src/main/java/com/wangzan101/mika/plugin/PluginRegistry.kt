package com.wangzan101.mika.plugin

import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.tool.PluginToolSpec
import com.wangzan101.mika.core.learn.skills.PluginSkills
import com.wangzan101.mika.core.tool.Risk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 已安装插件与启用状态的唯一入口。磁盘布局见 `PluginInstaller`:
 * `files/plugins/<id>/` 是包内容,`files/plugins/registry.json` 只存安装信息与启停开关。
 * 启动时按目录里的 manifest 对账,目录消失的记录自动丢弃。
 */
class PluginRegistry(
    private val filesDir: File,
    private val scope: CoroutineScope,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val root = File(filesDir, "plugins")
    private val file = File(root, REGISTRY)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Any()
    private val _plugins = MutableStateFlow<List<PluginRecord>>(emptyList())

    val plugins: StateFlow<List<PluginRecord>> = _plugins.asStateFlow()

    private val skills = PluginSkillsProvider({ _plugins.value }, root, log)

    /** 给技能目录用的只读来源:每次查找现读启停状态。 */
    val skillsProvider: PluginSkills get() = skills

    init {
        reload()
    }

    /** 任务开始时固定一份,本次任务里启用/停用从下一次任务生效。 */
    fun skillsSnapshot(): PluginSkills = skills.snapshot()

    fun enabledPlugins(): List<PluginRecord> = _plugins.value.filter { it.enabled }

    fun record(id: String): PluginRecord? = _plugins.value.firstOrNull { it.id == id }

    fun manifest(id: String): PluginManifest? {
        if (!PluginManifest.ID.matches(id)) return null
        return runCatching { PluginManifest.parse(File(File(root, id), PluginInstaller.MANIFEST).readText()) }
            .onFailure { log("could not read manifest of $id", it) }
            .getOrNull()
    }

    /** 启用插件的工具规格(名字拼成 `plugin_<id去点>_<工具名>`);脚本目录是插件目录。 */
    fun toolSpecs(): List<PluginToolSpec> = enabledPlugins().flatMap { record ->
        val m = manifest(record.id) ?: return@flatMap emptyList<PluginToolSpec>()
        val dir = pluginDir(record.id) ?: return@flatMap emptyList<PluginToolSpec>()
        m.tools.map { tool ->
            PluginToolSpec(
                name = "plugin_${record.id.replace('.', '_')}_${tool.name}",
                description = tool.description,
                scriptPath = tool.script ?: "",
                scriptDir = dir,
                inputSchema = tool.inputSchema ?: JsonObject(emptyMap()),
                timeoutSec = tool.timeoutSec,
                risk = tool.risk.toRisk(),
            )
        }.distinctBy { it.name }
    }

    /** 重新扫目录对账;启动时和安装后调用。 */
    fun reload() = synchronized(lock) {
        root.mkdirs()
        val stored = readRegistry()
        val known = stored.associateBy { it.id }
        val found = mutableListOf<PluginRecord>()
        val dirs = root.listFiles()
            ?.filter { it.isDirectory && it.name != STAGING && it.name != TRASH }
            .orEmpty()
            .sortedBy { it.name }
        for (dir in dirs) {
            val manifest = runCatching { PluginManifest.parse(File(dir, PluginInstaller.MANIFEST).readText()) }
                .onFailure { log("could not read ${dir.name}/manifest.json", it) }
                .getOrNull() ?: continue
            // 目录名必须和 manifest id 一致,避免读到别的插件的数据
            if (manifest.id != dir.name) {
                log("ignoring ${dir.name}: manifest id is ${manifest.id}", null)
                continue
            }
            if (manifest.validate(appVersion).isNotEmpty()) continue
            val old = known[manifest.id]
            found += PluginRecord(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                enabled = old?.enabled ?: true,
                installedAt = old?.installedAt ?: dir.lastModified(),
                skillNames = manifest.skills.map { it.name },
                description = manifest.description,
                permissions = manifest.permissions,
            )
        }
        val next = found.sortedBy { it.name.lowercase() }
        _plugins.value = next
        if (next != stored) writeRegistry(next)
    }

    fun reloadAsync(): Job = scope.launch(Dispatchers.IO) { reload() }

    /** 安装器成功把目录换入后登记;同 id 升级保留原来的启用状态和安装时间。 */
    fun install(record: PluginRecord) = synchronized(lock) {
        val old = _plugins.value.firstOrNull { it.id == record.id }
        val merged = record.copy(
            enabled = old?.enabled ?: true,
            installedAt = old?.installedAt ?: record.installedAt,
        )
        val next = (_plugins.value.filter { it.id != merged.id } + merged).sortedBy { it.name.lowercase() }
        _plugins.value = next
        writeRegistry(next)
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val next = _plugins.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        _plugins.value = next
        writeRegistry(next)
    }

    /** 删插件目录与注册表项;用户对话/记忆不受影响。 */
    fun uninstall(id: String): Boolean = synchronized(lock) {
        if (!PluginManifest.ID.matches(id)) return@synchronized false
        val next = _plugins.value.filter { it.id != id }
        if (next.size == _plugins.value.size) return@synchronized false
        File(root, id).deleteRecursively()
        _plugins.value = next
        writeRegistry(next)
        true
    }

    fun pluginDir(id: String): File? =
        if (PluginManifest.ID.matches(id) && _plugins.value.any { it.id == id }) File(root, id) else null

    private fun readRegistry(): List<PluginRecord> {
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString<RegistryFile>(file.readText()).plugins }
            .onFailure { log("could not read $REGISTRY", it) }
            .getOrDefault(emptyList())
    }

    private fun writeRegistry(records: List<PluginRecord>) {
        root.mkdirs()
        val tmp = File(root, "$REGISTRY.tmp")
        tmp.writeText(json.encodeToString(RegistryFile(plugins = records)))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write $REGISTRY" }
        }
    }

    @Serializable
    private data class RegistryFile(val schema: Int = 1, val plugins: List<PluginRecord> = emptyList())

    private companion object {
        const val REGISTRY = "registry.json"
        const val STAGING = ".staging"
        const val TRASH = ".trash"
    }
}

/** 插件工具声明的风险等级(manifest 里的字符串)映射到 core 的 [Risk];不认识的按只读算。 */
private fun String?.toRisk(): Risk = when (this?.lowercase()) {
    "write" -> Risk.Write
    "external" -> Risk.External
    else -> Risk.Read
}
