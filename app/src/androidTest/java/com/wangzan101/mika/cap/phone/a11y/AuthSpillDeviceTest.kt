package com.wangzan101.mika.cap.phone.a11y

import android.accounts.AccountManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import com.wangzan101.mika.core.screen.uistate.ActionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机测试:测试包临时登记成账号认证器(见 TestAuthenticatorService,清单里默认关着,这里打开、测完关掉),
 * 测试包没有桌面图标,就和系统的账号登录服务一样。它的整屏登录页没有对话框主题、没有密码框、也没有
 * excludeFromRecents,形状上和普通 App 页面一样;只有「所在的包是没有桌面图标的账号认证器」这条能认出它
 * 要用户亲手处理,应该留在主屏,不能被挪到 Mika 的屏上。
 */
@RunWith(AndroidJUnit4::class)
class AuthSpillDeviceTest {
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

    private val authenticatorService = "${DeviceSupport.TEST_PACKAGE}/${TestAuthenticatorService::class.java.name}"

    @After
    fun tearDown() {
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        if (main >= 0 && shell.packageOfTask(main) == DeviceSupport.TEST_PACKAGE) DeviceSupport.sh("input keyevent 4")
        // 关回去,等它从认证器表里下去:别的挪回测试里测试包的页面不能被当成账号登录页
        DeviceSupport.sh("pm disable $authenticatorService")
        DeviceSupport.pollUntil(10_000) { (!testAuthenticatorListed()).takeIf { it } }
        agent.release()
    }

    private fun testAuthenticatorListed(): Boolean {
        val am = AccountManager.get(InstrumentationRegistry.getInstrumentation().targetContext)
        return runCatching { am.authenticatorTypes.any { it.packageName == DeviceSupport.TEST_PACKAGE } }.getOrDefault(false)
    }

    /** 打开测试包的认证器,等它出现在系统的账号认证器表里,这条用例才有意义。 */
    private fun registerTestAuthenticator(): Boolean {
        DeviceSupport.sh("pm enable $authenticatorService")
        return DeviceSupport.pollUntil(10_000) { testAuthenticatorListed().takeIf { it } } == true
    }

    /** 测试包没有 LAUNCHER 入口(清单里没有),判据要的正是这一条。 */
    private fun testPackageHasNoLauncher(): Boolean {
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(DeviceSupport.TEST_PACKAGE)
        return pm.queryIntentActivities(launcher, 0).isEmpty()
    }

    @Test
    fun authenticatorFullScreenPageStaysOnTheMainScreen() = runBlocking<Unit> {
        assertTrue("the test package has a launcher entry", testPackageHasNoLauncher())
        assertTrue("the test package is not registered as an account authenticator", registerTestAuthenticator())
        screen.ensureDisplay()
        val vd = agent.display.displayId
        DeviceSupport.sh(
            "am start --display $vd -n ${DeviceSupport.TEST_PACKAGE}/${SpillTestActivity::class.java.name}" +
                " --es ${SpillTestActivity.EXTRA_AUTH} ${FullScreenAuthTestActivity::class.java.name}",
        )
        val s = DeviceSupport.pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open auth" } }
        }
        assertNotNull("spill page did not come up", s)
        val vdBefore = shell.topTaskId(vd)
        val since = android.os.SystemClock.uptimeMillis()
        val button = s!!.elements.first { it.name == "open auth" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        println("tap result: $r")

        // 服务的进程可能是冷的,弹窗会晚一两秒才出来;等它进了助手的记录
        val b = DeviceSupport.pollUntil(5_000) {
            val arr = shell.mainArrivals(since)
            val pkgs = arr?.getStringArray(MainScreenWatch.KEY_PACKAGE)
            if (pkgs?.contains(DeviceSupport.TEST_PACKAGE) == true) arr else null
        }
        assertNotNull("no arrival on the main screen was reported for the auth page", b)
        delay(500)
        val now = screen.snapshot()
        delay(500)
        val opened = now.opened ?: (r as? ActionResult.Ok)?.opened
        val stillThere = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
            .any { it.root?.packageName?.toString() == DeviceSupport.TEST_PACKAGE }
        println("after: opened=$opened stillOnMain=$stillThere vd top=${shell.topTaskId(vd)} (before $vdBefore)")
        assertTrue("the authenticator page was moved off the user's screen", stillThere)
        assertEquals("Mika's screen changed", vdBefore, shell.topTaskId(vd))
        assertNotNull("the auth page was not reported to the model", opened)
        assertTrue("the auth page was reported as pulled back: $opened",
            !opened!!.pulledBack && opened.onUserScreen && opened.needsUser)
    }
}
