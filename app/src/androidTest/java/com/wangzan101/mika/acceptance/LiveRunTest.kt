package com.wangzan101.mika.acceptance

import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.browser.PageAskModel
import com.wangzan101.mika.browser.PageConversations
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.apiKey
import com.wangzan101.mika.settings.choose
import com.wangzan101.mika.settings.configFor
import com.wangzan101.mika.settings.save
import com.wangzan101.mika.task.TaskEnvironment
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.ui.appString
import com.wangzan101.mika.ui.awaitOne
import com.wangzan101.mika.ui.typeAndSend
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机验收:用设置里配好的真实模型跑一个任务,只在显式传参时运行,平时整套测试里会被跳过。
 *
 * - 配模型(Key 只经参数传入,写进 App 自己的加密存储,不落别处):
 *   `-e liveKey <key> -e liveBaseUrl <url> -e liveModel <model> [-e liveId <id>]`,运行 [configureProvider]。
 * - 切主模型:`-e liveMain <id>`,同样运行 [configureProvider]。
 * - 跑任务:`-e liveGoal <目标> -e liveRun <记录目录名> [-e liveTimeoutSec 600] [-e liveOnTakeover cancel|handback]
 *   [-e livePace fast|standard|human] [-e liveMoveAwayAtStep N] [-e liveCancelAtStep N]`,运行 [runGoal]。经由真实界面:在输入框里写目标、点发送。
 *   liveCancelAtStep:到第 N 步时像点停止键一样停掉任务,再等停止后的交代记进对话(写进 result.txt 的 summary)。
 *   `-e liveKeepCheckSec N`:任务结束后看有没有留在后台运行的那一页(web_keep_open),有就隔 N 秒读两次页面里的媒体播放进度
 *   (video/audio 的 currentTime,同源的才读得到)和一个只计数的计时器,写进 result.txt 的 kept*(keptPage、keptMediaAdvanced、
 *   keptTimerAdvanced、keptNotification),再存一张它的画面 kept-frame.png;那一页不关,留着给人看。
 *   livePace:在 App 里操作的节奏 fast / standard / human(缺省 fast,跑得快;不改设置里的),比较快慢时传 standard。
 *   liveMoveAwayAtStep:到第 N 步时把虚拟屏上的 App 挪到主屏(模拟用户在主屏打开了同一个 App),
 *   记下暂停状态和通知,几秒后像点通知上的「继续」一样恢复。
 *
 * - 在浏览器页上问:`-e liveUrl <网址> -e liveGoal <问题> -e liveRun <记录目录名> [-e liveTimeoutSec 600] [-e liveDesktop true]
 *   [-e liveNew true] [-e liveTouchAtStep N -e liveResumeAfterSec S] [-e liveOnTakeover cancel|handback]`,运行 [runPageAsk]。
 *   经由真实界面:侧栏进浏览器,打开网址(liveDesktop 时换成电脑版),等页面加载完,在网页底下「问问 Mika」里写问题、点发送
 *   (liveNew 时先点「新对话」),这一页借给那个任务。liveTouchAtStep:到第 N 步时经窗口在网页上用手指往上划一下
 *   (和真人一样经 PageFrame,不点到链接),记下有没有暂停、横幅和小卡片出没出来,等 S 秒(缺省 5)后点横幅上的「继续」,记下恢复。
 *   不给 liveTouchAtStep 时,任务里因「你在操作」而暂停算失败(Mika 自己的点按、滚动不该让任务暂停),记录照写。
 *   result.txt 在 runGoal 那些之外还有:borrowed(页面借给了任务)、firstToolCalls、urlsOpened(web_navigate 打开过的网址)、
 *   reopenedSameUrl(又打开了这一页,不该有)、userTabUrlAtEnd、pausedByTouch、resumed、pauses(各种暂停的次数)、userPauses、
 *   conversationId、pageOrigin、listedAsWeb(侧栏里这条对话带「网页」小标签)。每一步、暂停、恢复、结束、侧栏各截一张主屏(step-NN.png 等)。
 *
 * - 聊天走前台的多轮:`-e deskScript "<步>||<步>…" -e deskRun <记录目录名> [-e deskWaitSec 300] [-e deskParallelMax N] [-e deskNew false]`,
 *   运行 [runDesk]。步有:`say:<一句>`(经输入框发出,等前台这一轮回完)、`wait:running|queued|needsyou|idle|report|tasks=N`
 *   (等任务板/汇报到那一步)、`sleep:<秒>`、`home`(退到桌面,之后的汇报走兜底)、`notifications`(记下此刻 Mika 的通知)。
 *   deskParallelMax 只在这次运行里临时改并行上限,结束时改回。result.txt 里每一句一行 turnN(第一个字/第一件任务/说完的毫秒、派出的任务、工具小标签、回话),
 *   前台说的汇报一行 reportN,最后的任务板、后台调过的工具和通知。
 *
 * 记录写到 App 外部文件目录 acceptance/<liveRun>/:log.txt(每步模型说的话、工具调用与结果摘要)、
 * result.txt(模型、目标、结果、步数、用时、最终回答)、虚拟屏每步画面与主屏截图;
 * 传了 `-e liveSettleSec N` 时等至多 N 秒让结局判定和学习做完,学到的写进 learned.txt。
 */
