package com.wangzan101.mika.ui.voicecall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn as ChatTurn
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.ChatCopy
import com.wangzan101.mika.ui.findActivity
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voicecall.AndroidVoiceCallAudio
import com.wangzan101.mika.voicecall.EndReason
import com.wangzan101.mika.voicecall.VoiceCallAudioRoute
import com.wangzan101.mika.voicecall.VoiceCallRoute
import com.wangzan101.mika.voicecall.VoiceCallSession
import com.wangzan101.mika.voicecall.VoiceCallState
import com.wangzan101.mika.voicecall.VoiceCallTaskLauncher
import com.wangzan101.mika.voicecall.VoiceCallTranscript
import com.wangzan101.mika.voicecall.RouteDevice
import com.wangzan101.mika.voicecall.typedResultText
import com.wangzan101.mika.voicecall.voiceCallStatusText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 通话页恒定深色(不随系统主题):底、字、控件。 */
private val PageBg = Color(0xFF070B16)
private val PageInk = Color(0xFFF2F5FA)
private val PageInk2 = Color(0xFF9AA5B5)
private val PageInk3 = Color(0xFF5A6270)
private val PageSurface = Color(0xFF151B29)
private val PageOk = Color(0xFF3CCF7A)
private val PageBad = Color(0xFFE5483D)

/**
 * 语音通话全屏页:全屏 Dialog(点外部、返回键都不收起),恒定深色不透明。
 * 左上收起 ⌄ / 顶部中央 mm:ss + 状态点和状态词;中央自绘点阵球(形态随状态:连接=网,
 * 平时=环,听/说=波,办事中=轨道);球下方状态行滚动区(≤12 行,最新高亮,顶部淡出);
 * 文字输入框「打字,或直接说话」(打字与说话同路,走 [VoiceCallSession.sendUserText]);
 * 底部免提(选中态反转)+ 红色挂断居中放大。失败就地在页面里重试(attempt++ 重跑整个流程)。
 *
 * 页面只认 [session] 的状态流,不依赖前台服务必然在位:服务 promote 失败时通话在前台照常,
 * 退后台由系统静音路径兜出 MicSilent 错误,页面照常用同一套错误文案和重试。
 */
@Composable
fun VoiceCallOverlay(session: VoiceCallSession, route: VoiceCallRoute, audioRoute: VoiceCallAudioRoute, onCollapse: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val state by session.state.collectAsState()
        val micLevel by session.micLevel.collectAsState()
        val speakerLevel by session.speakerLevel.collectAsState()
        val busy by route.busy.collectAsState()
        val lines by route.lines.collectAsState()
        val device by audioRoute.route.collectAsState()
        val level = maxOf(micLevel, speakerLevel)
        val shape = when {
            state is VoiceCallState.Connecting || state is VoiceCallState.MicCheck -> VoiceCallShape.Web
            busy -> VoiceCallShape.Orbits
            level > VOICE_WAVE_LEVEL -> VoiceCallShape.Wave
            else -> VoiceCallShape.Globe
        }
        VoiceCallPage(onCollapse = onCollapse) {
            TopStatus(state)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                VoiceCallBall(shape, level, Modifier.align(Alignment.Center).size(240.dp).testTag("voicecall-ball-${shape.name}"))
                if (state is VoiceCallState.Failed) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PageButton(stringResource(R.string.voicecall_retry), Modifier.testTag("voicecall-retry")) { session.retry() }
                    }
                } else {
                    // 结束后底部没有输入行兜 inset:状态行自己避开系统导航栏
                    StatusLines(lines, Modifier.align(Alignment.BottomCenter).then(if (state is VoiceCallState.Ended) Modifier.navigationBarsPadding() else Modifier))
                }
            }
            val active = state is VoiceCallState.Active
            val ended = state is VoiceCallState.Ended
            // 结束后只留「收起」这一条路:输入与免提/挂断都没意义了
            if (!ended) {
                InputLine(enabled = active, onSend = session::sendUserText)
                BottomBar(
                    speakerOn = device == RouteDevice.Speaker,
                    onSpeaker = { audioRoute.setSpeakerRequested(device != RouteDevice.Speaker) },
                    onHangup = session::hangup,
                )
            }
        }
    }
}

