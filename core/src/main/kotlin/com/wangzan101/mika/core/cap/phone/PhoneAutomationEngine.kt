package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiTreeNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 手机界面上的结构化查询和跨屏文本合并。工具和后续本地调用共用这套语义。 */
internal object PhoneAutomationEngine {
    data class Query(val text: String? = null, val resourceId: String? = null, val role: String? = null)

    fun find(screen: ScreenSnapshot, query: Query, limit: Int): JsonObject {
        val matches = mutableListOf<JsonObject>()
        var total = 0
        fun visit(node: UiTreeNode, parents: List<String>, clickableParent: String?, editableParent: String?) {
            val clickRef = if (node.clickable) node.ref ?: clickableParent else clickableParent
            val editRef = if (node.editable) node.ref ?: editableParent else editableParent
            val own = listOfNotNull(node.text.takeUnless { node.secret }, node.description, node.hintText).filter(String::isNotBlank).joinToString(" ")
            // text 匹配只看节点自身文字(叶子文本节点命中、actionRef 指向可点祖先);resourceId/role 命中容器时才需要后代文字补 name。
            val hit = (query.text == null || own.contains(query.text, ignoreCase = true)) &&
                (query.resourceId == null || node.resourceId?.contains(query.resourceId, ignoreCase = true) == true) &&
                (query.role == null || node.role.contains(query.role, ignoreCase = true))
            if (hit) {
                // 可访问名:点按的容器(tile/列表项)常把可见文字放在子节点上、自身 text/description/hintText 都空;和元素表一致,从后代取名字。
                val name = own.ifBlank { if (node.clickable) descendantLabel(node) else "" }
                total++
                if (matches.size < limit) matches += buildJsonObject {
                    node.ref?.let { put("ref", it) }
                    clickRef?.let { put("actionRef", it) }
                    editRef?.let { put("editRef", it) }
                    put("role", node.role)
                    if (name.isNotBlank() && !node.secret) put("name", name)
                    node.resourceId?.let { put("resourceId", it) }
                    put("bounds", JsonArray(node.bounds.map(::JsonPrimitive)))
                    put("parents", JsonArray(parents.map(::JsonPrimitive)))
                    if (node.clickable) put("clickable", true)
                    if (node.editable) put("editable", true)
                    if (node.scrollable) put("scrollable", true)
                    if (!node.enabled) put("enabled", false)
                }
            }
            val next = (parents + listOfNotNull(node.text.takeUnless { node.secret }, node.description, node.resourceId)
                .firstOrNull { it.isNotBlank() }?.take(100).orEmpty()).filter(String::isNotBlank).takeLast(8)
            node.children.forEach { visit(it, next, clickRef, editRef) }
        }
        screen.windows.forEach { window -> window.nodes.forEach { visit(it, listOfNotNull(window.title, window.packageName), null, null) } }
        if (screen.windows.isEmpty()) {
            screen.elements.forEach { e ->
                if ((query.text == null || (!e.secret && e.name.contains(query.text, true))) &&
                    (query.resourceId == null || e.resourceId?.contains(query.resourceId, true) == true) &&
                    (query.role == null || e.role.contains(query.role, true))) {
                    total++
                    if (matches.size < limit) matches += buildJsonObject {
                        put("ref", e.ref); put("role", e.role)
                        if (e.clickable) put("actionRef", e.ref)
                        if (e.editable) put("editRef", e.ref)
                        if (!e.secret) put("name", e.name)
                        e.resourceId?.let { put("resourceId", it) }
                        put("bounds", JsonArray(e.bounds.map(::JsonPrimitive)))
                        put("parents", JsonArray(emptyList()))
                        if (e.clickable) put("clickable", true)
                        if (e.editable) put("editable", true)
                    }
                }
            }
        }
        return buildJsonObject {
            put("snapshotId", screen.snapshotId)
            put("currentApp", screen.packageName ?: screen.unreadableApp)
            put("total", total)
            put("truncated", total > matches.size)
            put("matches", JsonArray(matches))
        }
    }

    /** 一个节点自身没有可读文字时,用它后代的文字当名字(和元素表给可点容器起名同一规则)。 */
    private fun descendantLabel(node: UiTreeNode): String {
        val parts = mutableListOf<String>()
        fun collect(n: UiTreeNode) {
            listOfNotNull(n.text?.takeUnless { n.secret }, n.description?.takeUnless { n.secret })
                .filter(String::isNotBlank)
                .forEach { if (it !in parts) parts += it }
            n.children.forEach(::collect)
        }
        node.children.forEach(::collect)
        return parts.joinToString(" ")
    }

    /** 重叠区域按整段顺序合并,同文的两条独立消息仍可保留。 */
    fun appendPage(accumulated: List<String>, page: List<String>): List<String> {
        if (page.isEmpty()) return accumulated
        val overlap = (minOf(accumulated.size, page.size) downTo 1).firstOrNull { n -> accumulated.takeLast(n) == page.take(n) } ?: 0
        return accumulated + page.drop(overlap)
    }

    fun prependPage(accumulated: List<String>, page: List<String>): List<String> {
        if (page.isEmpty()) return accumulated
        val overlap = (minOf(accumulated.size, page.size) downTo 1).firstOrNull { n -> page.takeLast(n) == accumulated.take(n) } ?: 0
        return page.dropLast(overlap) + accumulated
    }
}
