package com.wangzan101.mika.settings

import com.wangzan101.mika.core.device.access.AppAccessPolicy
import com.wangzan101.mika.core.device.access.AppAccessState
import com.wangzan101.mika.core.device.access.AppCategory
import com.wangzan101.mika.core.device.access.AppClassification
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** 应用权限与分类分开保存;没有记录的包名默认允许。 */
class AppAccessStore(private val file: File) : AppAccessPolicy {
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    @Volatile var sensitiveUi: Boolean = false
        private set
    private var sensitiveCount = 0

    override fun current(): AppAccessState = mutable.value

    @Synchronized fun protectUi(): AutoCloseable {
        sensitiveCount++
        sensitiveUi = true
        var closed = false
        return AutoCloseable { synchronized(this) {
            if (!closed) { closed = true; sensitiveCount--; sensitiveUi = sensitiveCount > 0 }
        } }
    }

    @Synchronized fun setBlocked(pkg: String, blocked: Boolean) = update {
        it.copy(blocked = if (blocked) it.blocked + pkg else it.blocked - pkg, shareAllowed = it.shareAllowed - pkg)
    }

    @Synchronized fun allowAll() = update { it.copy(blocked = emptySet(), shareAllowed = emptySet()) }
    @Synchronized fun setShareAllowed(pkg: String, allowed: Boolean) = update {
        it.copy(shareAllowed = if (allowed) it.shareAllowed + pkg else it.shareAllowed - pkg)
    }

    @Synchronized fun classify(values: Map<String, AppClassification>) = update { s ->
        s.copy(classifications = s.classifications + values.filter { (pkg, c) -> c.manual || s.classifications[pkg]?.manual != true })
    }

    @Synchronized fun blockPackages(packages: Set<String>) = update {
        it.copy(blocked = it.blocked + packages, shareAllowed = it.shareAllowed - packages)
    }

    @Synchronized fun reset() = persist(AppAccessState(revision = current().revision + 1))

    private fun update(change: (AppAccessState) -> AppAccessState) {
        check(current().available) { "Application access settings must be reset before editing." }
        persist(change(current()).copy(revision = current().revision + 1))
    }

    private fun persist(next: AppAccessState) {
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("version", 1)
            put("blocked", JsonArray(next.blocked.sorted().map(::JsonPrimitive)))
            put("shareAllowed", JsonArray(next.shareAllowed.sorted().map(::JsonPrimitive)))
            putJsonObject("classifications") {
                next.classifications.toSortedMap().forEach { (pkg, c) ->
                    putJsonObject(pkg) { put("category", c.category.name); put("reason", c.reason); put("manual", c.manual) }
                }
            }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        mutable.value = next
    }

    private fun load(): AppAccessState {
        if (!file.exists()) return AppAccessState()
        return runCatching {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            require(o["version"]?.jsonPrimitive?.int == 1)
            fun strings(key: String) = o.getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()
            val classes = o.getValue("classifications").jsonObject.mapValues { (_, value) ->
                val c = value.jsonObject
                AppClassification(AppCategory.valueOf(c.getValue("category").jsonPrimitive.content),
                    c.getValue("reason").jsonPrimitive.content, c.getValue("manual").jsonPrimitive.boolean)
            }
            AppAccessState(strings("blocked"), strings("shareAllowed"), classes)
        }.getOrElse { AppAccessState(available = false) }
    }
}
