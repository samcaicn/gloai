package com.wangzan101.mika.teach.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import com.wangzan101.mika.ui.components.PlainListTheme
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.skills.PersonalSkill
import com.wangzan101.mika.core.learn.teach.TeachPhase
import com.wangzan101.mika.core.learn.teach.TeachSession
import com.wangzan101.mika.teach.TeachNotice
import com.wangzan101.mika.teach.TeachRuntime
import com.wangzan101.mika.teach.TeachUi
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.chat.ui.draft
import com.wangzan101.mika.skills.ui.SkillReadingView
import com.wangzan101.mika.skills.ui.skillPreviewBody

/** 总结页要显示的东西，和 runtime 分开，便于单独预览与测试。 */
internal data class TeachSummaryView(
    val display: String,
    val description: String,
    val body: String,
    val folded: List<String>,
    val basic: Boolean,
)

@Composable
fun TeachScreen(runtime: TeachRuntime, onBack: () -> Unit) {
    val ui by runtime.ui.collectAsState()
    val session = ui.session
    PlainListTheme {
    Column(Modifier.fillMaxSize().background(MikaTheme.colors.bg)) {
        TopBar(stringResource(R.string.teach_title), MikaIcons.Back, onBack, stringResource(R.string.back))
        Notice(ui.notice)
        val draft = ui.draft
        when (session?.phase) {
            null, TeachPhase.Idle, TeachPhase.Ready -> Intro(ui.hint.orEmpty(), runtime)
            TeachPhase.Recording, TeachPhase.Paused -> Live(runtime, session.phase, session.steps.size)
            TeachPhase.Interrupted -> Interrupted(runtime)
            TeachPhase.Drafting -> Summarizing(failed = false, notice = ui.notice, onRetry = runtime::retry, onDiscard = runtime::discard)
            TeachPhase.Reviewing -> if (draft != null) {
                TeachSummaryPage(
                    view = summaryView(draft, ui),
                    onAccept = { runtime.accept() },
                    onRedo = { teachAgain(runtime, session.goal) },
                    onDiscard = runtime::discard,
                )
            } else {
                Summarizing(failed = true, notice = ui.notice, onRetry = runtime::retry, onDiscard = runtime::discard)
            }
            // 收下以后带着新目标再进来:问的是新目标,不该停在上一份的「已学会」
            TeachPhase.Saved -> if (showDone(session, ui.hint)) TeachDonePage(
                name = ui.acceptedName ?: metaValue(draft, "display") ?: session.goal,
                description = metaValue(draft, "description").orEmpty(),
                onBackToChat = onBack,
                onTeachAgain = { teachAgain(runtime, session.goal) },
            ) else Intro(ui.hint.orEmpty(), runtime)
        }
    }
    }
}

/** 已经收下、且没有带着新目标进来时，才停在「已学会」页。 */
internal fun showDone(session: TeachSession?, hint: String?): Boolean =
    session?.phase == TeachPhase.Saved && (hint.isNullOrBlank() || hint == session.goal)

