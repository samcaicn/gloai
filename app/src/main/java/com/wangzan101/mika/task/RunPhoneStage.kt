package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.cap.phone.AppPacePlan
import com.wangzan101.mika.core.device.shell.AccessibilityRepair
import com.wangzan101.mika.core.device.shell.HelperRestart
import com.wangzan101.mika.settings.ui.StatusCopy
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.os.RemoteException
import android.util.Log
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.CalendarBulkWriter
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.PhoneUnavailable
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.task.TaskRun.Companion.SHELL_WAIT_MS
import com.wangzan101.mika.task.TaskRun.Companion.DISPLAY_RETRY_MS
import com.wangzan101.mika.task.TaskRun.Companion.WAITING_DISPLAY
import com.wangzan101.mika.task.TaskRun.Companion.A11Y_WAIT_MS
import com.wangzan101.mika.task.TaskRun.Companion.NO_PHONE_HELPER
import com.wangzan101.mika.task.TaskRun.Companion.NO_PHONE_HELPER_USB
import com.wangzan101.mika.task.TaskRun.Companion.NO_PHONE_HELPER_SHIZUKU
import com.wangzan101.mika.task.TaskRun.Companion.TAG

// 运行装配:接手机、准备手机端口、锁屏关卡
internal suspend fun TaskRun.assemblePhone(a: RunAssembly): RunPhone {
    var watcher by a::watcher
    var afterTask by a::afterTask
    var accessGuard by a::accessGuard
    var smsPrivacy by a::smsPrivacy
    val appAccess = (service.application as MikaApp).appAccess
    val metrics = phoneRuntime.mainScreenMetrics()
    // 在 App 里操作的节奏(设置 › 任务运行,未覆盖的 App 跟随全局;评测、真机验收按钩子覆盖,
    // 钩子设了档位时按应用覆盖不参与——评测要的是确定)
    val hooked = env.evalHooks.operatingPace
    val pace = AppPacePlan(hooked ?: FeatureFlags.get(service).operatingPace(), if (hooked == null) FeatureFlags.get(service).appPaceOverrides() else emptyMap())
    // 助手是否连着;没接上手机之前当作连着(网页、沙箱的步骤不看它)
    val shellUp = MutableStateFlow(true)
    val states = DeviceStateWatcher(service) { id -> phone?.shell?.topTaskId(id) ?: -1 }.also { watcher = it }

    val phoneScreen = LazyPhoneScreen(metrics.widthPixels, metrics.heightPixels,
        listApps = { withContext(Dispatchers.IO) { launchableApps(service.packageManager, service.packageName) } },
        // 另一份用户里的 App 要问内置助手:助手已经连着才问,不为它去接
        appCopies = {
            phoneServices.hub.shell.value?.let { s ->
                withContext(Dispatchers.IO) { runCatching { AppCopyReader.read(s, launchableApps(service.packageManager, service.packageName)) }.getOrNull() }
            }.orEmpty()
        },
        // shareWorkspaceAudio is always supplied to AndroidScreen above, so this capability check stays lazy.
        canShareAudio = true,
        calendarBulkWriter = { events ->
            withContext(Dispatchers.IO) { CalendarBulkWriter.insert(service, events) }
        },
    ) { connectPhone(a, appAccess, metrics, pace, shellUp, states) }
    val accessedPhone = com.wangzan101.mika.core.screen.uistate.AppAccessScreenPort(
        phoneScreen, appAccess,
        packages = { phone?.screen?.accessPackages().orEmpty() },
        protectedUi = { appAccess.sensitiveUi }, ownPackage = service.packageName,
        windowToken = { ScreenAccessibility.windowGeneration() },
    ).also { accessGuard = it }
    val privatePhone = com.wangzan101.mika.core.cap.phone.sms.SmsPrivateScreen(accessedPhone).also { smsPrivacy = it }
    afterTask = phoneScreen.afterTask
    return RunPhone(appAccess, metrics, pace, shellUp, states, phoneScreen, accessedPhone, privatePhone)
}

