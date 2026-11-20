package com.wangzan101.mika.core.proactive

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriagePlannerTest {
    private val day = 86_400_000L
    private val t0 = 100 * day + 3_600_000L

    private fun rec(key: String, pkg: String, posted: Long = t0) = NoticeRecord(key, pkg, posted, "t", "x")

    private fun snap(vararg r: NoticeRecord) = NoticeInbox.Snapshot(r.toList(), 0, 0, 0)

    private fun planner() = TriagePlanner(zone = { ZoneOffset.UTC })

    private val send = { pkg: String -> TriagePlan.Send(pkg) }

    // ---- decide:纯函数 ----

    @Test fun lockedNeverSendsNorWaits() {
        val p = mapOf("a" to PendingApp(0, 8))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Locked), TriagePlanner.decide(false, false, true, p, 1_000_000))
    }

    @Test fun busyWaitsBudgetStopsNothingIsIdle() {
        val p = mapOf("a" to PendingApp(0, 8))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Busy), TriagePlanner.decide(true, true, true, p, 1_000_000))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.BudgetUsedUp), TriagePlanner.decide(true, false, false, p, 1_000_000))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Nothing), TriagePlanner.decide(true, false, true, emptyMap(), 1_000_000))
    }

    @Test fun batchesAfterTheWindowOrWhenFull() {
        val now = 1_000_000L
        val young = mapOf("a" to PendingApp(now - 5_000, 3), "b" to PendingApp(now - 1_000, 1))
        assertEquals(TriagePlan.WaitUntil(now - 5_000 + TriagePlanner.WINDOW_MS), TriagePlanner.decide(true, false, true, young, now))
        val full = young + ("b" to PendingApp(now - 1_000, 8))
        assertEquals(send("b"), TriagePlanner.decide(true, false, true, full, now))
        val old = mapOf("a" to PendingApp(now - 30_000, 1), "b" to PendingApp(now - 40_000, 1))
        assertEquals("先到点的先发", send("b"), TriagePlanner.decide(true, false, true, old, now))
    }

    // ---- 状态 ----

    @Test fun batchesArePerAppAndAtMostEight() {
        val p = planner()
        val recs = (1..10).map { rec("a$it", "com.a", t0 + it) } + (1..3).map { rec("b$it", "com.b", t0 + it) }
        p.sync(snap(*recs.toTypedArray()), t0)
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, t0))
        val batch = p.begin("com.a")
        assertEquals(8, batch.size)
        assertTrue(batch.all { it.record.packageName == "com.a" })
        assertEquals((1..8).map { "a$it" }, batch.map { it.record.key })
        assertEquals(TriagePlan.Idle(TriagePlan.Why.InFlight), p.plan(true, false, true, t0))
        assertEquals(TriageCounts(pending = 5, sending = 8), p.counts())
    }

    @Test fun surfaceIsCappedPerAppPerDay() {
        assertEquals(10, TriagePlanner.SURFACE_CAP_PER_APP)
        val p = planner()
        val a = (1..11).map { rec("a$it", "com.a", t0 + it) }
        val b = listOf(rec("b1", "com.b", t0))
        p.sync(snap(*(a + b).toTypedArray()), t0)
        val surface = TriageVerdict(TriageDecision.Surface, "r")
        // 8 条一批,再 3 条一批
        repeat(2) { p.begin("com.a").let { batch -> p.finish(batch.associate { it.record.key to surface }, t0) } }
        val decisions = a.map { (p.mark(it) as TriageMark.Done).verdict }
        assertEquals(10, decisions.count { it.decision == TriageDecision.Surface })
        assertEquals(TriageVerdict(TriageDecision.Ignore, note = TriageNote.SurfaceCapped), decisions.last())
        // 别的 App 不受影响
        p.begin("com.b").let { batch -> p.finish(batch.associate { it.record.key to surface }, t0) }
        assertEquals(surface, (p.mark(b[0]) as TriageMark.Done).verdict)
        // 第二天重新算
        val next = rec("a99", "com.a", t0 + day)
        p.sync(snap(*(a + b + next).toTypedArray()), t0 + day)
        p.begin("com.a").let { batch -> p.finish(batch.associate { it.record.key to surface }, t0 + day) }
        assertEquals(surface, (p.mark(next) as TriageMark.Done).verdict)
    }

    // 上限按会话(通知键)算,不按判定的次数:同一个键冷却后又判成值得提醒,不再占一个名额
    @Test fun surfaceCapCountsDistinctKeysNotVerdicts() {
        val p = planner()
        val chat = rec("chat", "com.a", t0)
        var at = t0
        repeat(12) { i ->
            judge(p, chat.copy(postedAtMs = at), surface, at)
            assertEquals("第 ${i + 1} 次", surface, (p.mark(chat.copy(postedAtMs = at)) as TriageMark.Done).verdict)
            at += TriagePlanner.SURFACE_COOLDOWN_MS + 1
        }
        // 另外 9 个会话还有名额(这个键只占了 1 个),第 10 个没有
        val others = (1..10).map { rec("o$it", "com.a", at + it) }
        // judge 每次只留这一条在收件箱里:结果当场取
        val verdicts = others.map { judge(p, it, surface, at); (p.mark(it) as TriageMark.Done).verdict }
        assertEquals(9, verdicts.count { it == surface })
        assertEquals(TriageNote.SurfaceCapped, verdicts.last().note)
        // 已经算过的键到了上限也照样能提醒
        judge(p, chat.copy(postedAtMs = at + 100), surface, at + 100)
        assertEquals(surface, (p.mark(chat.copy(postedAtMs = at + 100)) as TriageMark.Done).verdict)
    }

    // 从收件箱里没了(用户处理掉、结果被清掉)又出现的同一个键,当天不再占第二个名额;计数熬过结果清理,过了午夜重来
    @Test fun aKeyThatComesBackTheSameDayKeepsItsOneSlot() {
        val p = planner()
        val keys = (1..10).map { rec("k$it", "com.a", t0 + it) }
        keys.forEach { judge(p, it, surface, t0) }
        p.sync(snap(), t0 + 1)
        assertTrue(p.marks().isEmpty())
        // 回来的 k1 还是它那个名额;新的键没有名额了
        val back = keys.first().copy(postedAtMs = t0 + 1_000)
        judge(p, back, surface, t0 + 1_000)
        assertEquals(surface, (p.mark(back) as TriageMark.Done).verdict)
        val fresh = rec("new", "com.a", t0 + 2_000)
        judge(p, fresh, surface, t0 + 2_000)
        assertEquals(TriageNote.SurfaceCapped, (p.mark(fresh) as TriageMark.Done).verdict.note)
        // clear()(关掉判定再打开)也不重置当天的计数
        p.clear()
        val fresh2 = rec("new2", "com.a", t0 + 3_000)
        judge(p, fresh2, surface, t0 + 3_000)
        assertEquals(TriageNote.SurfaceCapped, (p.mark(fresh2) as TriageMark.Done).verdict.note)
        // 本地时间过了午夜:重新算
        val tomorrow = (t0 / day + 1) * day
        val fresh3 = rec("new3", "com.a", tomorrow)
        judge(p, fresh3, surface, tomorrow)
        assertEquals(surface, (p.mark(fresh3) as TriageMark.Done).verdict)
    }

    @Test fun abortedBatchIsPendingAgainNotFailed() {
        val p = planner()
        val r = rec("a1", "com.a")
        p.sync(snap(r), t0)
        p.begin("com.a")
        assertTrue(p.pending().isEmpty())
        // 请求期间收件箱有变化(锁屏前后照常 sync)
        p.sync(snap(r), t0 + 1_000)
        p.abort()
        assertEquals(listOf(r), p.pending())
        assertNull(p.mark(r))
        assertEquals(TriageCounts(pending = 1), p.counts())
        // 解锁后不用再等:它的计时起点还在
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, t0 + TriagePlanner.WINDOW_MS))
    }

    @Test fun failedStaysFailedUntilRetriedOnce() {
        val p = planner()
        val r = rec("a1", "com.a")
        p.sync(snap(r), t0)
        p.begin("com.a")
        p.fail(t0)
        assertTrue(p.mark(r) is TriageMark.Failed)
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Nothing), p.plan(true, false, true, t0 + 3_600_000))
        assertEquals(TriageCounts(failed = 1), p.counts())
        val later = t0 + 3_600_000
        assertEquals(1, p.retryFailed(later))
        p.sync(snap(r), later)
        assertEquals("再试一次不用再等凑批", TriagePlan.Send("com.a"), p.plan(true, false, true, later))
        p.begin("com.a")
        p.fail(t0)
        assertTrue("再失败就又是失败,不会自己再发", p.mark(r) is TriageMark.Failed)
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Nothing), p.plan(true, false, true, later + day / 2))
    }

    @Test fun updateDuringTheRequestKeepsTheVerdictAndCoolsDown() {
        val p = planner()
        val v1 = rec("k", "com.a", t0)
        p.sync(snap(v1), t0)
        p.begin("com.a")
        // 请求期间同一条通知更新了:结果记在发出去的那次上,新内容先冷却,不马上再判
        val v2 = v1.copy(postedAtMs = t0 + 5)
        p.sync(snap(v2), t0 + 5)
        p.finish(mapOf("k" to remember), t0 + 6)
        p.sync(snap(v2), t0 + 6)
        assertNull(p.mark(v2))
        assertEquals(TriageMark.Done(t0, remember), p.marks()["k"])
        assertTrue(p.pending().isEmpty())
        assertEquals(listOf(v2), p.cooling())
        assertEquals(TriageCounts(cooling = 1), p.counts())
        assertEquals(TriagePlan.WaitUntil(t0 + 6 + min(2)), p.plan(true, false, true, t0 + 6))
        // 收件箱里没了:结果和冷却都不留
        p.sync(snap(), t0 + 10)
        assertTrue(p.marks().isEmpty())
        assertEquals(TriageCounts(), p.counts())
    }

    @Test fun surfaceForANoticeUpdatedDuringTheRequestIsJudgedAgainSooner() {
        val p = planner()
        val v1 = rec("k", "com.a", t0)
        p.sync(snap(v1), t0)
        p.begin("com.a")
        val v2 = v1.copy(postedAtMs = t0 + 5)
        p.sync(snap(v2), t0 + 5)
        p.finish(mapOf("k" to surface), t0 + 6)
        p.sync(snap(v2), t0 + 6)
        // 结果记在发出去的那次上,对不上新的发出时间:提醒出不来
        assertEquals(TriageMark.Done(t0, surface), p.marks()["k"])
        assertEquals(0, NoticeHints().apply { sync(listOf(v2), p.marks(), triageOn = true) }.state.value.unseen)
        // 所以不按「已经标出来了」冷却 30 分钟,和忽略、记下一样 2 分钟后再判新内容
        val at = t0 + 6 + min(2)
        assertEquals(TriagePlan.WaitUntil(at), p.plan(true, false, true, t0 + 6))
        assertEquals(TriagePlan.WaitUntil(at + W), p.plan(true, false, true, at))
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, at + W))
        assertEquals(listOf("k"), p.begin("com.a").map { it.record.key })
        p.finish(mapOf("k" to surface), at + W)
        p.sync(snap(v2), at + W)
        assertEquals(1, NoticeHints().apply { sync(listOf(v2), p.marks(), triageOn = true) }.state.value.unseen)
    }

    // ---- 冷却 ----

    private val remember = TriageVerdict(TriageDecision.Remember, "r")
    private val surface = TriageVerdict(TriageDecision.Surface, "r")
    private fun min(n: Long) = n * 60_000L
    private val W = TriagePlanner.WINDOW_MS

    /** 把 [r] 当一批发出去并判成 [v],判定时间 [at]。 */
    private fun judge(p: TriagePlanner, r: NoticeRecord, v: TriageVerdict, at: Long) {
        p.sync(snap(r), at)
        assertEquals(listOf(TriageItem(r)), p.begin(r.packageName).map { it.copy(history = emptyList()) })
        p.finish(mapOf(r.key to v), at)
        p.sync(snap(r), at)
    }

    @Test fun updateRightAfterAVerdictWaitsForTheCooldown() {
        val p = planner()
        val v1 = rec("k", "com.a", t0)
        judge(p, v1, remember, t0)
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Nothing), p.plan(true, false, true, t0))
        val v2 = v1.copy(postedAtMs = t0 + 20_000)
        p.sync(snap(v2), t0 + 20_000)
        assertTrue("更新不马上待判定", p.pending().isEmpty())
        assertEquals(TriageCounts(cooling = 1), p.counts())
        assertEquals("只等冷却到点", TriagePlan.WaitUntil(t0 + min(2)), p.plan(true, false, true, t0 + 20_000))
        assertEquals(TriagePlan.WaitUntil(t0 + min(2)), p.plan(true, false, true, t0 + min(2) - 1))
        // 正好到点:待判定,从到点起照常凑批
        assertEquals(TriagePlan.WaitUntil(t0 + min(2) + W), p.plan(true, false, true, t0 + min(2)))
        assertEquals(listOf(v2), p.pending())
        assertEquals(TriageCounts(pending = 1), p.counts())
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, t0 + min(2) + W))
    }

    @Test fun updateAfterTheCooldownEndedWaitsForTheWindowOnly() {
        val p = planner()
        val v1 = rec("k", "com.a", t0)
        judge(p, v1, remember, t0)
        val late = t0 + min(10)
        val v2 = v1.copy(postedAtMs = late)
        p.sync(snap(v2), late)
        assertEquals(listOf(v2), p.pending())
        assertEquals(TriagePlan.WaitUntil(late + TriagePlanner.WINDOW_MS), p.plan(true, false, true, late))
    }

    @Test fun backoffIs2_5_15_30_30AndSurfaceIs30() {
        val p = planner()
        var r = rec("k", "com.a", t0)
        var at = t0
        val waits = mutableListOf<Long>()
        repeat(5) {
            judge(p, r, remember, at)
            r = r.copy(postedAtMs = r.postedAtMs + 1)
            p.sync(snap(r), at + 1)
            val wake = (p.plan(true, false, true, at + 1) as TriagePlan.WaitUntil).atMs
            waits += wake - at
            assertEquals(TriagePlan.WaitUntil(wake + W), p.plan(true, false, true, wake))
            at = wake + W
            assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, at))
        }
        assertEquals(listOf(min(2), min(5), min(15), min(30), min(30)), waits)
        assertEquals(min(30), TriagePlanner.cooldownMs(TriageDecision.Surface, 1))
        assertEquals(min(30), TriagePlanner.cooldownMs(TriageDecision.Surface, 3))
        assertEquals(min(2), TriagePlanner.cooldownMs(TriageDecision.Ignore, 1))
        // 值得提醒:30 分钟
        val q = planner()
        val s1 = rec("s", "com.b", t0)
        judge(q, s1, surface, t0)
        q.sync(snap(s1.copy(postedAtMs = t0 + 1)), t0 + 1)
        assertEquals(TriagePlan.WaitUntil(t0 + min(30)), q.plan(true, false, true, t0 + 1))
    }

    @Test fun handledNotificationStartsFresh() {
        val p = planner()
        var r = rec("k", "com.a", t0)
        judge(p, r, remember, t0)
        r = r.copy(postedAtMs = t0 + 1)
        p.sync(snap(r), t0 + 1)
        assertEquals(TriagePlan.WaitUntil(t0 + min(2) + W), p.plan(true, false, true, t0 + min(2)))
        judge(p, r, remember, t0 + min(2) + W)
        // 用户点开或划掉了:收件箱里没了
        p.sync(snap(), t0 + min(3))
        assertTrue(p.marks().isEmpty())
        // 同一个键又来了:是新的一条,照常凑批,之后的冷却又从 2 分钟起
        val again = r.copy(postedAtMs = t0 + min(4))
        p.sync(snap(again), t0 + min(4))
        assertEquals(listOf(again), p.pending())
        assertEquals(TriagePlan.WaitUntil(t0 + min(4) + TriagePlanner.WINDOW_MS), p.plan(true, false, true, t0 + min(4)))
        val at = t0 + min(5)
        judge(p, again, remember, at)
        p.sync(snap(again.copy(postedAtMs = at + 1)), at + 1)
        assertEquals(TriagePlan.WaitUntil(at + min(2)), p.plan(true, false, true, at + 1))
        // 清空也一样
        p.clear()
        p.sync(snap(again.copy(postedAtMs = at + 2)), at + 2)
        assertEquals(TriagePlan.WaitUntil(at + 2 + TriagePlanner.WINDOW_MS), p.plan(true, false, true, at + 2))
    }

    @Test fun waitsForTheEarlierOfWindowAndCooldown() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        judge(p, k, remember, t0)
        val k2 = k.copy(postedAtMs = t0 + 1)
        // 冷却 t0+2min 到点;别的 App 的新通知在冷却快到点时才来,凑批晚于冷却
        val other = rec("o", "com.b", t0 + min(2) - 5_000)
        p.sync(snap(k2, other), t0 + min(2) - 5_000)
        assertEquals(TriagePlan.WaitUntil(t0 + min(2)), p.plan(true, false, true, t0 + min(2) - 5_000))
        // 凑批先到
        val q = planner()
        judge(q, k, remember, t0)
        val early = rec("o", "com.b", t0 + 1_000)
        q.sync(snap(k2, early), t0 + 1_000)
        assertEquals(TriagePlan.WaitUntil(t0 + 1_000 + TriagePlanner.WINDOW_MS), q.plan(true, false, true, t0 + 1_000))
    }

    @Test fun lockedHasNoWaitAndSendsAfterUnlock() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        judge(p, k, remember, t0)
        val k2 = k.copy(postedAtMs = t0 + 1)
        p.sync(snap(k2), t0 + 1)
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Locked), p.plan(false, false, true, t0 + 1))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Locked), p.plan(false, false, true, t0 + min(60)))
        // 预算用完、有任务在跑时也不为冷却计时
        assertEquals(TriagePlan.Idle(TriagePlan.Why.BudgetUsedUp), p.plan(true, false, false, t0 + 1))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Busy), p.plan(true, true, true, t0 + 1))
        // 解锁时冷却早已到点:马上发
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, t0 + min(60)))
    }

    @Test fun retryBypassesTheFailureHoldAndSuccessKeepsTheVerdictLadder() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        judge(p, k, remember, t0)
        val k2 = k.copy(postedAtMs = t0 + 1)
        p.sync(snap(k2), t0 + 1)
        p.plan(true, false, true, t0 + min(2))
        val at = t0 + min(2) + W
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, at))
        p.begin("com.a")
        p.fail(at)
        p.sync(snap(k2), at)
        assertTrue(p.mark(k2) is TriageMark.Failed)
        assertEquals(TriageCounts(failed = 1), p.counts())
        // 再试一次不用等冷却;成功后是第二次判定:冷却 5 分钟(失败那次不算)
        assertEquals(1, p.retryFailed(at + 10))
        p.sync(snap(k2), at + 10)
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, at + 10))
        p.begin("com.a")
        p.finish(mapOf("k" to remember), at + 10)
        p.sync(snap(k2.copy(postedAtMs = at + 11)), at + 11)
        assertEquals(TriagePlan.WaitUntil(at + 10 + min(5)), p.plan(true, false, true, at + 11))
    }

    /** 把 [r] 当一批发出去,失败在 [at]。 */
    private fun failAt(p: TriagePlanner, r: NoticeRecord, at: Long) {
        p.sync(snap(r), at)
        assertEquals(listOf(r.key), p.begin(r.packageName).map { it.record.key })
        p.fail(at)
        p.sync(snap(r), at)
    }

    @Test fun failedKeyIsHeldThenRejudgedOnlyWithNewerContent() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        failAt(p, k, t0)
        // 没有新内容:一直是失败,不自动重试
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Nothing), p.plan(true, false, true, t0 + day / 2))
        val q = planner()
        failAt(q, k, t0)
        val k2 = k.copy(postedAtMs = t0 + 1)
        q.sync(snap(k2), t0 + 1)
        assertTrue("停着期间的更新不待判定", q.pending().isEmpty())
        assertEquals("界面上还是失败", TriageCounts(failed = 1), q.counts())
        assertTrue(q.marks()["k"] is TriageMark.Failed)
        assertEquals(TriagePlan.WaitUntil(t0 + min(2)), q.plan(true, false, true, t0 + 1))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Locked), q.plan(false, false, true, t0 + 1))
        assertEquals(TriagePlan.WaitUntil(t0 + min(2) + W), q.plan(true, false, true, t0 + min(2)))
        assertEquals(TriageCounts(pending = 1), q.counts())
        assertEquals(TriagePlan.Send("com.a"), q.plan(true, false, true, t0 + min(2) + W))
    }

    @Test fun consecutiveFailuresBackOff2_5_15_30_30AndSuccessResets() {
        val p = planner()
        var r = rec("k", "com.a", t0)
        var at = t0
        val holds = mutableListOf<Long>()
        repeat(5) {
            failAt(p, r, at)
            r = r.copy(postedAtMs = r.postedAtMs + 1)
            p.sync(snap(r), at + 1)
            val wake = (p.plan(true, false, true, at + 1) as TriagePlan.WaitUntil).atMs
            holds += wake - at
            at = wake + W
            assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, at))
        }
        assertEquals(listOf(min(2), min(5), min(15), min(30), min(30)), holds)
        // 判成功一次:失败次数清零,之后按判定冷却(第一次判定,2 分钟)
        judge(p, r, remember, at)
        r = r.copy(postedAtMs = r.postedAtMs + 1)
        p.sync(snap(r), at + 1)
        assertEquals(TriagePlan.WaitUntil(at + min(2)), p.plan(true, false, true, at + 1))
        p.plan(true, false, true, at + min(2))
        at += min(2) + W
        failAt(p, r, at)
        r = r.copy(postedAtMs = r.postedAtMs + 1)
        p.sync(snap(r), at + 1)
        assertEquals("又从 2 分钟起", TriagePlan.WaitUntil(at + min(2)), p.plan(true, false, true, at + 1))
    }

    @Test fun manualRetryIgnoresTheHoldAndAFailureContinuesTheLadder() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        failAt(p, k, t0)
        val k2 = k.copy(postedAtMs = t0 + 1)
        p.sync(snap(k2), t0 + 1)
        assertEquals(1, p.retryFailed(t0 + 2))
        p.sync(snap(k2), t0 + 2)
        assertEquals("马上发", TriagePlan.Send("com.a"), p.plan(true, false, true, t0 + 2))
        p.begin("com.a")
        p.fail(t0 + 2)
        p.sync(snap(k2), t0 + 2)
        assertEquals(TriageCounts(failed = 1), p.counts())
        val k3 = k.copy(postedAtMs = t0 + 3)
        p.sync(snap(k3), t0 + 3)
        assertEquals("第二次失败:停 5 分钟", TriagePlan.WaitUntil(t0 + 2 + min(5)), p.plan(true, false, true, t0 + 3))
    }

    @Test fun removalForgetsTheFailureHold() {
        val p = planner()
        var k = rec("k", "com.a", t0)
        failAt(p, k, t0)
        k = k.copy(postedAtMs = t0 + 1)
        p.plan(true, false, true, t0 + min(2))
        failAt(p, k, t0 + min(2) + W)
        p.sync(snap(), t0 + min(3))
        assertTrue(p.marks().isEmpty())
        val again = k.copy(postedAtMs = t0 + min(4))
        p.sync(snap(again), t0 + min(4))
        assertEquals("新的一条:照常凑批", listOf(again), p.pending())
        failAt(p, again, t0 + min(5))
        p.sync(snap(again.copy(postedAtMs = t0 + min(5) + 1)), t0 + min(5) + 1)
        assertEquals("失败次数从头算", TriagePlan.WaitUntil(t0 + min(7)), p.plan(true, false, true, t0 + min(5) + 1))
    }

    @Test fun newKeyIsJudgedWhileAnotherKeyOfTheAppIsHeld() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        failAt(p, k, t0)
        val held = k.copy(postedAtMs = t0 + 1)
        val fresh = rec("n", "com.a", t0 + 1)
        p.sync(snap(held, fresh), t0 + 1)
        assertEquals(listOf(fresh), p.pending())
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, t0 + 1 + W))
        assertEquals(listOf("n"), p.begin("com.a").map { it.record.key })
    }

    @Test fun keyHandledDuringTheRequestGetsNoCooldown() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        p.sync(snap(k), t0)
        p.begin("com.a")
        p.sync(snap(), t0 + 1)
        val back = k.copy(postedAtMs = t0 + 2)
        p.sync(snap(back), t0 + 2)
        p.finish(mapOf("k" to remember), t0 + 3)
        p.sync(snap(back), t0 + 3)
        assertNull(p.marks()["k"])
        assertEquals(listOf(back), p.pending())
    }

    @Test fun budgetLatchHoldsForTheDay() {
        val p = planner()
        val r = rec("a1", "com.a")
        p.sync(snap(r), t0)
        p.begin("com.a")
        p.budgetExhausted(t0)
        assertEquals(listOf(r), p.pending())
        val later = t0 + TriagePlanner.WINDOW_MS
        assertEquals(TriagePlan.Idle(TriagePlan.Why.BudgetUsedUp), p.plan(true, false, true, later))
        assertTrue(p.budgetLatched(later))
        assertFalse(p.budgetLatched(t0 + day))
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, t0 + day))
        p.clearBudgetLatch()
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, later))
    }

    @Test fun lockedWithPendingIsLocked() {
        val p = planner()
        p.sync(snap(rec("a1", "com.a")), t0)
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Locked), p.plan(false, false, true, t0 + day))
    }

    // 同意跟着总开关走:关掉就清掉(哪怕同时给了要确认的模型);打开时点了同意的记下那一家;打开时没有可确认的不动
    @Test fun consentFollowsTheMasterSwitch() {
        val t = TriageTarget("p1", "Provider", "m1")
        val had = TriageConsent("p0", "m0")
        assertEquals(null, TriageConsent.afterSwitch(on = false, confirmed = null, current = had))
        assertEquals(null, TriageConsent.afterSwitch(on = false, confirmed = t, current = had))
        assertEquals(TriageConsent("p1", "m1"), TriageConsent.afterSwitch(on = true, confirmed = t, current = null))
        // 重新确认换了一家:记新的,不留旧的
        assertEquals(TriageConsent("p1", "m1"), TriageConsent.afterSwitch(on = true, confirmed = t, current = had))
        // 没配模型时打开的:没有同意,闸门停在「等确认」,配好模型也不会自己放行
        val none = TriageConsent.afterSwitch(on = true, confirmed = null, current = null)
        assertEquals(null, none)
        assertEquals(TriageGate.NoModel, TriageGate.of(true, none, null))
        assertEquals(TriageGate.NeedsConsent, TriageGate.of(true, none, t))
        // 关掉再打开、这次没点同意:上一次的同意已经清了,不会沿用
        val reopened = TriageConsent.afterSwitch(on = true, confirmed = null, current = TriageConsent.afterSwitch(false, null, had))
        assertEquals(TriageGate.NeedsConsent, TriageGate.of(true, reopened, t))
    }

    @Test fun consentMatchesProviderAndModel() {
        val t = TriageTarget("p1", "Provider", "m1")
        val c = TriageConsent.of(t)
        assertTrue(c.covers(t))
        assertTrue("改名不算换", c.covers(t.copy(providerName = "Renamed")))
        assertFalse(c.covers(t.copy(model = "m2")))
        assertFalse(c.covers(t.copy(providerId = "p2")))
        assertFalse(c.covers(null))
        assertEquals(TriageGate.Off, TriageGate.of(false, c, t))
        assertEquals(TriageGate.NoModel, TriageGate.of(true, c, null))
        assertEquals(TriageGate.NeedsConsent, TriageGate.of(true, null, t))
        assertEquals(TriageGate.NeedsConsent, TriageGate.of(true, c, t.copy(model = "m2")))
        assertEquals(TriageGate.Ready, TriageGate.of(true, c, t))
    }
}
