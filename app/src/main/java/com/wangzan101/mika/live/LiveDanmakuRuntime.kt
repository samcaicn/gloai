package com.wangzan101.mika.live

import android.util.Log
import android.view.Display
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.ScreenReader
import com.wangzan101.mika.core.live.LiveDanmakuFilter
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.voice.VoicePlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 抖音直播间的评论弹幕播报器。
 *
 * 用户在声音页打开开关、前台是抖音直播间、并且应用访问里没有屏蔽抖音时,
 * 从主屏的无障碍树里读评论文字,交给 [LiveDanmakuFilter] 去重限速,再一条一条念出来。
 *
 * 不另起前台服务:Mika 的无障碍服务本来就把进程托着,而读屏也必须有它。
 * 播报用一条单独的串行协程 —— [MikaApp.voicePlayer] 是「后来者覆盖前者」,直接并发调用会丢弹幕。
 */
internal class LiveDanmakuRuntime(
    private val app: MikaApp,
    private val scope: CoroutineScope,
) {
    private val filter = LiveDanmakuFilter()
    private val queue = Channel<String>(Channel.BUFFERED)
    private var jobs: List<Job> = emptyList()
    private var running = false
    private var watcher: Job? = null

    /** 进程启动时调用一次;之后声音开关和「完全退出」都由流驱动。 */
    fun start() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            combine(app.voiceSettings.state, FeatureFlags.get(app).quitChanges()) { voice, quit ->
                voice.enabled && voice.liveDanmaku && !quit
            }.distinctUntilChanged().collect { wanted ->
                if (wanted) enable() else disable()
            }
        }
    }

    private fun enable() {
        if (running) return
        running = true
        filter.clear()
        drain()
        jobs = listOf(
            scope.launch { observe() },
            scope.launch { speak() },
        )
    }

    private fun disable() {
        if (!running) return
        running = false
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        drain()
    }

    private fun drain() {
        while (queue.tryReceive().isSuccess) { /* 丢掉关闭前没来得及念的 */ }
    }

    /** 只看主屏的无障碍文字,不截图、不 OCR:直播间的评论是原生 TextView,树里就有。 */
    private suspend fun observe() {
        while (currentCoroutineContext().isActive) {
            val service = ScreenAccessibility.instance.value
            if (service == null) {
                delay(IDLE_POLL_MS)
                continue
            }
            val metrics = service.resources.displayMetrics
            val read = ScreenReader.read(
                service.windowsOn(Display.DEFAULT_DISPLAY),
                metrics.widthPixels, metrics.heightPixels,
            )
            val pkg = read.topPackage
            if (pkg == null || pkg !in DOUYIN_PACKAGES || !app.appAccess.current().canShare(pkg)) {
                delay(IDLE_POLL_MS)
                continue
            }
            val now = System.currentTimeMillis()
            for (entry in read.entries) {
                val e = entry.element
                if (e.hint || e.secret || e.editable) continue
                // 顶部标题栏、底部输入框和按钮都不念;评论在中间偏下那一段
                if (e.centerY !in COMMENT_TOP..COMMENT_BOTTOM) continue
                filter.accept(e.name, now)?.let { queue.trySend(it) }
            }
            delay(POLL_MS)
        }
    }

    /** 单消费者:一条念完再念下一条,不打断聊天里正在播的回答。 */
    private suspend fun speak() {
        while (currentCoroutineContext().isActive) {
            val text = queue.receive()
            while (currentCoroutineContext().isActive && app.voicePlayer.state.value != VoicePlayback.Idle) {
                delay(WAIT_PLAYER_MS)
            }
            if (!currentCoroutineContext().isActive) break
            runCatching { app.speak(text) }
                .onFailure { Log.w(TAG, "could not speak live comment", it) }
            delay(SPEAK_GAP_MS)
        }
    }

    private companion object {
        const val TAG = "MikaLiveDanmaku"
        val DOUYIN_PACKAGES = setOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite")
        const val POLL_MS = 700L
        const val IDLE_POLL_MS = 1_500L
        const val COMMENT_TOP = 300
        const val COMMENT_BOTTOM = 900
        const val WAIT_PLAYER_MS = 200L
        const val SPEAK_GAP_MS = 250L
    }
}
