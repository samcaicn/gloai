package com.wangzan101.mika.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import com.wangzan101.mika.ui.theme.MikaTheme

/** 一个阶段的画法:小胶囊里的字、字色、胶囊底色,列表和小标签里的圆点颜色。 */
internal data class DeskPhaseStyle(val label: String, val fg: Color, val bg: Color, val dot: Color)

@Composable
internal fun deskPhaseStyle(view: DeskTaskView): DeskPhaseStyle {
    val c = MikaTheme.colors
    return when (view.state) {
        DeskTaskPhase.Running -> DeskPhaseStyle(stringResource(R.string.desk_phase_running), c.accent, c.accentSoft, c.accent)
        DeskTaskPhase.Queued -> DeskPhaseStyle(
            view.position?.let { stringResource(R.string.desk_phase_queued_at, it) } ?: stringResource(R.string.desk_phase_queued),
            c.ink2, c.fill, c.ink3,
        )
        DeskTaskPhase.NeedsYou -> DeskPhaseStyle(stringResource(R.string.desk_phase_needs_you), c.warn, c.warnSoft, c.warn)
        DeskTaskPhase.Done -> DeskPhaseStyle(stringResource(R.string.desk_phase_done), c.ok, c.okSoft, c.ok)
        DeskTaskPhase.Failed -> DeskPhaseStyle(stringResource(R.string.desk_phase_failed), c.bad, c.badSoft, c.bad)
        DeskTaskPhase.Cancelled -> DeskPhaseStyle(stringResource(R.string.desk_phase_cancelled), c.ink3, c.fill, c.ink3)
    }
}

/** 还算「在办」的阶段:在做、排队、要你处理。 */
internal val DeskTaskView.inProgress: Boolean
    get() = state == DeskTaskPhase.Running || state == DeskTaskPhase.Queued || state == DeskTaskPhase.NeedsYou
