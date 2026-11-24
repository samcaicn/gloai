package com.wangzan101.mika.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * App 内统一的输入框:浅灰底、圆角、不描边;标签在框上方,说明和出错提示在框下方。
 * 参数和顺序照 Material 的 OutlinedTextField,调用方直接换名字即可。
 */
@Composable
fun MikaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    var tfv by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(TextFieldValue(value)) }
    if (tfv.text != value) tfv = tfv.copy(text = value, selection = androidx.compose.ui.text.TextRange(value.length))
    MikaTextField(tfv, { tfv = it; if (it.text != value) onValueChange(it.text) }, modifier, enabled, readOnly, textStyle, label, placeholder,
        leadingIcon, trailingIcon, prefix, suffix, supportingText, isError, visualTransformation, keyboardOptions, keyboardActions,
        singleLine, maxLines, minLines)
}

/** 带光标位置的版本(需要自己控制选区时用)。 */
@Composable
fun MikaTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val c = MikaTheme.colors
    val base = MikaType.Body.merge(textStyle).let { it.copy(color = it.color.takeOrElse { c.ink }) }
    Column(Modifier.alpha(if (enabled) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            CompositionLocalProvider(LocalContentColor provides if (isError) c.bad else c.ink2, LocalTextStyle provides MikaType.Label) {
                Box(Modifier.padding(start = 4.dp)) { label() }
            }
        }
        BasicTextField(
            value, onValueChange, modifier.fillMaxWidth(),
            enabled = enabled, readOnly = readOnly, textStyle = base,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines,
            visualTransformation = visualTransformation, cursorBrush = SolidColor(c.accent),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .background(if (isError) c.badSoft else c.fill, MikaShapes.Button)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val adorn: @Composable (@Composable () -> Unit) -> Unit = { slot ->
                        CompositionLocalProvider(LocalContentColor provides c.ink3, LocalTextStyle provides base.copy(color = c.ink3)) { slot() }
                    }
                    leadingIcon?.let { adorn(it) }
                    prefix?.let { adorn(it) }
                    Box(Modifier.weight(1f)) {
                        if (value.text.isEmpty() && placeholder != null) adorn(placeholder)
                        inner()
                    }
                    suffix?.let { adorn(it) }
                    trailingIcon?.let { adorn(it) }
                }
            },
        )
        if (supportingText != null) {
            CompositionLocalProvider(LocalContentColor provides if (isError) c.bad else c.ink3, LocalTextStyle provides MikaType.Hint) {
                Box(Modifier.padding(start = 4.dp)) { supportingText() }
            }
        }
    }
}
