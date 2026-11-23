package com.wangzan101.mika.task

import android.os.Build
import android.util.Log
import android.view.Display
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.web.LazyWebPort
import com.wangzan101.mika.cap.web.WebHandover
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.ModelRouter
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.core.learn.knowledge.ChannelFacts
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.learn.replay.RouteMatcher
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.mainFallbackChain
import com.wangzan101.mika.settings.mainProviderId
import com.wangzan101.mika.takeover.HandoverActivity
import com.wangzan101.mika.takeover.HandoverActivityCounter
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.takeover.TakeoverPort
import com.wangzan101.mika.takeover.stoppedAt
import com.wangzan101.mika.task.TaskRun.Companion.TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 运行装配:Agent 循环与接管
internal suspend fun TaskRun.assembleAgent(a: RunAssembly, m: RunModels, ph: RunPhone, taskGate: TaskGate, mem: RunMemory, rec: RunRecord, pr: RunPrompt, web: LazyWebPort?, t: RunTools, priorTurns: List<Turn>): AgentLoop {
    var runStore by a::runStore
    var dispatcher by a::dispatcher
    var mcpEvents by a::mcpEvents
    var liveFeed by a::liveFeed
    val model = m.model
    val light = m.light
    val decision = m.decision
    val routedLight = m.routedLight
    val lightFallbacks = m.lightFallbacks
    val knowledge = m.knowledge
    val phoneScreen = ph.phoneScreen
    val apps = mem.apps
    val speed = mem.speed
    val switches = mem.switches
    val memory = mem.memory
    val profileSnapshot = mem.profileSnapshot
    val utterances = mem.utterances
    val catalog = mem.catalog
    val sink = rec.sink
    val trace = rec.trace
    val userScreens = pr.userScreens
    val basePrompt = pr.basePrompt
    val personaLabel = pr.personaLabel
    val runSpec = pr.runSpec
    val runApproval = t.runApproval
    val promptHook = t.promptHook
    val tools = t.tools
    // 主动能力从通知里攒下、够格带进任务的那一段:只给用户在手机上发起的任务(不给定时、渠道、电脑交来的,也不给条件检查和子任务);
    // 主动能力没开时不去碰它(连对象都不建)
    val observed = if (provenance.origin == RunOrigin.Ui && !conditionCheck && FeatureFlags.get(service).proactive()) {
        try {
            (service.application as MikaApp).proactive.observedForTasks()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not read what was gathered from notifications: ${e.javaClass.simpleName}")
            null
        }
    } else null

    // 主模型备援链(项目 3):评测换掉模型来源时不接设置里的备援,免得测到真服务商
    val chainStore = ProviderStore.get(service)
    val (chainModels, fallbackProviderIds) = if (env.modelFactory === TaskEnvironment.defaultModelFactory)
        chainStore.mainFallbackChain(session) else (emptyList<ChatModel>() to emptyList<String?>())
    val chainProviderIds = listOf(chainStore.mainProviderId()) + fallbackProviderIds
    val agent = AgentLoop(
        model, tools,
        basePrompt,
        promptTransform = { promptHook.transform(it) },
        // 助手断开、被挪走只在这一步用的是手机时才查
        gate = { w ->
            if (phoneScreen.acquired && dispatcher?.surface != WEB) phone?.screen?.let { phoneRuntime.awaitUserPick(it, taskGate, w) }
            // 等关卡期间发生过锁屏周期:按旧屏幕准备好的动作作废,执行入口换成说明结果喂回模型
            if (taskGate.pass({ phoneScreen.acquired && dispatcher?.surface != WEB }, w))
                throw com.wangzan101.mika.core.safety.gate.ScreenRecoveryRequired()
        }, knowledge = knowledge, screenMaps = memoryRuntime.screenMaps(), sink = sink, trace = trace, installedApps = { apps },
        // 选渠道用的事实:装了哪些 App、在 Mika 里登录过哪些网站、有没有内置浏览器
        channelFacts = {
            val registry = WebSites.registry(service)
            ChannelFacts(
                installed = apps.map { it.first }.toSet(),
                loggedInSites = withContext(Dispatchers.IO) { registry.list().filter { it.loggedInByUser }.map { it.host }.toSet() },
                webAvailable = web != null,
                challengedSites = withContext(Dispatchers.IO) { registry.challengedSites() },
            )
        },
        maxSteps = env.evalHooks.maxSteps ?: runSpec.stepBudget ?: AgentLoop.UNLIMITED_STEPS,
        reviewFinalAnswer = false,
        inputTimeoutMs = TaskService.INPUT_TIMEOUT_MS,
        // 询问 / 自主:询问时只有花钱的一步先问(有确认关卡和 request_confirmation);自主时连付款也不问。
        // 开工时询问、中途换成自主的,下一步起不再问;开工时自主的,换成询问留给下一个任务
        confirmations = runApproval.gate(),
        approvals = runApproval,
        autonomous = runApproval.startedAutonomous,
        observed = observed,
        deskNote = brief,
        confirmTimeoutMs = TaskService.CONFIRM_TIMEOUT_MS,
        selfPackage = service.packageName,
        flags = speed,
        // 只在 lightSteps 打开、轻量模型能调工具且与主模型不是同一份配置时生效
        modelFallbacks = chainModels, modelChainProviderIds = chainProviderIds,
        router = routedLight?.let { ModelRouter(model, it, fallbacks = lightFallbacks) },
        intentJudge = if (provenance.origin == RunOrigin.Ui) light ?: model else null,
        intentDecision = if (provenance.origin == RunOrigin.Ui) decision else null,
        // 选路线是辅助判断:用轻量模型,没配用主模型
        routes = if (speed.replay) RouteMatcher(light ?: model, routeStore, knowledge, decision) { detail ->
            sink.event(RunEvent.State(System.currentTimeMillis(), "jev_route", detail))
        } else null,
        skills = catalog,
        memory = memory,
        interaction = service.interaction(conversationId, channelPeer),
        profileSnapshot = profileSnapshot,
        streamReplies = true,
        stages = chipStages.takeIf { Build.VERSION.SDK_INT >= 36 && provenance.origin == RunOrigin.Ui },
        userScreens = userScreens,
        // 同一网站、App 里连着联系别人、提交:两次之间隔一段随机的时间
        outwardPacing = OutwardPacing.human(),
        personaLabel = personaLabel,
    )
    loop = agent
    liveFeed = agent.events.subscribe(liveEvents)
    mcpExternalId?.let { mcpEvents = (service.application as MikaApp).remoteTasks.attach(it, agent.events) }
    kit = LearnKit(switches, memory, utterances, tools, agent, catalog, knowledge, model, light, runStore, agent.flags.replay, priorTurns)
    return agent
}

