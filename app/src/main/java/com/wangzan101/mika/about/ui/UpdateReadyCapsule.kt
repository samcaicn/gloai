package com.wangzan101.mika.about.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.delay

@Composable
internal fun BoxScope.UpdateReadyCapsule(
    release: ReleaseInfo,
    onInstall: () -> Unit,
    onTimeout: () -> Unit,
    onDisable: () -> Unit,
    backdrop: GlassBackdrop?,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = MikaTheme.reduceMotion
    val reveal = remember(release.apkUrl) { Animatable(if (reduceMotion) 1f else 0f) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(false) }
    var confirmDisable by remember(release.apkUrl) { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    val dismiss by rememberUpdatedState(onTimeout)
    val held = hovered || pressed || focused || confirmDisable
    LaunchedEffect(reveal, held, resumed, reduceMotion) {
        if (!resumed) return@LaunchedEffect
        if (reduceMotion) reveal.snapTo(1f)
        else reveal.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        if (!held) {
            delay(5000)
            if (reduceMotion) reveal.snapTo(0f)
            else reveal.animateTo(0f, tween(230, easing = FastOutLinearInEasing))
            dismiss()
        }
    }

    val material = UpdateGlassMaterial.Capsule
    val success = if (MikaTheme.colors.isDark) Color(0xFF80E5A1) else Color(0xFF237B42)
    UpdateGlassSurface(
        material, backdrop,
        modifier.size(material.width.dp, material.height.dp)
            .testTag("update-ready-capsule")
            .graphicsLayer {
                alpha = reveal.value
                translationY = if (reduceMotion) 0f else -52.dp.toPx() * (1f - reveal.value)
            }
            .onFocusChanged { focused = it.hasFocus }.focusGroup().hoverable(interaction),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).fillMaxSize()
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onInstall)
                    .padding(start = 8.dp, end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(31.dp).background(success.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(MikaIcons.Check, null, Modifier.size(14.dp), tint = success)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.about_auto_update_ready, release.version), color = UpdateGlassInk,
                        style = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.about_update_install), color = UpdateGlassSecondaryInk,
                        style = MikaType.Micro, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            UpdateGlassClose(
                label = stringResource(R.string.about_auto_update_disable),
                onClick = { confirmDisable = true },
                modifier = Modifier.align(Alignment.Top),
            )
        }
    }

    if (confirmDisable) {
        val close = { confirmDisable = false }
        val dialog = UpdateGlassMaterial.Confirmation
        val title = stringResource(R.string.about_auto_update_confirm_title)
        BackHandler(onBack = close)
        Box(Modifier.matchParentSize().testTag("update-glass-confirmation"), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().background(UpdateGlassScrim.copy(alpha = dialog.scrim))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = stringResource(R.string.cancel), onClick = close))
            UpdateGlassSurface(dialog, backdrop,
                Modifier.size(dialog.width.dp, dialog.height.dp).semantics { paneTitle = title },
            ) {
                Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 15.dp, bottom = 12.dp)) {
                    Text(title, Modifier.padding(end = 21.dp), color = UpdateGlassInk, style = MikaType.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.about_auto_update_confirm_detail, release.version),
                        Modifier.weight(1f).padding(top = 7.dp), color = UpdateGlassSecondaryInk,
                        style = MikaType.Micro, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        UpdateGlassAction(stringResource(R.string.about_auto_update_now), primary = true,
                            onClick = { close(); onInstall() }, modifier = Modifier.weight(1f))
                        UpdateGlassAction(stringResource(R.string.about_auto_update_disable), primary = false,
                            onClick = { close(); onDisable() }, modifier = Modifier.weight(1f))
                    }
                }
                UpdateGlassClose(stringResource(R.string.cancel), close, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
private fun UpdateGlassClose(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp, 36.dp).clip(CircleShape)
        .clickable(role = Role.Button, onClick = onClick).padding(top = 8.dp, end = 3.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Icon(MikaIcons.Close, label, Modifier.size(13.dp), tint = UpdateGlassSecondaryInk)
    }
}

@Composable
private fun UpdateGlassAction(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dark = MikaTheme.colors.isDark
    val fill = when {
        primary && dark -> Color(0xFFB4DBB3)
        primary -> Color(0xFF294332)
        dark -> Color.White.copy(alpha = 0.13f)
        else -> Color.White.copy(alpha = 0.48f)
    }
    Box(modifier.height(36.dp).clip(MikaShapes.Chip)
        .background(fill)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) (if (dark) Color(0xFF172E21) else Color.White) else UpdateGlassInk,
            style = MikaType.Micro.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
