package com.wangzan101.mika.core.assembly

import com.wangzan101.mika.core.agent.CoreSections
import com.wangzan101.mika.core.agent.DO_STEPS_TOOL
import com.wangzan101.mika.core.cap.phone.AppPacing
import com.wangzan101.mika.core.cap.phone.AppTempo
import com.wangzan101.mika.core.cap.phone.PHONE
import com.wangzan101.mika.core.cap.phone.PhoneCapability
import com.wangzan101.mika.core.cap.root.RootCapability
import com.wangzan101.mika.core.cap.root.RootGroup
import com.wangzan101.mika.core.cap.root.RootPort
import com.wangzan101.mika.core.cap.sandbox.SandboxCapability
import com.wangzan101.mika.core.cap.sandbox.SANDBOX
import com.wangzan101.mika.core.cap.sandbox.SANDBOX_BUDGET_MS
import com.wangzan101.mika.core.cap.sandbox.SandboxGroup
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.sandbox.FolderFileImporter
import com.wangzan101.mika.core.cap.devicefiles.DEVICE_FILES
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilesCapability
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilesGroup
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.core.cap.web.WebCapability
import com.wangzan101.mika.core.cap.web.WebGroup
import com.wangzan101.mika.core.cap.web.WebPort
import com.wangzan101.mika.core.screen.uistate.IdleScroll
import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.screen.uistate.PlacePace
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.tool.PluginCapability
import com.wangzan101.mika.core.tool.PluginScriptTool
import com.wangzan101.mika.core.tool.PluginToolSpec
import com.wangzan101.mika.core.tool.ScriptRunner
import com.wangzan101.mika.core.general.GeneralGroup
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.run.record.RunRecords
import com.wangzan101.mika.core.learn.memory.ProfileStore
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.core.learn.skills.PersonalSkills
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.core.tool.ToolConfig
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.screen.vision.ImageOps
import com.wangzan101.mika.core.screen.vision.VisionService
import com.wangzan101.mika.core.workspace.WorkspacePathCheck
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.ScheduleAdmin
import com.wangzan101.mika.core.run.ChildRunHost
import com.wangzan101.mika.core.screen.user.UserScreenSource
import java.io.File

private val helperFileTools = DeviceFilesGroup.withSearchAndCopy(searchAndCopyTools(DEVICE_FILES))

/**
 * 一个任务的工具:装上手机、网页、沙箱三种能力和核心工具。这是唯一同时认识几种能力的地方。
 * 没给端口的能力照样装上(工具都不提供),按名字查声明时它们的工具也查得到。root 命令例外:没给端口就整个不装。
 */
