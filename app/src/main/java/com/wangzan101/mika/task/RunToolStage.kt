package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.screen.uistate.IdleScroll
import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.screen.uistate.PlacePace
import kotlin.random.Random
import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.settings.ui.StatusCopy
import com.wangzan101.mika.cap.sandbox.GrantedFolderFiles
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.phone.setup.RootShell
import com.wangzan101.mika.cap.root.RootCommands
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.cap.sandbox.FolderFileImporter
import com.wangzan101.mika.core.cap.wechat.explicitWechatOwnerRequest
import com.wangzan101.mika.wechat.AndroidWechatOwnerPort
import com.wangzan101.mika.core.run.ChildAgentRunner
import com.wangzan101.mika.core.screen.vision.VisionService
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.media.BitmapImageOps
import com.wangzan101.mika.cap.web.LazyWebPort
import com.wangzan101.mika.cap.web.WebStack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.WEB

// 运行装配:工具分发器
internal suspend fun TaskRun.assembleTools(a: RunAssembly, m: RunModels, ph: RunPhone, mem: RunMemory, rec: RunRecord, pr: RunPrompt, web: LazyWebPort?): RunTools {
    var smsLogin by a::smsLogin
    var webStack by a::webStack
    var dispatcher by a::dispatcher
    var approval by a::approval
    val resources = a.resources
    val approvalControl = a.approvalControl
    val side = a.side
    val model = m.model
    val vision = m.vision
    val visionFallbacks = m.visionFallbacks
    val appAccess = ph.appAccess
    val metrics = ph.metrics
    val pace = ph.pace
    val accessedPhone = ph.accessedPhone
    val privatePhone = ph.privatePhone
    val speed = mem.speed
    val memory = mem.memory
    val taskMemory = mem.taskMemory
    val utterances = mem.utterances
    val catalog = mem.catalog
    val sink = rec.sink
    val userScreens = pr.userScreens
    val basePrompt = pr.basePrompt
    val personaLabel = pr.personaLabel
    val runSpec = pr.runSpec
    // 外部 MCP server 只在主任务接;子任务/条件检查/评测不接,避免绕过父任务审批、影响评测可复现
    if (!conditionCheck) {
        // 连上的当场归 resources:收尾(含取消、失败、连到一半被停)关作用域时一起关,stdio server 的进程和沙箱租约跟着放掉
        mcpConnections = (service.application as MikaApp).mcpServers.connectionsFor(
            resources,
            com.wangzan101.mika.mcp.ConversationMcpResultStore(SandboxDirs(service), workspaceKey),
            workspaceKey.wire,
        )
        // 连接失败、超过总时限没连上的只在任务卡片上提示一句,不打断任务;详情在设置页的健康状态里
        mcpConnections?.failures?.takeIf { it.isNotEmpty() }?.let { failures ->
            val store = (service.application as MikaApp).mcpServers
            val names = store.servers.value.associate { it.id to it.name }
            (runState._state.value as? TaskState.Running)?.let {
                publish(it.copy(note = tr(R.string.mcp_servers_run_failed, failures.joinToString(", ") { f -> names[f.first] ?: f.first })))
            }
        }
    }
    // 对话里有通知提醒(第三方文字)时和电脑交来的任务一样:不管「自主模式」开没开,付款照样先问
    val runApproval = approvalControl.start(alwaysAsk = provenance.origin == RunOrigin.Mcp || noticeTainted).also { approval = it }
    // 插件:环境变量、工具规格、提示词钩子共用同一个脚本入口
    val pluginRunner = com.wangzan101.mika.plugin.PluginScriptRunner(
        envProvider = { com.wangzan101.mika.settings.EnvVarStore.get(service).all() })
    val pluginTools = (service.application as MikaApp).plugins.toolSpecs()
    val promptHook = com.wangzan101.mika.plugin.PluginPromptHook(
        (service.application as MikaApp).plugins, pluginRunner, { model.name },
        { com.wangzan101.mika.settings.EnvVarStore.get(service).all() })
    // 一个任务里沙箱命令累计时长上限(设置 › 干活时,默认 30 分钟);主任务和它派的子任务同档,各自独立累计
    val sandboxBudgetMs = FeatureFlags.get(service).sandboxBudgetMinutes() * 60_000L
    val childRunner = if (provenance.origin == RunOrigin.Ui && web != null) ChildAgentRunner(
        create = { childSpec, childResources ->
            val childWeb = if (childSpec.parallelChild) {
                WebStack.childPort(service, childSpec.id.value, metrics.widthPixels, metrics.heightPixels,
                    metrics.densityDpi, childResources)
            } else web
            val childTools = toolDispatcher(privatePhone, model.capabilities.vision,
                web = childWeb, webPacing = { PagePacing.human() }, webPlacePace = { PlacePace.of(catalog.sitePace(it)) },
                webIdleScroll = { IdleScroll(Random.Default) },
                appPacing = { pace.appPacing() }, appTempo = { pace.of(it).tempo }, appCapped = { pace.capped(it) }, appIdleScroll = { IdleScroll(Random.Default) },
                sandbox = sandbox, workspace = { workspaceKey.wire }, runSpec = childSpec,
                conversationId = conversationId, inlineScreen = speed.inlineScreen,
                sandboxBudgetMs = sandboxBudgetMs,
                adaptiveVision = speed.adaptiveVision)
            childTools.manualControl = manualControl
            AgentLoop(model, childTools, basePrompt, maxSteps = childSpec.stepBudget ?: AgentLoop.UNLIMITED_STEPS,
                // 子任务跟着询问 / 自主;询问时它也过关卡,子任务里花钱的一步一律不做
                confirmations = runApproval.gate(), approvals = runApproval, autonomous = runApproval.startedAutonomous,
                promptTransform = { promptHook.transform(it) },
                reviewFinalAnswer = false, flags = speed, outwardPacing = OutwardPacing.human(), personaLabel = personaLabel)
        },
        store = (service.application as MikaApp).childRuns,
        ownerAlive = { job?.isActive == true },
    ).also { children = it } else null
    // 自身端口:只给手机上、有用户的 UI 主任务;子任务、条件检查、定时、渠道、MCP 都没有。
    // 状态只读工具一直给;操作类工具等用户打开「允许 Mika 操作自己的界面」。
    val selfPort = if (provenance.origin == RunOrigin.Ui && !conditionCheck) {
        (service.application as? MikaApp)?.let { app -> com.wangzan101.mika.self.AndroidSelfPort(app, conversationId, key) }
    } else null
    val deviceImports = if (provenance.origin == RunOrigin.Ui &&
        provenance.attendance != Attendance.Unattended && !runSpec.readOnly) {
        DeviceWorkspaceImports(SandboxDirs(service), sandbox::freeBytes, DeviceImportStore.get(service),
            resolveWorkspace = { workspaceKey }, conversationIdFor = { conversationId },
            limits = { FeatureFlags.get(service).attachmentLimits() }) // 每次导入现取档位,与聊天附件同一套
    } else null
    val folders = sandbox.folderFiles as? GrantedFolderFiles
    val tools = toolDispatcher(privatePhone, model.capabilities.vision, catalog, recordFrames = true,
        vision = vision?.let {
            VisionService(it, BitmapImageOps, fallbacks = visionFallbacks, onModelSwitch = { from, to, _ ->
                sink.event(RunEvent.State(System.currentTimeMillis(), "vision_fallback", "${from.name} -> ${to.name}"))
            })
        },
        smsLogin = if (provenance.origin == RunOrigin.Ui && !conditionCheck && !runSpec.readOnly) {
            com.wangzan101.mika.cap.phone.sms.AndroidSmsLogin(service, phoneServices.smsLogin, appAccess,
                accessedPhone, privatePhone) { phone?.screen }.also { smsLogin = it }
        } else null,
        phoneCalls = (service.application as MikaApp).callPort.takeIf {
            !conditionCheck && (service.application as MikaApp).calls.settings.state.value.enabled
        },
        // 设置里允许 Mika 改自己时,才把 settings_list、settings_set 发给模型
        selfConfig = if (FeatureFlags.get(service).selfConfig()) {
            com.wangzan101.mika.settings.SelfConfigImpl(FeatureFlags.get(service))
        } else null,
        stableRefs = speed.inlineScreen, inlineScreen = speed.inlineScreen,
        adaptiveVision = speed.adaptiveVision,
        deepLinks = speed.deepLinks, memory = memory, profile = if (memory != null) memoryRuntime.profile() else null,
        taskMemory = taskMemory,
        utterances = utterances, conversationId = conversationId,
        web = web,
        // 同一个网站一页一页看:换页前补足停留,不连着秒开
        webPacing = { PagePacing.human() },
        // 停留时像人一样把页面轻轻滚几下
        webIdleScroll = { IdleScroll(Random.Default) },
        // 在 App 里一步一步操作:动作之间停一会儿,限制浏览快慢的 App 停得更久、屏数有上限
        // (全局档位 + 设置里的按应用覆盖)
        appPacing = { pace.appPacing() },
        appTempo = { pace.of(it).tempo },
        appCapped = { pace.capped(it) },
        appIdleScroll = { IdleScroll(Random.Default) },
        // 上传只认当前工作区里的普通文件(与沙箱同一套路径规则);技能目录、工作区外一律越界
        workspacePath = web?.let { { p: String -> sandbox.uploadCheck(workspaceKey, p) } },
        // 同项目对话共用 p:<projectId>,其余按对话隔离
        sandbox = sandbox, workspace = { workspaceKey.wire },
        sandboxBudgetMs = sandboxBudgetMs,
        deviceFiles = if (deviceImports != null && FeatureFlags.get(service).deviceFiles()) {
            AndroidDeviceFiles(phoneServices.hub, com.wangzan101.mika.settings.DeviceDirectoryStore.get(service), deviceImports)
        } else null,
        folderFileImporter = if (deviceImports != null && folders != null) {
            FolderFileImporter { path, workspace -> deviceImports.fromFolder(path, workspace, folders::openForImport) }
        } else null,
        audio = audioPort,
        image = imagePort,
        imageSaveEnabled = imageSettings.state.value.saveEnabled,
        imageAskBeforeSave = imageSettings.state.value.askBeforeSave,
        imageMaxPerTask = imageSettings.state.value.maxPerTask,
        transcribeProvider = ::transcribePort,
        audioSaveEnabled = audioSettings.state.value.saveEnabled,
        audioAskBeforeSave = audioSettings.state.value.askBeforeSave,
        audioSpeakTool = audioSettings.state.value.speakTool,
        onSandboxCommand = { phoneRuntime.sandboxNote(StatusCopy.sandboxCommand(it, phoneRuntime.sandboxOnline())) },
        onSandboxProgress = { phoneRuntime.sandboxNote(StatusCopy.sandboxProgress(it, phoneRuntime.sandboxOnline())) },
        onSandboxPreparing = { phoneRuntime.sandboxNote(StatusCopy.RUNTIME_PREPARING) },
        onSandboxScript = { phoneRuntime.sandboxNote(StatusCopy.sandboxScript(phoneRuntime.sandboxOnline())) },
        imageOps = BitmapImageOps, runSpec = runSpec,
        userScreens = userScreens,
        scheduleTrigger = if (provenance.origin == RunOrigin.Ui) { trigger -> TriggerRuntime.schedule(service, trigger) } else null,
        scheduleAdmin = if (provenance.origin == RunOrigin.Ui) LocalScheduleAdmin(service) else null,
        childHost = childRunner,
        // 用户要求时写下或改写个人技能(和自学收下的放在同一处,设置里立刻能看到)
        personalSkills = memoryRuntime.skills(),
        // 这个对话之前几轮的运行记录(只读;history_read 只认 runSpec 里这个对话的轮次)
        runRecords = RunStore(service).reader(),
        // 这两天的通知原文和存着的各天、各周、各月的归纳(主动能力攒下的):只给用户在手机上发起的任务;主动能力没开时不去碰它
        notices = if (provenance.origin == RunOrigin.Ui && !conditionCheck && FeatureFlags.get(service).proactive()) {
            (service.application as MikaApp).proactive.noticeSource()
        } else null,
        // 机主结果推送:只给用户明说「发我微信」的手机 UI 主任务;绑定快照、复查与同文幂等都在发送器一侧
        wechatOwner = if (provenance.origin == RunOrigin.Ui && !conditionCheck && explicitWechatOwnerRequest(goal)) {
            AndroidWechatOwnerPort(service.application as MikaApp, key)
        } else null,
        mcpServers = mcpEndpoints(),
        // 仅用户明确配置并启用时提供通用搜索;否则继续用浏览器搜索。
        webSearch = com.wangzan101.mika.settings.WebSearchSettings.get(service).service(),
        // 已 root 的手机上用户打开了「允许 Mika 执行 root 命令」才登记 root 工具(子任务、条件检查不给)
        root = phoneServices.shellManager.root.takeIf { !conditionCheck && it.commandsAllowed() }
            ?.let { r -> RootCommands(RootShell::su, r::commandsAllowed) { a -> if (a == null) r.suGone() else r.commandsDenied(a) } },
        // 看/操作 Mika 自己;开关关着时只给只读状态
        self = selfPort,
        selfOperate = selfPort?.takeIf { FeatureFlags.get(service).selfOperate() },
        pluginTools = pluginTools, pluginScriptRunner = pluginRunner,
        pluginArgsDir = java.io.File(service.cacheDir, "plugin-args"))
    dispatcher = tools
    tools.manualControl = manualControl
    manualTargetNow = target@{
        if (stopPressed || job?.isActive != true || takeover?.handedOver == true) return@target null
        val frame = runState._frames.value ?: return@target null
        if (dispatcher?.surface == WEB) {
            val stack = webStack ?: return@target null
            val tab = stack.pool.current(key) ?: return@target null
            if (!runState._webOpen.value || stack.handedOver || stack.onBorrowedTab) return@target null
            TaskRun.ManualTarget(stack.screen, "web:${tab.id}", frame.width, frame.height, stack.host.width, stack.host.height)
        } else {
            val p = phone ?: return@target null
            val display = p.display.current ?: return@target null
            if (runState._webOpen.value || !a.previewAllowed()) return@target null
            TaskRun.ManualTarget(accessedPhone, "phone:${display.displayId}", frame.width, frame.height, display.width, display.height)
        }
    }
    side.launch { manualControl.state.collect { loop?.state?.value?.let(::publish) } }
    return RunTools(runApproval, promptHook, tools)
}
