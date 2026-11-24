package com.wangzan101.mika.voicecall.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.CallVoiceProtocol
import com.wangzan101.mika.calls.VolcRealtimeClient
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.core.agent.Turn as ChatTurn
import com.wangzan101.mika.core.frontdesk.DeskPersona
import com.wangzan101.mika.core.frontdesk.DeskPrompt
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskToolSet
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.LlmBrain
import com.wangzan101.mika.core.frontdesk.opening
import com.wangzan101.mika.frontdesk.DeskRegistry
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.browser.ui.findActivity
import com.wangzan101.mika.voice.cascade.CallUse
import com.wangzan101.mika.voice.cascade.CascadeEngine
import com.wangzan101.mika.voice.model.CallEngine
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voicecall.AndroidVoiceCallAudio
import com.wangzan101.mika.voicecall.EndReason
import com.wangzan101.mika.voicecall.VoiceCallAudioRoute
import com.wangzan101.mika.voicecall.VoiceCallFeed
import com.wangzan101.mika.voicecall.ECHO_WINDOW_CAPACITY_CHARS
import com.wangzan101.mika.voicecall.RingBuffer
import com.wangzan101.mika.voicecall.VoiceCallRequest
import com.wangzan101.mika.voicecall.VoiceCallSession
import com.wangzan101.mika.voicecall.VoiceCallState
import com.wangzan101.mika.voicecall.VoiceCallTranscript
import com.wangzan101.mika.voicecall.VoiceDeskBridge
import com.wangzan101.mika.voicecall.deskReportSpeech
import com.wangzan101.mika.voicecall.typedDeskResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** 一通通话的页面侧持有物:会话、前台有没有任务在办([busy])、免提、转写、上屏的对话流,以及页面此刻显不显。 */
@Stable
class VoiceCallHolder(
    val session: VoiceCallSession,
    val busy: StateFlow<Boolean>,
    val audioRoute: VoiceCallAudioRoute,
    val transcript: VoiceCallTranscript,
    val feed: VoiceCallFeed,
) {
    var visible by mutableStateOf(true)
}

/** 通话页此刻在哪个阶段:门禁(还没通话)或通话中。 */
sealed interface VoiceCallStage {
    data class Gate(val stage: VoiceCallGateStage) : VoiceCallStage
    data class InCall(val call: VoiceCallHolder) : VoiceCallStage
}

/**
 * 通话页的装配与状态控制。生产依赖全部注入([deps]);一通电话从 [start] 开始:
 * 缺凭据/缺权限先进门禁,齐了才建会话。通话接着这条会话的前台:模型调的前台工具由 [VoiceDeskBridge] 执行,
 * 一律在通话协程域里 launch,不在会话事件循环里等;后台任务的汇报由前台交回通话说出来。
 */
