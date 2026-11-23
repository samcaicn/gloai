package com.wangzan101.mika.core.cap.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import com.wangzan101.mika.core.screen.uistate.IdleScroll
import com.wangzan101.mika.core.screen.uistate.PageLimitReached
import com.wangzan101.mika.core.screen.uistate.PlacePace
import com.wangzan101.mika.core.screen.uistate.dwellStopped
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一页一页看的节奏:第一页不等、停够了不等、换网站不等,停留有上下限且随看过的页数变长。 */
class PagePacingTest {
    private val limits = PagePacing.Limits()

    @Test
    fun `first page on a site has no dwell`() {
        val p = PagePacing.human(Random(1))
        assertEquals(0, p.waitBefore("shop.example.com", 0))
        assertEquals(0, p.noteLoad("https://shop.example.com/", 0))
        assertEquals(0, p.waitBefore("shop.example.com", 10))
    }

    @Test
    fun `second page dwell is within the first range and counts elapsed time`() {
        repeat(50) { seed ->
            val p = PagePacing.human(Random(seed))
            p.noteLoad("https://shop.example.com/", 0)
            val dwell = p.noteLoad("https://shop.example.com/list?page=2", 1_000)
            assertTrue("$dwell", dwell in limits.firstDwellMs)
            assertEquals(dwell, p.waitBefore("shop.example.com", 1_000))
            // 模型自己想、读花掉的时间算在停留里
            assertEquals(dwell - 1_500, p.waitBefore("shop.example.com", 2_500))
            assertEquals(0, p.waitBefore("shop.example.com", 1_000 + dwell))
            assertEquals(0, p.waitBefore("shop.example.com", 100_000))
        }
    }

    @Test
    fun `dwell grows with pages seen and never passes the cap`() {
        val p = PagePacing.human(Random(7))
        var last = 0L
        var t = 0L
        for (n in 1..30) {
            val d = p.noteLoad("https://shop.example.com/p$n", t)
            if (n >= 2) {
                val extra = (n - 2).toLong()
                val lo = (limits.firstDwellMs.first + extra * limits.perPageMs.first).coerceAtMost(limits.maxDwellMs)
                assertTrue("page $n: $d", d in lo..limits.maxDwellMs)
            }
            last = d
            t += 15_000
        }
        assertEquals(limits.maxDwellMs, last)
        assertTrue(p.waitBefore("shop.example.com", t - 15_000) <= limits.maxDwellMs)
    }

    @Test
    fun `another site does not wait and subdomains are the same site`() {
        val p = PagePacing.human(Random(3))
        p.noteLoad("https://www.shop.example.com/", 0)
        p.noteLoad("https://m.shop.example.com/item/1", 100)
        // 同一个可注册域名:第二页
        assertTrue(p.waitBefore("example.com", 200) > 0)
        // 别的网站:不等;到了别的网站之后,回原网站的动作也不按原网站那一页等
        assertEquals(0, p.waitBefore("news.example.org", 200))
        assertEquals(0, p.noteLoad("https://news.example.org/", 300))
        assertEquals(0, p.waitBefore("shop.example.com", 400))
    }

    @Test
    fun `pages outside the window no longer count`() {
        val p = PagePacing.human(Random(5))
        p.noteLoad("https://shop.example.com/", 0)
        // 过了时间窗,再来又是这个网站的第一页
        assertEquals(0, p.noteLoad("https://shop.example.com/again", limits.windowMs + 1))
    }

    @Test
    fun `off never waits`() {
        val p = PagePacing.Off
        p.noteLoad("https://shop.example.com/", 0)
        p.noteLoad("https://shop.example.com/2", 1)
        assertEquals(0, p.waitBefore("shop.example.com", 2))
    }

    @Test
    fun `dwell waits only for the rest and only on url changes`() = runBlocking {
        var now = 0L
        val slept = mutableListOf<Long>()
        val waited = mutableListOf<Long>()
        val d = PageDwell(PagePacing.human(Random(11)), clock = { now }, sleep = { slept += it; now += it })
        d.onWait = { waited += it }
        d.seen("https://shop.example.com/")
        assertEquals(0, d.before())
        // 同一页(只差 #)不算新的一页
        d.seen("https://shop.example.com/#top")
        assertEquals(0, d.before())
        d.seen("https://shop.example.com/list?page=2")
        now += 1_000
        val w = d.before()
        assertTrue("$w", w in (limits.firstDwellMs.first - 1_000)..(limits.firstDwellMs.last - 1_000))
        assertEquals(listOf(w), slept)
        assertEquals(listOf(w), waited)
        // 停够了:下一个动作不再等
        assertEquals(0, d.before())
        // 加载了更多:又是一页
        d.loadedMore()
        assertTrue(d.before() > 0)
        // 打开别的网站不等
        d.seen("https://shop.example.com/list?page=3")
        assertEquals(0, d.before("https://news.example.org/"))
    }