/** 页面骨架:恒定深色整页 + 左上角收起;[content] 排在顶栏下面。 */
@Composable
private fun VoiceCallPage(onCollapse: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(PageBg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .clickable(onClickLabel = stringResource(R.string.voicecall_collapse), onClick = onCollapse)
                    .testTag("voicecall-collapse"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MikaIcons.Chevron, stringResource(R.string.voicecall_collapse),
                    Modifier.size(22.dp).graphicsLayer { rotationZ = 90f }, tint = PageInk2,
                )
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(44.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/** 顶部中央:mm:ss 计时(接通后)+ 状态点 + 状态词。 */
@Composable
private fun TopStatus(state: VoiceCallState) {
    val active = state as? VoiceCallState.Active
    // 结束/失败后停在上次的值:通话时长是这通的最终结果,不该在收场瞬间归零
    val lastClock = remember { mutableStateOf("0:00") }
    val clock by produceState(lastClock.value, active?.startedAt) {
        val startedAt = active?.startedAt ?: return@produceState
        while (true) {
            value = ChatCopy.clock(System.currentTimeMillis() - startedAt)
            lastClock.value = value
            delay(250)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(clock, Modifier.testTag("voicecall-clock"), style = MikaType.Value, color = PageInk)
        Row(
            Modifier.padding(top = 4.dp).testTag("voicecall-status"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val dotColor = when (state) {
                is VoiceCallState.Active -> PageOk
                is VoiceCallState.Failed -> PageBad
                else -> PageInk3
            }
            Box(Modifier.size(7.dp).background(dotColor, CircleShape))
            Text(
                voiceCallStatusText(state),
                style = MikaType.Caption,
                color = if (state is VoiceCallState.Failed) PageBad else PageInk2,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 球下方的状态行滚动区:≤12 行,最新一行在最下面、高亮,顶部淡出遮罩。 */
@Composable
private fun StatusLines(lines: List<String>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 32.dp).heightIn(max = 180.dp)) {
        LazyColumn(Modifier.fillMaxWidth(), reverseLayout = true) {
            itemsIndexed(lines.asReversed()) { _, line ->
                val latest = line == lines.lastOrNull()
                Text(
                    line,
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    style = MikaType.Caption,
                    color = if (latest) PageInk else PageInk2,
                    textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 顶部淡出:旧行滚出时隐进底色
        Box(
            Modifier.fillMaxWidth().height(28.dp).align(Alignment.TopCenter).background(
                Brush.verticalGradient(listOf(PageBg, PageBg.copy(alpha = 0f))),
            ),
        )
    }
}

/** 文字输入框:打字与说话同路;接通后才可发。 */
@Composable
private fun InputLine(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp)).background(PageSurface)
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(stringResource(R.string.voicecall_input_hint), style = MikaType.Body, color = PageInk3, maxLines = 1)
            }
            BasicTextField(
                text, { text = it },
                Modifier.fillMaxWidth().testTag("voicecall-input"),
                enabled = enabled,
                textStyle = MikaType.Body.copy(color = PageInk),
                cursorBrush = SolidColor(PageInk),
                maxLines = 3,
            )
        }
        val canSend = enabled && text.isNotBlank()
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .clickable(enabled = canSend, onClickLabel = stringResource(R.string.chat_send)) {
                    onSend(text)
                    text = ""
                }
                .testTag("voicecall-send"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Up, stringResource(R.string.chat_send), Modifier.size(18.dp), tint = if (canSend) PageInk else PageInk3)
        }
    }
}

/** 底部:免提(选中态反转)在左,红色挂断居中放大。 */
@Composable
private fun BottomBar(speakerOn: Boolean, onSpeaker: () -> Unit, onHangup: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (speakerOn) NearCallColor else PageSurface)
                .clickable(onClickLabel = stringResource(R.string.voicecall_speaker), onClick = onSpeaker)
                .testTag("voicecall-speaker"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MikaIcons.Sound, stringResource(R.string.voicecall_speaker), Modifier.size(22.dp),
                tint = if (speakerOn) PageBg else PageInk2,
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(PageBad)
                .clickable(onClickLabel = stringResource(R.string.voicecall_hangup), onClick = onHangup)
                .testTag("voicecall-hangup"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MikaIcons.Phone, stringResource(R.string.voicecall_hangup),
                Modifier.size(28.dp).graphicsLayer { rotationZ = 135f }, tint = Color.White,
            )
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(48.dp))
    }
}

private val NearCallColor = Color(0xFF8EFAFF)

/** 页内一颗深色按钮(重试、门禁动作)。 */
@Composable
private fun PageButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(24.dp)).background(PageSurface).clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 10.dp),
    ) {
        Text(label, style = MikaType.BodyStrong, color = PageInk)
    }
}

