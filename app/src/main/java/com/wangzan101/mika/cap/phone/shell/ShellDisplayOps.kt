package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TASK_INFO
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.FLAG_OWN_DISPLAY_GROUP
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.displayFlags
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.IME_POLICY

// 助手服务:虚拟屏创建、亮屏保活、方向同步与窗口模式
internal fun ShellService.createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean): Int {
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

internal fun ShellService.pokeDisplay(displayId: Int): Boolean {
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
internal fun ShellService.displayGroup(displayId: Int): Int? = runCatching {
    val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
    val info = global.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(global, displayId)!!
    info.javaClass.getField("displayGroupId").getInt(info)
}.getOrNull()

/** 只往这个服务自己建的虚拟屏上启动,调用方传错屏号(比如主屏)也不会打扰用户。 */
internal fun ShellService.ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

/**
 * STEAL_TOP_FOCUS_DISABLED 保住主屏焦点,但有的系统因此不再采纳后台 Activity 的转屏请求。
 * 有 Mika 的屏时开一个方向监听,只纠正自己这块屏的方向,保留原来的焦点隔离。
 * 监听在锁外建(注册要走 binder);两次建屏同时进来时只留一个,多出来的关掉。
 */
internal fun ShellService.ensureOrientationWatch() {
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
internal fun ShellService.syncDisplayOrientations() {
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
 * 只设置刚建好的 Mika 屏,不改主屏。有的系统会把新建的外接/虚拟屏默认设成 freeform,
 * App 因此缩成小窗,无障碍坐标还会受窗口缩放影响。建屏后、启动任何页面前明确使用全屏模式,
 * 后续的 App 启动、页面跳转和从主屏挪回来的任务都沿用这块屏的策略。
 */
@SuppressLint("PrivateApi")
internal fun ShellService.setFullscreenWindowingMode(displayId: Int) {
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
internal fun ShellService.setImePolicy(displayId: Int) {
    runCatching {
        val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
        wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, displayId, IME_POLICY)
    }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
}
