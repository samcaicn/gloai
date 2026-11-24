package com.wangzan101.mika.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.lifecycleScope
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.about.DiagnosticsCollector
import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.about.QrImage
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.calls.activeVoiceProfile
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.setup.PairingNotifier
import com.wangzan101.mika.cap.web.WebHandover
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedDraftImporter
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.knowledge.FileRouteStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.SystemStatusMonitor
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import com.wangzan101.mika.ui.components.glassSource
import com.wangzan101.mika.ui.components.rememberGlassBackdrop
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.ui.theme.appearanceDark
import com.wangzan101.mika.ui.theme.systemBarsFor
import com.wangzan101.mika.voicecall.ui.VoiceCallDeps
import com.wangzan101.mika.task.screenConversations
import com.wangzan101.mika.voicecall.ui.VoiceCallBar
import com.wangzan101.mika.voicecall.ui.VoiceCallHost
import com.wangzan101.mika.voicecall.ui.VoiceCallScreen
import com.wangzan101.mika.voicecall.ui.liveCall
import com.wangzan101.mika.voicecall.ui.rememberVoiceCallController
import com.wangzan101.mika.voicecall.VoiceCallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.about.ui.AboutCopy
import com.wangzan101.mika.about.ui.AboutScreen
import com.wangzan101.mika.about.ui.LicensesScreen
import com.wangzan101.mika.about.ui.UpdateMirrorsScreen
import com.wangzan101.mika.about.ui.UpdateReadyCapsule
import com.wangzan101.mika.about.ui.UpdateSheet
import com.wangzan101.mika.browser.ui.BrowserScreen
import com.wangzan101.mika.browser.ui.WebSitesState
import com.wangzan101.mika.calls.ui.CallModeScreen
import com.wangzan101.mika.calls.ui.CallSettingsScreen
import com.wangzan101.mika.chat.ui.AttachFiles
import com.wangzan101.mika.chat.ui.ChatScreen
import com.wangzan101.mika.chat.ui.ChatWithDrawer
import com.wangzan101.mika.chat.ui.ScreensScreen
import com.wangzan101.mika.chat.ui.ShareContent
import com.wangzan101.mika.chat.ui.SourceScreen
import com.wangzan101.mika.chat.ui.SourceViewModel
import com.wangzan101.mika.chat.ui.draft
import com.wangzan101.mika.chat.ui.isShareIntent
import com.wangzan101.mika.chat.ui.readShare
import com.wangzan101.mika.chat.ui.shareCanRead
import com.wangzan101.mika.files.ui.FileLibraryScreen
import com.wangzan101.mika.memory.ui.MemoryDataScreen
import com.wangzan101.mika.memory.ui.MemoryViewModel
import com.wangzan101.mika.memory.ui.Segment
import com.wangzan101.mika.personal.ui.PersonalAgentScreen
import com.wangzan101.mika.settings.ui.Fix
import com.wangzan101.mika.settings.ui.LanguagePromptDialog
import com.wangzan101.mika.settings.ui.PersonaScreen
import com.wangzan101.mika.settings.ui.PhoneSetupScreen
import com.wangzan101.mika.settings.ui.SettingsScreen
import com.wangzan101.mika.settings.ui.UsageScreen
import com.wangzan101.mika.settings.ui.models.ImageModelScreen
import com.wangzan101.mika.settings.ui.models.ModelSetupScreen
import com.wangzan101.mika.settings.ui.models.ModelsScreen
import com.wangzan101.mika.settings.ui.models.refresh
import com.wangzan101.mika.setup.ui.Onboarding
import com.wangzan101.mika.setup.ui.OnboardingState
import com.wangzan101.mika.setup.ui.OnboardingStep
import com.wangzan101.mika.setup.ui.WelcomeScreen
import com.wangzan101.mika.task.ui.PlansScreen
import com.wangzan101.mika.task.ui.RunTimelineScreen
import com.wangzan101.mika.task.ui.RunTimelineViewModel
import com.wangzan101.mika.task.ui.TaskPlaybackScreen
import com.wangzan101.mika.task.ui.TaskReportScreen
import com.wangzan101.mika.task.ui.TaskReportViewModel
import com.wangzan101.mika.teach.ui.TeachScreen
import com.wangzan101.mika.voice.ui.RealtimeVoiceScreen
import com.wangzan101.mika.voice.ui.SpeechModelScreen
import com.wangzan101.mika.voice.ui.VoiceCatalogScreen
import com.wangzan101.mika.voice.ui.VoiceModelScreen
import com.wangzan101.mika.voice.ui.VoiceServicesScreen