fun toolDispatcher(
    screen: ScreenPort,
    visionEnabled: Boolean = false,
    /** 只读的知识与技能目录;为空时不提供 knowledge_search / knowledge_read / skill_search / skill_read。 */
    skills: GuideCatalog? = null,
    /** 读屏时另截一张画面给运行记录(看不了图的模型也留画面)。 */
    recordFrames: Boolean = false,
    /** 看图模型;为空(设置里没有能看图的模型)时不提供视觉类工具。 */
    vision: VisionService? = null,
    /** 内置浏览器;为空时不提供任何网页工具,只有手机界面。 */
    web: WebPort? = null,
    /** 网页上传的工作区路径检查(规范化、符号链接解析由 app 侧做)。 */
    workspacePath: ((String) -> WorkspacePathCheck)? = null,
    /** 网页一页一页看的节奏(换页前补足停留,见 [PagePacing]);默认不等。 */
    webPacing: () -> PagePacing = { PagePacing.Off },
    /** 网站对浏览快慢的要求(按主机名);默认按 [skills] 里站点知识的 pace。 */
    webPlacePace: (String) -> PlacePace = { host -> PlacePace.of(skills?.sitePace(host)) },
    /** 网页停留时轻轻滚几下页面(见 [IdleScroll]);默认不滚。 */
    webIdleScroll: () -> IdleScroll? = { null },
    /** 在 App 里一步一步操作的节奏(改动屏幕的动作之前补足停留,见 [AppPacing]);默认不等。 */
    appPacing: () -> AppPacing = { AppPacing.Off },
    /** App 的实际操作节奏(按包名,见 [AppTempo]);默认按 [skills] 里 App 知识的 pace。 */
    appTempo: (String) -> AppTempo = { pkg -> AppTempo.of(skills?.appPace(pkg)) },
    /** 哪些 App 限制浏览快慢(按包名,计屏数、受上限);默认同 [appTempo] 取 App 知识里标 careful 的。 */
    appCapped: (String) -> Boolean = { pkg -> PlacePace.of(skills?.appPace(pkg)) == PlacePace.CAREFUL },
    /** App 里新的一屏上的停留时轻轻滚几下(见 [IdleScroll]);默认不滚。 */
    appIdleScroll: () -> IdleScroll? = { null },
    /** 跨读屏的稳定编号:读屏给模型编号和 s<N>,动作前翻译回端口编号。 */
    stableRefs: Boolean = false,
    /** 写操作成功后等界面稳定再读一屏,把与上一屏的差异放进结果的 screen 字段;隐含 [stableRefs]。 */
    inlineScreen: Boolean = false,
    /** 先给模型元素表；只在元素信息明显不足时自动附图。动作仍带回元素变化。 */
    adaptiveVision: Boolean = false,
    /** 提速开关 deepLinks:提供打开链接的工具。 */
    deepLinks: Boolean = false,
    /** 记忆;和 [utterances] 都给了才提供 memory_search / memory_save / memory_edit。 */
    memory: MemoryStore? = null,
    profile: ProfileStore? = null,
    /** 用户本次说过的话,memory_save / memory_edit 的引文据此校验;AgentLoop 往里加。 */
    utterances: UserUtterances? = null,
    /** 本次任务所在的对话;记进记忆的出处。 */
    conversationId: String? = null,
    /** Linux 沙箱;设备支持时提供沙箱工具(没准备好时工具调用会等它)。 */
    sandbox: SandboxPort? = null,
    /** 本次任务的工作区(对话 ID)。 */
    workspace: () -> String = { "default" },
    /** 沙箱开始跑一条命令或脚本时(任务卡片显示「沙箱:…」)。 */
    onSandboxCommand: (String) -> Unit = {},
    /** 沙箱里 `mika progress` 报的进度。 */
    onSandboxProgress: (String) -> Unit = {},
    /** 沙箱工具在等运行环境准备好(任务卡片显示「正在准备」)。 */
    onSandboxPreparing: () -> Unit = {},
    /** 开始跑技能脚本时;不给就按 [onSandboxCommand] 报。 */
    onSandboxScript: ((String) -> Unit)? = null,
    /** 这个任务里沙箱命令累计最多跑多久(设置 › 干活时的档位);默认 [SANDBOX_BUDGET_MS]。 */
    sandboxBudgetMs: Long = SANDBOX_BUDGET_MS,
    /** 发给主模型的图统一编码用;为空时原样发。 */
    imageOps: ImageOps? = vision?.ops,
    /** 开局约束允许的能力；旧调用方不传时仍得到今天的工具。 */
    runSpec: RunSpec = RunSpec(RunId("legacy"), "legacy", "legacy"),
    /** 这次任务接上的所有外部 MCP 端点(不分来源,调用方在一处拼好);每个成为一个能力,按 serverId 区分。 */
    mcpServers: List<McpConnection> = emptyList(),
    /** 存下一个定时任务,并说明能不能准时(见 [Scheduled])。 */
    scheduleTrigger: ((TriggerDefinition) -> Scheduled)? = null,
    scheduleAdmin: ScheduleAdmin? = null,
    childHost: ChildRunHost? = null,
    /** 这个对话里用户发来的屏幕;给了而这次的话或之前几轮里带着屏幕时提供 view_user_screen。 */
    userScreens: UserScreenSource? = null,
    /** 用户的个人技能;和 [skills]、[utterances] 都给了才提供 skill_save(用户要求时写下或改一份个人技能)。 */
    personalSkills: PersonalSkills? = null,
    /** 改 Mika 自己设置的白名单入口;为空时不提供 settings_list、settings_set(见 [SelfConfig])。 */
    selfConfig: com.wangzan101.mika.core.self.SelfConfig? = null,
    /** 存下的运行记录;给了、而这个对话之前有记过运行的轮次时提供 history_read(查之前几轮读到的东西)。 */
    runRecords: RunRecords? = null,
    /** 声音生成/保存;为空时不装音频能力(没配置声音模型或用户没打开)。 */
    audio: com.wangzan101.mika.core.cap.audio.AudioPort? = null,
    /** 用户允许把音频存进音乐库;默认关。 */
    audioSaveEnabled: Boolean = false,
    /** 每次保存前是否问用户;默认问。 */
    audioAskBeforeSave: Boolean = true,
    /** 用户允许模型主动说话;默认关。 */
    audioSpeakTool: Boolean = false,
    /** 固定音频转写端口;为空且未给 [transcribeProvider] 时不装转写能力。 */
    transcribe: com.wangzan101.mika.core.cap.transcribe.TranscribePort? = null,
    /** 随当前 STT 选择动态解析转写端口;目录和执行都重新检查,不重建任务内其他状态。 */
    transcribeProvider: (() -> com.wangzan101.mika.core.cap.transcribe.TranscribePort?)? = null,
    /** 图片生成/改图/存相册;为空时不装图片能力(没配置图片模型或用户没打开)。 */
    image: com.wangzan101.mika.core.cap.image.ImagePort? = null,
    /** 用户允许保存图片到相册;默认关。 */
    imageSaveEnabled: Boolean = false,
    /** 每次保存前是否问用户;默认问。 */
    imageAskBeforeSave: Boolean = true,
    /** 每个任务最多生成几张;超过后工具拒绝,不去花服务商的钱。 */
    imageMaxPerTask: Int = 8,
    /** 以 root 跑命令(已 root 的手机上用户打开了「允许 Mika 执行 root 命令」才给);为空时 root 工具不登记。 */
    root: RootPort? = null,
    /** 用户配置并启用的通用网页搜索;为空时保持原有浏览器搜索路径。 */
    webSearch: com.wangzan101.mika.core.search.WebSearch? = null,
    /** 读 Mika 自己的状态;只对手机上、有用户的 UI 主任务给。 */
    self: com.wangzan101.mika.core.self.SelfPort? = null,
    /** 操作 Mika 自己的界面/文件/通知/剪贴板;用户打开「允许 Mika 操作自己的界面」时才给。 */
    selfOperate: com.wangzan101.mika.core.self.SelfPort? = null,
    phoneCalls: com.wangzan101.mika.core.calls.PhoneCallPort? = null,
    smsLogin: com.wangzan101.mika.core.cap.phone.sms.SmsLoginPort? = null,
    /** 跨会话任务摘要;给了才提供 task_memory_search / task_memory_timeline / task_memory_step。 */
    taskMemory: com.wangzan101.mika.core.learn.memory.TaskMemoryStore? = null,
    /** 这两天的通知原文和存着的各天、各周、各月的归纳(主动能力攒下的、够格带进任务的);给了才提供 notices_read。 */
    notices: com.wangzan101.mika.core.proactive.NoticeSource? = null,
    /** 用户在 UI 发起任务时可访问的设备文件。 */
    deviceFiles: com.wangzan101.mika.core.cap.devicefiles.DeviceFilesPort? = null,
    /** 把任务结果投递到机主绑定微信的出口;为空(任务没明确要发微信)时不提供 wechat_send_owner。没绑定时出口照样在,发送照实回报不可用。 */
    wechatOwner: com.wangzan101.mika.core.cap.wechat.WechatOwnerPort? = null,
    /** 启用的插件工具(名字已拼成 plugin_<id>_<name>);给了 [pluginScriptRunner] 才放进任务。 */
    pluginTools: List<PluginToolSpec> = emptyList(),
    /** 在 App 进程内跑插件脚本的入口;为空时插件工具不登记。 */
    pluginScriptRunner: ScriptRunner? = null,
    /** 插件脚本参数临时文件放哪;App 用 cacheDir,JVM 测试用 java.io.tmpdir。 */
    pluginArgsDir: File = File(System.getProperty("java.io.tmpdir"), "mika-plugin-args"),
    /** Streams a document from a system-granted folder to this task's workspace. */
    folderFileImporter: FolderFileImporter? = null,
): ToolDispatcher {
    val attendedUi = runSpec.provenance.origin == RunOrigin.Ui && runSpec.provenance.attendance != Attendance.Unattended
    val helper = deviceFiles?.takeIf { attendedUi && (runSpec.capabilities == null || DEVICE_FILES in runSpec.capabilities) }
    val screenshotStore = sandbox?.takeIf {
        it.status() !is com.wangzan101.mika.core.cap.sandbox.SandboxStatus.Unsupported &&
            !runSpec.readOnly && runSpec.provenance.origin != com.wangzan101.mika.core.run.RunOrigin.Child &&
            (runSpec.capabilities == null || "sandbox" in runSpec.capabilities)
    }?.let { com.wangzan101.mika.core.cap.sandbox.WorkspaceScreenshots(it, workspace) }
    val config = ToolConfig(visionEnabled, skills, recordFrames, vision, inlineScreen, adaptiveVision, memory, profile, utterances, conversationId,
        imageOps, scheduleTrigger, scheduleAdmin, runSpec, childHost, userScreens, personalSkills, selfConfig, runRecords, webSearch, self, selfOperate,
        screenshotStore?.let { store -> { path, png -> store.save(path, png) } }, taskMemory = taskMemory, notices = notices)
    val providers: List<CapabilityProvider> = listOf(
        object : CapabilityProvider {
            override val id = PHONE
            override fun open(spec: RunSpec) = PhoneCapability(screen, deepLinks, appPacing(), appTempo, appCapped, appIdleScroll(), smsLogin)
        },
        object : CapabilityProvider {
            override val id = WEB
            override fun open(spec: RunSpec) = WebCapability(web, workspacePath, webPacing(), webPlacePace, webIdleScroll())
        },
        object : CapabilityProvider {
            override val id = SANDBOX
            override fun open(spec: RunSpec) = SandboxCapability(sandbox, workspace, visionEnabled,
                onSandboxCommand, onSandboxProgress, onSandboxPreparing, onSandboxScript,
                folderAccess = attendedUi && !spec.readOnly,
                folderFileImporter = folderFileImporter,
                // When both sources are available, the helper group already registers the shared tools.
                folderTools = if (helper == null) searchAndCopyTools(SANDBOX) else emptyList(),
                budgetMs = sandboxBudgetMs)
        },
        // 端口为空时整个能力不进装配(任务没明确要发微信)
        object : CapabilityProvider {
            override val id = com.wangzan101.mika.core.cap.wechat.WechatOwnerCapability.WECHAT_OWNER
            override fun open(spec: RunSpec) =
                wechatOwner?.let { com.wangzan101.mika.core.cap.wechat.WechatOwnerCapability(it) }
        },
    ) + listOfNotNull(if (transcribe != null || transcribeProvider != null) {
        object : CapabilityProvider {
            override val id = com.wangzan101.mika.core.cap.transcribe.TRANSCRIBE
            override fun open(spec: RunSpec) = com.wangzan101.mika.core.cap.transcribe.TranscribeCapability(transcribe, transcribeProvider)
        }
    } else null) + listOfNotNull(audio?.let { port ->
        object : CapabilityProvider {
            override val id = com.wangzan101.mika.core.cap.audio.AUDIO
            override fun open(spec: RunSpec) = com.wangzan101.mika.core.cap.audio.AudioCapability(port, audioSaveEnabled, audioAskBeforeSave, audioSpeakTool)
        }
    }) + listOfNotNull(image?.let { port ->
        object : CapabilityProvider {
            override val id = com.wangzan101.mika.core.cap.image.IMAGE
            override fun open(spec: RunSpec) = com.wangzan101.mika.core.cap.image.ImageCapability(
                port, imageSaveEnabled, imageAskBeforeSave, imageMaxPerTask,
            )
        }
    }) + listOfNotNull(root?.let { port ->
        object : CapabilityProvider {
            override val id = RootCapability.KEY.name
            override fun open(spec: RunSpec) = RootCapability(port)
        }
    }, phoneCalls?.let { port ->
        object : CapabilityProvider {
            override val id = "calls"
            override fun open(spec: RunSpec) = com.wangzan101.mika.core.calls.PhoneCallsCapability(port, spec.threadId)
        }
    }, helper?.let { port ->
        object : CapabilityProvider {
            override val id = DEVICE_FILES
            override fun open(spec: RunSpec) = DeviceFilesCapability(port, workspace, helperFileTools)
        }
    }, pluginScriptRunner?.takeIf { pluginTools.isNotEmpty() }?.let { runner ->
        object : CapabilityProvider {
            override val id = "plugin"
            override fun open(spec: RunSpec) = PluginCapability(pluginTools.map { PluginScriptTool(it, runner, pluginArgsDir) })
        }
    }) + mcpServers.map(::McpClientProvider)
    return RunAssembler(providers).assemble(runSpec, config, stableRefs, FORMER_PREFIX, RENAMED, RETIRED, MOVED)
}

