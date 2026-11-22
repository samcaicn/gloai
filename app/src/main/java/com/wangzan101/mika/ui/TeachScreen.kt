package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.R
import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.teach.TeachPhase
import com.wangzan101.mika.core.teach.teachGoalAccepted
import com.wangzan101.mika.teach.TeachNotice
import com.wangzan101.mika.teach.TeachRuntime
import com.wangzan101.mika.teach.TeachUi
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

@Composable
fun TeachScreen(runtime: TeachRuntime, onBack: () -> Unit, onOpenDrafts: () -> Unit) {
    val ui by runtime.ui.collectAsState()
    val session = ui.session
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.teach_title), MikaIcons.Back, onBack, stringResource(R.string.back))
        Notice(ui.notice)
        when (session?.phase) {
            null, TeachPhase.Idle, TeachPhase.Ready -> GoalForm(runtime, ui.hint)
            TeachPhase.Recording, TeachPhase.Paused -> Live(runtime, session.phase, session.steps.size)
            TeachPhase.Interrupted -> Interrupted(runtime)
            TeachPhase.Reviewing, TeachPhase.Drafting -> Review(runtime, ui)
            TeachPhase.Saved -> Saved(runtime, onOpenDrafts)
        }
    }
}

@Composable
private fun Notice(notice: TeachNotice?) {
    val text = when (notice) {
        TeachNotice.Busy -> stringResource(R.string.teach_busy)
        TeachNotice.NoAccessibility -> stringResource(R.string.teach_need_a11y)
        TeachNotice.NeedGoal -> stringResource(R.string.teach_need_goal)
        TeachNotice.SecretGoal -> stringResource(R.string.teach_secret_goal)
        TeachNotice.DraftFailed -> stringResource(R.string.teach_draft_failed)
        TeachNotice.NoSteps -> stringResource(R.string.teach_no_steps)
        TeachNotice.SecretNote -> stringResource(R.string.teach_sensitive_note)
        TeachNotice.NoVisibleStop -> stringResource(R.string.teach_no_visible)
        TeachNotice.WatchLost -> stringResource(R.string.teach_watch_lost)
        null -> return
    }
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MikaTheme.colors.warn, style = MikaType.Body)
}

@Composable
private fun GoalForm(runtime: TeachRuntime, hint: String?) {
    var goal by remember { mutableStateOf(hint.orEmpty()) }
    var armed by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf<TeachNotice?>(null) }
    LaunchedEffect(hint) { if (goal.isEmpty() && !hint.isNullOrEmpty()) goal = hint }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("teach-form"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Notice(local)
        if (!armed) {
            Text(stringResource(R.string.teach_disclosure), style = MikaType.Body, color = MikaTheme.colors.ink)
            OutlinedTextField(
                goal, { goal = it; local = null },
                Modifier.fillMaxWidth().testTag("teach-goal"),
                label = { Text(stringResource(R.string.teach_goal_label)) },
                placeholder = { Text(stringResource(R.string.teach_goal_hint)) },
            )
            MikaButton(stringResource(R.string.teach_continue), {
                local = when {
                    goal.isBlank() -> TeachNotice.NeedGoal
                    !teachGoalAccepted(goal) -> TeachNotice.SecretGoal
                    else -> null
                }
                if (local == null) armed = true
            }, kind = ButtonKind.Primary, size = ButtonSize.Medium)
        } else {
            Text(stringResource(R.string.teach_ready_body), style = MikaType.Body, color = MikaTheme.colors.ink)
            Text(goal, style = MikaType.Body, color = MikaTheme.colors.ink2)
            val context = LocalContext.current
            MikaButton(stringResource(R.string.teach_start), {
                local = runtime.start(goal)
                if (local == null) {
                    armed = false
                    (context as? Activity)?.moveTaskToBack(true)
                }
            }, kind = ButtonKind.Primary, size = ButtonSize.Medium, modifier = Modifier.testTag("teach-start"))
        }
    }
}

