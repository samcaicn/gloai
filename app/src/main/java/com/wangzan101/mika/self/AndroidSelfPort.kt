package com.wangzan101.mika.self

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.webkit.WebViewCompat
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.browser.AddressInput
import com.wangzan101.mika.browser.BrowserFavorites
import com.wangzan101.mika.browser.FavoriteSite
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.self.SelfBrowserState
import com.wangzan101.mika.core.self.SelfConsent
import com.wangzan101.mika.core.self.SelfFavorite
import com.wangzan101.mika.core.self.SelfPort
import com.wangzan101.mika.core.self.SelfResult
import com.wangzan101.mika.core.self.SelfScreen
import com.wangzan101.mika.core.self.SelfSite
import com.wangzan101.mika.core.self.SelfStatus
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ProviderConnection
import com.wangzan101.mika.settings.SystemStatusMonitor
import com.wangzan101.mika.settings.WebSearchSettings
import com.wangzan101.mika.task.TriggerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自身端口的安卓实现。只给手机上、有用户的 UI 主任务;每个任务一份,只读状态 + 打开展示。
 *
 * 前台走进程内 [SelfUi] 请求(界面在导航栈里开);后台只发通知,用户点了才进,
 * 不在后台启动 Activity,也不假装用户已经看见了。
 */