/**
 * 进通话前的门:没权限、权限被拒过、没配凭据、配置的协议不支持通话,各占一页;
 * 都在页内给按钮,不自动弹系统框。
 */
enum class VoiceCallGateStage { NeedPermission, PermissionDenied, NeedCredentials, UnsupportedProtocol }

/**
 * 门禁页:和通话页同一个深色全屏骨架,球安静地转(Globe),中间是说明和按钮。
 * [stage] 决定文案与主按钮:请求权限 / 去设置 / 去配置。
 */
@Composable
fun VoiceCallGate(
    stage: VoiceCallGateStage,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onGoConfigure: () -> Unit,
    onCollapse: () -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        VoiceCallPage(onCollapse = onCollapse) {
            TopStatusPlaceholder()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                VoiceCallBall(VoiceCallShape.Globe, 0f, Modifier.align(Alignment.Center).size(240.dp).alpha(0.5f))
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val title: String
                    val body: String
                    val action: String
                    val go: () -> Unit
                    when (stage) {
                        VoiceCallGateStage.NeedPermission -> {
                            title = stringResource(R.string.voicecall_mic_needed_title)
                            body = stringResource(R.string.voicecall_mic_needed_body)
                            action = stringResource(R.string.voicecall_mic_allow)
                            go = onRequestPermission
                        }
                        VoiceCallGateStage.PermissionDenied -> {
                            title = stringResource(R.string.voicecall_mic_needed_title)
                            body = stringResource(R.string.voicecall_mic_denied_body)
                            action = stringResource(R.string.voice_permission_open)
                            go = onOpenSettings
                        }
                        VoiceCallGateStage.NeedCredentials -> {
                            title = stringResource(R.string.voicecall_setup_title)
                            body = stringResource(R.string.voicecall_setup_body)
                            action = stringResource(R.string.voicecall_go_setup)
                            go = onGoConfigure
                        }
                        VoiceCallGateStage.UnsupportedProtocol -> {
                            title = stringResource(R.string.voicecall_protocol_title)
                            body = stringResource(R.string.voicecall_protocol_body)
                            action = stringResource(R.string.voicecall_go_setup)
                            go = onGoConfigure
                        }
                    }
                    Text(title, style = MikaType.Title, color = PageInk, textAlign = TextAlign.Center)
                    Text(
                        body, Modifier.padding(top = 10.dp),
                        style = MikaType.Body, color = PageInk2, textAlign = TextAlign.Center,
                    )
                    PageButton(action, Modifier.padding(top = 18.dp).testTag("voicecall-gate-action"), go)
                }
            }
        }
    }
}

/** 门禁页顶栏中央留空(高度与通话页一致,页面切换不跳)。 */
@Composable
private fun TopStatusPlaceholder() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(" ", style = MikaType.Value, color = PageInk)
        Spacer(Modifier.height(4.dp))
        Text(" ", style = MikaType.Caption, color = PageInk2)
    }
}