@Composable
private fun Live(runtime: TeachRuntime, phase: TeachPhase, steps: Int) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(if (phase == TeachPhase.Recording) R.string.teach_learning else R.string.teach_paused),
            style = MikaType.Title, color = MikaTheme.colors.ink,
        )
        Text(stringResource(R.string.teach_notification), style = MikaType.Body, color = MikaTheme.colors.ink2)
        Text(steps.toString(), style = MikaType.Hint, color = MikaTheme.colors.ink3)
        val context = LocalContext.current
        if (phase == TeachPhase.Recording) MikaButton(stringResource(R.string.teach_pause), runtime::pause, size = ButtonSize.Medium)
        else MikaButton(stringResource(R.string.teach_resume), {
            if (runtime.resume() == null) (context as? Activity)?.moveTaskToBack(true)
        }, kind = ButtonKind.Primary, size = ButtonSize.Medium)
        MikaButton(stringResource(R.string.teach_end), runtime::end, kind = ButtonKind.Primary, size = ButtonSize.Medium)
        MikaButton(stringResource(R.string.teach_discard), runtime::discard, kind = ButtonKind.Tonal, size = ButtonSize.Medium)
    }
}

@Composable
private fun Interrupted(runtime: TeachRuntime) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val context = LocalContext.current
        Text(stringResource(R.string.teach_interrupted), style = MikaType.Body, color = MikaTheme.colors.ink)
        MikaButton(stringResource(R.string.teach_resume), {
            if (runtime.resume() == null) (context as? Activity)?.moveTaskToBack(true)
        }, kind = ButtonKind.Primary, size = ButtonSize.Medium)
        MikaButton(stringResource(R.string.teach_end), runtime::end, size = ButtonSize.Medium)
        MikaButton(stringResource(R.string.teach_discard), runtime::discard, kind = ButtonKind.Tonal, size = ButtonSize.Medium)
    }
}

@Composable
private fun Review(runtime: TeachRuntime, ui: TeachUi) {
    var confirm by remember { mutableStateOf(false) }
    val draft = ui.draft
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (draft == null) {
            Text(stringResource(R.string.teach_summarizing), style = MikaType.Title, color = MikaTheme.colors.ink)
            if (ui.session?.phase == TeachPhase.Reviewing) {
                Text(stringResource(R.string.teach_draft_failed), style = MikaType.Body, color = MikaTheme.colors.ink2)
                MikaButton(
                    stringResource(R.string.teach_retry),
                    runtime::retry,
                    kind = ButtonKind.Primary,
                    size = ButtonSize.Medium,
                    modifier = Modifier.testTag("teach-retry"),
                )
            }
        } else {
            (FrontMatter.parse(draft.markdown).first["display"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { Text(it, style = MikaType.Title, color = MikaTheme.colors.ink) }
            SkillReadingView(skillPreviewBody(draft.markdown))
            MikaButton(
                stringResource(R.string.teach_accept),
                { runtime.accept() },
                kind = ButtonKind.Primary,
                size = ButtonSize.Medium,
                modifier = Modifier.testTag("teach-accept"),
            )
        }
        if (confirm) {
            Text(stringResource(R.string.teach_discard_body), color = MikaTheme.colors.warn)
            MikaButton(stringResource(R.string.teach_discard), runtime::discard, kind = ButtonKind.Tonal, size = ButtonSize.Medium)
        } else {
            MikaButton(stringResource(R.string.teach_discard), { confirm = true }, kind = ButtonKind.Tonal, size = ButtonSize.Medium)
        }
    }
}

@Composable
private fun Saved(runtime: TeachRuntime, onOpenDrafts: () -> Unit) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.teach_draft_ready), style = MikaType.Body, color = MikaTheme.colors.ink)
        MikaButton(stringResource(R.string.teach_open_drafts), onOpenDrafts, kind = ButtonKind.Primary, size = ButtonSize.Medium)
        MikaButton(stringResource(R.string.teach_discard), runtime::discard, kind = ButtonKind.Tonal, size = ButtonSize.Medium)
    }
}
