package com.wangzan101.mika.task

import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.webkit.MimeTypeMap
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.display.PipInput
import com.wangzan101.mika.cap.phone.shell.AppAudioKey
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.media.AudioShareExporter
import com.wangzan101.mika.media.ShareableFile
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 已接上的手机侧:shell、无障碍、虚拟屏、静音。 */
internal class PhoneSide(
    val shell: IMikaShell, val a11y: ScreenAccessibility, val screen: AndroidScreen,
    val display: LazyAgentDisplay, val audio: AppMuter,
)

/**
 * 小窗直接操作的口子:把预览画面上的归一化坐标换算成虚拟屏逻辑坐标,交给内置助手注入。
 * 注入走后台线程:injectStroke 会按笔迹时长逐点 sleep,不能占着界面线程。
 */
internal class PipInputImpl(
    private val shell: IMikaShell,
    private val display: AgentDisplay,
) : PipInput {
    override suspend fun tap(fx: Float, fy: Float) {
        withContext(Dispatchers.IO) {
            runCatching { shell.injectTap(display.displayId, (fx * display.width).toInt(), (fy * display.height).toInt()) }
        }
    }

    override suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long) {
        if (points.size < 2) {
            if (points.size == 1) tap(points[0].first, points[0].second)
            return
        }
        withContext(Dispatchers.IO) {
            val n = points.size
            runCatching {
                shell.injectStroke(
                    display.displayId,
                    IntArray(n) { (points[it].first * display.width).toInt() },
                    IntArray(n) { (points[it].second * display.height).toInt() },
                    LongArray(n) { durationMs * it / (n - 1) },
                )
            }
        }
    }

    override suspend fun back() {
        withContext(Dispatchers.IO) {
            runCatching { shell.injectKey(display.displayId, KeyEvent.KEYCODE_BACK) }
        }
    }
}

/**
 * 手机侧运行时:浮层声音、静音规则、目标 App 监视、浏览器页触摸暂停、以及若干手机侧小助手。
 * 原 TaskRun 里的对应函数整体搬到这里;需要读/写的 TaskRun 瞬时字段经 lambda 注入。
 */