    @Test
    fun `careful sites dwell longer within their own cap`() {
        val c = PagePacing.Limits.CAREFUL
        repeat(30) { seed ->
            val p = PagePacing.human(Random(seed))
            var t = 0L
            for (n in 1..20) {
                val d = p.noteLoad("https://jobs.example.com/p$n", t, PlacePace.CAREFUL)
                if (n == 2) assertTrue("$d", d in c.firstDwellMs)
                if (n >= 2) assertTrue("page $n: $d", d in c.firstDwellMs.first..c.maxDwellMs)
                t += 40_000
            }
        }
        // 普通网站的范围不变
        val p = PagePacing.human(Random(2))
        p.noteLoad("https://shop.example.com/", 0)
        assertTrue(p.noteLoad("https://shop.example.com/2", 1) in limits.firstDwellMs)
        assertTrue(c.firstDwellMs.first > limits.firstDwellMs.last && c.maxDwellMs > limits.maxDwellMs)
    }

    /** 设了上限的节奏:普通网站 [CAP] 页,限制浏览快慢的网站 [CAREFUL_CAP] 页。 */
    private fun capped(seed: Int) = PagePacing.human(
        Random(seed), limits = PagePacing.Limits(pageCap = CAP), careful = PagePacing.Limits.CAREFUL.copy(pageCap = CAREFUL_CAP),
    )

    @Test
    fun `no page cap by default so reading tasks and unattended runs can finish`() {
        assertEquals(null, limits.pageCap)
        assertEquals(null, PagePacing.Limits.CAREFUL.pageCap)
        val p = PagePacing.human(Random(3))
        repeat(200) { p.noteLoad("https://jobs.example.com/p$it", it * 1_000L, PlacePace.CAREFUL) }
        assertFalse(p.capReached("jobs.example.com", PlacePace.CAREFUL))
        assertFalse(p.capReached("jobs.example.com"))
    }

    @Test
    fun `page cap counts pages per site in the task and resets when the user answers`() {
        val p = capped(4)
        repeat(CAP - 1) { p.noteLoad("https://shop.example.com/p$it", it * 60_000L) }
        assertFalse(p.capReached("shop.example.com"))
        p.noteLoad("https://www.shop.example.com/last", 3_000_000)
        // 过了时间窗的页也算:上限按整个任务数
        assertTrue(p.capReached("m.shop.example.com"))
        assertEquals(CAP, p.pagesOn("shop.example.com"))
        // 别的网站另算
        assertFalse(p.capReached("news.example.org"))
        p.userAnswered()
        assertFalse(p.capReached("shop.example.com"))
        // 限制浏览快慢的网站上限更低
        repeat(CAREFUL_CAP) { p.noteLoad("https://jobs.example.com/p$it", it.toLong(), PlacePace.CAREFUL) }
        assertTrue(p.capReached("jobs.example.com", PlacePace.CAREFUL))
        // 不设节奏时也不设上限
        repeat(50) { PagePacing.Off.noteLoad("https://shop.example.com/p$it", 0) }
        assertFalse(PagePacing.Off.capReached("shop.example.com"))
    }

    @Test
    fun `page cap is lifted for a site once an outward action was done there`() {
        val p = capped(5)
        repeat(CAP) { p.noteLoad("https://jobs.example.com/p$it", it * 1_000L) }
        repeat(CAP) { p.noteLoad("https://shop.example.org/p$it", it * 1_000L) }
        assertTrue(p.capReached("jobs.example.com"))
        p.outwardDone("www.jobs.example.com")
        // 做过对外动作:这个网站(同一可注册域名)不再设上限,停留照旧
        assertFalse(p.capReached("m.jobs.example.com"))
        val d = p.noteLoad("https://jobs.example.com/more", 100_000)
        assertTrue("$d", d > 0)
        assertEquals(d, p.waitBefore("jobs.example.com", 100_000))
        // 用户答话后照样不设;别的网站不受影响
        p.userAnswered()
        repeat(CAP * 2) { p.noteLoad("https://jobs.example.com/x$it", 200_000L + it) }
        assertFalse(p.capReached("jobs.example.com"))
        repeat(CAP) { p.noteLoad("https://shop.example.org/y$it", 300_000L + it) }
        assertTrue(p.capReached("shop.example.org"))
    }

