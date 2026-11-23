package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.cap.root.ROOT_CONFIRM_APP
import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.gate.IrrevAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationCopyTest {
    @get:Rule val texts = ChineseTexts()

    private fun req(content: String = "明天见", amount: String? = null, action: IrrevAction = IrrevAction.send, target: String = "张三") =
        ConfirmationRequest(action, target, content, amount, "com.chat")

    @Test fun longContentEllipsized() {
        val long = "字".repeat(80)
        val body = ConfirmationCopy.body(req(content = long), "聊天")
        assertTrue(body, body.endsWith("内容:" + "字".repeat(60) + "…"))
        // 模型写的字段原样当纯文本:换行压成空格,控制字符与方向控制符去掉,首尾空白去掉
        val odd = ConfirmationCopy.body(req(content = "  第一行\n第二行‮反向<b>粗</b>\u0007 ", target = " 张三\n"), "聊天")
        assertEquals("在 聊天 · 对象:张三 · 内容:第一行 第二行反向<b>粗</b>", odd)
        assertFalse(odd.contains('\n'))
    }

    // 修复 1 M1:字段里的分隔符、冒号、行分隔符和零宽字符造不出假的字段
    @Test fun fieldsCannotFakeStructure() {
        val r = req(content = "明天见 · 金额:¥0\u2028第二段\u200B尾", target = "张三：李四")
        assertEquals("在 聊天 · 对象:张三∶李四 · 内容:明天见 / 金额∶¥0 第二段尾", ConfirmationCopy.body(r, "聊天"))
    }

    // 修复 1 I3:每次申报的按钮各用各的 PendingIntent(data 不同),旧通知的按钮不会被换成新申报的
    @Test fun intentDataUniquePerRequest() {
        assertTrue(ConfirmationNotifier.intentData(1, true) != ConfirmationNotifier.intentData(2, true))
        assertTrue(ConfirmationNotifier.intentData(1, true) != ConfirmationNotifier.intentData(1, false))
    }

    /** root 命令的确认卡片:标题说是 root 命令,「在哪里」是整部手机,展开时命令整条显示,不截断。 */
    @Test fun rootCommandCardShowsTheWholeCommand() {
        val command = "rm -rf /sdcard/Download/" + "x".repeat(300)
        val r = ConfirmationRequest(IrrevAction.other, "", command, null, ROOT_CONFIRM_APP)
        assertEquals("要你同意:root 命令", ConfirmationCopy.title(r))
        val detail = ConfirmationCopy.detail(r, "root")
        assertTrue(detail, detail.startsWith("在 整部手机(root)\n内容:rm -rf"))
        assertTrue(detail, detail.endsWith("x".repeat(300)))
        assertFalse(detail, "对象" in detail)
    }
}
