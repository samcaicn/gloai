package com.wangzan101.mika.cap.phone.display

import android.hardware.display.DisplayManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.Build
import android.view.Display
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.shell.BuiltinShellConnection
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.task.AppMuter
import com.wangzan101.mika.task.AudioMute
import com.wangzan101.mika.task.MarkerStore
import com.wangzan101.mika.task.PrefsMarker
import com.wangzan101.mika.task.ShellAudio
import com.wangzan101.mika.core.screen.uistate.ActionResult
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** 真机测试:需要内置助手在运行(已配对、无线调试开着)。 */
@RunWith(AndroidJUnit4::class)
class AgentDisplayTest {
    // 整个测试类共用一条连接,与 App 里长期持有一条连接的用法一致
    companion object {
        private const val SETTINGS = "com.android.settings"
        private const val SHELL_PROCESS = "com.wangzan101.mika:helper"
        private lateinit var connection: BuiltinShellConnection

        @BeforeClass
        @JvmStatic
        fun bindShell() {
            val phone = PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext)
            connection = BuiltinShellConnection(phone.hub) { phone.shellManager.engine.need() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.bind() }
            awaitShell()
        }

        @AfterClass
        @JvmStatic
        fun unbindShell() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.unbind() }
        }

        private fun awaitShell(): IMikaShell =
            DeviceSupport.awaitShell(connection)
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shell: IMikaShell get() = awaitShell()
    private var display: AgentDisplay? = null

    @After
    fun tearDown() {
        display?.release()
    }

    private fun newDisplay() = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java)).also { display = it }

    @Test
    fun createsVersionAppropriatePublicDisplay() {
        val d = newDisplay()
        val dm = context.getSystemService(DisplayManager::class.java)
        val shown = dm.getDisplay(d.displayId)
        assertNotNull("display ${d.displayId} not visible to the app", shown)
        assertEquals(0, shown.flags and Display.FLAG_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue("display is not trusted", shown.flags and displayFlagTrusted() != 0)
        }
    }

    @Test
    fun startsSettingsOnDisplay() {
        val d = newDisplay()
        assertEquals(ShellService.START_OK, shell.startApp(d.displayId, SETTINGS))
        val task = pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } }
        assertNotNull("no task on display ${d.displayId}", task)
    }

    @Test
    fun startAppRefusesAppVisibleOnMainScreen() {
        val d = newDisplay()
        shellCommand("am start -W -a android.settings.SETTINGS --display 0")
        try {
            val mainTop = pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it != -1 } }
            assertEquals(ShellService.START_IN_USE_ON_MAIN, shell.startApp(d.displayId, SETTINGS))
            Thread.sleep(1_000)
            assertEquals("main screen top task changed", mainTop, shell.topTaskId(Display.DEFAULT_DISPLAY))
            assertEquals(-1, shell.topTaskId(d.displayId))
        } finally {
            // 测试自己在主屏打开的设置:回桌面并结束它,免得收尾动画期间仍算「主屏可见」影响后面的用例
            shellCommand("input -d 0 keyevent KEYCODE_HOME")
            shellCommand("am force-stop $SETTINGS")
        }
    }

    @Test
    fun frameArrives() {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        val png = pollUntil(2_000) { d.latestFramePng() }
        assertNotNull(png)
        // PNG 文件头
        assertEquals(0x89.toByte(), png!![0])
        assertEquals('P'.code.toByte(), png[1])
    }

    @Test
    fun screenshotIsIsolatedFromDifferentMainDisplayContent() {
        shellCommand("am start -W -a android.settings.SETTINGS --display 0")
        try {
            val d = newDisplay()
            val screen = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(d))
            val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
            shellCommand("am start -W --display ${d.displayId} -n $testPackage/${RotationTestActivity::class.java.name} --ei orientation ${ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}")
            assertNotNull("settings did not open on main", pollUntil(5_000) {
                shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it != -1 }
            })
            val mainState = DeviceSupport.mainScreenState(shell)
            val image = pollUntil(5_000) {
                val png = runBlocking { screen.screenshotPng() } ?: return@pollUntil null
                val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
                if (bitmap.width == 720 && bitmap.height == 1600 && uprightCorners(bitmap)) bitmap
                else {
                    bitmap.recycle()
                    null
                }
            }
            assertNotNull("task screenshot did not contain the task display's color markers", image)
            image!!.recycle()
            assertEquals("capturing the task display changed main-screen focus", mainState, DeviceSupport.mainScreenState(shell))
        } finally {
            shellCommand("input -d 0 keyevent KEYCODE_HOME")
            shellCommand("am force-stop $SETTINGS")
        }
    }

    @Test
    fun openingAnotherAppNeverScreenshotsThePreviousAppsFrame() {
        val d = newDisplay()
        val screen = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(d))
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        // 先留一张带四个明确色块的旧帧,再通过生产入口冷启动另一个 App。
        shellCommand("am start -W --display ${d.displayId} -n $testPackage/${RotationTestActivity::class.java.name} --ei orientation ${ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}")
        val previous = pollUntil(5_000) {
            d.latestFramePng()?.takeIf { png ->
                val image = BitmapFactory.decodeByteArray(png, 0, png.size)
                try { uprightCorners(image) } finally { image.recycle() }
            }
        }
        assertNotNull("the previous app's frame never arrived", previous)

        val opened = runBlocking { screen.openApp(SETTINGS, cold = true) }
        assertTrue("openApp: $opened", opened is ActionResult.Ok)
        // 不等另一帧:生产入口返回后,第一次截图就不能还是启动前的色块页。
        val png = runBlocking { screen.screenshotPng() }
        assertNotNull("the newly opened app has no servable frame", png)
        val image = BitmapFactory.decodeByteArray(png, 0, png!!.size)
        try {
            assertFalse("screenshot still contains the previous app's color markers", uprightCorners(image))
        } finally { image.recycle() }
    }

    @Test
    fun framesFlowEmits() = runBlocking {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        val frame = withTimeoutOrNull(2_000) { d.frames.filterNotNull().first() }
        assertNotNull("no preview frame within 2s", frame)
        assertTrue("preview width ${frame!!.width} > 360", frame.width <= 360)    }

    @Test
    fun releaseClearsFrames() = runBlocking {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        withTimeout(2_000) { d.frames.filterNotNull().first() }
        d.release()
        assertEquals(null, d.frames.value)
    }

    @Test
    fun appOrientationUpdatesPreviewScreenshotAndScreenCoordinates() = runBlocking {
        val d = newDisplay()
        val main = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mainRotation = main.rotation
        val mainState = DeviceSupport.mainScreenState(shell)
        val screen = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(d))
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
            shellCommand("am start -W --display ${d.displayId} -f 0x30000000 " +
                "-n $testPackage/${RotationTestActivity::class.java.name} --ei orientation $orientation")
            val landscape = orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            val width = if (landscape) 1600 else 720
            val height = if (landscape) 720 else 1600
            val previewWidth = if (landscape) 800 else 360
            val preview = pollUntil(5_000) {
                d.frames.value?.takeIf { it.width == previewWidth && it.height == height * previewWidth / width && uprightCorners(it) }
            }
            assertNotNull("preview did not follow orientation $orientation: logical=${d.width}x${d.height}, " +
                "preview=${d.frames.value?.let { "${it.width}x${it.height}" }}", preview)
            assertEquals(width, d.width)
            assertEquals(height, d.height)
            val snapshot = screen.snapshot()
            assertEquals(width, snapshot.width)
            assertEquals(height, snapshot.height)
            val png = d.latestFramePng()!!
            val image = BitmapFactory.decodeByteArray(png, 0, png.size)
            try {
                assertEquals(width, image.width)
                assertEquals(height, image.height)
                assertTrue("PNG corners for orientation $orientation", uprightCorners(image))
            } finally { image.recycle() }
            assertTrue(shell.injectTap(d.displayId, width * 3 / 4, height * 3 / 4))
            assertNotNull("logical tap missed the bottom-right quadrant", pollUntil(3_000) {
                val image = d.latestFrameBitmap() ?: return@pollUntil null
                try { true.takeIf { nearColor(image.getPixel(width * 3 / 4, height * 3 / 4), Color.CYAN) } }
                finally { image.recycle() }
            })
            assertEquals("main display rotated", mainRotation, main.rotation)
            assertEquals("main screen focus changed", mainState, DeviceSupport.mainScreenState(shell))
        }
    }

    private fun uprightCorners(frame: Bitmap): Boolean =
        nearColor(frame.getPixel(frame.width / 4, frame.height / 4), Color.RED) &&
            nearColor(frame.getPixel(frame.width * 3 / 4, frame.height / 4), Color.GREEN) &&
            nearColor(frame.getPixel(frame.width / 4, frame.height * 3 / 4), Color.BLUE) &&
            nearColor(frame.getPixel(frame.width * 3 / 4, frame.height * 3 / 4), Color.YELLOW)

    private fun normalizedAudioMode(mode: String?): String? = if (mode == "default") "allow" else mode

    // Surface 合成和 GPU 转屏可能把通道的 255 变为 254;容许量化误差,仍严格区分四个象限。
    private fun nearColor(actual: Int, expected: Int): Boolean =
        kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= 4 &&
            kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= 4 &&
            kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= 4

    @Test
    fun movesTaskBetweenVirtualDisplays() {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        val task = pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } }!!
        // 在两块虚拟屏之间挪,不碰用户的主屏
        val other = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        try {
            assertTrue(shell.moveTask(task, other.displayId))
            assertEquals(task, pollUntil(3_000) { shell.topTaskId(other.displayId).takeIf { it == task } })
            assertNotEquals(task, shell.topTaskId(d.displayId))
        } finally {
            other.release()
        }
    }

    @Test
    fun coldStartDoesNotKillAppWithTaskOnAnotherScreen() {
        val d = newDisplay()
        // 用另一块虚拟屏代表「Mika 屏以外的任务」,不碰用户的主屏
        val other = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        try {
            assertEquals(ShellService.START_OK, shell.startApp(other.displayId, SETTINGS))
            assertNotNull(pollUntil(5_000) { shell.topTaskId(other.displayId).takeIf { it != -1 } })
            val pid = pollUntil(3_000) { shellCommand("pidof $SETTINGS").trim().takeIf { it.isNotEmpty() } }
            assertNotNull(pid)
            // 另一块屏也是 Mika 的屏(代表另一个任务):直接拒绝、不强停,返回「被别的任务占用」
            assertEquals(ShellService.START_IN_USE_BY_OTHER, shell.restartApp(d.displayId, SETTINGS))
            assertEquals("settings process was killed", pid, shellCommand("pidof $SETTINGS").trim())
        } finally {
            other.release()
        }
    }

    @Test
    fun injectsInputOnDisplay() {
        val d = newDisplay()
        val mainTop = shell.topTaskId(Display.DEFAULT_DISPLAY)
        shell.startApp(d.displayId, SETTINGS)
        assertNotNull(pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } })
        val home = stableFrame(d)

        assertTrue(shell.injectTap(d.displayId, 360, 800))
        val afterTap = pollUntil(3_000) { d.latestFramePng()?.takeIf { !it.contentEquals(home) } }
        assertNotNull("tap had no visible effect on the virtual display", afterTap)
        val subPage = stableFrame(d)

        assertTrue(shell.injectKey(d.displayId, KeyEvent.KEYCODE_BACK))
        val afterBack = pollUntil(3_000) { d.latestFramePng()?.takeIf { !it.contentEquals(subPage) } }
        assertNotNull("BACK had no visible effect on the virtual display", afterBack)

        assertEquals("main screen top task changed", mainTop, shell.topTaskId(Display.DEFAULT_DISPLAY))
    }

    @Test
    fun mutesAndRestoresAudio() {
        val before = shell.getAudioMode(SETTINGS)
        assertNotNull(before)
        assertTrue(shell.setAudioMuted(SETTINGS, true))
        assertEquals("ignore", shell.getAudioMode(SETTINGS))
        assertTrue(shell.setAudioMuted(SETTINGS, false))
        assertEquals(normalizedAudioMode(before), normalizedAudioMode(shell.getAudioMode(SETTINGS)))
    }

    @Test
    fun appMuterLeavesAnUnrelatedPackageUntouchedAcrossTasks() {
        val other = DeviceSupport.TEST_PACKAGE
        val targetBefore = shell.getAudioMode(SETTINGS)
        val otherBefore = shell.getAudioMode(other)
        assertNotNull(targetBefore)
        assertNotNull(otherBefore)
        try {
            repeat(2) {
                // 与真实任务一致:每一轮都有新的 AppMuter、AudioMute 和独立记录,不能跨任务复用内存里的 muted 集。
                var values: Map<String, String?>? = null
                val store = object : MarkerStore {
                    override fun load() = values
                    override fun save(v: Map<String, String?>) { values = v }
                    override fun clear() { values = null }
                }
                val audio = AudioMute(ShellAudio(shell), store)
                val muter = AppMuter(audio, enabled = { true }, mutable = { true }, onMainScreen = { _, _ -> false }, failed = {})
                muter.beforeOpen(SETTINGS, null)
                muter.opened(SETTINGS, null)
                assertEquals("target was not muted", "ignore", shell.getAudioMode(SETTINGS))
                assertEquals("unrelated package changed while target was muted", otherBefore, shell.getAudioMode(other))
                assertTrue(audio.restoreAll())
                assertEquals("target was not restored", normalizedAudioMode(targetBefore), normalizedAudioMode(shell.getAudioMode(SETTINGS)))
                assertEquals("unrelated package changed after cleanup", otherBefore, shell.getAudioMode(other))
            }
        } finally {
            if (targetBefore != null) shell.setAudioMode(SETTINGS, targetBefore)
        }
    }

    /** 浮层动作最终经过的真实音频路径：恢复原值，而且本次任务不再自动静音。 */
    @Test
    fun manualUnmuteRestoresRealAudioAndSkipsLaterAutomaticMutes() {
        val prefs = context.getSharedPreferences("manual-unmute-device-test", 0)
        prefs.edit().clear().commit()
        val record = AudioMute(ShellAudio(shell), PrefsMarker(prefs, "audio"))
        val muter = AppMuter(
            record,
            enabled = { true },
            mutable = { it == SETTINGS },
            onMainScreen = { _, _ -> false },
            failed = { error("unexpected audio mute failure for $it") },
        )
        val before = shell.getAudioMode(SETTINGS)
        assertNotNull(before)
        try {
            muter.opened(SETTINGS, null)
            assertEquals("ignore", shell.getAudioMode(SETTINGS))
            assertTrue(muter.unmute(SETTINGS, null))
            assertEquals(normalizedAudioMode(before), normalizedAudioMode(shell.getAudioMode(SETTINGS)))
            muter.opened(SETTINGS, null)
            assertEquals(normalizedAudioMode(before), normalizedAudioMode(shell.getAudioMode(SETTINGS)))
        } finally {
            record.restoreAll()
            shell.setAudioMode(SETTINGS, before)
            prefs.edit().clear().commit()
        }
    }

    /** 助手进程被杀后,看门狗重起助手,连接拿到可用的新服务。 */
    @Test
    fun reconnectsAfterShellProcessDies() {
        val before = shell
        val pid = shellPid()
        assertNotNull("no $SHELL_PROCESS process", pid)
        shellCommand("kill $pid")
        val after = pollUntil(30_000) {
            connection.shell.value?.takeIf { it.asBinder() != before.asBinder() && it.asBinder().pingBinder() }
        }
        assertNotNull("shell did not come back after its process died", after)
        assertNotEquals(pid, shellPid())
        val d = AgentDisplay(after!!, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java)).also { display = it }
        assertTrue(d.displayId >= 0)
    }

    private fun shellPid(): Int? = shellCommand("ps -A -o PID,NAME").lines()
        .map { it.trim().split(Regex("\\s+")) }
        .firstOrNull { it.size == 2 && it[1] == SHELL_PROCESS }
        ?.get(0)?.toIntOrNull()

    private fun shellCommand(cmd: String): String {
        return DeviceSupport.sh(cmd)
    }

    /** 等画面静止(相隔 500ms 的两帧一样),返回该帧。 */
    private fun stableFrame(d: AgentDisplay): ByteArray {
        var last = pollUntil(2_000) { d.latestFramePng() }!!
        repeat(10) {
            Thread.sleep(500)
            val now = d.latestFramePng()!!
            if (now.contentEquals(last)) return now
            last = now
        }
        return last
    }

    // Display.FLAG_TRUSTED 是隐藏常量(1 shl 7),反射取不到时用其数值
    private fun displayFlagTrusted(): Int =
        runCatching { Display::class.java.getField("FLAG_TRUSTED").getInt(null) }.getOrDefault(1 shl 7)

    private fun <T : Any> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(50)
        }
        return block()
    }
}