    @Test
    fun `the outward action itself passes the cap and only a done one lifts it`() = runBlocking {
        var now = 0L
        val d = PageDwell(PagePacing.human(Random(9), limits = PagePacing.Limits(pageCap = 2)), clock = { now }, sleep = { now += it })
        for (i in 1..2) { d.seen("https://jobs.example.com/p$i"); now += 60_000 }
        assertTrue(runCatching { d.before() }.exceptionOrNull() is PageLimitReached)
        // 正在做的对外动作不拦;没做成的不解除上限
        d.outwardStart("jobs.example.com")
        d.before()
        d.outwardEnd("jobs.example.com", done = false)
        assertTrue(runCatching { d.before() }.exceptionOrNull() is PageLimitReached)
        d.outwardStart("jobs.example.com")
        d.outwardEnd("jobs.example.com", done = true)
        d.seen("https://jobs.example.com/p3")
        // 解除之后照样补足停留
        assertTrue(d.before() > 0)
    }

    @Test
    fun `dwell refuses page changes past the cap but still scrolls`() = runBlocking {
        var now = 0L
        val d = PageDwell(PagePacing.human(Random(9), limits = PagePacing.Limits(pageCap = 3)), clock = { now }, sleep = { now += it })
        for (i in 1..3) { d.seen("https://shop.example.com/p$i"); now += 60_000; d.before(gate = false) }
        val e = runCatching { d.before() }.exceptionOrNull()
        assertTrue("$e", e is PageLimitReached && e.pages == 3 && e.cap == 3 && e.site == "shop.example.com")
        val result = dwellStopped(e as PageLimitReached).content
        assertEquals(JsonPrimitive("page_limit"), result["error"])
        assertTrue(result["message"].toString().contains("Mika reached its per-task web page budget"))
        val budget = result["navigationBudget"] as JsonObject
        assertEquals(JsonPrimitive("web_pages"), budget["scope"])
        assertEquals(JsonPrimitive("shop.example.com"), budget["target"])
        assertEquals(JsonPrimitive(3), budget["used"])
        assertEquals(JsonPrimitive(3), budget["limit"])
        assertTrue(runCatching { d.before("https://shop.example.com/p4") }.exceptionOrNull() is PageLimitReached)
        // 往下滚(不看上限)和别的网站照常
        assertEquals(0, d.before(gate = false))
        assertEquals(0, d.before("https://news.example.org/"))
        // 用户答过话:接着看
        d.userAnswered()
        assertEquals(0, d.before())
    }

    @Test
    fun `site pace comes from the lookup by host`() = runBlocking {
        var now = 0L
        val asked = mutableListOf<String>()
        val d = PageDwell(PagePacing.human(Random(6)), pace = { asked += it; if (it.endsWith("jobs.example.com")) PlacePace.CAREFUL else PlacePace.NORMAL },
            clock = { now }, sleep = { now += it })
        d.seen("https://www.jobs.example.com/a")
        d.seen("https://www.jobs.example.com/b")
        val w = d.before()
        assertTrue("$w", w in PagePacing.Limits.CAREFUL.firstDwellMs)
        // 同一个主机只问一次
        assertEquals(listOf("jobs.example.com"), asked)
    }

    @Test
    fun `idle scrolls happen inside a long dwell only when a port is given`() = runBlocking {
        var now = 0L
        val slept = mutableListOf<Long>()
        val port = FakeWebPort()
        var idled = 0
        val d = PageDwell(PagePacing.human(Random(12)), idle = IdleScroll(Random(3)), clock = { now }, sleep = { slept += it; now += it })
        d.onIdleScroll = { idled += it }
        d.seen("https://shop.example.com/")
        d.seen("https://shop.example.com/2")
        val w = d.before(port = port)
        // 停够的总时长不变,滚了 1–3 下
        assertEquals(w, slept.sum())
        val scrolls = port.calls.filter { it.startsWith("idle ") }
        assertTrue("$scrolls", scrolls.size in 1..3)
        assertEquals(scrolls.size, idled)
        // 按坐标做的动作(不给端口)不滚
        d.seen("https://shop.example.com/3")
        port.calls.clear()
        d.before()
        assertTrue(port.calls.isEmpty())
        // 端口说这会儿不便滚:滚一下都不成就不再试,照样停够
        d.seen("https://shop.example.com/4")
        port.calls.clear(); slept.clear(); idled = 0
        port.idleOk = false
        val w2 = d.before(port = port)
        assertEquals(w2, slept.sum())
        assertTrue(port.calls.size <= 1)
        assertEquals(0, idled)
    }

    private companion object {
        const val CAP = 20
        const val CAREFUL_CAP = 12
    }
}
