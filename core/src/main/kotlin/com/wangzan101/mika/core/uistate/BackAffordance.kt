package com.wangzan101.mika.core.uistate

/**
 * 从一屏的可交互元素里认出「返回」控件,给 back 注入失效时的降级用。
 *
 * 识别顺序(越靠前越可信):名字精确命中返回词 → 名字包含返回词 → 左上角无名字的图标型控件。
 * 认不到就返回 null,让调用方决定要不要硬点——不在这里盲目点。
 */
object BackAffordance {
    private val EXACT = setOf(
        "返回", "后退", "回退", "返回键", "向上导航",
        "back", "navigate up", "previous",
        "←", "↩", "‹", "＜",
    )
    // 不含 "back":它的子串会误伤 feedback/backup/background
    private val CONTAINS = listOf("返回", "后退", "回退", "向上导航", "previous", "navigate up")
    private val ICON_ROLES = setOf("ImageButton", "AppCompatImageButton", "ImageView", "Image")
    // 返回控件的资源 id 末段(去掉包名和 /id/ 前缀)
    private val RESOURCE_SEGMENTS = setOf("back", "navigate_up", "arrow_back", "btn_back", "toolbar_back", "action_bar_up")

    fun find(elements: List<UiElement>, screenWidth: Int, screenHeight: Int): UiElement? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val clickable = elements.filter { it.clickable }
        clickable.firstOrNull { isExact(it.name) }?.let { return it }
        clickable.firstOrNull { isBackResource(it.resourceId) }?.let { return it }
        clickable.firstOrNull { isBackLike(it.name) }?.let { return it }
        return clickable.firstOrNull { it.name.isBlank() && isTopLeftIcon(it, screenWidth, screenHeight) }
    }

    private fun isExact(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.isNotEmpty() && n in EXACT
    }

    private fun isBackLike(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.isNotEmpty() && CONTAINS.any { n.contains(it) }
    }

    private fun isBackResource(id: String?): Boolean {
        if (id == null) return false
        return id.substringAfterLast('/').lowercase() in RESOURCE_SEGMENTS
    }

    private fun isTopLeftIcon(e: UiElement, screenWidth: Int, screenHeight: Int): Boolean =
        e.role in ICON_ROLES &&
            e.centerX <= screenWidth * TOP_LEFT_X &&
            e.centerY <= screenHeight * TOP_LEFT_Y

    private const val TOP_LEFT_X = 0.2
    private const val TOP_LEFT_Y = 0.15
}
