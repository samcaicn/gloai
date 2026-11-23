package com.wangzan101.mika.core.safety.gate

import com.wangzan101.mika.core.action.ClassifiedRisk
import com.wangzan101.mika.core.safety.gate.GateTarget
import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericKeypadEditingTest {
    private fun element(ref: String, name: String, x: Int, y: Int, width: Int = 178, height: Int = 75,
                        clickable: Boolean = true) = UiElement(
        ref, if (clickable) "button" else "view", name, x, y,
        listOf(x - width / 2, y - height / 2, x + width / 2, y + height / 2),
        resourceId = null,
        clickable = clickable,
        editable = false,
        scrollable = false,
    )

    private fun pricePad(extra: List<UiElement> = emptyList(), omit: String? = null): ScreenSnapshot {
        val keys = listOf(
            element("@decoration", "", 626, 223, 746, 67, false),
            element("@tip", "可以设「粉丝价」啦！快去给你的粉丝专属优惠吧！", 491, 301, 895, 23, false),
            element("@price", "价格设置3.9", 500, 367, 910, 60, false),
            element("@auction", "发起竞拍", 864, 368, 183, 23),
            element("@fanPrice", "设置粉丝价", 197, 428, 306, 61, false),
            element("@original", "原价设置", 500, 491, 910, 60, false),
            element("@stock", "库存设置1", 500, 554, 910, 60, false),
            element("@fee", "预估鱼小铺软件服务费 (1.6%)-¥0.06, 预估鱼小铺软件服务费 (1.6%)\n-¥0.06", 500, 611, 998, 50, false),
            element("@proceeds", "预估到手价(含运费) ¥3.84, 预估到手价(含运费) ¥3.84", 500, 659, 998, 45, false),
            element("@1", "1", 125, 734), element("@2", "2", 375, 734), element("@3", "3", 625, 734),
            element("@4", "4", 125, 810), element("@5", "5", 375, 810), element("@6", "6", 625, 810),
            element("@7", "7", 125, 886), element("@8", "8", 375, 886), element("@9", "9", 625, 886),
            element("@dot", ".", 125, 962), element("@0", "0", 375, 962),
            element("@backspace", "删除", 875, 772),
            element("@hide", "收起键盘", 625, 962),
            element("@done", "确定", 875, 924),
        ).filterNot { it.name == omit }
        return ScreenSnapshot("s1", "example.seller", keys + extra, 1080, 2400)
    }

    private fun tap(screen: ScreenSnapshot, ref: String, fresh: ScreenSnapshot? = null): Irreversible? {
        val args: JsonObject = Json.parseToJsonElement("""{"ref":"$ref","snapshotId":"s1"}""").jsonObject
        return IrreversibleDetector.classify(GateTarget.Tap, args, screen, fresh)
    }

    @Test fun decimalPriceKeypadBackspaceEditsText() {
        val screen = pricePad()
        val hit = tap(screen, "@backspace")
        assertNull(hit)
        assertFalse(ClassifiedRisk.assess(Verb.Tap, hit) { screen }.payment)
    }

    @Test fun decimalPriceKeypadDoneCommitsAnEditingField() {
        val screen = pricePad()
        val hit = tap(screen, "@done")
        assertNull(hit)
        assertFalse(ClassifiedRisk.assess(Verb.Tap, hit) { screen }.payment)
    }

    @Test fun incompleteOrAmbiguousKeypadDoesNotExemptDelete() {
        assertEquals(IrrevAction.delete, tap(pricePad(omit = "7"), "@backspace")?.action)
        val duplicate = element("@duplicate", "7", 140, 886)
        assertEquals(IrrevAction.delete, tap(pricePad(listOf(duplicate)), "@backspace")?.action)
    }

    @Test fun checkoutConfirmationRemainsGuardedEvenWithPriceEditorWords() {
        val screen = pricePad(listOf(element("@payment", "确认支付 ¥3.99", 500, 200, 900, 70)))
        val hit = tap(screen, "@done")
        assertNotNull(hit)
        assertTrue(ClassifiedRisk.assess(Verb.Tap, hit) { screen }.payment)
        assertEquals(IrrevAction.pay, tap(screen, "@payment")?.action)
    }

    @Test fun genericAmountKeypadConfirmationRemainsGuarded() {
        val screen = pricePad().let { it.copy(elements = it.elements.filterNot { e -> e.ref == "@stock" }) }
        val hit = tap(screen, "@done")
        assertNotNull(hit)
        // 卖家价格页的「预估服务费/到手价」只是页面内容,不是应付金额,不按付款判;但仍按不可逆动作拦。
        assertFalse(ClassifiedRisk.assess(Verb.Tap, hit) { screen }.payment)
    }

    @Test fun deletingAnObjectOutsideKeypadRemainsGuarded() {
        val screen = pricePad(listOf(element("@deleteItem", "删除商品", 500, 100, 400, 70)))
        assertEquals(IrrevAction.delete, tap(screen, "@deleteItem")?.action)
        assertNotNull(tap(screen, "@done"))
    }

    @Test fun oversizedDeleteContainerIsNotAnEditingKey() {
        val screen = pricePad().let { old ->
            old.copy(elements = old.elements.map {
                if (it.ref == "@backspace") it.copy(bounds = listOf(0, 600, 999, 999)) else it
            })
        }
        assertEquals(IrrevAction.delete, tap(screen, "@backspace")?.action)
    }

    @Test fun aFreshDifferentTargetCannotReuseTheEditingExemption() {
        val before = pricePad()
        val after = before.copy(snapshotId = "s2", elements = before.elements.map {
            if (it.ref == "@backspace") it.copy(name = "删除账户") else it
        })
        assertEquals(IrrevAction.delete, tap(before, "@backspace", after)?.action)
    }

    @Test fun submitOrderIsNeverAnEditingConfirmation() {
        val screen = pricePad().let { old ->
            old.copy(elements = old.elements.map { if (it.ref == "@done") it.copy(name = "提交订单") else it })
        }
        assertEquals(IrrevAction.order, tap(screen, "@done")?.action)
    }

    @Test fun unstableOrProtectedScreensDoNotExemptDelete() {
        val screen = pricePad()
        assertEquals(IrrevAction.delete, tap(screen.copy(changedDuringRead = true), "@backspace")?.action)
        assertEquals(IrrevAction.delete, tap(screen.copy(protected = true), "@backspace")?.action)
    }

    @Test fun explicitPaymentResourcePreventsAnEditingExemption() {
        val screen = pricePad().let { old ->
            old.copy(elements = old.elements.map {
                if (it.ref == "@done") it.copy(resourceId = "example:id/pay") else it
            })
        }
        assertNotNull(tap(screen, "@done"))
    }

    @Test fun publicationIsNeverAnEditingConfirmation() {
        val screen = pricePad().let { old ->
            old.copy(elements = old.elements.map { if (it.ref == "@done") it.copy(name = "发布") else it })
        }
        assertEquals(IrrevAction.send, tap(screen, "@done")?.action)
    }
}
