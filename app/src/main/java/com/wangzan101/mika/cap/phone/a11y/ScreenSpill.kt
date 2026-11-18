package com.wangzan101.mika.cap.phone.a11y

import android.accounts.AccountManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.uistate.LaunchChoice
import com.wangzan101.mika.core.uistate.LaunchChooser
import com.wangzan101.mika.core.uistate.OpenedElsewhere
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「App 被开到用户主屏上」的整块处理:动作前后盯梢主屏冒出来的任务,把开到主屏上的 App 挪回 Mika 的屏,
 * 或等用户在主屏上选完/亲手处理完。共享 [AndroidScreen] 的字段与方法经 [screen] 后向引用访问。
 */
internal class ScreenSpill(private val screen: AndroidScreen) {

    /** 一次动作的盯梢:[since] 开始的时刻,[topBefore] 那时主屏最上面的任务,[agentTopBefore] 那时 Mika 屏上最上面的任务。 */
    internal class Watch(val since: Long, val topBefore: Int, val agentTopBefore: Int)

    /** 挪回来的 App 和(系统问打开哪一个时)认出的选项。 */
    internal class Spill(val opened: OpenedElsewhere, val chooser: LaunchChoice?)

    /** 最近一次动作的盯梢;过了 [AndroidScreen.SPILL_WINDOW_MS] 清掉。 */
    @Volatile internal var watch: Watch? = null

    /** 已经判过的记录到哪个时刻:同一条不判两次。 */
    @Volatile internal var consumed = 0L

    /** 从用户主屏挪回 Mika 屏上的任务,和从它们里面打开的:它们在最上面算目标 App 打开的,不算被切走。 */
    internal val adopted: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    internal val _userPick = MutableStateFlow<AndroidScreen.UserPick?>(null)
    /** 正等着用户在主屏上选;任务据此停住,胶囊上请用户选一下。 */
    internal val userPick: StateFlow<AndroidScreen.UserPick?> = _userPick.asStateFlow()

    /** 等用户的那些事办完之后要告诉模型的,下一次读屏或动作时报出去。 */
    @Volatile internal var pendingReport: Spill? = null

    /** 用户在主屏上点东西的时刻和包(只在动作前后那一小段里记)。 */
    internal val userTaps = java.util.concurrent.ConcurrentLinkedDeque<MainScreenSpill.Tap>()
    internal val spillScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile internal var tapWatch: Job? = null
    @Volatile internal var tapWatchUntil = 0L