@Composable
private fun Intro(hint: String, runtime: TeachRuntime) {
    val context = LocalContext.current
    TeachIntroPage(hint) { goal ->
        val problem = runtime.start(goal)
        if (problem == null) (context as? Activity)?.moveTaskToBack(true)
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
internal fun TeachIntroPage(initial: String, onStart: (String) -> Unit) {
    val c = MikaTheme.colors
    var goal by remember(initial) { mutableStateOf(initial) }
    var detailed by remember { mutableStateOf(false) }
    var empty by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("teach-form"),
        ) {
            // 开头:图标、一句话说清这是什么
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(MikaIcons.Hand, null, Modifier.size(34.dp), tint = c.ink)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.teach_intro_title), style = MikaType.Title, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.teach_intro_body), style = MikaType.Caption, color = c.ink3, textAlign = TextAlign.Center)
            }
            // 三步:看完就知道点了开始之后会发生什么
            MikaCard {
                TeachStep(1, stringResource(R.string.teach_step1), stringResource(R.string.teach_step1_note), first = true)
                TeachStep(2, stringResource(R.string.teach_step2), stringResource(R.string.teach_step2_note))
                TeachStep(3, stringResource(R.string.teach_step3), stringResource(R.string.teach_step3_note))
            }
            // 说清什么值得教:Mika 自己摸不到的事
            Text(
                stringResource(R.string.teach_good_for),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), style = MikaType.Caption, color = c.ink3,
            )
            Text(
                stringResource(R.string.teach_goal_label),
                Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp), style = MikaType.Caption, color = c.ink3,
            )
            Box(
                Modifier.fillMaxWidth().background(if (empty) c.badSoft else c.surface, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (goal.isEmpty()) Text(stringResource(R.string.teach_goal_hint), style = MikaType.Body, color = c.ink3)
                BasicTextField(
                    goal, { goal = it; empty = false },
                    Modifier.fillMaxWidth().testTag("teach-goal"),
                    textStyle = MikaType.Body.copy(color = c.ink),
                    cursorBrush = SolidColor(c.ink),
                )
            }
            if (empty) {
                Text(stringResource(R.string.teach_need_goal), Modifier.padding(start = 16.dp, top = 6.dp), style = MikaType.Caption, color = c.bad)
            }
            // 隐私:一行小字,点开看完整说明
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 16.dp).clip(MikaShapes.Tile)
                    .clickable { detailed = !detailed }.padding(horizontal = 4.dp, vertical = 4.dp).testTag("teach-disclosure"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(MikaIcons.Shield, null, Modifier.size(15.dp).padding(top = 1.dp), tint = c.ink3)
                Text(
                    stringResource(if (detailed) R.string.teach_disclosure else R.string.teach_disclosure_short),
                    style = MikaType.Caption, color = c.ink3,
                )
            }
        }
        InkButton(
            stringResource(R.string.teach_start),
            { if (goal.isBlank()) empty = true else onStart(goal) },
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp).testTag("teach-start"),
        )
    }
}

/** 三步里的一步:左边黑底序号,右边标题和一句说明。 */
@Composable
private fun TeachStep(n: Int, title: String, note: String, first: Boolean = false) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (first) Modifier else Modifier.drawBehind {
                drawLine(c.hair, Offset(52.dp.toPx(), 0f), Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx())
            })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(22.dp).background(c.ink, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = MikaType.Caption, color = c.surface)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MikaType.Body, color = c.ink)
            Text(note, style = MikaType.Caption, color = c.ink3)
        }
    }
}

/** 页面底部的主按钮:铺满、黑底白字,和设置里的黑色开关、单选一致。 */
@Composable
internal fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Box(
        modifier.fillMaxWidth().height(MikaSize.Button).clip(MikaShapes.Button).background(c.ink)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MikaType.BodyStrong, color = c.surface) }
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
internal fun Summarizing(failed: Boolean, notice: TeachNotice?, onRetry: () -> Unit, onDiscard: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (failed) {
            Text(
                stringResource(if (notice == TeachNotice.NoSteps) R.string.teach_no_steps_again else R.string.teach_draft_failed),
                style = MikaType.Title, color = MikaTheme.colors.ink,
            )
            MikaButton(
                stringResource(R.string.teach_retry), onRetry,
                kind = ButtonKind.Primary, size = ButtonSize.Medium,
                modifier = Modifier.testTag("teach-retry"),
            )
        } else {
            Text(stringResource(R.string.teach_summarizing), style = MikaType.Title, color = MikaTheme.colors.ink)
            Text(stringResource(R.string.teach_summary_head), style = MikaType.Body, color = MikaTheme.colors.ink2)
        }
        MikaButton(
            stringResource(R.string.teach_discard), onDiscard, kind = ButtonKind.Tonal, size = ButtonSize.Medium,
            modifier = Modifier.testTag("teach-discard"),
        )
    }
}