internal suspend fun TaskRun.assembleTakeover(a: RunAssembly, agent: AgentLoop, t: RunTools) {
    var webStack by a::webStack
    val side = a.side
    val tools = t.tools
    suspend fun <T> io(fallback: T, call: () -> T): T =
        withContext(Dispatchers.IO) { runCatching(call).getOrDefault(fallback) }
    // 接管期间才订阅主屏的无障碍事件:平时没人订阅,服务不复制事件
    var counter: HandoverActivityCounter? = null
    var counting: Job? = null
    val handover = Takeover(object : TakeoverPort {
        override suspend fun agentTopTask() = phone?.let { p -> p.display.current?.let { d -> io(-1) { p.shell.topTaskId(d.displayId) } } } ?: -1
        override suspend fun targetTask() = phone?.screen?.target?.value?.taskId ?: -1
        override fun targetPackage() = phone?.screen?.target?.value?.packageName
        override suspend fun packageOf(taskId: Int) = phone?.let { p -> io(null) { p.shell.packageOfTask(taskId) } }
        override suspend fun displayOf(taskId: Int) = phone?.let { p -> io(-1) { p.shell.displayOfTask(taskId) } } ?: -1
        override suspend fun moveToMain(taskId: Int) = moveTask(taskId, Display.DEFAULT_DISPLAY)
        override suspend fun moveBack(taskId: Int) = moveBackToAgent(taskId)
        override fun setSound(packageName: String, on: Boolean) {
            val p = phone ?: return
            // 交出去的若是目标 App,按目标所属的那份算;别的按本来那份
            val user = p.screen.target.value?.takeIf { it.packageName == packageName }?.let { phoneRuntime.copyUser(it.userId) }
            if (on) {
                if (!p.audio.release(packageName, user)) Log.w(TAG, "could not restore sound of $packageName for takeover")
            } else {
                p.audio.opened(packageName, user)
            }
        }
        override fun unmute(packageName: String) {
            val p = phone ?: return
            val user = p.screen.target.value?.takeIf { it.packageName == packageName }?.let { phoneRuntime.copyUser(it.userId) }
            if (!p.audio.unmute(packageName, user)) Log.w(TAG, "could not restore sound of $packageName from pip takeover")
        }
        override fun muteAgain(packageName: String) {
            val p = phone ?: return
            val user = p.screen.target.value?.takeIf { it.packageName == packageName }?.let { phoneRuntime.copyUser(it.userId) }
            if (!p.audio.muteAgain(packageName, user)) Log.w(TAG, "could not mute again $packageName from pip takeover")
        }
        override fun watchInput(packageName: String) {
            counting?.cancel()
            val c = HandoverActivityCounter(packageName).also { counter = it }
            // 立即订阅:接着发生的第一下操作不能漏
            val a11y = phone?.a11y ?: return
            counting = side.launch(start = CoroutineStart.UNDISPATCHED) {
                a11y.eventsFor(Display.DEFAULT_DISPLAY).collect { e ->
                    c.onEvent(Display.DEFAULT_DISPLAY, e.packageName, e.eventType, e.className, e.eventTime, e.windowId to e.source?.hashCode())
                }
            }
        }
        override suspend fun handoverActivity(): HandoverActivity? {
            val c = counter ?: return null
            val pm = service.packageManager
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() }.getOrNull()
            return HandoverActivity(c.inputCount, stoppedAt(phone?.a11y?.let { phoneRuntime.mainWindowTitle(it, c.packageName) }, label, c.lastActivity))
        }
        override fun stopWatching() {
            counting?.cancel()
            counting = null
            counter = null
        }
        override suspend fun resume(result: HandBack) { inbox.send(RunInput.HandBack(result)) }
        override fun cancel() { inbox.cancel() }

        // 网页界面:把当前标签(同一个页面)拿到主屏的接管界面;手机界面返回 null,走挪任务
        override suspend fun showWeb(reason: String, key: Int): Boolean? {
            // 没打开过网页就不会在网页界面上
            val w = webStack ?: return null
            if (tools.surface != WEB) return null
            val message = takeoverMessage(runState._state.value)
            return withContext(Dispatchers.Main) {
                w.showForTakeover(reason, message, key, WebHandover.taskActions(service, key, this@assembleTakeover.key)) { takeover?.waitKey }
            }
        }

        override fun webBusy() = WebHandover.loginOpen

        override suspend fun returnWeb(loggedIn: Boolean): HandoverActivity? =
            webStack?.let { w -> withContext(Dispatchers.Main) { w.returnFromTakeover(loggedIn) } }
    }, side, queue = service.takeoverQueue, remind = { service.refreshNotifications(this@assembleTakeover) })
    takeover = handover
    handoverRef = handover

    side.launch {
        agent.state.collect {
            if (it == TaskState.Idle) return@collect
            if (it is TaskState.Running && it.preview != null && (gate?.movedAwayPending == true || gate?.userOnPagePending == true || manualControl.state.value != com.wangzan101.mika.core.run.ManualControl.State.Automatic)) return@collect
            val previous = runState._state.value as? TaskState.Running
            if (it is TaskState.Running && previous != null && previous.preview != it.preview &&
                previous.copy(preview = it.preview) == it) {
                // 文字增量只刷新聊天；通知、接管和设备唤醒不需要每个 token 都重跑。
                synchronized(runState.publishLock) { if (!runState.ended) runState.setState(it) }
                return@collect
            }
            handover.onState(it)
            publish(it)
            env.evalHooks.answerFor(it)?.let { answer -> agent.answerInput(answer) }
            env.evalHooks.decisionFor(it)?.let { approved -> agent.answerConfirmation(approved) }
        }
    }
    // 别的任务的接管进出队时,本任务的接管通知要跟着换(排到最前、或后面多了一个)
    if (parallel) side.launch { service.takeoverQueue.changes.collect { service.refreshNotifications(this@assembleTakeover) } }
    side.launch {
        combine(handover.stage, handover.problem) { stage, _ -> stage }.collect {
            runState._takeoverStage.value = it
            service.refreshNotifications(this@assembleTakeover)
        }
    }
}
