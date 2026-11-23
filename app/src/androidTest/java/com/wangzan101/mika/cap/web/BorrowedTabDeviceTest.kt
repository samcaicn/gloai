package com.wangzan101.mika.cap.web

import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/**
 * 真机测试:借来的标签(用户在浏览器页上看着的那一个)当任务的当前标签用。不经任务,直接拿标签池和网页端口:
 * 「用户的标签」挂在另一块网页显示上(代替浏览器页的界面),借进任务的池,网页工具在它上面读、点;
 * 借来的不挂到任务的显示上、不算上限、打开网址另开新标签;还回去和任务结束都不销毁它;用户关页面时任务收下它接着用。
 * 不需要内置助手,不碰主屏。
 */
@RunWith(AndroidJUnit4::class)
class BorrowedTabDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var host: WebHost
    private lateinit var userHost: WebHost
    private lateinit var pool: WebTabPool
    private lateinit var userTab: WebTab
    private val task = "task-borrow"

    private fun fixture(name: String) = "https://appassets.androidplatform.net/assets/web-fixtures/$name"

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val dpi = ctx.resources.displayMetrics.densityDpi
        instr.runOnMainSync {
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            val loaders = listOf(WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).build())
            host = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            userHost = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            pool = WebTabPool(ctx, host, perTask = 2, budget = TabBudget(), assetLoaders = loaders)
            // 用户的标签:不归池管,挂在自己的那块显示上(浏览器页的界面在真机上是 Activity 的窗口)
            userTab = WebTab(ctx, "browser1", "user-browser", loaders)
            userTab.devtools.setHandOperated(userTab)
            userHost.attach(userTab, desktop = false)
        }
        runBlocking { assertTrue(userTab.load(fixture("inputs.html"))) }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            pool.close()
            if (!userTab.destroyed) userTab.destroy()
            host.close()
            userHost.close()
        }
    }

    private fun screen() = WebScreen(pool, task, host, SystemClock::uptimeMillis)
    private fun main(block: () -> Unit) = instr.runOnMainSync(block)

    private suspend fun js(tab: WebTab, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun ScreenSnapshot.ref(resourceId: String): String =
        elements.firstOrNull { it.resourceId == resourceId }?.ref ?: error("$resourceId not in ${elements.map { it.resourceId }}")

    @Test
    fun webToolsActOnTheBorrowedTabWhereItIs() = runBlocking<Unit> {
        var attached = false
        main { attached = pool.attachExternal(task, userTab) }
        assertTrue(attached)
        main {
            assertSame(userTab, pool.current(task))
            assertTrue(pool.isExternal(userTab.id))
            // 不挂到任务的网页显示上,不算上限
            assertSame(userHost, (userTab.view.parent as android.view.View).tag)
            assertEquals(0, pool.tabCount())
        }
        val s = screen()
        val snap = s.snapshot()
        assertTrue(snap.snapshotId.startsWith("wbrowser1-"))
        val r = s.tapRef(snap.ref("#counter"), snap.snapshotId)
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals("1", js(userTab, "window.clicks"))
        assertEquals("true", js(userTab, "window.lastTrusted"))
        // 截图画的是这个视图本身
        assertNotNull(s.screenshotPng())
        // 打开网址不换走用户的这一页:另开新标签
        val nav = s.navigate(fixture("elements.html"), newTab = false)
        assertTrue(nav.newTab)
        main {
            assertNotSame(userTab, pool.current(task))
            assertTrue(userTab.view.url!!.endsWith("inputs.html"))
            assertEquals(1, pool.tabCount())
        }
        // 切回来照样能用;任务结束时还回去,不销毁
        main { assertSame(userTab, pool.switchTo(task, userTab.id)) }
        main { pool.closeAll(task) }
        main {
            assertFalse(userTab.destroyed)
            assertFalse(pool.isExternal(userTab.id))
            assertSame(userHost, (userTab.view.parent as android.view.View).tag)
        }
    }

    @Test
    fun detachReturnsAndAdoptKeepsThePage() = runBlocking<Unit> {
        main { assertTrue(pool.attachExternal(task, userTab)) }
        // 借进来两次只是切过去
        main { assertTrue(pool.attachExternal(task, userTab)) }
        // 别的任务借不走同一个
        main { assertFalse(pool.attachExternal("other", userTab)) }
        main {
            assertTrue(pool.detachExternal(userTab.id))
            assertEquals(null, pool.current(task))
            assertFalse(userTab.destroyed)
            assertFalse(pool.detachExternal(userTab.id))
        }
        // 用户关掉页面:任务收下它当普通标签,挂到任务的显示上,页面状态还在
        js(userTab, "document.getElementById('counter').click()")
        main { assertTrue(pool.attachExternal(task, userTab)) }
        main {
            assertTrue(pool.detachExternal(userTab.id))
            assertTrue(pool.adopt(task, userTab, current = true))
            assertSame(userTab, pool.current(task))
            assertFalse(pool.isExternal(userTab.id))
            assertEquals(task, userTab.ownerTaskId)
            assertSame(host, (userTab.view.parent as android.view.View).tag)
            assertEquals(1, pool.tabCount())
        }
        assertEquals("1", js(userTab, "window.clicks"))
        // 收下的随任务结束一起关
        main { pool.closeAll(task) }
        main { assertTrue(userTab.destroyed) }
    }
}