class MainActivity : ComponentActivity() {
    private lateinit var monitor: SystemStatusMonitor
    private var requestedConversation by mutableStateOf<String?>(null)
    private var requestedCalls by mutableStateOf(false)
    private var requestedCallHistoryId by mutableStateOf<String?>(null)
    private var voiceCallSignal by mutableIntStateOf(0)
    private var openTeach by mutableStateOf(false)
    private var requestedSharedDraft by mutableStateOf<SharedDraftRequest?>(null)
    private var shareJob: Job? = null
    private var shareSerial = 0L
    private var shareInCurrent = false
    private var shareHandled = false
    private var verifiedShareUris: List<String> = emptyList()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // 用户选过「不再询问」时系统不再弹窗,只能去设置里打开
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
        monitor.refresh()
    }

    // 安卓 12 及以前套上 App 里选的语言;13 起系统自己套
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            (application as MikaApp).chat.cancelPendingSharedDraft()
        }
        requestedConversation = intent.getStringExtra(EXTRA_OPEN_CONVERSATION)
        requestedCalls = intent.getBooleanExtra(EXTRA_OPEN_CALLS, false)
        requestedCallHistoryId = intent.getStringExtra(EXTRA_CALL_HISTORY_ID)
        takeVoiceCall(intent)
        takeTeach(intent)
        shareInCurrent = savedInstanceState?.getBoolean(STATE_SHARE_IN_CURRENT, false) ?: false
        shareHandled = savedInstanceState?.getBoolean(STATE_SHARE_HANDLED, false) ?: false
        verifiedShareUris = savedInstanceState?.getStringArrayList(STATE_VERIFIED_SHARE_URIS).orEmpty()
        // 已处理完的分享在旋转/重建时不重复触发;真正的冷启动分享或尚未完成的后台分享继续收下。
        if (!shareHandled && (savedInstanceState == null || isShareIntent(intent))) {
            receiveShare(intent, inCurrentConversation = shareInCurrent)
        }
        monitor = SystemStatusMonitor(this, PhoneServices.of(this).shellControl)
        val store = ProviderStore.get(this)
        setContent {
            // 外观按设置来,改了当场换;系统栏图标的深浅跟着界面
            val dark = appearanceDark()
            LaunchedEffect(dark) { systemBarsFor(dark) }
            MikaTheme(dark = dark, reduceMotion = animationsOff(this)) {
                // 在输入框外面点一下或拖动就收起键盘,全 App 统一
                KeyboardDismissArea(Modifier.fillMaxSize()) {
                    MikaRoot(
                        store, monitor, ::perform, requestedConversation, { requestedConversation = null },
                        requestedCalls, requestedCallHistoryId, { requestedCalls = false; requestedCallHistoryId = null },
                        voiceCallSignal,
                        requestedSharedDraft,
                        onSharedDraftOpened = { accepted, draft ->
                            requestedSharedDraft = null
                            shareHandled = true
                            // 处理完后换掉 intent,避免后续旋转屏幕再次把这次分享当成新分享或重复弹失败提示。
                            setIntent(Intent(this@MainActivity, MainActivity::class.java))
                            if (!accepted) {
                                (application as MikaApp).sharedDrafts.discard(draft.attachments)
                                Toast.makeText(this@MainActivity, R.string.share_draft_failed, Toast.LENGTH_SHORT).show()
                            }
                        },
                        openTeach = openTeach,
                        onTeachOpened = { openTeach = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Build.VERSION.SDK_INT >= 35) {
            setIntent(intent, runCatching { currentCaller }.getOrNull())
        } else {
            setIntent(intent)
        }
        val app = application as MikaApp
        shareJob?.cancel()
        requestedConversation = intent.getStringExtra(EXTRA_OPEN_CONVERSATION)
        requestedCalls = intent.getBooleanExtra(EXTRA_OPEN_CALLS, false)
        requestedCallHistoryId = intent.getStringExtra(EXTRA_CALL_HISTORY_ID)
        if (requestedConversation != null || intent.getBooleanExtra(EXTRA_OPEN_TEACH, false)) {
            app.chat.cancelPendingSharedDraft()
        }
        takeVoiceCall(intent)
        takeTeach(intent)
        requestedSharedDraft?.let { app.sharedDrafts.discard(it.draft.attachments) }
        requestedSharedDraft = null
        // 界面已在后台存活时收到分享:放入当前打开的会话,而不是新建会话。
        shareInCurrent = true
        shareHandled = false
        verifiedShareUris = emptyList()
        receiveShare(intent, inCurrentConversation = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHARE_IN_CURRENT, shareInCurrent)
        outState.putBoolean(STATE_SHARE_HANDLED, shareHandled)
        if (!shareHandled && verifiedShareUris.isNotEmpty()) {
            outState.putStringArrayList(STATE_VERIFIED_SHARE_URIS, ArrayList(verifiedShareUris))
        }
    }

    private fun rejectShare() {
        shareHandled = true
        setIntent(Intent(this, MainActivity::class.java))
        Toast.makeText(this, R.string.share_draft_failed, Toast.LENGTH_SHORT).show()
    }

    private fun receiveShare(intent: Intent, inCurrentConversation: Boolean) {
        val content = readShare(intent) ?: return
        shareJob?.cancel()
        when (content) {
            ShareContent.Rejected -> rejectShare()
            is ShareContent.Text -> requestedSharedDraft = SharedDraftRequest(SharedDraft("", content.text), ++shareSerial, inCurrentConversation)
            is ShareContent.Files -> {
                val uriStrings = content.uris.map { it.toString() }
                if (verifiedShareUris != uriStrings && content.uris.any { !shareCanRead(intent, it, content.clipUris) }) {
                    return rejectShare()
                }
                verifiedShareUris = uriStrings
                shareJob = lifecycleScope.launch {
                    val prepared = SharedDraftImporter(this@MainActivity, (application as MikaApp).sharedDrafts)
                        .attachments(content.text, content.uris, content.type)
                    if (prepared == null) rejectShare()
                    else requestedSharedDraft = SharedDraftRequest(
                        prepared.draft, ++shareSerial, inCurrentConversation,
                        rejected = prepared.failures, overLimit = content.overLimit,
                    )
                }
            }
        }
    }

    private var updateWatch: Job? = null

    override fun onStart() {
        super.onStart()
        // 用户先前点了「完全退出」:打开 Mika 就回来,胶囊和退出时略过的启动工作在这里补上
        com.wangzan101.mika.MikaQuit.resume(this)
        monitor.start()
        // 每次打开都悄悄查一次新版本(两分钟内连着打开的只查一次),失败不吭声
        val app = application as MikaApp
        val check = app.updates.autoCheckInBackground()
        updateWatch = lifecycleScope.launch {
            check.join()
            app.automaticUpdates.watch(app.updates.state)
        }
        // 电脑连接开着、服务却不在(被系统停过、从后台没能起来):界面在前台时补起来
        (application as MikaApp).mcp.ensureRunning()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        monitor.refresh()
        // 回到前台时按最新设置查一次自动归档(启动时也查过,这里照顾长期不退出的进程)
        (application as MikaApp).autoArchiveNow()
    }

    override fun onStop() {
        updateWatch?.cancel()
        monitor.stop()
        foreground = false
        super.onStop()
    }

    override fun onDestroy() {
        shareJob?.cancel()
        // 还没绑到对话的私有副本随这次接收结束清掉;已接受的草稿由 CurrentChat 恢复,不在这里删除。
        requestedSharedDraft?.let { (application as MikaApp).sharedDrafts.discard(it.draft.attachments) }
        requestedSharedDraft = null
        if (isFinishing) {
            (application as MikaApp).chat.cancelPendingSharedDraft()
        }
        super.onDestroy()
    }

    private val phone get() = PhoneServices.of(this)

    private fun perform(fix: Fix) {
        when (fix) {
            Fix.OpenAccessibility -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Fix.OpenAppInfo -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            Fix.AllowNotifications -> {
                val needsGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsGrant) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings()
            }
            Fix.OpenAttentionChannel -> openChannelSettings(TaskService.CHANNEL_ATTENTION)
            Fix.OpenResultChannel -> openChannelSettings(TaskService.CHANNEL_RESULT)
            Fix.OpenProgressChannel -> openChannelSettings(TaskService.CHANNEL_PROGRESS)
            Fix.OpenLiveTaskSettings -> if (Build.VERSION.SDK_INT >= 36) {
                try {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                } catch (_: ActivityNotFoundException) {
                    openNotificationSettings()
                }
            } else openNotificationSettings()
            Fix.OpenPairingChannel -> openChannelSettings(PairingNotifier.ensureChannel(this).id)
            Fix.AddModel -> Unit // 设置页自己处理
            // 换页由 MikaRoot 处理
            Fix.OpenPhoneSetup, Fix.OpenPhoneOps, Fix.RepairPhoneHelper -> Unit
            Fix.RetryPhoneHelper -> phone.pairing.scope.launch {
                phone.sharedShell.reconnect()
                phone.shellControl.retry()
            }
            Fix.UnpairPhoneHelper -> phone.pairing.scope.launch { phone.recovery.unpair() }
            Fix.OpenAboutPhone -> openFirst(DeveloperSettings.aboutPhone())
            Fix.OpenDeveloperOptions -> openFirst(DeveloperSettings.developerOptionsPage())
            Fix.OpenUsbDebugging -> openFirst(DeveloperSettings.usbDebuggingPage())
            Fix.OpenWirelessDebugging -> openFirst(DeveloperSettings.wirelessDebuggingPage())
            Fix.OpenWifi -> openFirst(DeveloperSettings.wifiPage())
        }
    }

    private fun appNotificationSettings() =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    private fun openNotificationSettings() = open(appNotificationSettings())

    /** 直接打开某个通知频道的设置页;系统没有这个页面时退回应用通知总页。 */
    private fun openChannelSettings(channelId: String) {
        val channelPage = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        firstThatStarts(listOf(channelPage, appNotificationSettings())) { intent ->
            if (intent.resolveActivity(packageManager) == null) false
            else try { startActivity(intent); true } catch (e: ActivityNotFoundException) { false }
        }
    }

    /** 依次试,第一个能打开的就用:各家设置页不一定都认同一个入口。 */
    private fun openFirst(intents: List<Intent>) {
        val hit = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: intents.last()
        open(hit)
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for ${intent.action}", e)
        }
    }

    private fun takeTeach(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_TEACH, false)) return
        openTeach = true
        (application as MikaApp).teach.prefill(intent.getStringExtra(EXTRA_TEACH_GOAL))
        intent.removeExtra(EXTRA_OPEN_TEACH)
    }

    /** 胶囊菜单「语音聊天」直开:信号 +1 交给界面层;读一次就摘掉,旋转不重复触发。 */
    private fun takeVoiceCall(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_VOICE_CALL, false)) return
        voiceCallSignal++
        intent.removeExtra(EXTRA_OPEN_VOICE_CALL)
    }

    companion object {
        const val EXTRA_OPEN_CONVERSATION = "open_conversation"
        const val EXTRA_OPEN_CALLS = "open_calls"
        const val EXTRA_CALL_HISTORY_ID = "call_history_id"
        const val EXTRA_OPEN_TEACH = "open_teach"
        const val EXTRA_TEACH_GOAL = "teach_goal"
        const val EXTRA_OPEN_VOICE_CALL = "open_voice_call"
        private const val STATE_SHARE_IN_CURRENT = "share_in_current"
        private const val STATE_SHARE_HANDLED = "share_handled"
        private const val STATE_VERIFIED_SHARE_URIS = "verified_share_uris"
        private const val TAG = "MikaMain"

        /** 主界面(聊天)此刻是否在前台可见。任务完成时据此决定要不要弹完成通知(#731)。 */
        @Volatile var foreground: Boolean = false
    }
}

