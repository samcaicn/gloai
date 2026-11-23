package com.wangzan101.mika.cap.phone.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.screen.uistate.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机:有能用的另一份用户(分身、工作资料)时,把装在那里的一个 App 开到 Mika 的屏上,最上面的任务属于那份用户,主屏不变。
 * 要测哪一个由参数定:`-Pandroid.testInstrumentationRunnerArguments.copyPackage=<包名>`(可再给 copyUser=<用户编号>);
 * 不给就用列出的第一份。系统先问打开哪一个时,由打开那一步自己点选项、认出要的那份。
 * 没有另一份用户、或那里没有要的 App 时跳过。只打开,不在 App 里做任何操作。
 */
@RunWith(AndroidJUnit4::class)
class AppCopyDeviceTest {
    private val agent = DeviceSupport.newAgent()

    @After
    fun tearDown() = agent.release()

    @Test
    fun launchesTheCopyInItsUserOnMikasDisplay() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val users = shell.appCopyUsers()?.getIntArray(ShellService.KEY_COPY_USERS)
        assumeTrue("no usable other user on this device: ${shell.appCopyUsers()?.getString(ShellService.KEY_COPY_SUMMARY)}", users != null && users.isNotEmpty())
        val args = InstrumentationRegistry.getArguments()
        val wantPackage = args.getString("copyPackage")
        val wantUser = args.getString("copyUser")?.toIntOrNull()
        val copies = agent.screen.appCopies()
        val copy = copies.firstOrNull { (wantPackage == null || it.packageName == wantPackage) && (wantUser == null || it.userId == wantUser) }
        assumeTrue("no such copy (asked for ${wantPackage ?: "any"} in user ${wantUser ?: "any"}); listed: $copies", copy != null)
        copy!!
        val main = DeviceSupport.mainScreenState(shell)
        val r = agent.screen.openAppCopy(copy.packageName, copy.userId)
        assertTrue("open ${copy.packageName} in user ${copy.userId}: $r", r is ActionResult.Ok)
        r as ActionResult.Ok
        assertEquals("result must say which copy is showing: $r", copy.userId, r.profileUser)
        val top = shell.topTaskId(agent.display.displayId)
        assertEquals(copy.packageName, shell.packageOfTask(top))
        assertEquals(copy.userId, shell.userOfTask(top))
        assertEquals(copy.userId, agent.screen.target.value?.userId)
        assertEquals("main screen changed", main, DeviceSupport.mainScreenState(shell))
        // 不在能用之列的用户一律不启动
        assertEquals(ShellService.START_USER_NOT_ALLOWED, shell.startAppInUser(agent.display.displayId, copy.packageName, 99_999, false))
    }
}