@Stable
class VoiceCallController(
    private val deps: VoiceCallDeps,
    private val micGranted: () -> Boolean,
    private val micPermanentlyDenied: () -> Boolean,
    private val markMicDenied: () -> Unit,
) {
    var stage by mutableStateOf<VoiceCallStage?>(null)
        private set

    @Volatile private var currentRealtime: CallRealtimeSession? = null
    private var callScope: CoroutineScope? = null

    /** After migration both entry points resolve the active model through this one controller. */
    private val realtime = VoiceModelEnvironment.get(deps.context).realtime
    private val cascade = CascadeEngine(deps.context)

    /** Standard calls (listen → think → speak) need no realtime model; their gate is the cascade's readiness. */
    private fun standard(): Boolean = realtime.migrated && cascade.engine(CallUse.InApp) == CallEngine.Standard

    /** The active realtime profile; legacy reads the profile store, migrated reads the catalog. */
    private fun activeProfile(): CallVoiceProfile? {
        if (!realtime.migrated) return deps.profile()
        val ref = realtime.current() ?: return null
        return runCatching { realtime.profile(ref) }.getOrNull()
    }

    private fun activeCredentials(profile: CallVoiceProfile): VolcRealtimeCredentials? =
        if (realtime.migrated) runCatching { realtime.credentials() }.getOrNull()
        else deps.credentials(profile.id)

    /** 拆掉当前通话的协程域(换新一通、收起终态通话、宿主离开时);会话自身已是终态或会被调用方先落终态。 */
    private fun cancelCallScope() {
        callScope?.cancel()
        callScope = null
        currentRealtime = null
    }

    /** 入口(听筒按钮/胶囊菜单/直开 extra):活的通话只重新亮出,终态的换新一通。 */
    fun start() {
        val current = stage
        if (current is VoiceCallStage.InCall && current.call.session.state.value !is VoiceCallState.Ended) {
            current.call.visible = true
            return
        }
        if (standard()) {
            if (cascade.blocker(CallUse.InApp) != null) {
                stage = VoiceCallStage.Gate(VoiceCallGateStage.NeedListenSpeak)
                return
            }
            when {
                micGranted() -> openCall()
                micPermanentlyDenied() -> stage = VoiceCallStage.Gate(VoiceCallGateStage.PermissionDenied)
                else -> stage = VoiceCallStage.Gate(VoiceCallGateStage.NeedPermission)
            }
            return
        }
        val profile = activeProfile()
        if (profile == null) {
            stage = VoiceCallStage.Gate(VoiceCallGateStage.NeedCredentials)
            return
        }
        // 应用内通话只用双向实时语音协议:别的协议进不去,也不静默失败,门禁页说明并引到配置
        if (profile.protocol != CallVoiceProtocol.VOLC_DUPLEX) {
            stage = VoiceCallStage.Gate(VoiceCallGateStage.UnsupportedProtocol)
            return
        }
        if (!profile.credentialsConfigured || activeCredentials(profile) == null) {
            stage = VoiceCallStage.Gate(VoiceCallGateStage.NeedCredentials)
            return
        }
        when {
            micGranted() -> openCall()
            micPermanentlyDenied() -> stage = VoiceCallStage.Gate(VoiceCallGateStage.PermissionDenied)
            else -> stage = VoiceCallStage.Gate(VoiceCallGateStage.NeedPermission)
        }
    }

    /** 系统权限框的结果:给了直接开;没给且不再询问,换「去设置」。 */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            openCall()
        } else {
            markMicDenied()
            stage = VoiceCallStage.Gate(
                if (micPermanentlyDenied()) VoiceCallGateStage.PermissionDenied else VoiceCallGateStage.NeedPermission,
            )
        }
    }

    /** 回到前台时重查:在设置里开了权限就直接开。 */
    fun onResume() {
        val current = stage
        if (current is VoiceCallStage.Gate && (current.stage == VoiceCallGateStage.NeedPermission || current.stage == VoiceCallGateStage.PermissionDenied) && micGranted()) openCall()
    }

    /** 收起:通话还活着就只藏页面(前台服务保活);失败不重试的落 Ended 收场;已结束的直接拆掉。 */
    fun collapse() {
        when (val current = stage) {
            is VoiceCallStage.Gate -> stage = null
            is VoiceCallStage.InCall -> {
                when (current.call.session.state.value) {
                    is VoiceCallState.Ended -> {
                        stage = null
                        cancelCallScope()
                    }
                    is VoiceCallState.Failed -> {
                        // 失败不重试直接收起:落终态收场后把页面一并拆掉(只 end 不收页,用户得再点一次)
                        current.call.session.end(EndReason.Hangup)
                        stage = null
                        cancelCallScope()
                    }
                    else -> current.call.visible = false
                }
            }
            null -> Unit
        }
    }

    /** 宿主要离开了:没终的通话挂断,转写在 close 里 NonCancellable 写完。 */
    fun teardown() {
        val current = stage as? VoiceCallStage.InCall ?: return
        if (current.call.session.state.value !is VoiceCallState.Ended) current.call.session.end(EndReason.Hangup)
        stage = null
        cancelCallScope()
        // 页面销毁后状态收集也随之没了:写回在这里兜底。先等会话收尾(最后一段转写先落进聚合),
        // close 幂等且 NonCancellable,已写过不重复、域被取消也写完。
        val session = current.call.session
        val transcript = current.call.transcript
        // 必须挂在比页面长寿的作用域上:teardown 只在组合销毁时触发,此时 deps.scope 正被取消,
        // 在那里 launch 的函数体根本轮不到执行、join 也会因父作用域取消而抛掉 close
        deps.appScope.launch {
            session.awaitFinished()
            transcript.close()
        }
    }

    private fun openCall() {
        cancelCallScope()
        val standard = standard()
        val profile = if (standard) null else activeProfile()
        if (!standard && (profile == null || profile.protocol != CallVoiceProtocol.VOLC_DUPLEX)) {
            stage = VoiceCallStage.Gate(VoiceCallGateStage.UnsupportedProtocol)
            return
        }
        // 通话接着发起它的会话;从胶囊直开没有会话时新开一条,任务和转写都挂在它下面。
        val conversation = deps.conversationId() ?: deps.newConversation()
        val transcript = VoiceCallTranscript(
            conversationId = { conversation },
            appendTurn = deps.appendTurn,
        )
        val feed = VoiceCallFeed()
        val desk = deps.desks.desk(conversation)
        val echoWindow = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(echoWindow)
        val executor = deps.desks.executor(conversation, gate)
        val bridge = VoiceDeskBridge(desk, executor, { currentRealtime }, ::deskReportSpeech, onSpoken = echoWindow::append)
        // 会话的回调要回头告诉会话(打字结果已显示):先声明,下面建好
        lateinit var session: VoiceCallSession
        val callScope = CoroutineScope(SupervisorJob(deps.scope.coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default)
        this.callScope = callScope
        session = VoiceCallSession(
            scope = callScope,
            audio = AndroidVoiceCallAudio(deps.context),
            foregroundResumed = deps.foregroundResumed,
            connect = connect@{
                if (standard) {
                    return@connect try {
                        cascade.open(
                            CallUse.InApp,
                            DeskPrompt.user(spoken = true, persona = deps.persona()),
                            DeskTools.specs(DeskToolSet.User).map(LlmBrain::toolSpec),
                            language = null,
                            prior = deps.turns(conversation),
                            background = deps.desks.background(conversation),
                        ).also { currentRealtime = it }
                    } catch (e: VoiceModelError) {
                        throw VolcRealtimeException(VolcRealtimeException.Kind.AUTH)
                    }
                }
                val profile = profile!!
                val credentials = activeCredentials(profile)
                    ?: throw VolcRealtimeException(VolcRealtimeException.Kind.AUTH)
                // 实时模型没有每轮的背景附注:开场时把任务板和相关记忆放进说明(记忆按这条会话最近一句用户原话检索一次),
                // 之后的变化由前台汇报念给它听。
                val board = deps.desks.background(conversation).opening(deps.turns(conversation)).orEmpty().take(MAX_OPENING_BOARD_CHARS)
                VolcRealtimeClient(endpoint = profile.endpoint).connect(
                    credentials,
                    VolcRealtimeConfig(
                        systemPrompt = board,
                        speaker = profile.speaker,
                        model = profile.model,
                        agentTools = true,
                        protocol = profile.protocol,
                        deskPersona = deps.persona(),
                    ),
                ).also { currentRealtime = it }
            },
            greeting = tr(R.string.voicecall_greeting),
            echoWindow = echoWindow,
            requestGate = gate,
            onDeskCall = { callId, name, args ->
                callScope.launch {
                    val result = bridge.onFunctionCall(callId, name, args)
                    startedTask(result)?.let(transcript::onTask)
                }
            },
            onTypedText = { text ->
                callScope.launch {
                    // 实时会话不收文字:打的字原样派成一件任务,结果在本地说一句
                    val result = bridge.dispatchTyped(text)
                    startedTask(result)?.let(transcript::onTask)
                    typedDeskResult(result).let {
                        transcript.onAssistant(it)
                        feed.onAssistant(it, isFinal = true)
                    }
                    session.onLocalReply()
                }
            },
            onUserTranscript = { text, isFinal ->
                feed.onUser(text, isFinal)
                if (isFinal) transcript.onUser(text)
            },
            onAssistantTranscript = { text, isFinal ->
                feed.onAssistant(text, isFinal)
                if (isFinal) transcript.onAssistant(text)
            },
            onAssistantDraft = feed::onAssistantDraft,
        )
        // 已有活动会话时服务拒第二通:立即销毁新会话,不让它占音频
        val attached = runCatching { deps.attachService(session) }.getOrElse {
            session.end(EndReason.Hangup)
            return
        }
        if (!attached) {
            session.end(EndReason.Hangup)
            return
        }
        // 通话活着才接着前台;结束了(页面藏着也一样)就松开,聊天页接回去,之后的汇报不再落到兜底
        callScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bridge.attachWhileLive(session.state)
            // 这时已换成下一通的话不动它的会话
            if (this@VoiceCallController.callScope === callScope) currentRealtime = null
        }
        // 前台的任务板进对话流:每件任务一张卡,进展加步骤
        callScope.launch { desk.active.collect(feed::onTasks) }
        val busy = desk.active
            .map { views -> views.any { it.state == DeskTaskPhase.Running || it.state == DeskTaskPhase.Queued } }
            .stateIn(callScope, SharingStarted.Eagerly, false)
        stage = VoiceCallStage.InCall(VoiceCallHolder(session, busy, VoiceCallAudioRoute(deps.context), transcript, feed))
    }

    /** 派单结果里开始或排上队的任务编号;别的结果为 null。 */
    private fun startedTask(result: kotlinx.serialization.json.JsonObject): String? {
        val status = (result["status"] as? JsonPrimitive)?.content
        if (status != "started" && status != "queued") return null
        return (result["task_id"] as? JsonPrimitive)?.content
    }

    private companion object {
        /** 实时会话开场说明里任务板和记忆最多放这么多字(说明总长有上限)。 */
        const val MAX_OPENING_BOARD_CHARS = 4_000
    }
}