@RunWith(AndroidJUnit4::class)
class LiveRunTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()

    @After
    fun tearDown() {
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.evalHooks.operatingPace = null
    }

    @Test
    fun configureProvider() {
        val key = args.getString("liveKey")
        val main = args.getString("liveMain")
        assumeTrue(key != null || main != null)
        val store = ProviderStore.get(context)
        var saved: String? = null
        if (key != null) {
            val model = args.getString("liveModel") ?: error("liveModel missing")
            val id = args.getString("liveId") ?: model
            val baseUrl = args.getString("liveBaseUrl") ?: ProviderPresets.all.first().baseUrl
            // 能力按地址对得上的模板取(和在设置里选模板一样,含提示词缓存的方式)
            val preset = ProviderPresets.forBaseUrl(baseUrl) ?: ProviderPresets.all.first()
            store.save(
                ProviderConfig(
                    id, ProviderConfig.Kind.OpenAiCompat, baseUrl,
                    // 看图主模型、看图定位已验证:按参数打开(定位验证只在有实测证据时给)
                    model, preset.capabilities.copy(vision = args.getString("liveVision") == "true",
                        groundingVerified = args.getString("liveGroundingVerified") == "true"), name = id,
                ),
                key,
            )
            check(store.apiKey(id) == key) { "key not stored" }
            saved = id
        }
        // 配置要有用途在用,否则会被当作没人用清掉:没指定主模型时就用刚存的这条
        (main ?: saved)?.let { check(store.choose(ModelRole.Main, RoleChoice.Model(it))) { "cannot assign $it" } }
        // 看图主模型:看图用主模型
        if (args.getString("liveVision") == "true") store.choose(ModelRole.Vision, RoleChoice.SameAsMain)
    }

    @Test
    fun runGoal() {
        val goal = args.getString("liveGoal")
        assumeTrue(goal != null)
        // 测试拉起的进程没套上 App 语言,照正常启动把默认语言换成它:内置浏览器按它向网站要页面语言
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.getSystemService(android.app.LocaleManager::class.java).applicationLocales
                .takeUnless { it.isEmpty }?.let { android.os.LocaleList.setDefault(it) }
        }
        val run = args.getString("liveRun") ?: "run"
        val timeoutMs = (args.getString("liveTimeoutSec")?.toLong() ?: 600) * 1000
        val onTakeover = args.getString("liveOnTakeover") ?: "cancel"
        val moveAwayAt = args.getString("liveMoveAwayAtStep")?.toInt() ?: -1
        val cancelAt = args.getString("liveCancelAtStep")?.toInt() ?: -1
        val pace = OperatingPace.parse(args.getString("livePace")) ?: OperatingPace.FAST
        testTasks.evalHooks.operatingPace = pace
        var cancelled = false
        val dir = File(context.getExternalFilesDir(null), "acceptance/$run").apply { deleteRecursively(); mkdirs() }
        val log = File(dir, "log.txt")
        fun note(s: String) = log.appendText("[${System.currentTimeMillis()}] $s\n")

        DeviceSupport.shell()
        DeviceSupport.service()
        val store = ProviderStore.get(context)
        val cfg = store.configFor(ModelRole.Main) ?: error("no main model configured")
        val recorder = Recorder(::note)
        testTasks.modelFactory = { ctx, s -> TaskEnvironment.defaultModelFactory(ctx, s)?.let { recorder.wrap(it) } }
        note("model=${cfg.model} pace=${pace.name.lowercase()} goal=$goal")
        // 学习在任务结束后的进程级作用域里跑;开局记下那里已有几件常驻的事,收尾时等它回到这个数
        val memory = MemoryRuntime.of(context)
        val learningJob = memory.scope.coroutineContext[Job]!!
        val resident = learningJob.children.count { it.isActive }

        val before = DeviceTaskState.runs.value.toSet()
        compose.typeAndSend(goal!!)
        // 发出去就收起键盘:对话页下面的后台屏小画面才露得出来,和真人发完就收键盘一样
        compose.runOnUiThread {
            val w = compose.activity.window
            compose.activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(w, w.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        val chat = (context.applicationContext as MikaApp).chat
        chat.ui.value.blocked?.let { error("blocked by prerequisites: $it") }
        // 发出去了,对话里就有这一问;没有就立刻报,不白等到超时
        check(chat.ui.value.exchanges.any { it.goal == goal.trim() }) {
            "send did not go out: composer=${chat.ui.value.composer} exchanges=${chat.ui.value.exchanges.size} task=${DeviceTaskState.state.value}"
        }
        val t0 = System.currentTimeMillis()
        // 聊天走前台:这句先到前台,由它派成一件后台任务(跑在自己的子会话里),跟着派出来的那件;
        // 前台只回话、没派单时,前台的回话就是这一轮的回答
        val dispatched: Any? = DeviceSupport.pollUntil(timeoutMs) {
            compose.mainClock.advanceTimeBy(250)
            DeviceTaskState.runs.value.firstOrNull { it !in before }
                ?: chat.ui.value.exchanges.lastOrNull { it.goal == goal.trim() }
                    ?.takeIf { it.status != com.wangzan101.mika.chat.ui.ExchangeStatus.Working && it.deskTasks.isEmpty() }
                    ?.let { it.deskReply.orEmpty() }
        }
        val task = dispatched as? TaskRun
        val deskOnly = dispatched as? String
        note("desk dispatched=${task?.key} child=${task?.conversationId} deskOnlyReply=${deskOnly?.take(300)}")

        var lastStep = -1
        var takeovers = 0
        var movedAway = false
        var pausesHandled = 0
        var end: TaskState? = null
        var last: TaskState? = null
        while (task != null && System.currentTimeMillis() - t0 < timeoutMs) {
            val s = task.state.value
            if (s != last) {
                note("state $s")
                last = s
            }
            if (s is TaskState.Running && s.step != lastStep) {
                lastStep = s.step
                frame(dir, "step-%02d".format(s.step))
            }
            if (moveAwayAt > 0 && !movedAway && s is TaskState.Running && s.step >= moveAwayAt) {
                val shell = DeviceSupport.shell()
                val task = shell.topTaskId(testTasks.agentDisplayId.value)
                movedAway = true
                note("moving task $task (${shell.packageOfTask(task)}) to the main screen: ${task >= 0 && shell.moveTask(task, 0)}")
            }
            if (cancelAt > 0 && !cancelled && s is TaskState.Running && s.step >= cancelAt) {
                cancelled = true
                note("stopping at step ${s.step}")
                TaskService.cancel(context, task.key)
            }
            if (s is TaskState.Held && s.reason.resumable && pausesHandled == 0) {
                pausesHandled++
                Thread.sleep(3000)
                val shell = DeviceSupport.shell()
                note("paused; still paused after 3s: ${task.state.value}; main top task=${shell.topTaskId(0)} pkg=${shell.packageOfTask(shell.topTaskId(0))}")
                note("notifications: " + mikaNotifications())
                mainShot(dir, "paused-main")
                TaskService.resume(context, task.key)
                note("resume requested")
            }
            if (s is TaskState.WaitingTakeover && takeovers == 0) {
                takeovers++
                Thread.sleep(800)
                frame(dir, "takeover-agent")
                mainShot(dir, "takeover-main")
                note("takeover requested; action=$onTakeover")
                if (onTakeover == "handback") TaskService.handBack(context, runKey = task.key) else TaskService.cancel(context, task.key)
            }
            if (s is TaskState.Succeeded || s is TaskState.Failed || s == TaskState.Cancelled) {
                end = s
                note("desk board at end: ${chat.ui.value.deskTasks.joinToString { "${it.taskId} ${it.state}" }}")
                break
            }
            // 界面测试的时钟只在测试推它时才走:等的时候一起往前推,对话页的步骤、计时、小画面才会照常刷新(录演示要用)
            compose.mainClock.advanceTimeBy(250)
            Thread.sleep(250)
        }
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        if (end == null && task != null) {
            note("timeout, cancelling")
            TaskService.cancel(context, task.key)
        }
        Thread.sleep(1500)
        compose.waitForIdle()
        mainShot(dir, "end-main")
        // 停止后的交代:任务跑在前台派出的子会话里,等它当作那一轮的回答记进子会话
        val app = context.applicationContext as MikaApp
        val summary = if (end == TaskState.Cancelled) DeviceSupport.pollUntil(30_000) {
            app.conversations.turns(task!!.conversationId).lastOrNull()?.let(com.wangzan101.mika.core.agent.Conversation::stopSummary)
        }.also {
            note("stop summary: $it")
            compose.waitForIdle()
            mainShot(dir, "stopped-summary")
        } else null
        val outcome = when (end) {
            is TaskState.Succeeded -> "succeeded"
            is TaskState.Failed -> "failed"
            TaskState.Cancelled -> if (takeovers > 0) "takeover(cancelled)" else "cancelled"
            else -> if (deskOnly != null) "desk_reply" else "timeout"
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> deskOnly.orEmpty()
        }
        // liveSettleSec:等交出之后的结局判定和学习做完,把学到的写进 learned.txt
        val settleMs = (args.getString("liveSettleSec")?.toLong() ?: 0) * 1000
        if (settleMs > 0) {
            val settled = DeviceSupport.pollUntil(settleMs) { true.takeIf { learningJob.children.count { it.isActive } <= resident } } != null
            note("learning settled=$settled")
            File(dir, "learned.txt").writeText(learned(memory))
        }
        val keepSec = args.getString("liveKeepCheckSec")?.toLongOrNull() ?: 0
        val kept = if (keepSec > 0) keptPageCheck(keepSec, dir, ::note) else ""
        File(dir, "result.txt").writeText(
            "model=${cfg.model}\ngoal=$goal\noutcome=$outcome\nsteps=${recorder.calls}\nmaxStep=$lastStep\n" +
                "takeovers=$takeovers\npauses=$pausesHandled\nwallSec=$wall\ninputTokens=${recorder.inTokens}\noutputTokens=${recorder.outTokens}\n" +
                "answer=$answer\n" + (summary?.let { "summary=$it\n" } ?: "") + kept,
        )
    }

    /**
     * 聊天页走前台的多轮验收:按 [deskScript] 一步一步来,经真实界面在输入框里写字、点发送,旁边只读地看前台每一轮
     * ([DeskWatch]):第一个字、第一件任务、说完各用了多久,派了什么、顶上的工具小标签,任务板每次的变化。
     * 每一步截一张主屏,结束时把各轮和任务板写进 result.txt。
     */
    @Test
    fun runDesk() {
        val script = args.getString("deskScript")
        assumeTrue(script != null)
        val run = args.getString("deskRun") ?: "desk"
        val waitMs = (args.getString("deskWaitSec")?.toLong() ?: 300) * 1000
        testTasks.evalHooks.operatingPace = OperatingPace.parse(args.getString("livePace")) ?: OperatingPace.FAST
        val dir = File(context.getExternalFilesDir(null), "acceptance/$run").apply { deleteRecursively(); mkdirs() }
        val log = File(dir, "log.txt")
        fun note(s: String) = log.appendText("[${System.currentTimeMillis()}] $s\n")
        DeviceSupport.shell()
        DeviceSupport.service()
        val app = context.applicationContext as MikaApp
        val chat = app.chat
        val flags = FeatureFlags.get(context)
        val parallelBefore = flags.parallelMax()
        // 并行上限只在这次验收里临时改(Mika 自己的设置),结束时改回去
        args.getString("deskParallelMax")?.toInt()?.let { flags.setParallelMax(it) }
        val recorder = Recorder(::note)
        testTasks.modelFactory = { ctx, s -> TaskEnvironment.defaultModelFactory(ctx, s)?.let { recorder.wrap(it) } }
        if (args.getString("deskNew") != "false") compose.runOnUiThread { chat.newConversation() }
        compose.waitForIdle()
        note("parallelMax=${flags.parallelMax()} (was $parallelBefore) limit=${app.taskState.parallelLimit.value} script=$script")
        val watch = DeskWatch(chat.ui, ::note, System::currentTimeMillis).apply { start() }
        val sent = mutableListOf<Pair<String, Long>>()
        val results = StringBuilder()
        fun board() = chat.ui.value.deskTasks
        fun waitUntil(what: String, cond: () -> Boolean): Boolean {
            val ok = DeviceSupport.pollUntil(waitMs) {
                compose.mainClock.advanceTimeBy(250)
                true.takeIf { cond() }
            } != null
            note("wait $what: ${if (ok) "ok" else "TIMEOUT"} board=${board().joinToString { "${it.taskId} ${it.state}${it.position?.let { p -> "#$p" } ?: ""}" }}")
            return ok
        }
        val ended = setOf(DeskTaskPhase.Done, DeskTaskPhase.Failed, DeskTaskPhase.Cancelled)
        try {
            script!!.split("||").map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, step ->
                val (kind, value) = step.substringBefore(':') to step.substringAfter(':', "")
                note("step $i: $step")
                val ok = when (kind) {
                    "say" -> {
                        val seen = watch.turns().map { it.runId }.toSet()
                        val at = System.currentTimeMillis()
                        compose.typeAndSend(value)
                        compose.runOnUiThread {
                            val w = compose.activity.window
                            compose.activity.currentFocus?.clearFocus()
                            WindowCompat.getInsetsController(w, w.decorView).hide(WindowInsetsCompat.Type.ime())
                        }
                        chat.ui.value.blocked?.let { error("blocked by prerequisites: $it") }
                        sent += value.trim() to at
                        // 这一轮前台回完(说完或只派了单)才往下走
                        waitUntil("reply to \"$value\"") { watch.turnFor(value.trim(), seen)?.doneAt != null }
                    }
                    "wait" -> when {
                        value == "running" -> waitUntil(value) { board().any { it.state == DeskTaskPhase.Running } }
                        value == "needsyou" -> waitUntil(value) { board().any { it.state == DeskTaskPhase.NeedsYou } }
                        value == "queued" -> waitUntil(value) { board().any { it.state == DeskTaskPhase.Queued } }
                        value == "idle" -> waitUntil(value) {
                            board().isNotEmpty() && board().all { it.state in ended } && watch.turns().none { it.doneAt == null }
                        }
                        value.startsWith("tasks=") -> value.removePrefix("tasks=").toInt().let { n -> waitUntil(value) { board().size >= n } }
                        value == "report" -> watch.turns().count { it.isReport }.let { n -> waitUntil(value) { watch.turns().count { it.isReport && it.doneAt != null } > n } }
                        else -> error("unknown wait $value")
                    }
                    "sleep" -> { Thread.sleep(value.toLong() * 1000); true }
                    // 页面退到后台:之后的汇报该走兜底(写回会话、发通知);回来时从桌面上的入口重新打开
                    "home" -> { sh("input keyevent KEYCODE_HOME"); Thread.sleep(1500); true }
                    "notifications" -> { note("notifications: " + mikaNotifications()); true }
                    else -> error("unknown step $step")
                }
                compose.mainClock.advanceTimeBy(500)
                runCatching { compose.waitForIdle() }
                mainShot(dir, "step-%02d".format(i))
                results.append("step$i=$step ok=$ok\n")
            }
        } finally {
            watch.stop()
            flags.setParallelMax(parallelBefore)
            val turns = watch.turns()
            sent.forEachIndexed { i, (text, at) ->
                val turn = turns.firstOrNull { it.goal == text && it.seenAt >= at }
                results.append("turn$i.text=$text\nturn$i.${turn?.describe(at) ?: "missing"}\n")
            }
            turns.filter { it.isReport }.forEachIndexed { i, t -> results.append("report$i.${t.describe()}\n") }
            results.append("board=" + board().joinToString(" ; ") { "${it.taskId} ${it.state}${it.position?.let { p -> "#$p" } ?: ""} ${it.title}" } + "\n")
            results.append("conversationId=${chat.ui.value.conversationId}\n")
            // 存下来的轮次:页面不在时的汇报由兜底写回会话(status = desk_report),这里看得到
            chat.ui.value.conversationId?.let { id ->
                app.conversations.turns(id).forEachIndexed { i, t ->
                    results.append("saved$i=${t.status} user=${t.userText.take(40)} said=${t.outcome.replace('\n', ' ').take(160)}\n")
                }
            }
            results.append("backendSteps=${recorder.calls} backendTools=${recorder.toolCalls.map { it.first }.distinct().joinToString(",")}\n")
            results.append("notifications=${mikaNotifications()}\n")
            File(dir, "result.txt").writeText(results.toString())
            // 还在跑的停掉,不留在手机上
            board().filter { it.state !in ended }.forEach { v ->
                DeviceTaskState.runs.value.firstOrNull { it.conversationId == v.taskId }?.let { TaskService.cancel(context, it.key) }
            }
        }
    }

    /**
     * 任务结束后留在后台的那一页还在不在跑:隔 [seconds] 秒读两次页面里媒体的播放进度和一个只计数的计时器(只为这次验收放进页面,
     * 不碰页面自己的东西),存一张它那块显示的画面。返回写进 result.txt 的几行。
     */
    private fun keptPageCheck(seconds: Long, dir: File, note: (String) -> Unit): String {
        val keeper = (context.applicationContext as MikaApp).pageKeeper
        val page = keeper.page.value
        val tab = keeper.keptTab()
        if (page == null || tab == null) {
            note("no kept page after the run")
            return "keptPage=none\n"
        }
        val probe = "(function(){if(!window.__keepCheck){window.__keepCheck=0;setInterval(function(){window.__keepCheck++},200);}" +
            "return JSON.stringify({timer:window.__keepCheck,media:Array.from(document.querySelectorAll('video,audio')).map(function(m){return {t:m.currentTime,paused:m.paused}})});})()"
        fun read(): org.json.JSONObject? {
            val latch = java.util.concurrent.CountDownLatch(1)
            var raw: String? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (tab.destroyed) latch.countDown() else tab.view.evaluateJavascript(probe) { raw = it; latch.countDown() }
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return raw?.let { r -> runCatching { org.json.JSONObject(org.json.JSONTokener(r).nextValue() as String) }.getOrNull() }
        }
        val first = read()
        Thread.sleep(seconds * 1000)
        val second = read()
        note("kept page ${page.host}: before=$first after=$second")
        fun times(o: org.json.JSONObject?) = o?.optJSONArray("media")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optDouble("t") } }.orEmpty()
        val before = times(first)
        val after = times(second)
        val mediaAdvanced = after.indices.any { i -> before.getOrNull(i)?.let { after[i] > it } == true }
        val timerAdvanced = (second?.optInt("timer") ?: 0) > (first?.optInt("timer") ?: 0)
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(5_000) { keeper.frames().filterNotNull().first() } }?.let { bmp ->
            File(dir, "kept-frame.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val notified = mikaNotifications().contains(appString(R.string.kept_notif_title))
        // WebView 只能在主线程上问
        var keptUrl: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { keptUrl = if (tab.destroyed) null else tab.view.url }
        return "keptPage=${page.host}\nkeptUrl=$keptUrl\nkeptMedia=${before.size}\nkeptMediaAdvanced=$mediaAdvanced\n" +
            "keptTimerAdvanced=$timerAdvanced\nkeptPageAlive=${!tab.destroyed && keeper.page.value?.id == page.id}\nkeptNotification=$notified\n"
    }

    @Test
    fun runPageAsk() {
        val url = args.getString("liveUrl")
        val goal = args.getString("liveGoal")
        assumeTrue(url != null && goal != null)
        val run = args.getString("liveRun") ?: "page"
        val timeoutMs = (args.getString("liveTimeoutSec")?.toLong() ?: 600) * 1000
        val onTakeover = args.getString("liveOnTakeover") ?: "cancel"
        val touchAt = args.getString("liveTouchAtStep")?.toInt() ?: -1
        val resumeAfterMs = (args.getString("liveResumeAfterSec")?.toLong() ?: 5) * 1000
        testTasks.evalHooks.operatingPace = OperatingPace.parse(args.getString("livePace")) ?: OperatingPace.FAST
        val dir = File(context.getExternalFilesDir(null), "acceptance/$run").apply { deleteRecursively(); mkdirs() }
        val log = File(dir, "log.txt")
        fun note(s: String) = log.appendText("[${System.currentTimeMillis()}] $s\n")
        val instr = InstrumentationRegistry.getInstrumentation()
        val app = context.applicationContext as MikaApp
        val store = ProviderStore.get(context)
        val cfg = store.configFor(ModelRole.Main) ?: error("no main model configured")
        val recorder = Recorder(::note)
        testTasks.modelFactory = { ctx, s -> TaskEnvironment.defaultModelFactory(ctx, s)?.let { recorder.wrap(it) } }
        note("model=${cfg.model} url=$url goal=$goal")

        // 侧栏进浏览器,打开网址,等页面加载完
        compose.onNodeWithContentDescription(appString(R.string.chat_menu)).performClick()
        compose.awaitOne(appString(R.string.drawer_browser)).performClick()
        compose.waitForIdle()
        val browser = UserBrowser.of(context)
        compose.runOnUiThread { browser.open(url!!)?.let { error("cannot open $url: $it") } }
        fun loaded() = DeviceSupport.pollUntil(30_000) { browser.page.value?.takeIf { !it.loading && it.progress >= 100 } } != null
        note("page loaded=${loaded()}")
        if (args.getString("liveDesktop") == "true") {
            compose.runOnUiThread { browser.setDesktop(true) }
            Thread.sleep(1000)
            note("desktop page loaded=${loaded()}")
        }
        Thread.sleep(1500)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("page-ask-bar").fetchSemanticsNodes().isNotEmpty() }
        if (args.getString("liveNew") == "true" && compose.onAllNodesWithTag("page-ask-fresh").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("page-ask-fresh").performClick()
        }
        mainShot(dir, "step-00")
        val before = DeviceTaskState.runs.value.toSet()
        compose.onNodeWithTag("page-ask-field").performTextInput(goal!!)
        compose.onNodeWithTag("page-ask-send").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val task = DeviceSupport.pollUntil(15_000) {
            DeviceTaskState.runs.value.firstOrNull { it !in before && AttachedPage.parse(it.goal).words == goal.trim() }
        } ?: error("the page question did not start a task: runs=${DeviceTaskState.runs.value.map { it.goal.take(60) }}")
        val conversationId = task.conversationId
        note("task ${task.key} conversation=$conversationId")
        val t0 = System.currentTimeMillis()

        var lastStep = -1
        var last: TaskState? = null
        var borrowed = false
        var pauses = 0
        var userPauses = 0
        var takeovers = 0
        var asked = false
        var touched = false
        var pausedByTouch = false
        var resumed = false
        var end: TaskState? = null
        // 暂停按状态流里出现过的每一个算(轮询会漏掉短暂的暂停,也会漏掉触摸后到恢复前测试自己等着的那段)
        val seen = java.util.Collections.synchronizedList(mutableListOf<TaskState>())
        val watch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            var prev: TaskState? = null
            task.state.collect { st ->
                if (st is TaskState.Held && prev !is TaskState.Held) seen += st
                prev = st
            }
        }
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val s = task.state.value
            if (browser.loans.lentTo.value == conversationId) borrowed = true
            if (s != last) {
                note("state $s")
                if (s is TaskState.WaitingInput) asked = true
                last = s
            }
            if (s is TaskState.Running && s.step != lastStep) {
                lastStep = s.step
                compose.mainClock.advanceTimeBy(250)
                mainShot(dir, "step-%02d".format(s.step))
            }
            if (touchAt > 0 && !touched && s is TaskState.Running && s.step >= touchAt) {
                touched = true
                note("finger swipe on the page at step ${s.step}: ${swipeOnPage()}")
                pausedByTouch = DeviceSupport.pollUntil(3_000) { true.takeIf { PageAskModel.pausedForUser(task.state.value) } } != null
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                val banner = compose.onAllNodesWithTag("page-paused-banner").fetchSemanticsNodes().isNotEmpty()
                val card = compose.onAllNodesWithTag("page-card").fetchSemanticsNodes().isNotEmpty()
                note("paused by touch=$pausedByTouch banner=$banner card=$card state=${task.state.value}")
                mainShot(dir, "paused")
                Thread.sleep(resumeAfterMs)
                note("still paused after ${resumeAfterMs / 1000}s: ${PageAskModel.pausedForUser(task.state.value)}")
                if (banner) compose.onNodeWithTag("page-paused-resume").performClick() else TaskService.resume(context, task.key)
                resumed = DeviceSupport.pollUntil(5_000) { true.takeIf { !PageAskModel.pausedForUser(task.state.value) } } != null
                note("resumed=$resumed state=${task.state.value}")
                compose.waitForIdle()
                mainShot(dir, "resumed")
            }
            if (s is TaskState.WaitingTakeover && takeovers == 0) {
                takeovers++
                Thread.sleep(800)
                mainShot(dir, "takeover")
                note("takeover requested; action=$onTakeover")
                if (onTakeover == "handback") TaskService.handBack(context, runKey = task.key) else TaskService.cancel(context, task.key)
            }
            if (s is TaskState.Succeeded || s is TaskState.Failed || s == TaskState.Cancelled) {
                end = s
                break
            }
            compose.mainClock.advanceTimeBy(250)
            Thread.sleep(250)
        }
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        if (end == null) {
            note("timeout, cancelling")
            TaskService.cancel(context, task.key)
        }
        Thread.sleep(1500)
        watch.cancel()
        synchronized(seen) {
            pauses = seen.size
            userPauses = seen.count(PageAskModel::pausedForUser)
            note("pauses: ${seen.joinToString { (it as TaskState.Held).reason.why }}")
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        mainShot(dir, "end")
        val userTabUrl = browser.page.value?.url
        val origin = app.conversations.pageOrigin(conversationId)
        val urls = recorder.toolCalls.filter { it.first == "web_navigate" }.mapNotNull { Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(it.second)?.groupValues?.get(1) }
        val reopened = urls.any { PageConversations.pageKey(it) == PageConversations.pageKey(url!!) }
        // 回到对话页、打开侧栏:这条对话带「网页」小标签。先收起通知栏和悬浮通知(盖在上面截不到侧栏);
        // 界面测试的时钟要推着走,侧栏滑出来的动画才会走完
        fun menuShown() = compose.onAllNodesWithContentDescription(appString(R.string.chat_menu)).fetchSemanticsNodes().isNotEmpty()
        for (i in 0 until 8) {
            if (menuShown()) break
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
        }
        var listedAsWeb = false
        for (attempt in 1..3) {
            sh("cmd statusbar collapse")
            Thread.sleep(1500)
            runCatching { compose.onNodeWithContentDescription(appString(R.string.chat_menu)).performSemanticsAction(SemanticsActions.OnClick) }
                .onFailure { note("menu not clickable: $it") }
            repeat(8) {
                compose.mainClock.advanceTimeBy(250)
                Thread.sleep(150)
            }
            compose.waitForIdle()
            listedAsWeb = DeviceSupport.pollUntil(5_000) {
                compose.mainClock.advanceTimeBy(250)
                true.takeIf { compose.onAllNodesWithTag("conversation-web", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            } != null
            val drawerOpen = compose.onAllNodesWithTag("drawer").fetchSemanticsNodes().isNotEmpty()
            note("drawer attempt $attempt: open=$drawerOpen webTag=$listedAsWeb")
            if (drawerOpen) break
        }
        mainShot(dir, "drawer")
        val outcome = when (end) {
            is TaskState.Succeeded -> "succeeded"
            is TaskState.Failed -> "failed"
            TaskState.Cancelled -> if (takeovers > 0) "takeover(cancelled)" else "cancelled"
            else -> "timeout"
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> ""
        }
        File(dir, "result.txt").writeText(
            "model=${cfg.model}\nurl=$url\ngoal=$goal\noutcome=$outcome\nsteps=${recorder.calls}\nmaxStep=$lastStep\n" +
                "takeovers=$takeovers\nasked=$asked\npauses=$pauses\nuserPauses=$userPauses\nwallSec=$wall\n" +
                "inputTokens=${recorder.inTokens}\noutputTokens=${recorder.outTokens}\n" +
                "borrowed=$borrowed\nfirstToolCalls=${recorder.toolCalls.take(5).joinToString(" ; ") { "${it.first} ${it.second.take(120)}" }}\n" +
                "urlsOpened=${urls.joinToString(" ")}\nreopenedSameUrl=$reopened\nuserTabUrlAtEnd=$userTabUrl\n" +
                "touchAtStep=$touchAt\npausedByTouch=$pausedByTouch\nresumed=$resumed\n" +
                "conversationId=$conversationId\npageOrigin=${origin?.let { "${it.host} ${it.url} ${it.title}" }}\nlistedAsWeb=$listedAsWeb\n" +
                "answer=$answer\n",
        )
        // Mika 自己的点按、滚动不该让任务以为用户在操作
        if (touchAt <= 0) check(userPauses == 0) { "the run paused as if the user touched the page ($userPauses times) though nobody did" }
        else check(pausedByTouch && resumed) { "touch at step $touchAt: paused=$pausedByTouch resumed=$resumed" }
    }

    /** 在浏览器页的网页上经窗口用手指往上划一小段(网页上半截,面板盖不到;划动不会点到链接);返回划在哪。 */
    private fun swipeOnPage(): String {
        val instr = InstrumentationRegistry.getInstrumentation()
        var at: IntArray? = null
        var size = 0 to 0
        instr.runOnMainSync {
            fun find(v: android.view.View): android.webkit.WebView? = when (v) {
                is android.webkit.WebView -> v.takeIf { it.isShown }
                is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
                else -> null
            }
            find(compose.activity.window.decorView)?.let { w ->
                at = IntArray(2).also(w::getLocationOnScreen)
                size = w.width to w.height
            }
        }
        val o = at ?: return "no page view"
        val x = o[0] + size.first / 2f
        val y0 = o[1] + size.second * 0.3f
        val down = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, y: Float, t: Long) {
            val e = android.view.MotionEvent.obtain(down, t, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            instr.sendPointerSync(e)
            e.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, y0, down)
        for (i in 1..5) send(android.view.MotionEvent.ACTION_MOVE, y0 - i * 12f, down + i * 16L)
        send(android.view.MotionEvent.ACTION_UP, y0 - 60f, down + 100)
        return "x=$x y=$y0"
    }

    /** 此刻学到的全部:个人技能(在用与草稿)、App 知识的各份补充、记住的路线条数。 */
    private fun learned(memory: MemoryRuntime): String = buildString {
        val skills = memory.skills()
        skills.list().forEach { appendLine("skill ${it.name} status=${it.status} outcomes=${it.outcomes}\n${it.markdown}\n") }
        skills.drafts().forEach { (id, s) -> appendLine("skill-draft $id ${s.name}\n${s.markdown}\n") }
        memory.appKnowledge().all().forEach { appendLine("app ${it.appName} stage=${it.stage} lesson=${it.lesson} revises=${it.revises} trials=${it.trialOutcomes}\n${it.markdown}\n") }
        appendLine("routes=${(context.applicationContext as MikaApp).routes.count()}")
    }

    /** Mika 当前通知的标题、正文和按钮(从 dumpsys notification 里摘)。 */
    private fun mikaNotifications(): String = sh("dumpsys notification --noredact")
        .split("NotificationRecord(").drop(1).filter { "pkg=com.wangzan101.mika " in it }
        .joinToString(" || ") { rec ->
            rec.lines().map { it.trim() }
                .filter { it.startsWith("android.title=") || it.startsWith("android.text=") || it.startsWith("[") && "\"" in it }
                .joinToString(" | ")
        }.take(1500)

    private fun frame(dir: File, name: String) {
        val bmp = DeviceTaskState.frames.value ?: return
        runCatching { File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun mainShot(dir: File, name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 80, it) }
    }

    /** 只记录,不改动:真实模型的每次回复和上一步的工具结果写进日志。 */
    private class Recorder(private val note: (String) -> Unit) {
        @Volatile var calls = 0
        @Volatile var inTokens = 0
        @Volatile var outTokens = 0
        /** 模型调过的工具(名字, 参数),按先后。 */
        val toolCalls: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(mutableListOf())

        fun wrap(inner: ChatModel) = object : ChatModel {
            override val capabilities = inner.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                messages.lastOrNull()?.parts?.filterIsInstance<Part.ToolResult>()?.forEach {
                    note("  result${if (it.isError) " ERROR" else ""}: ${it.content.take(400).replace('\n', ' ')}")
                }
                messages.lastOrNull()?.parts?.filterIsInstance<Part.Text>()?.forEach {
                    note("  user/tool text: ${it.text.take(300).replace('\n', ' ')}")
                }
                val t = System.currentTimeMillis()
                val reply = try {
                    inner.complete(system, messages, tools)
                } catch (e: Exception) {
                    note("model error: $e")
                    throw e
                }
                calls++
                reply.usage?.let { inTokens += it.inputTokens; outTokens += it.outputTokens }
                note("call#$calls ${System.currentTimeMillis() - t}ms stop=${reply.stopReason}")
                reply.parts.forEach { p ->
                    when (p) {
                        is Part.Text -> note("  say: ${p.text.take(500).replace('\n', ' ')}")
                        is Part.ToolCall -> {
                            toolCalls += p.name to p.argsJson
                            note("  tool: ${p.name} ${p.argsJson.take(300)}")
                        }
                        is Part.Thinking -> note("  think: ${p.text.take(200).replace('\n', ' ')}")
                        else -> Unit
                    }
                }
                return reply
            }
        }
    }
}
