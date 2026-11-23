package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.safety.gate.Irreversible
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import com.wangzan101.mika.core.testing.verb

class ClassifiedRiskTest {
    private fun el(ref: String, role: String, name: String, b: List<Int>, editable: Boolean = false, secret: Boolean = false, focused: Boolean = false) =
        UiElement(ref, role, name, 0, 0, b, null, !editable, editable, false, focused = focused, secret = secret)
    private val screen = ScreenSnapshot("s1", "com.any", listOf(
        el("@e1", "Switch", "隐私保护", listOf(0, 0, 999, 100)),
        el("@e2", "TextView", "隐私政策", listOf(0, 100, 999, 200)),
        el("@e3", "Button", "修改密码", listOf(0, 200, 999, 300)),
        el("@e4", "EditText", "", listOf(0, 300, 999, 400), editable = true, secret = true, focused = true),
        el("@e5", "Button", "Uninstall", listOf(0, 400, 999, 500)),
        el("@e6", "Button", "好的", listOf(0, 500, 999, 600)),
    ), 1080, 2400)
    private val shop = screen.copy(elements = screen.elements + el("@e7", "TextView", "合计 ¥25", listOf(0, 600, 999, 700)))

    private fun assess(tool: String, hit: Irreversible? = null, on: ScreenSnapshot? = screen) = ClassifiedRisk.assess(verb(tool), hit) { on }

    @Test fun levelsFollowTheClassifier() {
        assertEquals(Risk(ActionRisk.Read), assess("phone_back"))
        assertEquals(Risk(ActionRisk.Read), assess("phone_scroll"))
        assertEquals(Risk(ActionRisk.Change), assess("phone_tap"))
        assertEquals(Risk(ActionRisk.External), assess("phone_tap", Irreversible(IrrevAction.send, "发送")))
        assertEquals(Risk(ActionRisk.External), assess("phone_tap", Irreversible(IrrevAction.submit, "提交")))
        assertEquals(Risk(ActionRisk.Irreversible), assess("phone_tap", Irreversible(IrrevAction.delete, "删除")))
        assertEquals(Risk(ActionRisk.Unknown), assess("phone_tap", Irreversible(IrrevAction.other, "其他")))
    }

    @Test fun onlySpendingMoneyIsPayment() {
        // 付款、下单就是花钱
        assertEquals(Risk(ActionRisk.Irreversible, payment = true), assess("phone_tap", Irreversible(IrrevAction.pay, "付款")))
        assertEquals(Risk(ActionRisk.Irreversible, payment = true), assess("phone_tap", Irreversible(IrrevAction.order, "提交订单")))
        // 屏上有金额时不可逆的动作(删除)按花钱算;发送、提交放开,不再按花钱
        assertEquals(Risk(ActionRisk.External), assess("phone_tap", Irreversible(IrrevAction.submit, "提交"), on = shop))
        // 账号、密码、安全设置、密码框、看不清的都不是花钱
        for (tool in listOf("phone_tap", "phone_type_text")) assertEquals(false, assess(tool).payment)
        assertEquals(false, assess("phone_tap", Irreversible(IrrevAction.delete, "注销账号")).payment)
        assertEquals(false, assess("phone_tap", Irreversible(IrrevAction.send, "发送")).payment)
        // 金额在输入框里(自己打的字)不算;没命中不可逆判定的一般改动不看金额
        val typed = screen.copy(elements = screen.elements + el("@e7", "EditText", "¥25", listOf(0, 600, 999, 700), editable = true))
        assertEquals(false, assess("phone_tap", Irreversible(IrrevAction.submit, "提交"), on = typed).payment)
        assertEquals(Risk(ActionRisk.Change), assess("phone_tap", on = shop))
    }

    @Test fun arbitraryAmountsDoNotTurnOrdinaryActionsIntoPayments() {
        val salary = screen.copy(elements = screen.elements + listOf(
            el("@e7", "TextView", "150-200元/天", listOf(0, 600, 999, 660)),
            el("@e8", "Button", "提交", listOf(0, 700, 999, 760)),
        ))
        // 金额只是页面内容,不是这一步要付的钱;区间也不能把提交、联系或删除升级成付款
        assertEquals(Risk(ActionRisk.External), assess("phone_tap", Irreversible(IrrevAction.submit, "提交"), on = salary))
        assertEquals(Risk(ActionRisk.External), assess("phone_tap", Irreversible(IrrevAction.submit, "立即沟通"), on = salary))
        assertEquals(Risk(ActionRisk.Irreversible), assess("phone_tap", Irreversible(IrrevAction.delete, "删除"), on = salary))
        // 提交放开、不再看金额;合计、付款面板仍把删除升级成付款;明确下单、付款不依赖页面金额
        assertEquals(false, assess("phone_tap", Irreversible(IrrevAction.submit, "提交"), on = shop).payment)
        val unreadableTotal = screen.copy(elements = screen.elements + el("@e9", "TextView", "合计", listOf(0, 600, 999, 660)))
        assertEquals(false, assess("phone_tap", Irreversible(IrrevAction.submit, "提交"), on = unreadableTotal).payment)
        val paySheet = salary.copy(elements = salary.elements + el("@e9", "TextView", "付款方式", listOf(0, 800, 999, 860)))
        assertEquals(false, assess("phone_tap", Irreversible(IrrevAction.submit, "提交"), on = paySheet).payment)
        assertEquals(true, assess("phone_tap", Irreversible(IrrevAction.order, "提交订单"), on = salary).payment)
        assertEquals(true, assess("phone_tap", Irreversible(IrrevAction.pay, "确认转账"), on = salary).payment)
    }

    @Test fun coordinateTapsUseTheSmallestElementThere() {
        assertEquals("修改密码", ClassifiedRisk.target(verb("phone_tap"), buildJsonObject { put("x", 500); put("y", 250) }, screen)?.name)
    }
}
