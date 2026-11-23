package com.wangzan101.mika.task

import com.wangzan101.mika.cap.sandbox.SandboxDirs
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.prompt.PersonaPrompt
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.coroutines.CancellationException
import androidx.webkit.WebViewCompat
import com.wangzan101.mika.cap.web.LazyWebPort
import com.wangzan101.mika.cap.web.WebStack
import com.wangzan101.mika.cap.web.KeptOrigin
import com.wangzan101.mika.cap.web.WebTab
import com.wangzan101.mika.browser.PageLoans
import com.wangzan101.mika.browser.UserBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.task.TaskRun.Companion.WEB_FRAME_MS
import com.wangzan101.mika.task.TaskRun.Companion.TAG

// 运行装配:模型、运行记录、记忆与知识、网页、提示词
internal suspend fun TaskRun.assembleModels(): RunModels? {
    val model = env.modelFactory(service, session) ?: return null
    // 用户在浏览器页上对着一页问的:那一页借给这个任务当当前网页标签(见 PageLoans);开跑时页面还开着才借得到
    val pageLoan = if (provenance.origin == RunOrigin.Ui) withContext(Dispatchers.Main) { UserBrowser.existing()?.claim(conversationId, key) } else null
    val light = env.lightModelFactory(service, session)
    val decision = env.decisionModel(service)
    // 同一模型分成两个实例并不会更便宜或更快;禁止它给批量/收尾工具反而只会增加改派和逐步操作。
    // 这只跳过逐步分工,轻量角色仍可用于意图判断、标题和收尾等辅助调用。
    val routedLight = light?.takeIf {
        !ProviderStore.get(service).roles.value.sameModel(ModelRole.Main, ModelRole.Light)
    }
    val vision = env.visionModelFactory(service, session)
    val visionFallbacks = env.visionFallbackFactory(service, session)
    val lightFallbacks = env.lightFallbackFactory(service, session)

    val (knowledge, skills) = withContext(Dispatchers.IO) {
        // 用户对某个 App/站点的补充放在私有目录 personal/apps、personal/sites 下
        val userNotes = KnowledgeStore.loadNotes(File(service.filesDir, "personal"))
        // 自学的 App 知识里改名前写下的工具名换成现在的
        val learnedNotes = legacyNames().let { legacy -> memoryRuntime.appKnowledge().notes().mapValues { it.value.copy(text = legacy.rewrite(it.value.text)) } }
        KnowledgeStore(KnowledgeStore.loadBundled(), userNotes, learnedNotes) to GuideCatalog.loadBundled()
    }
    return RunModels(model, pageLoan, light, decision, routedLight, vision, visionFallbacks, lightFallbacks, knowledge, skills)
}

internal suspend fun TaskRun.assembleRecord(a: RunAssembly): RunRecord {
    var record by a::record
    var runStore by a::runStore
    val resources = a.resources
    val traces = a.traces
    val traceTicket = a.traceTicket
    // 记录开不起来就不记,任务照跑
    val sink: RunSink = withContext(Dispatchers.IO) {
        runCatching {
            val runs = RunStore(service).also { runStore = it }
            runs.open(goal).also { opened ->
                record = opened
                runState._runId.value = opened.runId
                service.rememberRecord(this@assembleRecord, opened.runId)
                mcpExternalId?.let { ext ->
                    (service.application as? com.wangzan101.mika.MikaApp)?.remoteTasks?.rememberRun(ext, opened.runId)
                }
            }
        }.onFailure { Log.w(TAG, "run record unavailable, running without it", it) }.getOrNull()
    } ?: RunSink.NONE
    val trace = record?.takeIf { traceTicket != null }?.let { run ->
        resources.acquire(Dispatchers.IO, traces.store::finish) { traces.start(traceTicket, run.runId) }
    }
    return RunRecord(sink, trace)
}

