package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.wangzan101.mika.core.device.shell.DevtoolsSocket
import com.wangzan101.mika.core.screen.uistate.AppCopies
import com.wangzan101.mika.core.screen.uistate.PressKeys
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    internal val allowedUid: Int,
    /** 静音记录放的目录;shell 身份能写的位置。 */
    stateDir: File = File("/data/local/tmp"),
) : IMikaShell.Stub() {
    internal val context: Context = ShellContext.create()
    internal val displays = HashMap<Int, VirtualDisplay>()
    internal var orientationWatch: DisplayOrientationWatch? = null
    internal val requestedOrientations = ConcurrentHashMap<Int, Int>()
    /** 被静音的包 → 静音前的 PLAY_AUDIO 模式("default" 表示原先未设置),落在 [stateDir] 里,进程被杀后下次启动接着恢复。 */
    internal val muted = AudioMuteLedger(File(stateDir, "mika_audio_mute_$allowedUid"))
    internal val callAudio = ShellCallAudio(context)
    // Bundle 将嵌套的 PFD 复制到 reply，但不会关掉服务端原件。写完 reply 后关闭，
    // 否则客户端关掉管道时，音频线程永远收不到 EOF。
    internal val returnedCallPipes = ThreadLocal<List<ParcelFileDescriptor>>()
    internal data class FilePolicyState(val session: String, val revision: Long, val roots: List<String>, val recursive: List<Boolean>, val policy: DeviceFilePolicy)
    internal val filePolicyLock = Any()
    @Volatile internal var filePolicy: FilePolicyState? = null
    /** Mika 所在的用户:本来那份 App 装在这里;别的用户里的是另一份(分身、工作资料)。 */
    internal val myUser = allowedUid / PER_USER_RANGE

    // 不能用 getSystemService:那样拿到的 DisplayManager 绑的是系统上下文,包名与 shell uid 对不上
    @SuppressLint("PrivateApi")
    internal val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    // 剪贴板要以 shell 包名访问(系统只对来自 shell 的剪贴板认「不弹已复制浮层」的标记);读取绕开 ClipboardManager 里 shell 进程走不通的 Settings 查询
    internal val clipboard = ShellClipboard(context)
    internal val clipLock = Any()
    /** 查占用和启动之间不能插进另一个任务的启动:两个任务同时开同一个 App 时只有一个能成。 */
    internal val launchLock = Any()
    /** setClipText 之前用户的剪贴板(null 表示原先确实是空的,读不到的不会换出);[clipSaved] 为 false 表示没有待还原的。 */
    internal var savedClip: ClipData? = null
    internal var clipSaved = false
    /** Mika 最后放进去的文字;还原前据此判断剪贴板还是不是 Mika 的(用户中途复制了别的就不动)。 */
    internal var ourText: String? = null

    internal val taskManager: Any by lazy {
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

    override fun resizeDisplay(displayId: Int, surface: Surface, width: Int, height: Int, dpi: Int): Boolean = synchronized(displays) {
        val vd = displays[displayId] ?: return@synchronized false
        if (width !in 1..4096 || height !in 1..4096 || dpi !in 72..1000 || !surface.isValid) return@synchronized false
        val old = android.util.DisplayMetrics().also { vd.display.getRealMetrics(it) }
        val oldMode = vd.display.mode
        val oldSurface = vd.surface
        try {
            vd.resize(width, height, dpi)
            vd.surface = surface
            oldSurface?.release()
            true
        } catch (e: Exception) {
            runCatching { vd.resize(oldMode.physicalWidth, oldMode.physicalHeight, old.densityDpi); vd.surface = oldSurface }
            Log.w(TAG, "resizeDisplay failed", e)
            false
        }
    }

    /** 自成一组的虚拟屏一直不睡,见 [DisplayAwake]。 */
    internal val awake by lazy {
        val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "mika-display-awake").apply { isDaemon = true } }
        DisplayAwake(::pokeDisplay) { r ->
            val f = timer.scheduleWithFixedDelay(r, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
            return@DisplayAwake { f.cancel(false) }
        }
    }

    /** IPowerManager 和它按屏报用户操作的方法;拿不到为 null(只记一次,屏照常用,只是没人替它续亮)。 */
    @get:SuppressLint("PrivateApi")
    internal val userActivity: Pair<Any, Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "power")
            val power = Class.forName("android.os.IPowerManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
            power to (DisplayAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

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
     * 先确认用户没在主屏用这个 App:若它在主屏有可见的任务就不启动(返回 [START_IN_USE_ON_MAIN]),
     * 否则系统会把用户正在看的界面挪走或打断。后台任务可以被拉到虚拟屏上。
     * 再解析启动入口(包里有些 Activity 是跳板,直接启动会立刻跳走、只在虚拟屏留下空任务),
     * 以 shell 包名调用 IActivityTaskManager.startActivityAsUser,带 ActivityOptions.setLaunchDisplayId。
     * 占用只看本来那份(Mika 所在用户里的):用户在主屏用着装在别的用户里的那份时,不挡这一份。
     */
    override fun startApp(displayId: Int, packageName: String): Int = start(displayId, packageName, myUser)

    /** ILauncherApps(LauncherApps 背后的系统服务);拿不到为 null。 */
    internal val launcherApps: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "launcherapps")
            Class.forName("android.content.pm.ILauncherApps\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        }.onFailure { Log.w(TAG, "launcherapps service unavailable", it) }.getOrNull()
    }

    /**
     * 只有这个 App 的任务全在 Mika 的屏上时才结束进程再启动(回到初始页面)。
     * 它在别的屏上还有任务(用户的状态)就绝不结束,改为普通启动并返回 [START_OK_COLD_IGNORED];
     * 用户正在主屏用它时不启动。
     */
    override fun restartApp(displayId: Int, packageName: String): Int = restart(displayId, packageName, myUser)

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
    internal val kept = HashMap<Int, String>()

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
    internal class Sorting(
        val moved: MutableList<AppsAfterTask.Fate>,
        val closed: MutableList<String>,
        val notes: MutableSet<String>,
        val hidden: Set<Int>,
    )

    /** OK:挪到主屏最后面了;HIDDEN:挪过去了,但取消隐藏没做成,还藏着;UNSUPPORTED:这台系统挪不了。 */
    internal enum class Behind { OK, HIDDEN, UNSUPPORTED }

    internal val ownPackages: Set<String> by lazy {
        runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toSet() }.getOrNull().orEmpty()
    }

    /** 主屏任务的记录:Agent 动作前开始盯,见 [MainScreenWatch]。第一次用到才建(线程、任务栈监听)。 */
    internal val mainWatch by lazy { MainScreenWatch({ taskManager }, ::taskRows) }

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

    /** 启动时把上次没来得及恢复的包还原(只动记录里的)。 */
    fun recoverMuted() {
        synchronized(muted) { muted.restoreAll(::writeRecorded) }
    }

    override fun getAudioMode(packageName: String): String? = audioMode(packageName, null)

    override fun getAudioModeInUser(packageName: String, userId: Int): String? {
        if (userId == myUser) return audioMode(packageName, null)
        return audioMode(packageName, otherUser(userId) ?: return null)
    }

    /** "default"(没设置过)按 allow 写,理由同 [AudioMuteLedger.restoreMode]。 */
    override fun setAudioMode(packageName: String, mode: String): Boolean = audioMode(packageName, null, mode)

    override fun setAudioModeInUser(packageName: String, userId: Int, mode: String): Boolean {
        if (userId == myUser) return audioMode(packageName, null, mode)
        return audioMode(packageName, otherUser(userId) ?: return false, mode)
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

    internal val deviceUnlock by lazy { DeviceUnlockInput(context,
        inject = { inject(it, Display.DEFAULT_DISPLAY, waitForFinish = true) },
        injectTo = { event, uid -> injectTo(event, Display.DEFAULT_DISPLAY, uid) },
        command = { exec(*it.toTypedArray()) == 0 }) }

    override fun wakeDevice(): Boolean = deviceUnlock.wake()
    override fun dismissDeviceKeyguard(): Boolean = deviceUnlock.dismiss()
    override fun revealDeviceCredential(): Boolean = deviceUnlock.reveal()
    override fun revealDeviceCredentialTap(x: Int, y: Int): Boolean = deviceUnlock.tap(x, y)
    override fun enterDeviceCredential(kind: Int, credential: ByteArray, clear: Int): Boolean = deviceUnlock.enter(kind, credential, clear)

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

    companion object {
        internal const val TAG = "MikaShell"
        internal const val MAX_TASKS = 100
        internal const val TAP_MS = 50L
        // injectStroke 一笔的上限:点数、从按下到抬起的毫秒数
        internal const val STROKE_MAX_POINTS = 1_024
        internal const val STROKE_MAX_MS = 10_000L
        internal const val CMD_TIMEOUT_S = 10L
        internal const val COMMAND_OUTPUT_MAX = 64 * 1024
        internal const val READER_JOIN_MS = 1_000L
        internal val MODE_RE = Regex("[a-z_]+")
        internal const val PER_USER_RANGE = 100_000

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
        internal const val PROFILE_API_VISIBILITY_HIDDEN = 1

        // setClipText 的结果
        const val CLIP_SET = 0
        const val CLIP_UNREADABLE = 1
        const val CLIP_FAILED = 2
        internal const val CLIP_LABEL = "Mika"
        // AppOpsManager 里这个名字是隐藏的
        internal const val OPSTR_READ_CLIPBOARD = "android:read_clipboard"
        internal const val READ_CLIPBOARD_IN_BACKGROUND = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"

        internal val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }
        internal val WCT: Class<*> by lazy { Class.forName("android.window.WindowContainerTransaction") }
        internal const val INJECT_MODE_ASYNC = 0 // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
        internal const val INJECT_MODE_WAIT_FOR_RESULT = 1 // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
        internal const val INJECT_MODE_WAIT_FOR_FINISH = 2 // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
        internal const val VIRTUAL_KEYBOARD = -1 // KeyCharacterMap.VIRTUAL_KEYBOARD
        internal const val USER_CURRENT = -2 // UserHandle.USER_CURRENT
        // WindowConfiguration.ACTIVITY_TYPE_*:桌面、最近任务、助理等是别的种类
        internal const val ACTIVITY_TYPE_UNDEFINED = 0
        internal const val ACTIVITY_TYPE_STANDARD = 1

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_*(部分为隐藏常量)
        internal const val FLAG_PUBLIC = 1 shl 0
        internal const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        internal const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        internal const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        internal const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        internal const val FLAG_TRUSTED = 1 shl 10
        internal const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        internal const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        internal const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        internal const val FLAG_OWN_FOCUS = 1 shl 14
        internal const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

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
        internal const val IME_POLICY = 0

        // SystemUI 的约定:来自 shell 的剪贴板带上它就不显示「已复制」浮层(否则会弹在用户的主屏上)
        internal const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE" // ClipDescription.EXTRA_IS_SENSITIVE(API 33)
        internal const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"

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
