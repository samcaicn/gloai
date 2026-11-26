package com.tuptup.speech.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 暖白风：米白底、白卡、陶土色强调。所有页面统一引用这套色值。
val Accent = Color(0xFFC4633A)          // 陶土橙（主按钮/强调）
val AccentDeep = Color(0xFF9E4B29)
val AccentSoft = Color(0xFFF7E9DD)      // 浅橙底（选中态）
val Bg = Color(0xFFFBF8F3)              // 暖白背景
val Card = Color(0xFFFFFFFF)            // 卡片白
val Card2 = Color(0xFFF5EFE7)           // 次卡片奶油色
val Line = Color(0xFFE8DED1)            // 分割线
val Muted = Color(0xFF8B8177)           // 次级文字
val TextMain = Color(0xFF2F2A25)        // 主文字
val Success = Color(0xFF4F9A6B)
val Danger = Color(0xFFD2584F)

@Composable
fun TuptupTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Accent,
        onPrimary = Color.White,
        primaryContainer = AccentSoft,
        onPrimaryContainer = AccentDeep,
        secondary = Success,
        onSecondary = Color.White,
        surface = Card,
        onSurface = TextMain,
        surfaceVariant = Card2,
        onSurfaceVariant = Muted,
        background = Bg,
        onBackground = TextMain,
        error = Danger,
        outline = Line
    )
    MaterialTheme(colorScheme = colors, content = content)
}
