package com.wangzan101.mika.chat.ui


import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.foundation.layout.size

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaShapes

internal const val MENU_IN_MS = 200
internal const val MENU_OUT_MS = 140
internal val DrawerWidth = 360.dp

/** 卡片先纵向缩短,轮廓在屏幕上仍保持圆弧,不把上下角压成椭圆。 */
internal class DrawerCardShape(private val progress: Float) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { 32.dp.toPx() } * progress
        return Outline.Rounded(RoundRect(
            rect = Rect(Offset.Zero, size),
            cornerRadius = CornerRadius(radius, radius / (1f - 0.04f * progress)),
        ))
    }
}
internal val NavShape = MikaShapes.Chip
internal val SectionText = MikaType.Hint.copy(fontWeight = FontWeight.Medium)
internal val ConversationTitle = MikaType.Body.copy(fontWeight = FontWeight.Medium)
internal val ConversationTime = MikaType.Hint
internal val ConversationTag = MikaType.Micro.copy(fontWeight = FontWeight.Medium)
