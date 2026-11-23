package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机测试:后台进程在主屏上打开一个对话框样式的页面(登录验证、账号确认一类要用户在主屏点确认的弹窗)。
 * 这种弹窗要留在主屏给用户;Mika 的屏不该被它盖住,也不该把它挪到后台屏上。
 */
@RunWith(AndroidJUnit4::class)
class DialogSpillDeviceTest {
    // 和任务里一样,屏的尺寸、密度同主屏:挪过来的页面不必因尺寸变了重建
    private val agent = run {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val m = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val shell = DeviceSupport.shell()
        val d = com.wangzan101.mika.cap.phone.display.AgentDisplay(shell, m.widthPixels, m.heightPixels, m.densityDpi, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        DeviceSupport.Agent(d, AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(d)))
    }
    private val screen get() = agent.screen
    private val shell = DeviceSupport.shell()

    @After
    fun tearDown() {
        // 失败时弹窗可能还留在主屏:关掉它
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        if (main >= 0 && shell.packageOfTask(main) == DeviceSupport.TEST_PACKAGE) DeviceSupport.sh("input keyevent 4")
        agent.release()
    }

    /** 主屏上各窗口的类型、包和范围:对话框样式的窗口明显比整屏小。 */
    private fun dumpMainWindows(label: String) {
        val service = DeviceSupport.service()
        for (w in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val b = Rect()
            w.getBoundsInScreen(b)
            println("$label window type=${w.type} pkg=${w.root?.packageName} bounds=$b")
        }
    }

    @Test
    fun dialogOnTheMainScreenIsLeftForTheUser() = runBlocking<Unit> {
        // 先单独看一眼这种对话框在主屏上的窗口(不经过 Mika 的动作)
        DeviceSupport.sh("am start -n ${DeviceSupport.TEST_PACKAGE}/${DialogSpillTestActivity::class.java.name}")
        DeviceSupport.pollUntil(5_000) {
            shell.topActivity(Display.DEFAULT_DISPLAY)?.takeIf { it.contains("DialogSpillTestActivity") }
        }
        dumpMainWindows("dialog")
        DeviceSupport.sh("input keyevent 4")
        delay(500)

        screen.ensureDisplay()
        val vd = agent.display.displayId
        DeviceSupport.sh(
            "am start --display $vd -n ${DeviceSupport.TEST_PACKAGE}/${SpillTestActivity::class.java.name}" +
                " --es ${SpillTestActivity.EXTRA_DIALOG} ${DialogSpillTestActivity::class.java.name}",
        )
        val s = DeviceSupport.pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open dialog" } }
        }
        assertNotNull("spill page did not come up", s)
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val vdBefore = shell.topTaskId(vd)
        val since = SystemClock.uptimeMillis()
        val button = s!!.elements.first { it.name == "open dialog" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        println("tap result: $r")

        // 从助手的记录里认这次冒到主屏上的弹窗任务(挪走后任务号不变)
        val b = shell.mainArrivals(since)
        val packages = b?.getStringArray(MainScreenWatch.KEY_PACKAGE)
        val tasks = b?.getIntArray(MainScreenWatch.KEY_TASK)
        val dialogTask = packages?.indices?.firstOrNull { packages[it] == DeviceSupport.TEST_PACKAGE }?.let { tasks?.get(it) }
        assertNotNull("no arrival on the main screen was reported for the popup", dialogTask)
        dumpMainWindows("after action")

        // 让 Mika 有机会处理这次冒到主屏上的任务(动作里没赶上的,读屏时会补)
        delay(1_500)
        val now = screen.snapshot()
        delay(500)
        val opened = now.opened ?: (r as? com.wangzan101.mika.core.screen.uistate.ActionResult.Ok)?.opened
        val stillThere = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
            .any { it.root?.packageName?.toString() == DeviceSupport.TEST_PACKAGE }
        println(
            "after: opened=$opened main top=${shell.topTaskId(Display.DEFAULT_DISPLAY)} " +
                "dialogTask=$dialogTask stillOnMain=$stillThere vd top=${shell.topTaskId(vd)} (before $vdBefore, main before $mainBefore)",
        )
        // 弹窗还在主屏,用户能接着点确认
        assertTrue("the dialog was moved off the user's screen", stillThere)
        // Mika 的屏还是原来那一页
        assertEquals("Mika's screen changed", vdBefore, shell.topTaskId(vd))
        assertNotNull("the popup was not reported to the model", opened)
        assertEquals(DeviceSupport.TEST_PACKAGE, opened!!.packageName)
        assertTrue("the popup was reported as pulled back: $opened", !opened.pulledBack && opened.onUserScreen && opened.needsUser)
    }
}