/** 改名前各能力工具名的前缀:手机的没有前缀,网页的是 browser_。 */
private val FORMER_PREFIX = mapOf(PHONE to "", WEB to "browser_")
/** 屏幕工具第二次改名时动词部分的变化(以前 → 现在),手机、网页两套一样。 */
private val SCREEN_VERBS_MOVED = mapOf(
    "dump_elements" to "list_elements", "locate_on_screen" to "find_visually",
    "screen_description" to "describe", "check_screen" to "diagnose",
)

/** 除了前缀,其余部分也改过的(现在的名字 → 最早的名字;第二次改名的屏幕工具最早是几块屏共用的那个名字)。 */
private val RENAMED = mapOf("web_wait_for" to "browser_wait") +
    SCREEN_VERBS_MOVED.flatMap { (old, now) -> listOf(PHONE, WEB).map { "${it}_$now" to old } }

/**
 * 整个换了名字的(以前的名字 → 现在的名字)。一个拆成知识、技能两个的列两个:模型调旧名时按参数挑(见 [LegacyNames.replacements]),
 * 文字里的旧名两个都写上。
 */
private val MOVED: Map<String, List<String>> = mapOf(
    "find_skills" to listOf("knowledge_search", "skill_search"),
    "skill_view" to listOf("knowledge_read", "skill_read"),
    "run_script" to listOf("run_bundled_script"),
    "report_barrier" to listOf("handle_obstacle"),
    "report_failure" to listOf("end_not_done"),
    "request_input" to listOf("ask_user"),
    "user_takeover" to listOf("hand_to_user"),
    "recall_screenshot" to listOf("view_past_screens"),
) + SCREEN_VERBS_MOVED.flatMap { (old, now) -> listOf(PHONE, WEB).map { "${it}_$old" to listOf("${it}_$now") } }

