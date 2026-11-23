package com.wangzan101.mika.files.ui

import com.wangzan101.mika.chat.FileSource
import com.wangzan101.mika.chat.SharedFile

/** 文件库多选:key 只由来源身份决定,刷新索引后仍能对上。 */
internal object LibrarySelection {
    fun inputKey(conversationId: String, path: String): String = "in:$conversationId:$path"

    /** 有 URI 的产物按 URI 合并;没有 URI 的只能用来源位置区分。 */
    fun outputKey(file: SharedFile, source: FileSource): String =
        if (file.uri.isNotBlank()) "out:uri:${file.uri}"
        else "out:src:${source.conversationId}:${source.turnIndex}:${source.runId}:${source.steerIndex}:${file.savedAs}:${file.size}"

    fun toggle(selected: Set<String>, key: String): Set<String> =
        if (key in selected) selected - key else selected + key

    /** 全选只作用于当前筛选出来的行;这些行都选着时取消它们。 */
    fun toggleAll(selected: Set<String>, visible: List<String>): Set<String> {
        val keys = visible.toSet()
        return if (keys.isNotEmpty() && keys.all { it in selected }) selected - keys else selected + keys
    }

    /** 刷新后去掉已经不存在的行。 */
    fun prune(selected: Collection<String>, valid: Collection<String>): Set<String> {
        val keys = valid.toSet()
        return selected.filterTo(mutableSetOf()) { it in keys }
    }
}
