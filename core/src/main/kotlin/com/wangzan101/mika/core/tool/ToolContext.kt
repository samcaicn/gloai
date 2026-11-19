package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.memory.Change
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.memory.ProfileStore
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.vision.ImageOps
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.core.visual.Shots
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.knowledge.BundledScripts
import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.ScheduleAdmin
import com.wangzan101.mika.core.child.ChildRunHost
import com.wangzan101.mika.core.run.RunSpec

/** 本任务的配置(模型、技能、记忆、开关);能力各自的端口与开关在能力里。 */
internal class ToolConfig(
    val visionEnabled: Boolean,
    val skills: GuideCatalog?,
    val recordFrames: Boolean,
    val vision: VisionService?,
    val inlineScreen: Boolean,
    /** 元素优先:可读页面不把截图送给主模型,读不到时自动补图。 */
    val adaptiveVision: Boolean,
    val memory: MemoryStore?,
    val profile: ProfileStore?,
    val utterances: UserUtterances?,
    val conversationId: String?,
    val imageOps: ImageOps?,
    /** 存下一个定时任务,并说明能不能准时(见 [Scheduled])。 */
    val scheduleTrigger: ((TriggerDefinition) -> Scheduled)? = null,
    val scheduleAdmin: ScheduleAdmin? = null,
    val runSpec: RunSpec? = null,
    val childHost: ChildRunHost? = null,
    /** 这个对话里用户发来的屏幕;没有时不提供 view_user_screen。 */
    val userScreens: com.wangzan101.mika.core.userscreen.UserScreenSource? = null,
    /** 用户的个人技能;给了(且有技能目录、用户本次说的话)才提供 skill_save。 */
    val personalSkills: com.wangzan101.mika.core.skills.PersonalSkills? = null,
    /** 改 Mika 自己设置的白名单入口;为空(用户没允许)时不提供 settings_list、settings_set。 */
    val selfConfig: com.wangzan101.mika.core.settings.SelfConfig? = null,
    /** 存下的运行记录;给了、而这个对话之前有记过运行的轮次时提供 history_read。 */
    val runRecords: com.wangzan101.mika.core.record.RunRecords? = null,
    /** 用户配置并启用的网页搜索;为空时不提供 web_search。 */
    val webSearch: com.wangzan101.mika.core.search.WebSearch? = null,
    /** 读 Mika 自己的状态;只对手机上、有用户的 UI 主任务给。 */
    val self: com.wangzan101.mika.core.self.SelfPort? = null,
    /** 操作 Mika 自己的界面/文件/通知/剪贴板;用户打开「允许 Mika 操作自己的界面」时才给。 */
    val selfOperate: com.wangzan101.mika.core.self.SelfPort? = null,
    /** Save a captured PNG in this task's workspace; screen tools do not depend on the sandbox capability. */
    val saveScreenshot: (suspend (String, ByteArray) -> String)? = null,
    /** 跨会话任务摘要;给了才提供 task_memory_search / task_memory_timeline / task_memory_step。 */
    val taskMemory: com.wangzan101.mika.core.memory.TaskMemoryStore? = null,
    /** 这两天的通知原文和存着的各天、各周、各月的归纳(主动能力攒下的);给了才提供 notices_read。 */
    val notices: com.wangzan101.mika.core.proactive.NoticeSource? = null,
)

/** 一个任务里按规矩不收的记忆写入,到这么多条为止不计入连续失败;再多就照常计(见 [ToolContext.noteMemoryRefusal])。 */
internal const val MAX_QUIET_MEMORY_REFUSALS = 12

/**
 * 工具执行时共用的东西:配置、核查材料、能力放进来的东西([get]),以及跨工具的少量状态(被拒的付款申报、允许打开的链接……)。
 * 工具本身无状态,都从这里取。有界面的能力的读屏状态也是放进来的一份,由界面状态一侧取用。
 */