internal suspend fun TaskRun.assembleMemory(m: RunModels, ph: RunPhone): RunMemory {
    val skills = m.skills
    val phoneScreen = ph.phoneScreen
    // 装了哪些 App:每个任务读一次,读不到就当没有;参考资料用
    val apps = try { phoneScreen.listApps() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    val speed = FeatureFlags.get(service).speedFlags()
    // 记忆与个人技能:进程内同一份;总开关关着就不给记忆(不检索、不注入、不提取、没有记忆工具)
    val switches = memoryRuntime.settings().switches.value.let { sw ->
        sw.copy(learnSkills = env.evalHooks.learnSkills ?: sw.learnSkills, autoRemember = env.evalHooks.rememberMemory ?: sw.autoRemember)
    }
    val memory = if (switches.useMemory) withContext(Dispatchers.IO) { memoryRuntime.store() } else null
    val taskMemory = if (switches.taskMemoryEnabled) withContext(Dispatchers.IO) { memoryRuntime.taskMemory() } else null
    val profileSnapshot = if (switches.useMemory) withContext(Dispatchers.IO) {
        runCatching { memoryRuntime.profile().snapshot() }.onFailure { Log.w(TAG, "profile unavailable", it) }.getOrDefault(emptyList())
    } else emptyList()
    // 用户本次说的话:记忆的引文、按用户要求写下技能的引文都据此核对(只在内存里,任务开始清空)
    val utterances = UserUtterances()
    // 改名前沉淀的个人技能:里面的旧工具名载入时换成现在的
    // 插件技能取任务开始时的快照:任务中途启停插件,下一次任务才生效
    val catalog = GuideCatalog.withPersonal(
        skills, memoryRuntime.skills(), legacyNames()::rewrite,
        plugins = (service.application as MikaApp).plugins.skillsSnapshot(),
    )
    return RunMemory(apps, speed, switches, memory, taskMemory, profileSnapshot, utterances, catalog)
}

internal suspend fun TaskRun.assembleWeb(a: RunAssembly, ph: RunPhone): LazyWebPort? {
    var webStack by a::webStack
    var dispatcher by a::dispatcher
    val resources = a.resources
    val side = a.side
    val metrics = ph.metrics
    // 内置浏览器:每个任务自己的后台网页显示、标签池和脚本引擎,第一次打开网址时才建(只用手机的任务不建);
    // 系统里没有 WebView 时不提供网页工具
    val webInstalled = runCatching { WebViewCompat.getCurrentWebViewPackage(service) != null }.getOrDefault(false)
    val web = if (!webInstalled) null else LazyWebPort {
        val stack = resources.acquire(Dispatchers.Main, WebStack::close) { WebStack.create(
                service, key, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                // 下载、上传走当前任务解析出的工作区:只是 App 私有目录里的文件夹,不要求沙箱已装好
                workspace = SandboxDirs(service).let { d -> WebStack.Workspace(d.workspace(workspaceKey), d.gate, d.tree) },
            ) }
        webStack = stack
        // 模型用 web_keep_open 标过的那一页,任务结束时留在后台,记在这一轮名下(对话里那一轮下面显示、关了补一句)
        stack.keepOrigin = { KeptOrigin(conversationId, runState._runId.value, goal) }
        side.launch {
            // 网页界面时画中画显示网页显示的画面;换界面时先清掉上一边的画面;怎么显示见 WebPreview
            var last: String? = null
            while (true) {
                delay(WEB_FRAME_MS)
                val surface = dispatcher?.surface
                if (surface == WEB) runState._phoneFrames.value = null
                if (surface != last) {
                    runState._frames.value = null
                    last = surface
                }
                // 当前标签是借来的那一页时它就在用户眼前,和接管中一样不放画面
                val show = WebPreview.of(onWeb = surface == WEB, hasTab = stack.hasVisibleTab, handedOver = stack.handedOver || stack.onBorrowedTab)
                when (show) {
                    WebPreview.Frames -> withContext(Dispatchers.IO) { stack.host.latestFrame() }?.let { runState._frames.value = it }
                    WebPreview.Hidden, WebPreview.NoPage -> runState._frames.value = null
                    WebPreview.Off -> Unit
                }
                runState._webOpen.value = show.open
            }
        }
        stack.screen
    }
    return web
}

internal suspend fun TaskRun.lendPage(a: RunAssembly, m: RunModels, ph: RunPhone, web: LazyWebPort?) {
    var webStack by a::webStack
    var dispatcher by a::dispatcher
    val pageLoan = m.pageLoan
    val phoneScreen = ph.phoneScreen
    // 借来的页面:开局就建好网页这一套,把它当当前标签借进去;用户摸了它就暂停(此刻正在手机屏上操作时不算)
    if (pageLoan != null && web != null && web.prepare()) webStack?.let { stack ->
        withContext(Dispatchers.Main) {
            stack.borrowedTouches = { pageLoan.touches }
            UserBrowser.existing()?.loans?.bind(pageLoan, object : PageLoans.Borrower<WebTab> {
                override fun take(tab: WebTab) = stack.borrow(tab)
                override fun giveBack(tab: WebTab) = stack.giveBack(tab)
                override fun swap(prev: WebTab, next: WebTab) = stack.swapBorrowed(prev, next)
                override fun adopt(tab: WebTab) = stack.adopt(tab)
                override fun touched() {
                    if (stack.onBorrowedTab && !(phoneScreen.acquired && dispatcher?.surface != WEB)) phoneRuntime.userOnPage()
                }
            })
        }
    }
}

internal suspend fun TaskRun.assemblePrompt(a: RunAssembly, priorTurns: List<Turn>): RunPrompt {
    val tokenBudget = a.tokenBudget
    // 这个对话里用户发来的屏幕(跟着对话存在工作区里)
    val userScreens = (service.application as MikaApp).userScreens.source(conversationId)
    // 人格是设置里选的在用的那一套,对所有对话一样;条件检查是内部只读 JSON 检查,不继承人格。
    // 评测换掉模型来源时也不带(和备援链一样):这台设备上选了什么人格不该改变评测结果。
    // 对话旁文件里以前存的绑定不再读。
    val persona = if (conditionCheck || !FeatureFlags.get(service).persona() ||
        env.modelFactory !== TaskEnvironment.defaultModelFactory) null
    else (service.application as MikaApp).personas.active()
    // 称呼是助理的自称(默认 Mika):身份句和人格层的固定头都用它。
    val assistant = PersonaPrompt.nickname(persona?.nickname)
    // 人格拼在身份、准则、通用规则之后;子任务和主任务用同一份。
    // 选项全默认、正文也为空时没有可拼的层。
    val personaLayer = persona?.let { PersonaPrompt.layer(it.name, it.prompt, it.style, assistant) }
    val appLang = AppLanguage.current(service)
    val promptWithPersona = SystemPrompt.withPersona(SystemPrompt.identity(assistant), personaLayer)
    val basePrompt = SystemPrompt.withLanguage(promptWithPersona, appLang.tag)
    // 这一轮用了人格:拼出了人格层,或者只改了称呼;两样都没有就不记
    val personaLabel = if (persona != null && (personaLayer != null || assistant != PersonaPrompt.DEFAULT_NICKNAME)) {
        Redactor.text("${persona.name} · v${persona.revision}")
    } else null
    val stepLimit = if (conditionCheck) null else FeatureFlags.get(service).taskStepLimit()
    val runSpec = RunSpec(RunId(key), goal, conversationId,
        priorTurns,
        provenance, if (conditionCheck) setOf("web", "sandbox") else null, readOnly = conditionCheck,
        stepBudget = stepLimit, tokenBudget = tokenBudget)
    return RunPrompt(userScreens, basePrompt, personaLabel, runSpec)
}