/** 通话页的生产依赖;都是函数,宿主(ChatScreen 所在层)按 App 容器填。 */
class VoiceCallDeps(
    val context: android.content.Context,
    val scope: CoroutineScope,
    /** 应用级作用域(比页面长寿):页面销毁后仍要跑完的收尾用它。 */
    val appScope: CoroutineScope,
    val profile: () -> CallVoiceProfile,
    val credentials: (String) -> VolcRealtimeCredentials?,
    val foregroundResumed: StateFlow<Boolean>,
    /** 各会话的前台:通话接着发起会话的那一个。 */
    val desks: DeskRegistry,
    val conversationId: () -> String?,
    /** 新开一条会话(从胶囊直开、没有发起会话时),返回它的编号。 */
    val newConversation: () -> String,
    /** 一条会话已有的轮次,标准模式开场时给思考模型接上前文。 */
    val turns: (String) -> List<ChatTurn>,
    val appendTurn: suspend (String, ChatTurn) -> Unit,
    val attachService: (VoiceCallSession) -> Boolean,
    /** 前台此刻用的人格(称呼和人格层),每次接通时取一次。 */
    val persona: () -> DeskPersona = { DeskPersona() },
)

/**
 * 通话页的宿主:跟着 [openSignal] 的变化开(或重新亮出)通话;门禁的三个按钮、权限回执、
 * 回前台重查都接好。通话页本身不感知前台服务在不在——保活是服务的事,页面只看会话状态。
 */