/** 一通通话的页面侧持有物:会话、路由、免提、转写,以及页面此刻显不显。 */
@Stable
class VoiceCallHolder(
    val session: VoiceCallSession,
    val route: VoiceCallRoute,
    val audioRoute: VoiceCallAudioRoute,
    val transcript: VoiceCallTranscript,
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
 * 缺凭据/缺权限先进门禁,齐了才建会话。装配方不在会话事件循环里 await 路由:
 * onVoiceRequest 一律 `scope.launch { route.execute(...) }`。
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

    private var currentRealtime: CallRealtimeSession? = null
    private var callScope: CoroutineScope? = null

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
        val profile = deps.profile()
        // 应用内通话只用双向实时语音协议:别的协议进不去,也不静默失败,门禁页说明并引到配置
        if (profile.protocol != CallVoiceProtocol.VOLC_DUPLEX) {
            stage = VoiceCallStage.Gate(VoiceCallGateStage.UnsupportedProtocol)
            return
        }
        if (!profile.credentialsConfigured || deps.credentials(profile.id) == null) {
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
        if (current is VoiceCallStage.Gate && current.stage != VoiceCallGateStage.NeedCredentials && micGranted()) openCall()
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
        val profile = deps.profile()
        if (profile.protocol != CallVoiceProtocol.VOLC_DUPLEX) {
            stage = VoiceCallStage.Gate(VoiceCallGateStage.UnsupportedProtocol)
            return
        }
        val originConversation = deps.conversationId()
        val transcript = VoiceCallTranscript(
            conversationId = { originConversation },
            appendTurn = deps.appendTurn,
        )
        val callScope = CoroutineScope(SupervisorJob(deps.scope.coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default)
        this.callScope = callScope
        val route = VoiceCallRoute(
            launcher = deps.launcher,
            runState = deps.runState,
            sendResult = { callId, text ->
                if (callId.startsWith(VoiceCallSession.TYPED_CALL_PREFIX)) {
                    // 打字的请求模型侧没有 function_call,结果回本地:进转写(答),面板状态行已由路由自己上过
                    transcript.onAssistant(typedResultText(text))
                } else {
                    currentRealtime?.sendToolResult(callId, buildJsonObject {
                        put("status", "completed")
                        put("result", text)
                    })
                }
            },
            cancelTask = deps.cancelTask,
        )
        val session = VoiceCallSession(
            scope = callScope,
            audio = AndroidVoiceCallAudio(deps.context),
            foregroundResumed = deps.foregroundResumed,
            connect = {
                val credentials = deps.credentials(profile.id)
                    ?: throw VolcRealtimeException(VolcRealtimeException.Kind.AUTH)
                VolcRealtimeClient(endpoint = profile.endpoint).connect(
                    credentials,
                    VolcRealtimeConfig(
                        speaker = profile.speaker,
                        model = profile.model,
                        agentTools = true,
                        protocol = profile.protocol,
                    ),
                ).also { currentRealtime = it }
            },
            greeting = tr(R.string.voicecall_greeting),
            onVoiceRequest = { callId, text -> callScope.launch { route.execute(callId, text) } },
            onBargeIn = route::onBargeIn,
            onUserTranscript = { text, isFinal -> if (isFinal) transcript.onUser(text) },
            onAssistantTranscript = { text, isFinal -> if (isFinal) transcript.onAssistant(text) },
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
        stage = VoiceCallStage.InCall(VoiceCallHolder(session, route, VoiceCallAudioRoute(deps.context), transcript))
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
    val launcher: VoiceCallTaskLauncher,
    val runState: (String) -> StateFlow<TaskState>?,
    val cancelTask: (String) -> Unit,
    val conversationId: () -> String?,
    val appendTurn: suspend (String, ChatTurn) -> Unit,
    val attachService: (VoiceCallSession) -> Boolean,
)

/**
 * 通话页的宿主:跟着 [openSignal] 的变化开(或重新亮出)通话;门禁的三个按钮、权限回执、
 * 回前台重查都接好。通话页本身不感知前台服务在不在——保活是服务的事,页面只看会话状态。
 */
@Composable
fun VoiceCallHost(deps: VoiceCallDeps, openSignal: Int, onGoConfigure: () -> Unit) {
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
                onGoConfigure()
            },
            onCollapse = controller::collapse,
        )
        is VoiceCallStage.InCall -> if (stage.call.visible) {
            VoiceCallOverlay(stage.call.session, stage.call.route, stage.call.audioRoute, controller::collapse)
        }
        null -> Unit
    }
}
