package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.wangzan101.mika.core.shell.DevtoolsSocket
import com.wangzan101.mika.core.uistate.AppCopies
import com.wangzan101.mika.core.uistate.PressKeys
import java.io.File
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import com.wangzan101.mika.shell.IMikaShell

/**
 * 以 shell 身份(uid 2000)常驻,持有 Agent 的虚拟屏,跑在内置助手进程([HelperMain])里。
 * 进程结束时虚拟屏及其上的 App 一并销毁,所以必须常驻而不是一次性命令。
 *
 * 隐藏接口一律反射调用,反射失败时退回到同等效果的 shell 命令。
 */
class ShellService(
    /** 只接受这个 uid(Mika)的调用。 */
    private val allowedUid: Int,
    /** 静音记录放的目录;shell 身份能写的位置。 */
    stateDir: File = File("/data/local/tmp"),
) : IMikaShell.Stub() {
    private val context: Context = ShellContext.create()
    private val displays = HashMap<Int, VirtualDisplay>()
    private var orientationWatch: DisplayOrientationWatch? = null
    private val requestedOrientations = ConcurrentHashMap<Int, Int>()
    /** 被静音的包 → 静音前的 PLAY_AUDIO 模式("default" 表示原先未设置),落在 [stateDir] 里,进程被杀后下次启动接着恢复。 */
    private val muted = AudioMuteLedger(File(stateDir, "mika_audio_mute_$allowedUid"))
    private val callAudio = ShellCallAudio(context)
    // Bundle 将嵌套的 PFD 复制到 reply，但不会关掉服务端原件。写完 reply 后关闭，
    // 否则客户端关掉管道时，音频线程永远收不到 EOF。
    private val returnedCallPipes = ThreadLocal<List<ParcelFileDescriptor>>()
    private data class FilePolicyState(val session: String, val revision: Long, val roots: List<String>, val recursive: List<Boolean>, val policy: DeviceFilePolicy)
    private val filePolicyLock = Any()
    @Volatile private var filePolicy: FilePolicyState? = null
    /** Mika 所在的用户:本来那份 App 装在这里;别的用户里的是另一份(分身、工作资料)。 */
    private val myUser = allowedUid / PER_USER_RANGE

    // 不能用 getSystemService:那样拿到的 DisplayManager 绑的是系统上下文,包名与 shell uid 对不上
    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    // 剪贴板要以 shell 包名访问(系统只对来自 shell 的剪贴板认「不弹已复制浮层」的标记);读取绕开 ClipboardManager 里 shell 进程走不通的 Settings 查询
    private val clipboard = ShellClipboard(context)
    private val clipLock = Any()
    /** 查占用和启动之间不能插进另一个任务的启动:两个任务同时开同一个 App 时只有一个能成。 */
    private val launchLock = Any()
    /** setClipText 之前用户的剪贴板(null 表示原先确实是空的,读不到的不会换出);[clipSaved] 为 false 表示没有待还原的。 */
    private var savedClip: ClipData? = null
    private var clipSaved = false
    /** Mika 最后放进去的文字;还原前据此判断剪贴板还是不是 Mika 的(用户中途复制了别的就不动)。 */
    private var ourText: String? = null

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        createVirtualDisplay(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        if (!retryWithoutTrust(t)) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        } else {
            // shell 没有 ADD_TRUSTED_DISPLAY 时(有的系统不给):去掉只对可信屏可用的几项再建一次。
            // 退回后这块屏没有自己的焦点,自检页读它的标志会报「own focus no」并给出主屏焦点可能受影响的提示(Advice.FocusShared)。
            Log.w(TAG, "createDisplay without trusted display flags, retrying", t)
            try {
                createVirtualDisplay(surface, width, height, dpi, trusted = false)
            } catch (t: Throwable) {
                Log.e(TAG, "createDisplay failed", t)
                -1
            }
        }
    }

    private fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean): Int {
        val flags = displayFlags(Build.VERSION.SDK_INT, trusted)
        val vd = displayManager.createVirtualDisplay("mika-agent", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setFullscreenWindowingMode(id)
        ensureOrientationWatch()
        setImePolicy(id)
        if (DisplayAwake.needed(flags, FLAG_OWN_DISPLAY_GROUP, displayGroup(id))) awake.start(id)
        return id
    }

    /** 自成一组的虚拟屏一直不睡,见 [DisplayAwake]。 */
    private val awake by lazy {
        val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "mika-display-awake").apply { isDaemon = true } }
        DisplayAwake(::pokeDisplay) { r ->
            val f = timer.scheduleWithFixedDelay(r, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
            return@DisplayAwake { f.cancel(false) }
        }
    }

    /** IPowerManager 和它按屏报用户操作的方法;拿不到为 null(只记一次,屏照常用,只是没人替它续亮)。 */
    @get:SuppressLint("PrivateApi")
    private val userActivity: Pair<Any, Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "power")
            val power = Class.forName("android.os.IPowerManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
            power to (DisplayAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

    private fun pokeDisplay(displayId: Int): Boolean {
        val (power, method) = userActivity ?: return false
        return try {
            method.invoke(power, displayId, SystemClock.uptimeMillis(), DisplayAwake.EVENT_TOUCH, 0)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "userActivity failed, display keep-awake off", t)
            false
        }
    }

    /** 这块屏所在的显示组(DisplayInfo.displayGroupId,隐藏字段);读不到为 null。 */
    @SuppressLint("PrivateApi")
    private fun displayGroup(displayId: Int): Int? = runCatching {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(global, displayId)!!
        info.javaClass.getField("displayGroupId").getInt(info)
    }.getOrNull()

    /** 只往这个服务自己建的虚拟屏上启动,调用方传错屏号(比如主屏)也不会打扰用户。 */
    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    override fun releaseDisplay(displayId: Int) {
        awake.stop(displayId)
        // 锁里只摘下来,关监听(binder)和放屏在锁外做
        val (vd, watch) = synchronized(displays) {
            displays.remove(displayId) to (if (displays.isEmpty()) orientationWatch.also { orientationWatch = null } else null)
        }
        watch?.close()
        if (watch != null) requestedOrientations.clear()
        vd?.release()
    }

    /**
     * STEAL_TOP_FOCUS_DISABLED 保住主屏焦点,但有的系统因此不再采纳后台 Activity 的转屏请求。
     * 有 Mika 的屏时开一个方向监听,只纠正自己这块屏的方向,保留原来的焦点隔离。
     * 监听在锁外建(注册要走 binder);两次建屏同时进来时只留一个,多出来的关掉。
     */
    private fun ensureOrientationWatch() {
        if (Build.VERSION.SDK_INT < 34 || synchronized(displays) { orientationWatch != null }) return
        val watch = runCatching {
            DisplayOrientationWatch(
                taskManager,
                active = { synchronized(displays) { displays.isNotEmpty() } },
                onRequestedOrientation = { taskId, orientation -> requestedOrientations[taskId] = orientation },
                onTaskRemoved = { taskId -> requestedOrientations.remove(taskId) },
                sync = ::syncDisplayOrientations,
            )
        }.onFailure { Log.w(TAG, "display orientation watcher unavailable", it) }.getOrNull() ?: return
        val spare = synchronized(displays) {
            if (orientationWatch == null && displays.isNotEmpty()) { orientationWatch = watch; null } else watch
        }
        spare?.close()
    }

    /**
     * 按各屏最上面任务的应用窗口比例纠正屏方向。锁里只拍一份屏的快照;
     * 查任务、读字段、freezeDisplayRotation 和退回的 `wm` 命令都在锁外,不挡建屏、放屏和启动。
     */
    @SuppressLint("PrivateApi")
    private fun syncDisplayOrientations() {
        val ours = synchronized(displays) { displays.toMap() }
        if (ours.isEmpty()) return
        val top = allTasks().groupBy { TASK_INFO.getField("displayId").getInt(it) }
        for ((id, vd) in ours) runCatching {
            val task = top[id]?.firstOrNull() ?: return@runCatching
            val taskId = TASK_INFO.getField("taskId").getInt(task)
            val compat = runCatching { TASK_INFO.getField("appCompatTaskInfo").get(task) }.getOrNull() ?: task
            val cls = compat.javaClass
            // 安卓 15 起另有明确的 letterbox 标志;14 的宽高只在部分 letterbox 模式下填充。
            if (runCatching { cls.getField("topActivityBoundsLetterboxed").getBoolean(compat) }.getOrDefault(true).not()) return@runCatching
            val width = runCatching { cls.getField("topActivityLetterboxWidth").getInt(compat) }.getOrDefault(0)
            val height = runCatching { cls.getField("topActivityLetterboxHeight").getInt(compat) }.getOrDefault(0)
            val display = vd.display
            val mode = display.mode
            val rotation = DisplayOrientation.rotation(
                mode.physicalWidth, mode.physicalHeight, display.rotation, width, height,
                requestedOrientations[taskId] ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ) ?: return@runCatching
            // 快照之后这块屏可能已经放掉了
            if (!ownDisplay(id)) return@runCatching
            val ok = runCatching {
                val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
                val method = wm.javaClass.methods.first { it.name == "freezeDisplayRotation" }
                if (method.parameterTypes.size == 3) method.invoke(wm, id, rotation, "mika") else method.invoke(wm, id, rotation)
            }.isSuccess
            if (!ok) exec("wm", "user-rotation", "-d", "$id", "lock", "$rotation")
        }.onFailure { Log.w(TAG, "orientation sync failed on display $id", it) }
    }

    /**
     * 先确认用户没在主屏用这个 App:若它在主屏有可见的任务就不启动(返回 [START_IN_USE_ON_MAIN]),
     * 否则系统会把用户正在看的界面挪走或打断。后台任务可以被拉到虚拟屏上。
     * 再解析启动入口(包里有些 Activity 是跳板,直接启动会立刻跳走、只在虚拟屏留下空任务),
     * 以 shell 包名调用 IActivityTaskManager.startActivityAsUser,带 ActivityOptions.setLaunchDisplayId。
     * 占用只看本来那份(Mika 所在用户里的):用户在主屏用着装在别的用户里的那份时,不挡这一份。
     */
    override fun startApp(displayId: Int, packageName: String): Int = start(displayId, packageName, myUser)

    /**
     * 在 [userId] 那份用户里启动 [packageName](见 [startApp]);占用只看这一份(包名 + 用户)。
     * [userId] 是 Mika 自己的用户时按原来的方式启动(USER_CURRENT、包管理器给的入口);别的用户先在那份用户里查启动入口,
     * 再以那份用户启动,接口不可用时退回 `am start --user`。
     */
    private fun start(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        try {
            if (inUseOnMainScreen(packageName, userId)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(listOf(packageName), displayId, userId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            // 看不清主屏状态时宁可不启动,也不冒险打扰用户
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        // 上次任务留下的那个任务还在主屏后面:整个挪回来,接着那一页做,不另开一个从头来
        if (resumeKept(displayId, packageName, userId)) return START_OK
        val intent = launchIntent(packageName, userId) ?: return START_NOT_FOUND
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        // 另一份用户里的先走桌面打开图标的那条路:有的系统对程序启动的另一份先弹「用哪一个打开」,桌面这条路不弹
        val component = intent.component
        if (userId != myUser && component != null && startAsLauncher(component, userId, options)) return START_OK
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho,
            //  requestCode, flags, profilerInfo, options, userId)
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, if (userId == myUser) USER_CURRENT else userId,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser($packageName, user $userId) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
            val user = if (userId == myUser) emptyArray() else arrayOf("--user", "$userId")
            exec("am", "start", *user, "--display", "$displayId", "-n", component) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /** ILauncherApps(LauncherApps 背后的系统服务);拿不到为 null。 */
    private val launcherApps: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "launcherapps")
            Class.forName("android.content.pm.ILauncherApps\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        }.onFailure { Log.w(TAG, "launcherapps service unavailable", it) }.getOrNull()
    }

    /**
     * 像桌面那样打开另一份用户 [userId] 里的启动页 [component]:ILauncherApps.startActivityAsUser(LauncherApps.startMainActivity
     * 背后的接口),带同样的 [options](启动到 Mika 的屏)。系统那边按调用方查能不能碰那份用户:shell 有跨用户的权限,
     * 包名也对得上自己的 uid。接口不在、或被拒(抛错)返回 false,调用方改走 startActivityAsUser。
     */
    private fun startAsLauncher(component: ComponentName, userId: Int, options: Bundle): Boolean = try {
        val la = launcherApps ?: throw IllegalStateException("no launcherapps service")
        val user = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        // 安卓 11 起:(caller, callingPackage, callingFeatureId, component, sourceBounds, opts, user);更早没有 callingFeatureId
        val m = la.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size in 6..7 && ComponentName::class.java in it.parameterTypes }
        val args: Array<Any?> = if (m.parameterTypes.size == 7) arrayOf(null, ShellContext.SHELL_PACKAGE, null, component, null, options, user)
        else arrayOf(null, ShellContext.SHELL_PACKAGE, component, null, options, user)
        m.invoke(la, *args)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "launcher-style start of ${component.flattenToShortString()} in user $userId failed", t)
        false
    }

    /**
     * 打开 [packageName] 用的 intent:Mika 自己的用户里就是包管理器给的入口;别的用户里查那份用户的启动页
     * (隐藏接口 queryIntentActivitiesAsUser,不行改问 `cmd package query-activities --user`)。查不到为 null。
     */
    @SuppressLint("PrivateApi")
    private fun launchIntent(packageName: String, userId: Int): Intent? {
        if (userId == myUser) return context.packageManager.getLaunchIntentForPackage(packageName)
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val found = try {
            val pm = context.packageManager
            val m = pm.javaClass.getMethod("queryIntentActivitiesAsUser", Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            (m.invoke(pm, query, 0, userId) as List<*>).filterIsInstance<ResolveInfo>().map { it.activityInfo.packageName to it.activityInfo.name }
        } catch (t: Throwable) {
            Log.w(TAG, "queryIntentActivitiesAsUser failed, asking the package service", t)
            val args = ActivityQuery.args(Intent.ACTION_MAIN, category = Intent.CATEGORY_LAUNCHER, packageName = packageName, user = "$userId")
            sh(*args.toTypedArray()).takeIf { it.first == 0 }?.second?.let(ActivityQuery::parse)
        }
        val (pkg, cls) = found?.firstOrNull { it.first == packageName } ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(pkg, cls))
    }

    /** 这些包里有在 Mika 另一块屏(不是 [displayId])上的任务:那是别的任务正在用的 App。[userId] 不为空时只看那份用户里的。 */
    private fun onOtherOwnDisplay(packages: Collection<String>, displayId: Int, userId: Int? = null): Boolean {
        val others = synchronized(displays) { displays.keys.toSet() } - displayId
        if (others.isEmpty()) return false
        val displayField = TASK_INFO.getField("displayId")
        return allTasks().any { t -> displayField.getInt(t) in others && packages.any { isCopy(t, it, userId) } }
    }

    /** 任务是 [packageName] 在 [userId] 那份用户里的那份(规则见 [sameCopy])。 */
    private fun isCopy(task: Any, packageName: String, userId: Int?): Boolean =
        sameCopy(packagesOf(task), runCatching { TASK_INFO.getField("userId").getInt(task) }.getOrNull(), packageName, userId)

    /**
     * 只有这个 App 的任务全在 Mika 的屏上时才结束进程再启动(回到初始页面)。
     * 它在别的屏上还有任务(用户的状态)就绝不结束,改为普通启动并返回 [START_OK_COLD_IGNORED];
     * 用户正在主屏用它时不启动。
     */
    override fun restartApp(displayId: Int, packageName: String): Int = restart(displayId, packageName, myUser)

    /** [restartApp] 作用在 [userId] 那份用户里的那份:只看这一份的任务、只结束这份用户里的进程,装在别的用户里的另一份不动。 */
    private fun restart(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
        // 不是自己建的屏(含主屏)一律不动,更不能先结束进程
        if (!ownDisplay(displayId)) return START_ERROR
        val decision = try {
            // 别的任务正在 Mika 另一块屏上用它:绝不结束它的进程
            if (onOtherOwnDisplay(listOf(packageName), displayId, userId)) return START_IN_USE_BY_OTHER
            // 要从头开:上次任务留在主屏后面的那份是 Mika 自己留下的,先关掉,不算用户的状态
            dropKept(packageName, userId)
            val displayField = TASK_INFO.getField("displayId")
            val taskDisplays = allTasks().filter { isCopy(it, packageName, userId) }.map { displayField.getInt(it) }
            ColdStart.decide(taskDisplays, displayId, inUseOnMainScreen(packageName, userId))
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect tasks", t)
            return START_ERROR
        }
        if (launchIntent(packageName, userId) == null) return START_NOT_FOUND
        when (decision) {
            ColdStart.IN_USE -> START_IN_USE_ON_MAIN
            ColdStart.WARM -> start(displayId, packageName, userId).let { if (it == START_OK) START_OK_COLD_IGNORED else it }
            ColdStart.RESTART -> {
                val user = if (userId == myUser) "current" else "$userId"
                if (exec("am", "force-stop", "--user", user, packageName) != 0) return START_ERROR
                start(displayId, packageName, userId)
            }
        }
    }

    /** 在另一份用户里打开:只认 [appCopyUsers] 判为能用的那几份(私密空间、别人的用户一律不碰),规则见 [AppCopies]。 */
    override fun startAppInUser(displayId: Int, packageName: String, userId: Int, cold: Boolean): Int {
        if (userId == myUser) return if (cold) restartApp(displayId, packageName) else startApp(displayId, packageName)
        if (!ownDisplay(displayId)) return START_ERROR
        val usable = try {
            users()?.let { AppCopies.usable(myUser, it) }
        } catch (t: Throwable) {
            Log.e(TAG, "cannot read users", t)
            null
        } ?: return START_ERROR
        if (userId !in usable) {
            Log.w(TAG, "refused to start $packageName in user $userId")
            return START_USER_NOT_ALLOWED
        }
        return if (cold) restart(displayId, packageName, userId) else start(displayId, packageName, userId)
    }

    override fun appCopyUsers(): Bundle? = try {
        users()?.let { users ->
            Bundle().apply {
                putIntArray(KEY_COPY_USERS, AppCopies.usable(myUser, users).toIntArray())
                putString(KEY_COPY_SUMMARY, AppCopies.summary(myUser, users))
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "appCopyUsers failed", t)
        null
    }

    /**
     * 这台手机的用户,按安卓原版的 UserInfo / UserProperties 读(隐藏接口反射):标记、类型、资料组、是否在运行、安静模式、
     * 是否和当前用户共用媒体、是否对别的 App 隐藏。读不到用户列表为 null;单项读不到的留空(按不满足算,见 [AppCopies])。
     */
    @SuppressLint("PrivateApi")
    private fun users(): List<AppCopies.User>? {
        val um = runCatching { context.getSystemService(UserManager::class.java) }.getOrNull()
            ?: UserManager::class.java.getMethod("get", Context::class.java).invoke(null, context) as UserManager
        val list = (runCatching { um.javaClass.getMethod("getAliveUsers").invoke(um) }.getOrNull()
            ?: runCatching { um.javaClass.getMethod("getUsers").invoke(um) }.getOrNull()) as? List<*> ?: return null
        val info = Class.forName("android.content.pm.UserInfo")
        // 系统自己给标记起的名字(dumpsys user 用的同一个 UserInfo.flagsToString,含系统自定义的标记);反射不行时读 dumpsys user
        val flagsToString = runCatching { info.getMethod("flagsToString", Int::class.javaPrimitiveType) }.getOrNull()
        val dumped by lazy { sh("dumpsys", "user").takeIf { it.first == 0 }?.second?.let(AppCopies::parseDumpsys).orEmpty() }
        return list.filterNotNull().map { u ->
            val id = info.getField("id").getInt(u)
            val flags = info.getField("flags").getInt(u)
            val handle = UserHandle.getUserHandleForUid(id * PER_USER_RANGE)
            val props = runCatching { um.javaClass.getMethod("getUserProperties", UserHandle::class.java).invoke(um, handle) }.getOrNull()
            fun prop(name: String): Any? = props?.let { p -> runCatching { p.javaClass.getMethod(name).invoke(p) }.getOrNull() }
            val names = flagsToString?.let { m -> runCatching { (m.invoke(null, flags) as? String)?.let(::flagNames) }.getOrNull() }
                ?: dumped[id]?.flagNames
            val group = runCatching { info.getField("profileGroupId").getInt(u) }.getOrDefault(AppCopies.NO_PROFILE_GROUP)
            val running = runCatching { um.isUserRunning(handle) }.getOrDefault(false)
            AppCopies.User(
                id = id,
                flags = flags,
                type = runCatching { info.getField("userType").get(u) as? String }.getOrNull(),
                profileGroupId = group,
                running = running,
                unlocked = running && runCatching { um.isUserUnlocked(handle) }.getOrDefault(false),
                quiet = runCatching { um.isQuietModeEnabled(handle) }.getOrDefault(false),
                mediaShared = prop("isMediaSharedWithParent") as? Boolean,
                hidden = (prop("getProfileApiVisibility") as? Int)?.let { it == PROFILE_API_VISIBILITY_HIDDEN },
                flagNames = names,
                // dumpsys 里的 parentId 就是资料组(不是自己、也不是「不在组里」时才写);读到了一并带上
                parentId = group.takeIf { it != id && it != AppCopies.NO_PROFILE_GROUP } ?: if (flagsToString == null) dumped[id]?.parentId else null,
            )
        }
    }

    /** flagsToString 的结果(如 "CLONED_PROFILE|FULL|INITIALIZED",有的版本带花括号)拆成名字。 */
    private fun flagNames(s: String): List<String> = s.trim('{', '}', ' ').split('|', ' ', ',').map(String::trim).filter(String::isNotEmpty)

    /**
     * 打开链接或设置页。外壳这边再按 [ShellLinks] 查一次链接;解析不到返回 [START_UNRESOLVED]。
     * 能打开它的 App 在主屏有任务就不启动:带 NEW_TASK 的链接可能落进那个任务,出现在用户眼前。
     * 只有一个 App 能打开时直接指定到那个页面,免得系统另选。
     */
    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        ShellLinks.refuse(action, uri, packageName)?.let { return it }
        val intent = Intent(action ?: Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        uri?.let { intent.data = Uri.parse(it) }
        packageName?.let { intent.setPackage(it) }
        // 网页式链接只交给声明了可从链接打开的页面,不碰 App 的内部页面
        if (action == null) intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val candidates = activitiesFor(intent, null) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            val onMain = tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet()
            if (ShellLinks.inUse(candidates.map { it.first }.distinct(), onMain)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(candidates.map { it.first }.distinct(), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(link) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val target = intent.component?.let { listOf("-n", it.flattenToShortString()) }
                ?: (listOf("-a", intent.action!!) + listOfNotNull(uri?.let { "-d" }, uri) + listOfNotNull(packageName?.let { "-p" }, packageName) +
                    (if (action == null) listOf("-c", Intent.CATEGORY_BROWSABLE) else emptyList()))
            exec("am", "start", "--display", "$displayId", *target.toTypedArray()) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * 把文字分享给 [packageName]:ACTION_SEND text/plain 带 EXTRA_TEXT,只交给这个包。它没有收文字分享的页面返回 [START_UNRESOLVED];
     * 和 [startApp] 一样,用户在主屏用着它时不启动。只有一个收分享的页面时直接指定,有几个时由系统问用哪一个。
     */
    override fun startShare(displayId: Int, packageName: String, text: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        val intent = ShareIntent.of(packageName, text)
        val candidates = activitiesFor(intent, ShareIntent.TYPE) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(listOf(packageName), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, ShareIntent.TYPE, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(share) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            exec(*ShareIntent.amArgs(displayId, packageName, text, intent.component?.flattenToShortString()).toTypedArray()) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * 把一个工作区文件分享给 [packageName]:ACTION_SEND + EXTRA_STREAM,content URI 允许对方读,只交给这个包。
     * 它没有收这类分享的页面返回 [START_UNRESOLVED];用户在主屏用着它时不启动。只打开分享页,发不发由模型在那一页上点。
     */
    override fun startShareFile(displayId: Int, packageName: String, uri: String, mime: String, text: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        val target = Uri.parse(uri)
        val intent = ShareIntent.ofFile(packageName, target, mime, text)
        val candidates = activitiesFor(intent, mime) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(listOf(packageName), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, mime, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(share file) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            exec(*ShareIntent.amArgsFile(displayId, packageName, target, mime, text, intent.component?.flattenToShortString()).toTypedArray()) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * 系统动作(见 [SystemIntents]):只放行那几种 intent。没有页面接返回 [START_UNRESOLVED];和 [startView] 一样,
     * 接手的 App 在主屏有任务就不启动。先以 shell 身份启动;不成(接手的页面要求 shell 没有的权限,如设闹钟)时发出 Mika 做好的
     * [asMika]:以 Mika 的身份启动(权限按 Mika 查),屏和后台启动按发出的这一方(shell)查,仍落在这块屏上。
     */
    override fun startSystemAction(displayId: Int, intent: Intent, asMika: android.app.PendingIntent?): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        // Mika 自己的兼容性自检页:以 shell 身份直接开到这块屏
        if (intent.component?.className == SystemIntents.OWN_PAGE_CLASS) return startOwnPage(displayId, intent)
        if (!SystemIntents.allowed(intent.action, intent.dataString)) return START_INVALID_LINK
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val candidates = activitiesFor(intent, intent.type) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            val onMain = tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet()
            if (ShellLinks.inUse(candidates.map { it.first }.distinct(), onMain)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(candidates.map { it.first }.distinct(), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        val asShell = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, intent.type, null, null,
                0, 0, null, options.toBundle(), USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(${intent.action}) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser(${intent.action}) refused as shell", t)
            false
        }
        if (asShell) return START_OK
        if (asMika == null) return START_ERROR
        // 安卓 14 起 PendingIntent 的发出方要明说允许它从后台启动页面
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        return try {
            asMika.send(context, 0, null, null, null, null, options.toBundle())
            START_OK
        } catch (t: Throwable) {
            Log.w(TAG, "sending ${intent.action} as Mika failed", t)
            START_ERROR
        }
    }

    /**
     * 以 shell 身份把 Mika 的兼容性自检页([SystemIntents.OWN_PAGE_CLASS])开到这块屏,和 [startApp] 同一条路
     * (startActivityAsUser 带 setLaunchDisplayId,不行退回 `am start --display`)。那一页只对持有 INTERACT_ACROSS_USERS_FULL 的
     * 调用方开放(shell 有,普通 App 没有);shell 启动不受后台启动页面的限制。包名必须是 Mika 自己的,别的组件一律不开。
     */
    private fun startOwnPage(displayId: Int, intent: Intent): Int {
        val component = intent.component ?: return START_INVALID_LINK
        val mine = runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toList() }.getOrNull().orEmpty()
        // 只有 Mika 调得到这个服务;包名再核一次(查不到 Mika 的包名时只认类名)
        if (component.className != SystemIntents.OWN_PAGE_CLASS || (mine.isNotEmpty() && component.packageName !in mine)) return START_INVALID_LINK
        // 每次都从头开一页:旧的那一页连同它的任务清掉,新的一定在 Mika 屏的最上面
        val page = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, page, null, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(own page) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            exec("am", "start", "--display", "$displayId", "--activity-clear-task", "-n", component.flattenToShortString()) == 0
        }
        return if (ok) START_OK else START_ERROR
    }

    /**
     * 能接 [intent] 的页面,(包名, 完整类名)。系统接口在这一侧查不了时改问系统服务(见 [ActivityQuery]);都不行时为 null。
     * [type] 是 intent 的数据类型(命令里要单独给)。
     */
    private fun activitiesFor(intent: Intent, type: String?): List<Pair<String, String>>? = try {
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName to it.activityInfo.name }
    } catch (t: Throwable) {
        Log.w(TAG, "queryIntentActivities failed, asking the package service", t)
        val args = ActivityQuery.args(intent.action!!, type, intent.dataString, intent.categories?.firstOrNull(), intent.`package`)
        sh(*args.toTypedArray()).takeIf { it.first == 0 }?.second?.let(ActivityQuery::parse)
            .also { if (it == null) Log.e(TAG, "cannot resolve ${intent.action}") }
    }

    private fun packagesOf(task: Any): List<String> = listOf("baseActivity", "topActivity").mapNotNull {
        (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName
    }

    /** 该包在主屏有可见任务,或者就是主屏最上面的任务。[userId] 不为空时只看那份用户里的(见 [sameCopy])。 */
    private fun inUseOnMainScreen(packageName: String, userId: Int? = null): Boolean {
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        return tasksOn(Display.DEFAULT_DISPLAY).withIndex().any { (index, task) ->
            isCopy(task, packageName, userId) && (visibleField?.getBoolean(task) ?: (index == 0))
        }
    }

    /**
     * IActivityTaskManager.getTasks:按最近使用排序,shell 持有 REAL_GET_TASKS,能看到所有 App 的任务。
     * API 33+ 有带 displayId 的重载,优先用它;更早的版本取全部再按 TaskInfo.displayId 过滤。
     */
    private fun tasksOn(displayId: Int): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        val displayField = TASK_INFO.getField("displayId")
        return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
    }

    /** 所有屏上的任务:带屏号的重载传 INVALID_DISPLAY 即不按屏过滤。 */
    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        return tasks.filterNotNull()
    }

    private fun task(taskId: Int): Any? {
        val idField = TASK_INFO.getField("taskId")
        return allTasks().firstOrNull { idField.getInt(it) == taskId }
    }

    override fun displayOfTask(taskId: Int): Int = try {
        task(taskId)?.let { TASK_INFO.getField("displayId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfTask failed", t)
        -1
    }

    override fun displayOfPackage(packageName: String): Int = try {
        val ours = synchronized(displays) { displays.keys.toSet() }
        val displayField = TASK_INFO.getField("displayId")
        allTasks().filter { packageName in packagesOf(it) }.map { displayField.getInt(it) }.firstOrNull { it in ours } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfPackage failed", t)
        -1
    }

    override fun displayOfPackageInUser(packageName: String, userId: Int): Int = try {
        val ours = synchronized(displays) { displays.keys.toSet() }
        val displayField = TASK_INFO.getField("displayId")
        allTasks().filter { isCopy(it, packageName, userId) }.map { displayField.getInt(it) }.firstOrNull { it in ours } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfPackageInUser failed", t)
        -1
    }

    override fun packageOfTask(taskId: Int): String? = try {
        task(taskId)?.let { t ->
            listOf("topActivity", "baseActivity").firstNotNullOfOrNull { (TASK_INFO.getField(it).get(t) as? ComponentName)?.packageName }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "packageOfTask failed", t)
        null
    }

    override fun topTaskId(displayId: Int): Int = try {
        tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "topTaskId failed", t)
        -1
    }

    override fun topActivity(displayId: Int): String? = try {
        if (!ownDisplay(displayId)) null else tasksOn(displayId).firstOrNull()?.let {
            (TASK_INFO.getField("topActivity").get(it) as? ComponentName)?.className
        }
    } catch (t: Throwable) {
        Log.e(TAG, "topActivity failed", t)
        null
    }

    /**
     * IActivityTaskManager.moveRootTaskToDisplay(taskId, displayId),即 `am display move-stack` 背后的调用。
     * 真机(安卓 14)手动验证:任务在虚拟屏与主屏之间双向都能挪。Mika 启动的任务都是根任务。
     */
    override fun moveTask(taskId: Int, displayId: Int): Boolean = try {
        taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java)
            .invoke(taskManager, taskId, displayId)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "moveRootTaskToDisplay unavailable, falling back to am", t)
        exec("am", "display", "move-stack", "$taskId", "$displayId") == 0
    }

    /**
     * 任务结束时留在主屏后面的任务:任务编号 → 键([AppsAfterTask.keyOf])。下次 Mika 打开同一份 App 时把它挪回来([resumeKept])。
     * 只在内存里:助手重启后不认得,打开时照常走启动入口(系统多半也会把这个后台任务拉过来)。
     */
    private val kept = HashMap<Int, String>()

    /**
     * 任务结束:按 [AppsAfterTask] 定下这块屏上每个任务的去留,留下的挪到主屏最后面,再释放这块屏(屏带着
     * FLAG_DESTROY_CONTENT_ON_REMOVAL,没挪走的随屏关掉)。顺序:定去留 → 挪留下的 → 再看一眼出声的 → 等它们退到看不见([SettleBehind])
     * → 还看得见的挪回这块屏 → 释放。静音由 Mika 在这之后还原,留下的 App 不会在还原时突然出声。
     *
     * 挪到最后面的做法(都是安卓原版的接口):先用 WindowContainerTransaction.setHidden 把任务藏起来,
     * 再 moveRootTaskToDisplay 挪到主屏(系统总是把它放在最上面,藏着就不显示、不拿焦点、不让用户正在用的页面暂停),
     * 接着在同一个事务里 reorder 到最后面并取消隐藏。隐藏或移动本身失败时照旧随屏关掉。
     *
     * 挪和收尾在 [launchLock] 里做;中间等过渡(最多 [SettleBehind.TIMEOUT_MS])不占着锁,别的任务照常能打开 App。
     * 这段时间里被别的任务拿到它屏上的不再管;留下的任务也要等收尾时才记进 [kept],等的时候 [resumeKept] 不会拿它。
     */
    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle? {
        val sorting = synchronized(launchLock) {
            if (!ownDisplay(displayId)) return null
            sortAndMove(displayId, keep, close)
        }
        val settled = settleBehind(sorting.moved.mapTo(LinkedHashSet()) { it.task.id })
        return synchronized(launchLock) { closeOut(displayId, sorting, settled) }
    }

    /** [finishDisplay] 的第一段:留下的([moved])已挪到主屏;[hidden] 是挪过去以后取消隐藏没做成的。 */
    private class Sorting(
        val moved: MutableList<AppsAfterTask.Fate>,
        val closed: MutableList<String>,
        val notes: MutableSet<String>,
        val hidden: Set<Int>,
    )

    private fun sortAndMove(displayId: Int, keep: Array<String>?, close: Array<String>?): Sorting {
        val closed = ArrayList<String>()
        val notes = LinkedHashSet<String>()
        // 已挪到主屏后面的;中途出错也照记,下次打开接得上
        val moved = ArrayList<AppsAfterTask.Fate>()
        val hidden = HashSet<Int>()
        try {
            val playing = playingUids()
            if (playing == null) notes += AppsAfterTask.NOTE_PLAYBACK_UNKNOWN
            val rows = tasksOn(displayId)
            val idField = TASK_INFO.getField("taskId")
            val byId = rows.associateBy { idField.getInt(it) }
            val fates = AppsAfterTask.decide(rows.map { afterTaskRow(it, playing) },
                AppsAfterTask.normalize(keep, myUser), AppsAfterTask.normalize(close, myUser))
            var supported = true
            for (f in fates) {
                if (!f.keep) { closed += AppsAfterTask.closedEntry(f); continue }
                if (!supported) { closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.MoveUnsupported)); continue }
                when (keepBehind(byId.getValue(f.task.id))) {
                    Behind.OK -> moved += f
                    Behind.HIDDEN -> { moved += f; hidden += f.task.id }
                    Behind.UNSUPPORTED -> {
                        supported = false
                        notes += AppsAfterTask.NOTE_MOVE_UNSUPPORTED
                        closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.MoveUnsupported))
                    }
                }
            }
            // 挪过去之后又出声的(没标过 keep):挪回这块屏,随屏关掉;挪不回就直接关
            val playingNow = if (moved.isEmpty()) null else playingUids()
            val nowLoud = moved.map { f -> AppsAfterTask.recheck(f, task(f.task.id)?.let { playingIn(it, playingNow) }) }.filter { !it.keep }
            for (f in nowLoud) {
                sendBackToClose(f.task.id, displayId)
                moved.removeAll { it.task.id == f.task.id }
                closed += AppsAfterTask.closedEntry(f)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "finishDisplay: cannot inspect tasks, closing the rest with the display", t)
            notes += "error ${t.javaClass.simpleName}"
        }
        return Sorting(moved, closed, notes, hidden)
    }

    /**
     * [finishDisplay] 的最后一段,在锁里:按 [SettleBehind.closeOut] 再定一次。等到超时还看得见的挪回这块屏随屏关掉
     * (和挪过去又出声的同一条路,不留在用户眼前),记成没留下([AppsAfterTask.Why.StillInFront]);已不在主屏的不再记;
     * 记下留下的,释放屏。没能等(读不了任务列表,[settled] 为 null)时照留,结果里写明没核对上。
     */
    private fun closeOut(displayId: Int, sorting: Sorting, settled: SettleBehind.Plan.Done?): Bundle {
        val (moved, closed, notes) = Triple(sorting.moved, sorting.closed, sorting.notes)
        if (settled == null) notes += AppsAfterTask.NOTE_SETTLE_UNKNOWN
        else try {
            // 等的时候不占锁:回到锁里再看一次,别的任务这会儿把它拿走了、或已经落定的,都不动
            val ids = moved.mapTo(LinkedHashSet()) { it.task.id }
            val final = SettleBehind.closeOut(ids, settled.fallback, seenOnMain(ids))
            for (f in moved.filter { it.task.id in final.fallback }) {
                Log.w(TAG, "task ${f.task.id} is still in front on the main screen, closing it with display $displayId")
                sendBackToClose(f.task.id, displayId)
                closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.StillInFront))
            }
            if (final.fallback.isNotEmpty()) notes += AppsAfterTask.NOTE_STILL_IN_FRONT
            if (final.gone.isNotEmpty()) Log.i(TAG, "tasks ${final.gone} left the main screen while settling; not keeping them")
            moved.removeAll { it.task.id !in final.kept }
        } catch (t: Throwable) {
            Log.e(TAG, "finishDisplay: cannot recheck the main screen", t)
            notes += "error ${t.javaClass.simpleName}"
        }
        // 取消隐藏没做成的:留在主屏但最近任务里看不见,下次打开同一份时 resumeKept 挪回来补上取消隐藏
        val hidden = moved.count { it.task.id in sorting.hidden }
        if (hidden > 0) notes += "$hidden ${AppsAfterTask.NOTE_LEFT_HIDDEN}"
        synchronized(kept) { moved.forEach { kept[it.task.id] = it.key } }
        releaseDisplay(displayId)
        return Bundle().apply {
            putStringArray(AppsAfterTask.KEY_KEPT, moved.map { it.key }.toTypedArray())
            putStringArray(AppsAfterTask.KEY_CLOSED, closed.toTypedArray())
            putString(AppsAfterTask.KEY_NOTE, notes.joinToString("; ").ifEmpty { null })
        }
    }

    /** 不留的任务挪回 Mika 的屏 [displayId],随屏关掉;挪不回就直接关。 */
    private fun sendBackToClose(taskId: Int, displayId: Int) {
        if (!moveRoot(taskId, displayId)) removeTask(taskId)
    }

    /** OK:挪到主屏最后面了;HIDDEN:挪过去了,但取消隐藏没做成,还藏着;UNSUPPORTED:这台系统挪不了。 */
    private enum class Behind { OK, HIDDEN, UNSUPPORTED }

    /** 任务的一行给 [AppsAfterTask.decide]:键、是不是普通 App 的页面、是不是 Mika 自己的、此刻出不出声。 */
    private fun afterTaskRow(t: Any, playing: Map<Int, Set<String>>?): AppsAfterTask.Task {
        val user = runCatching { TASK_INFO.getField("userId").getInt(t) }.getOrDefault(myUser)
        val pkgs = packagesOf(t).distinct()
        val type = runCatching { TASK_INFO.getField("topActivityType").getInt(t) }.getOrNull()
        return AppsAfterTask.Task(
            id = TASK_INFO.getField("taskId").getInt(t),
            keys = pkgs.map { AppsAfterTask.keyOf(it, user, myUser) },
            standard = type == null || type == ACTIVITY_TYPE_UNDEFINED || type == ACTIVITY_TYPE_STANDARD,
            own = pkgs.any { it in ownPackages },
            playing = playingIn(t, playing),
            muted = synchronized(muted) { pkgs.any { muted.contains(AppsAfterTask.keyOf(it, user, myUser)) } },
        )
    }

    private val ownPackages: Set<String> by lazy {
        runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toSet() }.getOrNull().orEmpty()
    }

    /** 这个任务的 App(同一份用户里的)此刻在出声;读不到为 null。 */
    private fun playingIn(task: Any, playing: Map<Int, Set<String>>?): Boolean? {
        playing ?: return null
        val user = runCatching { TASK_INFO.getField("userId").getInt(task) }.getOrNull()
        val pkgs = packagesOf(task)
        return playing.any { (uid, owners) -> (user == null || uid / PER_USER_RANGE == user) && pkgs.any { it in owners } }
    }

    /**
     * 此刻正在播放的 uid → 这个 uid 的包。先问音频服务(AudioManager.getActivePlaybackConfigurations,
     * 带 MODIFY_AUDIO_ROUTING 时才给出 uid);拿到的 uid 被隐去时改读 `dumpsys audio`。只认播放器状态为 started 的
     * (被 Mika 静音的照样算在播:静音只是没声,还原就响)。都读不到为 null。
     */
    private fun playingUids(): Map<Int, Set<String>>? {
        val uids = runCatching {
            val configs = context.getSystemService(AudioManager::class.java).activePlaybackConfigurations
            val uidOf = AudioPlaybackConfiguration::class.java.getMethod("getClientUid")
            val stateOf = AudioPlaybackConfiguration::class.java.getMethod("getPlayerState")
            val rows = configs.map { (uidOf.invoke(it) as Int) to (stateOf.invoke(it) as Int) }
            if (rows.any { it.first < 0 }) null
            else rows.filter { it.second == AppsAfterTask.PLAYER_STATE_STARTED }.map { it.first }.toSet()
        }.onFailure { Log.w(TAG, "playback configurations unavailable", it) }.getOrNull()
            ?: sh("dumpsys", "audio").takeIf { it.first == 0 }?.second?.let(AppsAfterTask::playingUids)
            ?: return null
        return uids.associateWith { uid ->
            runCatching { context.packageManager.getPackagesForUid(uid)?.toSet() }.getOrNull().orEmpty()
        }
    }

    /**
     * 把 Mika 屏上的任务 [row] 挪到主屏最后面。移动成功后 reorder/取消隐藏没做成也不删任务:还看得见的交给 [settleBehind] 等过渡、
     * 再排到后面;一直藏着的返回 [Behind.HIDDEN],结果里写明,下次 [resumeKept] 挪回来时取消隐藏。
     */
    private fun keepBehind(row: Any): Behind {
        val id = TASK_INFO.getField("taskId").getInt(row)
        val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull() ?: return Behind.UNSUPPORTED
        if (!transact { wct -> wctCall(wct, "setHidden", token, true) }) return Behind.UNSUPPORTED
        if (!moveRoot(id, Display.DEFAULT_DISPLAY)) {
            transact { wct -> wctCall(wct, "setHidden", token, false) }
            return Behind.UNSUPPORTED
        }
        // 隐藏着 reorder 再取消隐藏,否则系统不挪它;两步放在同一个事务里。
        val placed = transact { wct ->
            wctCall(wct, "reorder", token, false)
            wctCall(wct, "setHidden", token, false)
        }
        if (placed) return Behind.OK
        // 别把它留在隐藏状态;取消隐藏后在上面的,交给 settleBehind 再排到后面。
        if (transact { wct -> wctCall(wct, "setHidden", token, false) }) return Behind.OK
        Log.w(TAG, "task $id is still hidden on the main screen; resumeKept will unhide it")
        return Behind.HIDDEN
    }

    /** [ids] 里此刻还在主屏上的任务,和它们看不看得见;读不到 isVisible 时按「是不是主屏最上面的任务」看(那里的任务按最近使用排,只作退路)。 */
    private fun seenOnMain(ids: Set<Int>): List<SettleBehind.Seen> {
        if (ids.isEmpty()) return emptyList()
        val idField = TASK_INFO.getField("taskId")
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        return tasksOn(Display.DEFAULT_DISPLAY).withIndex().mapNotNull { (index, row) ->
            val id = idField.getInt(row)
            if (id !in ids) null else SettleBehind.Seen(id, onMain = true, visible = visibleField?.getBoolean(row) ?: (index == 0))
        }
    }

    /**
     * 等挪到主屏的任务 [moved] 退到用户看不见的地方,规则见 [SettleBehind]。不在 [launchLock] 里调:要等最多 [SettleBehind.TIMEOUT_MS]。
     * 读不了任务列表时返回 null(调用方照留,结果里写明没核对上)。
     */
    private fun settleBehind(moved: Set<Int>): SettleBehind.Plan.Done? = try {
        val start = SystemClock.uptimeMillis()
        var lastReorder: Long? = null
        var watching = moved
        val gone = LinkedHashSet<Int>()
        var done: SettleBehind.Plan.Done? = null
        while (done == null) {
            val now = SystemClock.uptimeMillis()
            when (val plan = SettleBehind.plan(watching, seenOnMain(watching), now - start, lastReorder?.let { now - it })) {
                is SettleBehind.Plan.Done -> done = plan.copy(gone = plan.gone + gone)
                is SettleBehind.Plan.Wait -> {
                    gone += plan.gone
                    watching = watching - plan.gone
                    if (plan.reorder.isNotEmpty()) {
                        lastReorder = now
                        sendToBack(plan.reorder)
                    }
                    Thread.sleep(SettleBehind.POLL_MS)
                }
            }
        }
        if (done.fallback.isNotEmpty()) Log.w(TAG, "moved tasks ${done.fallback} are still visible on the main screen after ${SettleBehind.TIMEOUT_MS}ms")
        done
    } catch (t: Throwable) {
        Log.e(TAG, "cannot watch moved tasks settle on the main screen", t)
        null
    }

    /** 把主屏上的这些任务再排到最后面(过渡还没完的不算失败,下一轮再看)。 */
    private fun sendToBack(ids: Set<Int>) {
        val idField = TASK_INFO.getField("taskId")
        for (row in tasksOn(Display.DEFAULT_DISPLAY)) {
            val id = idField.getInt(row)
            if (id !in ids) continue
            Log.w(TAG, "moved task $id is still visible on the main screen, sending it back")
            val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull() ?: continue
            transact { wct -> wctCall(wct, "reorder", token, false) }
        }
    }

    /** IActivityTaskManager.moveRootTaskToDisplay;接口不在时退回 `am display move-stack`(同一个调用)。 */
    private fun moveRoot(taskId: Int, displayId: Int): Boolean = moveTask(taskId, displayId)

    /** IActivityTaskManager.removeTask(关掉任务,和最近任务里划掉一样);不行退回 `am stack remove`。 */
    private fun removeTask(taskId: Int): Boolean = try {
        taskManager.javaClass.getMethod("removeTask", Int::class.java).invoke(taskManager, taskId) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "removeTask unavailable, falling back to am", t)
        exec("am", "stack", "remove", "$taskId") == 0
    }

    /** 建一个 WindowContainerTransaction,经 IWindowOrganizerController.applyTransaction 交给系统;哪一步不行返回 false。 */
    @SuppressLint("PrivateApi")
    private fun transact(build: (Any) -> Unit): Boolean = try {
        val wct = WCT.getConstructor().newInstance()
        build(wct)
        val controller = taskManager.javaClass.getMethod("getWindowOrganizerController").invoke(taskManager)!!
        controller.javaClass.methods.first { it.name == "applyTransaction" && it.parameterTypes.size == 1 }.invoke(controller, wct)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "window container transaction failed", t)
        false
    }

    @SuppressLint("PrivateApi")
    private fun wctCall(wct: Any, method: String, token: Any, flag: Boolean) {
        WCT.getMethod(method, Class.forName("android.window.WindowContainerToken"), Boolean::class.javaPrimitiveType).invoke(wct, token, flag)
    }

    /** 主屏上(没在用户眼前)的、上次任务留下的这份 App 的任务。 */
    private fun keptOnMain(packageName: String, userId: Int): List<Any> {
        val ids = synchronized(kept) { kept.keys.toSet() }
        if (ids.isEmpty()) return emptyList()
        val idField = TASK_INFO.getField("taskId")
        val displayField = TASK_INFO.getField("displayId")
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        val all = allTasks()
        // 已经不在的任务不再记着
        synchronized(kept) { kept.keys.retainAll(all.map { idField.getInt(it) }.toSet()) }
        return all.filter { t ->
            idField.getInt(t) in ids && displayField.getInt(t) == Display.DEFAULT_DISPLAY && isCopy(t, packageName, userId) &&
                visibleField?.getBoolean(t) != true
        }
    }

    /** 把留在主屏后面的那个任务挪回 Mika 的屏;没有、或挪不了返回 false(调用方照常启动)。 */
    private fun resumeKept(displayId: Int, packageName: String, userId: Int): Boolean {
        val row = runCatching { keptOnMain(packageName, userId).firstOrNull() }.getOrNull() ?: return false
        val id = TASK_INFO.getField("taskId").getInt(row)
        if (!moveRoot(id, displayId)) return false
        // 收尾时没来得及取消隐藏的,挪回来补一次。
        val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull()
        if (token != null && !transact { wct -> wctCall(wct, "setHidden", token, false) }) {
            Log.w(TAG, "task $id is still hidden after moving it back to display $displayId")
        }
        synchronized(kept) { kept.remove(id) }
        return true
    }

    /** 冷启动前关掉上次任务留在主屏后面的那份(Mika 自己留下的,不是用户的状态)。 */
    private fun dropKept(packageName: String, userId: Int) {
        keptOnMain(packageName, userId).forEach { t ->
            val id = TASK_INFO.getField("taskId").getInt(t)
            if (removeTask(id)) synchronized(kept) { kept.remove(id) }
        }
    }

    /** 主屏任务的记录:Agent 动作前开始盯,见 [MainScreenWatch]。第一次用到才建(线程、任务栈监听)。 */
    private val mainWatch by lazy { MainScreenWatch({ taskManager }, ::taskRows) }

    override fun watchMainScreen(windowMs: Int): Int = try {
        mainWatch.watch(windowMs)
    } catch (t: Throwable) {
        Log.e(TAG, "watchMainScreen failed", t)
        -1
    }

    override fun mainArrivals(sinceUptimeMs: Long): Bundle? = try {
        mainWatch.arrivals(sinceUptimeMs)
    } catch (t: Throwable) {
        Log.e(TAG, "mainArrivals failed", t)
        null
    }

    override fun userOfTask(taskId: Int): Int = try {
        task(taskId)?.let { TASK_INFO.getField("userId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "userOfTask failed", t)
        -1
    }

    /** 所有屏上的任务,换成 [MainScreenLog.Task];页面种类读不到时当作普通 App 的页面。 */
    private fun taskRows(): List<MainScreenLog.Task> {
        val id = TASK_INFO.getField("taskId")
        val display = TASK_INFO.getField("displayId")
        val user = runCatching { TASK_INFO.getField("userId") }.getOrNull()
        val type = runCatching { TASK_INFO.getField("topActivityType") }.getOrNull()
        return allTasks().map { t ->
            val top = TASK_INFO.getField("topActivity").get(t) as? ComponentName
            MainScreenLog.Task(
                taskId = id.getInt(t),
                displayId = display.getInt(t),
                packageName = top?.packageName ?: (TASK_INFO.getField("baseActivity").get(t) as? ComponentName)?.packageName,
                userId = user?.getInt(t) ?: -1,
                topActivity = top?.className,
                standard = type?.getInt(t)?.let { it == ACTIVITY_TYPE_UNDEFINED || it == ACTIVITY_TYPE_STANDARD } ?: true,
            )
        }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        // 与 press_key 同一张白名单:即便调用方出错,也不从这里发出音量、电源、Home 之类的整机按键
        if (!PressKeys.isAllowed(keyCode)) {
            Log.w(TAG, "refused key $keyCode")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        // 按键由系统按输入焦点路由,不认 event 上的 displayId:不定向的话会投到主屏焦点,虚拟屏上的 App 收不到返回键。
        // 解析到虚拟屏顶部任务的 uid 时定向投给它;定向没投出去再退回非定向注入(和旧路径一致),最后才走 shell 命令。
        // 逐级实际走的通道记进日志,方便在真机上定位 back 注入失效(#622)。
        val uid = topTaskUid(displayId)
        var channel = if (uid != null) "targeted" else "nontargeted"
        val ok = if (uid != null) {
            injectPairTo(down, up, displayId, uid) {
                channel = "nontargeted"
                injectPair(down, up, displayId) {
                    channel = "shell"
                    exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
                }
            }
        } else {
            injectPair(down, up, displayId) {
                channel = "shell"
                exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Log.i(TAG, "injectKey BACK display=$displayId uid=$uid channel=$channel ok=$ok")
        }
        return ok
    }

    /** 虚拟屏顶部任务的 uid(定向注入的目标);解析不到返回 null(退回非定向注入)。 */
    private fun topTaskUid(displayId: Int): Int? = try {
        val task = tasksOn(displayId).firstOrNull() ?: return null
        val top = TASK_INFO.getField("topActivity").get(task) as? ComponentName
        val packageName = top?.packageName ?: return null
        val userId = TASK_INFO.getField("userId").getInt(task)
        resolveTargetUid(Build.VERSION.SDK_INT, packageName, userId) { pkg, user ->
            val pm = context.packageManager
            pm.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.java, Int::class.java)
                .invoke(pm, pkg, 0, user) as Int
        }
    } catch (t: Throwable) {
        Log.w(TAG, "resolve top task uid on display $displayId failed", t)
        null
    }

    /** 自有虚拟屏上的 Ctrl+A / Ctrl+V,同时发送修饰键按下和抬起;松开修饰键失败不会把已经发出的正文判成未发送。 */
    override fun injectCtrlKey(displayId: Int, keyCode: Int): Int {
        if (!ownDisplay(displayId)) return AsciiTextKeys.NOT_SENT
        if (keyCode != KeyEvent.KEYCODE_A && keyCode != KeyEvent.KEYCODE_V) {
            Log.w(TAG, "refused ctrl key $keyCode")
            return AsciiTextKeys.NOT_SENT
        }
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        fun event(code: Int, action: Int, modifiers: Int) = KeyEvent(
            now, SystemClock.uptimeMillis(), action, code, 0, modifiers,
            VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        var modifierDown = false
        try {
            if (!inject(event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_DOWN, 0), displayId)) return AsciiTextKeys.NOT_SENT
            modifierDown = true
            if (!inject(event(keyCode, KeyEvent.ACTION_DOWN, meta), displayId)) return AsciiTextKeys.NOT_SENT
            val up = event(keyCode, KeyEvent.ACTION_UP, meta)
            return if (inject(up, displayId) || inject(up, displayId)) AsciiTextKeys.DISPATCHED else AsciiTextKeys.UNCONFIRMED
        } finally {
            if (modifierDown) {
                val up = event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP, 0)
                if (ownDisplay(displayId) && !inject(up, displayId) && !inject(up, displayId)) {
                    Log.w(TAG, "could not release Ctrl on display $displayId")
                }
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int {
        if (displayId <= Display.DEFAULT_DISPLAY || !ownDisplay(displayId) || taskId < 0 || !AsciiTextKeys.supports(text)) {
            return AsciiTextKeys.NOT_SENT
        }
        val (events, targetUid) = runCatching {
            val task = tasksOn(displayId).firstOrNull() ?: return AsciiTextKeys.NOT_SENT
            val top = TASK_INFO.getField("topActivity").get(task) as? ComponentName
            if (TASK_INFO.getField("taskId").getInt(task) != taskId || top?.packageName != packageName) {
                return AsciiTextKeys.NOT_SENT
            }
            val uid = if (Build.VERSION.SDK_INT >= 33) {
                val user = TASK_INFO.getField("userId").getInt(task)
                val pm = context.packageManager
                (pm.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.java, Int::class.java)
                    .invoke(pm, packageName, 0, user) as Int).takeIf { it >= 0 } ?: return AsciiTextKeys.NOT_SENT
            } else null
            val keys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
                ?.takeIf { it.isNotEmpty() } ?: return AsciiTextKeys.NOT_SENT
            keys to uid
        }.getOrElse { return AsciiTextKeys.NOT_SENT }

        for (event in events) {
            if (!ownDisplay(displayId) || (targetUid == null && topTaskId(displayId) != taskId)) {
                return AsciiTextKeys.UNCONFIRMED
            }
            event.source = InputDevice.SOURCE_KEYBOARD
            val sent = if (targetUid != null) injectTo(event, displayId, targetUid)
                else inject(event, displayId, waitForFinish = true)
            // A failed dispatch can leave a prefix. Never replay it through another input route.
            if (!sent) return AsciiTextKeys.UNCONFIRMED
        }
        return AsciiTextKeys.DISPATCHED
    }

    override fun listUsers(): String? = sh("pm", "list", "users").takeIf { it.first == 0 }?.second

    override fun listPackages(userId: Int): String? =
        sh("pm", "list", "packages", "--user", "$userId").takeIf { it.first == 0 }?.second

    override fun isSystemPackage(packageName: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0 }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /**
     * 一根手指的一笔:按下、按 [times] 逐点移动、在最后一点抬起;每个事件到了它的时刻才注入(事件时间就是那一刻),
     * App 看到的速度就是 Mika 排好的快慢。只发到 Mika 自己的屏。按下没注入成功返回 false(调用方改走别的路);
     * 按下之后中途哪个点没注入成功也照样往下走,最后一定补发抬起,不让手指一直按着。
     */
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n || times[0] != 0L ||
            times.last() > STROKE_MAX_MS || (1 until n).any { times[it] < times[it - 1] }
        ) return false
        val down = SystemClock.uptimeMillis()
        fun event(i: Int, action: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { x = xs[i].toFloat(); y = ys[i].toFloat(); pressure = 1f; size = 1f }
            return MotionEvent.obtain(down, down + times[i], action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = down + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = event(i, action)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) return false
        for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
        return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
    }

    private inline fun injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean =
        sendPair({ inject(down, displayId) }, { inject(up, displayId) }, fallback)

    /** 同 [injectPair],但定向投给 [targetUid] 拥有的焦点窗口,不经过全局输入焦点。 */
    private inline fun injectPairTo(down: InputEvent, up: InputEvent, displayId: Int, targetUid: Int, fallback: () -> Boolean): Boolean =
        sendPair({ injectTo(down, displayId, targetUid) }, { injectTo(up, displayId, targetUid) }, fallback)

    /**
     * `appops set <pkg> PLAY_AUDIO deny`;首次静音时记下原模式(同时落文件),取消静音时恢复。
     * 用 deny 而不是 ignore:`ignore` 只是把声音压掉,App 仍处于「在播」、仍持有音频焦点,会抢走前台正在播的 App 的声音(#483/#228);
     * `deny` 让它的音频起不来,也就不占焦点。原来没设置过的恢复成 allow,不写 default(见 [AudioMuteLedger.restoreMode])。
     * 命令失败返回 false,且不记录原模式(读不到原值就不改)。作用在 Mika 所在的用户上(本来那份)。
     */
    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean = audioMuted(packageName, null, muted)

    /** 同 [setAudioMuted],作用在 [userId] 那份用户里的那份(分身、工作资料);记录按 [AppAudioKey] 分开记。 */
    override fun setAudioMutedInUser(packageName: String, userId: Int, muted: Boolean): Boolean {
        if (userId == myUser) return audioMuted(packageName, null, muted)
        return audioMuted(packageName, otherUser(userId) ?: return false, muted)
    }

    private fun audioMuted(packageName: String, user: Int?, muted: Boolean): Boolean = synchronized(this.muted) {
        val key = AppAudioKey.of(packageName, user)
        if (muted) {
            if (!this.muted.contains(key)) {
                val (code, out) = appops("get", packageName, user)
                if (code != 0) return false
                this.muted.put(key, parsePlayAudioMode(out))
            }
            appops("set", packageName, user, "deny").first == 0
        } else {
            val ok = restoreAudioMode(packageName, user, AudioMuteLedger.restoreMode(this.muted[key]))
            if (ok) this.muted.remove(key)
            ok
        }
    }

    /** 启动时把上次没来得及恢复的包还原(只动记录里的)。 */
    fun recoverMuted() {
        synchronized(muted) { muted.restoreAll(::writeRecorded) }
    }

    /** 按记录的键写回一项;键不合格式的当作已处理,免得一直留着。 */
    private fun writeRecorded(key: String, mode: String): Boolean {
        val (pkg, user) = AppAudioKey.parse(key) ?: return true
        return restoreAudioMode(pkg, user, mode)
    }

    private fun restoreAudioMode(packageName: String, user: Int?, mode: String): Boolean {
        if (appops("set", packageName, user, mode).first == 0) return true
        // 有些系统禁止 shell 写 AppOps。静音没生效时，回读确认原值仍在就能清掉记录。
        val (code, output) = appops("get", packageName, user)
        return code == 0 && AudioMuteLedger.readMode(output) == mode
    }

    /** [user] 为空是 Mika 所在的用户。 */
    private fun appops(verb: String, packageName: String, user: Int?, mode: String? = null): Pair<Int, String> {
        val u = (user ?: myUser).toString()
        return if (mode == null) sh("appops", verb, "--user", u, packageName, "PLAY_AUDIO")
        else sh("appops", verb, "--user", u, packageName, "PLAY_AUDIO", mode)
    }

    /** 别的用户编号原样给出;就是 Mika 所在的用户时为 null(按本来那份处理);不合法时也为 null,调用方据此拒绝。 */
    private fun otherUser(userId: Int): Int? = userId.takeIf { it >= 0 && it != myUser }

    override fun getAudioMode(packageName: String): String? = audioMode(packageName, null)

    override fun getAudioModeInUser(packageName: String, userId: Int): String? {
        if (userId == myUser) return audioMode(packageName, null)
        return audioMode(packageName, otherUser(userId) ?: return null)
    }

    private fun audioMode(packageName: String, user: Int?): String? {
        val (code, out) = appops("get", packageName, user)
        return if (code == 0) parsePlayAudioMode(out) else null
    }

    /** "default"(没设置过)按 allow 写,理由同 [AudioMuteLedger.restoreMode]。 */
    override fun setAudioMode(packageName: String, mode: String): Boolean = audioMode(packageName, null, mode)

    override fun setAudioModeInUser(packageName: String, userId: Int, mode: String): Boolean {
        if (userId == myUser) return audioMode(packageName, null, mode)
        return audioMode(packageName, otherUser(userId) ?: return false, mode)
    }

    private fun audioMode(packageName: String, user: Int?, mode: String): Boolean {
        if (!MODE_RE.matches(mode)) return false
        val ok = restoreAudioMode(packageName, user, AudioMuteLedger.restoreMode(mode))
        if (ok) synchronized(muted) { muted.remove(AppAudioKey.of(packageName, user)) }
        return ok
    }

    /**
     * 该包([userId] 那份用户里的)在主屏上有看得见的任务:用户正在主屏用它。静音按包作用,这时静了它用户手上的也没声了,
     * Mika 据此不去静它。看不清主屏时按「在用」答,宁可不静也不把用户正在用的静了。
     */
    override fun visibleOnMainScreen(packageName: String, userId: Int): Boolean =
        try {
            inUseOnMainScreen(packageName, userId)
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            true
        }

    /**
     * 只有读得到用户原来的剪贴板才动它:读不到就分不清「原来是空的」和「有内容但看不到」,
     * 覆盖了就还原不回去,这时返回 [CLIP_UNREADABLE],剪贴板保持原样。
     */
    override fun setClipText(text: String): Int = synchronized(clipLock) {
        try {
            // 上次还原因读不到而没完成时,先再还原一次;仍读不到就不能覆盖此刻可能属于用户的新内容。
            if (clipSaved && !restoreClip()) return CLIP_UNREADABLE
            if (!clipSaved) {
                if (!canReadClipboard()) return CLIP_UNREADABLE
                val prior = clipboard.primaryClip
                // 读到空不一定是空:系统可能悄悄拦下了读取,这时有无、描述还会说有内容
                when (ShellClip.read(prior != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
                    ShellClip.Read.Unreadable -> {
                        Log.i(TAG, "setClipText: clip present but unreadable, not replacing")
                        return CLIP_UNREADABLE
                    }
                    ShellClip.Read.Empty -> Log.i(TAG, "setClipText: user clipboard was empty")
                    ShellClip.Read.Has -> Log.i(TAG, "setClipText: user clipboard saved")
                }
                savedClip = prior
                clipSaved = true
            }
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            suppressOverlay(clip)
            markSensitive(clip)
            clipboard.setPrimaryClip(clip)
            ourText = text
            // Android can silently decline the write. Never paste a clipboard we didn't set.
            if (nowState(text) == ShellClip.Now.Ours) CLIP_SET else CLIP_FAILED
        } catch (t: Throwable) {
            Log.w(TAG, "setClipText failed", t)
            CLIP_FAILED
        }
    }

    override fun restoreClip(): Boolean = synchronized(clipLock) {
        if (!clipSaved) return true
        val finished = runCatching {
            val prior = savedClip
            val now = nowState(ourText)
            val action = ShellClip.restore(prior == null, now)
            Log.i(TAG, "restoreClip: now=$now action=$action")
            when (action) {
                ShellClip.Restore.PutBack -> clipboard.setPrimaryClip(suppressOverlay(prior!!))
                ShellClip.Restore.Clear -> clipboard.clearPrimaryClip()
                ShellClip.Restore.Leave -> Unit
            }
            // 写入也可能被系统悄悄拒绝:再读一次,仍是 Mika 的或此刻读不到都保留记录,下次继续尝试。
            // 用户已经复制了别的内容时保留用户的新内容,留底可以清掉。
            val after = nowState(ourText)
            after != ShellClip.Now.Ours && after != ShellClip.Now.Unreadable
        }.onFailure { Log.w(TAG, "restoreClip failed", it) }.getOrDefault(false)
        if (finished) {
            savedClip = null
            clipSaved = false
            ourText = null
        }
        finished
    }

    override fun savedClipText(): String? = synchronized(clipLock) {
        if (!clipSaved) return null
        runCatching { savedClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
    }

    override fun restoreClipText(original: String, ours: String): Boolean = synchronized(clipLock) {
        runCatching {
            val now = nowState(ours)
            val action = ShellClip.restore(original.isEmpty(), now)
            Log.i(TAG, "restoreClipText: now=$now action=$action")
            when (action) {
                ShellClip.Restore.PutBack -> clipboard.setPrimaryClip(suppressOverlay(ClipData.newPlainText("", original)))
                ShellClip.Restore.Clear -> clipboard.clearPrimaryClip()
                ShellClip.Restore.Leave -> Unit
            }
            val after = nowState(ours)
            after != ShellClip.Now.Ours && after != ShellClip.Now.Unreadable
        }.onFailure { Log.w(TAG, "restoreClipText failed", it) }.getOrDefault(false)
    }

    /** 剪贴板此刻是不是 Mika 放进去的 [ours];读不到(有内容但取不到)时不当成空的。 */
    private fun nowState(ours: String?): ShellClip.Now {
        val now = clipboard.primaryClip
        return when (ShellClip.read(now != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
            ShellClip.Read.Empty -> ShellClip.Now.Empty
            ShellClip.Read.Unreadable -> ShellClip.Now.Unreadable
            ShellClip.Read.Has -> {
                val ourClip = now!!.description.label == CLIP_LABEL && now.itemCount == 1 && ours != null &&
                    now.getItemAt(0).text?.toString() == ours
                if (ourClip) ShellClip.Now.Ours else ShellClip.Now.Other
            }
        }
    }

    /** shell 要有后台读剪贴板的权限、且读剪贴板的权限操作没被关掉,才算读得到。 */
    private fun canReadClipboard(): Boolean = runCatching {
        val granted = context.checkPermission(READ_CLIPBOARD_IN_BACKGROUND, Process.myPid(), Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED
        val ops = context.getSystemService(AppOpsManager::class.java)
        granted && ops.unsafeCheckOpNoThrow(OPSTR_READ_CLIPBOARD, Process.myUid(), ShellContext.SHELL_PACKAGE) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun suppressOverlay(clip: ClipData): ClipData {
        val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
        extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
        clip.description.extras = extras
        return clip
    }

    /**
     * Mika 粘贴的内容(可能是地址、手机号等)标成敏感:系统和输入法的剪贴板预览、历史不显示原文。
     * 只标 Mika 自己放进去的,还原用户的剪贴板时保持原样。
     */
    private fun markSensitive(clip: ClipData): ClipData {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return clip
        val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
        extras.putBoolean(EXTRA_IS_SENSITIVE, true)
        clip.description.extras = extras
        return clip
    }

    public override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // 内置助手的进程不跟着 Mika 结束,Binder 也可能被别人拿到:只有 Mika 能调用接口方法
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION &&
            Binder.getCallingUid() != allowedUid
        ) {
            throw SecurityException("caller ${Binder.getCallingUid()} is not allowed")
        }
        try {
            return super.onTransact(code, data, reply, flags)
        } finally {
            returnedCallPipes.get()?.let { pipes ->
                returnedCallPipes.remove()
                pipes.forEach { runCatching { it.close() } }
            }
        }
    }

    override fun setDeviceFilePolicy(protocol: Int, session: String?, revision: Long, roots: Array<out String>?, recursive: BooleanArray?): Bundle = asCallAudioShell {
        synchronized(filePolicyLock) {
            fun reply(status: String) = Bundle().apply { putString("status", status); putInt("protocol", 1); putString("session", session); putLong("revision", revision) }
            if (protocol != 1 || session.isNullOrBlank() || session.length > 64 || revision < 0 || roots == null || recursive == null) return@synchronized reply("invalid")
            val previous = filePolicy?.takeIf { it.session == session }
            val paths = roots.toList()
            val flags = recursive.toList()
            if (previous != null && (revision < previous.revision || (revision == previous.revision && (paths != previous.roots || flags != previous.recursive)))) {
                return@synchronized reply("stale_policy")
            }
            if (previous != null && revision == previous.revision) return@synchronized reply("ok")
            val policy = try { DeviceFilePolicy.create(roots, recursive, myUser) } catch (_: Exception) { return@synchronized reply("invalid") }
            filePolicy = FilePolicyState(session, revision, paths, flags, policy)
            reply("ok")
        }
    }

    private inline fun deviceFileCall(block: (DeviceFilePolicy) -> Bundle): Bundle = asCallAudioShell {
        val snapshot = filePolicy ?: return@asCallAudioShell Bundle().apply { putString("status", "policy_unsynced") }
        val response = block(snapshot.policy)
        synchronized(filePolicyLock) {
            if (filePolicy !== snapshot) {
                response.getParcelable<ParcelFileDescriptor>("fd")?.close()
                Bundle().apply { putString("status", "policy_changed") }
            } else {
                response.getParcelable<ParcelFileDescriptor>("fd")?.let { returnedCallPipes.set(listOf(it)) }
                response
            }
        }
    }

    override fun listFiles(path: String?, afterName: String?, limit: Int): Bundle =
        deviceFileCall { ShellFiles.list(path, afterName, limit, myUser, it) }

    override fun searchFiles(path: String?, name: String?, extension: String?, modifiedAfter: Long, modifiedBefore: Long, maxDepth: Int, maxEntries: Int, timeoutMs: Int): Bundle =
        deviceFileCall { ShellFiles.search(path, name, extension, modifiedAfter, modifiedBefore, maxDepth, maxEntries, timeoutMs, myUser, it) }

    override fun openFile(path: String?): Bundle = deviceFileCall { ShellFiles.open(path, myUser, it) }

    override fun probeCallAudio(mode: Int): Bundle = asCallAudioShell { callAudio.probe(mode) }

    override fun openCallAudio(mode: Int, owner: IBinder): Bundle = asCallAudioShell {
        callAudio.open(mode, owner).also { result ->
            if (result.getBoolean("ready")) {
                val down = result.getParcelable<ParcelFileDescriptor>("downlink")
                val up = result.getParcelable<ParcelFileDescriptor>("uplink")
                returnedCallPipes.set(listOfNotNull(down, up))
            }
        }
    }

    override fun stopCallAudio(owner: IBinder) = asCallAudioShell { callAudio.stop(owner) }

    override fun flushCallAudio(owner: IBinder): Boolean = asCallAudioShell { callAudio.flush(owner) }

    override fun callAudioStats(owner: IBinder): Bundle = asCallAudioShell { callAudio.stats(owner) }

    private inline fun <T> asCallAudioShell(block: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        return try { block() } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    override fun runSystemCommand(displayId: Int, command: String): Bundle {
        if (!ownDisplay(displayId)) return commandResult(-1, "No task display is active.")
        return exec(command, displayId)
    }

    override fun runCommand(command: String): Bundle = exec(command, null)

    /** 助手进程看 Mika 交来的心愿(见 [setRestoreWish]);Mika 死后据此决定要不要把它拉起来。 */
    @Volatile var onRestoreWish: ((Int) -> Unit)? = null

    override fun setRestoreWish(flags: Int) {
        onRestoreWish?.invoke(flags)
    }

    private val deviceUnlock by lazy { DeviceUnlockInput(context,
        inject = { inject(it, Display.DEFAULT_DISPLAY, waitForFinish = true) },
        injectTo = { event, uid -> injectTo(event, Display.DEFAULT_DISPLAY, uid) },
        command = { exec(*it.toTypedArray()) == 0 }) }

    override fun wakeDevice(): Boolean = deviceUnlock.wake()
    override fun dismissDeviceKeyguard(): Boolean = deviceUnlock.dismiss()
    override fun revealDeviceCredential(): Boolean = deviceUnlock.reveal()
    override fun revealDeviceCredentialTap(x: Int, y: Int): Boolean = deviceUnlock.tap(x, y)
    override fun enterDeviceCredential(kind: Int, credential: ByteArray, clear: Int): Boolean = deviceUnlock.enter(kind, credential, clear)

    private fun commandResult(code: Int, output: String) = Bundle().apply { putInt("exitCode", code); putString("output", output) }

    /** 以 shell 身份跑 `sh -c`,合并输出,限时限长;[displayId] 不为空时放进 MIKA_DISPLAY_ID。 */
    private fun exec(command: String, displayId: Int?): Bundle {
        fun result(code: Int, output: String) = commandResult(code, output)
        if (command.isBlank() || command.length > 4_000) return result(-1, "Invalid command length.")
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true)
                .apply { if (displayId != null) environment()["MIKA_DISPLAY_ID"] = displayId.toString() }.start()
        } catch (t: Throwable) {
            return result(-1, t.message ?: "Command could not start.")
        }
        val bytes = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() < COMMAND_OUTPUT_MAX) bytes.write(buffer, 0, minOf(count, COMMAND_OUTPUT_MAX - bytes.size()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(READER_JOIN_MS)
        val output = bytes.toString(Charsets.UTF_8.name()) + if (bytes.size() >= COMMAND_OUTPUT_MAX) "\n[output truncated]" else ""
        return result(if (finished) process.exitValue() else -1, output)
    }

    override fun openDevtoolsSocket(name: String): ParcelFileDescriptor? {
        val pid = DevtoolsSocket.pidOf(name) ?: return null
        // 只连 Mika 自己进程的调试套接字:别的 App 的网页不经这里
        val uid = runCatching { DevtoolsSocket.procUid(File("/proc/$pid/status").readText()) }.getOrNull()
        if (uid != allowedUid) {
            Log.w(TAG, "devtools socket refused: pid $pid is not Mika's (uid $uid)")
            return null
        }
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            ParcelFileDescriptor.dup(socket.fileDescriptor)
        } catch (e: Exception) {
            Log.w(TAG, "devtools socket $name: ${e.message}")
            null
        } finally {
            // 交出去的是复制的 fd,这一份关掉
            runCatching { socket.close() }
        }
    }

    override fun releaseAll() {
        synchronized(filePolicyLock) { filePolicy = null }
        asCallAudioShell { callAudio.stopAll() }
        restoreClip()
        synchronized(muted) { muted.packages() }.forEach { key ->
            AppAudioKey.parse(key)?.let { (pkg, user) -> audioMuted(pkg, user, false) } ?: synchronized(muted) { muted.remove(key) }
        }
        awake.stopAll()
        val (all, watch) = synchronized(displays) {
            displays.values.toList().also { displays.clear() } to orientationWatch.also { orientationWatch = null }
        }
        watch?.close()
        all.forEach { it.release() }
    }

    override fun destroy() {
        releaseAll()
        exitProcess(0)
    }

    /**
     * 只设置刚建好的 Mika 屏,不改主屏。有的系统会把新建的外接/虚拟屏默认设成 freeform,
     * App 因此缩成小窗,无障碍坐标还会受窗口缩放影响。建屏后、启动任何页面前明确使用全屏模式,
     * 后续的 App 启动、页面跳转和从主屏挪回来的任务都沿用这块屏的策略。
     */
    @SuppressLint("PrivateApi")
    private fun setFullscreenWindowingMode(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            // WindowConfiguration.WINDOWING_MODE_FULLSCREEN = 1 (隐藏常量)
            wm.javaClass.getMethod("setWindowingMode", Int::class.java, Int::class.java).invoke(wm, displayId, 1)
        }.onFailure {
            Log.w(TAG, "setWindowingMode failed, falling back to wm", it)
            if (exec("wm", "set-display-windowing-mode", "-d", "$displayId", "1") != 0) {
                Log.w(TAG, "could not set fullscreen windowing mode on display $displayId")
            }
        }
    }

    /** IWindowManager.setDisplayImePolicy:输入法不弹到用户的主屏。选哪种策略见 IME_POLICY。 */
    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, displayId, IME_POLICY)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int, waitForFinish: Boolean = false): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, injectMode(event is KeyEvent, waitForFinish)) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }

    /**
     * 只投给 [targetUid] 拥有的焦点窗口,等处理完再返回;焦点不在它那里时系统拒绝,事件不会落到别的窗口。
     * 用安卓 13 起的隐藏重载 `injectInputEvent(event, mode, targetUid)`,没有这个重载时不注入。
     * 失败只记异常类型:被拒的原因里不含按键,但这条路径输入的是锁屏凭据,不留多余信息。
     */
    @SuppressLint("PrivateApi")
    private fun injectTo(event: InputEvent, displayId: Int, targetUid: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java, Int::class.java)
            .invoke(manager, event, INJECT_MODE_WAIT_FOR_FINISH, targetUid) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "targeted injectInputEvent failed: ${(t.cause ?: t).javaClass.simpleName}")
        false
    }

    /** API 34 起注入入口在 InputManagerGlobal,更早在 InputManager。 */
    @SuppressLint("PrivateApi")
    private fun inputManager(): Any = runCatching {
        Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!

    private fun exec(vararg cmd: String): Int = sh(*cmd).first

    /**
     * 跑一条 shell 命令,返回 (退出码, 输出);超时返回 -1。
     * 输出在单独线程里读,卡住的命令不会把 binder 线程永远堵住。
     */
    private fun sh(vararg cmd: String): Pair<Int, String> {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1 to ""
        }
        val output = AtomicReference("")
        val reader = Thread { output.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")) }
            .apply { isDaemon = true; start() }
        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "exec ${cmd.joinToString(" ")} timed out")
            return -1 to ""
        }
        reader.join(READER_JOIN_MS)
        return p.exitValue() to output.get()
    }

    companion object {
        private const val TAG = "MikaShell"
        private const val MAX_TASKS = 100
        private const val TAP_MS = 50L
        // injectStroke 一笔的上限:点数、从按下到抬起的毫秒数
        private const val STROKE_MAX_POINTS = 1_024
        private const val STROKE_MAX_MS = 10_000L
        private const val CMD_TIMEOUT_S = 10L
        private const val COMMAND_OUTPUT_MAX = 64 * 1024
        private const val READER_JOIN_MS = 1_000L
        private val MODE_RE = Regex("[a-z_]+")
        private const val PER_USER_RANGE = 100_000

        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3
        /** 冷启动被改成了普通启动:App 在 Mika 的屏以外还有任务。 */
        const val START_OK_COLD_IGNORED = 4
        /** startView:没有 App 能打开这个链接。 */
        const val START_UNRESOLVED = 5
        /** startView:链接不允许打开(见 [ShellLinks])。 */
        const val START_INVALID_LINK = 6
        /** 这个 App 正在 Mika 的另一块屏上给别的任务用。 */
        const val START_IN_USE_BY_OTHER = 7
        /** startAppInUser:那份用户不在能用之列(见 [AppCopies])。 */
        const val START_USER_NOT_ALLOWED = 8

        // appCopyUsers 的 Bundle
        const val KEY_COPY_USERS = "users"
        const val KEY_COPY_SUMMARY = "summary"
        // UserProperties.PROFILE_API_VISIBILITY_HIDDEN
        private const val PROFILE_API_VISIBILITY_HIDDEN = 1

        // setClipText 的结果
        const val CLIP_SET = 0
        const val CLIP_UNREADABLE = 1
        const val CLIP_FAILED = 2
        private const val CLIP_LABEL = "Mika"
        // AppOpsManager 里这个名字是隐藏的
        private const val OPSTR_READ_CLIPBOARD = "android:read_clipboard"
        private const val READ_CLIPBOARD_IN_BACKGROUND = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"

        private val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }
        private val WCT: Class<*> by lazy { Class.forName("android.window.WindowContainerTransaction") }
        private const val INJECT_MODE_ASYNC = 0 // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
        private const val INJECT_MODE_WAIT_FOR_RESULT = 1 // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
        private const val INJECT_MODE_WAIT_FOR_FINISH = 2 // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
        private const val VIRTUAL_KEYBOARD = -1 // KeyCharacterMap.VIRTUAL_KEYBOARD
        private const val USER_CURRENT = -2 // UserHandle.USER_CURRENT
        // WindowConfiguration.ACTIVITY_TYPE_*:桌面、最近任务、助理等是别的种类
        private const val ACTIVITY_TYPE_UNDEFINED = 0
        private const val ACTIVITY_TYPE_STANDARD = 1

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_*(部分为隐藏常量)
        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        /**
         * 建虚拟屏用的标志。[trusted] 为 false 时去掉依赖可信屏的那几项:有的系统里 shell 没有
         * ADD_TRUSTED_DISPLAY,带上 TRUSTED 会被系统以 SecurityException 拒绝;OWN_DISPLAY_GROUP、ALWAYS_UNLOCKED
         * 和 OWN_FOCUS/STEAL_TOP_FOCUS_DISABLED 同样只对可信屏可用。其余几项与可信与否无关,照旧。
         */
        internal fun displayFlags(sdk: Int, trusted: Boolean): Int {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
                if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            }
            // 只有 OWN_FOCUS 时,虚拟屏上启动 App 或 App 自己开新页面都会让它成为「最上层焦点屏」,
            // 主屏的焦点窗口随之清空;STEAL_TOP_FOCUS_DISABLED(需同时有 OWN_FOCUS、TRUSTED)让它不抢主屏的焦点
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
                flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            }
            return flags
        }

        /**
         * 注入方式。按键等到系统定下投给谁(WAIT_FOR_RESULT):屏上没有能接按键的窗口时注入报失败,调用方才会退回 shell 命令
         * 或报动作失败,不再「发了就算成」;只等派发、不等 App 处理完,最多等到系统自己的注入超时。
         * 触摸照旧不等(ASYNC):一笔划要按时间逐点送,等派发会打乱快慢。[waitForFinish] 的(主屏解锁输入)等 App 处理完。
         */
        internal fun injectMode(key: Boolean, waitForFinish: Boolean): Int = when {
            waitForFinish -> INJECT_MODE_WAIT_FOR_FINISH
            key -> INJECT_MODE_WAIT_FOR_RESULT
            else -> INJECT_MODE_ASYNC
        }

        /**
         * 一对按下/抬起:只有按下就没注入成功时才走 [fallback](shell 命令);按下已送达后再退回会造成一次多余的完整点击/按键,
         * 这时只补发抬起(失败重试一次)。
         */
        internal inline fun sendPair(down: () -> Boolean, up: () -> Boolean, fallback: () -> Boolean): Boolean {
            if (!down()) return fallback()
            return up() || up()
        }

        /**
         * 有没有可定向注入的目标 uid。安卓 13 起才有定向注入重载(`injectInputEvent(event, mode, targetUid)`),
         * 更早只能走非定向注入;包名缺失或 uid 解析为负(未装)也视为不可定向。
         */
        internal fun resolveTargetUid(sdkInt: Int, packageName: String?, userId: Int, uidLookup: (String, Int) -> Int): Int? =
            if (sdkInt < 33 || packageName == null) null else uidLookup(packageName, userId).takeIf { it >= 0 }

        /** 只有缺权限(SecurityException,如 shell 没有 ADD_TRUSTED_DISPLAY)才值得去掉可信标志再试;别的失败照旧报错。 */
        internal fun retryWithoutTrust(t: Throwable): Boolean = t is SecurityException

        // WindowManager.DISPLAY_IME_POLICY_LOCAL = 0, _FALLBACK_DISPLAY = 1, _HIDE = 2。
        // 真机(安卓 14)实测 LOCAL:在虚拟屏聚焦输入框后输入法窗口出现在虚拟屏上,主屏焦点不变,无需 HIDE。
        private const val IME_POLICY = 0

        // SystemUI 的约定:来自 shell 的剪贴板带上它就不显示「已复制」浮层(否则会弹在用户的主屏上)
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE" // ClipDescription.EXTRA_IS_SENSITIVE(API 33)
        private const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"

        /**
         * 一个任务(页面所在的包 [taskPackages]、所属用户 [taskUser],读不到为 null)是不是 [packageName] 在 [userId] 那份用户里的那份。
         * [userId] 为空时不分哪一份;任务的用户读不到时算是(宁可当成占用,不冒险打扰用户)。
         */
        internal fun sameCopy(taskPackages: Collection<String>, taskUser: Int?, packageName: String, userId: Int?): Boolean =
            packageName in taskPackages && (userId == null || taskUser == null || taskUser == userId)

        /** 解析 `appops get <pkg> PLAY_AUDIO`:"PLAY_AUDIO: ignore; time=..." → "ignore";未设置 → "default"。 */
        internal fun parsePlayAudioMode(output: String): String = AudioMuteLedger.parseSetMode(output) ?: "default"
    }
}
