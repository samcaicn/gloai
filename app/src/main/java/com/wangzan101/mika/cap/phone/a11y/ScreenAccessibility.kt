package com.wangzan101.mika.cap.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.calls.onCallerLabelEvent
import com.wangzan101.mika.core.screen.uistate.ScreenEvent
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine

/** Mika 的无障碍服务:操作和事件转给当前已连接实例,任务持有旧实例时也能在重连后恢复。 */
open class ScreenAccessibility : AccessibilityService() {

    private class DisplayEvent(val displayId: Int, val event: AccessibilityEvent)


    override fun onServiceConnected() {
        super.onServiceConnected()
        // 不少 App 把显示结果的文字控件标成「对无障碍不重要」,不读它们就看不到结果;配置文件里已声明,
        // 这里再按运行时补一次,防止系统沿用旧的服务配置
        serviceInfo?.let { info ->
            if (info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS == 0) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                serviceInfo = info
            }
        }
        _instance.value = this
        // 配对时读码、引导页打勾都看它;连上和断开各记一句,排查「明明开着却当没开」
        Log.i(TAG, "accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "accessibility service unbound")
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        _instance.compareAndSet(this, null)
        overlayHost?.closeAll()
        overlayHost = null
    }

    private var overlayHost: OverlayHost? = null

