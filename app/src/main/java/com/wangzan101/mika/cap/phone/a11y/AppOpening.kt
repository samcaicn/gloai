package com.wangzan101.mika.cap.phone.a11y

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.AppWait
import com.wangzan101.mika.core.screen.uistate.LaunchChooser
import com.wangzan101.mika.cap.phone.shell.ShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 打开 App / 分享 / 链接:启动到虚拟屏,等它到前台,认系统问打开哪一个的选择框。
 * 共享 [AndroidScreen] 的字段与方法经 [screen] 后向引用访问。
 */
internal class AppOpening(private val screen: AndroidScreen) {

    suspend fun openApp(packageName: String, cold: Boolean): ActionResult = open(packageName, null, cold)

    /**
     * 打开装在另一份用户 [userId] 里的那份(调用方先按 [appCopies] 核对过):内置助手启动前再按同一规则核一次那份用户能不能用,
     * 不能用为 copy_not_found;占用、冷启动都只看这一份。[userId] 就是 Mika 自己的用户时等于 [openApp]。
     */
    suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult =
        open(packageName, userId.takeIf { it != AndroidScreen.myUser }, cold)

    /**
     * 等别的任务放开 [packageName](模型打开它时要了等):放开指别的屏上已经没有它、占用登记里也没有别的任务占着。
     * 只在并行任务时有意义,否则没有别的任务和它争。
     */
    suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
        if (!screen.checkOtherTasks) return AppWait.Unsupported
        val user = userId?.takeIf { it != AndroidScreen.myUser }
        return screen.awaitApp(packageName, user, timeoutMs) {
            withContext(Dispatchers.IO) { !onOtherDisplay(packageName, user) && screen.appFree(packageName, user) }
        }
    }

    /** 这个包([user] 那份)此刻开在 Mika 的另一块屏上(别的任务手里)。 */
    internal fun onOtherDisplay(packageName: String, user: Int?): Boolean {
        val other = runCatching { if (user == null) screen.shell.displayOfPackage(packageName) else screen.shell.displayOfPackageInUser(packageName, user) }.getOrDefault(-1)
        return other >= 0 && other != screen.display.current?.displayId
    }

    /** [openApp] / [openAppCopy] 的共同部分;[user] 为空是本来那份。 */
    internal suspend fun open(packageName: String, user: Int?, cold: Boolean): ActionResult = withContext(Dispatchers.IO) {
        // 同一个 App 不能同时在两块屏上:系统会把整个任务挪过来,等于从另一个任务手里抢走它。装了两份的,两份各算各的
        if (screen.checkOtherTasks) {
            if (onOtherDisplay(packageName, user)) return@withContext AndroidScreen.IN_USE_BY_OTHER
            if (!(if (user == null) screen.reserve(packageName) else screen.reserveCopy(packageName, user))) return@withContext AndroidScreen.IN_USE_BY_OTHER
        }
        // 打开前最上面的窗口:之后冒出来的才可能是这次打开引起的选择框(上次没选、又打开一次时还是同一个选择框)
        val before = if (screen.hasDisplay) screen.topWindowPackage().takeIf { it != screen.chooserPackage } else null
        val id = screen.display.get().displayId
        // 画面新鲜度从这次启动开始算:之后的截图、认字只认这之后到的帧(见 FrameFreshness)
        val startedAt = System.nanoTime()
        screen.beforeOpen(packageName, user)
        fun launch() = when {
            user != null -> screen.shell.startAppInUser(id, packageName, user, cold)
            cold -> screen.shell.restartApp(id, packageName)
            else -> screen.shell.startApp(id, packageName)
        }
        var code = launch()
        val retryUntil = SystemClock.uptimeMillis() + AndroidScreen.IN_USE_RETRY_MS
        while (code == ShellService.START_IN_USE_ON_MAIN && SystemClock.uptimeMillis() < retryUntil) {
            delay(AndroidScreen.IN_USE_POLL_MS)
            code = launch()
        }
        if (code != ShellService.START_OK && code != ShellService.START_OK_COLD_IGNORED) screen.notOpened(packageName, user)
        when (code) {
            ShellService.START_OK, ShellService.START_OK_COLD_IGNORED -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext AndroidScreen.IN_USE_BY_OTHER
            ShellService.START_NOT_FOUND -> return@withContext ActionResult.Failed("app_not_found")
            ShellService.START_USER_NOT_ALLOWED -> return@withContext ActionResult.Failed(AndroidScreen.COPY_NOT_FOUND)
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        // 同一个 App 只是回到已有静态页时可能不会来新帧,旧帧仍就是当前画面;冷启动、另一份用户或换 App 必须等新帧。
        val needsNewFrame = cold || user != null || before != packageName
        awaitOpened(packageName, before, hint = if (code == ShellService.START_OK_COLD_IGNORED) AndroidScreen.HINT_COLD_IGNORED else null, user = user,
            launchedAt = if (needsNewFrame) startedAt else 0L)
    }

    /**
     * 把文字分享给 [packageName]:和 [openApp] 一样第一次用时才建屏、用户正在主屏用它时不打开,打开后同样等它到前台、
     * 认系统问打开哪一个。这个 App 不收文字分享为 cannot_share。只打开分享页,发不发由模型在那一页上点。
     */
    suspend fun shareText(packageName: String, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (screen.checkOtherTasks) {
            val other = runCatching { screen.shell.displayOfPackage(packageName) }.getOrDefault(-1)
            if (other >= 0 && other != screen.display.current?.displayId) return@withContext AndroidScreen.IN_USE_BY_OTHER
            if (!screen.reserve(packageName)) return@withContext AndroidScreen.IN_USE_BY_OTHER
        }
        val before = if (screen.hasDisplay) screen.topWindowPackage().takeIf { it != screen.chooserPackage } else null
        val id = screen.display.get().displayId
        val startedAt = System.nanoTime()
        screen.beforeOpen(packageName, null)
        val code = runCatching { screen.shell.startShare(id, packageName, text) }.getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK) screen.notOpened(packageName, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext AndroidScreen.IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("cannot_share")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(packageName, before, hint = null, sharing = true, launchedAt = startedAt)
    }

    val canShareAudio: Boolean get() = screen.shareAudioFile != null
    val canShareImage: Boolean get() = screen.shareImageFile != null

    /** 和 [shareText] 相同的打开/占用/等待流程;音频先由 [screen.shareAudioFile] 变成 content URI。只打开分享页,不发出。 */
    suspend fun shareAudio(packageName: String, path: String, text: String?): ActionResult = withContext(Dispatchers.IO) {
        val share = screen.shareAudioFile?.invoke(path) ?: return@withContext ActionResult.Failed("unsupported")
        if (screen.checkOtherTasks) {
            val other = runCatching { screen.shell.displayOfPackage(packageName) }.getOrDefault(-1)
            if (other >= 0 && other != screen.display.current?.displayId) return@withContext AndroidScreen.IN_USE_BY_OTHER
            if (!screen.reserve(packageName)) return@withContext AndroidScreen.IN_USE_BY_OTHER
        }
        val before = if (screen.hasDisplay) screen.topWindowPackage().takeIf { it != screen.chooserPackage } else null
        val id = screen.display.get().displayId
        val startedAt = System.nanoTime()
        screen.beforeOpen(packageName, null)
        val code = runCatching { screen.shell.startShareFile(id, packageName, share.uri.toString(), share.mime, text.orEmpty()) }
            .getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK) screen.notOpened(packageName, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext AndroidScreen.IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("cannot_share")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(packageName, before, hint = null, sharing = true, launchedAt = startedAt)
    }

    /** 和 [shareAudio] 相同,只是把图片交给目标 App。 */
    suspend fun shareImage(packageName: String, path: String, text: String?): ActionResult = withContext(Dispatchers.IO) {
        val share = screen.shareImageFile?.invoke(path) ?: return@withContext ActionResult.Failed("unsupported")
        if (screen.checkOtherTasks) {
            val other = runCatching { screen.shell.displayOfPackage(packageName) }.getOrDefault(-1)
            if (other >= 0 && other != screen.display.current?.displayId) return@withContext AndroidScreen.IN_USE_BY_OTHER
            if (!screen.reserve(packageName)) return@withContext AndroidScreen.IN_USE_BY_OTHER
        }
        val before = if (screen.hasDisplay) screen.topWindowPackage().takeIf { it != screen.chooserPackage } else null
        val id = screen.display.get().displayId
        val startedAt = System.nanoTime()
        screen.beforeOpen(packageName, null)
        val code = runCatching { screen.shell.startShareFile(id, packageName, share.uri.toString(), share.mime, text.orEmpty()) }
            .getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK) screen.notOpened(packageName, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext AndroidScreen.IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("cannot_share")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(packageName, before, hint = null, sharing = true, launchedAt = startedAt)
    }

    /**
     * 在 Mika 的屏上打开 Mika 的兼容性自检页([intent] 指明组件;只给兼容性自检用)。那一页只对 shell 这一级的调用方开放,
     * 交给内置助手以 shell 身份直接启动到这块屏(助手只认这一个组件);shell 启动不受后台启动页面的限制,Mika 在不在前台都一样。
     * 之后和 [openApp] 一样等它到 Mika 屏的最上面;第一次用时建屏。
     */
    suspend fun openOwnPage(intent: Intent): ActionResult = withContext(Dispatchers.IO) {
        val before = if (screen.hasDisplay) screen.topWindowPackage().takeIf { it != screen.chooserPackage } else null
        val id = screen.display.get().displayId
        val startedAt = System.nanoTime()
        when (runCatching { screen.shell.startSystemAction(id, intent, null) }.getOrDefault(ShellService.START_ERROR)) {
            ShellService.START_OK -> Unit
            ShellService.START_INVALID_LINK -> return@withContext ActionResult.Failed("own_page_not_allowed")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(screen.a11y.packageName, before, hint = null, launchedAt = if (before != screen.a11y.packageName) startedAt else 0L)
    }

    /**
     * 启动发出之后:等 [packageName] 到 Mika 屏的最上面,或系统先问打开哪一个([before] 是启动前最上面的窗口所在的包)。
     * [sharing]:这次是分享(App 有几个收分享的页面时系统问用哪一个,选项里不一定有 App 名):最上面换成了系统包的窗口也算在问。
     */
    internal suspend fun awaitOpened(
        packageName: String, before: String?, hint: String?, sharing: Boolean = false, user: Int? = null, launchedAt: Long = 0L,
    ): ActionResult {
        val beforeTree = screen.treeHash()
        screen.targetPackage = packageName
        screen.chooserPackage = null
        screen.requestedUser = user
        // 从等待窗口到顶起就保护所有返回路径(包括系统选择器),不是只有最后的成功路径才拦旧帧。
        // 同 App 静态页可以不另等新帧,但不能借此清掉上一次尚未由新帧解除的保护。
        if (launchedAt > 0) screen.frameGuardSince = launchedAt
        // 只有确实还在原来的页面,失败后才能继续用原来的静态帧;已经切走或换页时仍须等新帧。
        fun clearGuardIfUnchanged() {
            val now = screen.foregroundTaskPackage() ?: ScreenReader.topPackage(screen.windows())
            if (now == before && screen.treeHash() == beforeTree) screen.frameGuardSince = null
        }
        // 打开了:Mika 屏上最上面的任务是它(问不到任务时退回看无障碍读到的最上面的窗口);指定了哪一份时还得是那份用户的任务
        fun launched(): Boolean = (screen.foregroundTaskPackage()?.let { it == packageName } ?: (ScreenReader.topPackage(screen.windows()) == packageName)) &&
            (user == null || screen.userOf(runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)).let { it < 0 || it == user })
        fun picker(): String? = if (sharing) screen.systemPickerOf(packageName, before) else null
        val deadline = SystemClock.uptimeMillis() + AndroidScreen.LAUNCH_WAIT_MS
        // 系统先问打开哪一个时 App 不会自己到前台:看到选择框就不再干等
        while (!launched() && screen.chooserOf(packageName, before) == null && picker() == null && SystemClock.uptimeMillis() < deadline) delay(AndroidScreen.POLL_MS)
        screen.waitStable(AndroidScreen.SETTLE_CAP_MS)
        val onTop = launched()
        val task = if (onTop) screen.shell.topTaskId(screen.displayId) else -1
        screen._target.value = AndroidScreen.TargetApp(packageName, task, if (task >= 0) screen.userOf(task) else user ?: -1)
        if (!onTop && user != null) {
            // 要的是另一份,系统却先问打开哪一个:自己逐个点选项,认开出来的是哪一份,不交给模型猜
            if (chooserTargets(packageName, before) != null) return resolveCopyChooser(packageName, user, before, hint)
                .also { if (it is ActionResult.Failed) clearGuardIfUnchanged() }
            // 直接开出来的是这个 App 的别的那份:照实失败,不当成要的那份
            if (screen.foregroundTaskPackage() == packageName) return copyNotOpened(user, screen.userOf(runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)))
                .also { clearGuardIfUnchanged() }
        }
        // 系统在问打开哪一个(App 装了两份等):算打开了,等模型选;选完 App 到了前台,之后的动作照常认它是目标。
        // App 已在最上面时盖着的系统窗口(权限框)不算
        if (!onTop) screen.chooserOf(packageName, before)?.let { first ->
            // 第一次碰到时才去问装了几份,好标出哪个是另一份
            if (screen.profiles == null) screen.otherProfiles()
            val choice = screen.chooserOf(packageName, before) ?: first
            screen.chooserPackage = choice.packageName
            return screen.ok().copy(chooser = choice, appSwitched = null, switchedByTarget = false)
        }
        // 分享时系统问用这个 App 的哪个分享页:同样算打开了,等模型选
        if (!onTop) picker()?.let { pkg ->
            screen.chooserPackage = pkg
            return screen.ok(hint = AndroidScreen.HINT_SHARE_PICKER).copy(appSwitched = null, switchedByTarget = false)
        }
        // 没打开就照实说,并说清此刻最上面的是什么,不报成功
        if (!onTop) {
            clearGuardIfUnchanged()
            val now = screen.foregroundTaskPackage() ?: ScreenReader.topPackage(screen.windows())
            // 要的是另一份,最上面却是这个 App 的别的那份
            val copy = if (user != null && now == packageName) " (a different copy of the app)" else ""
            return ActionResult.Failed("app_not_in_front", hint = now?.let { "In the foreground now: $it$copy." } ?: "Nothing came to the foreground.")
        }
        // 树稳定只是窗口到了顶,画面可能还没合成:等这次启动之后的第一帧;等不到就持续拦住旧帧,
        // 不把上一屏留在缓冲里的残影当这次打开的画面交给模型(issue #253)
        if (launchedAt > 0) screen.awaitFrameAfter(launchedAt)
        // 打开了,但另一个包的窗口盖在上面(权限弹窗、账号选择器):照实说是谁,仍算打开了
        val cover = ScreenReader.topPackage(screen.windows())?.takeIf { it != packageName }
        return screen.ok(hint = hint).let { if (cover != null) it.copy(coveredBy = cover, appSwitched = null, switchedByTarget = false) else it }
    }

    /**
     * 要的是另一份用户 [user] 里的那份,系统却在 Mika 屏上问打开哪一个(选项可能名字一模一样,看不出哪个是哪份):
     * 逐个点选项,等开出来,读最上面任务所属的用户;是要的那份就算打开。不是就按一次返回让它退到后台(那可能是用户手上那份的任务,
     * 不结束它),再打开一次、点下一个。都试过还不是,照实失败 copy_not_opened,说开出来的是哪一份;不把错的那份当成功。
     */
    internal suspend fun resolveCopyChooser(packageName: String, user: Int, before: String?, hint: String?): ActionResult {
        fun topTask() = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        suspend fun opened(): ActionResult? {
            if (screen.foregroundTaskPackage() != packageName) return null
            val task = topTask()
            if (screen.userOf(task) != user) return null
            screen._target.value = AndroidScreen.TargetApp(packageName, task, user)
            return screen.ok(hint = hint)
        }
        var tried = 0
        var shown: Int? = null
        while (true) {
            val targets = chooserTargets(packageName, before) ?: break
            if (tried >= targets.size) break
            val (x, y) = targets[tried++]
            if (!runCatching { screen.shell.injectTap(screen.displayId, x, y) }.getOrDefault(false)) break
            val deadline = SystemClock.uptimeMillis() + AndroidScreen.LAUNCH_WAIT_MS
            while (screen.foregroundTaskPackage() != packageName && SystemClock.uptimeMillis() < deadline) delay(AndroidScreen.POLL_MS)
            screen.waitStable(AndroidScreen.SETTLE_CAP_MS)
            if (screen.foregroundTaskPackage() != packageName) break
            opened()?.let { return it }
            shown = screen.userOf(topTask())
            Log.i(AndroidScreen.TAG, "chooser option $tried opened user $shown, not the requested $user")
            runCatching { screen.shell.injectKey(screen.displayId, KeyEvent.KEYCODE_BACK) }
            screen.waitStable(AndroidScreen.SETTLE_CAP_MS)
            if (tried >= targets.size) break
            if (runCatching { screen.shell.startAppInUser(screen.displayId, packageName, user, false) }.getOrDefault(ShellService.START_ERROR) != ShellService.START_OK) break
            val until = SystemClock.uptimeMillis() + AndroidScreen.LAUNCH_WAIT_MS
            while (chooserTargets(packageName, before) == null && SystemClock.uptimeMillis() < until) {
                // 这一次直接开出了要的那份
                opened()?.let { return it }
                delay(AndroidScreen.POLL_MS)
            }
        }
        return copyNotOpened(user, shown)
    }

    /** 要的那份 [user] 没打开;[shown] 是开出来的那份所属的用户(没开出来或读不到为 null / 负数)。 */
    internal fun copyNotOpened(user: Int, shown: Int?): ActionResult.Failed {
        val what = when {
            shown == null || shown < 0 -> "Nothing from the app came to the front."
            shown == AndroidScreen.myUser -> "The main app opened instead (user $shown)."
            else -> "The copy in user $shown opened instead."
        }
        return ActionResult.Failed(AndroidScreen.COPY_NOT_OPENED, "$what Requested: the copy in user $user.")
    }

    /** 系统在 Mika 屏上问打开哪一个时,那几个选项的中心点(屏上像素,先后同选项);不是在问为 null。认法同 [chooserOf]。 */
    internal fun chooserTargets(packageName: String, before: String?): List<Pair<Int, Int>>? {
        val w = ScreenReader.contentWindows(screen.windows()).firstOrNull { it.root != null } ?: return null
        val pkg = w.root?.packageName?.toString() ?: return null
        if (pkg == packageName || pkg == before || !screen.isSystemPackage(pkg)) return null
        val label = screen.appLabel(packageName) ?: return null
        val entries = ScreenReader.read(listOf(w), screen.screenWidth, screen.screenHeight).entries
        val picked = LaunchChooser.optionIndices(label, entries.map { it.element }) ?: return null
        return picked.map { i -> android.graphics.Rect().also(entries[i].node::getBoundsInScreen).let { it.centerX() to it.centerY() } }
    }
}
