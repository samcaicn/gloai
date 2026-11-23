package com.wangzan101.mika.cap.phone.setup

import android.app.NotificationManager
import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.device.shell.BuiltinShellFailure
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.PairingChannelHealth
import com.wangzan101.mika.core.device.shell.PairingDiscovery
import com.wangzan101.mika.core.device.shell.PairingScreen
import com.wangzan101.mika.core.device.shell.PairingScreenReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingNoticeTest {
    @get:Rule val texts = ChineseTexts()

    /** 找到配对窗口才弹输入框;还在找时先静静放一个(后台被冻结前就发出去),没在找时不发。 */
    @Test
    fun codeEntryPopsOnceThePairingWindowIsFoundAndWaitsQuietlyBefore() {
        assertEquals(PairingNotice.EnterCode(null, quiet = true), PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Searching))
        assertEquals(PairingNotice.EnterCode(null, quiet = true), PairingNotice.of(BuiltinShellStatus.WirelessDebuggingOff, PairingDiscovery.Searching))
        assertNull(PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Idle))
        assertEquals(PairingNotice.EnterCode(37001), PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Found(37001)))
    }

    /** 静静备好的那个:无障碍开着也马上发(要赶在冻结之前),之后不再提醒。 */
    @Test
    fun quietEntryIsPostedAtOnceAndNeverRealerts() {
        val quiet = PairingNotice.EnterCode(null, quiet = true)
        assertEquals(0L, PairingNotice.delayBefore(quiet, autoRead = true))
        assertNull(PairingNotice.realertAfter(quiet, 0))
    }

    @Test
    fun enterCodeThenPairingThenSuccess() {
        val found = PairingDiscovery.Found(1)
        assertEquals(PairingNotice.EnterCode(1), PairingNotice.of(BuiltinShellStatus.NotPaired, found))
        assertEquals(PairingNotice.Working, PairingNotice.of(BuiltinShellStatus.Connecting, found))
        assertEquals(PairingNotice.Done, PairingNotice.of(BuiltinShellStatus.Running, PairingDiscovery.Idle))
    }

    @Test
    fun pairingFailuresKeepTheInputWithHowToRetry() {
        assertEquals(
            PairingNotice.EnterCode(5, "配对码不对或已失效：在配对窗口重新点「使用配对码配对设备」拿新的码，再填一次"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), PairingDiscovery.Found(5)),
        )
        // 窗口关了(找不到配对窗口)也留着输入框,说同样的办法
        val closed = PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed), PairingDiscovery.Searching)
        closed as PairingNotice.EnterCode
        assertNull(closed.port)
        assertTrue(closed.error!!.endsWith("拿新的码，再填一次"))
        // 配对之后的失败请用户回 Mika
        assertEquals(
            PairingNotice.Problem("连上了,但没能准备好"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), PairingDiscovery.Found(5)),
        )
    }

    /** 配对失效:引导页让用户重新配对,找到配对窗口后和没配对时一样给输入框,还在找时一样先静静备好;没在找时照旧请用户回 Mika。 */
    @Test
    fun revokedPairingGetsTheInputOnceThePairingWindowIsFound() {
        val revoked = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)
        assertEquals(PairingNotice.EnterCode(37001), PairingNotice.of(revoked, PairingDiscovery.Found(37001)))
        assertEquals(PairingNotice.EnterCode(null, quiet = true), PairingNotice.of(revoked, PairingDiscovery.Searching))
        assertEquals(PairingNotice.Problem("配对失效了"), PairingNotice.of(revoked, PairingDiscovery.Idle))
        // 用 USB 启动命令起的助手停了:在找配对窗口也不给输入框
        assertEquals(
            PairingNotice.Problem("手机助手已停止"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), PairingDiscovery.Searching),
        )
        // 用 USB 启动命令起的助手停了要再运行一次命令,找到配对窗口也不给输入框
        assertEquals(
            PairingNotice.Problem("手机助手已停止"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), PairingDiscovery.Found(37001)),
        )
    }

    /** 找到配对窗口时,通知给不给输入框和无障碍读到码后试不试(PairingScreen.attempt)对每种状态都一致。 */
    @Test
    fun codeInputMatchesWhatAutoReadWouldTry() {
        val reading = PairingScreenReading("123456", null)
        val statuses = listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting, BuiltinShellStatus.Running,
        ) + BuiltinShellFailure.entries.map { BuiltinShellStatus.Failed(it) }
        statuses.forEach { s ->
            val input = PairingNotice.of(s, PairingDiscovery.Found(5)) is PairingNotice.EnterCode
            assertEquals(s.toString(), PairingScreen.attempt(reading, portFound = true, s, emptySet()) != null, input)
        }
    }

    @Test
    fun codeEntryPopsAtOnceWithoutAccessibilityAndWaitsForAutoRead() {
        val enter = PairingNotice.EnterCode(5)
        assertEquals(0L, PairingNotice.delayBefore(enter, autoRead = false))
        assertEquals(PairingNotice.AUTO_READ_GRACE_MS, PairingNotice.delayBefore(enter, autoRead = true))
        assertEquals(0L, PairingNotice.delayBefore(PairingNotice.Working, autoRead = true))
    }

    @Test
    fun realertsAreCappedAtThree() {
        val enter = PairingNotice.EnterCode(5)
        val waits = generateSequence(0) { it + 1 }.map { PairingNotice.realertAfter(enter, it) }.takeWhile { it != null }.toList()
        assertEquals(3, waits.size)
        assertEquals(PairingNotice.MAX_REALERTS, waits.size)
        // 越往后隔得越久
        assertEquals(waits.filterNotNull().sorted(), waits)
        // 填错后的提示也会再提醒;别的状态不提醒
        assertEquals(PairingNotice.REALERT_MS[0], PairingNotice.realertAfter(PairingNotice.EnterCode(5, "x"), 0))
        listOf(PairingNotice.Working, PairingNotice.Done, PairingNotice.Problem("x")).forEach { assertNull(PairingNotice.realertAfter(it, 0)) }
    }

    @Test
    fun aSubmittedCodeShowsPairingUntilThePairingStarts() {
        val revoked = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)
        // 填了码、配对还没开始:不按填码前的状态算,也不结束(助手原本在运行时也一样,配对开始后状态还会变)
        assertEquals(PairingNotice.Working to false, PairingNotice.follow(revoked, PairingDiscovery.Searching, repairing = false, submitted = true))
        assertEquals(PairingNotice.Working to false, PairingNotice.follow(BuiltinShellStatus.Running, PairingDiscovery.Searching, repairing = false, submitted = true))
        // 没填码时照常按状态算
        assertEquals(PairingNotice.Problem("配对失效了") to false, PairingNotice.follow(revoked, PairingDiscovery.Idle, repairing = false, submitted = false))
    }

    @Test
    fun followingEndsOnlyWhenRunningOutsideARepair() {
        // 重新配对时运行中的旧状态不算配好:不结束,和没配对时一样先静静备好填码通知
        assertEquals(PairingNotice.EnterCode(null, quiet = true) to false, PairingNotice.follow(BuiltinShellStatus.Running, PairingDiscovery.Searching, repairing = true, submitted = false))
        assertEquals(PairingNotice.Done to true, PairingNotice.follow(BuiltinShellStatus.Running, PairingDiscovery.Idle, repairing = false, submitted = false))
    }

    /** 一次配对里通知的先后:先静静备好,找到窗口换成会弹的,填码后正在配对,配上后成功并结束;相邻两条都不一样(换了才重发)。 */
    @Test
    fun aPairingRunGoesQuietThenAlertThenWorkingThenDone() {
        val steps = listOf(
            BuiltinShellStatus.NotPaired to PairingDiscovery.Searching,
            BuiltinShellStatus.NotPaired to PairingDiscovery.Found(37001),
            BuiltinShellStatus.Connecting to PairingDiscovery.Found(37001),
            BuiltinShellStatus.Running to PairingDiscovery.Idle,
        ).map { (s, d) -> PairingNotice.follow(s, d, repairing = false, submitted = false) }
        assertEquals(
            listOf(
                PairingNotice.EnterCode(null, quiet = true) to false,
                PairingNotice.EnterCode(37001) to false,
                PairingNotice.Working to false,
                PairingNotice.Done to true,
            ),
            steps,
        )
        // 找到窗口后那条是会弹、会再提醒的
        assertEquals(PairingNotice.REALERT_MS[0], PairingNotice.realertAfter(steps[1].first!!, 0))
    }

    @Test
    fun channelIsCreatedHighWhenMissing() {
        assertEquals(ChannelPlan.Create(PairingChannel.IDS.last()), PairingChannel.plan(emptyList()))
        assertEquals(PairingChannelHealth.Ready, PairingChannel.plan(emptyList()).health)
    }

    @Test
    fun channelKeepsWhatWorksMigratesOldOnesAndRespectsTheUser() {
        val old = PairingChannel.IDS.first()
        val new = PairingChannel.IDS.last()
        // 旧版本建的、够高:接着用,不换 id
        assertEquals(
            ChannelPlan.Use(old, PairingChannelHealth.Ready),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_HIGH, userSet = false))),
        )
        // 旧版本建得不够高、用户没动过:换新 id 重建
        assertEquals(
            ChannelPlan.Create(new),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_DEFAULT, userSet = false))),
        )
        // 用户自己调低:不改回去,提示
        assertEquals(
            ChannelPlan.Use(old, PairingChannelHealth.Lowered),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_DEFAULT, userSet = true))),
        )
        // 用户关掉:发不出来
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Off),
            PairingChannel.plan(listOf(ChannelSnapshot(new, NotificationManager.IMPORTANCE_NONE, userSet = true))),
        )
        // 最新的 id 也不够高、又没有更新的可换:只能提示
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Lowered),
            PairingChannel.plan(listOf(ChannelSnapshot(new, NotificationManager.IMPORTANCE_LOW, userSet = false))),
        )
        // 新旧都在时看新的
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Ready),
            PairingChannel.plan(
                listOf(
                    ChannelSnapshot(old, NotificationManager.IMPORTANCE_LOW, userSet = true),
                    ChannelSnapshot(new, NotificationManager.IMPORTANCE_HIGH, userSet = false),
                ),
            ),
        )
    }
}
