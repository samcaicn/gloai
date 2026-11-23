package com.wangzan101.mika.core.screen.uistate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 返回控件的识别：back 注入失效时,从一屏元素里认出「返回」控件做降级点击。
 * 只认名字精确/包含返回词的可点元素,或左上角无名字的图标型控件;认不到返回 null(不硬点)。
 */
class BackAffordanceTest {
    private fun el(
        ref: String,
        name: String,
        role: String = "TextView",
        clickable: Boolean = false,
        centerX: Int = 500,
        centerY: Int = 1000,
        resourceId: String? = null,
    ) = UiElement(
        ref = ref, role = role, name = name,
        centerX = centerX, centerY = centerY,
        bounds = listOf(centerX - 10, centerY - 10, centerX + 10, centerY + 10),
        resourceId = resourceId, clickable = clickable, editable = false, scrollable = false,
    )

    @Test fun picksClickableBackLabel() {
        val list = listOf(
            el("@e1", "设置", role = "Button", clickable = true, centerX = 500, centerY = 500),
            el("@e2", "返回", role = "ImageButton", clickable = true, centerX = 50, centerY = 80),
        )
        assertEquals("@e2", BackAffordance.find(list, 1080, 2400)?.ref)
    }

    @Test fun backLabelIsCaseInsensitive() {
        val list = listOf(el("@e1", "Back", role = "ImageButton", clickable = true, centerX = 50, centerY = 80))
        assertEquals("@e1", BackAffordance.find(list, 1080, 2400)?.ref)
    }

    @Test fun picksBackByResourceIdWhenUnlabeled() {
        val list = listOf(
            el("@e1", "", role = "ImageButton", clickable = true, centerX = 50, centerY = 80, resourceId = "com.foo:id/action_bar_up"),
            el("@e2", "搜索", role = "Button", clickable = true, centerX = 400, centerY = 80),
        )
        assertEquals("@e1", BackAffordance.find(list, 1080, 2400)?.ref)
    }

    @Test fun ignoresUnrelatedResourceId() {
        val list = listOf(el("@e1", "", role = "ImageButton", clickable = true, centerX = 500, centerY = 500, resourceId = "com.foo:id/refresh"))
        assertNull(BackAffordance.find(list, 1080, 2400))
    }

    @Test fun ignoresNonClickableBackText() {
        val list = listOf(el("@e1", "返回", role = "TextView", clickable = false, centerX = 50, centerY = 80))
        assertNull(BackAffordance.find(list, 1080, 2400))
    }

    @Test fun fallsBackToTopLeftIconWhenNoLabel() {
        val list = listOf(
            el("@e1", "", role = "ImageButton", clickable = true, centerX = 40, centerY = 70),
            el("@e2", "搜索", role = "Button", clickable = true, centerX = 400, centerY = 80),
        )
        assertEquals("@e1", BackAffordance.find(list, 1080, 2400)?.ref)
    }

    @Test fun exactLabelPreferredOverContains() {
        val list = listOf(
            el("@e1", "返回首页", role = "Button", clickable = true, centerX = 500, centerY = 500),
            el("@e2", "返回", role = "ImageButton", clickable = true, centerX = 50, centerY = 80),
        )
        assertEquals("@e2", BackAffordance.find(list, 1080, 2400)?.ref)
    }

    @Test fun feedbackDoesNotCountAsBack() {
        val list = listOf(el("@e1", "Feedback", role = "Button", clickable = true, centerX = 500, centerY = 500))
        assertNull(BackAffordance.find(list, 1080, 2400))
    }

    @Test fun noCandidateReturnsNull() {
        val list = listOf(el("@e1", "正文", role = "TextView", clickable = false, centerX = 500, centerY = 1000))
        assertNull(BackAffordance.find(list, 1080, 2400))
    }
}
