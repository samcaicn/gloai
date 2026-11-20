package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.chat.ProjectStore

/**
 * 沙箱工作区标识:要么归属单条对话([Conversation]),要么归属整个项目([Project])。
 *
 * 对话与内置固定工作区(`self-check`、`mcp-settings`)沿用原 ID 作为传输标识([wire]),
 * 宿主目录为 `c-<id>`(或 `h-<hash>`);项目工作区使用 `p:<id>` 作为传输标识,宿主目录为 `p-<id>`。
 * 合法对话编号不含 `:`,两者在磁盘目录和序列化键上不会撞名。
 */
sealed interface WorkspaceKey {
    val wire: String

    data class Conversation(val id: String) : WorkspaceKey {
        override val wire: String get() = id
    }

    data class Project(val id: String) : WorkspaceKey {
        init {
            require(ProjectStore.isValidId(id)) { "invalid project workspace id: $id" }
        }

        override val wire: String get() = "$PROJECT_PREFIX$id"
    }

    companion object {
        private const val PROJECT_PREFIX = "p:"

        fun parse(wire: String): WorkspaceKey =
            if (wire.startsWith(PROJECT_PREFIX)) {
                val id = wire.removePrefix(PROJECT_PREFIX)
                if (ProjectStore.isValidId(id)) Project(id) else Conversation(wire)
            } else {
                Conversation(wire)
            }
    }
}