/** 用系统浏览器打开外部链接(GitHub、X 等);没有浏览器时提示一句。 */
internal fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, AboutCopy.NO_BROWSER, Toast.LENGTH_SHORT).show()
    }
}

/** 依次试 [candidates],第一个 [start] 成功的就停;都不行返回 false。系统设置页各家不一定都有,靠这个退回通用页。 */
internal fun <T> firstThatStarts(candidates: List<T>, start: (T) -> Boolean): Boolean = candidates.any(start)

private data class SharedDraftRequest(
    val draft: SharedDraft,
    val serial: Long,
    val inCurrentConversation: Boolean = false,
    val rejected: List<AttachmentFailure> = emptyList(),
    val overLimit: Int = 0,
)

/**
 * 页面切换:对话页是根,侧栏和设置等入口把页面压进 [NavStack],返回键和页面上的返回按钮都走 [NavStack.back]。
 * 各页面只通过回调进出,不知道彼此。
 */
@Composable
private fun MikaRoot(
    store: ProviderStore,
    monitor: SystemStatusMonitor,
    activityFix: (Fix) -> Unit,
    requestedConversation: String?,
    onConversationOpened: () -> Unit,
    requestedCalls: Boolean,
    requestedCallHistoryId: String?,
    onCallsOpened: () -> Unit,
    voiceCallSignal: Int,
    sharedDraft: SharedDraftRequest?,
    onSharedDraftOpened: (Boolean, SharedDraft) -> Unit,
    openTeach: Boolean,
    onTeachOpened: () -> Unit,
) {
    val context = LocalContext.current
    var focusedCallHistoryId by remember { mutableStateOf<String?>(null) }
    val personalStart = remember { FeatureFlags.get(context).personalStart() }
    val nav = rememberNavStack(if (personalStart && requestedConversation == null) Route.Personal else Route.Chat)
    var filesFromDrawer by rememberSaveable { mutableStateOf(false) }
    var filesConversation by rememberSaveable { mutableStateOf<String?>(null) }
    // 从设置页「重新配对」进来时先回到配对一步
    var setupRepair by rememberSaveable { mutableStateOf(false) }
    // 首次上手正在走哪一步;为空时不在上手流程里。配置模型页从哪儿来就回哪儿去
    var onboarding by rememberSaveable { mutableStateOf<OnboardingStep?>(null) }
    var openPhoneOps by rememberSaveable { mutableStateOf(false) }
    val onFix: (Fix) -> Unit = { fix ->
        when (fix) {
            Fix.AddModel -> nav.push(Route.Models)
            Fix.OpenPhoneSetup, Fix.RepairPhoneHelper -> {
                setupRepair = fix == Fix.RepairPhoneHelper
                nav.push(Route.PhoneSetup)
            }
            Fix.OpenPhoneOps -> {
                openPhoneOps = true
                nav.push(Route.Settings)
            }
            else -> activityFix(fix)
        }
    }
    var runId by rememberSaveable { mutableStateOf<String?>(null) }
    // 「运行过程」是从通话页点开的:返回时调它把通话页亮回来;从别处打开时为 null
    var reportRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var playbackScreenshotId by rememberSaveable { mutableStateOf<Int?>(null) }
    var sourceRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceLegacyText by rememberSaveable { mutableStateOf<String?>(null) }
    val status by monitor.status.collectAsState()
    val roles by store.roles.collectAsState()
    val hasMain = roles.hasMain
    val check = Prerequisites.check(status, hasMain)
    val app = context.applicationContext as MikaApp
    val personasOn = FeatureFlags.get(context).persona()
    // 通话入口:输入栏常驻听筒按钮;信号一涨就亮出通话页(直开 extra 或按钮都行)
    var localVoiceCall by remember { mutableIntStateOf(0) }
    val voiceCallScope = rememberCoroutineScope()
    val voiceCallResumed = remember { MutableStateFlow(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { voiceCallResumed.value = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { voiceCallResumed.value = false }
    val voiceCallDeps = remember(app) {
        VoiceCallDeps(
            context = context,
            scope = voiceCallScope,
            appScope = app.appScope,
            profile = { app.calls.activeVoiceProfile() },
            credentials = { app.calls.voiceProfiles.credentials(it) },
            foregroundResumed = voiceCallResumed,
            desks = app.desks,
            conversationId = { app.chat.ui.value.conversationId },
            newConversation = { app.chat.claimConversation() },
            deskParent = { ConversationStore.shared(context).deskParent(it) },
            turns = { ConversationStore.shared(context).turns(it) },
            // 通话里边说边存,从应用级作用域调过来:文件读写放到 IO 上
            appendTurn = { id, turn -> withContext(Dispatchers.IO) { ConversationStore.shared(context).append(id, turn) } },
            recordCall = { id, call -> withContext(Dispatchers.IO) { ConversationStore.shared(context).recordCall(id, call) } },
            attachService = { VoiceCallService.attach(context, it) },
            persona = { com.wangzan101.mika.frontdesk.deskPersona(app) },
            openConversation = { id ->
                app.chat.openConversation(id)
                nav.reset()
            },
            // 运行过程压在通话页上面,返回就回到通话页
            openRun = { id ->
                runId = id
                nav.push(Route.Run)
            },
            calls = { ConversationStore.shared(context).observeCalls(it) },
            // 通话记录里点一条:到聊天里那一通开头(通话接着通,顶上有「通话中」那条)
            openCallRecord = { id, callId ->
                app.chat.openConversationAt(id, com.wangzan101.mika.chat.ui.ChatTarget.Call(callId))
                nav.reset()
            },
            liveRun = { id -> app.taskState.runs.value.firstOrNull { it.conversationId == id }?.runId?.value },
            screens = app.taskState.screenConversations(app.appScope),
            frames = { id -> app.taskState.runs.value.firstOrNull { it.conversationId == id }?.frames },
        )
    }
    val voiceCall = rememberVoiceCallController(voiceCallDeps)
    // 查到的新版本:侧栏、设置首页、关于页的红点都看它
    val updateState by app.updates.state.collectAsState()
    val autoUpdate by app.automaticUpdates.enabled.collectAsState()
    val readyUpdate by app.automaticUpdates.ready.collectAsState()
    val mirrorEnabled by app.updateMirrors.enabled.collectAsState()
    val mirrors by app.updateMirrors.mirrors.collectAsState()
    val mirrorScope = rememberCoroutineScope()
    LaunchedEffect(requestedConversation) {
        if (requestedConversation != null) {
            if (requestedConversation == "personal-main") nav.show(Route.Personal)
            else {
                app.chat.openConversation(requestedConversation)
                nav.reset()
            }
            onConversationOpened()
        }
    }
    LaunchedEffect(requestedCalls, requestedCallHistoryId) {
        if (requestedCalls) {
            focusedCallHistoryId = requestedCallHistoryId
            nav.push(Route.Calls)
            onCallsOpened()
        }
    }
    LaunchedEffect(sharedDraft) {
        val draft = sharedDraft?.draft
        if (draft != null) {
            val accepted = app.chat.openSharedDraft(
                draft,
                inCurrentConversation = sharedDraft.inCurrentConversation,
                rejected = sharedDraft.rejected,
                overLimit = sharedDraft.overLimit,
            )
            if (accepted) nav.reset()
            onSharedDraftOpened(accepted, draft)
        }
    }
    // 消费任务或内部通知入口提交的自身操作请求。
    suspend fun openSelfFile(file: com.wangzan101.mika.self.SelfFileRequest) {
        if (!AttachFiles.openSent(context, file.conversationId, file.path)) {
            Toast.makeText(context, R.string.self_file_failed, Toast.LENGTH_SHORT).show()
        }
    }
    fun openSelfUrl(url: String, newTab: Boolean = false, show: Boolean = true) {
        nav.push(Route.Browser)
        val browser = UserBrowser.of(context)
        val error = if (newTab) browser.openInNewTab(url, select = show) else browser.open(url)
        error?.let { Toast.makeText(context, R.string.self_browser_failed, Toast.LENGTH_SHORT).show() }
    }
    fun openSelfLogin(host: String) {
        nav.push(Route.Browser)
        UserBrowser.of(context).openFavorite(com.wangzan101.mika.browser.FavoriteSite(host, loggedIn = false))
            ?.let { Toast.makeText(context, R.string.self_browser_failed, Toast.LENGTH_SHORT).show() }
    }
    val selfRequest by app.selfUi.request.collectAsState()
    LaunchedEffect(selfRequest?.id) {
        val r = selfRequest ?: return@LaunchedEffect
        when {
            r.url != null -> openSelfUrl(r.url, r.newTab, r.show)
            r.loginHost != null -> openSelfLogin(r.loginHost)
            r.file != null -> openSelfFile(r.file)
            r.screen != null -> nav.push(r.screen.route())
        }
        app.selfUi.consumed(r.id)
    }
    val release = remember { Build.VERSION.RELEASE }
    // 结果卡片上点了「看看」:打开技能分段
    val memoryRuntime = remember { MemoryRuntime.of(context) }
    val phoneSetup = remember { PhoneServices.of(context).setupPrompt }
    val openMemory by memoryRuntime.openRequest.collectAsState()
    LaunchedEffect(openMemory) { if (openMemory != null) nav.push(Route.Memory) }
    LaunchedEffect(openTeach) {
        if (openTeach) {
            nav.push(Route.Teach)
            onTeachOpened()
        }
    }
    // 第一次打开:欢迎 → 配置模型 → 手机操作;配过模型的跳过前两步,手机操作已就绪或看过引导的跳过最后一步
    val onboardingState = remember { OnboardingState(context) }
    fun onboardingInputs() = Onboarding.Inputs(
        hasMainModel = store.roles.value.hasMain,
        modelSetupDone = onboardingState.modelSetupDone(),
        phoneNeeded = status.builtinShell != BuiltinShellStatus.Running && status.sdkInt >= Prerequisites.MIN_SDK,
        phoneGuideSeen = phoneSetup.guideSeen(),
    )
    fun goOnboarding(step: OnboardingStep?) {
        onboarding = step
        when (step) {
            // 上手的每一步都直接放在对话页上面一层,做完回到对话页
            OnboardingStep.Welcome -> nav.show(Route.Welcome)
            OnboardingStep.Model -> nav.show(Route.ModelSetup)
            OnboardingStep.Phone -> { setupRepair = false; nav.show(Route.PhoneSetup) }
            null -> nav.reset()
        }
    }
    LaunchedEffect(Unit) {
        if (onboarding == null && requestedConversation == null && nav.current != Route.Personal) {
            Onboarding.start(onboardingInputs())?.let(::goOnboarding)
        }
    }
    val phoneSetupNeed by phoneSetup.needed.collectAsState()
    // 页面有自己的返回处理时(设置页里的子页、侧栏)由它们先接;这里只管退回上一页
    BackHandler(enabled = nav.canGoBack) { nav.back() }
    val back: () -> Unit = { nav.back() }
    // 聊天页离开组合树时保留列表位置和输入内容，返回设置页等子页面后原样恢复。
    val screenStates = rememberSaveableStateHolder()
    val sidebarPage = nav.current in setOf(Route.Chat, Route.Personal, Route.Plans, Route.Memory, Route.Browser)
    // 通话页不在最上面时,顶上一条「通话中 · 回到通话」;下面的页面从它下面开始
    val liveCall = voiceCall.liveCall()
    val callBar = liveCall != null && nav.current != Route.VoiceCall
    Column(Modifier.fillMaxSize()) {
    if (callBar && liveCall != null) VoiceCallBar(liveCall) { nav.bringToTop(Route.VoiceCall) }
    Box(Modifier.weight(1f).fillMaxWidth().then(if (callBar) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
    AdaptiveWindow(navigation = sidebarPage) {
        val page: @Composable (() -> Unit) -> Unit = { openDrawer ->
    // 全局换页套滑动推入过渡;页内一律用 route 不用 nav.current:过渡动画期间旧页还在渲染,读实时状态会瞬间变脸
    NavSlide(nav.stack, depth = { it.size }) { stack ->
    val route = stack.last()
    when (route) {
        Route.PhoneSetup -> PhoneSetupScreen(status, setupRepair, onFix, onboarding = onboarding == OnboardingStep.Phone) {
            phoneSetup.markGuideSeen()
            if (onboarding == OnboardingStep.Phone) goOnboarding(null) else nav.back()
        }
        Route.Welcome -> WelcomeScreen { goOnboarding(OnboardingStep.Model) }
        Route.ModelSetup -> {
            val inOnboarding = onboarding == OnboardingStep.Model
            ModelSetupScreen(
                store, onboarding = inOnboarding,
                onBack = { if (inOnboarding) goOnboarding(OnboardingStep.Welcome) else nav.back() },
                onDone = {
                    if (inOnboarding) {
                        onboardingState.markModelSetupDone()
                        goOnboarding(Onboarding.next(OnboardingStep.Model, onboardingInputs()))
                    } else nav.back()
                },
            )
        }
        Route.Chat, Route.Personal -> screenStates.SaveableStateProvider(route.name) {
            // 对话页重新看得见(回到前台、从别的页面回来):补上别处存进来的轮次,接上别处开始的任务
            LifecycleStartEffect(Unit) {
                app.chat.refresh()
                onStopOrDispose {}
            }
            // 聊天页看得见时,前台的汇报说在对话里;离开或退到后台就交回兜底(通知)
            LifecycleStartEffect(route) {
                app.chat.pageShown(route == Route.Chat)
                onStopOrDispose { app.chat.pageShown(false) }
            }
            Box(Modifier.fillMaxSize()) {
                    val readyRelease = readyUpdate?.takeIf { autoUpdate && it.apkUrl == updateState.update?.apkUrl }
                    val updateBackdrop = if (readyRelease != null) rememberGlassBackdrop() else null
                    Box(Modifier.fillMaxSize().glassSource(updateBackdrop)) {
                        if (route == Route.Personal) PersonalAgentScreen(
                            runtime = app.personal,
                            voice = app.voice,
                            onOpenDrawer = openDrawer,
                            onOpenTask = { id -> app.chat.openPersonalTask(id); nav.reset() },
                            memoryRuntime = memoryRuntime,
                            onModels = { nav.push(Route.Models) },
                            onPickSpeech = { nav.push(Route.SpeechModel) },
                        ) else ChatScreen(
                            vm = app.chat,
                            check = check,
                            frames = app.taskState.frames,
                            webOpen = app.tasks.webScreenOpen,
                            pipInput = app.taskState.pipInput,
                            screens = remember(app) { app.taskState.screenConversations(app.appScope) },
                            pipSoundOn = app.taskState.pipSoundOn,
                            pipOperateEnabled = FeatureFlags.get(context).pipOperate(),
                            reduceMotion = MikaTheme.reduceMotion,
                            onOpenDrawer = openDrawer,
                            openSettings = { nav.push(Route.Settings) },
                            onOpenBrowser = { nav.push(Route.Browser) },
                            openRun = { runId = it; nav.push(Route.Run) },
                            phoneSetupNeed = (if (status.builtinShell != BuiltinShellStatus.Running) phoneSetupNeed else null),
                            openPhoneSetup = { onFix(Fix.OpenPhoneSetup) },
                            voice = app.voice,
                            openSpeechModel = { nav.push(Route.SpeechModel) },
                            onOpenTeach = { hint ->
                                app.teach.prefill(hint)
                                nav.push(Route.Teach)
                            },
                            // 入口常驻:没配凭据/协议不兼容时进的是门禁页(说明 + 去配置),不静默消失
                            onVoiceCall = { localVoiceCall++ },
                            openDeskRun = { id, phase ->
                                voiceCallScope.launch {
                                    val live = app.taskState.runs.value.firstOrNull { it.conversationId == id }?.runId?.value
                                    val run = withContext(Dispatchers.IO) {
                                        com.wangzan101.mika.voicecall.VoiceCallTaskLink.runFor(phase, live) { ConversationStore.shared(context).turns(id) }
                                    }
                                    if (run == null) app.chat.openConversation(id) else { runId = run; nav.push(Route.Run) }
                                }
                            },
                            openCallSettings = { nav.push(Route.CallMode) },
                        )
                    }
                    var installUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
                    readyRelease?.let { release ->
                        UpdateReadyCapsule(
                            release,
                            onInstall = {
                                installUpdate = release
                                app.automaticUpdates.dismiss(release)
                            },
                            onTimeout = { app.automaticUpdates.dismiss(release) },
                            onDisable = { app.automaticUpdates.setEnabled(false) },
                            backdrop = updateBackdrop,
                            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 54.dp),
                        )
                    }
                    installUpdate?.let { release ->
                        UpdateSheet(release, BuildConfig.VERSION_NAME, BuildConfig.BETA,
                            onOpenUrl = { openInBrowser(context, it) }, onDismiss = { installUpdate = null })
                    }
                }
        }
        Route.Settings -> {
            val autonomous by app.approvals.autonomous.collectAsState()
            SettingsScreen(store, status, release, onFix, onBack = back, onOpenMemory = { nav.push(Route.Memory) },
                onOpenModels = { nav.push(Route.Models) },
                onOpenBrowser = { nav.push(Route.Browser) },
                onOpenFiles = { filesFromDrawer = false; filesConversation = app.chat.ui.value.conversationId; nav.push(Route.Files) },
                onOpenPersonal = { nav.show(Route.Personal) },
                onOpenPlans = { nav.push(Route.Plans) },
                onOpenPersonas = if (personasOn) ({ nav.push(Route.Persona) }) else null,
                autonomous = autonomous, onAutonomous = app.approvalControl::setAutonomous,
                updateVersion = updateState.update?.version, onOpenAbout = { nav.push(Route.About) },
                onOpenVoiceCatalog = { nav.push(Route.VoiceServices) },
                mcpServers = app.mcpServers,
                openPhoneOps = openPhoneOps, onPhoneOpsOpened = { openPhoneOps = false })
        }
        Route.Models -> ModelsScreen(store, onBack = back, onOpenSpeech = { nav.push(Route.SpeechModel) }, onOpenVoice = { nav.push(Route.VoiceModel) }, onOpenImage = { nav.push(Route.ImageModel) }, onOpenUsage = { nav.push(Route.Usage) }, onOpenVoiceCatalog = { nav.push(Route.VoiceServices) }, onOpenCallMode = { nav.push(Route.CallMode) })
        Route.VoiceModel -> VoiceModelScreen(store, onBack = back, onOpenServices = { nav.push(Route.VoiceServices) })
        Route.VoiceCall -> VoiceCallScreen(voiceCall, onGone = { nav.leave(Route.VoiceCall) })
        Route.CallMode -> CallModeScreen(onBack = back, onOpenStt = { nav.push(Route.SpeechModel) }, onOpenTts = { nav.push(Route.VoiceModel) }, onOpenRealtime = { nav.push(Route.RealtimeVoice) })
        Route.RealtimeVoice -> RealtimeVoiceScreen(store, onBack = back, onOpenCalls = { nav.push(Route.Calls) }, onOpenServices = { nav.push(Route.VoiceServices) })
        Route.VoiceServices -> VoiceServicesScreen(store, onBack = back, onOpenCustom = { nav.push(Route.VoiceCatalog) })
        Route.VoiceCatalog -> VoiceCatalogScreen(com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(context), onBack = back)
        Route.ImageModel -> ImageModelScreen(store, onBack = back)
        Route.Persona -> if (personasOn) PersonaScreen(app.personas, onBack = back) else LaunchedEffect(Unit) { nav.back() }
        Route.Usage -> UsageScreen(app.usage, store, onBack = back)
        Route.SpeechModel -> SpeechModelScreen(store, onBack = back, onOpenServices = { nav.push(Route.VoiceServices) })
        Route.About -> AboutScreen(
            versionName = BuildConfig.VERSION_NAME, build = BuildConfig.GIT_HASH, beta = BuildConfig.BETA,
            update = updateState,
            issueUrl = remember {
                MikaLinks.issueUrl(
                    MikaLinks.Device(
                        Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                        BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH,
                    ),
                )
            },
            onCheck = app.updates::checkInBackground,
            autoUpdate = autoUpdate,
            onAutoUpdate = app.automaticUpdates::setEnabled,
            mirrorEnabled = mirrorEnabled,
            mirrorCount = mirrors.size,
            onMirrorEnabled = { on ->
                mirrorScope.launch {
                    val error = withContext(Dispatchers.IO) { app.updateMirrors.setEnabled(on) }
                    if (error != null) Toast.makeText(context, R.string.about_mirrors_save_failed, Toast.LENGTH_SHORT).show()
                }
            },
            onOpenMirrors = { nav.push(Route.UpdateMirrors) },
            onOpenUrl = { openInBrowser(context, it) },
            onSaveQr = { QrImage.saveToPictures(context) },
            onOpenLicense = { nav.push(Route.License) },
            onBack = back,
            diagnostics = {
                Diagnostics.render(DiagnosticsCollector.collect(context, status))
            },
        )
        Route.UpdateMirrors -> UpdateMirrorsScreen(app.updateMirrors, onBack = back)
        Route.License -> LicensesScreen(onBack = back)
        Route.Browser -> BrowserScreen(remember { UserBrowser.of(context) }, onBack = back)
        Route.Files -> FileLibraryScreen(app.chat, filesConversation, filesFromDrawer, onBack = back, onAdded = { id ->
            app.chat.openConversation(id)
            val opened = app.chat.ui.value.conversationId == id
            if (opened) nav.reset()
            opened
        }, onOpenSource = { id, target ->
            app.chat.openConversationAt(id, target)
            val opened = app.chat.ui.value.conversationId == id
            if (opened) nav.reset()
            opened
        })
        Route.Memory -> {
            val registry = remember { WebSites.registry(context) }
            val sites by registry.entries.collectAsState()
            val webSites = WebSitesState(
                sites = sites,
                onOpenLogin = { WebHandover.showLogin(context, it) },
                onLogout = { WebSites.login(context).logout(it) },
                onLogoutAll = { WebSites.logoutAll(context) },
            )
            val routeStore = remember { FileRouteStore.shared(context) }
            var routeCount by remember { mutableStateOf<Int?>(null) }
            // 第一次取记忆要读盘解密,放到后台线程;页面的协程跟着这一屏走
            val vmScope = rememberCoroutineScope()
            LaunchedEffect(Unit) { routeCount = withContext(Dispatchers.IO) { runCatching { routeStore.count() }.getOrNull() } }
            val vm by produceState<MemoryViewModel?>(null) {
                value = withContext(Dispatchers.IO) {
                    val bundled = GuideCatalog.loadBundled()
                    MemoryViewModel(
                        memoryRuntime.store(), memoryRuntime.settings(), memoryRuntime.skills(),
                        vmScope, memoryRuntime.file().brokenNotice,
                        wipe = { memoryRuntime.scope.launch(Dispatchers.IO) { memoryRuntime.wipeAll() } },
                        onForgotten = { id -> memoryRuntime.scope.launch(Dispatchers.IO) { memoryRuntime.stripMemory(setOf(id)) } },
                        runnableScripts = bundled.runnableScripts(),
                        appDrafts = memoryRuntime.appKnowledge(),
                        builtinSkillNames = bundled.items.map { it.name }.toSet(),
                        onSkillRetired = { app.teach.forget(it) },
                    )
                }
            }
            vm?.let { m ->
                // 从结果卡片的「看看」进来:直接看技能分段
                LaunchedEffect(m) {
                    val open = memoryRuntime.openRequest.value
                    if (open != null) m.select(if (open.draftId != null) Segment.Pending else Segment.Skills)
                }
                MemoryDataScreen(
                    m, webSites, routeCount,
                    onClearRoutes = {
                        vmScope.launch { routeCount = withContext(Dispatchers.IO) { routeStore.clear(); routeStore.count() } }
                    },
                    onBack = { memoryRuntime.requestOpen(null); nav.back() },
                )
            }
        }
        Route.Run -> {
            val id = runId
            val vm = remember(id) { id?.let { RunTimelineViewModel(
                    RunStore(context), it, resolveMemory = app.chat::usedMemoryTexts,
                    alive = { run -> app.taskState.runs.value.any { r -> r.runId.value == run } },
                ) } }
            if (vm != null) RunTimelineScreen(vm, back) else LaunchedEffect(Unit) { nav.back() }
        }
        Route.Report, Route.Playback -> {
            val id = reportRunId
            val vm = remember(id) { id?.let { TaskReportViewModel(RunStore(context), it) } }
            if (vm == null) LaunchedEffect(Unit) { nav.back() }
            else if (route == Route.Report) TaskReportScreen(vm, back,
                onReplay = { playbackScreenshotId = it; nav.push(Route.Playback) },
                onTimeline = { runId = id; nav.push(Route.Run) })
            else TaskPlaybackScreen(vm, playbackScreenshotId, back)
        }
        Route.Source -> {
            val vm = remember(sourceRunId, sourceLegacyText) {
                SourceViewModel(RunStore(context), sourceRunId, sourceLegacyText) { ConfirmationNotifier.appLabel(context, it) }
            }
            SourceScreen(vm, back)
        }
        Route.Plans -> PlansScreen(context, onBack = back, onOpenConversation = {
            app.chat.openConversation(it)
            nav.reset()
        })
        Route.Teach -> TeachScreen(
            app.teach,
            onBack = { nav.back() },
        )
        Route.Screens -> ScreensScreen(app.taskState, onBack = back, onOpenConversation = {
            app.chat.openConversation(it)
            nav.reset()
        })
        Route.Calls -> CallSettingsScreen(app.calls, onBack = back, standalone = true, initialHistoryCallId = focusedCallHistoryId, onOpenVoiceCatalog = { nav.push(Route.VoiceServices) })
    }
    }
        }
        val permanent = LocalAdaptiveLayout.current.hasNavigation
        if (nav.current == Route.Chat || nav.current == Route.Personal || (permanent && sidebarPage)) {
            fun openFromSidebar(route: Route) {
                if (permanent && nav.current != Route.Chat && nav.current != Route.Personal) nav.reset()
                nav.pushFromDrawer(route)
            }
            ChatWithDrawer(
                vm = app.chat,
                check = check,
                reduceMotion = MikaTheme.reduceMotion,
                conversations = { ConversationStore.shared(context).list(includeArchived = true) },
                conversationChanges = ConversationStore.shared(context).changes,
                conversationTitle = { ConversationStore.shared(context).title(it) },
                search = ConversationStore.shared(context).search,
                onSettings = { openFromSidebar(Route.Settings) },
                onScreens = { openFromSidebar(Route.Screens) },
                onAbout = { openFromSidebar(Route.About) },
                updateAvailable = AboutCopy.drawerDot(updateState),
                reopen = nav.reopenDrawer,
                onReopened = nav::drawerReopened,
                onRegularChat = nav::reset,
                resetKey = nav.resetSerial,
                onPersonal = { nav.show(Route.Personal) },
                onPlans = { openFromSidebar(Route.Plans) },
                onMemory = { openFromSidebar(Route.Memory) },
                onBrowser = { openFromSidebar(Route.Browser) },
                frames = if (nav.current == Route.Chat) app.taskState.frames else null,
                operationRun = app.taskState::run,
                chatActive = nav.current == Route.Chat || nav.current == Route.Personal,
                content = page,
            )
        } else page({})
    }
    }
    }

    // 通话的宿主在导航外面:通话页是页面栈里的一页,离开它通话接着通(服务保活),随时能回去
    VoiceCallHost(voiceCall, voiceCallSignal + localVoiceCall, showPage = { nav.bringToTop(Route.VoiceCall) }, hidePage = { nav.leave(Route.VoiceCall) }, onGoConfigure = { gate ->
        // 标准通话缺的是听和说的模型,去「通话方式」;实时通话缺凭据,去电话页的语音设置
        nav.push(if (gate == com.wangzan101.mika.voicecall.ui.VoiceCallGateStage.NeedListenSpeak) Route.CallMode else Route.Calls)
    })

    var showLanguagePrompt by rememberSaveable { mutableStateOf(!AppLanguage.hasPrompted(context)) }
    if (showLanguagePrompt && onboarding == null) {
        LanguagePromptDialog(
            initial = AppLanguage.current(context),
            onConfirm = { lang ->
                showLanguagePrompt = false
                AppLanguage.markPrompted(context)
                AppLanguage.set(context, lang)
            },
            onDismiss = {
                showLanguagePrompt = false
                AppLanguage.markPrompted(context)
            },
        )
    }
}

/** 自身工具能打开的 Mika 顶层页;只给这几页,不给权限/root/开发者子页。 */
private fun com.wangzan101.mika.core.self.SelfScreen.route(): Route = when (this) {
    com.wangzan101.mika.core.self.SelfScreen.Chat -> Route.Chat
    com.wangzan101.mika.core.self.SelfScreen.Settings -> Route.Settings
    com.wangzan101.mika.core.self.SelfScreen.Memory -> Route.Memory
    com.wangzan101.mika.core.self.SelfScreen.Plans -> Route.Plans
    com.wangzan101.mika.core.self.SelfScreen.Browser -> Route.Browser
    com.wangzan101.mika.core.self.SelfScreen.Models -> Route.Models
    com.wangzan101.mika.core.self.SelfScreen.Usage -> Route.Usage
    com.wangzan101.mika.core.self.SelfScreen.Screens -> Route.Screens
    com.wangzan101.mika.core.self.SelfScreen.About -> Route.About
}