class AndroidSelfPort(
    private val app: MikaApp,
    private val conversationId: String,
    private val runKey: String,
) : SelfPort {
    private val nextId = AtomicInteger(1000)

    override suspend fun status(): SelfStatus = withContext(Dispatchers.IO) {
        val flags = FeatureFlags.get(app)
        val operate = flags.selfOperate()
        val monitor = SystemStatusMonitor(app, PhoneServices.of(app).shellControl)
        monitor.refresh()
        val system = monitor.status.value
        val roles = app.providers.roles.value
        val config = roles.main?.let { id -> app.providers.configs.value.firstOrNull { it.id == id } }
        val page = withContext(Dispatchers.Main) { UserBrowser.existing()?.page?.value }
        val host = page?.url?.let(WebUrlPolicy::host)
        val run = app.taskState.run(conversationId)
        val running = run?.state?.value as? TaskState.Running
        SelfStatus(
            appVersion = BuildConfig.VERSION_NAME,
            appBuild = BuildConfig.GIT_HASH,
            beta = BuildConfig.BETA,
            sdk = Build.VERSION.SDK_INT,
            selfOperate = operate,
            selfConfigure = flags.selfConfig(),
            foreground = app.foreground.visible.value,
            mainModel = config?.let(ProviderConnection::displayName),
            accessibility = when (system.accessibility) {
                AccessibilityState.On -> "on"
                AccessibilityState.EnabledNotRunning -> "enabled_not_running"
                AccessibilityState.Off -> "off"
            },
            builtinShell = shellName(system.builtinShell),
            notificationsAllowed = system.notificationsAllowed,
            batteryUnrestricted = runCatching {
                app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName)
            }.getOrNull(),
            browserAvailable = WebViewCompat.getCurrentWebViewPackage(app) != null,
            webSearchConfigured = WebSearchSettings.get(app).service() != null,
            userPageHost = host.takeIf { operate },
            userPageTitle = page?.title?.takeIf { operate },
            keptPages = if (app.pageKeeper.page.value != null) 1 else 0,
            taskState = run?.state?.value?.let(::stateName),
            taskStep = running?.step,
            otherRuns = app.taskState.runs.value.count { it.conversationId != conversationId },
            plans = TriggerRuntime.list(app).count { it.threadId == conversationId },
            updateVersion = app.updates.state.value.update?.version,
            sandbox = sandboxName(app.sandboxState.status.value),
            mcpInbound = app.mcp.settings.enabled.value || app.mcp.usb.enabled.value,
            mcpServers = app.mcpServers.servers.value.map { it.name },
            relayEnabled = app.mcp.relaySettings.config.value.enabled,
        )
    }

    override suspend fun openScreen(screen: SelfScreen, note: String?): SelfResult = withContext(Dispatchers.Main) {
        if (!FeatureFlags.get(app).selfOperate()) return@withContext SelfResult(ok = false, reason = "not_enabled")
        if (app.foreground.visible.value) {
            app.selfUi.requestOpen(screen = screen, note = note)
            SelfResult(ok = true, requested = true, foreground = true)
        } else {
            val ok = post(
                title = app.getString(R.string.app_name),
                body = app.getString(R.string.self_screen_notice, screenLabel(screen)),
                screen = screen,
                file = null,
            )
            SelfResult(ok = ok, notified = ok, foreground = false, reason = if (ok) null else "notifications_off")
        }
    }

    override suspend fun openFile(path: String, name: String?): SelfResult = withContext(Dispatchers.Main) {
        if (!FeatureFlags.get(app).selfOperate()) return@withContext SelfResult(ok = false, reason = "not_enabled")
        val file = SelfFileRequest(conversationId, path, name)
        if (app.foreground.visible.value) {
            app.selfUi.requestOpen(file = file)
            SelfResult(ok = true, requested = true, foreground = true)
        } else {
            val ok = post(
                title = app.getString(R.string.app_name),
                body = app.getString(R.string.self_file_notice),
                screen = null,
                file = file,
            )
            SelfResult(ok = ok, notified = ok, foreground = false, reason = if (ok) null else "notifications_off")
        }
    }

    override suspend fun notify(title: String, body: String, screen: SelfScreen?): SelfResult = withContext(Dispatchers.Main) {
        if (!FeatureFlags.get(app).selfOperate()) return@withContext SelfResult(ok = false, reason = "not_enabled")
        val ok = post(title, body, screen, file = null)
        SelfResult(ok = ok, notified = ok, foreground = app.foreground.visible.value, reason = if (ok) null else "notifications_off")
    }

    override suspend fun copy(text: String): Boolean = withContext(Dispatchers.Main) {
        val cm = app.getSystemService(ClipboardManager::class.java) ?: return@withContext false
        runCatching {
            cm.setPrimaryClip(ClipData.newPlainText("Mika", text))
            true
        }.getOrDefault(false)
    }

    // ---- 用户浏览器 ----

    override suspend fun browserState(): SelfBrowserState = withContext(Dispatchers.Main) {
        if (!FeatureFlags.get(app).selfOperate()) SelfBrowserState() else browserStateNow()
    }

    private fun browserStateNow(): SelfBrowserState {
        val browser = UserBrowser.existing()
        val page = browser?.page?.value
        return SelfBrowserState(
            pageOpen = page != null,
            host = page?.url?.let(WebUrlPolicy::host),
            title = page?.title,
            url = page?.url,
            loaned = browser?.loans?.current != null,
            multiTab = true,
        )
    }

    override suspend fun openInBrowser(url: String, show: Boolean, reason: String?): SelfResult = withContext(Dispatchers.Main) {
        if (!FeatureFlags.get(app).selfOperate()) return@withContext SelfResult(ok = false, reason = "not_enabled")
        val state = browserStateNow()
        if (state.loaned) return@withContext SelfResult(ok = false, reason = "page_in_use")
        if (!show && !state.multiTab) return@withContext SelfResult(ok = false, reason = "not_supported")
        if (app.foreground.visible.value) {
            app.selfUi.requestOpen(url = url, newTab = state.multiTab, show = show, note = reason)
            SelfResult(ok = true, requested = true, foreground = true)
        } else {
            val ok = post(
                title = app.getString(R.string.app_name),
                body = app.getString(R.string.self_browser_notice, WebUrlPolicy.host(url) ?: url),
                screen = SelfScreen.Browser,
                file = null,
                url = url,
                newTab = state.multiTab,
                show = show,
            )
            SelfResult(ok = ok, notified = ok, foreground = false, reason = if (ok) null else "notifications_off")
        }
    }

    override suspend fun searchInBrowser(query: String, show: Boolean): SelfResult {
        val url = withContext(Dispatchers.Main) {
            val browser = UserBrowser.of(app)
            AddressInput.search(query, browser.searchEngine.choices.value.selected)
        }
        return openInBrowser(url, show, null)
    }

    override suspend fun sites(): List<SelfSite> = withContext(Dispatchers.IO) {
        if (!FeatureFlags.get(app).selfOperate()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        app.webSites.list().take(200).map { SelfSite(it.host, it.loggedInByUser, it.challenged(now)) }
    }

    override suspend fun loginSite(host: String): SelfResult = withContext(Dispatchers.Main) {
        if (!FeatureFlags.get(app).selfOperate()) return@withContext SelfResult(ok = false, reason = "not_enabled")
        val browser = UserBrowser.of(app)
        if (browser.loans.current != null) return@withContext SelfResult(ok = false, reason = "page_in_use")
        if (app.foreground.visible.value) {
            val error = browser.openFavorite(FavoriteSite(host, loggedIn = false))
            if (error != null) return@withContext SelfResult(ok = false, reason = error)
            app.selfUi.requestOpen(screen = SelfScreen.Browser)
            SelfResult(ok = true, requested = true, foreground = true)
        } else {
            val ok = post(
                title = app.getString(R.string.app_name),
                body = app.getString(R.string.self_browser_notice, host),
                screen = SelfScreen.Browser,
                file = null,
                loginHost = host,
            )
            SelfResult(ok = ok, notified = ok, foreground = false, reason = if (ok) null else "notifications_off")
        }
    }

    override suspend fun confirmLogin(): SelfResult = withContext(Dispatchers.Main) {
        if (!FeatureFlags.get(app).selfOperate()) return@withContext SelfResult(ok = false, reason = "not_enabled")
        val browser = UserBrowser.of(app)
        if (browser.loginHost() == null) return@withContext SelfResult(ok = false, reason = "not_in_login")
        browser.confirmLogin()
        SelfResult(ok = true, foreground = app.foreground.visible.value)
    }

    override suspend fun favorites(op: String, host: String?): List<SelfFavorite> = withContext(Dispatchers.Main) {
        if (!FeatureFlags.get(app).selfOperate()) return@withContext emptyList()
        val browser = UserBrowser.of(app)
        when (op) {
            "add" -> host?.let(browser.favorites::add)
            "remove" -> host?.let(browser.favorites::remove)
        }
        BrowserFavorites.combine(browser.favorites.edits.value, app.webSites.list(), limit = 50)
            .map { SelfFavorite(it.host, it.loggedIn) }
    }

    override fun browserConsent(url: String, query: String?, replaces: Boolean): SelfConsent {
        val base = if (query != null) app.getString(R.string.self_consent_search, query)
        else app.getString(R.string.self_consent_open, url)
        val prompt = if (replaces) base + "\n" + app.getString(R.string.self_consent_replace) else base
        return SelfConsent(prompt, app.getString(R.string.self_consent_allow), app.getString(R.string.self_consent_deny))
    }

    override fun loginConsent(host: String, replaces: Boolean): SelfConsent {
        val base = app.getString(R.string.self_consent_login, host)
        val prompt = if (replaces) base + "\n" + app.getString(R.string.self_consent_replace) else base
        return SelfConsent(prompt, app.getString(R.string.self_consent_allow), app.getString(R.string.self_consent_deny))
    }

    /** 后台通知经内部入口打开对应页面或文件。 */
    private fun post(
        title: String,
        body: String,
        screen: SelfScreen?,
        file: SelfFileRequest?,
        url: String? = null,
        loginHost: String? = null,
        newTab: Boolean = false,
        show: Boolean = true,
    ): Boolean {
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        val nm = app.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannel(nm)
        val intent = Intent(app, SelfNotificationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            screen?.let { putExtra(SelfIntents.EXTRA_SCREEN, it.wire) }
            file?.let {
                putExtra(SelfIntents.EXTRA_FILE, it.path)
                putExtra(SelfIntents.EXTRA_FILE_CONVERSATION, it.conversationId)
                it.name?.let { name -> putExtra(SelfIntents.EXTRA_FILE_NAME, name) }
            }
            url?.let { putExtra(SelfIntents.EXTRA_URL, it) }
            loginHost?.let { putExtra(SelfIntents.EXTRA_LOGIN_HOST, it) }
            if (newTab) putExtra(SelfIntents.EXTRA_NEW_TAB, true)
            if (!show) putExtra(SelfIntents.EXTRA_SHOW, false)
        }
        val pending = PendingIntent.getActivity(
            app, nextId.incrementAndGet(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addAction(R.drawable.ic_notification, app.getString(R.string.self_notify_open), pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return runCatching {
            NotificationManagerCompat.from(app).notify(nextId.incrementAndGet(), notification)
            true
        }.getOrDefault(false)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.self_notify_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = app.getString(R.string.self_notify_channel_desc)
            },
        )
    }

    private fun screenLabel(screen: SelfScreen): String = when (screen) {
        SelfScreen.Chat -> app.getString(R.string.app_name)
        SelfScreen.Settings -> app.getString(R.string.settings_title)
        SelfScreen.Memory -> app.getString(R.string.settings_memory_data)
        SelfScreen.Plans -> app.getString(R.string.drawer_plans)
        SelfScreen.Browser -> app.getString(R.string.drawer_browser)
        SelfScreen.Models -> app.getString(R.string.settings_model)
        SelfScreen.Usage -> app.getString(R.string.usage_title)
        SelfScreen.Screens -> app.getString(R.string.screens_title)
        SelfScreen.About -> app.getString(R.string.settings_about)
    }

    private fun shellName(s: BuiltinShellStatus): String = when (s) {
        BuiltinShellStatus.Running -> "running"
        BuiltinShellStatus.Connecting -> "connecting"
        BuiltinShellStatus.NotPaired -> "not_paired"
        BuiltinShellStatus.WirelessDebuggingOff -> "wireless_debugging_off"
        is BuiltinShellStatus.Failed -> "failed"
    }

    private fun stateName(s: TaskState): String = when (s) {
        TaskState.Idle -> "idle"
        is TaskState.Running -> "running"
        is TaskState.Held -> "held"
        is TaskState.WaitingTakeover -> "waiting_takeover"
        is TaskState.WaitingInput -> "waiting_input"
        is TaskState.WaitingConfirmation -> "waiting_confirmation"
        is TaskState.Succeeded -> "done"
        is TaskState.Failed -> "failed"
        is TaskState.Cancelled -> "cancelled"
    }

    private fun sandboxName(s: SandboxStatus): String = when (s) {
        is SandboxStatus.Ready -> if (s.network) "ready (network)" else "ready"
        is SandboxStatus.Installing -> "installing"
        is SandboxStatus.Broken -> "broken"
        SandboxStatus.NotInstalled -> "not_installed"
        is SandboxStatus.Unsupported -> "unsupported"
    }

    private companion object {
        const val CHANNEL = "mika_self"
    }
}
