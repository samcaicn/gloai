package com.wangzan101.mika.task

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.screen.uistate.AppWait
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ResourceSchedulerTest {
    @Test fun simultaneousAppClaimsHaveExactlyOneOwnerAndReleaseFreesIt() {
        val resources = ResourceScheduler()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val wins = AtomicInteger()
        for (owner in listOf("a", "b")) Thread {
            start.await()
            if (resources.reserveApp("com.example", owner, { true }, { true })) wins.incrementAndGet()
            done.countDown()
        }.start()
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, wins.get())
        val first = resources.appHolder("com.example")!!
        resources.releaseOwner(first)
        assertTrue(resources.reserveApp("com.example", if (first == "a") "b" else "a", { true }, { true }))
    }

    @Test fun clipboardLeaseSerializesTwoTasks() {
        val resources = ResourceScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val secondEntered = AtomicBoolean(false)
        val first = Thread {
            resources.clipboard.lock()
            try { entered.countDown(); release.await() } finally { resources.clipboard.unlock() }
        }
        val second = Thread {
            resources.clipboard.lock()
            try { secondEntered.set(true) } finally { resources.clipboard.unlock(); secondFinished.countDown() }
        }
        first.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        second.start()
        assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))
        assertFalse(secondEntered.get())
        release.countDown()
        assertTrue(secondFinished.await(5, TimeUnit.SECONDS))
        first.join(5_000)
    }

    // ---------- 等别的任务放开 App ----------

    private val active: (String) -> Boolean = { true }

    /** 「登记里没人占」就算放开(测试里不看别的屏)。 */
    private fun ResourceScheduler.freeFor(pkg: String, owner: String): suspend () -> Boolean = { appFree(pkg, owner, active, { true }) }

    /** 占着的任务一放开,等着的马上醒来(不用等到下一次轮询)。 */
    @Test fun releaseWakesTheWaiter() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "a", active, { true }))
        val waiter = async { r.awaitApp("com.chat", "b", 60_000, active, r.freeFor("com.chat", "b"), pollMs = 30_000) }
        advanceTimeBy(5_000)
        assertFalse(waiter.isCompleted)
        r.releaseOwner("a")
        runCurrent()
        assertEquals(AppWait.Free, waiter.await())
        assertTrue("醒来远早于下一次轮询", currentTime < 30_000)
        assertTrue(r.reserveApp("com.chat", "b", active, { true }))
    }

    /** 到时限仍被占着:如实说等不到,也不再算在等。 */
    @Test fun waitingTimesOut() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "a", active, { true }))
        assertEquals(AppWait.TimedOut, r.awaitApp("com.chat", "b", 3_000, active, r.freeFor("com.chat", "b")))
        assertEquals(3_000L, currentTime)
        // b 不再登记为在等:a 现在去等 b 手里的 App 不会被当成互相等
        assertTrue(r.reserveApp("com.mail", "b", active, { true }))
        assertEquals(AppWait.TimedOut, r.awaitApp("com.mail", "a", 1_000, active, r.freeFor("com.mail", "a")))
    }

    /** a 占着聊天、在等 b 手里的邮件;b 再去等聊天就是互相等:b 当场不等,a 照样等着。 */
    @Test fun mutualWaitIsRefusedAtOnce() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "a", active, { true }))
        assertTrue(r.reserveApp("com.mail", "b", active, { true }))
        val a = async { r.awaitApp("com.mail", "a", 60_000, active, r.freeFor("com.mail", "a")) }
        runCurrent()
        assertEquals(AppWait.WouldDeadlock, r.awaitApp("com.chat", "b", 60_000, active, r.freeFor("com.chat", "b")))
        assertEquals("没等:时间没走", 0L, currentTime)
        // b 不等、做完收尾放掉邮件,a 就等到了
        r.releaseOwner("b")
        runCurrent()
        assertEquals(AppWait.Free, a.await())
    }

    /** 三个任务绕成一圈(a 等 b、b 等 c、c 再等 a)同样认得出;不成圈的照常等。 */
    @Test fun longerCyclesAreFoundAndChainsWithoutCycleWait() = runTest {
        val r = ResourceScheduler()
        for ((pkg, owner) in listOf("p.a" to "a", "p.b" to "b", "p.c" to "c")) assertTrue(r.reserveApp(pkg, owner, active, { true }))
        val a = async { r.awaitApp("p.b", "a", 60_000, active, r.freeFor("p.b", "a")) }
        val b = async { r.awaitApp("p.c", "b", 60_000, active, r.freeFor("p.c", "b")) }
        runCurrent()
        assertEquals(AppWait.WouldDeadlock, r.awaitApp("p.a", "c", 60_000, active, r.freeFor("p.a", "c")))
        // d 不占东西,等 a 手里的不成圈
        val d = async { r.awaitApp("p.a", "d", 2_000, active, r.freeFor("p.a", "d")) }
        advanceTimeBy(2_001)
        assertEquals(AppWait.TimedOut, d.await())
        a.cancel(); b.cancel()
    }

    /** 占着的任务已经不在了(没来得及放开):不算占着,也不算互相等。 */
    @Test fun goneHolderDoesNotBlockOrDeadlock() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "gone", active, { true }))
        val alive: (String) -> Boolean = { it != "gone" }
        assertEquals(AppWait.Free, r.awaitApp("com.chat", "b", 1_000, alive, { r.appFree("com.chat", "b", alive, { true }) }))
    }
}

