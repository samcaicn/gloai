package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class UsageLedgerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val hour = UsageLedger.HOUR_MS
    private var clock = 1_000 * hour + 123
    private val file get() = File(tmp.root, "usage/usage.json")
    // 写出不靠后台定时:测试里直接调 flush
    private fun ledger() = UsageLedger(file, now = { clock }, flushDelayMs = 60_000)

    private val main = UsageSource("p1", "服务商甲", "m1", UsagePurpose.Main)
    private val light = UsageSource("p1", "服务商甲", "m1", UsagePurpose.Light)
    private val other = UsageSource("p2", "服务商乙", "m9", UsagePurpose.Vision)

    private fun UsageLedger.row(h: Long, s: UsageSource): UsageCounts? =
        snapshot().rows.firstOrNull { it.hour == h && it.key == UsageKey(s.providerId, s.model, s.purpose) }?.counts

    @Test fun sameHourAndKeyAddsUp() {
        val l = ledger()
        l.record(main, Usage(100, 10, 40))
        l.record(main, Usage(50, 5))
        l.record(main, null)
        assertEquals(UsageCounts(150, 15, 40, calls = 3, callsWithoutUsage = 1), l.row(1000, main))
    }

    @Test fun compatibilityRetriesCountAttemptsWithoutDuplicatingFinalUsage() {
        val l = ledger()
        l.record(main, Usage(100, 10, 40), httpAttempts = 2)
        l.record(main, null, httpAttempts = 0)
        assertEquals(UsageCounts(100, 10, 40, calls = 2, callsWithoutUsage = 1), l.row(1000, main))
    }

    @Test fun localFailureWithoutHttpDoesNotCreateAnEmptyUsageRow() {
        val l = ledger()
        l.record(main, null, httpAttempts = 0)
        assertTrue(l.snapshot().rows.isEmpty())
    }

    @Test fun differentPurposeHourOrProviderAreSeparateCells() {
        val l = ledger()
        l.record(main, Usage(1, 1))
        l.record(light, Usage(2, 2))
        l.record(other, Usage(3, 3))
        clock += hour
        l.record(main, Usage(4, 4))
        assertEquals(4, l.snapshot().rows.size)
        assertEquals(UsageCounts(2, 2, 0, 1, 0), l.row(1000, light))
        assertEquals(UsageCounts(4, 4, 0, 1, 0), l.row(1001, main))
        assertEquals(mapOf("p1" to "服务商甲", "p2" to "服务商乙"), l.snapshot().providerNames)
    }

    @Test fun providerBudgetTotalsAllModelsAndPurposesOnTheLocalCalendarDay() {
        val utc = ZoneId.of("UTC")
        clock = Instant.parse("2026-11-09T12:00:00Z").toEpochMilli()
        val l = UsageLedger(file, now = { clock }, zone = { utc })
        l.record(main, Usage(100, 20, cachedInputTokens = 60))
        l.record(light.copy(model = "m2"), Usage(50, 5))
        l.record(other, Usage(999, 999))
        assertEquals("同一家跨模型、跨用途相加，缓存输入不重复计", 175L, l.providerTokensToday("p1"))
        assertEquals(1_998L, l.providerTokensToday("p2"))
        assertEquals(0L, l.providerTokensToday("missing"))

        clock = Instant.parse("2026-11-09T23:59:59Z").toEpochMilli()
        assertEquals(175L, l.providerTokensToday("p1"))
        clock = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli()
        assertEquals("本地日期变更后重新计数", 0L, l.providerTokensToday("p1"))
        l.record(light, Usage(7, 3))
        assertEquals(10L, l.providerTokensToday("p1"))
    }

    @Test fun proactiveUsageIsLeftOutOfProviderBudgetAndSummedAcrossProvidersOnTheLocalDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        // 本地 2026-11-10 23:30
        clock = Instant.parse("2026-11-10T15:30:00Z").toEpochMilli()
        val l = UsageLedger(file, now = { clock }, zone = { zone }, flushDelayMs = 60_000)
        l.record(main, Usage(100, 20))
        l.record(main.copy(purpose = UsagePurpose.Proactive), Usage(40, 2))
        l.record(other.copy(purpose = UsagePurpose.Proactive), Usage(7, 1))
        assertEquals("主动能力不占服务商的每日预算", 120L, l.providerTokensToday("p1"))
        assertEquals(0L, l.providerTokensToday("p2"))
        assertEquals("各服务商合计", 50L, l.proactiveTokensToday())
        assertEquals("统计里照样算进去", 170L, l.snapshot().rows.sumOf { it.counts.input + it.counts.output })

        // 本地零点后重新计数
        clock = Instant.parse("2026-11-10T16:00:00Z").toEpochMilli()
        assertEquals(0L, l.proactiveTokensToday())
        l.record(main.copy(purpose = UsagePurpose.Proactive), Usage(5, 5))
        assertEquals(10L, l.proactiveTokensToday())
        assertEquals(0L, l.providerTokensToday("p1"))

        l.flush()
        val reopened = UsageLedger(file, now = { clock }, zone = { zone })
        assertEquals("用途写进文件、读回来还是主动能力", 10L, reopened.proactiveTokensToday())
        assertEquals(0L, reopened.providerTokensToday("p1"))
    }

    @Test fun proactivePurposeKeyRoundTripsAndUnknownKeysStayOther() {
        assertEquals(UsagePurpose.Proactive, UsagePurpose.of(UsagePurpose.Proactive.key))
        assertEquals("proactive", UsagePurpose.Proactive.key)
        assertEquals(UsagePurpose.Other, UsagePurpose.of("from-a-later-version"))
    }

    @Test fun versionMovesOnEveryRecord() {
        val l = ledger()
        val v = l.version.value
        l.record(main, null)
        l.record(main, null)
        assertEquals(v + 2, l.version.value)
    }

    @Test fun localMidnightSplitsAnHourForTheBudgetButNotForTheChart() {
        val zone = ZoneId.of("Asia/Kolkata")
        clock = Instant.parse("2026-11-10T18:25:00Z").toEpochMilli()
        val l = UsageLedger(file, now = { clock }, zone = { zone }, flushDelayMs = 60_000)
        l.record(main, Usage(100, 10))
        clock = Instant.parse("2026-11-10T18:40:00Z").toEpochMilli()
        assertEquals(0L, l.providerTokensToday("p1"))
        l.record(main, Usage(10_000, 10))
        assertEquals(10_010L, l.providerTokensToday("p1"))
        assertEquals(10_120L, l.snapshot().rows.single().counts.let { it.input + it.output })
        l.flush()
        val reopened = UsageLedger(file, now = { clock }, zone = { zone })
        assertEquals(10_010L, reopened.providerTokensToday("p1"))
        assertEquals(l.snapshot().rows, reopened.snapshot().rows)
    }

    @Test fun dailyBudgetFollowsTheTimeZoneChangedWhileRunning() {
        // 进程没退出时手机改了时区(系统会改进程的默认时区):「今天」和拆格都按改过之后的时区算,不按建账本时的
        val before = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            clock = Instant.parse("2026-11-10T15:30:00Z").toEpochMilli()
            val l = ledger()
            l.record(main, Usage(100, 10))
            clock = Instant.parse("2026-11-10T17:00:00Z").toEpochMilli()
            assertEquals("上海已过午夜", 0L, l.providerTokensToday("p1"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            assertEquals("加尔各答还是 11 月 10 日,15:30Z 那笔算今天", 110L, l.providerTokensToday("p1"))
            clock = Instant.parse("2026-11-10T18:25:00Z").toEpochMilli()
            l.record(main, Usage(1_000, 0))
            clock = Instant.parse("2026-11-10T18:40:00Z").toEpochMilli()
            l.record(main, Usage(5, 5))
            assertEquals("这个整点按加尔各答的午夜 18:30Z 拆开", 10L, l.providerTokensToday("p1"))
        } finally {
            TimeZone.setDefault(before)
        }
    }

    @Test fun persistsAcrossInstances() {
        val l = ledger()
        l.record(main, Usage(100, 10, 40))
        l.record(other, null)
        clock += hour
        l.record(light, Usage(7, 3))
        l.flush()
        val back = ledger()
        assertEquals(l.snapshot().rows.toSet(), back.snapshot().rows.toSet())
        assertEquals(l.snapshot().providerNames, back.snapshot().providerNames)
        assertTrue("临时文件不留下", !File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test fun flushWritesInBackgroundAfterTheDelay() {
        val l = UsageLedger(file, now = { clock }, flushDelayMs = 10)
        l.record(main, Usage(1, 2))
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.isFile && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(UsageCounts(1, 2, 0, 1, 0), UsageLedger(file, now = { clock }).row(1000, main))
    }

    @Test fun prunesOlderThanKeepDays() {
        val l = UsageLedger(file, now = { clock }, flushDelayMs = 60_000, keepDays = 2)
        l.record(main, Usage(1, 1))
        clock += 49 * hour
        l.record(main, Usage(2, 2))
        l.flush()
        assertEquals("写出时丢掉两天以前的", listOf(1049L), l.snapshot().rows.map { it.hour })
        clock += 49 * hour
        val back = UsageLedger(file, now = { clock }, keepDays = 2)
        assertTrue("读入时也丢", back.snapshot().rows.isEmpty())
    }

    @Test fun corruptFileStartsEmpty() {
        file.parentFile.mkdirs()
        file.writeText("{not json")
        val l = ledger()
        assertTrue(l.snapshot().rows.isEmpty())
        l.record(main, Usage(1, 1))
        l.flush()
        assertEquals(1, ledger().snapshot().rows.size)
    }

    @Test fun concurrentRecordsAreAllCounted() {
        val l = ledger()
        val threads = 8
        val each = 2_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(each) { i ->
                    l.record(if (t % 2 == 0) main else light, if (i % 4 == 0) null else Usage(10, 1, 2))
                    if (i % 500 == 0) l.flush()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        val rows = l.snapshot().rows
        assertEquals(threads * each.toLong(), rows.sumOf { it.counts.calls })
        assertEquals(threads * each / 4L, rows.sumOf { it.counts.callsWithoutUsage })
        assertEquals(threads * each * 3 / 4 * 10L, rows.sumOf { it.counts.input })
        l.flush()
        assertEquals(rows.toSet(), ledger().snapshot().rows.toSet())
    }
}
