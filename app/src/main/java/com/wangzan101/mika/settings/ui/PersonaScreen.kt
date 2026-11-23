package com.wangzan101.mika.settings.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TokenEstimate
import com.wangzan101.mika.core.prompt.PersonaPrompt
import com.wangzan101.mika.core.prompt.PersonaStyle
import com.wangzan101.mika.settings.Persona
import com.wangzan101.mika.settings.PersonaStore
import com.wangzan101.mika.settings.assistant
import com.wangzan101.mika.settings.changesNothing
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.settings.ui.models.pick

/**
 * 设置 › 人格。打开就是现在在用的那一套:在页面上随便改,点底部的「保存」才写下去,从下一条消息起对所有对话生效;
 * 有没保存的改动就离开会先问一句。还没有在用的时页面显示全默认,第一次保存才建一套(名称「我的风格」)并设为在用。
 * 多套人格收在页底的「管理人格」里,只用一套的人不用进去。
 */
@Composable
fun PersonaScreen(store: PersonaStore, onBack: () -> Unit) {
    var managing by rememberSaveable { mutableStateOf(false) }
    if (managing) {
        // 系统返回键和左上角返回一致:回到人格页,不直接退出
        BackHandler { managing = false }
        ManagePersonas(store, onBack = { managing = false })
    } else {
        PersonaPage(store, onBack, onManage = { managing = true })
    }
}

