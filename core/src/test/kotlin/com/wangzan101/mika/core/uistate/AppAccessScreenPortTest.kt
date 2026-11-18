package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.access.AppAccessDenied
import com.wangzan101.mika.core.access.AppAccessPolicy
import com.wangzan101.mika.core.access.AppAccessState
import com.wangzan101.mika.core.tool.FakeScreen
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppAccessScreenPortTest {
    @Test fun emptyBlocklistAllowsUnknownPackageWithoutProbe() = runTest {
        assertNull(AppAccessState().denial(null))
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy.ALLOW,
            packages = { error("empty blocklist must not query windows") })
        screen.snapshot()
    }

    @Test fun unknownPackageIsReadOnceMore() = runTest {
        var probes = 0
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = {
            if (++probes == 1) listOf(null) else listOf("allowed")
        })
        screen.checkAppAccess()
        assertEquals(2, probes)
    }

    @Test fun stillUnknownPackageIsRefusedAfterOneRetry() = runTest {
        var probes = 0
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = { probes++; listOf(null) })
        denied("app_access_unverified") { screen.checkAppAccess() }
        assertEquals(2, probes)
    }

    @Test fun retryCanIdentifyBlockedPackage() = runTest {
        var probes = 0
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = {
            if (++probes == 1) listOf(null) else listOf("bank")
        })
        denied("app_blocked") { screen.checkAppAccess() }
        assertEquals(2, probes)
    }

    @Test fun unavailableSettingsDenyEvenWhenListIsEmpty() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy { AppAccessState(available = false) },
            packages = { error("must reject before probing") })
        denied("app_access_unavailable") { screen.checkAppAccess() }
    }

    @Test fun uncreatedDisplayDoesNotNeedAnApp() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = { emptyList() })
        screen.checkAppAccess()
    }

    @Test fun sensitiveOwnUiIsProtectedEvenWithEmptyList() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy.ALLOW, packages = { listOf("mika") },
            protectedUi = { true }, ownPackage = "mika")
        denied("app_access_protected") { screen.snapshot() }
    }

    @Test fun blocklistIsRereadAfterWaiting() = runTest {
        var state = AppAccessState(blocked = setOf("bank"))
        var probes = 0
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy { state }, packages = {
            probes++
            if (probes == 2) state = AppAccessState()
            listOf(null)
        })
        screen.checkAppAccess()
        assertEquals(2, probes)
    }

    @Test fun runtimeBlockStopsNextReadAndAllowRestoresIt() = runTest {
        var state = AppAccessState()
        var reads = 0
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun screenshotPng(): ByteArray { reads++; return byteArrayOf(1) }
        }
        val screen = AppAccessScreenPort(inner, AppAccessPolicy { state }, packages = { listOf("bank") })
        assertArrayEquals(byteArrayOf(1), screen.screenshotPng())
        state = AppAccessState(blocked = setOf("bank"), revision = 1)
        denied("app_blocked") { screen.screenshotPng() }
        assertEquals(1, reads)
        state = AppAccessState(revision = 2)
        assertArrayEquals(byteArrayOf(1), screen.screenshotPng())
    }

    @Test fun readThatCrossesIntoBlockedAppDoesNotReturnContent() = runTest {
        var pkg = "allowed"
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun screenshotPng(): ByteArray { pkg = "bank"; return byteArrayOf(1) }
        }
        val screen = AppAccessScreenPort(inner, blocked(), packages = { listOf(pkg) })
        denied("app_blocked") { screen.screenshotPng() }
    }

    @Test fun canLeaveBlockedAppButCannotLaunchBlockedTarget() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = { listOf("bank") })
        screen.openApp("allowed")
        denied("app_blocked") { screen.openApp("bank") }
        denied("app_blocked") { screen.openAppCopy("bank", 10) }
        denied("app_blocked") { screen.shareText("bank", "text") }
        denied("app_blocked") { screen.awaitAppFree("bank", null, 1_000) }
    }

    // ---------- 每个端口成员都过这道边界 ----------

    /** 端口的每个成员都在边界里显式转交给里面的端口:漏写的会落到接口的默认实现上,到不了里面。 */
    @Test fun everyPortMemberIsForwardedExplicitly() {
        val reached = mutableSetOf<String>()
        val screen = AppAccessScreenPort(recordingPort(reached), AppAccessPolicy.ALLOW, packages = { listOf("bank") })
        for (m in portMembers()) {
            reached.clear()
            invoke(screen, m)
            assertTrue("${m.name} is not forwarded explicitly by AppAccessScreenPort", m.name in reached)
        }
    }

    /** 禁用的 App 在前台时,除了明说不碰 App 界面的那几个,端口的每个成员都被拒绝;新加的成员默认算在要检查的里面。 */
    @Test fun everyPortMemberExceptPassThroughIsChecked() {
        val screen = AppAccessScreenPort(recordingPort(mutableSetOf()), blocked(), packages = { listOf("bank") })
        for (m in portMembers()) {
            val error = runCatching { invoke(screen, m) }.exceptionOrNull()
            if (m.name in PASS_THROUGH) assertNull("${m.name} should pass through", error)
            else assertTrue("${m.name} is not checked by AppAccessScreenPort (got $error)", error is AppAccessDenied)
        }
    }

    private fun portMembers(): List<Method> = ScreenPort::class.java.methods.filter { it.name !in OWN }

    /** 什么都只记下名字的端口;读屏给一屏前台是 bank 的。 */
    private fun recordingPort(reached: MutableSet<String>): ScreenPort =
        Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, m, _ ->
            reached += m.name
            when {
                m.name == "snapshot" || m.name == "peek" -> ScreenSnapshot("s", "bank", emptyList(), 1, 1)
                m.returnType == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        } as ScreenPort

    /** 按参数类型给样例值调一次(挂起函数最后一个参数是续体,这里的调用都不会真挂起);抛出的异常原样抛出。 */
    private fun invoke(screen: ScreenPort, m: Method) {
        val args = m.parameterTypes.map { t ->
            when (t) {
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Double::class.javaPrimitiveType -> 0.0
                Boolean::class.javaPrimitiveType -> false
                String::class.java -> ""
                Int::class.javaObjectType -> null
                List::class.java -> emptyList<Any>()
                SystemAction::class.java -> SystemAction.ShowAlarms
                Continuation::class.java -> Continuation<Any?>(EmptyCoroutineContext) {}
                else -> error("no sample for ${t.name} in ${m.name}: add one")
            }
        }
        try {
            m.invoke(screen, *args.toTypedArray())
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    // ---------- 少问窗口 ----------

    @Test fun snapshotIsCheckedAgainstItsOwnWindowsWithoutProbing() = runTest {
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = withWindows("allowed")
        }
        val screen = AppAccessScreenPort(inner, blocked(), packages = { error("snapshot carries its windows") })
        assertEquals("allowed", screen.snapshot().packageName)
        val bank = AppAccessScreenPort(object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = withWindows("bank")
        }, blocked(), packages = { error("snapshot carries its windows") })
        denied("app_blocked") { bank.snapshot() }
    }

    @Test fun snapshotWithUnreadableWindowStillRetriesOnce() = runTest {
        var probes = 0
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = withWindows(null)
        }
        val screen = AppAccessScreenPort(inner, blocked(), packages = { probes++; listOf("bank") })
        denied("app_blocked") { screen.snapshot() }
        assertEquals(1, probes)
    }

    @Test fun readsRightAfterASnapshotReuseItsCheckWhileWindowsStay() = runTest {
        var token = 0L
        var now = 0L
        var probes = 0
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = withWindows("allowed")
        }
        val screen = AppAccessScreenPort(inner, blocked(), packages = { probes++; listOf("allowed") },
            windowToken = { token }, clock = { now })
        screen.snapshot()
        screen.screenshotPng()
        screen.frameGrid()
        repeat(10) { screen.checkAppAccess() }
        assertEquals(0, probes)
        // 窗口变了:再问一次,之后又沿用
        token++
        screen.screenshotPng()
        screen.screenshotPng()
        assertEquals(1, probes)
        // 过了沿用的时限:再问
        now += AppAccessScreenPort.CHECK_TTL_MS + 1
        screen.checkAppAccess()
        assertEquals(2, probes)
    }

    @Test fun reusedCheckIsDroppedWhenTheListChanges() = runTest {
        var state = AppAccessState(blocked = setOf("other"), revision = 1)
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy { state }, packages = { listOf("bank") },
            windowToken = { 0L }, clock = { 0L })
        screen.checkAppAccess()
        state = AppAccessState(blocked = setOf("bank"), revision = 2)
        denied("app_blocked") { screen.checkAppAccess() }
    }

    @Test fun emptyListDoesNoExtraWorkOnAnyRead() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy.ALLOW,
            packages = { error("empty list must not query windows") }, windowToken = { error("empty list must not read the window count") })
        screen.snapshot()
        screen.peek()
        screen.screenshotPng()
        screen.tapXY(1, 1)
        screen.checkAppAccess()
    }

    private fun withWindows(vararg packages: String?) = ScreenSnapshot("s", packages.firstOrNull(), emptyList(), 1, 1,
        windows = packages.mapIndexed { i, p -> UiWindowTree(i, "application", i, null, p, i == 0, i == 0, emptyList()) })

    private fun blocked() = AppAccessPolicy { AppAccessState(blocked = setOf("bank")) }

    private suspend fun denied(code: String, body: suspend () -> Unit) {
        try {
            body()
            fail("expected $code")
        } catch (e: AppAccessDenied) {
            assertEquals(code, e.code)
        }
    }

    private companion object {
        /** 边界自己的成员。 */
        val OWN = setOf("getAppAccess", "checkAppAccess")

        /** 不碰任何 App 界面、原样转交的成员;按目标挑 App 的系统动作由端口自己看名单。加进来要说得出为什么。 */
        val PASS_THROUGH = setOf("systemAction", "systemCommand", "bulkCalendarInsert", "listApps", "otherProfiles", "appCopies", "getAfterTask", "getCanShareAudio")
    }
}
