package com.wangzan101.mika.core.safety.gate

import com.wangzan101.mika.core.safety.gate.GateTarget
import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FinancialJobTitleTest {
    private val detector = IrreversibleDetector
    private fun element(name: String, ref: String = "@e1", clickable: Boolean = true) =
        UiElement(ref, "button", name, 0, 0, listOf(100, 200, 900, 300), null, clickable, false, false)
    private fun screen(vararg elements: UiElement, id: String = "s1") =
        ScreenSnapshot(id, "com.example.jobs", elements.toList(), 1080, 2400)
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun financialJobTitlesAreNotPaymentActions() {
        for (title in listOf(
            "跨境支付产品经理", "支付产品经理", "支付工程师", "支付运营", "支付架构师",
            "付款专员", "转账会计", "结算会计", "支付研发工程师", "支付运营经理",
            "支付产品经理 北京", "支付产品经理（跨境）", "支付产品经理 20–40K·15薪",
        )) {
            assertNull(title, detector.matchName(title))
        }
    }

    @Test fun jobTitleClicksWorkForReferencesCoordinatesAndFallbackLabels() {
        val title = "跨境支付产品经理"
        val s = screen(element(title))
        for (call in listOf(
            """{"ref":"@e1","snapshotId":"s1"}""",
            """{"x":500,"y":250}""",
            """{"x":500,"y":250,"target":"$title","text":"$title","seen":"$title"}""",
        )) assertNull(call, detector.classify(GateTarget.Tap, args(call), s, s))
        for (key in listOf("target", "text", "seen")) {
            assertNull(key, detector.classify(GateTarget.Tap, args("""{"x":500,"y":250,"$key":"$title"}"""), null))
        }
        val card = screen(element(""), element(title, "@e2", clickable = false))
        assertNull(detector.classify(GateTarget.Tap, args("""{"ref":"@e1","snapshotId":"s1"}"""), card))
    }

    @Test fun jobTitleDoesNotSupplyPaymentContext() {
        val title = element("跨境支付产品经理", clickable = false)
        val salary = element("150-200元/天", "@e2", clickable = false).copy(bounds = listOf(100, 400, 900, 450))
        val s = screen(title, salary)
        assertNull(detector.paymentPage(s))
        assertFalse(detector.hasPayableAmount(s))
        assertNull(detector.matchName("确定", screen(title)))
    }

    @Test fun explicitActionsAndOtherOccurrencesStillMatch() {
        for (label in listOf(
            "支付", "确认支付", "立即付款", "转账给会计", "付款给经理", "使用支付宝支付", "支付 工程师",
            "确认支付产品经理", "确认 支付产品经理", "立即付款专员", "请支付工程师", "请先支付工程师",
            "现在就支付工程师", "支付工程师 ¥100", "付款会计（￥100）",
            "支付产品经理服务费", "支付工程师工资", "支付产品经理 付款", "支付工程师支付",
        )) assertEquals(label, IrrevAction.pay, detector.matchName(label))
        assertEquals(IrrevAction.delete, detector.matchName("删除支付产品经理"))
        assertEquals(IrrevAction.order, detector.matchName("购买产品经理课程"))
        assertEquals(IrrevAction.order, detector.matchName("提交订单"))
        assertEquals(IrrevAction.order, detector.matchName("结算会计 100元"))
        assertEquals(IrrevAction.submit, detector.matchName("立即沟通"))
    }

    @Test fun jobTitleCannotHidePaymentResourceOrFreshPaymentButton() {
        val old = screen(element("跨境支付产品经理"))
        val tap = args("""{"ref":"@e1","snapshotId":"s1"}""")
        val now = screen(element("立即支付"), id = "fresh")
        assertEquals(IrrevAction.pay, detector.classify(GateTarget.Tap, tap, old, now)?.action)
        val resource = screen(element("跨境支付产品经理").copy(resourceId = "com.example:id/btn_pay"))
        assertEquals(IrrevAction.pay, detector.classify(GateTarget.Tap, tap, resource)?.action)
        val checkout = screen(element("跨境支付产品经理"),
            element("确认支付", "@e2").copy(bounds = listOf(100, 800, 900, 900)))
        assertEquals(IrrevAction.pay, detector.classify(GateTarget.Tap,
            args("""{"ref":"@e2","snapshotId":"s1"}"""), checkout)?.action)
        assertEquals(IrrevAction.pay, detector.matchName("确定", checkout))
    }

    @Test fun occupationFollowedByPaymentDetailsIsStillAPayment() {
        for (label in listOf("支付工程师 工资", "支付产品经理（服务费）", "付款专员-费用", "转账会计 报酬")) {
            assertEquals(label, IrrevAction.pay, detector.matchName(label))
        }
        assertEquals(IrrevAction.order, detector.matchName("结算会计 工资"))
    }
}
