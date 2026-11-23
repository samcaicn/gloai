package com.wangzan101.mika.core.learn.replay

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.safety.gate.ConfirmationGate
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远程(MCP)、定时等非 Ui 来源的运行也照常回放路线(TaskRun 对所有来源都接了 routes)。
 * 路线里记录过的付款步骤,在这些运行里此刻一律被拒(#217 的 approval_unavailable),
 * Replayer 应把它当作对不上交还模型,而不是和 AgentLoop 修掉的那次一样直接抛
 * "unexpected refusal" 崩掉整个任务(RouteReplay 的契约:路线出问题都不影响任务)。
 */
class ReplayerDeniedApprovalTest {
    @Test fun deniedPaymentStepHandsBackToTheModelInsteadOfCrashing() = runTest {
        val shop = listOf(
            ui("@e1", "text", "订单确认", listOf(0, 0, 999, 60), clickable = false),
            ui("@e2", "button", "确认支付", listOf(300, 800, 700, 900), rid = "n:id/pay"),
        )
        val s = PagedScreen(mapOf("shop" to shop), start = "shop").apply { pkg = "com.shop" }
        val step = RouteStep("phone_tap", "{}",
            Selector("button", "确认支付", "n:id/pay", listOf(300, 800, 700, 900), 0, true),
            Anchors("com.shop", listOf("订单确认", "确认支付")),
            Anchors("com.shop", listOf("已支付")))
        val route = Route("r-pay", "买{1}", 1, listOf("com.shop"), emptyMap(), listOf(step), lastUsedMs = 0)
        val sink = Events()
        val d: ToolDispatcher = toolDispatcher(s).also { it.captureTargets = true }
        // 回放沿用这次运行的 provenance:远程来源付款一律拒绝(见 ApprovalPolicy)
        val executor = ActionExecutor(d, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(sink),
            provenance = RunProvenance(RunOrigin.Mcp, Attendance.Unattended))
        val out = Replayer(d, executor, gate = {}, sink = sink).run(RouteChoice(route, listOf("矿泉水")))
        assertTrue("denied payment step should hand back to the model, got $out", out is ReplayOutcome.Diverged)
        assertTrue("the denied tap must not run", s.taps.isEmpty())
    }

    /** 对照:同样的路线在 Ui/在场运行里走既有 confirmation_required 路径,照常交还模型、不崩。 */
    @Test fun confirmationRequiredStepStillStopsForConfirmation() = runTest {
        val shop = listOf(
            ui("@e1", "text", "订单确认", listOf(0, 0, 999, 60), clickable = false),
            ui("@e2", "button", "确认支付", listOf(300, 800, 700, 900), rid = "n:id/pay"),
        )
        val s = PagedScreen(mapOf("shop" to shop), start = "shop").apply { pkg = "com.shop" }
        val step = RouteStep("phone_tap", "{}",
            Selector("button", "确认支付", "n:id/pay", listOf(300, 800, 700, 900), 0, true),
            Anchors("com.shop", listOf("订单确认", "确认支付")),
            Anchors("com.shop", listOf("已支付")))
        val route = Route("r-pay", "买{1}", 1, listOf("com.shop"), emptyMap(), listOf(step), lastUsedMs = 0)
        val sink = Events()
        val d: ToolDispatcher = toolDispatcher(s).also { it.captureTargets = true }
        val executor = ActionExecutor(d, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(sink))
        val out = Replayer(d, executor, gate = {}, sink = sink).run(RouteChoice(route, listOf("矿泉水")))
        assertTrue("UI replay should stop for confirmation, got $out", out is ReplayOutcome.StoppedForConfirmation)
        assertTrue(s.taps.isEmpty())
    }
}
