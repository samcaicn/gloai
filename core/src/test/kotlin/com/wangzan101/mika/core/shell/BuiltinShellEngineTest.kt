package com.wangzan101.mika.core.shell

import com.wangzan101.mika.core.adb.AdbConnectionException
import com.wangzan101.mika.core.adb.AdbKeyUnreadableException
import com.wangzan101.mika.core.adb.AdbPairingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinShellEngineTest {
    private val helper = MutableStateFlow<Any?>(null)

    private inner class FakePlatform : BuiltinShellPlatform {
        var pairedFlag = false
        var wireless: Boolean? = true
        var wifi = true
        var pairingPort: Int? = 37000
        var pairError: AdbPairingException.Reason? = null
        /** 每次起助手的结果:null = 成功并交接,其余抛对应异常;用完后一直成功。 */
        val launchResults = ArrayDeque<AdbConnectionException.Reason?>()
        var deliverOnLaunch = true
        var keyUnreadable = false
        val calls = mutableListOf<String>()

        override fun paired() = pairedFlag
        override fun setPaired(paired: Boolean) { pairedFlag = paired; calls += "paired=$paired" }
        override fun wirelessDebugging() = wireless
        override fun wifiConnected() = wifi
        /** 配对窗口没开([pairingPort] 为 null)时找满时限才说没找到。 */
        override suspend fun findPairingPort(timeoutMs: Long) = pairingPort ?: run { delay(timeoutMs); null }
        override fun pair(port: Int, code: String) {
            calls += "pair $port $code"
            pairError?.let { throw AdbPairingException(it, "x") }
        }
        override suspend fun launchHelper() {
            calls += "launch"
            if (keyUnreadable) throw AdbKeyUnreadableException("cannot unwrap adbkey.bin")
            launchResults.removeFirstOrNull()?.let { throw AdbConnectionException(it, "x") }
            if (deliverOnLaunch) helper.value = "binder"
        }
        override fun stopHelper() { calls += "stop"; helper.value = null }
        override fun forget() { calls += "forget" }
        var usb = false
        override fun usbStarted() = usb
        override fun setUsbStarted(on: Boolean) { usb = on }

        /** 「用 root 启动」:是否请求过、有没有 su、su 记下的明确答复;[suAnswers] 是每次经 su 起助手时 su 的答复,用完后一直答应。 */
        var rootOn = false
        var suPresent = true
        var answered: SuAnswer? = null
        val suAnswers = ArrayDeque<SuAnswer>()
        fun enableRoot() {
            rootOn = true
            answered = SuAnswer.Granted
        }
        override fun rootStart() = RootStart.of(rootOn, suPresent, answered)
        override suspend fun launchHelperAsRoot(): SuAnswer? {
            calls += "su"
            if (!suPresent) return null
            val a = suAnswers.removeFirstOrNull() ?: SuAnswer.Granted
            if (a == SuAnswer.Granted && deliverOnLaunch) helper.value = "root"
            return a
        }
        override fun setRootAnswer(answer: SuAnswer?) { answered = answer }
        override fun requestRootStart(): Boolean {
            calls += "ask"
            answered = SuAnswer.Granted
            return true
        }

        var transportMode: ShellTransport = ShellTransport.Auto
        var shizuku = ShizukuPlatformState.Unavailable
        val shizukuResults = ArrayDeque<BuiltinShellFailure?>()
        var deliverOnShizukuLaunch = true

        override fun transport() = transportMode
        override fun setTransport(transport: ShellTransport) {
            transportMode = transport
            calls += "transport=$transport"
        }
        override fun shizukuState() = shizuku
        override suspend fun launchHelperViaShizuku(): BuiltinShellFailure? {
            calls += "shizuku"
            val failure = shizukuResults.removeFirstOrNull()
            if (failure != null) return failure
            if (deliverOnShizukuLaunch) helper.value = "shizuku"
            return null
        }
    }

    private val platform = FakePlatform()

    private fun TestScope.engine(
        backoff: Backoff = Backoff(baseMs = 1_000, maxMs = 8_000, maxAttempts = 3),
        autoOn: WirelessDebuggingAutoOn? = null,
    ) =
        BuiltinShellEngine(
            // 不用 backgroundScope:advanceUntilIdle 不等后台任务,看门狗的重试会被跳过
            platform, helper, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            StandardTestDispatcher(testScheduler), backoff,
            graceMs = 3_000, handoffTimeoutMs = 10_000, autoOn = autoOn,
        )

    /** 替用户打开无线调试:写的是假平台的开关;[revert] 为 true 时像不允许的 Wi-Fi 那样,写完马上被系统拨回。 */
    private var wants = true
    private var revert = false
    private var switchWrites = 0

    private fun TestScope.autoOn() = WirelessDebuggingAutoOn(
        wants = { wants }, canWrite = { true },
        read = { platform.wireless.also { if (revert) platform.wireless = false } },
        write = { switchWrites++; platform.wireless = true; true },
        now = { testScheduler.currentTime },
    )

    private fun launches() = platform.calls.count { it == "launch" }
    private fun suCalls() = platform.calls.count { it == "su" }
    private fun shizukuCalls() = platform.calls.count { it == "shizuku" }

    @Test
    fun backoffDoublesAndStops() {
        val b = Backoff(baseMs = 1_000, maxMs = 5_000, maxAttempts = 4)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, null), (1..4).map { b.delayAfter(it) })
        assertEquals(5_000L, Backoff(baseMs = 1_000, maxMs = 5_000, maxAttempts = 10).delayAfter(6))
    }

    @Test
    fun failureReasonsMapToWhatTheUserShouldDo() {
        assertEquals(BuiltinShellFailure.WrongCode, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.WRONG_CODE))
        assertEquals(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.CONNECT_FAILED))
        assertEquals(BuiltinShellFailure.Unknown, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.TLS_FAILED))
        assertEquals(BuiltinShellFailure.NotLocal, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.NOT_LOCAL))
        assertEquals(BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.UNAUTHORIZED))
        assertEquals(BuiltinShellFailure.ConnectFailed, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.CONNECT_FAILED))
        assertEquals(BuiltinShellFailure.ConnectFailed, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.TLS_FAILED))
        assertEquals(BuiltinShellFailure.NotLocal, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.NOT_LOCAL))
        assertEquals(false, BuiltinShellFailures.retryable(BuiltinShellFailure.NotLocal))
    }

    @Test
    fun notPairedNeverLaunches() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        e.need()
        advanceUntilIdle()
        assertEquals(0, launches())
    }

    @Test
    fun pairingThenLaunchEndsRunning() = runTest {
        val e = engine()
        e.startPairing("123456", null)
        assertEquals(listOf("pair 37000 123456", "paired=true", "launch"), platform.calls)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun pairingFailuresAreReported() = runTest {
        val e = engine()
        platform.pairError = AdbPairingException.Reason.WRONG_CODE
        e.startPairing("123456", 40000)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), e.status.value)
        assertEquals(false, platform.pairedFlag)
        platform.pairingPort = null
        e.startPairing("123456", null)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed), e.status.value)
    }

    /** 诊断信息用的最近一次出错:异常类别、原因和说明;连上后清空。 */
    @Test
    fun lastErrorRecordedAndClearedOnSuccess() = runTest {
        val e = engine()
        platform.pairError = AdbPairingException.Reason.WRONG_CODE
        e.startPairing("123456", 40000)
        assertEquals("AdbPairingException(WRONG_CODE): x", e.lastError)
        platform.pairError = null
        platform.launchResults += AdbConnectionException.Reason.UNAUTHORIZED
        e.startPairing("123456", 40000)
        assertEquals("AdbConnectionException(UNAUTHORIZED): x", e.lastError)
        e.retry()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(null, e.lastError)
    }

    @Test
    fun startupWaitsForRedeliveryBeforeLaunching() = runTest {
        platform.pairedFlag = true
        val e = engine()
        e.start()
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceTimeBy(2_000)
        // 还活着的助手在宽限期内交接了:不用再经 adbd 起
        helper.value = "old"
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(0, launches())
    }

    @Test
    fun startupLaunchesAfterGraceWhenNobodyDelivers() = runTest {
        platform.pairedFlag = true
        val e = engine()
        e.start()
        advanceTimeBy(2_999)
        assertEquals(0, launches())
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun deathIsFollowedByRestartWithBackoff() = runTest {
        platform.pairedFlag = true
        helper.value = "first"
        val e = engine()
        e.start()
        runCurrent()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        platform.launchResults += listOf(AdbConnectionException.Reason.CONNECT_FAILED, AdbConnectionException.Reason.CONNECT_FAILED)
        helper.value = null
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceTimeBy(1_001)
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed), e.status.value)
        // 第 1 次失败后等 1 s,第 2 次失败后等 2 s
        advanceTimeBy(1_001)
        assertEquals(2, launches())
        advanceTimeBy(1_000)
        assertEquals(2, launches())
        advanceUntilIdle()
        assertEquals(3, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun retriesStopAfterMaxAttemptsUntilAskedAgain() = runTest {
        platform.pairedFlag = true
        platform.launchResults += List(10) { AdbConnectionException.Reason.CONNECT_FAILED }
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(3, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed), e.status.value)
        e.retry()
        assertEquals(4, launches())
    }

    @Test
    fun unreadableKeyAsksToPairAgainAndIsNotRetried() = runTest {
        platform.pairedFlag = true
        platform.keyUnreadable = true
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.KeyUnreadable, BuiltinShellFix.RePair), e.status.value)
        assertTrue(e.lastError!!.startsWith("AdbKeyUnreadableException"))
    }

    @Test
    fun notLocalIsNotRetriedAsANetworkFailure() = runTest {
        platform.pairedFlag = true
        platform.wifi = false // 也不能被二次归成 NoWifi
        platform.launchResults += AdbConnectionException.Reason.NOT_LOCAL
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.NotLocal), e.status.value)
    }

    @Test
    fun revokedKeyAndMissingWifiAreNotRetriedAutomatically() = runTest {
        platform.pairedFlag = true
        platform.launchResults += AdbConnectionException.Reason.UNAUTHORIZED
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFix.RePair), e.status.value)

        platform.wifi = false
        platform.launchResults += AdbConnectionException.Reason.CONNECT_FAILED
        e.retry()
        advanceUntilIdle()
        assertEquals(2, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi), e.status.value)
    }

    @Test
    fun wifiComingBackRetriesAfterNoWifi() = runTest {
        platform.pairedFlag = true
        platform.wifi = false
        platform.launchResults += AdbConnectionException.Reason.CONNECT_FAILED
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi), e.status.value)

        platform.wifi = true
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(2, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun wifiComingBackIgnoredUnlessWaitingForWifi() = runTest {
        platform.pairedFlag = true
        platform.launchResults += AdbConnectionException.Reason.UNAUTHORIZED
        val e = engine()
        e.retry()
        advanceUntilIdle()
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFix.RePair), e.status.value)
    }

    @Test
    fun wirelessDebuggingOffWaitsForTheSwitch() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)
        assertEquals(0, launches())
        platform.wireless = true
        e.wirelessDebuggingChanged()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun wirelessDebuggingOffIsTurnedOnWhenTheHelperIsNeeded() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        assertEquals(1, switchWrites)
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun revertedOnThisWifiStopsAndSaysSoUntilWifiChanges() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        revert = true
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed), e.status.value)
        assertEquals(1, switchWrites)
        assertEquals(0, launches())

        // 要用助手、开关又变了:不再写(每写一次系统就弹一次框)
        e.need()
        e.wirelessDebuggingChanged()
        advanceUntilIdle()
        assertEquals(1, switchWrites)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed), e.status.value)

        // 换到了允许过的 Wi-Fi:再写一次,这次开着
        revert = false
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(2, switchWrites)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun userRetryWritesAgainAfterARevert() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        revert = true
        val e = engine(autoOn = autoOn())
        e.retry()
        assertEquals(1, switchWrites)
        // 用户在系统弹框里允许了、再点重试
        revert = false
        e.retry()
        assertEquals(2, switchWrites)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun switchOffInMikaNeverWrites() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        wants = false
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        e.wifiAvailable()
        e.need()
        advanceUntilIdle()
        assertEquals(0, switchWrites)
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)
    }

    @Test
    fun withoutWifiWaitsAndTurnsOnWhenWifiComesBack() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        platform.wifi = false
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        assertEquals(0, switchWrites)
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)

        platform.wifi = true
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(1, switchWrites)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun launchedButNeverHandedOffIsHelperStartFailed() = runTest {
        platform.pairedFlag = true
        platform.deliverOnLaunch = false
        val e = engine(Backoff(maxAttempts = 1))
        e.retry()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), e.status.value)
    }

    @Test
    fun needLaunchesOnceEvenIfCalledRepeatedly() = runTest {
        platform.pairedFlag = true
        val e = engine()
        e.need()
        e.need()
        advanceUntilIdle()
        assertEquals(1, launches())
    }

    @Test
    fun unpairStopsHelperAndForgets() = runTest {
        platform.pairedFlag = true
        helper.value = "h"
        val e = engine()
        e.start()
        runCurrent()
        e.unpair()
        advanceUntilIdle()
        assertTrue(platform.calls.containsAll(listOf("stop", "forget", "paired=false")))
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        assertEquals(0, launches())
    }

    /** 电脑经 USB 起的助手没配对也收下:状态是运行中,并记下「是电脑起的」;手机操作和配对过的一样可用。 */
    @Test
    fun usbStartedHelperRunsWithoutPairing() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        helper.value = "usb"
        runCurrent()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertTrue(platform.usb)
        assertEquals(false, e.paired)
        assertEquals(0, launches())
    }

    /** 没配对时电脑起的助手停了:不当作从没设置过,说它停了;Mika 自己不去起(起不来)。 */
    @Test
    fun usbHelperStoppingSaysSoAndDoesNotLaunch() = runTest {
        val e = engine()
        e.start()
        helper.value = "usb"
        runCurrent()
        helper.value = null
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), e.status.value)
        e.need()
        e.retry()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), e.status.value)
        assertEquals(0, launches())
        // 再运行一次命令,不是去配对无线调试
        assertEquals(BuiltinShellFix.RunUsbCommand, (e.status.value as BuiltinShellStatus.Failed).fix)
        assertEquals(HelperRestart.UsbCommand, e.restart)
    }

    /** 用着用着断了(比如助手正在换一个新的来交接):先显示正在连接,等一会儿还没回来才说停了。 */
    @Test
    fun usbHelperDropMidRunGetsAGraceBeforeStopped() = runTest {
        val e = engine()
        e.start()
        helper.value = "usb"
        runCurrent()
        helper.value = null
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceTimeBy(2_000)
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        // 换手完成:新的助手来交接,一直没有闪成「已停止」
        helper.value = "usb2"
        runCurrent()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(0, launches())
    }

    /** 助手停了以后靠什么再起来:没设置、只用 USB 命令起过、配对过无线调试(配对过优先,哪怕上次是用命令起的)。 */
    @Test
    fun restartRouteFollowsHowTheHelperCameUp() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(HelperRestart.None, e.restart)
        helper.value = "usb"
        runCurrent()
        assertEquals(HelperRestart.UsbCommand, e.restart)
        platform.pairedFlag = true
        assertEquals(HelperRestart.Wireless, e.restart)
    }

    /** 重启后(新进程)助手没来交接:先等一会儿(还活着的会自己来),等不到才说它停了。 */
    @Test
    fun afterRestartUsbHelperGetsAGraceThenStopped() = runTest {
        platform.usb = true
        val e = engine()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        e.start()
        advanceTimeBy(2_000)
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), e.status.value)
        assertEquals(0, launches())
    }

    @Test
    fun usbHelperComingBackWithinGraceIsRunning() = runTest {
        platform.usb = true
        val e = engine()
        e.start()
        advanceTimeBy(1_000)
        helper.value = "usb"
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    /** 配对过就照旧由看门狗经无线调试重起,不提示跑命令。 */
    @Test
    fun pairedUsbHelperIsRestartedWireless() = runTest {
        platform.usb = true
        platform.pairedFlag = true
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(1, launches())
    }

    /** 解除配对把「电脑起过」也忘掉:之后是没设置,不是「停了」。 */
    @Test
    fun unpairForgetsUsbStart() = runTest {
        val e = engine()
        e.start()
        helper.value = "usb"
        runCurrent()
        e.unpair()
        advanceUntilIdle()
        assertEquals(false, platform.usb)
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
    }

    @Test
    fun discoveryReportsFoundPort() = runTest {
        val e = engine()
        e.startDiscovery()
        assertEquals(PairingDiscovery.Searching, e.pairingDiscovery.value)
        advanceUntilIdle()
        assertEquals(PairingDiscovery.Found(37000), e.pairingDiscovery.value)
        e.stopDiscovery()
        assertEquals(PairingDiscovery.Idle, e.pairingDiscovery.value)
    }

    /** 还在找时再开始一次(用户又回到配对这件事上),找的时限从这时重新算,不按第一次开始的时候收工。 */
    @Test
    fun startingDiscoveryAgainRestartsTheSearchTime() = runTest {
        val e = engine()
        platform.pairingPort = null
        e.startDiscovery()
        advanceTimeBy(9 * 60_000L)
        e.startDiscovery()
        advanceTimeBy(9 * 60_000L)
        assertEquals(PairingDiscovery.Searching, e.pairingDiscovery.value)
        advanceTimeBy(2 * 60_000L)
        assertEquals(PairingDiscovery.Idle, e.pairingDiscovery.value)
    }

    /** 打开了「用 root 启动」:没配对也由看门狗经 su 起助手,不碰无线调试;不记成「电脑起的」。 */
    @Test
    fun rootStartLaunchesWithoutPairing() = runTest {
        platform.enableRoot()
        platform.wireless = false
        val e = engine()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        assertTrue(e.rootStart)
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(1, suCalls())
        assertEquals(0, launches())
        assertEquals(false, platform.usb)
    }

    /** 助手死了(或重启后)由看门狗经 su 自己再起,不用配对、不用电脑。 */
    @Test
    fun rootRestartsTheHelperAfterItDies() = runTest {
        platform.enableRoot()
        val e = engine()
        e.start()
        advanceUntilIdle()
        helper.value = null
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(2, suCalls())
    }

    /** 几条路都有时先走 root;root 起不来(这里是交接超时)且配过对,接着走无线调试。 */
    @Test
    fun rootIsPreferredAndWirelessIsTheFallback() = runTest {
        platform.enableRoot()
        platform.pairedFlag = true
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(listOf("su"), platform.calls)
        helper.value = null
        platform.deliverOnLaunch = false
        advanceTimeBy(1_000)
        runCurrent()
        platform.deliverOnLaunch = true
        advanceUntilIdle()
        // 第二次:su 答应了却没等到交接,换无线调试起来
        assertEquals(listOf("su", "su", "launch"), platform.calls)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    /** su 拒绝了:记下来,报「root 被拒绝」,之后不再自己去问 su(助手需要时也不问),直到用户点重试。 */
    @Test
    fun rootDenialIsRecordedAndNotRetriedUntilTheUserAsks() = runTest {
        platform.enableRoot()
        platform.suAnswers += SuAnswer.Denied
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootDenied), e.status.value)
        assertEquals(SuAnswer.Denied, platform.answered)
        assertEquals(false, e.rootStart)
        e.need()
        advanceTimeBy(120_000)
        advanceUntilIdle()
        assertEquals(1, suCalls())
        assertEquals(BuiltinShellFix.Retry, (e.status.value as BuiltinShellStatus.Failed).fix)
        // 用户点了重试:平台异步再请求授权,此时尚未直接起助手
        e.retry()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        assertEquals(1, suCalls())
        assertEquals("ask", platform.calls.last())
        // 授权完成后平台通知引擎,才按新答复启动
        e.rootChanged()
        advanceUntilIdle()
        assertEquals(2, suCalls())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    /** su 不回话、切不到 shell 身份也一样不反复去问,各自报自己的原因。 */
    @Test
    fun rootNoAnswerAndWrongUidStopAndSaySo() = runTest {
        platform.enableRoot()
        platform.suAnswers += SuAnswer.NoAnswer
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootNoAnswer), e.status.value)
        platform.suAnswers += SuAnswer.WrongUid
        e.retry()
        e.rootChanged()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootNoShellUid), e.status.value)
        e.need()
        advanceUntilIdle()
        assertEquals(2, suCalls())
    }

    /** 配过对时 su 拒绝了就走无线调试,状态是运行中(root 的问题在设置里那一项说)。 */
    @Test
    fun rootDeniedButPairedRunsOverWireless() = runTest {
        platform.enableRoot()
        platform.pairedFlag = true
        platform.suAnswers += SuAnswer.Denied
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(listOf("su", "launch"), platform.calls)
        helper.value = null
        advanceUntilIdle()
        // 拒绝之后不再问 su
        assertEquals(listOf("su", "launch", "launch"), platform.calls)
    }

    /** 打开了「用 root 启动」,su 却没了:没配对时说 root 用不了了。 */
    @Test
    fun rootGoneSaysSo() = runTest {
        platform.enableRoot()
        platform.suPresent = false
        val e = engine()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootUnavailable), e.status.value)
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootUnavailable), e.status.value)
        assertEquals(0, suCalls())
    }

    /** 只记录过用户想用时仍不能自动问 su;必须已有明确的授权答复。 */
    @Test
    fun rootStartNeverAsksWithoutStoredAuthorization() = runTest {
        platform.rootOn = true
        platform.answered = null
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        assertEquals(0, suCalls())
    }

    /** 用户在设置里授权或停止自动启动:助手不在时按新的情况马上处理。 */
    @Test
    fun rootSwitchChangesTakeEffectRightAway() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        platform.enableRoot()
        e.rootChanged()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(1, suCalls())
        platform.rootOn = false
        helper.value = null
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        assertEquals(1, suCalls())
    }

    // ---- Shizuku 通道与切换 ----

    @Test
    fun shellTransportParseDefaultsToAuto() {
        assertEquals(ShellTransport.Auto, ShellTransport.parse(null))
        assertEquals(ShellTransport.Auto, ShellTransport.parse(""))
        assertEquals(ShellTransport.Auto, ShellTransport.parse("unknown"))
        assertEquals(ShellTransport.Auto, ShellTransport.parse("auto"))
        assertEquals(ShellTransport.WirelessDebugging, ShellTransport.parse("WirelessDebugging"))
        assertEquals(ShellTransport.WirelessDebugging, ShellTransport.parse("wirelessdebugging"))
        assertEquals(ShellTransport.Shizuku, ShellTransport.parse(" shizuku "))
    }

    @Test
    fun shizukuPlatformStateMapsReadyAndFailures() {
        assertFalse(ShizukuPlatformState.Unavailable.ready)
        assertEquals(BuiltinShellFailure.ShizukuNotInstalled, ShizukuPlatformState.Unavailable.failure())
        assertEquals(
            BuiltinShellFailure.ShizukuNotRunning,
            ShizukuPlatformState(installed = true, running = false, permissionGranted = false).failure(),
        )
        assertEquals(
            BuiltinShellFailure.ShizukuPermissionNeeded,
            ShizukuPlatformState(installed = true, running = true, permissionGranted = false).failure(),
        )
        val ready = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        assertTrue(ready.ready)
        assertEquals(BuiltinShellFailure.ShizukuBindFailed, ready.failure())
        assertTrue(BuiltinShellFailures.retryable(BuiltinShellFailure.ShizukuBindFailed))
        assertFalse(BuiltinShellFailures.retryable(BuiltinShellFailure.ShizukuNotInstalled))
        assertFalse(BuiltinShellFailures.retryable(BuiltinShellFailure.ShizukuNotRunning))
        assertFalse(BuiltinShellFailures.retryable(BuiltinShellFailure.ShizukuPermissionNeeded))
    }

    @Test
    fun autoModeConnectsViaShizukuWithoutWifiOrPairing() = runTest {
        platform.wifi = false
        platform.wireless = false
        platform.pairedFlag = false
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        val e = engine()
        assertEquals(ShellTransport.Auto, e.transport)
        assertTrue(e.shizukuStart)
        assertEquals(HelperRestart.Shizuku, e.restart)
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)

        e.start()
        runCurrent()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(1, shizukuCalls())
        assertEquals(0, launches())
        assertEquals(0, suCalls())
        assertFalse(platform.usb)
        assertEquals(null, e.lastError)
    }

    @Test
    fun autoModeFallsBackToWirelessWhenShizukuNotRunningPermissionMissingOrBindFails() = runTest {
        platform.pairedFlag = true
        platform.shizuku = ShizukuPlatformState(installed = true, running = false, permissionGranted = true)
        val scope1 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val e1 = BuiltinShellEngine(platform, helper, scope1, StandardTestDispatcher(testScheduler), Backoff(baseMs = 1_000, maxMs = 8_000, maxAttempts = 3), graceMs = 3_000, handoffTimeoutMs = 10_000)
        assertEquals(HelperRestart.Wireless, e1.restart)
        e1.start()
        advanceUntilIdle()
        assertEquals(0, shizukuCalls())
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e1.status.value)
        scope1.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        helper.value = null
        platform.calls.clear()
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = false)
        val scope2 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val e2 = BuiltinShellEngine(platform, helper, scope2, StandardTestDispatcher(testScheduler), Backoff(baseMs = 1_000, maxMs = 8_000, maxAttempts = 3), graceMs = 3_000, handoffTimeoutMs = 10_000)
        e2.start()
        advanceUntilIdle()
        assertEquals(0, shizukuCalls())
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e2.status.value)
        scope2.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        helper.value = null
        platform.calls.clear()
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        platform.shizukuResults += BuiltinShellFailure.ShizukuBindFailed
        val e3 = engine()
        e3.start()
        advanceUntilIdle()
        assertEquals(listOf("shizuku", "launch"), platform.calls)
        assertEquals(BuiltinShellStatus.Running, e3.status.value)
        assertEquals(null, e3.lastError)
    }

    @Test
    fun autoModeStopsInFlightShizukuBeforeFallingBackToWirelessOnTimeout() = runTest {
        platform.pairedFlag = true
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        platform.deliverOnShizukuLaunch = false
        platform.deliverOnLaunch = true
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(listOf("shizuku", "stop", "launch"), platform.calls)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals("binder", helper.value)
        assertEquals(null, e.lastError)
    }

    @Test
    fun autoModeReportsShizukuNotRunningWhenInstalledAndUnpairedThenConnectsOnRetryAndShizukuChanged() = runTest {
        platform.pairedFlag = false
        platform.shizuku = ShizukuPlatformState(installed = true, running = false, permissionGranted = true)
        val e = engine()
        assertEquals(HelperRestart.Shizuku, e.restart)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotRunning), e.status.value)
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotRunning), e.status.value)
        assertEquals(BuiltinShellFix.Retry, (e.status.value as BuiltinShellStatus.Failed).fix)
        assertEquals(0, shizukuCalls())
        assertEquals(0, launches())

        // Shizuku 启动后点 retry 直接连上
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        e.retry()
        advanceUntilIdle()
        assertEquals(1, shizukuCalls())
        assertEquals(BuiltinShellStatus.Running, e.status.value)

        // 之后 Shizuku 停了且助手断开:报 ShizukuNotRunning,等 Shizuku 再起来通知引擎后自动连上
        platform.shizuku = ShizukuPlatformState(installed = true, running = false, permissionGranted = true)
        helper.value = null
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotRunning), e.status.value)
        assertFalse(platform.usb)

        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        e.shizukuChanged()
        advanceUntilIdle()
        assertEquals(2, shizukuCalls())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun shizukuModeReportsAllShizukuFailuresWithoutWirelessOrRootFallback() = runTest {
        platform.transportMode = ShellTransport.Shizuku
        platform.pairedFlag = true
        platform.enableRoot()

        platform.shizuku = ShizukuPlatformState.Unavailable
        val notInstalled = engine()
        assertEquals(HelperRestart.Shizuku, notInstalled.restart)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotInstalled), notInstalled.status.value)
        notInstalled.start()
        advanceUntilIdle()
        notInstalled.retry()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotInstalled), notInstalled.status.value)
        assertEquals(0, launches())
        assertEquals(0, suCalls())
        assertEquals(0, shizukuCalls())

        platform.shizuku = ShizukuPlatformState(installed = true, running = false, permissionGranted = false)
        val notRunning = engine()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotRunning), notRunning.status.value)

        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = false)
        val noPerm = engine()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuPermissionNeeded), noPerm.status.value)

        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        platform.shizukuResults += List(10) { BuiltinShellFailure.ShizukuBindFailed }
        val bindFailed = engine()
        bindFailed.start()
        advanceUntilIdle()
        assertEquals(3, shizukuCalls())
        assertEquals(0, launches())
        assertEquals(0, suCalls())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuBindFailed), bindFailed.status.value)
        assertEquals("shizuku: ShizukuBindFailed", bindFailed.lastError)

        // 调起返回 null 却一直没交接过来时,超时报 ShizukuBindFailed
        platform.calls.clear()
        platform.shizukuResults.clear()
        platform.deliverOnShizukuLaunch = false
        val handoffTimeout = engine(backoff = Backoff(maxAttempts = 1))
        handoffTimeout.retry()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuBindFailed), handoffTimeout.status.value)
        assertEquals("helper handoff timed out (shizuku)", handoffTimeout.lastError)
        assertEquals(0, launches())
        assertEquals(0, suCalls())
    }

    @Test
    fun wirelessDebuggingModeIgnoresReadyShizuku() = runTest {
        platform.transportMode = ShellTransport.WirelessDebugging
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        platform.pairedFlag = false
        val unpaired = engine()
        assertEquals(ShellTransport.WirelessDebugging, unpaired.transport)
        assertFalse(unpaired.shizukuStart)
        assertEquals(HelperRestart.None, unpaired.restart)
        unpaired.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, unpaired.status.value)
        unpaired.shizukuChanged()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, unpaired.status.value)
        assertEquals(0, shizukuCalls())

        platform.pairedFlag = true
        val paired = engine()
        assertFalse(paired.shizukuStart)
        assertEquals(HelperRestart.Wireless, paired.restart)
        paired.start()
        advanceUntilIdle()
        assertEquals(0, shizukuCalls())
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, paired.status.value)
    }

    @Test
    fun selectTransportSwitchesBetweenShizukuAndWirelessDebugging() = runTest {
        platform.pairedFlag = true
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals("shizuku", helper.value)
        assertEquals(1, shizukuCalls())

        // 切到同一通道不重启助手
        e.selectTransport(ShellTransport.Auto)
        advanceUntilIdle()
        assertEquals(0, platform.calls.count { it == "stop" })

        // 切到仅无线调试:停掉 Shizuku 起的助手,立即经无线调试重起
        e.selectTransport(ShellTransport.WirelessDebugging)
        runCurrent()
        assertEquals(1, launches())
        assertEquals("binder", helper.value)
        assertEquals(BuiltinShellStatus.Running, e.status.value)

        // 切到仅 Shizuku:停掉无线调试助手,立即经 Shizuku 重起
        e.selectTransport(ShellTransport.Shizuku)
        runCurrent()
        assertEquals(2, shizukuCalls())
        assertEquals("shizuku", helper.value)
        assertEquals(BuiltinShellStatus.Running, e.status.value)

        // 没配对时切到仅无线调试:停掉助手,回到 NotPaired
        platform.pairedFlag = false
        e.selectTransport(ShellTransport.WirelessDebugging)
        advanceUntilIdle()
        assertEquals(null, helper.value)
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
    }
}