/** 接上手机:连内置助手、等无障碍、备好(到第一次 launch_app 才建的)虚拟屏;缺一样就报 phone_unavailable。 */
internal suspend fun TaskRun.connectPhone(a: RunAssembly, appAccess: com.wangzan101.mika.settings.AppAccessStore, metrics: DisplayMetrics, pace: AppPacePlan, shellUp: MutableStateFlow<Boolean>, states: DeviceStateWatcher): AndroidScreen {
    var connection by a::connection
    var mute by a::mute
    var shellDeath by a::shellDeath
    var shellBinder by a::shellBinder
    var dispatcher by a::dispatcher
    val resources = a.resources
    val side = a.side
    // 版本要求只属于手机操作;旧系统仍可聊天、使用网页和沙箱,也不应被引导去配对不支持的助手。
    if (Build.VERSION.SDK_INT < Prerequisites.MIN_SDK) throw PhoneUnavailable(TaskService.TOO_OLD)
    val conn = connection ?: phoneServices.sharedShell.acquire().also { connection = it }
    val shell = withTimeoutOrNull(SHELL_WAIT_MS) { conn.shell.filterNotNull().first() }
        ?: run {
            phoneServices.setupPrompt.request(conversationId, runState.runId.value)
            // 只用 USB 命令起过的助手停了:照实说要再运行命令,不让人去找无线调试
            throw PhoneUnavailable(
                when (phoneServices.shellControl.restart) {
                    HelperRestart.UsbCommand -> NO_PHONE_HELPER_USB
                    HelperRestart.Shizuku -> NO_PHONE_HELPER_SHIZUKU
                    HelperRestart.Wireless, HelperRestart.None -> NO_PHONE_HELPER
                },
            )
        }
    // 服务卡在「开着却没运行」、正在自动重启时多等一会儿,修好了接着做,放弃了照常报错
    val a11y = AccessibilityRepair.awaitService(ScreenAccessibility.instance, phoneServices.recovery.accessibilityRepair.phase, A11Y_WAIT_MS)
        ?: throw PhoneUnavailable(StatusCopy.accessibilityUnavailable(phoneServices.recovery.accessibility.value.enabled))
    recovery.beginRun(shell, key)
    // 只有用过手机的任务才跟着 shell 的死活:shell 没了,虚拟屏和上面的 App 一起没了
    shellDeath = IBinder.DeathRecipient {
        shellDied = true
        val running = loop
        if (running != null) running.cancel() else job?.cancel()
    }.also { shell.asBinder().linkToDeath(it, 0) }
    shellBinder = shell.asBinder()
    side.launch { conn.shell.map { it != null }.collect { shellUp.value = it } }
    val agentDisplay = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels, sizeOf = { it.width to it.height }) {
        suspend fun create() = resources.acquire(Dispatchers.IO, AgentDisplay::release) { AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, service.getSystemService(DisplayManager::class.java)) }
        try {
            create()
        } catch (e: Exception) {
            if (e is CancellationException || e !is IllegalStateException && e !is RemoteException) throw e
            if (!parallel || !service.otherRunHasDisplay(this@connectPhone)) throw e
            // 外壳建不出第二块屏:这次运行期间不再并行;本任务排在后面,等别的任务的屏都放掉再建
            service.taskState.markSecondDisplayFailed()
            publish(TaskState.Held(GateReason.queued(WAITING_DISPLAY)))
            while (service.otherRunHasDisplay(this@connectPhone)) delay(DISPLAY_RETRY_MS)
            create()
        }.also { d ->
            activePhoneDisplay = d
            runState._displayId.value = d.displayId
            runState._pipInput.value = PipInputImpl(shell, d)
            side.launch {
                combine(d.frames, runState._appShown.flow) { frame, shown -> frame to shown }.collect { (frame, shown) ->
                    if (dispatcher?.surface != WEB) {
                        val allowed = frame.takeIf { a.previewAllowed() }
                        if (dispatcher?.surface != WEB) {
                            runState._frames.value = allowed
                            runState._phoneFrames.value = allowed.takeIf { shown }
                        }
                    }
                }
            }
            side.launch {
                appAccess.state.collect {
                    if (dispatcher?.surface != WEB && !a.previewAllowed()) {
                        runState._frames.value = null
                        runState._phoneFrames.value = null
                    }
                }
            }
        }
    }
    val audio = recovery.audioMute(shell, key).also { mute = it }
    val muter = phoneRuntime.appMuter(shell, audio)
    // 占用登记里的名字:另一份用户里的那份单独占,和本来那份互不挡;占着的任务还在不在用它,问内置助手
    fun claimKey(pkg: String, user: Int?) = if (user == null) pkg else "$pkg@$user"
    fun stillUsed(pkg: String, user: Int?): () -> Boolean = {
        runCatching { if (user == null) shell.displayOfPackage(pkg) else shell.displayOfPackageInUser(pkg, user) }.getOrDefault(-1) >= 0
    }
    val screen = AndroidScreen(
        a11y, shell, agentDisplay, { recovery.markClipHeld(it) },
        clipLock = service.clipLock, checkOtherTasks = parallel,
        reserve = { pkg -> service.reservePackage(pkg, key, stillUsed(pkg, null)) },
        reserveCopy = { pkg, user -> service.reservePackage(claimKey(pkg, user), key, stillUsed(pkg, user)) },
        appFree = { pkg, user -> service.packageFree(claimKey(pkg, user), key, stillUsed(pkg, user)) },
        // 模型要了等:等的时候界面写「等另一个任务用完某 App」(仍是在干活,不算要你处理),等完放回原来那句
        awaitApp = { pkg, user, timeoutMs, free ->
            phoneRuntime.waitingForApp(ConfirmationNotifier.appLabel(service, pkg)) { service.awaitPackage(claimKey(pkg, user), key, timeoutMs, free) }
        },
        clipSaved = { original, ours -> recovery.saveClip(original, ours) },
        handedOver = { handoverRef?.handedOver == true },
        // 打字一段一段出来;限制浏览快慢的 App(设置里像人/覆盖)打得更慢
        typing = { pkg -> pace.of(pkg).typing() },
        // 启动前就静上,开头几秒也没声;启动被拒就还原
        beforeOpen = muter::beforeOpen,
        notOpened = muter::notOpened,
        appAccess = appAccess,
        shareAudioFile = { path -> phoneRuntime.shareWorkspaceAudio(path) },
        shareImageFile = { path -> shareWorkspaceImage(path) },
    )
    val side0 = PhoneSide(shell, a11y, screen, agentDisplay, muter)
    phone = side0
    phoneRuntime.watchTarget(side0, states)
    return screen
}

    // 挪回虚拟屏后等窗口出现,Agent 接下来读屏才读得到
    internal suspend fun TaskRun.moveTask(taskId: Int, to: Int): Boolean {
        val p = phone ?: return false
        val moved = withContext(Dispatchers.IO) { runCatching { p.shell.moveTask(taskId, to) }.getOrDefault(false) }
        if (moved && to == p.display.current?.displayId) p.screen.awaitContent()
        return moved
    }
    // 挪回去的 App 原本就在虚拟屏上,屏一定已经建好;没有屏就没有可挪回的
    internal suspend fun TaskRun.moveBackToAgent(taskId: Int): Boolean {
        val id = phone?.display?.current?.displayId ?: return false
        return moveTask(taskId, id)
    }

