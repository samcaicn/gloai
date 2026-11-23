package com.wangzan101.mika.mcp.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.wangzan101.mika.settings.ui.models.use

/**
 * 一个品牌标志:24×24 视口里的若干 path(`d` 为旧版单 path 写法)、图形颜色、填充规则。
 * [tile] 是太亮的品牌色:此时它做底色、图形用深色;为空则用默认浅色底。
 */
@Serializable
data class McpLogoData(
    val color: String,
    val tile: String? = null,
    val paths: List<String> = emptyList(),
    val d: String = "",
    val evenOdd: Boolean = false,
) {
    val allPaths: List<String> get() = (paths + d).filter { it.isNotBlank() }
}

@Serializable
private data class McpLogosDto(val formatVersion: Int, val logos: Map<String, McpLogoData>)

/**
 * 品牌 logo 资产:解析 `assets/mcp/mcp-logos.json`,把每个品牌的 path + 官方品牌色懒加载成
 * 自着色的 [ImageVector](不引图片加载库)。`logo(id)` 未命中返回 null,由调用方退回分类图标。
 */
class McpLogos private constructor(private val logos: Map<String, McpLogoData>) {
    private val cache = mutableMapOf<String, ImageVector?>()

    /** 有品牌标时它的底色(品牌自带的亮色底,否则默认浅色底);没有品牌标返回 null。 */
    fun tile(id: String): Color? {
        val data = logos[id] ?: return null
        return data.tile?.let(::parseColor) ?: DefaultTile
    }

    fun logo(id: String): ImageVector? {
        val data = logos[id] ?: return null
        return cache.getOrPut(id) { runCatching { vector(data) }.getOrNull() }
    }

    private fun vector(data: McpLogoData): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            val fill = SolidColor(parseColor(data.color))
            val rule = if (data.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
            data.allPaths.forEach { d ->
                addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = fill, pathFillType = rule)
            }
        }.build()

    companion object {
        const val ASSET_PATH = "mcp/mcp-logos.json"
        private val json = Json { ignoreUnknownKeys = true }
        private val hex = Regex("^#?[0-9A-Fa-f]{6}$")

        /** 品牌标的默认底:两种模式都用固定浅色,深色品牌标在深色模式下也可读(生成器按它算对比度)。 */
        val DefaultTile = Color(0xFFF4F4F5)

        /** 宽松解析:整体损坏返回空;单条没有 path 或 color 非法则跳过该条。 */
        fun parse(text: String): McpLogos = try {
            val dto = json.decodeFromString<McpLogosDto>(text)
            McpLogos(dto.logos.filterValues { it.allPaths.isNotEmpty() && hex.matches(it.color) && (it.tile == null || hex.matches(it.tile)) })
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
