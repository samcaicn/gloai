package com.wangzan101.mika.settings.ui.models

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaTheme

/**
 * ChatGPT 的连接:账号(邮箱)、退出登录或再登录、管理用量(打开 ChatGPT 的用量页)。
 * 退出登录只清这台手机上的令牌,服务商、模型和用途都留着。
 */
@Composable
internal fun ChatGptConnection(ui: ModelSetupUi, state: ListState?, actions: ProviderDetailActions) {
    val c = MikaTheme.colors
    val account = ui.chatGptAccount
    val signedIn = account?.signedIn == true
    GroupCard {
        ListRow(null, ModelsCopy.STATUS, first = true) {
            when {
                !signedIn -> RowValue(ModelsCopy.CHATGPT_SIGNED_OUT, ValueTone.Warn)
                state == ListState.Running -> RowValue(ModelsCopy.LISTING)
                state is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                else -> RowValue(ModelsCopy.CHATGPT_SIGNED_IN, ValueTone.Ok)
            }
        }
        account?.email?.takeIf { it.isNotEmpty() }?.let { email -> ListRow(null, ModelsCopy.CHATGPT_ACCOUNT) { RowValue(email) } }
        ListRow(null, ModelsCopy.CHATGPT_MANAGE_USAGE, titleColor = c.accent, onClick = actions.manageUsage) { RowChevron() }
        if (signedIn) {
            ListRow(null, ModelsCopy.CHATGPT_SIGN_OUT, titleColor = c.bad, onClick = actions.signOut)
        } else {
            ListRow(
                null, if (ui.chatGptSignIn == ListState.Running) ModelsCopy.CHATGPT_WAITING else ModelsCopy.CHATGPT_CONTINUE,
                titleColor = c.accent, onClick = actions.signIn,
            )
        }
    }
    FooterNote(if (signedIn) ModelsCopy.CHATGPT_PLAN_NOTE else ModelsCopy.CHATGPT_SIGNED_OUT_NOTE)
    if (ui.chatGptRevokeUnconfirmed) FooterNote(ModelsCopy.CHATGPT_REVOKE_UNCONFIRMED, color = c.warn)
    (ui.chatGptSignIn as? ListState.Failed)?.let { FooterNote(it.message, color = c.bad) }
}

/**
 * 用 xAI 或 OpenRouter 账号登录的连接:账号、状态、设为主模型、打开对方的账号页(OpenRouter 是 Keys 页面)、退出或再登录。
 * 退出只清这台手机上的登录信息(xAI 的令牌、OpenRouter 换来的 Key),服务商、模型和用途都留着。
 */
@Composable
internal fun AccountConnection(ui: ModelSetupUi, p: Provider, state: ListState?, actions: ProviderDetailActions) {
    val c = MikaTheme.colors
    val kind = if (p.kind == ProviderConfig.Kind.Xai) SignInKind.Xai else SignInKind.OpenRouter
    val account = if (kind == SignInKind.Xai) ui.xaiAccount else ui.openRouterAccount
    val signedIn = account?.signedIn == true
    val (_, waiting, failure) = ui.signInState(kind)
    GroupCard {
        account?.label?.takeIf { it.isNotEmpty() }?.let { label -> ListRow(null, ModelsCopy.CHATGPT_ACCOUNT, first = true) { RowValue(label) } }
        ListRow(null, ModelsCopy.STATUS, first = account?.label.isNullOrEmpty()) {
            when {
                !signedIn -> RowValue(ModelsCopy.CHATGPT_SIGNED_OUT, ValueTone.Warn)
                state == ListState.Running -> RowValue(ModelsCopy.LISTING)
                state is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                else -> RowValue(ModelsCopy.CHATGPT_SIGNED_IN, ValueTone.Ok)
            }
        }
    }
    GroupCard {
        // 主模型已经在这家就不放这一行
        val isMain = ui.configFor(ModelRole.Main)?.providerId == p.id
        if (signedIn && !isMain && ui.options(p).isNotEmpty()) {
            // 设主模型先试调用一次(见 ModelSetupViewModel.useAsMain)
            val pick = ui.manualPick[p.id]
            ListRow(
                null, ModelsCopy.SET_MAIN, if (pick == ListState.Running) ModelSetupCopy.TESTING else ModelsCopy.SET_MAIN_NOTE,
                first = true, onClick = { if (pick != ListState.Running) actions.useAsMain() },
            ) { RowChevron() }
        }
        ListRow(null, ModelsCopy.accountManage(kind), titleColor = c.accent, first = !(signedIn && !isMain && ui.options(p).isNotEmpty()), onClick = actions.manageUsage) { RowChevron() }
        if (signedIn) {
            ListRow(null, ModelsCopy.CHATGPT_SIGN_OUT, ModelsCopy.SIGN_OUT_NOTE, titleColor = c.bad, onClick = actions.signOut)
        } else {
            ListRow(null, if (waiting) ModelsCopy.SIGN_IN_RUNNING else ModelsCopy.signInStart(kind), titleColor = c.accent, onClick = actions.signIn)
        }
    }
    FooterNote(ModelsCopy.accountNote(kind, signedIn))
    (ui.manualPick[p.id] as? ListState.Failed)?.let { FooterNote(it.message, Modifier.testTag("set-main-failed"), color = c.bad) }
    if (signedIn && kind == SignInKind.Xai) FooterNote(ModelsCopy.XAI_403_NOTE, Modifier.testTag("account-403-note"), color = c.warn)
    failure?.let { FooterNote(it, color = c.bad) }
}