    /** 用户回主屏桌面的导航证据；桌面 App 按 HOME 解析，不写死。 */
    internal val homeApps: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching { screen.a11y.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName } }.getOrDefault(emptyList()).toSet()
    }

    /** 不算开到主屏上的:Mika 自己、桌面 App。 */
    internal val notSpills: Set<String> by lazy { homeApps + screen.a11y.packageName }

    /** 动作之前:请内置助手盯着主屏,记下此刻两块屏最上面的任务;没有屏、接管中或助手不支持时为 null(不盯)。 */
    internal fun armSpillWatch(): Watch? {
        if (!screen.hasDisplay || screen.handedOver()) return null
        val since = SystemClock.uptimeMillis()
        watchUserTaps(since + AndroidScreen.SPILL_WINDOW_MS)
        val top = runCatching { screen.shell.watchMainScreen(AndroidScreen.SPILL_WINDOW_MS.toInt()) }.getOrNull() ?: return null
        val agentTop = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        return Watch(since, top, agentTop).also { watch = it }
    }

    /** 盯梢期间记下用户在主屏上的点按(Mika 自己的浮层不算);到期自己停。 */
    internal fun watchUserTaps(until: Long) {
        tapWatchUntil = maxOf(tapWatchUntil, until)
        if (tapWatch?.isActive == true) return
        tapWatch = spillScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val collecting = launch(start = CoroutineStart.UNDISPATCHED) {
                screen.a11y.eventsFor(Display.DEFAULT_DISPLAY).collect { e ->
                    val systemTouch = e.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                    if (MainScreenSpill.isUserTap(e.eventType) &&
                        // Mika 浮层自己的控件点击不是用户打开了别的 App;系统触摸事件没有这种包名歧义
                        (systemTouch || e.packageName?.toString() != screen.a11y.packageName)
                    ) {
                        // 系统触摸事件带有真实发生时刻;通知栏、桌面等不发控件点击事件的入口也不能漏掉
                        val at = if (systemTouch) e.eventTime else SystemClock.uptimeMillis()
                        userTaps.addLast(MainScreenSpill.Tap(at, e.packageName?.toString()))
                        while (userTaps.size > AndroidScreen.MAX_USER_TAPS) userTaps.pollFirst()
                    }
                }
            }
            while (true) {
                val left = tapWatchUntil - SystemClock.uptimeMillis()
                if (left <= 0) break
                delay(left)
            }
            collecting.cancel()
        }
    }

    /**
     * 动作之后:问内置助手这次动作开始后主屏冒出来的任务,按 [MainScreenSpill] 判;开到主屏上的挪回 Mika 的屏,
     * 开在用户正用着的 App 里的只报不动。没有为 null。
     */
    internal suspend fun takeSpill(w: Watch?): Spill? {
        if (w == null || screen.handedOver()) return null
        val b = runCatching { screen.shell.mainArrivals(maxOf(w.since, consumed + 1)) }.getOrNull() ?: return null
        val arrivals = parseArrivals(b)
        arrivals.maxOfOrNull { it.at }?.let { consumed = maxOf(consumed, it) }
        var last: Spill? = null
        for (v in MainScreenSpill.decide(arrivals, w.since, AndroidScreen.SPILL_WINDOW_MS, w.topBefore, notSpills, userTaps.toList(), homeApps)) {
            val a = v.arrival
            val pkg = a.packageName ?: continue
            // 开到用户主屏上的不在应用访问边界看的那块屏上:被禁用的不挪、不读
            screen.appAccess.current().denial(pkg)?.let { throw it }
            last = when (v) {
                // 系统问打开哪一个的先按选择框处理;要用户亲手处理的弹窗(登录验证、账号确认一类)留在主屏,不挪到 Mika 的屏上
                is MainScreenSpill.PullBack -> leaveForUser(a, pkg, w) ?: leavePopup(a, pkg) ?: pullBack(a, pkg) ?: last
                is MainScreenSpill.InUse -> Spill(OpenedElsewhere(pkg, screen.appLabel(pkg), pulledBack = false, inProfile = inProfileOf(pkg, a.userId)), null)
            }
        }
        if (last != null) Log.i(AndroidScreen.TAG, "opened on the main screen after an action: ${last.opened}")
        return last
    }

    /**
     * 上一次动作的盯梢还没过期时再问一次(那个动作过了一会儿才开出来的);过期了就清掉。
     * 用户在主屏上选完、处理完之后要说的,先说这个。
     */
    internal suspend fun lateSpill(): Spill? {
        pendingReport?.let { pendingReport = null; return it }
        val w = watch ?: return null
        val spill = takeSpill(w)
        if (SystemClock.uptimeMillis() > w.since + AndroidScreen.SPILL_WINDOW_MS) watch = null
        return spill
    }

    /**
     * 冒到主屏上的是要用户亲手处理的弹窗(登录验证、账号确认、付款确认一类):不挪,留在用户屏上。
     * 判据:系统账号登录服务(登记了账号认证器、没有桌面图标的包)的页面,顶层窗口不是整屏(对话框、底部弹层),窗口里有密码框,或页面本身不进最近任务(excludeFromRecents)。
     * 这类窗口挪到 Mika 的屏上就从用户眼前消失,用户也就没法在主屏上点确认。不是为 null。
     * noHistory 不算:很多 App 的开屏页带着它,算进来就把普通 App 当成了弹窗。
     */
    internal suspend fun leavePopup(a: MainScreenSpill.Arrival, pkg: String): Spill? =
        if (needsUserWindow(a, pkg)) popup(a, pkg) else null

    /** 主屏上这个任务的顶层窗口是不是一个要用户亲手处理的弹窗(见 [leavePopup])。最多多等两次 [AndroidScreen.POPUP_SETTLE_MS]。 */
    internal suspend fun needsUserWindow(a: MainScreenSpill.Arrival, pkg: String): Boolean {
        if (isAuthenticatorUi(pkg)) return true
        val activity = a.topActivity?.let {
            runCatching { screen.a11y.packageManager.getActivityInfo(ComponentName(pkg, it), 0) }.getOrNull()
        }
        if (activity != null && activity.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0) return true
        val metrics = screen.a11y.resources.displayMetrics
        // 刚冒出来的窗口可能还在开场动画里(画得比整屏小),等它停稳再看一次,免得把普通 App 当成弹窗
        var window = topWindowOf(pkg)
        if (window == null) {
            delay(AndroidScreen.POPUP_SETTLE_MS)
            window = topWindowOf(pkg) ?: return false
        }
        if (isNarrow(window, metrics)) {
            delay(AndroidScreen.POPUP_SETTLE_MS)
            val settled = topWindowOf(pkg) ?: return false
            if (isNarrow(settled, metrics)) return true
            window = settled
        }
        return ScreenReader.read(listOf(window), metrics.widthPixels, metrics.heightPixels).passwordField
    }

    internal fun topWindowOf(pkg: String): AccessibilityWindowInfo? =
        ScreenReader.contentWindows(screen.a11y.windowsOn(Display.DEFAULT_DISPLAY)).firstOrNull { it.root?.packageName?.toString() == pkg }

    /**
     * 这个包是不是系统的账号登录服务:登记了账号认证器(AccountManager.authenticatorTypes,平台数据),自己又没有桌面图标
     * (没有 LAUNCHER 入口)。这种包没有自己的首页,冒到主屏上的只会是加账号、登录、验证、验证码一类页面,本身就是用户的事,
     * 整屏、没有密码框也不能挪到 Mika 的屏上。有桌面图标的认证器包(同步联系人的聊天 App、邮箱 App 等)不算,
     * 它们的会话、分享、写信页照常挪回。认证器表每次现读:新装的认证器也认得出,这一步只在有页面冒到主屏时才走。
     */
    internal fun isAuthenticatorUi(pkg: String): Boolean {
        val authenticators = runCatching { AccountManager.get(screen.a11y).authenticatorTypes.mapNotNull { it.packageName }.toSet() }
            .getOrDefault(emptySet())
        if (pkg !in authenticators) return false
        // 有桌面图标的包对 Mika 一定看得见(清单里查了 LAUNCHER);查不到才算没有
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val hasLauncher = runCatching { screen.a11y.packageManager.queryIntentActivities(launcher, 0).isNotEmpty() }.getOrDefault(true)
        return MainScreenSpill.authenticatorUi(pkg, authenticators, hasLauncher)
    }

    /** 窗口没有遮住整块屏:对话框、底部弹层、悬浮窗一类。 */
    internal fun isNarrow(window: AccessibilityWindowInfo, metrics: android.util.DisplayMetrics): Boolean {
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        return bounds.width() < metrics.widthPixels * AndroidScreen.FULL_SCREEN_SHARE || bounds.height() < metrics.heightPixels * AndroidScreen.FULL_SCREEN_SHARE
    }

    internal suspend fun popup(a: MainScreenSpill.Arrival, pkg: String): Spill =
        Spill(OpenedElsewhere(pkg, screen.appLabel(pkg), pulledBack = false, inProfile = inProfileOf(pkg, a.userId), needsUser = true), null)

    /**
     * 开到主屏上的是系统问「用哪一个打开」的窗口(系统的选择器,或系统包的窗口里的选项对得上装了两份的 App;
     * 各系统的选择框不一定是原版那个):不挪,留给用户在自己屏上选,记下来让任务等着。不是为 null。
     */
    internal suspend fun leaveForUser(a: MainScreenSpill.Arrival, pkg: String, w: Watch): Spill? {
        val system = screen.isSystemPackage(pkg)
        val choice = if (system) mainChooser(pkg) else null
        if (MainScreenSpill.handling(system, pkg in screen.systemPickers, choice != null) != MainScreenSpill.Handling.LeaveForUser) return null
        val app = choice?.options?.firstOrNull()?.label ?: screen.appLabel(pkg)
        _userPick.value = AndroidScreen.UserPick(a.taskId, pkg, app, w.topBefore, w.agentTopBefore, a.at)
        return Spill(OpenedElsewhere(pkg, app, pulledBack = false, picker = true), choice)
    }

    /** 把开到主屏上的任务挪到 Mika 的屏(只挪,不替它重新打开),等它到最上面;挪不动为 null。 */
    internal suspend fun pullBack(a: MainScreenSpill.Arrival, pkg: String, byUser: Boolean = false): Spill? {
        if (!runCatching { screen.shell.moveTask(a.taskId, screen.displayId) }.getOrDefault(false)) {
            Log.w(AndroidScreen.TAG, "could not move task ${a.taskId} ($pkg) from the main screen")
            return null
        }
        adopted += a.taskId
        // 事先不知道会开出哪个 App:挪回来后再占;占不到也不撤回
        if (screen.checkOtherTasks) screen.reserve(pkg)
        val deadline = SystemClock.uptimeMillis() + AndroidScreen.LAUNCH_WAIT_MS
        while (runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1) != a.taskId && SystemClock.uptimeMillis() < deadline) delay(AndroidScreen.POLL_MS)
        screen.waitStable(AndroidScreen.SETTLE_CAP_MS)
        val profile = if (screen.isSystemPackage(pkg)) null else inProfileOf(pkg, a.userId)
        return Spill(OpenedElsewhere(pkg, screen.appLabel(pkg), pulledBack = true, inProfile = profile, byUser = byUser), null)
    }

    /**
     * 主屏上这个系统包的窗口是不是在问打开哪一个:事先不知道是为哪个 App 问的,拿装了两份的 App 的名字去对窗口里的选项
     * (同一个 App 装了两份正是会这样问的情形);只读这个系统窗口,对不上为 null。
     */
    internal suspend fun mainChooser(pkg: String): LaunchChoice? {
        val w = ScreenReader.contentWindows(screen.a11y.windowsOn(Display.DEFAULT_DISPLAY)).firstOrNull { it.root?.packageName?.toString() == pkg } ?: return null
        val elements = ScreenReader.read(listOf(w), screen.display.width, screen.display.height).entries.map { it.element }
        for ((app, names) in screen.otherProfiles()) {
            val label = screen.appLabel(app) ?: continue
            LaunchChooser.options(label, elements, names)?.let { return LaunchChoice(pkg, it) }
        }
        return null
    }

    /**
     * 任务在下一个改动屏幕的动作前调:等用户在主屏上选完(选之前一直盯着主屏)。选完后,开到主屏上的普通页面挪回 Mika 的屏
     * (只挪,打开它的仍是原来那个 App);要用户亲手处理的(要输密码、窗口读不到,像付款、指纹)留在主屏,返回 [AndroidScreen.PickResult.NeedsUser];
     * 开在了 Mika 屏上的一并算进来;用户把问题关了就照实说。没在等时为 null。
     */
    suspend fun awaitUserPick(): AndroidScreen.PickResult? = withContext(Dispatchers.IO) {
        val p = _userPick.value ?: return@withContext null
        try {
            while (runCatching { screen.shell.displayOfTask(p.taskId) }.getOrDefault(-1) == Display.DEFAULT_DISPLAY) {
                runCatching { screen.shell.watchMainScreen(AndroidScreen.SPILL_WINDOW_MS.toInt()) }
                delay(AndroidScreen.PICK_POLL_MS)
            }
            val gone = SystemClock.uptimeMillis()
            runCatching { screen.shell.watchMainScreen(AndroidScreen.SPILL_WINDOW_MS.toInt()) }
            val deadline = gone + AndroidScreen.PICK_WAIT_MS
            while (true) {
                val arrivals = runCatching { screen.shell.mainArrivals(p.since) }.getOrNull()?.let(::parseArrivals).orEmpty()
                val opened = MainScreenSpill.picked(arrivals, p.taskId, gone, AndroidScreen.PICK_SLACK_MS, AndroidScreen.PICK_WAIT_MS, p.topBefore, notSpills, homeApps)
                arrivals.maxOfOrNull { it.at }?.let { consumed = maxOf(consumed, it) }
                if (opened != null) {
                    val pkg = opened.packageName!!
                    if (needsUser(pkg) || needsUserWindow(opened, pkg)) {
                        return@withContext AndroidScreen.PickResult.NeedsUser(opened.taskId, pkg, screen.appLabel(pkg) ?: p.app, opened.userId)
                    }
                    pendingReport = pullBack(opened, pkg)
                    return@withContext AndroidScreen.PickResult.Settled
                }
                // 选中的 App 开在了 Mika 的屏上(从那边打开的)
                val top = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
                if (top >= 0 && top != p.agentTop && top !in adopted) {
                    adopted += top
                    screen.waitStable(AndroidScreen.SETTLE_CAP_MS)
                    val pkg = runCatching { screen.shell.packageOfTask(top) }.getOrNull() ?: p.packageName
                    val profile = inProfileOf(pkg, runCatching { screen.shell.userOfTask(top) }.getOrDefault(-1))
                    pendingReport = Spill(OpenedElsewhere(pkg, screen.appLabel(pkg), pulledBack = true, inProfile = profile), null)
                    return@withContext AndroidScreen.PickResult.Settled
                }
                if (SystemClock.uptimeMillis() >= deadline) break
                delay(AndroidScreen.POLL_MS)
            }
            pendingReport = Spill(OpenedElsewhere(p.packageName, p.app, pulledBack = false, picker = true, closed = true), null)
            AndroidScreen.PickResult.Settled
        } finally {
            _userPick.value = null
        }
    }

    /** 用户处理完([AndroidScreen.PickResult.NeedsUser])点了继续:它还在主屏上就挪回 Mika 的屏接着做,已经关了就照实说。 */
    suspend fun afterUserStep(r: AndroidScreen.PickResult.NeedsUser) = withContext(Dispatchers.IO) {
        val still = runCatching { screen.shell.displayOfTask(r.taskId) }.getOrDefault(-1) == Display.DEFAULT_DISPLAY
        val moved = if (still) pullBack(MainScreenSpill.Arrival(r.taskId, r.packageName, r.userId, 0, Display.DEFAULT_DISPLAY, false, true, Display.DEFAULT_DISPLAY), r.packageName, byUser = true) else null
        pendingReport = moved ?: Spill(OpenedElsewhere(r.packageName, r.app, pulledBack = false, byUser = true), null)
    }

    /**
     * 主屏上这个 App 的窗口要用户亲手处理:有密码框,或树里读不出东西而截主屏时系统报防截屏窗口(截到的画面不留,马上丢掉)。
     * 只看有没有,不读里面写了什么。
     */
    internal suspend fun needsUser(pkg: String): Boolean {
        // 读的是用户主屏上的窗口,应用访问边界看不到:被禁用的不读
        screen.appAccess.current().denial(pkg)?.let { throw it }
        // 刚上来的窗口要一会儿才读得到:读得到内容(或看到密码框)就下结论,到时限还读不到再看是不是防截屏
        val deadline = SystemClock.uptimeMillis() + AndroidScreen.PICK_READ_MS
        while (true) {
            val mine = ScreenReader.contentWindows(screen.a11y.windowsOn(Display.DEFAULT_DISPLAY)).filter { it.root?.packageName?.toString() == pkg }
            val r = mine.takeIf { it.isNotEmpty() }?.let { ScreenReader.read(it, screen.display.width, screen.display.height) }
            val readable = r?.entries?.any { it.element.meaningful } == true
            if (r?.passwordField == true || readable) return MainScreenSpill.needsUser(r?.passwordField == true, readable, secureWindow = false)
            if (SystemClock.uptimeMillis() >= deadline) {
                val shot = runCatching { screen.a11y.takeMainScreenshot() }.getOrNull()
                (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
                return MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = shot is ScreenAccessibility.MainShot.Secure)
            }
            delay(AndroidScreen.POLL_MS * 2)
        }
    }

    /**
     * 动作之前 Mika 屏上最上面的是挪回来的任务(或从它里面打开的),动作之后最上面换成了另一个任务:
     * 这个也是那条路上打开的,一并算进来。装了两份的 App 报开的是哪一份。
     */
    internal suspend fun adoptFromAdopted(w: Watch?): String? {
        if (w == null || w.agentTopBefore !in adopted) return null
        val top = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        if (top < 0 || top == w.agentTopBefore || top in adopted || top == screen._target.value?.taskId) return null
        adopted += top
        val pkg = runCatching { screen.shell.packageOfTask(top) }.getOrNull() ?: return null
        return inProfileOf(pkg, runCatching { screen.shell.userOfTask(top) }.getOrDefault(-1))
    }

    /** 装了两份的 App 开的是哪一份:和 Mika 同一个用户的是本来那份;只装了一份或看不出为 null。 */
    internal suspend fun inProfileOf(pkg: String, userId: Int): String? {
        if (userId < 0 || pkg !in screen.otherProfiles()) return null
        return if (userId == AndroidScreen.myUser) OpenedElsewhere.MAIN_PROFILE else OpenedElsewhere.OTHER_PROFILE
    }

    internal fun parseArrivals(b: Bundle): List<MainScreenSpill.Arrival> {
        val tasks = b.getIntArray(MainScreenWatch.KEY_TASK) ?: return emptyList()
        val packages = b.getStringArray(MainScreenWatch.KEY_PACKAGE)
        val users = b.getIntArray(MainScreenWatch.KEY_USER)
        val at = b.getLongArray(MainScreenWatch.KEY_AT)
        val from = b.getIntArray(MainScreenWatch.KEY_FROM)
        val same = b.getBooleanArray(MainScreenWatch.KEY_SAME_TASK)
        val standard = b.getBooleanArray(MainScreenWatch.KEY_STANDARD)
        val activity = b.getStringArray(MainScreenWatch.KEY_ACTIVITY)
        val now = b.getIntArray(MainScreenWatch.KEY_NOW)
        if (packages == null || users == null || at == null || from == null || same == null || standard == null || now == null) return emptyList()
        return tasks.indices.map { i ->
            MainScreenSpill.Arrival(tasks[i], packages[i], users[i], at[i], from[i], same[i], standard[i], now[i], activity?.getOrNull(i))
        }
    }

    internal fun hintLateSpill(o: OpenedElsewhere): String {
        val name = "${o.app ?: o.packageName} (${o.packageName})"
        val copy = when (o.inProfile) {
            OpenedElsewhere.OTHER_PROFILE -> " It is the other copy of the app (clone or other profile): if the user did not ask for that copy, go back and pick the other option."
            OpenedElsewhere.MAIN_PROFILE -> " It is the main copy of the app."
            else -> ""
        }
        return when {
            o.closed -> "The user closed the system's question on their screen without picking, so nothing was opened. Decide what to do next; do not simply repeat the action."
            o.byUser && !o.pulledBack -> "$name needed the user; they handled it on their own screen and tapped continue, and it has closed. Observe before acting."
            o.byUser -> "$name needed the user; they handled it on their own screen and tapped continue. It is now in front on Mika's screen. Observe before acting."
            o.needsUser && o.onUserScreen -> "It was $name: a popup on the user's screen that needs them (a sign-in, verification or confirmation step). " +
                "It was left there on purpose; do not repeat the action. Call hand_to_user so they can finish it, then observe before acting."
            o.pulledBack -> "It was $name, now in front on Mika's screen.$copy Observe before acting."
            else -> "It was $name, which the user is using on their own screen, so it was left there. " +
                "Do not repeat the previous action: call hand_to_user with reason other so the user can do that step there, or wait and try again later."
        }
    }
}