internal suspend fun TaskRun.assembleGate(ph: RunPhone): TaskGate {
    val shellUp = ph.shellUp
    val states = ph.states
    val app = service.application as MikaApp
    // 微信只使用当前绑定会话的独立授权；仍保留 Channel 来源及原有确认/接管规则。
    // 界面任务跟着本机开关走(判定每次重查);定时任务远程/本机两个开关任一即可。
    val unlockAuthorized = taskUnlockAuthorization(provenance.origin, channelPeer,
        localUnlockEnabled = { app.deviceAccess.settings.localEnabled }) { peer ->
        app.wechat.store.canUnlock(peer)
    }
    // 本机任务(界面/定时)在本机开关开着时走本机分流;远程来源永远走远程分流
    fun useLocalUnlock() = (provenance.origin == RunOrigin.Ui || provenance.origin == RunOrigin.Trigger) &&
        app.deviceAccess.settings.localEnabled
    var unlockWasLocal = false
    val taskGate = TaskGate(states.locked, shellUp,
        refreshLocked = states::refresh,
        unattendedUnlock = unlockAuthorized?.let { authorized ->
            {
                // 回锁走无障碍的全局动作、守卫走通知;两样都不在就立不住「锁回去」的义务,不去解锁
                if (ScreenAccessibility.instance.value == null) {
                    states.refresh()
                    "accessibility_unavailable"
                } else if (!service.startUnlockGuard()) {
                    states.refresh()
                    "notification_unavailable"
                } else {
                    unlockWasLocal = useLocalUnlock()
                    val result = try {
                        if (unlockWasLocal) app.deviceAccess.access.unlockLocal(authorized)
                        else app.deviceAccess.access.unlock(authorized)
                    } catch (e: CancellationException) {
                        // 取消后系统仍可能完成已注入的输入:换常驻警告,提醒用户检查锁屏
                        service.notifyRelockFailed()
                        throw e
                    } catch (e: Exception) {
                        service.notifyRelockFailed()
                        throw e
                    }
                    // 真的解开了才挂上回锁义务;本来就没锁(用户在用)的,摘掉刚挂的守卫
                    if (result.performedUnlock) service.noteTaskUnlock() else service.clearUnlockGuardIfSafe()
                    states.refresh()
                    Log.i(TAG, "${provenance.origin} unlock finished: ${result.status}")
                    result.status
                }
            }
        },
        // 本地授权(本机存着的授权)的任务解锁失败后不杀:转为等用户手动解锁
        recoverUnlockFailure = { unlockAuthorized != null },
        onRecoveryFailure = { status -> service.notifyUnlockRecoveryNeeded(conversationId, status) },
        unlockFailureMessage = {
            tr(when {
                provenance.origin == RunOrigin.Channel -> R.string.wechat_unlock_failed
                // 界面任务只有本机分流;定时任务按这次实际走的分流给指引
                provenance.origin == RunOrigin.Ui || unlockWasLocal -> R.string.device_local_unlock_failed
                else -> R.string.trigger_unlock_failed
            })
        },
    ) {
        val t = phone?.screen?.target?.value
        t != null && t.taskId >= 0 && moveBackToAgent(t.taskId)
    }
    gate = taskGate
    return taskGate
}