    /** 在用户屏幕上画浮层用的宿主(只在主线程用);服务断开时它加的窗口一起拆掉。 */
    fun overlays(): OverlayHost = overlayHost ?: OverlayHost(this).also { overlayHost = it }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (instance.value !== this) return
        // 只数主屏上的用户交互,不留文字等内容;回锁义务据此判断用户有没有接着用手机
        if (event.eventType in MAIN_INTERACTIONS && displayOf(event) == Display.DEFAULT_DISPLAY) mainInteractions.incrementAndGet()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) windowChanges.tryEmit(Unit)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            windowGeneration.incrementAndGet()
        }
        val app = application as? com.wangzan101.mika.MikaApp
        // Do not copy local consent fields or privately filled code events into teaching/observers.
        if (app != null && (app.appAccess.sensitiveUi && event.packageName?.toString() == app.packageName ||
                app.phone.smsLogin.isPrivate(event.packageName?.toString()) ||
                app.phone.smsLogin.privateLoginActive && event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)) return
        val access = app?.appAccess?.current()
        if (access != null && access.denial(event.packageName?.toString()) != null) return
        app?.runningCalls?.onCallerLabelEvent(this, event)
        if (app != null && app.teachReady && app.teach.wantsEvents()) {
            val displayId = displayOf(event)
            if (displayId != Display.INVALID_DISPLAY) {
                com.wangzan101.mika.teach.TeachEvents.capture(event, displayId)?.let(app.teach::offer)
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && notifications.subscriptionCount.value > 0) {
            val notification = event.parcelableData as? Notification
            val title = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val body = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val text = listOfNotNull(title, body).joinToString(" ").ifBlank { event.text.joinToString(" ") }
            notifications.tryEmit(ScreenEvent("notification", event.packageName?.toString(), text.take(2_000)))
        }
        // 没人订阅就不复制、不查屏号,事件很密
        if (events.subscriptionCount.value == 0) return
        val displayId = displayOf(event)
        if (displayId == Display.INVALID_DISPLAY) return
        // API 33 以下系统会在回调返回后回收事件对象,订阅方异步读取前先复制一份
        events.tryEmit(DisplayEvent(displayId, AccessibilityEvent(event)))
    }

    override fun onInterrupt() = Unit

    /** 指定屏上的窗口;该屏没有窗口(或不存在)时为空列表。 */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> {
        val service = instance.value ?: return emptyList()
        // getWindowsOnAllDisplays 是安卓 11 起才有的;更早的系统只有主屏那一份 getWindows。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return service.windowsOnAllDisplays[displayId].orEmpty()
        if (displayId != Display.DEFAULT_DISPLAY) return emptyList()
        @Suppress("DEPRECATION")
        val main = service.windows
        return main.orEmpty()
    }

    /** 在指定屏上沿 [path] 做一次单指手势;完成返回 true,被取消或无法下发返回 false。 */
    suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean {
        return gesture(displayId, listOf(path), durationMs)
    }

    suspend fun gesture(displayId: Int, paths: List<Path>, durationMs: Long): Boolean =
        dispatch(displayId, paths.map { GestureDescription.StrokeDescription(it, 0, durationMs.coerceAtLeast(1)) })

    /** 在同一点上连点 [count] 下(一次手势里先后几笔):每下按住 [holdMs],两下之间隔 [gapMs]。 */
    suspend fun taps(displayId: Int, x: Float, y: Float, count: Int, holdMs: Long, gapMs: Long): Boolean {
        val at = Path().apply { moveTo(x, y) }
        return dispatch(displayId, (0 until count).map { i -> GestureDescription.StrokeDescription(at, i * (holdMs + gapMs), holdMs.coerceAtLeast(1)) })
    }

    private suspend fun dispatch(displayId: Int, strokes: List<GestureDescription.StrokeDescription>): Boolean {
        val service = instance.value ?: return false
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .apply { strokes.forEach(::addStroke) }
            .build()
        suspend fun send(): Boolean = suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
        }
        // 注入是在用户屏幕上操作别的 App,不是操作 Mika 自己的浮层;所有单点、双击和拖动统一隔离
        return service.overlayHost?.passInjectedGesturesThrough { send() } ?: send()
    }

    /** 截一张用户屏幕(主屏)的结果:画面,或屏上有防截屏窗口,或截不到(错误码)。 */
    sealed interface MainShot {
        class Ok(val bitmap: Bitmap) : MainShot
        data object Secure : MainShot
        data class Failed(val code: Int) : MainShot
    }

    /**
     * 截一张主屏(用户看着的那块屏,包括 Mika 自己的浮层:要不要藏起来由调用方定)。系统限制两次截图至少隔一会儿,
     * 太快时等一秒再截一次。画面是软件位图(可以读像素、编码)。
     */
    suspend fun takeMainScreenshot(): MainShot {
        val first = screenshotOnce()
        if (first !is MainShot.Failed || first.code != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return first
        kotlinx.coroutines.delay(SCREENSHOT_INTERVAL_MS)
        return screenshotOnce()
    }

    private suspend fun screenshotOnce(): MainShot = suspendCancellableCoroutine { cont ->
        val service = instance.value
        if (service == null) {
            cont.resume(MainShot.Failed(ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS))
            return@suspendCancellableCoroutine
        }
        // takeScreenshot 是安卓 11 起才有的;更早的系统当作截不到,不让胶囊的截屏路径崩掉。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cont.resume(MainShot.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            return@suspendCancellableCoroutine
        }
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = runCatching {
                    result.hardwareBuffer.use { buffer ->
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@runCatching null
                        hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
                    }
                }.getOrNull()
                if (cont.isActive) cont.resume(shot?.let(MainShot::Ok) ?: MainShot.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            }

            override fun onFailure(errorCode: Int) {
                // 屏上有防截屏窗口:安卓 14 起有专门的错误码;更早的系统截出来是黑的,由调用方看画面判断
                val secure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                if (cont.isActive) cont.resume(if (secure) MainShot.Secure else MainShot.Failed(errorCode))
            }
        })
    }

    /**
     * 发生在指定屏上的无障碍事件。通道跨实例保留,只有当前连接的服务发布事件。
     */
    fun eventsFor(displayId: Int): Flow<AccessibilityEvent> =
        events.filter { it.displayId == displayId }.map { it.event }

    fun notificationEvents(): Flow<ScreenEvent> = notifications

    /** 窗口变了的信号(见 [windowChanges]);通道跨服务实例保留。 */
    fun windowChanges(): Flow<Unit> = windowChanges

    /**
     * 事件所在的屏。API 33 起事件自带屏号;更早的系统按事件的窗口号去各屏窗口里找,
     * 找不到(如没有窗口号的通知类事件)就丢弃。
     */
    private fun displayOf(event: AccessibilityEvent): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return event.displayId
        val windowId = event.windowId
        if (windowId == -1) return Display.INVALID_DISPLAY
        // 安卓 11 起才有「各屏的窗口」;更早的系统只有主屏一份,只在主屏里找。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            val main = windows
            return if (main.any { it.id == windowId }) Display.DEFAULT_DISPLAY else Display.INVALID_DISPLAY
        }
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) {
            if (all.valueAt(i).any { it.id == windowId }) return all.keyAt(i)
        }
        return Display.INVALID_DISPLAY
    }

    companion object {
        private const val EVENT_BUFFER = 64
        /** 两次截图之间系统要求的间隔(安卓原版是一秒)。 */
        private const val SCREENSHOT_INTERVAL_MS = 1_000L

        // 保持热流:调用方用 UNDISPATCHED 先订阅再操作,不要因服务切换引入异步订阅窗口。
        // 事件只作观察用,订阅方处理不过来时丢最旧的,不阻塞系统回调
        private val events = MutableSharedFlow<DisplayEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        /** 窗口变了(加上、拿掉、挪动,包括输入法弹出收起):只是个信号,订阅方自己去读窗口。 */
        private val windowChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private val windowGeneration = java.util.concurrent.atomic.AtomicLong()

        /** 窗口每加上、拿掉、换页一次就加一(任何屏上的都算);只用来判断「窗口自上次以来变没变」,值本身没有含义。 */
        fun windowGeneration(): Long = windowGeneration.get()

        /** 主屏上用户动过手的次数(点击、长按、滚动、改字、触摸开始);不留事件内容,只数次数。 */
        private val mainInteractions = java.util.concurrent.atomic.AtomicLong()
        private val MAIN_INTERACTIONS = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
        )

        /** 主屏交互的当前计数;只用来判断「自上次以来用户动没动过主屏」,值本身没有含义。 */
        fun mainInteractionCount(): Long = mainInteractions.get()
        private val notifications = MutableSharedFlow<ScreenEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        private const val TAG = "MikaA11y"
        private val _instance = MutableStateFlow<ScreenAccessibility?>(null)
        /** 已连接的服务实例;服务未开启或已断开时为 null。 */
        val instance: StateFlow<ScreenAccessibility?> = _instance.asStateFlow()
    }
}
