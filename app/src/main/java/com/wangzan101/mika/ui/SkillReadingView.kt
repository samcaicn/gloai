package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes

internal data class ReadingSection(val title: MdBlock.Heading?, val blocks: List<MdBlock>)

/** Group only top-level H2s. Keep the preamble, nested lists and fenced examples in their original order. */
internal fun readingSections(blocks: List<MdBlock>): List<ReadingSection> = buildList {
    var title: MdBlock.Heading? = null
    var start = 0
    for ((i, block) in blocks.withIndex()) {
        if (block !is MdBlock.Heading || block.level != 2) continue
        if (title != null || start < i) add(ReadingSection(title, blocks.subList(start, i)))
        title = block
        start = i + 1
    }
    if (title != null || start < blocks.size) add(ReadingSection(title, blocks.subList(start, blocks.size)))
}

@Composable
internal fun SkillReadingView(markdown: String) {
    val c = MikaTheme.colors
    val body = MikaType.Body.copy(color = c.ink2)
    MarkdownDocument(markdown) { blocks ->
        Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
            for ((title, contents) in remember(blocks) { readingSections(blocks) }) {
                val label = title?.text?.text.orEmpty()
                val caution = label.startsWith("容易出错的地方")
                val icon = when {
                    label.startsWith("入口") -> MikaIcons.Pin
                    label.startsWith("步骤") -> MikaIcons.Route
                    caution -> MikaIcons.Info
                    label.startsWith("怎样算办成") -> MikaIcons.Check
                    label.startsWith("什么时候停下交给用户") -> MikaIcons.Hand
                    else -> null
                }
                val panel = if (caution) Modifier.background(c.warnSoft, MikaShapes.Tile).padding(12.dp) else Modifier
                Column(Modifier.fillMaxWidth().then(panel), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (title != null) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = if (caution) c.warn else c.accent)
                        Text(title.text, Modifier.semantics { heading() }, style = MikaType.BodyStrong, color = c.ink)
                    }
                    for (block in contents) {
                        if (block is MdBlock.ListBlock && block.start != null && label.startsWith("步骤")) {
                            StepList(block, block.start, body)
                        } else {
                            MarkdownBlocks(listOf(block), body)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepList(list: MdBlock.ListBlock, start: Int, style: TextStyle) {
    val c = MikaTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        list.items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                    .background(c.accentSoft, CircleShape).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text((start + index).toString(), style = MikaType.Label.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                }
                MarkdownBlocks(item, style, Modifier.weight(1f), depth = 1)
            }
        }
    }
}
