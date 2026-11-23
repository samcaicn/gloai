package com.wangzan101.mika.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.usage.TokenBudgetLimits
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.theme.MikaTheme
import java.text.NumberFormat

/** 设置与运行记录里都显示完整数字，避免紧凑格式把预算四舍五入成另一个值。 */
internal fun tokenBudgetNumber(value: Long): String = NumberFormat.getIntegerInstance().format(value)

/**
 * 每任务和每服务商共用的预算选择：不限、预设，或范围内的自定义整数。
 * 主动能力的每日预算没有「不限」([unlimited] 为 false),预设档位另给([presets])。
 */
@Composable
internal fun TokenBudgetDialog(
    title: String,
    description: String,
    current: Long?,
    custom: String,
    onCustom: (String) -> Unit,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
    saveError: String? = null,
    presets: List<Long> = TokenBudgetLimits.PRESETS,
    unlimited: Boolean = true,
) {
    val customValue = custom.toLongOrNull()?.takeIf(TokenBudgetLimits::valid)
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(description, Modifier.padding(bottom = 8.dp))
                if (unlimited) RadioRow(
                    stringResource(R.string.settings_token_budget_unlimited),
                    selected = current == null, onClick = { onPick(null) }, first = true,
                )
                presets.forEachIndexed { i, n ->
                    RadioRow(tokenBudgetNumber(n), selected = current == n, onClick = { onPick(n) }, first = !unlimited && i == 0)
                }
                OutlinedTextField(
                    value = custom, onValueChange = onCustom, singleLine = true,
                    label = { Text(stringResource(R.string.settings_token_budget_custom)) },
                    isError = custom.isNotEmpty() && customValue == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (custom.isNotEmpty() && customValue == null) {
                    Text(
                        stringResource(
                            R.string.settings_token_budget_invalid,
                            tokenBudgetNumber(TokenBudgetLimits.MIN), tokenBudgetNumber(TokenBudgetLimits.MAX),
                        ),
                        Modifier.padding(top = 4.dp),
                    )
                }
                saveError?.let { Text(it, Modifier.padding(top = 4.dp), color = MikaTheme.colors.bad) }
            }
        },
        confirmButton = {
            TextButton(onClick = { customValue?.let(onPick) }, enabled = customValue != null) {
                Text(stringResource(R.string.settings_token_budget_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