@Composable
private fun PersonaPage(store: PersonaStore, onBack: () -> Unit, onManage: () -> Unit) {
    val c = MikaTheme.colors
    val personas by store.personas.collectAsState()
    val activeId by store.activeId.collectAsState()
    val active = personas.firstOrNull { it.id == activeId }
    val savedStyle = active?.style ?: PersonaStyle.DEFAULT
    val savedPrompt = active?.prompt.orEmpty()
    val savedNickname = active?.nickname.orEmpty()
    val context = LocalContext.current
    val myStyle = stringResource(R.string.persona_my_style)
    val saveFailed = stringResource(R.string.persona_save_failed)

    // 这一页改的是草稿,点「保存」才写下去;在用的那一套换了或被别处改了,草稿跟着重取
    var style by remember(active?.id, active?.revision) { mutableStateOf(savedStyle) }
    var prompt by remember(active?.id, active?.revision) { mutableStateOf(savedPrompt) }
    var nickname by remember(active?.id, active?.revision) { mutableStateOf(savedNickname) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    // 有没保存的改动时,离开(返回、去管理人格)先问一句;这里记着问完要去哪
    var leaving by remember { mutableStateOf<(() -> Unit)?>(null) }

    val dirty = style != savedStyle || prompt.trim() != savedPrompt || nickname.trim() != savedNickname
    val tooLong = prompt.length > PersonaPrompt.MAX_PROMPT_CHARS
    val nicknameOk = PersonaPrompt.nicknameAccepted(nickname)
    fun leave(go: () -> Unit) { if (dirty) leaving = go else go() }
    BackHandler(enabled = dirty) { leaving = onBack }

    PageScaffold(stringResource(R.string.persona_title), onBack = { leave(onBack) }) {
        // 存了不止一套时说清楚这一页改的是哪一套
        if (active != null && personas.size > 1) FooterNote(stringResource(R.string.persona_editing, active.name))

        // 称呼:Mika 的自称,空着就是 Mika;只接受名字里该有的字符,别的当场提示,不悄悄删
        GroupCard {
            InputRow(
                stringResource(R.string.persona_nickname), nickname, { nickname = it.replace('\n', ' ') },
                placeholder = PersonaPrompt.DEFAULT_NICKNAME, first = true, modifier = Modifier.testTag("persona-nickname"),
            )
        }
        if (nicknameOk) FooterNote(stringResource(R.string.persona_nickname_about))
        else FooterNote(stringResource(R.string.persona_nickname_invalid, PersonaPrompt.MAX_NICKNAME), color = c.bad)

        GroupLabel(stringResource(R.string.persona_style_base))
        GroupCard {
            PersonaStyle.Base.entries.forEachIndexed { i, base ->
                RadioRow(
                    title = stringResource(baseLabel(base)), selected = style.base == base,
                    onClick = { style = style.copy(base = base) },
                    subtitle = stringResource(baseAbout(base)), first = i == 0,
                )
            }
        }

        GroupCard {
            levelOptions(style).forEachIndexed { i, o ->
                ListRow(
                    null, stringResource(o.title), first = i == 0,
                    onClick = { picking = o.tag }, modifier = Modifier.testTag("persona-opt-${o.tag}"),
                ) {
                    RowValue(stringResource(o.choices[o.selected]))
                    RowChevron()
                }
            }
            if (!style.isDefault) {
                ListRow(
                    null, stringResource(R.string.persona_style_reset),
                    onClick = { style = PersonaStyle.DEFAULT }, modifier = Modifier.testTag("persona-style-reset"),
                )
            }
        }

        GroupLabel(stringResource(R.string.persona_prompt))
        // 卡片本身就是输入框的外形:里面是不带描边的输入区(同卡片里的输入行),不再套一层直角的框
        GroupCard {
            Box(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (prompt.isEmpty()) Text(stringResource(R.string.persona_prompt_hint), style = MikaType.Body, color = c.ink3)
                BasicTextField(
                    prompt, { prompt = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp).keepsKeyboard().testTag("persona-prompt"),
                    textStyle = MikaType.Body.copy(color = c.ink, textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(c.accent),
                )
            }
        }
        // 字数和上限提示放在卡片下面,和别处的页脚小字一个样子
        if (tooLong) FooterNote(stringResource(R.string.persona_prompt_too_long, PersonaPrompt.MAX_PROMPT_CHARS), color = c.bad)
        else FooterNote(stringResource(R.string.persona_counter, prompt.length, TokenEstimate.of(prompt)))
        FooterNote(stringResource(R.string.persona_empty_sub))
        FooterNote(stringResource(R.string.persona_applies_note))

        // 没改过、或指令太长时点不了;保存成功就回上一页
        MikaButton(
            stringResource(R.string.save),
            {
                val saved = store.editActive(myStyle) { it.copy(style = style, prompt = prompt.trim(), nickname = nickname.trim()) }
                if (saved.isSuccess) onBack() else Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
            },
            size = ButtonSize.Medium, enabled = dirty && !tooLong && nicknameOk,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp).testTag("persona-save"),
        )

        GroupCard {
            ListRow(
                null, stringResource(R.string.persona_manage), first = true,
                onClick = { leave(onManage) }, modifier = Modifier.testTag("persona-manage"),
            ) { RowChevron() }
        }
    }

    levelOptions(style).firstOrNull { it.tag == picking }?.let { open ->
        MikaDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(open.title)) },
            text = {
                Column {
                    open.about?.let { Text(stringResource(it), Modifier.padding(bottom = 8.dp)) }
                    open.choices.forEachIndexed { i, label ->
                        RadioRow(
                            title = stringResource(label), selected = i == open.selected,
                            onClick = { style = open.pick(style, i); picking = null }, first = i == 0,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    leaving?.let { go ->
        MikaDialog(
            onDismissRequest = { leaving = null },
            title = { Text(stringResource(R.string.persona_discard_title)) },
            text = { Text(stringResource(R.string.persona_discard_body)) },
            confirmButton = {
                TextButton({ leaving = null; go() }, Modifier.testTag("persona-discard")) {
                    Text(stringResource(R.string.teach_discard), color = c.bad)
                }
            },
            dismissButton = { TextButton({ leaving = null }) { Text(stringResource(R.string.persona_keep_editing)) } },
        )
    }
}

/** 起名字的对话框在做哪件事:新建一套,还是给已有的一套改名。 */
private sealed interface Naming {
    data object New : Naming
    data class Rename(val persona: Persona) : Naming
}

/**
 * 管理人格:存下来的各套,点一行弹操作面板(用这一套、改名、复制一份、删除),在用的那一套带「在用」。
 * 要改内容得先选用它,再回人格页调整;这里只管「有哪几套、用哪一套」。
 */
@Composable
private fun ManagePersonas(store: PersonaStore, onBack: () -> Unit) {
    val personas by store.personas.collectAsState()
    val activeId by store.activeId.collectAsState()
    val active = personas.firstOrNull { it.id == activeId }
    val context = LocalContext.current
    val saveFailed = stringResource(R.string.persona_save_failed)
    // 各语言「副本」的写法和位置不一样:先用占位符拿到格式,复制时再把名称放进去
    val copyName = stringResource(R.string.persona_copy_name, NAME_SLOT)
    var acting by remember { mutableStateOf<Persona?>(null) }
    var deleting by remember { mutableStateOf<Persona?>(null) }
    var naming by remember { mutableStateOf<Naming?>(null) }
    var preview by rememberSaveable { mutableStateOf(false) }
    val full = personas.size >= PersonaStore.MAX_PERSONAS

    fun done(result: Result<*>) {
        if (result.isFailure) Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
    }

    PageScaffold(stringResource(R.string.persona_manage), onBack) {
        GroupCard {
            if (personas.isEmpty()) {
                ListRow(MikaIcons.Spark, stringResource(R.string.persona_empty), stringResource(R.string.persona_empty_sub), first = true)
            }
            personas.forEachIndexed { i, p ->
                ListRow(
                    null, p.name, subtitle = summary(p).takeIf { it.isNotEmpty() }, first = i == 0,
                    onClick = { acting = p }, modifier = Modifier.testTag("persona-row"),
                ) { if (p.id == activeId) StatusPill(stringResource(R.string.persona_in_use), PillTone.Accent) }
            }
            if (!full) {
                ListRow(
                    MikaIcons.Plus, stringResource(R.string.persona_new), first = false,
                    onClick = { naming = Naming.New }, modifier = Modifier.testTag("persona-new"),
                )
            }
        }
        if (full) FooterNote(stringResource(R.string.persona_limit, PersonaStore.MAX_PERSONAS))
        FooterNote(stringResource(R.string.persona_manage_note))

        // 在用的那一套确实拼出了提示词时,才有「预览完整提示词」;点一下展开,再点收起
        val layer = active?.let { PersonaPrompt.layer(it.name, it.prompt, it.style, it.assistant) }
        if (layer != null) {
            GroupCard {
                ListRow(
                    null, stringResource(R.string.persona_preview), first = true,
                    onClick = { preview = !preview }, modifier = Modifier.testTag("persona-preview"),
                ) { RowChevron() }
                if (preview) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                        Text(stringResource(R.string.persona_preview_note), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                        Text(previewText(layer, PersonaPrompt.content(active.prompt, active.style)), Modifier.padding(top = 8.dp), style = MikaType.Caption)
                    }
                }
            }
        }
    }

    acting?.let { p ->
        ActionSheet(
            p.name,
            listOfNotNull(
                if (p.id != activeId) SheetAction(stringResource(R.string.persona_use)) { store.setActive(p.id) } else null,
                SheetAction(stringResource(R.string.drawer_rename)) { naming = Naming.Rename(p) },
                if (!full) SheetAction(stringResource(R.string.persona_copy)) {
                    done(store.duplicate(p.id) { name -> copyName.replace(NAME_SLOT, name) })
                } else null,
                // 删除先确认:删掉在用的那一套,Mika 就回到默认的说话方式
                SheetAction(stringResource(R.string.confirm_delete), destructive = true) { deleting = p },
            ),
            onDismiss = { acting = null },
        )
    }

    naming?.let { n ->
        NameDialog(
            initial = (n as? Naming.Rename)?.persona?.name.orEmpty(),
            note = if (n is Naming.New) stringResource(R.string.persona_new_note) else null,
            title = stringResource(if (n is Naming.New) R.string.persona_new else R.string.drawer_rename),
            onSave = { name ->
                when (n) {
                    // 新建的一套直接在用,回人格页去调
                    is Naming.New -> store.create(name).also(::done).onSuccess { onBack() }
                    is Naming.Rename -> done(store.rename(n.persona.id, name))
                }
                naming = null
            },
            onDismiss = { naming = null },
        )
    }

    deleting?.let { p ->
        MikaDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.persona_delete_title, p.name)) },
            text = { Text(stringResource(R.string.persona_delete_body)) },
            confirmButton = {
                TextButton({ store.delete(p.id); deleting = null }, Modifier.testTag("persona-delete-confirm")) {
                    Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad)
                }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 起名字:单行输入框,空或太长时说明原因并禁用保存。 */
@Composable
private fun NameDialog(initial: String, note: String?, title: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var touched by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    val problem = when {
        trimmed.isEmpty() -> stringResource(R.string.persona_name_required)
        trimmed.length > PersonaPrompt.MAX_NAME -> stringResource(R.string.persona_name_too_long)
        else -> null
    }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                note?.let { Text(it, style = MikaType.Hint, color = MikaTheme.colors.ink3) }
                OutlinedTextField(
                    name, { name = it; touched = true }, singleLine = true,
                    label = { Text(stringResource(R.string.persona_name)) },
                    isError = touched && problem != null,
                    supportingText = if (touched && problem != null) ({ Text(problem) }) else null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("persona-name"),
                )
            }
        },
        confirmButton = {
            TextButton({ onSave(trimmed) }, enabled = problem == null, modifier = Modifier.testTag("persona-name-save")) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 完整提示词的预览:固定规则用灰色,这一套人格自己的内容([own]:选项的句子和用户写的指令)用正文色,
 * 一眼分得出哪些是改得动的。[own] 在 [layer] 里找不到(不该发生)时整段按灰色显示。
 */
@Composable
private fun previewText(layer: String, own: String): AnnotatedString {
    val fixed = SpanStyle(color = MikaTheme.colors.ink3)
    val mine = SpanStyle(color = MikaTheme.colors.ink)
    val at = if (own.isEmpty()) -1 else layer.lastIndexOf(own)
    return buildAnnotatedString {
        if (at < 0) {
            withStyle(fixed) { append(layer) }
        } else {
            withStyle(fixed) { append(reflow(layer.substring(0, at))) }
            withStyle(mine) { append(own) }
            withStyle(fixed) { append(layer.substring(at + own.length)) }
        }
    }
}

/**
 * 固定规则在提示词文件里是按固定列宽折好行的,原样显示在手机上每行都断在半句。预览时把段内的换行接成空格,
 * 让文字按屏幕宽度自己折;段与段之间的空行、以及 `<persona …>` 这样单独成行的标签不动。
 */
private fun reflow(fixed: String): String = fixed.split("\n\n").joinToString("\n\n") { para ->
    if (para.trimStart().startsWith("<")) para else para.replace("\n", " ")
}

/** 列表里一套人格下面那行小字:有简短说明用它(导入的可能带),否则把不是默认的几项拼起来。 */
@Composable
private fun summary(p: Persona): String {
    if (p.description.isNotBlank()) return oneLine(p.description)
    val parts = mutableListOf<String>()
    if (p.assistant != PersonaPrompt.DEFAULT_NICKNAME) parts += stringResource(R.string.persona_nickname) + " " + p.assistant
    if (p.style.base != PersonaStyle.Base.Default) parts += stringResource(baseLabel(p.style.base))
    levelOptions(p.style).filter { it.selected != DEFAULT_LEVEL }.forEach { o ->
        parts += stringResource(o.title) + " " + stringResource(o.choices[o.selected])
    }
    if (p.prompt.isNotBlank()) parts += stringResource(R.string.persona_prompt)
    return oneLine(parts.joinToString(" · "))
}

/** 设置首页「人格」一行行尾的值:默认 / 存了多套时在用那一套的名称 / 基础风格的叫法 / 已调整。 */
@Composable
internal fun personaSummaryValue(store: PersonaStore): String {
    val personas by store.personas.collectAsState()
    val activeId by store.activeId.collectAsState()
    val active = personas.firstOrNull { it.id == activeId }
    return when {
        active == null || active.changesNothing -> stringResource(R.string.persona_default)
        personas.size > 1 -> clip(active.name, 6)
        active.style.base != PersonaStyle.Base.Default -> stringResource(baseLabel(active.style.base))
        else -> stringResource(R.string.persona_adjusted)
    }
}

/** 一个三档选项在界面上的样子:标题、(可选)一句说明、三档的叫法、现在选的是第几档,以及选了第 i 档之后的 [PersonaStyle]。 */
private class StyleOption(
    val tag: String,
    val title: Int,
    val about: Int?,
    val choices: List<Int>,
    val selected: Int,
    val pick: (PersonaStyle, Int) -> PersonaStyle,
)

/** 三档里「默认」排在中间。 */
private const val DEFAULT_LEVEL = 1

/** 取「副本」格式时占名称位置的字符;名称里不会有它(控制字符存不进去)。 */
private const val NAME_SLOT = "\u0001"

/** 三个三档选项:表情符号、回答篇幅、进度说明,放在一张卡片里。 */
private fun levelOptions(style: PersonaStyle): List<StyleOption> {
    val levels = PersonaStyle.Level.entries
    val scale = listOf(R.string.persona_level_less, R.string.persona_level_default, R.string.persona_level_more)
    fun level(
        tag: String, title: Int, about: Int?, value: PersonaStyle.Level, labels: List<Int> = scale,
        set: (PersonaStyle, PersonaStyle.Level) -> PersonaStyle,
    ) = StyleOption(tag, title, about, labels, levels.indexOf(value)) { s, i -> set(s, levels[i]) }
    return listOf(
        level("emoji", R.string.persona_opt_emoji, null, style.emoji) { s, v -> s.copy(emoji = v) },
        level(
            "length", R.string.persona_opt_length, R.string.persona_opt_length_about, style.length,
            listOf(R.string.persona_length_less, R.string.persona_level_default, R.string.persona_length_more),
        ) { s, v -> s.copy(length = v) },
        level(
            "progress", R.string.persona_opt_progress, R.string.persona_opt_progress_about, style.progress,
            listOf(R.string.persona_progress_less, R.string.persona_level_default, R.string.persona_progress_more),
        ) { s, v -> s.copy(progress = v) },
    )
}

private fun baseLabel(base: PersonaStyle.Base): Int = when (base) {
    PersonaStyle.Base.Default -> R.string.persona_level_default
    PersonaStyle.Base.Crisp -> R.string.persona_base_crisp
    PersonaStyle.Base.Warm -> R.string.persona_base_warm
    PersonaStyle.Base.Playful -> R.string.persona_base_playful
}

/** 基础风格每一项下面那句说明,照 style.md 里模型实际收到的那句写的。 */
private fun baseAbout(base: PersonaStyle.Base): Int = when (base) {
    PersonaStyle.Base.Default -> R.string.persona_default_sub
    PersonaStyle.Base.Crisp -> R.string.persona_base_crisp_sub
    PersonaStyle.Base.Warm -> R.string.persona_base_warm_sub
    PersonaStyle.Base.Playful -> R.string.persona_base_playful_sub
}

/** 列表里只露一行:压成一行,太长截断加省略号,免得一段长指令把整页撑开。 */
private fun oneLine(text: String): String = clip(text.replace(Regex("\\s+"), " ").trim(), 60)

/** 按码点截到 [max] 个字,截了就加省略号(不把一个表情劈成两半)。 */
private fun clip(text: String, max: Int): String {
    if (text.codePointCount(0, text.length) <= max) return text
    return text.substring(0, text.offsetByCodePoints(0, max)) + "…"
}
