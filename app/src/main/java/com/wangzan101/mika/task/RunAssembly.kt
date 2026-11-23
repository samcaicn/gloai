package com.wangzan101.mika.task

import android.os.IBinder
import android.util.DisplayMetrics
import com.wangzan101.mika.browser.PageLoans
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.web.WebStack
import com.wangzan101.mika.cap.web.WebTab
import com.wangzan101.mika.core.device.access.AppAccessDenied
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.phone.AppPacePlan
import com.wangzan101.mika.core.model.decision.DecisionModel
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.learn.memory.ProfileEntry
import com.wangzan101.mika.core.learn.memory.TaskMemoryStore
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.ResourceScope
import com.wangzan101.mika.core.run.trace.RunTrace
import com.wangzan101.mika.core.model.usage.RunUsageTally
import com.wangzan101.mika.core.model.usage.DeepSeekRunCost
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.screen.user.UserScreenSource
import com.wangzan101.mika.record.DetailedTraces
import com.wangzan101.mika.record.RunHandle
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.RunApproval
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 一次运行装配过程中各阶段共用、收尾时要放掉的状态。
 * 各阶段([assembleModels]、[assemblePhone]…)把建好的东西记在这里;不经「建完再记到变量」,取消落在中间也不会漏。
 */
internal class RunAssembly(
    val traces: DetailedTraces,
    // 整个运行共用一份已报告 Token 预算；子任务沿用协程上下文里的这份，不另开额度。
    val tokenBudget: Long?,
    showCost: Boolean,
    // 询问 / 自主:开工时取一份,开发者选项里换开关时跟着变;收尾时交还
    val approvalControl: com.wangzan101.mika.settings.ApprovalControl,
    // 任务期间的旁路协程(助手连接状态、静音与挪走监视、状态发布),任务结束一并取消
    val side: CoroutineScope,
) {
    // 虚拟屏、网页显示等:建好当场归它,收尾时一起放掉
    val resources = ResourceScope()
    val traceTicket = traces.ticket()
    // 这次运行里经计量的模型请求(含旁路调用),收尾时记进运行记录
    val tally = RunUsageTally(tokenBudget, if (showCost) DeepSeekRunCost() else null)
    // 手机一侧(内置助手、无障碍、虚拟屏)第一次 launch_app 时才接上:只用网页、沙箱的任务不需要它们
    var connection: com.wangzan101.mika.cap.phone.shell.ShellBinding? = null
    var smsLogin: com.wangzan101.mika.cap.phone.sms.AndroidSmsLogin? = null
    var watcher: DeviceStateWatcher? = null
    var mute: AudioMute? = null
    // 模型标下的任务结束时各 App 的去留(在手机端口上);收尾时交给内置助手
    var afterTask: com.wangzan101.mika.core.screen.uistate.AfterTaskMarks? = null
    var shellDeath: IBinder.DeathRecipient? = null
    var shellBinder: IBinder? = null
    var record: RunHandle? = null
    var runStore: RunStore? = null
    // 第一次打开网址时才建;没建过就不用关
    var webStack: WebStack? = null
    // 当前界面(手机/网页)要看分发器;画中画的画面来源随它切换
    var dispatcher: ToolDispatcher? = null
    var mcpEvents: AutoCloseable? = null
    var liveFeed: AutoCloseable? = null
    var approval: RunApproval? = null
    // 应用访问边界:工具和预览画面共用一份(接手机端口时建),窗口没变时核对结果直接沿用,预览不必每一帧都问窗口
    var accessGuard: com.wangzan101.mika.core.screen.uistate.AppAccessScreenPort? = null
    var smsPrivacy: com.wangzan101.mika.core.cap.phone.sms.SmsPrivateScreen? = null

    suspend fun previewAllowed(): Boolean {
        if (smsPrivacy?.imagesPrivate == true) return false
        val guard = accessGuard ?: return false
        return try {
            guard.checkAppAccess()
            true
        } catch (e: CancellationException) { throw e }
        catch (_: AppAccessDenied) { false }
    }
}

internal class RunModels(
    val model: ChatModel,
    // 用户在浏览器页上对着一页问的:那一页借给这个任务当当前网页标签
    val pageLoan: PageLoans.Loan<WebTab>?,
    val light: ChatModel?,
    val decision: DecisionModel?,
    val routedLight: ChatModel?,
    val vision: ChatModel?,
    val visionFallbacks: List<ChatModel>,
    val lightFallbacks: List<ChatModel>,
    val knowledge: KnowledgeStore,
    val skills: GuideCatalog,
)

internal class RunPhone(
    val appAccess: com.wangzan101.mika.settings.AppAccessStore,
    val metrics: DisplayMetrics,
    val pace: AppPacePlan,
    val shellUp: MutableStateFlow<Boolean>,
    val states: DeviceStateWatcher,
    val phoneScreen: LazyPhoneScreen,
    val accessedPhone: com.wangzan101.mika.core.screen.uistate.AppAccessScreenPort,
    val privatePhone: com.wangzan101.mika.core.cap.phone.sms.SmsPrivateScreen,
)

internal class RunRecord(val sink: RunSink, val trace: RunTrace?)

internal class RunMemory(
    val apps: List<Pair<String, String>>,
    val speed: SpeedFlags,
    val switches: MemorySwitches,
    val memory: MemoryStore?,
    val taskMemory: TaskMemoryStore?,
    val profileSnapshot: List<ProfileEntry>,
    val utterances: UserUtterances,
    val catalog: GuideCatalog,
)

internal class RunPrompt(val userScreens: UserScreenSource, val basePrompt: String, val personaLabel: String?, val runSpec: RunSpec)

internal class RunTools(val runApproval: RunApproval, val promptHook: com.wangzan101.mika.plugin.PluginPromptHook, val tools: ToolDispatcher)