internal class PhoneRuntime(
    private val service: TaskService,
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val state: TaskRunState,
    private val publish: (TaskState) -> Unit,
    private val phone: () -> PhoneSide?,
    private val takeover: () -> Takeover?,
    private val gate: () -> TaskGate?,
    private val handoverRef: () -> Takeover?,
    private val sideScope: () -> CoroutineScope?,
    private val sandbox: () -> AndroidSandbox,
    private val setMuteFailedFor: (String?) -> Unit,
    private val workspaceKey: () -> WorkspaceKey = { WorkspaceKey.Conversation(conversationId) },
) {
    /** 浮层上的声音开关:恢复过声音的再按一次静回去,没恢复的按一次恢复(重复点击重试真实动作);没有手机侧或目标 App 时不动。 */
    fun togglePipSound() {
        scope.launch(Dispatchers.IO) {
            val restored = pipSoundRestored()
            val targetPkg = phone()?.screen?.target?.value?.packageName
            val handed = takeover()?.handedOverPackage
            Log.i(TAG, "togglePipSound restored=$restored target=$targetPkg handed=$handed unmuted=${phone()?.audio?.userUnmuted?.value}")
            if (restored) mutePipSound(takeover()) {
                val p = phone() ?: return@mutePipSound
                val target = p.screen.target.value ?: return@mutePipSound
                if (!p.audio.muteAgain(target.packageName, copyUser(target.userId))) {
                    Log.w(TAG, "could not mute again ${target.packageName} from pip")
                }
            } else restorePipSound(takeover()) {
                val p = phone() ?: return@restorePipSound
                val target = p.screen.target.value ?: return@restorePipSound
                if (!p.audio.unmute(target.packageName, copyUser(target.userId))) {
                    Log.w(TAG, "could not restore sound of ${target.packageName} from pip")
                }
            }
        }
    }

    /** 小窗当前目标的声音是否已被用户恢复过:接管中看交出去的包,否则看虚拟屏目标;键的取法与 unmute/muteAgain 一致。 */
    fun pipSoundRestored(): Boolean {
        val p = phone() ?: return false
        val target = p.screen.target.value
        val pkg = takeover()?.handedOverPackage ?: target?.packageName ?: return false
        val user = target?.takeIf { it.packageName == pkg }?.let { copyUser(it.userId) }
        return AppAudioKey.of(pkg, user) in p.audio.userUnmuted.value
    }

    internal suspend fun shareWorkspaceAudio(path: String): ShareableFile? = withContext(Dispatchers.IO) {
        val trimmed = path.trim()
        val prefix = SandboxPaths.WORKSPACE + "/"
        if (!trimmed.startsWith(prefix)) return@withContext null
        val rel = trimmed.removePrefix(prefix)
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return@withContext null
        val dirs = SandboxDirs(service)
        val bytes = dirs.tree.shared {
            dirs.gate.openRead(dirs.workspace(workspaceKey()), rel).use { g ->
                if (g.size > 20L * 1024 * 1024) null else g.read(0, g.size.toInt())
            }
        } ?: return@withContext null
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(trimmed.substringAfterLast('.', "").lowercase())
            ?.takeIf { it.startsWith("audio/") } ?: "audio/wav"
        AudioShareExporter(service).save(bytes, mime, trimmed.substringAfterLast('/'))
    }

    /**
     * 这次任务的静音规则(见 [AppMuter]):Mika 自己不静;没有桌面入口的系统包不静(系统界面、选择框一类);
     * 用户在主屏上看得见的不静;没静成的记下,任务进度上提示可能出声。
     */
    fun appMuter(shell: IMikaShell, audio: AudioMute): AppMuter {
        val pm = service.packageManager
        val own = service.packageName
        fun system(pkg: String) = runCatching {
            pm.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrElse { runCatching { shell.isSystemPackage(pkg) }.getOrDefault(false) }
        return AppMuter(
            audio,
            enabled = { FeatureFlags.get(service).muteTarget() },
            mutable = { pkg -> pkg != own && (runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null || !system(pkg)) },
            onMainScreen = { pkg, user -> runCatching { shell.visibleOnMainScreen(pkg, user ?: AppCopyReader.myUser) }.getOrDefault(true) },
            failed = { pkg ->
                setMuteFailedFor(pkg)
                publish(state._state.value)
            },
        )
    }

    /** 任务所属的用户 → 静音按哪一份:本来那份(或读不到)为 null,另一份用户里的为那个用户编号。 */
    fun copyUser(userId: Int): Int? = userId.takeIf { it >= 0 && it != AppCopyReader.myUser }

    /**
     * 系统在用户主屏上问打开哪一个(见 [AndroidScreen.userPick]):停在这里,胶囊上请用户在屏幕上选一下,选好自动接着做;
     * 选出来的 App 要用户亲手处理的,留在主屏,请用户处理好了点继续。不在等时直接返回。
     */
    suspend fun awaitUserPick(screen: AndroidScreen, gate: TaskGate, setWaiting: (TaskState?) -> Unit) {
        val pick = screen.userPick.value ?: return
        setWaiting(TaskState.Held(TaskGate.userStep(TaskGate.pickCopy(pick.app))))
        val r = screen.awaitUserPick() as? AndroidScreen.PickResult.NeedsUser ?: return
        gate.pauseForUser(TaskGate.finishCopy(r.app), setWaiting)
        screen.afterUserStep(r)
    }

    /**
     * 目标 App 换了就静音(启动前多半已静上,这里补上没静成的),并盯着它有没有被用户挪到主屏。
     */
    fun watchTarget(p: PhoneSide, states: DeviceStateWatcher) {
        val side = sideScope() ?: return
        side.launch(Dispatchers.IO) {
            p.screen.foregroundApps().collect { t ->
                if (handoverRef()?.handedOver != true) p.audio.opened(t.packageName, copyUser(t.userId))
                delay(FOREGROUND_GAP_MS)
            }
        }
        side.launch(Dispatchers.IO) {
            val stage = takeover()?.stage ?: MutableStateFlow(Takeover.Stage.None)
            combine(p.screen.target, p.audio.userUnmuted, stage) { _, _, _ -> }.collect {
                state._pipSoundOn.value = pipSoundRestored()
            }
        }
        side.launch(Dispatchers.IO) {
            p.screen.target.filterNotNull().collectLatest { t ->
                state._appShown.value = true
                if (handoverRef()?.handedOver != true) p.audio.opened(t.packageName, copyUser(t.userId))
                val displayId = p.display.current?.displayId
                if (t.taskId >= 0 && displayId != null) {
                    states.taskMovedAway(displayId, t.taskId).collect {
                        if (handoverRef()?.handedOver == true) return@collect
                        gate()?.onTaskMovedAway()
                        publish(state._state.value)
                    }
                }
            }
        }
    }

    /**
     * 用户在浏览器页上亲手摸了借给这个任务的页面:正在干活时马上显示暂停,下一个会改动屏幕的工具之前停下,
     * 用户点「继续」再接着做(见 [TaskGate.onUserOnPage])。等用户回答、接管中等别的状态不动。主线程。
     */
    fun userOnPage() {
        if (state._state.value !is TaskState.Running) return
        val g = gate() ?: return
        g.onUserOnPage()
        publish(state._state.value)
    }

    /** 主屏上该包最上面那个窗口的标题;窗口按从上到下排列。 */
    fun mainWindowTitle(a11y: ScreenAccessibility, packageName: String): String? = runCatching {
        a11y.windowsOn(Display.DEFAULT_DISPLAY).firstNotNullOfOrNull { w ->
            w.title?.toString()?.takeIf { it.isNotBlank() && w.root?.packageName?.toString() == packageName }
        }
    }.getOrNull()

    /** 沙箱这一步的说明写到任务卡片上(当前这一步的 note)。 */
    fun sandboxNote(text: String) {
        val s = state._state.value as? TaskState.Running ?: return
        publish(s.copy(note = text))
    }

    fun sandboxOnline() = (sandbox().status() as? SandboxStatus.Ready)?.network == true

    /**
     * 等另一个任务放开 [app] 的这段时间:对外的进展写「等另一个任务用完 [app]」,仍是 Running,不是暂停、也不要你处理;
     * 等完(放开了、等不到、被停掉)把等之前那一句放回去。等之前不在干活(比如已经停下来要你处理)就不改。
     */
    suspend fun <T> waitingForApp(app: String, wait: suspend () -> T): T {
        val before = state._state.value as? TaskState.Running ?: return wait()
        val waiting = before.copy(progress = RunProgress.WaitingForApp(app))
        publish(waiting)
        try {
            return wait()
        } finally {
            if (state._state.value == waiting) publish(before)
        }
    }

    @Suppress("DEPRECATION")
    fun mainScreenMetrics(): DisplayMetrics = DisplayMetrics().also {
        service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    private companion object {
        const val TAG = "MikaTask"
        /** Mika 屏上的前台 App 两次重读之间至少隔这么久。 */
        const val FOREGROUND_GAP_MS = 300L
    }
}
