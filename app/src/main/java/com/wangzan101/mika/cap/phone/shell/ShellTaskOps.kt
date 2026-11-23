package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.ComponentName
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.wangzan101.mika.cap.phone.shell.ShellService.Behind
import com.wangzan101.mika.cap.phone.shell.ShellService.Sorting
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.MAX_TASKS
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.PER_USER_RANGE
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TASK_INFO
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.WCT
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.ACTIVITY_TYPE_UNDEFINED
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.ACTIVITY_TYPE_STANDARD
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.resolveTargetUid
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.sameCopy

// 助手服务:任务栈查询、挪屏、留在后台与收尾关闭
/** 该包在主屏有可见任务,或者就是主屏最上面的任务。[userId] 不为空时只看那份用户里的(见 [sameCopy])。 */
internal fun ShellService.inUseOnMainScreen(packageName: String, userId: Int? = null): Boolean {
    val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
    return tasksOn(Display.DEFAULT_DISPLAY).withIndex().any { (index, task) ->
        isCopy(task, packageName, userId) && (visibleField?.getBoolean(task) ?: (index == 0))
    }
}

/**
 * IActivityTaskManager.getTasks:按最近使用排序,shell 持有 REAL_GET_TASKS,能看到所有 App 的任务。
 * API 33+ 有带 displayId 的重载,优先用它;更早的版本取全部再按 TaskInfo.displayId 过滤。
 */
internal fun ShellService.tasksOn(displayId: Int): List<Any> {
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
internal fun ShellService.allTasks(): List<Any> {
    val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
    val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
    val tasks = if (withDisplay != null) {
        withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
    } else {
        methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
    } as List<*>
    return tasks.filterNotNull()
}

internal fun ShellService.task(taskId: Int): Any? {
    val idField = TASK_INFO.getField("taskId")
    return allTasks().firstOrNull { idField.getInt(it) == taskId }
}

internal fun ShellService.sortAndMove(displayId: Int, keep: Array<String>?, close: Array<String>?): Sorting {
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
internal fun ShellService.closeOut(displayId: Int, sorting: Sorting, settled: SettleBehind.Plan.Done?): Bundle {
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
internal fun ShellService.sendBackToClose(taskId: Int, displayId: Int) {
    if (!moveRoot(taskId, displayId)) removeTask(taskId)
}

/** 任务的一行给 [AppsAfterTask.decide]:键、是不是普通 App 的页面、是不是 Mika 自己的、此刻出不出声。 */
internal fun ShellService.afterTaskRow(t: Any, playing: Map<Int, Set<String>>?): AppsAfterTask.Task {
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

/** 这个任务的 App(同一份用户里的)此刻在出声;读不到为 null。 */
internal fun ShellService.playingIn(task: Any, playing: Map<Int, Set<String>>?): Boolean? {
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
internal fun ShellService.playingUids(): Map<Int, Set<String>>? {
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
internal fun ShellService.keepBehind(row: Any): Behind {
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
internal fun ShellService.seenOnMain(ids: Set<Int>): List<SettleBehind.Seen> {
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
internal fun ShellService.settleBehind(moved: Set<Int>): SettleBehind.Plan.Done? = try {
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
internal fun ShellService.sendToBack(ids: Set<Int>) {
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
internal fun ShellService.moveRoot(taskId: Int, displayId: Int): Boolean = moveTask(taskId, displayId)

/** IActivityTaskManager.removeTask(关掉任务,和最近任务里划掉一样);不行退回 `am stack remove`。 */
internal fun ShellService.removeTask(taskId: Int): Boolean = try {
    taskManager.javaClass.getMethod("removeTask", Int::class.java).invoke(taskManager, taskId) as Boolean
} catch (t: Throwable) {
    Log.w(TAG, "removeTask unavailable, falling back to am", t)
    exec("am", "stack", "remove", "$taskId") == 0
}

/** 建一个 WindowContainerTransaction,经 IWindowOrganizerController.applyTransaction 交给系统;哪一步不行返回 false。 */
@SuppressLint("PrivateApi")
internal fun ShellService.transact(build: (Any) -> Unit): Boolean = try {
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
internal fun ShellService.wctCall(wct: Any, method: String, token: Any, flag: Boolean) {
    WCT.getMethod(method, Class.forName("android.window.WindowContainerToken"), Boolean::class.javaPrimitiveType).invoke(wct, token, flag)
}

/** 主屏上(没在用户眼前)的、上次任务留下的这份 App 的任务。 */
internal fun ShellService.keptOnMain(packageName: String, userId: Int): List<Any> {
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
internal fun ShellService.resumeKept(displayId: Int, packageName: String, userId: Int): Boolean {
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
internal fun ShellService.dropKept(packageName: String, userId: Int) {
    keptOnMain(packageName, userId).forEach { t ->
        val id = TASK_INFO.getField("taskId").getInt(t)
        if (removeTask(id)) synchronized(kept) { kept.remove(id) }
    }
}

/** 所有屏上的任务,换成 [MainScreenLog.Task];页面种类读不到时当作普通 App 的页面。 */
internal fun ShellService.taskRows(): List<MainScreenLog.Task> {
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

/** 虚拟屏顶部任务的 uid(定向注入的目标);解析不到返回 null(退回非定向注入)。 */
internal fun ShellService.topTaskUid(displayId: Int): Int? = try {
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