@Composable
internal fun TeachSummaryPage(
    view: TeachSummaryView,
    onAccept: () -> Unit,
    onRedo: () -> Unit,
    onDiscard: () -> Unit,
) {
    var foldedOpen by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 8.dp).testTag("teach-summary"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.teach_summary_ready), style = MikaType.Label, color = MikaTheme.colors.ink3)
            Text(view.display, style = MikaType.Title, color = MikaTheme.colors.ink)
            if (view.description.isNotBlank()) {
                Text(view.description, style = MikaType.Caption, color = MikaTheme.colors.ink2)
            }
            Text(stringResource(R.string.teach_summary_head), style = MikaType.Hint, color = MikaTheme.colors.ink3)
            if (view.folded.isNotEmpty()) {
                MikaCard(Modifier.clickable { foldedOpen = !foldedOpen }.testTag("teach-folded")) {
                    Text(
                        stringResource(R.string.teach_folded_n, view.folded.size),
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MikaType.Label, color = MikaTheme.colors.ink,
                    )
                    if (foldedOpen) {
                        view.folded.forEach {
                            Text(
                                "· $it",
                                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                                style = MikaType.Hint, color = MikaTheme.colors.ink3,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            if (view.basic) {
                Text(stringResource(R.string.teach_basic_note), style = MikaType.Caption, color = MikaTheme.colors.warn)
            }
            SkillReadingView(view.body)
            Spacer(Modifier.height(8.dp))
        }
        Column(
            Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InkButton(stringResource(R.string.teach_accept), onAccept, Modifier.testTag("teach-accept"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.teach_redo),
                    Modifier.clickable(onClick = onRedo).padding(8.dp).testTag("teach-redo"),
                    style = MikaType.Label, color = MikaTheme.colors.ink3,
                )
                Text(
                    stringResource(R.string.teach_discard),
                    Modifier.clickable {
                        if (confirmDiscard) onDiscard() else confirmDiscard = true
                    }.padding(8.dp).testTag("teach-discard"),
                    style = MikaType.Label,
                    color = if (confirmDiscard) MikaTheme.colors.warn else MikaTheme.colors.ink3,
                )
            }
        }
    }
}

@Composable
internal fun TeachDonePage(
    name: String,
    description: String,
    onBackToChat: () -> Unit,
    onTeachAgain: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(20.dp).testTag("teach-done"),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(MikaIcons.Check, null, Modifier.size(36.dp), tint = MikaTheme.colors.ok)
        Text(stringResource(R.string.teach_accepted_title), style = MikaType.Label, color = MikaTheme.colors.ink3)
        Text(
            stringResource(R.string.teach_accepted_body, name),
            style = MikaType.Title, color = MikaTheme.colors.ink, textAlign = TextAlign.Center,
        )
        if (description.isNotBlank()) {
            Text(description, style = MikaType.Body, color = MikaTheme.colors.ink2, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        InkButton(stringResource(R.string.teach_back_chat), onBackToChat, Modifier.testTag("teach-back-chat"))
        MikaButton(
            stringResource(R.string.teach_teach_again), onTeachAgain,
            kind = ButtonKind.Tonal, modifier = Modifier.fillMaxWidth().testTag("teach-again"),
        )
    }
}

private fun teachAgain(runtime: TeachRuntime, goal: String) {
    runtime.discard()
    runtime.prefill(goal)
}

private fun summaryView(draft: PersonalSkill, ui: TeachUi) = TeachSummaryView(
    display = metaValue(draft, "display") ?: draft.name,
    description = metaValue(draft, "description").orEmpty(),
    body = skillPreviewBody(draft.markdown),
    folded = ui.folded,
    basic = ui.basic,
)

private fun metaValue(draft: PersonalSkill?, field: String): String? =
    draft?.let { (FrontMatter.parse(it.markdown).first[field] as? String)?.takeIf { v -> v.isNotBlank() } }
