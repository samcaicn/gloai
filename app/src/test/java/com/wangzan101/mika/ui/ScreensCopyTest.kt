package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import com.wangzan101.mika.chat.ui.ScreenRow
import com.wangzan101.mika.chat.ui.ScreensCopy
import com.wangzan101.mika.chat.ui.orderedScreens

/** 「后台屏」那一页的排序和文字(不碰界面,也不碰真任务)。 */
class ScreensCopyTest {
    @get:Rule val texts = ChineseTexts()

    private fun row(displayId: Int, key: String = "k$displayId", foreground: String? = null, web: Boolean = false) =
        ScreenRow(key, "c$displayId", "做点什么", displayId, TaskState.Running(0, "打开设置"), foreground, web)

    /** 建了屏的在前(按建屏先后);还没建屏(-1)的排在后面,彼此保持原来的先后。 */
    @Test fun screensAreOrderedWithUnbuiltOnesLast() {
        val rows = listOf(row(-1, "a"), row(15), row(-1, "b"), row(12))
        assertEquals(listOf("k12", "k15", "a", "b"), orderedScreens(rows).map { it.key })
        assertEquals(emptyList<ScreenRow>(), orderedScreens(emptyList()))
    }

    /** 第二行说在用哪个 App,不写屏号、像素、密度这些开发用的东西。 */
    @Test fun placeSaysWhichAppNotDisplayInternals() {
        assertEquals("正在用微信", ScreensCopy.place(row(12, foreground = "微信")))
        assertEquals("正在用浏览器", ScreensCopy.place(row(-1, web = true)))
        assertEquals("还没打开 App", ScreensCopy.place(row(-1)))
        assertEquals("暂时看不到在用哪个 App", ScreensCopy.place(row(12)))
        listOf(row(12, foreground = "微信"), row(12), row(-1)).map(ScreensCopy::place).forEach {
            assertFalse(it, it.contains("12") || it.contains("dpi") || it.contains("×"))
        }
    }

    /** 每种状态都有一句;等着的、要你动手的和真在干活的分得开。 */
    @Test fun everyStateHasALabel() {
        assertEquals("正在执行", ScreensCopy.state(TaskState.Running(1, "点了一下")))
        assertEquals("任务完成", ScreensCopy.state(TaskState.Succeeded("好了")))
        assertEquals("任务没有完成", ScreensCopy.state(TaskState.Failed("出错了", FailKind.Model)))
        assertEquals("任务已停止", ScreensCopy.state(TaskState.Cancelled))
        val needsYou = listOf(
            TaskState.WaitingInput("密码是什么"),
            TaskState.WaitingTakeover("payment", "要你付款"),
            TaskState.Held(GateReason("waiting_user", "在系统的选择框里选一个", needsYou = true)),
        )
        assertEquals(listOf("等你处理", "等你处理", "等你处理"), needsYou.map(ScreensCopy::state))
        // 只是等条件(锁屏、助手没连上)的不算「要你处理」
        assertEquals("等待中", ScreensCopy.state(TaskState.Held(GateReason("locked", "锁屏了"))))
        assertEquals("等待中", ScreensCopy.state(TaskState.Idle))
    }
}
