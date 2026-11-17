package com.wangzan101.mika.about

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 一个 GitHub 下载镜像。[prefix] 形如 `https://ghfast.top/`,完整地址是 prefix + 官方附件地址。
 * 用户可改名、改地址、调顺序、增删;GitHub 官方始终排在最后兜底,不在这个列表里。
 */
@Serializable
data class UpdateMirror(val id: String, val name: String, val prefix: String)

/** 更新走的镜像设置:总开关和按顺序尝试的列表。 */
@Serializable
data class MirrorSettings(val enabled: Boolean = false, val mirrors: List<UpdateMirror> = emptyList()) {
    companion object {
        val OFF = MirrorSettings()
    }
}

/** 保存镜像列表没通过时的原因。界面只在写入成功后改列表。 */
enum class UpdateMirrorError { InvalidName, InvalidPrefix, Duplicate, TooMany, SaveFailed, Unreadable }

/**
 * 用户可管理的更新镜像列表。文件还不存在时写入 [UpdateMirrors.PRESETS] 一次;
 * 之后用户的增删改、排序就是这份列表本身,升级 App 不会把用户改过的顺序盖掉。
 * 读不出来时不覆盖原文件,要等用户明确恢复默认。
 */
class UpdateMirrorStore(
    private val file: File,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    private val _mirrors = MutableStateFlow<List<UpdateMirror>>(emptyList())
    private val _enabled = MutableStateFlow(false)
    private val _unreadable = MutableStateFlow(false)
    private var loaded = false
    private var attempted = false

    val mirrors: StateFlow<List<UpdateMirror>> = _mirrors.asStateFlow()
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 已有文件读不出来。此时不能增删改,避免用空列表盖掉还没读到的内容。 */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    @Synchronized
    fun ensureLoaded() {
        if (loaded || attempted) return
        attempted = true
        when (val read = read()) {
            Read.Missing -> {
                val seeded = presets()
                // 写不进去也先把预设放进内存:下载和检查更新照样能用,下次改动再写。
                write(false, seeded)
                publish(false, seeded)
            }
            is Read.Ok -> publish(read.enabled, read.mirrors)
            Read.Failed -> {
                _mirrors.value = emptyList()
                _enabled.value = false
                _unreadable.value = true
                loaded = true
            }
        }
    }

    /** 下载和检查更新取这一份:开关和列表一起读,保证是同一次设置。 */
    @Synchronized
    fun snapshot(): MirrorSettings {
        ensureLoaded()
        return MirrorSettings(_enabled.value, _mirrors.value)
    }

    @Synchronized
    fun setEnabled(on: Boolean): UpdateMirrorError? {
        guard()?.let { return it }
        if (_enabled.value == on) return null
        return commit(on, _mirrors.value)
    }

    @Synchronized
    fun add(name: String, prefix: String): UpdateMirrorError? {
        guard()?.let { return it }
        val prefixValue = UpdateMirrors.normalizePrefix(prefix) ?: return UpdateMirrorError.InvalidPrefix
        val nameValue = displayName(name, prefixValue) ?: return UpdateMirrorError.InvalidName
        if (_mirrors.value.size >= UpdateMirrors.MAX) return UpdateMirrorError.TooMany
        if (_mirrors.value.any { it.prefix.equals(prefixValue, ignoreCase = true) }) return UpdateMirrorError.Duplicate
        return commit(_enabled.value, _mirrors.value + UpdateMirror(ids(), nameValue, prefixValue))
    }

    @Synchronized
    fun update(id: String, name: String, prefix: String): UpdateMirrorError? {
        guard()?.let { return it }
        val prefixValue = UpdateMirrors.normalizePrefix(prefix) ?: return UpdateMirrorError.InvalidPrefix
        val nameValue = displayName(name, prefixValue) ?: return UpdateMirrorError.InvalidName
        val index = _mirrors.value.indexOfFirst { it.id == id }
        if (index < 0) return null
        if (_mirrors.value.anyIndexed { i, m -> i != index && m.prefix.equals(prefixValue, ignoreCase = true) }) {
            return UpdateMirrorError.Duplicate
        }
        return commit(
            _enabled.value,
            _mirrors.value.mapIndexed { i, mirror -> if (i == index) mirror.copy(name = nameValue, prefix = prefixValue) else mirror },
        )
    }

    @Synchronized
    fun delete(id: String): UpdateMirrorError? {
        guard()?.let { return it }
        if (_mirrors.value.none { it.id == id }) return null
        return commit(_enabled.value, _mirrors.value.filterNot { it.id == id })
    }

    /** 按拖动开始时看到的完整 ID 列表排序,避免并发增删时丢失镜像。 */
    @Synchronized
    fun reorder(ids: List<String>): UpdateMirrorError? {
        guard()?.let { return it }
        val current = _mirrors.value
        if (ids.size != current.size || ids.toSet() != current.map { it.id }.toSet()) return UpdateMirrorError.SaveFailed
        val byId = current.associateBy { it.id }
        val reordered = ids.map { byId.getValue(it) }
        return if (reordered == current) null else commit(_enabled.value, reordered)
    }

    /** 用户明确要回预设时调用;读不出来的旧文件也在这里被换掉。 */
    @Synchronized
    fun resetDefaults(): UpdateMirrorError? = commit(_enabled.value, presets())

    private fun presets() = UpdateMirrors.PRESETS.map { UpdateMirror(ids(), it.name, it.prefix) }

    private fun guard(): UpdateMirrorError? {
        ensureLoaded()
        return if (_unreadable.value) UpdateMirrorError.Unreadable else null
    }

    private fun publish(enabled: Boolean, mirrors: List<UpdateMirror>) {
        _enabled.value = enabled
        _mirrors.value = mirrors
        _unreadable.value = false
        loaded = true
    }

    private fun commit(enabled: Boolean, mirrors: List<UpdateMirror>): UpdateMirrorError? {
        if (!write(enabled, mirrors)) return UpdateMirrorError.SaveFailed
        publish(enabled, mirrors)
        return null
    }

    private fun read(): Read {
        if (!file.exists()) return Read.Missing
        if (!file.isFile) return Read.Failed
        return try {
            val o = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return Read.Failed
            val enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false
            val array = o["mirrors"] as? JsonArray ?: JsonArray(emptyList())
            val mirrors = array.mapNotNull { element ->
                val m = element as? JsonObject ?: return@mapNotNull null
                val id = text(m, "id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = text(m, "name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val prefix = text(m, "prefix")?.let(UpdateMirrors::normalizePrefix) ?: return@mapNotNull null
                UpdateMirror(id, name, prefix)
            }.distinctBy { it.id }
            Read.Ok(enabled, mirrors)
        } catch (_: SerializationException) {
            Read.Failed
        } catch (_: IOException) {
            Read.Failed
        }
    }

    private fun write(enabled: Boolean, mirrors: List<UpdateMirror>): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        return try {
            val json = storageJson.encodeToString(MirrorSettings(enabled, mirrors))
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
        data class Ok(val enabled: Boolean, val mirrors: List<UpdateMirror>) : Read
        data object Failed : Read
    }

    /** 空名字用主机名;太长返回空(调用方报 [UpdateMirrorError.InvalidName])。 */
    private fun displayName(name: String, prefix: String): String? {
        val trimmed = name.trim()
        if (trimmed.codePointCount(0, trimmed.length) > UpdateMirrors.NAME_MAX) return null
        return trimmed.ifEmpty { UpdateMirrors.host(prefix) }
    }

    companion object {
        const val FILE = "update-mirrors.json"
        private val storageJson = Json { encodeDefaults = true }

        private fun text(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean =
            indices.any { predicate(it, this[it]) }
    }
}

/** 镜像地址的规则、预设,以及一次更新要尝试的完整地址列表。 */
object UpdateMirrors {
    /** 再多会让一次检查同时打出去太多请求。 */
    const val MAX = 8
    const val NAME_MAX = 40

    /**
     * 预设:2026-11-15 实测能下载 Mika 发布附件的服务。地址只保留协议、主机和路径,末尾补斜杠;
     * 完整下载地址是 `prefix + https://github.com/...`。前两个还能用于检查更新(一个走 API,一个走发布页)。
     */
    val PRESETS = listOf(
        UpdateMirror("preset-gh-proxy", "GH-Proxy", "https://gh-proxy.com/"),
        UpdateMirror("preset-ghfast", "GhFast", "https://ghfast.top/"),
        UpdateMirror("preset-ghproxy-net", "GhProxy.net", "https://ghproxy.net/"),
    )

    /**
     * 把用户输入的镜像地址规范成 `https://主机[:端口][/路径]/`:
     * 只接受 HTTPS,不带账号密码、查询串和片段;不合法时返回空。
     */
    fun normalizePrefix(raw: String): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.query != null || url.fragment != null) return null
        if (url.host.isBlank()) return null
        val base = url.newBuilder().query(null).fragment(null).build().toString()
        return base.trimEnd('/') + "/"
    }

    fun host(prefix: String): String = prefix.toHttpUrlOrNull()?.host.orEmpty()

    /**
     * 一次更新按顺序尝试的完整地址:用户镜像(开启时,按列表顺序)在前,GitHub 官方永远最后。
     * 关闭镜像或列表为空时只有官方地址。
     */
    fun candidates(officialUrl: String, settings: MirrorSettings): List<String> = buildList {
        if (settings.enabled) settings.mirrors.forEach { add(it.prefix + officialUrl) }
        add(officialUrl)
    }.distinct()
}
