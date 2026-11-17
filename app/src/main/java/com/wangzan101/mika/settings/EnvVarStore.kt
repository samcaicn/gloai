package com.wangzan101.mika.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 设置 › 环境变量:名字=值 的键值对,插件脚本和提示词钩子跑起来时经它拿到(如 `MIKA_TRANSLATE_BASE/KEY/MODEL`)。
 * 值可能含密钥,但按「功能前置、安全后置」暂存明文;加密留作后置项。
 */
class EnvVarStore(private val dir: File) {
    private val file = File(dir, FILE)
    @Volatile private var cached: Map<String, String>? = null
    private val _changes = MutableStateFlow(emptyMap<String, String>())

    /** 给设置页观察的当前值;每次改动后更新。 */
    val changes: StateFlow<Map<String, String>> get() = _changes.asStateFlow()

    @Synchronized fun all(): Map<String, String> {
        cached?.let { return it }
        val m = runCatching { read() }.getOrDefault(emptyMap())
        cached = m
        _changes.value = m
        return m
    }

    /** 存下 [k]=[v];[v] 为空等于删掉这一条。 */
    fun set(k: String, v: String) {
        synchronized(this) {
            val next = all().toMutableMap()
            if (v.isEmpty()) next.remove(k) else next[k] = v
            write(next)
        }
    }

    fun remove(k: String) {
        synchronized(this) {
            val next = all().toMutableMap()
            next.remove(k)
            write(next)
        }
    }

    fun clear() {
        synchronized(this) { write(emptyMap()) }
    }

    private fun read(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val obj = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return emptyMap()
        return obj.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }

    private fun write(m: Map<String, String>) {
        dir.mkdirs()
        val obj = buildJsonObject { m.forEach { (k, v) -> put(k, v) } }
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(obj.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cached = m
        _changes.value = m
    }

    companion object {
        private const val FILE = "env-vars.json"
        private val Json = Json { ignoreUnknownKeys = true }

        @Volatile private var instance: EnvVarStore? = null

        fun get(context: Context): EnvVarStore = instance ?: synchronized(this) {
            instance ?: EnvVarStore(File(context.applicationContext.filesDir, "settings")).also { instance = it }
        }
    }
}
