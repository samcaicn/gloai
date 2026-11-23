package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import com.wangzan101.mika.core.device.shell.HelperHandoff
import com.wangzan101.mika.core.device.shell.RootGrant
import com.wangzan101.mika.core.device.shell.RootStart
import com.wangzan101.mika.core.device.shell.SuAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手机已有 root 能力时的两项授权。检测 su 只看文件能力,不执行 su,也不识别 root 实现。
 * 手机助手在用户首次明确授权后自动优先经 su 以 shell 身份启动,没有可见开关;
 * root 命令是另一项独立开关,默认关闭。拒绝或不回应后只由用户的明确动作重新请求。
 */
class RootControl(context: Context, private val scope: CoroutineScope, private val changed: () -> Unit) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [startRequested] 只记录用户是否点过助手授权;真正自动启动还要求 [startGrant] 为 [RootGrant.Granted]。 */
    data class State(
        val suPresent: Boolean = false,
        val startRequested: Boolean = false,
        val startGrant: RootGrant = RootGrant.Unknown,
        val commands: Boolean = false,
        val commandsGrant: RootGrant = RootGrant.Unknown,
    )

    private val _state = MutableStateFlow(
        State(
            suPresent = false,
            startRequested = prefs.getBoolean(KEY_START, false),
            startGrant = RootGrant.of(answer(KEY_START_ANSWER)),
            commands = prefs.getBoolean(KEY_COMMANDS, false),
            commandsGrant = RootGrant.of(answer(KEY_COMMANDS_ANSWER)),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** su 在不在:第一次用时当场看一眼(只是几个文件在不在),之后由 [refresh] 更新。 */
    @Volatile private var suFound: Boolean? = null
    /** 每次新的授权动作作废旧请求;解除配对后,迟到的 su 答复也不能把自动启动重新打开。 */
    private val startRequest = AtomicInteger(0)
    private val commandsRequest = AtomicInteger(0)

    private fun suPresent(): Boolean = suFound ?: (RootShell.su() != null).also { found ->
        suFound = found
        _state.update { it.copy(suPresent = found) }
    }

    /** 重新找一次 su(App 启动、打开设置页时)。这里只检测文件,绝不会请求 root 授权。 */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val before = suFound
            val found = RootShell.su() != null
            suFound = found
            _state.update { it.copy(suPresent = found) }
            if (before != null && before != found && _state.value.startRequested) changed()
        }
    }

    /** 看门狗看的:只有用户请求过并且 su 已确认过 shell 身份,Root 启动才是 Ready。 */
    fun rootStart(): RootStart = RootStart.of(_state.value.startRequested, suPresent(), answer(KEY_START_ANSWER))

    /** 看门狗起助手时 su 的答复;null 是用户明确重试后忘掉上一次答复。 */
    fun setStartAnswer(a: SuAnswer?) {
        if (a == null) prefs.edit().remove(KEY_START_ANSWER).apply()
        else prefs.edit().putString(KEY_START_ANSWER, a.name).apply()
        _state.update { it.copy(startGrant = RootGrant.of(a)) }
    }

    /** 经 su 起助手或执行命令时发现 su 没了。 */
    fun suGone() {
        if (suFound == false) return
        suFound = false
        _state.update { it.copy(suPresent = false) }
    }

    /**
     * 用户点「自动启动手机助手」或失败后的重试:这是首次和再次请求 su 的唯一入口。
     * 已授权或正在询问时重复点击不做事;拒绝、不回话、身份不对都留在设置里说明。
     */
    fun requestStart() {
        val current = _state.value
        if (current.startGrant == RootGrant.Granted || current.startGrant == RootGrant.Pending) return
        val request = startRequest.incrementAndGet()
        prefs.edit().putBoolean(KEY_START, true).remove(KEY_START_ANSWER).apply()
        _state.update { it.copy(startRequested = true, startGrant = RootGrant.Pending) }
        scope.launch {
            val a = withContext(Dispatchers.IO) { RootShell.ask(HelperHandoff.SHELL_UID, ASK_TIMEOUT_MS) }
            if (request != startRequest.get()) return@launch
            if (a == null) {
                // su 在授权期间消失:不能一直停在 Pending,否则 su 恢复后也无法再次点授权
                setStartAnswer(null)
                suGone()
            } else setStartAnswer(a)
            changed()
        }
    }

    /** 解除配对会停止手机操作:清掉自动启动请求,避免看门狗再把助手起回来。 */
    fun turnOffStart() {
        startRequest.incrementAndGet()
        prefs.edit().putBoolean(KEY_START, false).remove(KEY_START_ANSWER).apply()
        _state.update { it.copy(startRequested = false, startGrant = RootGrant.Unknown) }
    }

    /** Agent 能用 root 工具:用户打开了、su 答应过、su 还在。 */
    fun commandsAllowed(): Boolean = _state.value.commands && answer(KEY_COMMANDS_ANSWER) == SuAnswer.Granted && suPresent()

    /** 打开或关掉「允许 Mika 执行 root 命令」。打开时以 root 问 su 一次,答应了才打开。 */
    fun setCommands(on: Boolean) {
        if (!on) {
            commandsRequest.incrementAndGet()
            prefs.edit().putBoolean(KEY_COMMANDS, false).apply()
            _state.update { it.copy(commands = false, commandsGrant = RootGrant.Unknown) }
            return
        }
        if (_state.value.commandsGrant == RootGrant.Pending) return
        val request = commandsRequest.incrementAndGet()
        _state.update { it.copy(commandsGrant = RootGrant.Pending) }
        scope.launch {
            val a = withContext(Dispatchers.IO) { RootShell.ask(0, ASK_TIMEOUT_MS) }
            if (request != commandsRequest.get()) return@launch
            if (a == null) suGone()
            val ok = a == SuAnswer.Granted
            prefs.edit().putBoolean(KEY_COMMANDS, ok).putString(KEY_COMMANDS_ANSWER, a?.name).apply()
            _state.update { it.copy(commands = ok, commandsGrant = RootGrant.of(a)) }
        }
    }

    /** 执行 root 命令时 su 拒绝了:关掉这一项并记下(下次打开时再问)。 */
    fun commandsDenied(a: SuAnswer) {
        commandsRequest.incrementAndGet()
        prefs.edit().putBoolean(KEY_COMMANDS, false).putString(KEY_COMMANDS_ANSWER, a.name).apply()
        _state.update { it.copy(commands = false, commandsGrant = RootGrant.of(a)) }
    }

    private fun answer(key: String): SuAnswer? = prefs.getString(key, null)?.let { n -> SuAnswer.entries.firstOrNull { it.name == n } }

    private companion object {
        const val PREFS = "root"
        const val KEY_START = "start"
        const val KEY_START_ANSWER = "startAnswer"
        const val KEY_COMMANDS = "commands"
        const val KEY_COMMANDS_ANSWER = "commandsAnswer"
        /** 用户明确请求授权时等待系统授权框,给看清并点一下的时间。 */
        const val ASK_TIMEOUT_MS = 60_000L
    }
}