@Composable
fun VoiceCallHost(deps: VoiceCallDeps, openSignal: Int, onGoConfigure: (VoiceCallGateStage) -> Unit) {
    val context = LocalContext.current
    val controller = remember(deps) {
        VoiceCallController(
            deps,
            micGranted = {
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
            micPermanentlyDenied = {
                val activity = context.findActivity()
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
                    context.getSharedPreferences("voice_call", 0).getBoolean("mic_denied", false) &&
                    activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            },
            markMicDenied = {
                context.getSharedPreferences("voice_call", 0).edit().putBoolean("mic_denied", true).apply()
            },
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.onPermissionResult(granted)
    }
    LaunchedEffect(openSignal) { if (openSignal > 0) controller.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.onResume() }
    DisposableEffect(controller) { onDispose { controller.teardown() } }

    // 通话的状态推进:接通接管免提路由,终态恢复路由并批量写回转写(NonCancellable 在 close 内)
    val call = (controller.stage as? VoiceCallStage.InCall)?.call
    LaunchedEffect(call) {
        if (call == null) return@LaunchedEffect
        call.session.state.collect { state ->
            when (state) {
                is VoiceCallState.Active -> call.audioRoute.begin()
                is VoiceCallState.Failed -> call.audioRoute.end()
                is VoiceCallState.Ended -> {
                    call.audioRoute.end()
                    call.transcript.close()
                }
                else -> Unit
            }
        }
    }

    when (val stage = controller.stage) {
        is VoiceCallStage.Gate -> VoiceCallGate(
            stage.stage,
            onRequestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onGoConfigure = {
                controller.collapse()
                onGoConfigure(stage.stage)
            },
            onCollapse = controller::collapse,
        )
        is VoiceCallStage.InCall -> if (stage.call.visible) {
            VoiceCallOverlay(stage.call, controller::collapse)
        }
        null -> Unit
    }
}
