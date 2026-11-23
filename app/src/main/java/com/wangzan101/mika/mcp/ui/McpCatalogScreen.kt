package com.wangzan101.mika.mcp.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.core.cap.mcp.McpPreset
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SearchField
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.memory.ui.Link
import com.wangzan101.mika.ui.Route

/**
 * 目录的粗分类:25 个细分 key 归并成约 10 个粗类,stdio(在本机运行)单独一类。
 * 粗类只决定「服务放哪个分类页」;细分 key 仍留在目录数据里,不影响连接。
 */
internal object McpCategory {
    const val ALL = "all"
    const val AI = "ai"
    const val DATA = "data"
    const val DEV = "dev"
    const val COMM = "comm"
    const val PROD = "prod"
    const val DESIGN = "design"
    const val CLOUD = "cloud"
    const val WEB = "web"
    const val MAPS = "maps"
    const val LOCAL = "local"

    /** 分类胶囊顺序(不含「全部」,它固定在最前)。 */
    val order = listOf(AI, DATA, DEV, COMM, PROD, DESIGN, CLOUD, WEB, MAPS, LOCAL)

    private val fineToCoarse = mapOf(
        "llm" to AI, "vector" to AI, "memory" to AI,
        "database" to DATA, "analytics" to DATA, "files" to DATA,
        "code" to DEV, "git" to DEV, "ci" to DEV, "security" to DEV,
        "chat" to COMM, "email" to COMM, "crm" to COMM,
        "tasks" to PROD, "docs" to PROD, "calendar" to PROD, "time" to PROD, "payments" to PROD,
        "design" to DESIGN, "media" to DESIGN,
        "cloud" to CLOUD,
        "search" to WEB, "browser" to WEB, "web" to WEB,
        "maps" to MAPS,
    )

    fun of(preset: McpPreset): String =
        if (preset.transport == "stdio") LOCAL else (fineToCoarse[preset.category] ?: PROD)

    fun icon(category: String): ImageVector = when (category) {
        AI -> MikaIcons.Spark
        DATA -> MikaIcons.Chart
        DEV -> MikaIcons.Code
        COMM -> MikaIcons.Chats
        PROD -> MikaIcons.ListChecks
        DESIGN -> MikaIcons.Photo
        CLOUD -> MikaIcons.Globe
        WEB -> MikaIcons.Search
        MAPS -> MikaIcons.Route
        LOCAL -> MikaIcons.Terminal
        else -> MikaIcons.Link
    }

    fun labelRes(category: String): Int = when (category) {
        AI -> R.string.mcp_category_ai
        DATA -> R.string.mcp_category_data
        DEV -> R.string.mcp_category_dev
        COMM -> R.string.mcp_category_comm
        PROD -> R.string.mcp_category_prod
        DESIGN -> R.string.mcp_category_design
        CLOUD -> R.string.mcp_category_cloud
        WEB -> R.string.mcp_category_web
        MAPS -> R.string.mcp_category_maps
        LOCAL -> R.string.mcp_category_local
        else -> R.string.mcp_category_all
    }
}

/** 设置 › MCP 服务器 › 服务目录:搜索、按粗分类筛选、点开一条进引导式连接;自定义入口始终可见。 */
@Composable
fun McpCatalogScreen(
    catalog: McpCatalog,
    onOpenPreset: (String) -> Unit,
    onCustomServer: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val langTag = remember(context) { AppLanguage.resolved(context).tag ?: "en" }
    val logos = remember(context) { McpLogos.load(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(McpCategory.ALL) }
    val c = MikaTheme.colors

    val q = query.trim()
    val filtered = remember(catalog, q, category, langTag) {
        catalog.presets.filter { p ->
            val cat = McpCategory.of(p)
            (category == McpCategory.ALL || cat == category) &&
                (q.isEmpty() || p.name.contains(q, ignoreCase = true) ||
                    p.summary.resolve(langTag).contains(q, ignoreCase = true))
        }
    }

    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(stringResource(R.string.mcp_catalog_title), MikaIcons.Back, onBack, stringResource(R.string.back))
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
                SearchField(query, { query = it }, stringResource(R.string.mcp_catalog_search_hint), Modifier.testTag("mcp-catalog-search"))
            }
            CategoryChips(category) { category = it }
            GroupCard(Modifier.padding(top = 12.dp)) {
                ListRow(
                    MikaIcons.Link, stringResource(R.string.mcp_catalog_custom), first = true,
                    titleColor = c.accent, tone = TileTone.Accent, onClick = onCustomServer,
                    modifier = Modifier.testTag("mcp-catalog-custom"),
                ) { RowChevron() }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Column {
                            EmptyNote(stringResource(R.string.mcp_catalog_no_results))
                            FooterNote(stringResource(R.string.mcp_catalog_no_results_sub))
                        }
                    }
                } else {
                    itemsIndexed(filtered, key = { _, p -> p.id }) { i, p ->
                        val logo = logos.logo(p.id)
                        Box(
                            Modifier.fillMaxWidth().clip(rowShape(i, filtered.size)).background(c.surface),
                        ) {
                            ListRow(
                                logo ?: McpCategory.icon(McpCategory.of(p)), p.name,
                                subtitle = p.summary.resolve(langTag),
                                first = i == 0,
                                brandTile = logos.tile(p.id),
                                onClick = { onOpenPreset(p.id) },
                                modifier = Modifier.testTag("mcp-preset-${p.id}"),
                            ) { RowChevron() }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SelectChip(stringResource(R.string.mcp_category_all), selected == McpCategory.ALL, { onSelect(McpCategory.ALL) }, small = true)
        McpCategory.order.forEach { cat ->
            SelectChip(stringResource(McpCategory.labelRes(cat)), selected == cat, { onSelect(cat) }, small = true)
        }
    }
}

/** 一张卡拆成懒加载的行时,每行的圆角:首行上圆、末行下圆。 */
private fun rowShape(i: Int, n: Int): RoundedCornerShape {
    val r = MikaRadius.Card
    return RoundedCornerShape(
        topStart = if (i == 0) r else 0.dp, topEnd = if (i == 0) r else 0.dp,
        bottomStart = if (i == n - 1) r else 0.dp, bottomEnd = if (i == n - 1) r else 0.dp,
    )
}
