package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityEvent
import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.InUse
import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.PullBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenSpillTest {
    private val since = 10_000L
    private val window = 6_000L
    private val launchers = setOf("com.home")
    private val home = launchers + "com.wangzan101.mika"

    private fun arrival(
        task: Int = 7, pkg: String? = "com.chat", at: Long = since + 500, from: Int = MainScreenSpill.NEW,
        sameTask: Boolean = false, standard: Boolean = true, now: Int = MainScreenSpill.MAIN, user: Int = 0,
    ) = MainScreenSpill.Arrival(task, pkg, user, at, from, sameTask, standard, now)

    private fun decide(
        vararg a: MainScreenSpill.Arrival,
        topBefore: Int = 3,
        taps: List<MainScreenSpill.Tap> = emptyList(),
        userActiveBefore: Boolean = false,
    ) = MainScreenSpill.decide(a.toList(), since, window, topBefore, home, taps, launchers, userActiveBefore)

    @Test fun newTaskOnTheMainScreenRightAfterTheActionIsPulledBack() {
        assertEquals(listOf(PullBack(arrival())), decide(arrival()))
    }

    @Test fun backgroundTaskOnTheMainScreenBroughtToFrontIsPulledBackToo() {
        // 系统把那个 App 已有的后台任务拉到前台,不新建任务
        val a = arrival(from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(a)), decide(a))
    }

    @Test fun outsideTheWindowIsNotThisAction() {
        assertTrue(decide(arrival(at = since - 1)).isEmpty())
        assertTrue(decide(arrival(at = since + window + 1)).isEmpty())
    }

    @Test fun launcherMikaAndNonStandardPagesAreNotSpills() {
        assertTrue(decide(arrival(pkg = "com.home")).isEmpty())
        assertTrue(decide(arrival(pkg = "com.wangzan101.mika")).isEmpty())
        assertTrue(decide(arrival(standard = false)).isEmpty())
        assertTrue(decide(arrival(pkg = null)).isEmpty())
    }

    @Test fun taskMovedFromAnotherDisplayIsATakeoverNotASpill() {
        // 接管(本任务或别的任务)是把任务从 Mika 的屏挪到主屏
        assertTrue(decide(arrival(from = 5)).isEmpty())
    }

    @Test fun taskNoLongerOnTheMainScreenIsLeftAlone() {
        assertTrue(decide(arrival(now = 5)).isEmpty())
        assertTrue(decide(arrival(now = -1)).isEmpty())
    }

    @Test fun userTappingTheMainScreenMeansTheUserOpenedIt() {
        val other = MainScreenSpill.Tap(since + 400, "com.other")
        assertTrue(decide(arrival(at = since + 900), taps = listOf(other)).isEmpty())
    }

    @Test fun userActivityJustBeforeTheActionIsConservative() {
        // 动作开始前用户刚在主屏上动过手(两次动作之间点的,只有计数、没有具体时刻):归属说不清,不拉回、报成用户在用
        val a = arrival(at = since + 900)
        assertEquals(listOf(InUse(a)), decide(a, userActiveBefore = true))
        // 动作之前没动过手:照常拉回
        assertEquals(listOf(PullBack(a)), decide(a, userActiveBefore = false))
    }

    @Test fun systemTouchWithoutASemanticClickKeepsTheUsersAppOnTheMainScreen() {
        // 系统若投递触摸事件，它是控件点击之外的补充证据
        assertTrue(MainScreenSpill.isUserTap(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        assertFalse(MainScreenSpill.isUserTap(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        val touch = MainScreenSpill.Tap(since + 400, null)
        assertTrue(decide(arrival(at = since + 900), taps = listOf(touch)).isEmpty())
    }

    @Test fun returningHomeBeforeOpeningAnAppKeepsTheUsersAppOnTheMainScreen() {
        val desktop = arrival(task = 2, pkg = "com.home", at = since + 200, from = MainScreenSpill.MAIN)
        val opened = arrival(at = since + 900)
        // 桌面和通知栏入口不发控件点击时，先有回桌面的主屏导航也足以证明是用户入口
        assertTrue(decide(desktop, opened).isEmpty())
        // Mika 自己的主屏页面不是桌面，不能把 Agent 的真实外溢当成用户操作
        val mika = arrival(task = 2, pkg = "com.wangzan101.mika", at = since + 200, from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(opened)), decide(mika, opened))
    }

    @Test fun userTappingThePopupItselfLeavesItThere() {
        // 弹窗冒出来之后用户就点在上面(在屏幕上点确认):用户在用它,不挪
        val a = arrival(at = since + 900)
        assertEquals(listOf(InUse(a)), decide(a, taps = listOf(MainScreenSpill.Tap(since + 1_500, "com.chat"))))
        // 点的是别的 App 的窗口:说不清,照常挪回
        assertEquals(1, decide(a, taps = listOf(MainScreenSpill.Tap(since + 1_500, "com.other"))).size)
    }

    @Test fun previousTopComingBackIsNotASpill() {
        // 挪走开出来的那个之后,原来最上面的又露出来
        assertTrue(decide(arrival(task = 3, from = MainScreenSpill.MAIN), topBefore = 3).isEmpty())
    }

    @Test fun newPageInTheAppTheUserIsUsingIsReportedNotMoved() {
        val a = arrival(task = 3, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(InUse(a)), decide(a, topBefore = 3))
    }

    @Test fun systemQuestionIsLeftForTheUserAndNothingElseIs() {
        val leave = MainScreenSpill.Handling.LeaveForUser
        val move = MainScreenSpill.Handling.Move
        // 系统的选择器,或系统包的窗口里的选项对得上装了两份的 App:留给用户选,从不挪、从不重开
        assertEquals(leave, MainScreenSpill.handling(systemPackage = true, systemPicker = true, optionsMatch = false))
        assertEquals(leave, MainScreenSpill.handling(systemPackage = true, systemPicker = false, optionsMatch = true))
        // 系统设置一类的普通系统页面、普通 App:挪回
        assertEquals(move, MainScreenSpill.handling(systemPackage = true, systemPicker = false, optionsMatch = false))
        assertEquals(move, MainScreenSpill.handling(systemPackage = false, systemPicker = false, optionsMatch = true))
    }

    @Test fun appPickedByTheUserIsWhatCameUpAfterTheQuestionClosed() {
        val gone = since + 20_000
        val chooser = arrival(task = 8, pkg = "android", at = since + 300)
        val picked = arrival(task = 9, at = gone - 200)
        val pick = { list: List<MainScreenSpill.Arrival> -> MainScreenSpill.picked(list, 8, gone, 1_000, 3_000, 3, home) }
        assertEquals(picked, pick(listOf(chooser, picked)))
        // 用户刚点过选项也算(那一下就是选)
        assertEquals(picked, MainScreenSpill.picked(listOf(picked), 8, gone, 1_000, 3_000, 3, home))
        // 关掉选择框后露出来的原来那个不算:用户把问题关了
        assertEquals(null, pick(listOf(chooser, arrival(task = 3, at = gone, from = MainScreenSpill.MAIN))))
        // 选择框关掉很久之前冒出来的不是选出来的
        assertEquals(null, pick(listOf(arrival(task = 9, at = gone - 5_000))))
        // 本来就在主屏后台的那个 App 被拉到前台:就是选中的那份
        val existing = arrival(task = 12, at = gone + 400, from = MainScreenSpill.MAIN, user = 128)
        assertEquals(existing, pick(listOf(chooser, existing)))
    }

    @Test fun pickedAppNeedsTheUserForAPasswordOrASecureUnreadablePage() {
        assertTrue(MainScreenSpill.needsUser(passwordField = true, readable = true, secureWindow = false))
        assertTrue(MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = true))
        // 树本来就读不出东西的 App 不算要用户处理,照常挪回
        assertEquals(false, MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = false))
        assertEquals(false, MainScreenSpill.needsUser(passwordField = false, readable = true, secureWindow = false))
    }

    @Test fun accountServiceWithoutLauncherIsAUserStep() {
        val auth = setOf("com.example.accounts", "com.example.chat")
        // 系统的账号登录服务:登记了认证器、没有桌面图标,冒到主屏上的加账号、验证码页整屏、没有密码框也留给用户
        assertTrue(MainScreenSpill.authenticatorUi("com.example.accounts", auth, hasLauncher = false))
    }

    @Test fun authenticatorAppWithLauncherIsMovedBackAsBefore() {
        val auth = setOf("com.example.accounts", "com.example.chat")
        // 聊天 App 为同步联系人登记了认证器,但它有桌面图标:会话、分享页冒到主屏上照常挪回,不因认证器留下
        assertFalse(MainScreenSpill.authenticatorUi("com.example.chat", auth, hasLauncher = true))
        // 不是认证器的包,没有桌面图标也不算
        assertFalse(MainScreenSpill.authenticatorUi("com.example.helper", auth, hasLauncher = false))
    }

    @Test fun oneVerdictPerTaskInOrder() {
        val chooser = arrival(task = 8, pkg = "android", at = since + 300)
        val first = arrival(task = 7, at = since + 200)
        val again = arrival(task = 7, at = since + 600, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(chooser), PullBack(again)), decide(again, first, chooser))
    }
}
