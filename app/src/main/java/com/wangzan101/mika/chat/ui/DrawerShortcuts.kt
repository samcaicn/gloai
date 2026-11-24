package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 侧栏顶部的三项主要能力:浏览器、教 Mika、AI 电话,一排三块等宽。 */
@Composable
internal fun DrawerShortcuts(onBrowser: () -> Unit, onTeach: () -> Unit, onCalls: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Shortcut(MikaIcons.Globe, stringResource(R.string.drawer_browser), "drawer-browser", onBrowser, Modifier.weight(1f))
        Shortcut(MikaIcons.Hand, stringResource(R.string.teach_title), "drawer-teach", onTeach, Modifier.weight(1f))
        Shortcut(MikaIcons.Handset, stringResource(R.string.drawer_calls), "drawer-calls", onCalls, Modifier.weight(1f))
    }
}

private val ShortcutShape = RoundedCornerShape(14.dp)

/** 一张浅灰小卡:图标在上、文字在下,都靠左。 */
@Composable
private fun Shortcut(icon: ImageVector, label: String, tag: String, onClick: () -> Unit, modifier: Modifier) {
    val c = MikaTheme.colors
    Column(
        modifier.height(70.dp).clip(ShortcutShape).background(c.fill)
            .clickable(role = Role.Button, onClick = onClick).testTag(tag)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = c.ink)
        Text(label, style = MikaType.Hint, color = c.ink, maxLines = 1)
    }
}

/** 底部的设置:浅灰圆底的齿轮 + 文字。 */
@Composable
internal fun SettingsEntry(onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.height(46.dp).clip(ShortcutShape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp).testTag("drawer-settings"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.fill), contentAlignment = Alignment.Center) {
            Icon(MikaIcons.Gear, null, Modifier.size(18.dp), tint = c.ink)
        }
        Text(stringResource(R.string.settings_title), style = MikaType.Body, color = c.ink)
    }
}
