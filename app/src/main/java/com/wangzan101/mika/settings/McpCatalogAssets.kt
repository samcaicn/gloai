package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.core.cap.mcp.McpCatalogParser

/**
 * 随 App 打包的 MCP 服务目录:读 `assets/mcp/catalog.json`,宽松解析(单条损坏跳过,不阻塞其余条目)。
 * 目录只含声明式、无秘密的预设;秘密在连接时才由用户输入并经加密存储投递。
 */
object McpCatalogAssets {
    const val ASSET_PATH = "mcp/catalog.json"

    /** 目录文案必须覆盖的 UI 语言;服务专有名称不翻译。 */
    val REQUIRED_LOCALES: List<String> = listOf("en", "zh", "zh-Hant", "ar", "fa", "ja", "ko", "es", "pt", "ru", "id", "tr")

    fun load(context: Context): McpCatalog = parse(
        context.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) },
    )

    /** 宽松解析;单条坏预设被跳过,目录级错误照常抛出。 */
    fun parse(text: String): McpCatalog = McpCatalogParser.parseLenient(text)
}
