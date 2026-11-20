package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.SystemAction
import com.wangzan101.mika.core.uistate.SystemActionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** 没有内置助手/无障碍时:不用手机的步骤照常,用到手机才报 phone_unavailable(带怎么弄好),之后还能再试。 */
class LazyPhoneScreenTest {
    private var attempts = 0
    private var available = false
    private val opened = mutableListOf<String>()

    private val real: ScreenPort = Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, m, args ->
        if (m.name == "openApp") { opened += args[0] as String; ActionResult.Ok() } else throw UnsupportedOperationException(m.name)
    } as ScreenPort

    private val screen = LazyPhoneScreen(1080, 2400, listApps = { listOf("a.b" to "A") }) {
        attempts++
        if (!available) throw PhoneUnavailable("手机操作助手没在运行")
        real
    }

    @Test fun blockedAppDoesNotConnectHelper() = runTest {
        val lazy = LazyPhoneScreen(1080, 2400, listApps = { emptyList() }) { attempts++; real }
        val guarded = com.wangzan101.mika.core.uistate.AppAccessScreenPort(lazy,
            com.wangzan101.mika.core.access.AppAccessPolicy {
                com.wangzan101.mika.core.access.AppAccessState(blocked = setOf("bank"))
            }, packages = { emptyList() })
        try {
            guarded.openApp("bank")
            org.junit.Assert.fail("blocked app must be refused")
        } catch (e: com.wangzan101.mika.core.access.AppAccessDenied) {
            assertEquals("app_blocked", e.code)
        }
        assertEquals(0, attempts)
    }

    @Test fun readsAndAppListWorkWithoutConnecting() = runTest {
        assertFalse(screen.snapshot().hasWindow)
        assertEquals(listOf("a.b" to "A"), screen.listApps())
        assertEquals(ActionResult.Failed("no_app_open"), screen.tapXY(1, 1))
        assertEquals(0, attempts)
        assertFalse(screen.acquired)
    }

    @Test fun launchingAnAppConnectsOrSaysWhy() = runTest {
        assertEquals(ActionResult.Failed(LazyPhoneScreen.PHONE_UNAVAILABLE, "手机操作助手没在运行"), screen.openApp("a.b"))
        assertFalse(screen.acquired)
        available = true
        assertTrue(screen.openApp("a.b") is ActionResult.Ok)
        assertTrue(screen.acquired)
        assertEquals(listOf("a.b"), opened)
        assertEquals(2, attempts)
    }

    @Test fun audioCapabilityDoesNotConnectThePhone() {
        var builds = 0
        val lazy = LazyPhoneScreen(1080, 2400, listApps = { emptyList() }, canShareAudio = true) {
            builds++
            error("checking the capability must stay lazy")
        }
        assertTrue(lazy.canShareAudio)
        assertEquals(0, builds)
    }

    @Test fun shareAudioIsForwardedAfterLazyBuild() = runTest {
        val calls = mutableListOf<String>()
        val inner = Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, method, _ ->
            calls += method.name
            if (method.name == "shareAudio") ActionResult.Ok() else null
        } as ScreenPort
        var builds = 0
        val lazy = LazyPhoneScreen(1080, 2400, listApps = { emptyList() }, canShareAudio = true) {
            builds++
            inner
        }

        assertEquals(ActionResult.Ok(), lazy.shareAudio("com.example.app", "/workspace/out/a.wav", null))
        assertEquals(1, builds)
        assertEquals(listOf("shareAudio"), calls)
    }

    @Test fun bulkCalendarInsertUsesInjectedWriterWithoutBuildingThePhone() = runTest {
        var builds = 0
        var received: List<SystemAction.AddCalendarEvent>? = null
        val expected = SystemActionResult(ActionResult.Ok(), eventUris = listOf("content://calendar/events/1"))
        val lazy = LazyPhoneScreen(
            1080,
            2400,
            listApps = { emptyList() },
            calendarBulkWriter = { events ->
                received = events
                expected
            },
        ) {
            builds++
            error("calendar insertion must not build the phone")
        }

        val events = listOf(SystemAction.AddCalendarEvent("title", 1L))
        assertEquals(expected, lazy.bulkCalendarInsert(events))
        assertEquals(events, received)
        assertEquals(0, builds)
    }

    @Test fun bulkCalendarInsertFallsBackToBuiltPort() = runTest {
        val calls = mutableListOf<String>()
        val expected = SystemActionResult(ActionResult.Ok(), eventUris = listOf("content://calendar/events/2"))
        val inner = Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, method, _ ->
            calls += method.name
            if (method.name == "bulkCalendarInsert") expected else null
        } as ScreenPort
        var builds = 0
        val lazy = LazyPhoneScreen(1080, 2400, listApps = { emptyList() }) {
            builds++
            inner
        }

        assertEquals(expected, lazy.bulkCalendarInsert(listOf(SystemAction.AddCalendarEvent("title", 1L))))
        assertEquals(1, builds)
        assertEquals(listOf("bulkCalendarInsert"), calls)
    }
}