/** 去掉了、没有替代工具的(以前的名字 → 现在怎么做):以前用工具交结果,现在不带调用的回复就是回答。 */
private val RETIRED = mapOf(
    "finish" to "reply to the user with your answer as text, without calling any tool",
    "phone_ask_app_ai" to "read the app's own screens yourself (asking an app's built-in AI assistant is no longer available)",
)

/** 改名前的工具名 → 现在的名字(读旧的路线、运行记录、个人技能时用)。 */
fun legacyNames(): LegacyNames =
    LegacyNames(knownTools(), FORMER_PREFIX, RENAMED, retired = RETIRED, moved = MOVED)

/** 读运行记录用的工具声明:改名前记下的旧名也认成现在的工具(动作、能力相同),旧的记录和新的评分一样。 */
fun recordedTools(): ToolIndex = legacyNames().let { legacy -> ToolIndex(allTools()) { legacy.resolve(it) } }

/** Mika 所有可能提供的工具,按发给模型的顺序。 */
internal fun allTools(): List<AgentTool> =
    GeneralGroup.tools + PhoneCapability.tools() + WebGroup.tools + SandboxGroup.tools + com.wangzan101.mika.core.cap.audio.AudioGroup.tools + com.wangzan101.mika.core.cap.transcribe.TranscribeGroup.tools + com.wangzan101.mika.core.cap.image.ImageGroup.tools + RootGroup.tools + com.wangzan101.mika.core.calls.callAgentTools + helperFileTools + com.wangzan101.mika.core.cap.wechat.wechatOwnerAgentTools

/** Mika 所有可能提供的工具的声明。 */
fun knownTools(): ToolIndex = ToolIndex(allTools())

/** Mika 所有可能提供的工具名(技能文档里提到的工具名据此校验)。 */
fun knownToolNames(): Set<String> = knownTools().names + DO_STEPS_TOOL
