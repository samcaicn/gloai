package com.wangzan101.mika.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class McpLogoData(val d: String, val color: String)

@Serializable
private data class McpLogosDto(val formatVersion: Int, val logos: Map<String, McpLogoData>)

/**
 * 品牌 logo 资产:解析 `assets/mcp/mcp-logos.json`,把每个品牌的 path `d` + 官方品牌色懒加载成
 * 自着色的 [ImageVector](不引图片加载库)。`logo(id)` 未命中返回 null,由调用方退回分类图标。
 */
class McpLogos private constructor(private val logos: Map<String, McpLogoData>) {
    private val cache = mutableMapOf<String, ImageVector?>()

    fun logo(id: String): ImageVector? {
        val data = logos[id] ?: return null
        return cache.getOrPut(id) { runCatching { vector(data) }.getOrNull() }
    }

    private fun vector(data: McpLogoData): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            addPath(pathData = PathParser().parsePathString(data.d).toNodes(), fill = SolidColor(parseColor(data.color)))
        }.build()

    companion object {
        const val ASSET_PATH = "mcp/mcp-logos.json"
        private val json = Json { ignoreUnknownKeys = true }
        private val hex = Regex("^#?[0-9A-Fa-f]{6}$")

        /** 宽松解析:整体损坏返回空;单条 `d` 为空或 color 非法则跳过该条。 */
        fun parse(text: String): McpLogos = try {
            val dto = json.decodeFromString<McpLogosDto>(text)
            McpLogos(dto.logos.filterValues { it.d.isNotBlank() && hex.matches(it.color) })
        } catch (e: Exception) {
            McpLogos(emptyMap())
        }

        fun load(context: Context): McpLogos = try {
            parse(context.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (e: Exception) {
            McpLogos(emptyMap())
        }

        private fun parseColor(c: String): Color = Color(0xFF000000L or c.removePrefix("#").toLong(16))
    }
}
