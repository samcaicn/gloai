package com.wangzan101.mika

import com.wangzan101.mika.record.DetailedTraces
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.ResourceTexts
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.ChatCopy
import android.app.Application
import android.net.Uri
import android.util.Log
import com.wangzan101.mika.chat.AndroidBranchWorkspace
import com.wangzan101.mika.chat.AutoArchive
import com.wangzan101.mika.chat.ConversationDeleter
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.ProjectDeleter
import com.wangzan101.mika.chat.ProjectStore
import com.wangzan101.mika.chat.SharedDraftStore
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.ui.AttachFiles
import com.wangzan101.mika.cap.sandbox.ShareExporter
import com.wangzan101.mika.cap.web.WebFiles
import com.wangzan101.mika.cap.sandbox.RuntimePreparer
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.SkillsSync
import com.wangzan101.mika.capsule.AppForeground
import com.wangzan101.mika.capsule.CapsuleController
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.memory.AppLearningActions
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.settings.ApprovalControl
import com.wangzan101.mika.settings.ApprovalStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.usage.UsageLedger
import com.wangzan101.mika.task.FileOutboxStore
import com.wangzan101.mika.task.FileRunStateStore
import com.wangzan101.mika.task.LocalChannel
import com.wangzan101.mika.task.RunRecovery
import com.wangzan101.mika.task.TaskRecovery
import com.wangzan101.mika.task.TriggerOutcomes
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.teach.TeachRuntime
import com.wangzan101.mika.ui.ChatViewModel
import com.wangzan101.mika.ui.TaskActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.wangzan101.mika.cap.web.cdp.WebDevtools
import com.wangzan101.mika.cap.web.WebOwner
import com.wangzan101.mika.cap.web.SiteRegistry
import com.wangzan101.mika.cap.sandbox.SandboxOwner
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.task.TaskEnvironment
import com.wangzan101.mika.task.ResourceScheduler
import com.wangzan101.mika.task.RemoteTaskHub
import com.wangzan101.mika.task.FileChildRunStore
import com.wangzan101.mika.task.TriggerRuntime

/**
 * App 容器:进程里共用的服务都在这里建一份,经各自的 Owner 接口交给安卓入口(界面、服务、广播、provider),
 * 其余代码经构造参数拿到。助手进程(HelperMain、ShellService)没有这个对象,不用这里的任何服务。
 */
