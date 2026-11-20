package com.wangzan101.mika.terminal

import android.content.Context
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ProjectStore
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.core.cap.sandbox.SandboxShell
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 用户终端:进程里一份,按沙箱工作区([WorkspaceKey])管理会话(同项目对话共用同一个项目终端)。
 * 终端不属于任何任务,不经过模型,也不写运行记录;同一工作区只开一个,全局最多 [MAX_SESSIONS] 个。
 * 对话或项目删除、树锁收回、用户关闭都会结束对应会话。
 */
class TerminalHub internal constructor(
    private val conversations: ConversationStore,
    private val projects: ProjectStore? = null,
    private val resolveWorkspace: (String) -> WorkspaceKey,
    private val awaitReady: suspend () -> SandboxStatus,
    private val isolationIssue: () -> String?,
    private val openShell: suspend (WorkspaceKey, Int, Int) -> SandboxShell,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class AndroidRuntime(context: Context) {
        val app: Context = context.applicationContext
        val state = SandboxState.get(app)
        val sandbox = AndroidSandbox(app, state)
    }

    private constructor(
        runtime: AndroidRuntime,
        conversations: ConversationStore,
        projects: ProjectStore?,
    ) : this(
        conversations = conversations,
        projects = projects,
        resolveWorkspace = { id -> WorkspaceResolver.resolveFor(runtime.app, id) },
        awaitReady = { runtime.sandbox.awaitReady(PREPARE_WAIT_MS) },
        isolationIssue = { runtime.state.isolationIssue },
        openShell = { key, cols, rows -> runtime.sandbox.openShell(key, cols, rows) },
    )

    constructor(
        context: Context,
        conversations: ConversationStore,
        projects: ProjectStore? = null,
    ) : this(AndroidRuntime(context), conversations, projects)

    private val mutex = Mutex()
    private val _sessions = MutableStateFlow<Map<String, TerminalSession>>(emptyMap())
    val sessions: StateFlow<Map<String, TerminalSession>> = _sessions.asStateFlow()

    init {
        scope.launch {
            conversations.deletions.collect { id -> closeWorkspace(WorkspaceKey.Conversation(id)) }
        }
        projects?.let { store ->
            scope.launch {
                store.deletions.collect { projectId -> closeWorkspace(WorkspaceKey.Project(projectId)) }
            }
        }
    }

    fun session(conversationId: String): TerminalSession? =
        sessionForWorkspace(resolveWorkspace(conversationId))

    fun sessionForWorkspace(key: WorkspaceKey): TerminalSession? = _sessions.value[key.wire]

    /** 打开或附着这个对话所属工作区的终端;运行环境没准备好时先等它(最多两分钟)。 */
    suspend fun open(conversationId: String, title: String?, cols: Int = 80, rows: Int = 24): TerminalSession {
        val key = resolveWorkspace(conversationId)
        val displayTitle = if (key is WorkspaceKey.Project) projects?.get(key.id)?.name ?: title else title
        return openWorkspace(key, displayTitle, cols, rows)
    }

    /** 打开或附着指定工作区 [key] 的终端;运行环境没准备好时先等它(最多两分钟)。 */
    suspend fun openWorkspace(key: WorkspaceKey, title: String?, cols: Int = 80, rows: Int = 24): TerminalSession {
        val sessionKey = key.wire
        _sessions.value[sessionKey]?.let {
            if (it.phase.value == TerminalSession.Phase.Running) return it
            closeWorkspace(key)
        }
        return mutex.withLock {
            _sessions.value[sessionKey]?.let {
                if (it.phase.value == TerminalSession.Phase.Running) return@withLock it
                closeWorkspace(key)
            }
            // 退出、被收回、被关掉的会话不再占名额。
            _sessions.update { map -> map.filterValues { it.phase.value == TerminalSession.Phase.Running } }
            if (_sessions.value.size >= MAX_SESSIONS) {
                throw SandboxError("terminal_limit", "Close another terminal first.", recoverable = true)
            }
            val ready = awaitReady()
            when (ready) {
                is SandboxStatus.Ready -> Unit
                is SandboxStatus.Unsupported -> throw SandboxError("sandbox_unsupported", ready.reason, recoverable = false)
                else -> throw SandboxError("sandbox_unavailable", "The Linux environment is not ready yet.", recoverable = true)
            }
            val readyNetwork = (ready as SandboxStatus.Ready).network
            if (!readyNetwork && isolationIssue() != null) {
                throw SandboxError(
                    "sandbox_network_isolation_unavailable",
                    "The sandbox cannot cut off the network on this device, so the terminal is refused while network access is off.",
                    recoverable = true,
                )
            }
            val shell = openShell(key, cols, rows)
            val session = TerminalSession(sessionKey, title, readyNetwork, shell, scope)
            _sessions.update { it + (sessionKey to session) }
            session
        }
    }

    fun close(conversationId: String) = closeWorkspace(resolveWorkspace(conversationId))

    fun closeWorkspace(key: WorkspaceKey) {
        val sessionKey = key.wire
        var session: TerminalSession? = null
        _sessions.update { current ->
            session = current[sessionKey]
            current - sessionKey
        }
        session?.close()
    }

    fun closeAll() {
        var existing: Collection<TerminalSession> = emptyList()
        _sessions.update { current ->
            existing = current.values
            emptyMap()
        }
        existing.forEach { it.close() }
    }

    companion object {
        const val MAX_SESSIONS = 2
        private const val PREPARE_WAIT_MS = 120_000L
    }
}