internal class ToolContext(
    val config: ToolConfig,
    val evidence: EvidenceLog,
    /** 本任务可能提供的全部工具的声明(页面脚本按动作找工具)。 */
    val tools: ToolIndex,
    /** 能跑技能脚本的能力;没有为 null。 */
    val scripts: BundledScripts?,
    val shots: Shots,
) {
    private val parts = HashMap<CapabilityKey<*>, Any>()

    /** 能力在任务开始时放进来的东西;没放为 null。 */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: CapabilityKey<T>): T? = parts[key] as T?

    operator fun <T : Any> set(key: CapabilityKey<T>, value: T) { parts[key] = value }

    /** 最近一次被拒的付款申报;接管付款时补上模型没写的商品和金额。 */
    var refusedPay: ConfirmationRequest? = null
    /** 本次任务有没有成功打开过 App;没有时核查器知道没有屏幕可对照。 */
    var appOpened = false
    /** 本次调用的编号;页面脚本里的动作按它编号。 */
    var callId = ""
    /** 自己不动界面的工具(页面脚本)里的动作经它回到执行入口;只读调用、测试里不给时里面的动作一律不做。 */
    var sub: SubActions? = null
    /** 自身工具的当次同意;由 AgentLoop 把等用户回答的那条路接进来,测试里换成假实现。 */
    var ask: AskPort? = null
    /** 本任务已经发过几条自身通知(每任务限 3 条)。 */
    var selfNotifies = 0
    /** 本任务已经做过几次用户浏览器动作(打开/搜索/代登录,每任务限 5 次)。 */
    var selfBrowserOps = 0
    /** 本次调用所在的轮次(循环的「第 N 步」)。 */
    var currentStep: Int
        get() = shots.step
        set(v) { shots.step = v }

    /** 参考资料里给过模型的链接;不带 action 的 open_link 只能开这些(uri 与包名都要一致)。 */
    val allowedLinks = mutableSetOf<Pair<String, String?>>()

    private val saved = mutableListOf<Change>()
    val savedChanges: List<Change> get() = synchronized(saved) { saved.toList() }
    fun noteSaved(c: Change) = synchronized(saved) { saved += c }

    /** 最近一次 memory_search 返回的稳定编号;null 表示本任务还没搜过,空列表表示最近搜不到。 */
    @Volatile private var memoryCandidates: List<String>? = null
    fun noteMemorySearch(ids: List<String>) { memoryCandidates = ids }
    fun memorySearchCandidates(): List<String>? = memoryCandidates

    private val memoryRefusals = mutableSetOf<String>()
    /**
     * 记一次按规矩不收的记忆写入;返回这一次还算不算「只是答复、不计入连续失败」:头一回不收这一条,
     * 而且本任务不收的还没超过 [MAX_QUIET_MEMORY_REFUSALS] 条。同一条被原样再提(答复里说了别再试)、或者不收的攒得太多,
     * 就照常计失败,交给连续失败的守卫去停——手机上默认不限步数,不能指望步数上限兜底。
     */
    fun noteMemoryRefusal(key: String): Boolean =
        synchronized(memoryRefusals) { memoryRefusals.add(key) && memoryRefusals.size <= MAX_QUIET_MEMORY_REFUSALS }

    /** 这次调用的工具会由分发器报实际效果(effect):「没变化」由效果来说,这里不另提示。 */
    var reportsEffect = false

    /** 这次调用里能力为节奏有意停下的时间(见 [ToolExecution.waitMs]);能力在停下时往上加,分发器每次调用清零后交进结果。 */
    var pacedWaitMs = 0L

    /** 这次调用里停留时轻轻滚了几下页面(见 [com.wangzan101.mika.core.uistate.IdleScroll]);和 [pacedWaitMs] 一样每次调用清零,只进运行记录。 */
    var idleScrolls = 0
}

/** 模型写的申报字段截短到界面原样显示得下。 */
internal fun capField(s: String): String = s.trim().let { if (it.length <= CONFIRM_FIELD_MAX) it else it.take(CONFIRM_FIELD_MAX - 1) + "…" }