class MikaApp : Application(), WebOwner, SandboxOwner, TaskEnvironment.Owner, PhoneServices.Owner,
    MemoryRuntime.Owner, ProviderStore.Owner, FileRouteStore.Owner, TaskRecovery.Owner,
    com.wangzan101.mika.settings.VoiceSettings.Owner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 手机能力的服务:内置助手、共用的 shell 连接、配对、「去设置」提示。 */
    override val phone: PhoneServices by lazy { PhoneServices(this) }

    /** 网页标签的 DevTools 会话入口:整个进程一份;连不上时经内置助手代连。 */
    override val webDevtools: WebDevtools by lazy { WebDevtools(phone.hub::openDevtoolsSocket) }

    /** 各任务的标签池共用的标签总数上限。 */
    val resources = ResourceScheduler()
    val detailedTraces by lazy { DetailedTraces(this) }

    val remoteTasks by lazy { RemoteTaskHub(this) }
    internal fun workflowInterrupted(attemptId: String) {
        scope.launch(Dispatchers.IO) { workflows.complete(attemptId, false, "", "interrupted") }
    }
    val workflows by lazy {
        com.wangzan101.mika.workflow.WorkflowRuntime(
            com.wangzan101.mika.workflow.WorkflowStore(
                java.io.File(filesDir, com.wangzan101.mika.workflow.WorkflowStore.FILE),
                com.wangzan101.mika.settings.KeystoreKeyCipher("mika-workflows"),
            ),
        ) { dispatch -> TaskService.startWorkflow(this, dispatch.attemptId) }
    }


    /** 通话功能：设置页负责开关与凭证；CallRuntime 是本地状态与实时语音的宿主，AndroidPhoneCallPort 是交给模型的工具入口。 */
    private val callRuntime = lazy { com.wangzan101.mika.calls.CallRuntime(this) }
    val calls by callRuntime
    internal val runningCalls get() = if (callRuntime.isInitialized()) calls else null
    val callPort by lazy { com.wangzan101.mika.calls.AndroidPhoneCallPort(calls) }

    /** Web 控制台读的对话与运行中任务;只读,不参与任务执行。 */
    val remoteCompanion by lazy {
        com.wangzan101.mika.chat.RemoteConversationReader(conversations) { taskState.runs.value }
    }

    /** 微信渠道：凭证、监听和渠道适配器整个进程一份；任务仍由 TaskService 执行。 */
    val wechat by lazy {
        com.wangzan101.mika.wechat.WechatRuntime(this,
            com.wangzan101.mika.wechat.WechatStore(java.io.File(filesDir, "wechat"),
                com.wangzan101.mika.settings.KeystoreKeyCipher("mika-wechat-account")))
    }

    /** 本机授权的主屏唤醒/解锁；电脑连接和无人值守定时任务共用同一条确定性流程。 */
    val deviceAccess by lazy { com.wangzan101.mika.device.AndroidDeviceAccess(this) { phone.hub.shell.value } }

    /**
     * 电脑连接(MCP):开关、访问令牌(和服务商的 Key 一样加密存)、监听。局域网那种默认关着;
     * USB 那种由电脑经 adb 打开,只到这次关机为止(开机编号和 shell 里 `settings get global boot_count` 读到的一样)。
     */
    val mcp by lazy {
        val settingsDir = java.io.File(filesDir, "settings")
        val settings = com.wangzan101.mika.settings.McpSettings(settingsDir, com.wangzan101.mika.settings.KeystoreKeyCipher("mika-mcp-token"))
        val history = com.wangzan101.mika.task.PhoneHistory(conversations, com.wangzan101.mika.record.RunStore(this), detailedTraces.store,
            allowed = { settings.historyEnabled.value }, live = { remoteTasks.list(100) })
        com.wangzan101.mika.mcp.McpRuntime(this, settings,
            com.wangzan101.mika.settings.McpUsbSettings(settingsDir,
                bootId = { android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.BOOT_COUNT) ?: "null" },
                infoFile = { getExternalFilesDir(null)?.let { java.io.File(it, com.wangzan101.mika.core.mcp.McpUsb.INFO_FILE) } },
                migrateTaskOwner = remoteTasks::migrateUsbOwner),
            remoteTasks, deviceAccess,
            com.wangzan101.mika.settings.RelaySettings(settingsDir, com.wangzan101.mika.settings.KeystoreKeyCipher("mika-remote-relay")),
            history, callPort, remoteCompanion)
    }
    val childRuns by lazy { FileChildRunStore(java.io.File(filesDir, "children-v3.json")) }

    /** 外部 MCP server 的设置(与「连接电脑」的 inbound 设置分开)。 */
    val mcpServers by lazy {
        com.wangzan101.mika.settings.McpServerStore(
            java.io.File(filesDir, "settings"),
            com.wangzan101.mika.settings.KeystoreKeyCipher("mika-mcp-servers"),
            stdio = com.wangzan101.mika.core.cap.mcp.McpStdioLauncher { workspace, argv ->
                val process = AndroidSandbox(this, SandboxState.get(this)).openProcess(workspace, argv, null)
                object : com.wangzan101.mika.core.cap.mcp.McpStdioProcess {
                    override val stdin get() = process.stdin
                    override val stdout get() = process.stdout
                    override val stderr get() = process.stderr
                    override fun close() = process.close()
                }
            },
        )
    }
    /** 已安装的插件:zip 安装、启停与只读技能;任务开始时给技能目录一份固定快照。 */
    val plugins by lazy {
        com.wangzan101.mika.plugin.PluginRegistry(filesDir, scope,
            log = { msg, e -> Log.w("MikaPlugin", msg, e) })
    }

    /** 人格:命名的自定义提示词层,存在 settings 目录;不加密,不含密钥。 */
    val personas by lazy { com.wangzan101.mika.settings.PersonaStore(java.io.File(filesDir, "settings")) }
    override val tabBudget get() = resources.tabBudget

    /** 任务结束后留在后台接着运行的那一页;关掉时在来源对话那一轮记下原因(对话页那一轮下面显示「后台网页已关闭」)。 */
    override val pageKeeper: com.wangzan101.mika.cap.web.PageKeeper by lazy {
        com.wangzan101.mika.cap.web.PageKeeper(this, tabBudget).also { k ->
            k.onClosed = { p, reason ->
                p.runId?.let { runId ->
                    scope.launch(Dispatchers.IO) {
                        runCatching { conversations.updateTurn(p.conversationId, runId) { it.copy(keptPageClosed = reason.wire) } }
                            .onFailure { android.util.Log.w("MikaApp", "could not note the kept page closing", it) }
                    }
                }
            }
        }
    }

    /** 记忆、个人技能、开关与结果卡片提示。 */
    override val memory: MemoryRuntime by lazy { MemoryRuntime(this) }

    /** 本机的 token 用量账本:经模型配置建出的每个模型的每次调用都记在这里。 */
    val usage: UsageLedger by lazy { UsageLedger(java.io.File(filesDir, "usage-v1.json")) }

    /** 模型配置与 Key;建出的模型都套上计量,记到 [usage]。 */
    override val providers: ProviderStore by lazy {
        ProviderStore.create(this, usage, "Mika/${BuildConfig.VERSION_NAME}", usage::providerTokensToday,
            proactiveUsage = usage::proactiveTokensToday, proactiveLimit = { FeatureFlags.get(this).proactiveDailyTokenBudget() },
            proactiveCalls = usage::proactiveCallsToday)
    }

    /** 语音转文字的选择:整个进程一份,设置页改了输入框立刻看得到(两份实例互相看不到对方的 set)。 */
    val speech: com.wangzan101.mika.voice.SpeechSettings by lazy { com.wangzan101.mika.voice.SpeechSettings.get(this) }

    /** 语音输入的入口,输入框和悬浮胶囊共用。 */
    val voice: com.wangzan101.mika.voice.VoiceInput by lazy { com.wangzan101.mika.voice.VoiceInput(this, speech, providers) }

    /** 用 ChatGPT 登录:跑在 App 的作用域里,期间开前台服务撑住进程(见 [com.wangzan101.mika.settings.ChatGptSignIns])。 */
    val chatGptSignIn by lazy {
        com.wangzan101.mika.settings.ChatGptSignIns(scope,
            keepAlive = { com.wangzan101.mika.settings.ChatGptSignInService.start(this) },
            log = { msg, e -> Log.w("MikaChatGpt", msg, e) })
    }

    /** 经验路线。 */
    override val routes: FileRouteStore by lazy { FileRouteStore.create(this) }

    /** 异常退出后留下的系统改动的恢复;在跑的任务登记在这里。 */
    override val taskRecovery: TaskRecovery by lazy { TaskRecovery(this, phone.sharedShell) }

    /** 运行账本与送达账本:整个进程一份,任务服务和启动时的恢复共用(同一个文件不能有两份实例)。 */
    val runStore: RunStore by lazy { FileRunStateStore(java.io.File(filesDir, "runs-v3.json")) }
    val outbox: Outbox by lazy { Outbox(FileOutboxStore(java.io.File(filesDir, "outbox-v3.json"))) }

    /** 同对话下一件任务的持久队列:用户明确排队的和引导 fallback 都在这里,任务服务与界面共用。 */
    val followUpQueue: com.wangzan101.mika.task.FollowUpQueue by lazy {
        com.wangzan101.mika.task.FollowUpQueue(
            com.wangzan101.mika.task.FileFollowUpQueueStore(java.io.File(filesDir, "followups-v1.json")),
        )
    }

    /** 没结束的运行的善后(标中断、补送、告知):进程里只做一次,App 启动和任务服务启动都调它。 */
    val runRecovery: RunRecovery by lazy {
        val app = this
        fun localChannel(scheduled: Boolean) = LocalChannel { _, delivery ->
            TaskService.ensureChannels(app)
            TaskService.notifyDelivery(app, delivery, TaskService.nextResultId(), scheduled)
        }
        val local = localChannel(scheduled = false)
        val labels = com.wangzan101.mika.ui.ToolLabels(com.wangzan101.mika.core.assembly.knownTools())
        RunRecovery(runStore, outbox, { id -> if (id == LocalChannel.ID) local else wechat.channelFor(id) }, object : TriggerOutcomes {
            override fun interrupted(key: String, reason: String) = TriggerRuntime.interrupted(app, key, reason)
            override fun delivered(key: String) = TriggerRuntime.delivered(app, key)
            override fun actionFailed(key: String, reason: String) = TriggerRuntime.actionFailed(app, key, reason)
        }, conversations, scope, records = com.wangzan101.mika.record.RunStore(app).reader(), label = labels::label,
            appLabel = { com.wangzan101.mika.confirm.ConfirmationNotifier.appLabel(app, it) },
            languageTag = { com.wangzan101.mika.i18n.AppLanguage.resolved(app).tag },
            scheduledLocalChannel = localChannel(scheduled = true))
    }

    /** 站点登记表(登录过的网站等):设置页和标签池共用。 */
    override val webSites: SiteRegistry by lazy { SiteRegistry(java.io.File(filesDir, "web")) }

    /** `files/sandbox/` 的树锁:安装、重置、命令、下载、附件都在它的许可里写。 */
    override val sandboxTree = SandboxTree()

    /** 任务从 App 拿的模型来源、评测钩子、屏幕画面的开关。 */
    override val tasks = TaskEnvironment()

    /** 沙箱状态:安装服务更新的,设置页与 Agent 看到的是同一份。 */
    override val sandboxState: SandboxState by lazy { SandboxState(this) }

    /** 对话记录:整个进程一份,任务服务、聊天界面、结果卡片的按钮共用。 */
    val conversations: ConversationStore by lazy { ConversationStore(this) }

    /** 项目定义:整个进程一份。 */
    val projects: ProjectStore by lazy { ProjectStore(this) }

    /** 对话编号到工作区的解析器(不依赖 [chat] 避免循环 lazy)。 */
    val workspaceResolver: WorkspaceResolver by lazy { WorkspaceResolver(conversations, projects) }

    /** 最近一次系统分享尚未发送的草稿和它的私有图片副本。 */
    val sharedDrafts: SharedDraftStore by lazy { SharedDraftStore(java.io.File(filesDir, "shared-drafts")) }

    /** 用户自己打开的终端(按工作区):只从界面进,不交给任何任务,也不写运行记录。 */
    val terminals: com.wangzan101.mika.terminal.TerminalHub by lazy {
        com.wangzan101.mika.terminal.TerminalHub(this, conversations, projects)
    }

    /** 删项目:停掉旗下对话的当前任务、删除项目内对话与项目定义,并清掉共享工作区 `p-<id>`。 */
    val projectDeleter: ProjectDeleter by lazy {
        val app = this
        ProjectDeleter(
            projects, conversations, taskState.activeConversations,
            cancelTasks = { id -> taskState.runs.value.filter { it.conversationId == id }.forEach { TaskService.cancel(app, it.key) } },
            schedulesOf = { id -> TriggerRuntime.list(app).filter { it.threadId == id }.map { it.id } },
            deleteProjectWorkspace = { pid -> SandboxDirs(app).deleteWorkspace(WorkspaceKey.Project(pid)) },
            forgetRemoteTasks = { id ->
                if (com.wangzan101.mika.core.remote.RemoteTaskService.isConversation(id)) remoteTasks.forget(id)
            },
            onDeleted = { pid, ids ->
                val imports = com.wangzan101.mika.task.DeviceImportStore.get(app)
                imports.removeWorkspace(WorkspaceKey.Project(pid).wire)
                ids.forEach(imports::removeConversation)
            },
        )
    }

    /** 删对话(侧栏长按):停掉它的当前任务并删工作区(同项目对话保留共享工作区),保留定时任务。 */
    val conversationDeleter: ConversationDeleter by lazy {
        val app = this
        ConversationDeleter(
            conversations, taskState.activeConversations,
            forgetRemoteTasks = { id ->
                if (com.wangzan101.mika.core.remote.RemoteTaskService.isConversation(id)) remoteTasks.forget(id)
            },
            cancelTasks = { id -> taskState.runs.value.filter { it.conversationId == id }.forEach { TaskService.cancel(app, it.key) } },
            schedulesOf = { id -> TriggerRuntime.list(app).filter { it.threadId == id }.map { it.id } },
            deleteWorkspace = { id ->
                // 工作区里有附件、用户发来的屏幕、任务写的文件;沙箱里的命令碰得到这里,和命令、导入、重置互斥着删
                SandboxDirs(app).deleteWorkspace(WorkspaceKey.Conversation(id))
            },
            isSharedWorkspace = { id -> workspaceResolver.isShared(id) },
            onDeleted = { id -> com.wangzan101.mika.task.DeviceImportStore.get(app).removeConversation(id) },
        )
    }

    /** 用户发来的屏幕(跟着各自的对话存在工作区里)。 */
    val userScreens: com.wangzan101.mika.chat.UserScreenFiles by lazy { com.wangzan101.mika.chat.UserScreenFiles(this) }

    /** 全局的询问 / 自主:整个进程一份,设置页和各任务共用。 */
    val approvals: ApprovalStore by lazy { ApprovalStore(java.io.File(filesDir, "approvals.json")) }

    val appAccess: com.wangzan101.mika.settings.AppAccessStore by lazy {
        com.wangzan101.mika.settings.AppAccessStore(java.io.File(filesDir, "app-access.json"))
    }

    /** 主动能力:通知白名单、监听与本机收件箱。 */
    val proactive by lazy {
        com.wangzan101.mika.proactive.ProactiveRuntime.create(this,
            { com.wangzan101.mika.proactive.AppGate.of(appAccess.current()) },
            appAccess.state.map { com.wangzan101.mika.proactive.AppGate.of(it) }.distinctUntilChanged(),
            com.wangzan101.mika.proactive.TriageHooks(
            target = { providers.proactiveTarget() }, model = { call -> providers.proactiveTargeted(call) },
            tokensToday = { usage.proactiveTokensToday() }, callsToday = { usage.proactiveCallsToday() },
            usageVersion = { usage.version }, busy = taskState.busy,
        )) { phone.shellManager.commandRunner }
    }

    /** 首页推荐任务。文件还不存在时,由界面用当前语言的内置示例初始化一次。 */
    val recommendedTasks: com.wangzan101.mika.settings.RecommendedTaskStore by lazy {
        com.wangzan101.mika.settings.RecommendedTaskStore(java.io.File(filesDir, com.wangzan101.mika.settings.RecommendedTaskStore.FILE))
    }

    val savedChecklists by lazy {
        com.wangzan101.mika.chat.SavedChecklistStore(
            java.io.File(filesDir, com.wangzan101.mika.chat.SavedChecklistStore.FILE),
            com.wangzan101.mika.settings.KeystoreKeyCipher("mika-saved-checklists"),
        )
    }

    /** 全局的询问 / 自主和各任务自己的那一份;开发者选项里的「自主模式」经它改。 */
    val approvalControl: ApprovalControl by lazy { ApprovalControl(approvals) }

    /** 各 Run 的队列与展示状态跟 App 进程走，Service 重建时继续使用同一份。 */
    val taskState = TaskCenterState()

    /** 长期个人主会话与其独立任务，由进程持有。 */
    val personal by lazy { com.wangzan101.mika.personal.PersonalRuntime(this) }

    /** Mika 自己的界面此刻看不看得见。 */
    val foreground = AppForeground()

    /** 自身操作的进程内 UI 请求(开 Mika 的页/文件);任务服务发,MainActivity 处理。 */
    val selfUi = com.wangzan101.mika.self.SelfUi()

    /** 更新镜像:用户可增删改排序;检查更新和下载都按它走,官方 GitHub 始终兜底。 */
    val updateMirrors: com.wangzan101.mika.about.UpdateMirrorStore by lazy {
        com.wangzan101.mika.about.UpdateMirrorStore(java.io.File(filesDir, com.wangzan101.mika.about.UpdateMirrorStore.FILE))
    }

    /** 检查更新:查到的新版本跟进程走,侧栏、设置首页、关于页的红点看同一份。 */
    val updates: com.wangzan101.mika.about.UpdateChecker by lazy {
        com.wangzan101.mika.about.UpdateChecker(java.io.File(filesDir, "update-v1.json"), BuildConfig.VERSION_NAME,
            mirrorSettings = updateMirrors::snapshot,
            deviceAbis = android.os.Build.SUPPORTED_ABIS.toList())
    }

    internal val apkUpdater by lazy {
        com.wangzan101.mika.about.ApkUpdater(this, mirrorSettings = updateMirrors::snapshot)
    }
    internal val automaticUpdates by lazy { com.wangzan101.mika.about.AutomaticUpdates(this, apkUpdater) }

    /** 浮在别的 App 上的悬浮胶囊。 */
    val capsule: CapsuleController by lazy { CapsuleController(this, taskState, foreground, resources.takeoverQueue) }

    /** Mika 自己的朗读播放器:全进程一个,聊天里的「朗读」和设置页的测试共用。 */
    val voicePlayer by lazy { com.wangzan101.mika.voice.VoicePlayer(this) }
    override val voiceSettings by lazy {
        com.wangzan101.mika.settings.VoiceSettings(getSharedPreferences(FeatureFlags.PREFS, MODE_PRIVATE))
    }

    /** 播放工作区里的一段音频(生成结果);读不到时什么都不做。 */
    private suspend fun playWorkspaceAudio(conversationId: String, path: String) {
        val prefix = com.wangzan101.mika.core.workspace.SandboxPaths.WORKSPACE + "/"
        if (!path.startsWith(prefix)) return
        val rel = path.removePrefix(prefix)
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return
        val dirs = com.wangzan101.mika.cap.sandbox.SandboxDirs(this)
        val key = workspaceResolver.resolve(conversationId)
        val bytes = dirs.tree.shared {
            dirs.gate.openRead(dirs.workspace(key), rel).use { g ->
                if (g.size > 20L * 1024 * 1024) null else g.read(0, g.size.toInt())
            }
        } ?: return
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(path.substringAfterLast('.', "").lowercase())
            ?.takeIf { it.startsWith("audio/") } ?: "audio/wav"
        voicePlayer.play(bytes, mime)
    }

    /** 合成一句话并在本机播放;关闭或未配置时跳过,失败交给聊天页显示。 */
    internal suspend fun speak(
        text: String,
        synthesize: suspend (String) -> Unit = { words ->
            com.wangzan101.mika.media.readAloud(
                words,
                voiceSettings,
                model = { choice, caps -> ProviderStore.get(this).ttsModel(choice, caps) },
                play = { voicePlayer.play(it.bytes, it.mime) },
            )
        },
    ) = synthesize(text)

    /** 人工示范。onCreate 里建好；在那之前的无障碍事件不采集。 */
    lateinit var teach: TeachRuntime
        private set
    val teachReady: Boolean get() = ::teach.isInitialized

    /** 对话页的状态跟进程走:界面关了再打开,正在跑的任务的步骤还在。 */
    val chat: ChatViewModel by lazy {
        val app = this
        ChatViewModel(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            taskState.state,
            taskState.takeoverStage,
            object : TaskActions {
                override fun start(goal: String, conversationId: String) = TaskService.start(app, goal, conversationId)
                override fun steer(conversationId: String, messageId: String, text: String) = TaskService.start(app, text, conversationId, messageId)
                override fun enqueueFollowUp(conversationId: String, messageId: String, text: String, startIfIdle: Boolean) =
                    TaskService.enqueueFollowUp(app, conversationId, messageId, text, startIfIdle = startIfIdle)
                override fun cancelFollowUp(conversationId: String, messageId: String): Boolean =
                    app.followUpQueue.cancel(messageId) != null
                override fun drainFollowUps(conversationId: String) = TaskService.drainFollowUps(app, conversationId)
                override fun withdrawSteer(conversationId: String, messageId: String) = taskState.run(conversationId)?.withdrawSteer(messageId) ?: false
                override fun cancel() = TaskService.cancel(app)
                override fun resume() = TaskService.resume(app)
                override fun takeOver() = TaskService.takeOver(app)
                override fun handBack() = TaskService.handBack(app)
                override fun finishedMyself() = TaskService.finishedMyself(app)
                override fun skip() = TaskService.skip(app)
                override fun answer(text: String) = TaskService.answer(app, text)
                override fun approve(key: Int) = TaskService.approve(app, key)
                override fun decline(key: Int) = TaskService.decline(app, key)
                override fun show(conversationId: String) = taskState.show(conversationId)

                // 按下那一刻就换成该对话任务的编号:之后切了对话、或那个任务换了,动作也不会落到别的任务上
                private fun key(conversationId: String) = taskState.run(conversationId)?.key
                override fun cancelIn(conversationId: String) { key(conversationId)?.let { TaskService.cancel(app, it) } }
                override fun resumeIn(conversationId: String) { key(conversationId)?.let { TaskService.resume(app, it) } }
                override fun takeOverIn(conversationId: String) { key(conversationId)?.let { TaskService.takeOver(app, runKey = it) } }
                override fun handBackIn(conversationId: String) { key(conversationId)?.let { TaskService.handBack(app, runKey = it) } }
                override fun finishedMyselfIn(conversationId: String) { key(conversationId)?.let { TaskService.finishedMyself(app, runKey = it) } }
                override fun skipIn(conversationId: String) { key(conversationId)?.let { TaskService.skip(app, runKey = it) } }
                override fun answerIn(conversationId: String, text: String) { key(conversationId)?.let { TaskService.answer(app, text, it) } }
                override fun approveIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.approve(app, key, it) } }
                override fun declineIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.decline(app, key, it) } }
                override fun togglePipSoundIn(conversationId: String) { taskState.run(conversationId)?.togglePipSound() }
            },
            conversations,
            followUps = followUpQueue.changes,
            serviceBusy = taskState.busy,
            appLabel = { ConfirmationNotifier.appLabel(app, it) },
            // 虚拟屏上打开过 App,或在用内置浏览器:画中画才有画面可看(只建了空屏的不算)
            screenOpen = tasks.previewOpen
                .stateIn(scope, SharingStarted.Eagerly, tasks.agentAppShown.value || tasks.webScreenOpen.value),
            endedRunId = taskState.endedRunId,
            // 按对话取它自己的任务,不用服务对外那一份(并行时那是显示中的别的任务)
            liveRunId = { taskState.run(it)?.runId?.value },
            endedRunIds = taskState.endedRunIds,
            parallelFlag = { FeatureFlags.get(app).speedFlags().parallelTasks },
            shownTask = taskState.shownTask,
            activeConversations = taskState.activeConversations,
            runGoal = { taskState.run(it)?.goal },
            runState = { taskState.run(it)?.state },
            runNotes = memory.notes,
            learning = AppLearningActions(app, memory),
            memoryItems = memory.items(),
            // 附件进对话的工作区,不需要运行环境已准备好:设备支持就给附件按钮
            sandboxReady = SandboxState.get(app).status.map { it !is SandboxStatus.Unsupported }
                .stateIn(scope, SharingStarted.Eagerly, SandboxState.get(app).status.value !is SandboxStatus.Unsupported),
            sharedFiles = taskState.sharedFiles,
            generatedAudios = taskState.audios,
            playAudio = { id, path -> playWorkspaceAudio(id, path) },
            generatedPhotos = taskState.photos,
            activities = taskState.activities,
            steers = taskState.steers,
            summarizing = taskState.summarizing,
            importAttachments = { id, uris ->
                val sandbox = AndroidSandbox(app, SandboxState.get(app))
                val dirs = SandboxDirs(app)
                val key = workspaceResolver.resolve(id)
                Attachments.importInto(app, uris.map(Uri::parse), dirs.workspace(key), dirs.gate, dirs.tree, sandbox::freeBytes) { sandbox.workspaceBytes(key) }
            },
            describeAttachments = { uris -> AttachFiles.describe(app, uris) },
            deletionImpact = conversationDeleter::impact,
            removeConversation = { id -> followUpQueue.dropConversation(id); conversationDeleter.delete(id) },
            keptPage = pageKeeper.page,
            closeKept = { pageKeeper.close(com.wangzan101.mika.core.cap.web.KeptPageClose.User) },
            branchWorkspace = AndroidBranchWorkspace(app),
            // 标题后面加当前界面语言的「分支」后缀;源标题太长时先截短,后缀留着
            branchTitle = { base -> ChatCopy.branchTitle(base, tr(R.string.chat_branch_title_suffix)) },
            sharedDrafts = sharedDrafts,
            sharedDraftUri = { name ->
                sharedDrafts.imageFile(name)?.let { file ->
                    androidx.core.content.FileProvider.getUriForFile(app, com.wangzan101.mika.chat.AttachFilesProvider.authority(app.packageName), file).toString()
                }.orEmpty()
            },
            teaching = { teach.blocksTasks() },
            onTeachingBlocked = {
                android.widget.Toast.makeText(app, tr(R.string.teach_blocked_task), android.widget.Toast.LENGTH_LONG).show()
            },
            voiceReady = voiceSettings.state.map { it.enabled && it.choice != null }
                .stateIn(scope, SharingStarted.Eagerly, voiceSettings.state.value.enabled && voiceSettings.state.value.choice != null),
            autoRead = voiceSettings.state.map { it.enabled && it.autoRead }
                .stateIn(scope, SharingStarted.Eagerly, voiceSettings.state.value.let { it.enabled && it.autoRead }),
            voicePlayback = voicePlayer.state,
            speakText = { text -> speak(text) },
            stopVoice = { voicePlayer.stop() },
            voiceFailed = voicePlayer::fail,
            projectStore = projects,
            projectDeletionImpact = projectDeleter::impact,
            removeProject = { pid ->
                projectDeleter.memberConversationIds(pid).forEach(followUpQueue::dropConversation)
                projectDeleter.delete(pid)
            },
            workspaceResolver = workspaceResolver,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // 上次进程在复制或绑定分享草稿途中退出时留下的临时文件;目录至多保留一份有效草稿。
        runCatching { sharedDrafts.cleanup() }
        scope.launch(Dispatchers.IO) {
            runCatching { updateMirrors.ensureLoaded() }
            runCatching { com.wangzan101.mika.task.AndroidDeviceFiles.cleanupStaging(SandboxDirs(this@MikaApp)) }
                .onFailure { Log.w("MikaImport", "Could not clean device import staging", it) }
        }
        // 界面以外的文字(通知、服务、悬浮胶囊)按 App 语言取
        AppLanguage.applyDefaultLocale(this)
        UiText.source = ResourceTexts { AppLanguage.resources(this) }
        teach = TeachRuntime(this, taskState)
        teach.recover()
        registerActivityLifecycleCallbacks(foreground)
        val flags = FeatureFlags.get(this)
        // 用户点了「完全退出」:进程可能被无障碍服务重新拉起。胶囊照样接线(开关按退出算关着,见 CapsuleController),
        // 但运行环境准备和没结束的运行的善后先不做,免得刚退出就冒出一条通知;用户下次打开 Mika 时补上(见 [resumeAfterQuit])。
        val quit = flags.quitRequested()
        // 设置 › 网络代理:Mika 自己的连接从第一个请求起就按它走;SOCKS 代理的账号密码经全进程的认证器给
        com.wangzan101.mika.settings.NetProxyStore.get(this)
        com.wangzan101.mika.core.net.NetProxy.shared.installSocksAuth()
        // 内置浏览器:设了代理才给 WebView 设上(读设置要解密,放到后台;没设代理不为此加载 WebView)
        scope.launch(Dispatchers.IO) { runCatching { com.wangzan101.mika.cap.web.WebProxy.apply(this@MikaApp) } }
        capsule.start()
        // 内置助手的真实现,并开始看着助手(Mika 进程起来后,还活着的助手会自己交接 Binder)
        phone.shellManager.start()
        // 连接一可用就补上默认打开的几项能力
        phone.recovery.start()
        // 告诉内置助手:Mika 被杀后要不要把它拉起来(胶囊一直显示或有任务在跑时才要;退出期间都不要)
        com.wangzan101.mika.cap.phone.setup.RestoreWishSync(
            phone.hub, flags.capsuleModeChanges(), taskState.busy, phone.recoverySettings.consent,
            phone.recovery.accessibility, flags.quitChanges(),
        ).start()
        // 上次异常退出时留下的改动(App 被静音)在这里恢复
        scope.launch { taskRecovery.recover() }
        // 上次进程被杀时没结束的运行:现在就标为中断并告知,不等任务服务起来
        if (!quit) runRecovery.recover().let { if (it.isNotEmpty()) Log.w("MikaTask", "${it.size} runs interrupted by process restart") }
        // 用户已绑定并打开微信渠道时恢复监听；完全退出期间由各运行入口统一拒绝补起
        wechat.ensureRunning()
        if (flags.proactive()) proactive.start(paused = quit)
        // 先对一下设备时区;退出期间不排闹钟,重新打开时再补排
        if (!quit) scope.launch(Dispatchers.IO) { runCatching { TriggerRuntime.syncTimeZone(this@MikaApp); TriggerRuntime.rearm(this@MikaApp) } }
        // 自动归档:太久没新消息的对话收进归档(设置里开着才跑);退出期间等下次打开再补
        if (!quit) autoArchiveNow()
        // 给电脑跑的「经 USB 连接」脚本;顺带清掉上次开机留下的 USB 连接信息
        scope.launch(Dispatchers.IO) { runCatching { mcp.writeUsbScript() }.onFailure { Log.w("MikaMcp", "cannot write USB script", it) } }
        // 上次导出写到一半被杀留下的空壳条目
        scope.launch { runCatching { ShareExporter(this@MikaApp).cleanPending() } }
        // 上次存音乐库写到一半被杀留下的空壳条目
        scope.launch { runCatching { com.wangzan101.mika.cap.audio.AudioExporter(this@MikaApp).cleanPending() } }
        scope.launch { runCatching { com.wangzan101.mika.media.AudioShareExporter(this@MikaApp).cleanPending() } }
        // 上次存相册写到一半被杀留下的空壳条目
        scope.launch { runCatching { com.wangzan101.mika.cap.image.ImageExporter(this@MikaApp).cleanPending() } }
        // 上次分享图片复制到一半被杀留下的空壳条目
        scope.launch { runCatching { com.wangzan101.mika.media.ShareFileExporter(this@MikaApp).cleanPending() } }
        // 上次进程留下的网页上传副本
        scope.launch { runCatching { WebFiles.clearStale(this@MikaApp, SandboxDirs(this@MikaApp).gate) } }
        // 更新已经装上后,关于页下好的安装包
        scope.launch(Dispatchers.IO) { runCatching { apkUpdater.dropInstalled() } }
        // 运行环境没准备好(首次启动、Mika 升级后)就在后台准备,不联网、对用户不可见
        if (!quit) RuntimePreparer.ensure(this)
        // 沙箱就绪(启动时已就绪,或刚装好)就把技能目录核对一次:App 更新带来的脚本马上能用
        scope.launch {
            SandboxState.get(this@MikaApp).status.first { it is SandboxStatus.Ready }
            val state = SandboxState.get(this@MikaApp)
            runCatching { SkillsSync.sync(SandboxDirs(this@MikaApp)) { state.status.value is SandboxStatus.Ready } }
        }
    }

    /**
     * 按设置把太久没有新消息的对话收进归档:启动时跑一次,设置里改了天数也跑一次。
     * 正在跑任务的对话和用户此刻在看的对话不动;之后再有新的一轮写进来会自动取消归档。
     */
    fun autoArchiveNow() {
        val days = FeatureFlags.get(this).autoArchiveDays()
        if (days <= 0) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                AutoArchive.run(
                    conversations, days,
                    running = taskState.activeConversations.value,
                    currentId = (conversations.currentChat() as? CurrentChat.Open)?.id,
                    isRunning = { it in taskState.activeConversations.value },
                )
            }.onFailure { Log.w("MikaChat", "could not auto archive conversations", it) }
        }
    }

    /** 用户重新打开 Mika:补上退出时略过的启动工作(见 [com.wangzan101.mika.quit.MikaQuit])。 */
    fun resumeAfterQuit() {
        runRecovery.recover()
        RuntimePreparer.ensure(this)
        autoArchiveNow()
        mcp.ensureRunning()
        wechat.ensureRunning()
        if (FeatureFlags.get(this).proactive()) proactive.start(paused = false)
        scope.launch(Dispatchers.IO) {
            runCatching {
                TriggerRuntime.syncTimeZone(this@MikaApp)
                TriggerRuntime.rearm(this@MikaApp)
            }.onFailure { Log.w("MikaTrigger", "could not restore schedules after quit", it) }
        }
    }
}
