package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.personal.PersonalTask
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.core.safety.verify.Verifier
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.shell.IMikaShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * 真机测试:用脚本模型驱动 TaskService,在虚拟屏上打开系统设置,检验常亮、静音、被挪走暂停与锁屏等待。
 * 需要内置助手在运行、Mika 无障碍服务已开启。
 */
@RunWith(AndroidJUnit4::class)
class TaskServiceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    /**
     * 每个任务一个脚本模型:模型的调用编号、核查次数各任务分开数。共用一个时,上一个任务没取走的调用
     * (核查、收尾)会被下一个任务的等待取到,测试就跑到了任务前面。
     * 一个任务里向工厂要模型的不止任务本身:新对话起标题、停止后写交代也各要一次,而且和任务并发,
     * 谁先要到说不准。所以工厂不按「第几次被要」发模型,一律给最近开始的那个任务的模型(见 [model])。
     */
    private val models = List(MAX_TASKS) { ScriptedModel() }
    /** 工厂在任务服务的线程上读它。 */
    @Volatile private var started = 0
    /** 最近一次 [start] 开始的那个任务的模型(还没开始任务时是第一个任务要用的)。 */
    private val model: ScriptedModel get() = models[maxOf(0, started - 1)]

    private fun start(goal: String) {
        started++
        TaskService.start(context, goal)
    }
    private var settingsTask = -1
    private lateinit var priorAudio: String

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        priorAudio = shell.getAudioMode(SETTINGS)!!
        // 这里的任务都从界面发起,提议第一个工具调用时会另问一次意图;一律答「要操作」,不占脚本
        models.forEach { it.intent = "ACT" }
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        // 测试中途失败时设置可能还留在主屏:只结束这个测试自己挪过去的任务
        if (settingsTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == settingsTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    /** 开任务并让它在虚拟屏上打开设置,返回虚拟屏编号;此时模型正等着第二步。 */
    private fun startWithSettingsOpen(): Int {
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        settingsTask = shell.topTaskId(displayId)
        assertTrue(settingsTask >= 0)
        return displayId
    }

    /** 交出一个不带可核对值的回答,等任务结束:字面核对没有要对的,不另请核查,结局是办成。[appOpened] 只为调用处读起来清楚。 */
    private fun finishAndAwait(@Suppress("UNUSED_PARAMETER") appOpened: Boolean = true) {
        model.answer("done")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == "done" && !end.doubtful)
        // 状态先一步变成结束,恢复声音等收尾做完后 busy 才变回 false
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
    }

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    private fun mikaScreenLock(): String? =
        sh("dumpsys power").lines().map { it.trim() }.firstOrNull { "'Mika:task'" in it }

    /**
     * 产品路径上回答不另请核查(任务以 reviewFinalAnswer = false 运行):模型给的回答原样交出,结局是办成、未经核查;
     * 主模型和轻量模型都收不到核查请求。核查本身的规则由核心的单元测试管。
     */
    @Test
    fun answerHandedOverWithoutReview() {
        val lightChecks = AtomicInteger()
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    // 配了轻量模型时,判意图、起标题先问它;别的只可能是核查,数下来
                    if (system == INTENT) return ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null)
                    if (system == TITLE) return ModelReply(listOf(Part.Text("测试对话")), StopReason.EndTurn, null)
                    if (system == Verifier.PROMPT) lightChecks.incrementAndGet()
                    return ModelReply(listOf(Part.Text(ScriptedModel.CANNOT_VERIFY)), StopReason.EndTurn, null)
                }
            }
        }
        model.verdict = ScriptedModel.CANNOT_VERIFY
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        assertEquals(2, model.awaitCall().number)
        model.answer("电量是 9731 毫安时")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == "电量是 9731 毫安时" && !end.reviewed && end.unconfirmed.isEmpty())
        assertEquals(0, lightChecks.get())
        assertEquals(0, model.verifierRequests.get())
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
    }

    /** 不打开 App 的任务不建虚拟屏:读屏是空屏并提示先 phone_launch_app,写操作失败 no_app_open,没有画面。 */
    @Test
    fun noDisplayUntilLaunchApp() {
        start("你好")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        val second = model.awaitCall()
        assertTrue(second.messages.last().toString(), "Start with phone_launch_app" in second.messages.last().toString())
        model.feed("phone_tap", """{"x":500,"y":500}""")
        val third = model.awaitCall()
        assertTrue(third.messages.last().toString(), "no_app_open" in third.messages.last().toString())
        assertEquals(-1, testTasks.agentDisplayId.value)
        assertEquals(null, DeviceTaskState.frames.value)
        assertEquals(null, DeviceTaskState.runs.value.single().phoneFrames.value)
        finishAndAwait(appOpened = false)
        assertEquals(-1, testTasks.agentDisplayId.value)

        // 第一次 phone_launch_app 才建屏,而且只建一块。第二个任务有它自己的脚本模型,调用从 1 数起
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        assertEquals(-1, testTasks.agentDisplayId.value)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        val first = testTasks.agentDisplayId.value
        assertTrue(first > 0)
        settingsTask = shell.topTaskId(first)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(3, model.awaitCall().number)
        assertEquals(first, testTasks.agentDisplayId.value)
        assertNotNull(pollUntil(5_000) { DeviceTaskState.frames.value })
        val run = DeviceTaskState.runs.value.single()
        assertNotNull(pollUntil(5_000) { run.phoneFrames.value })
        finishAndAwait()
        assertEquals(-1, testTasks.agentDisplayId.value)
        assertEquals(null, run.phoneFrames.value)
    }

    @Test
    fun personalReplayKeepsAFrameFromTheTaskDisplay() {
        val personal = (context.applicationContext as MikaApp).personal
        val id = UUID.randomUUID().toString()
        val task = PersonalTask(id, id, "打开设置", "打开设置", "test", System.currentTimeMillis())
        personal.store.update { it.copy(tasks = it.tasks + task) }
        try {
            started++
            TaskService.start(context, task.goal, task.conversationId)
            assertEquals(1, model.awaitCall().number)
            model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
            assertEquals(2, model.awaitCall().number)
            val replay = pollUntil(8_000) { runBlocking { personal.replayFrames(id).firstOrNull() } }
            assertNotNull(replay)
            requireNotNull(BitmapFactory.decodeFile(replay!!.file.path)).recycle()
            finishAndAwait()
            assertTrue(replay.file.isFile)
        } finally {
            personal.store.update {
                it.copy(tasks = it.tasks.filterNot { t -> t.id == id }, messages = it.messages.filterNot { m -> m.taskId == id })
            }
        }
    }

    @Test
    fun openingSameAppPauses() {
        val displayId = startWithSettingsOpen()
        // 静音在旁路协程里跟着打开的 App 做,稍等
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } } ?: shell.getAudioMode(SETTINGS))

        // 模拟用户在主屏打开了同一个 App:任务被挪到主屏
        assertTrue(shell.moveTask(settingsTask, Display.DEFAULT_DISPLAY))
        // 模型还在「推理」(没走到 gate)时就应立即显示暂停
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), awaitState { it is TaskState.Held })
        model.feed("phone_back")
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), awaitState { it is TaskState.Held })
        // 暂停期间不自动挪回
        Thread.sleep(1_500)
        assertEquals(settingsTask, shell.topTaskId(Display.DEFAULT_DISPLAY))

        TaskService.resume(context)
        assertEquals(3, model.awaitCall().number)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(displayId).takeIf { it == settingsTask } })
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)

        finishAndAwait()
        assertEquals(priorAudio, shell.getAudioMode(SETTINGS))
    }

    /**
     * 只看 dumpsys power,不改任何全局设置:临时关掉「插 USB 常亮」去等真实熄屏超时,
     * 在上次用户操作已久时会让主屏立刻熄灭并上锁(实测踩过),无人值守时不可接受。
     * 锁被系统禁用(例如后台限制)时 dumpsys 会标 DISABLED,这里一并排除。
     */
    @Test
    fun keepsScreenOnDuringTask() {
        startWithSettingsOpen()
        val line = mikaScreenLock()
        assertNotNull("Mika holds no screen wake lock while running", line)
        // 有的系统在类型前加锁对象编号(mLock:<id>),只认类型字样
        // 只保持亮屏、允许系统调暗,不锁在全亮
        assertTrue(line!!, "SCREEN_DIM_WAKE_LOCK" in line)
        assertFalse(line, "DISABLED" in line)
        val summary = sh("dumpsys power").lines().map { it.trim() }.first { it.startsWith("mWakeLockSummary=") }
        // 摘要是位掩码(0x..),AOSP 的 WAKE_LOCK_SCREEN_DIM = 1 shl 2;部分版本打印成文字
        val bits = Regex("0x([0-9a-fA-F]+)").find(summary)?.groupValues?.get(1)?.toInt(16)
        assertTrue(summary, if (bits != null) bits and (1 shl 2) != 0 else "SCREEN_DIM" in summary)

        finishAndAwait()
        val released = pollUntil(3_000) { if (mikaScreenLock() == null) true else null }
        assertTrue("screen wake lock still held after the task: ${mikaScreenLock()}", released == true)
    }

    /** 第一步之前的准备(开模型、读知识、选路线)也算在跑:模型还没开出来时屏幕锁就已拿上。 */
    @Test
    fun keepsScreenOnWhilePreparing() {
        val opening = java.util.concurrent.CountDownLatch(1)
        testTasks.modelFactory = { _, _ -> opening.await(); model }
        start("测试任务")
        val held = pollUntil(5_000) { mikaScreenLock() }
        opening.countDown()
        assertNotNull("Mika holds no screen wake lock while preparing", held)
        assertEquals(1, model.awaitCall().number)
        finishAndAwait()
    }

    /**
     * 停止:界面当场是已停止,随后补上一段交代当作这一轮的回答(结局照旧是取消)。交代请轻量模型写,不给工具;
     * 轻量模型出错时换主模型,主模型也写不出就按记录直接拼。
     */
    @Test
    fun stopLeavesASummaryAsTheAnswer() {
        val asked = AtomicInteger()
        val gate = java.util.concurrent.CountDownLatch(1)
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    // 开局判意图的那一问答「要操作」,别的轻量调用只有写交代这一次
                    if (system == INTENT) return ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null)
                    if (system != SUMMARY) throw IllegalStateException("not for this test")
                    assertTrue(tools.isEmpty())
                    asked.incrementAndGet()
                    // 交代先别写完:看得到「正在整理」
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                    return ModelReply(listOf(Part.Text("停止前打开了设置,还没看任何页面。")), StopReason.EndTurn, null)
                }
            }
        }
        startWithSettingsOpen()
        val conversation = DeviceTaskState.conversationId.value!!
        TaskService.cancel(context)
        assertEquals(TaskState.Cancelled, awaitState { !it.running() })
        val runId = pollUntil(5_000) { DeviceTaskState.endedRunIds.value[conversation] }!!
        assertTrue(runId in pollUntil(5_000) { DeviceTaskState.summarizing.value.takeIf { runId in it } }.orEmpty())
        val store = com.wangzan101.mika.chat.ConversationStore.shared(context)
        // 先记下「已取消」,停止不等交代
        val placeholder = pollUntil(5_000) { store.turns(conversation).lastOrNull { it.runId == runId } }!!
        assertEquals("cancelled" to "已取消", placeholder.status to placeholder.outcome)
        gate.countDown()
        val turn = pollUntil(15_000) { store.turns(conversation).lastOrNull { it.runId == runId && it.outcome != "已取消" } }!!
        assertEquals("停止前打开了设置,还没看任何页面。", turn.outcome)
        assertEquals("cancelled", turn.status)
        assertEquals(1, asked.get())
        assertNotNull(pollUntil(5_000) { DeviceTaskState.summarizing.value.takeIf { runId !in it } })
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }

        // 轻量模型出错、主模型交白卷:按记录直接拼
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                    if (system == INTENT) ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null) else throw IllegalStateException("down")
            }
        }
        startWithSettingsOpen()
        val second = DeviceTaskState.conversationId.value!!
        val secondRun = pollUntil(5_000) { DeviceTaskState.run(second)?.runId?.value }!!
        TaskService.cancel(context)
        assertEquals(TaskState.Cancelled, awaitState { !it.running() })
        val plain = pollUntil(20_000) { store.turns(second).lastOrNull { it.runId == secondRun && it.outcome != "已取消" } }!!
        assertTrue(plain.outcome, plain.outcome.startsWith("已经停下,停止前做了这些:"))
        assertEquals(1, model.summaryRequests.get())
    }

    // 绝不能在无人值守时启用:它会发 KEYCODE_SLEEP 让手机熄屏上锁,只有人能输入密码解锁。
    // 只在用户本人在场、准备好解锁时手动去掉 @Ignore 运行。
    @Ignore("needs user to unlock: pressing power locks the phone and only a person can unlock it")
    @Test
    fun lockPausesAndUnlockResumes() {
        startWithSettingsOpen()
        sh("input keyevent KEYCODE_SLEEP")
        model.feed("phone_back")
        assertEquals(TaskState.Held(TaskGate.LOCKED), awaitState { it == TaskState.Held(TaskGate.LOCKED) })
        // 由人解锁;解锁后应自动继续,不需要点继续
        val resumed = pollUntil(120_000) { DeviceTaskState.state.value.takeIf { it is TaskState.Running } }
        assertNotNull(resumed)
        assertEquals(3, model.awaitCall().number)
        finishAndAwait()
    }

    @Test
    fun scheduledTaskFailsAndNotifiesWhenUnlockIsNotAllowed() {
        val access = (context.applicationContext as MikaApp).deviceAccess
        val wasEnabled = access.settings.config.value.enabled
        val power = context.getSystemService(PowerManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val before = notifications.activeNotifications.map { it.key }.toSet()
        val conversation = "test-scheduled-unlock-${System.nanoTime()}"
        access.settings.setEnabled(false)
        try {
            TaskService.startTrigger(context, "测试定时任务", conversation, conversation, null)
            assertEquals(1, model.awaitCall().number)
            sh("input keyevent KEYCODE_SLEEP")
            assertNotNull("screen did not turn off", pollUntil(5_000) { true.takeIf { !power.isInteractive } })

            model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
            val end = awaitState { it is TaskState.Failed } as TaskState.Failed
            val expected = context.getString(com.wangzan101.mika.R.string.trigger_unlock_failed) + " [disabled]"
            assertEquals(expected, end.reason)
            assertEquals(FailKind.Environment, end.kind)

            val posted = pollUntil(5_000) {
                notifications.activeNotifications.firstOrNull {
                    it.key !in before && it.notification.channelId == TaskService.CHANNEL_RESULT &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT) == expected
                }
            }
            assertNotNull("missing scheduled-task failure notification", posted)
        } finally {
            access.settings.setEnabled(wasEnabled)
            sh("input keyevent KEYCODE_WAKEUP")
        }
    }

    @Test
    fun scheduledPartialOutcomeKeepsOneShotPlan() {
        val id = "test-partial-trigger-${System.nanoTime()}"
        val plan = TriggerDefinition(id, "thread-$id", "执行部分结果测试", System.currentTimeMillis() + 86_400_000)
        val judge = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                ModelReply(listOf(Part.Text("""{"outcome":"partial","reason":"只完成了一部分"}""")), StopReason.EndTurn, null)
        }
        started++
        testTasks.lightModelFactory = { _, _ -> judge }
        val flags = FeatureFlags.get(context)
        val oldQuit = flags.quitRequested()
        flags.setQuitRequested(false)
        try {
            TriggerRuntime.schedule(context, plan)
            TriggerRuntime.runNow(context, id)
            assertEquals(1, model.awaitCall().number)
            model.answer("已完成一部分")

            val key = pollUntil(5_000) {
                TriggerRuntime.snapshot(context).windows.firstOrNull { it.triggerId == id }?.key
            } ?: error("trigger window was not created")
            val failed = pollUntil(20_000) {
                TriggerRuntime.snapshot(context).windows.firstOrNull { it.key == key }?.state
                    ?.takeIf { it == TriggerWindowState.Failed }
            }
            assertEquals(TriggerWindowState.Failed, failed)
            assertNotNull(TriggerRuntime.list(context).singleOrNull { it.id == id })
        } finally {
            TriggerRuntime.remove(context, id)
            testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
            flags.setQuitRequested(oldQuit)
        }
    }

    @Test
    fun uiTaskStillWaitsForManualUnlock() {
        val power = context.getSystemService(PowerManager::class.java)
        try {
            start("测试普通任务")
            assertEquals(1, model.awaitCall().number)
            sh("input keyevent KEYCODE_SLEEP")
            assertNotNull("screen did not turn off", pollUntil(5_000) { true.takeIf { !power.isInteractive } })

            model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
            assertEquals(TaskState.Held(TaskGate.LOCKED), awaitState { it == TaskState.Held(TaskGate.LOCKED) })
            Thread.sleep(1_500)
            assertEquals("ordinary task attempted unattended unlock", TaskState.Held(TaskGate.LOCKED), DeviceTaskState.state.value)
        } finally {
            sh("input keyevent KEYCODE_WAKEUP")
        }
    }

    private companion object {
        const val MAX_TASKS = 4
        val INTENT by lazy { com.wangzan101.mika.core.prompt.Prompts.role("intent_gate") }
        val SUMMARY by lazy { com.wangzan101.mika.core.prompt.Prompts.role("stop_summary") }
        val TITLE by lazy { com.wangzan101.mika.core.prompt.Prompts.role("conversation_title") }
    }
}
