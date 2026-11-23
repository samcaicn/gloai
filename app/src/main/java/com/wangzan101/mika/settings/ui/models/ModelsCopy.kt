package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.oauth.AccountSignInException
import com.wangzan101.mika.core.model.oauth.AccountSignInFailure
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.wangzan101.mika.core.model.chatgpt.SignInFailure
import com.wangzan101.mika.core.model.GroupStrategy
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechOptions
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.chat.ui.Exchange

/** 模型页(用途、选择页、服务商详情、全部模型)的文字(纯函数,便于测试)。三个用途的说法全 App 只在这里。 */
object ModelsCopy {
    val TITLE: String get() = tr(R.string.settings_model)
    val LEAD: String get() = tr(R.string.models_lead)
    val ROLES: String get() = tr(R.string.models_roles)
    val PROVIDERS: String get() = tr(R.string.models_providers)
    val ADD_PROVIDER: String get() = tr(R.string.models_add_provider)
    val KEY_NOTE: String get() = tr(R.string.models_key_note)
    val UNSET: String get() = tr(R.string.settings_not_set)
    val MAIN_ADD_FIRST: String get() = tr(R.string.models_main_add_first)
    val MAIN_PICK: String get() = tr(R.string.models_main_pick)
    val SAME_AS_MAIN: String get() = tr(R.string.models_same_as_main)
    val MAIN_NOT_PICKED: String get() = tr(R.string.models_main_not_picked)
    val OFF: String get() = tr(R.string.models_off)
    val OFF_NOTE: String get() = tr(R.string.models_off_note)
    val VISION_SAME_NOTE: String get() = tr(R.string.models_vision_same_note)
    val LIGHT_SAME_NOTE: String get() = tr(R.string.models_light_same_note)
    val PICK_FROM: String get() = tr(R.string.models_pick_from)
    val FILTER_HINT: String get() = tr(R.string.models_filter_hint)
    val NO_MATCH: String get() = tr(R.string.models_no_match)
    val MODEL_NAME: String get() = tr(R.string.models_model_name)
    val USE: String get() = tr(R.string.models_use)
    val ROLE_EMPTY: String get() = tr(R.string.models_role_empty)
    val PICKER_NOTE: String get() = tr(R.string.models_picker_note)
    val LISTING: String get() = tr(R.string.models_listing)
    val CONNECTION: String get() = tr(R.string.models_connection)
    val STATUS: String get() = tr(R.string.models_status)
    val CONNECTED: String get() = tr(R.string.phone_connected)
    val LIST_FAILED: String get() = tr(R.string.models_list_failed)
    val KIND: String get() = tr(R.string.model_kind)
    val BASE_URL: String get() = tr(R.string.models_url)
    const val API_KEY = "API Key"
    val KEY_SAVED: String get() = tr(R.string.models_key_saved)
    val KEY_NONE: String get() = tr(R.string.models_key_none)
    val CHANGE: String get() = tr(R.string.models_change)
    val CHANGE_KEY: String get() = tr(R.string.models_change_key)
    val NEW_KEY_HINT: String get() = tr(R.string.models_new_key_hint)
    val SAVE: String get() = tr(R.string.save)
    val MODELS: String get() = tr(R.string.models_models)
    val MODELS_NOTE: String get() = tr(R.string.models_models_note)
    val NO_LIST_NOTE: String get() = tr(R.string.models_no_list_note)
    val UNTESTED_NOTE: String get() = tr(R.string.models_untested_note)
    val UNVERIFIED: String get() = tr(R.string.models_unverified)
    val RETEST: String get() = tr(R.string.models_retest)
    val SEES_IMAGES: String get() = tr(R.string.models_sees_images)

    // ---------- 模型组 ----------

    val GROUPS: String get() = tr(R.string.models_groups)
    val NEW_GROUP: String get() = tr(R.string.models_group_new)
    val GROUP_NAME: String get() = tr(R.string.models_group_name)
    val GROUP_NAME_HINT: String get() = tr(R.string.models_group_name_hint)
    val GROUP_STRATEGY: String get() = tr(R.string.models_group_strategy)
    val GROUP_STICKY: String get() = tr(R.string.models_group_sticky)
    val GROUP_STICKY_NOTE: String get() = tr(R.string.models_group_sticky_note)
    val GROUP_MEMBERS: String get() = tr(R.string.models_group_members)
    val GROUP_NO_MEMBERS: String get() = tr(R.string.models_group_no_members)
    val GROUP_UP: String get() = tr(R.string.models_group_up)
    val GROUP_DOWN: String get() = tr(R.string.models_group_down)
    val GROUP_REMOVE: String get() = tr(R.string.models_group_remove)
    val GROUP_CONTEXT: String get() = tr(R.string.models_group_member_context)
    val GROUP_ADD_MEMBER: String get() = tr(R.string.models_group_add_member)
    val GROUP_MAIN_NEEDS_TOOLS: String get() = tr(R.string.models_group_main_needs_tools)
    val GROUP_VISION_NEEDS_EYES: String get() = tr(R.string.models_group_vision_needs_eyes)
    val GROUP_PRIVACY: String get() = tr(R.string.models_group_privacy)
    val GROUP_SAVE_FAILED: String get() = tr(R.string.models_group_save_failed)
    val GROUP_DELETE: String get() = tr(R.string.models_group_delete)
    val GROUP_DELETE_BODY: String get() = tr(R.string.models_group_delete_body)
    val CANCEL: String get() = tr(R.string.cancel)
    val ADD: String get() = tr(R.string.models_group_add)
    val groupLead: String get() = tr(R.string.models_group_lead)
    val groupEmpty: String get() = tr(R.string.models_group_empty)
    val groupFooter: String get() = tr(R.string.models_group_footer)

    fun groupCount(n: Int): String = tr(R.string.models_group_member_count, n)
    fun groupDeleteTitle(name: String): String = tr(R.string.models_group_delete_title, name)
    fun editGroup(name: String): String = tr(R.string.models_group_edit, name)
    fun groupPriority(n: Int): String = tr(R.string.models_group_priority, n)

    fun groupRoles(roles: RoleChoices, groupId: String): String {
        val used = ModelRole.entries.filter { roles.resolveGroup(it) == groupId }.map { roleLabel(it) }
        return if (used.isEmpty()) "" else tr(R.string.models_group_used_by, used.joinToString(tr(R.string.list_sep)))
    }

    fun strategyTitle(s: GroupStrategy): String = tr(when (s) {
        GroupStrategy.Failover -> R.string.models_group_strategy_failover
        GroupStrategy.Weighted -> R.string.models_group_strategy_weighted
        GroupStrategy.LeastLatency -> R.string.models_group_strategy_latency
        GroupStrategy.CostFirst -> R.string.models_group_strategy_cost
    })

    fun strategyNote(s: GroupStrategy): String = tr(when (s) {
        GroupStrategy.Failover -> R.string.models_group_strategy_failover_note
        GroupStrategy.Weighted -> R.string.models_group_strategy_weighted_note
        GroupStrategy.LeastLatency -> R.string.models_group_strategy_latency_note
        GroupStrategy.CostFirst -> R.string.models_group_strategy_cost_note
    })

    /** 「能看图」开关下的说明:用户说过的、用作看图的、列表写明的、没写未确认的。 */
    fun seesNote(o: ModelOption, usedForVision: Boolean): String = tr(when {
        o.visionByUser != null -> R.string.models_sees_note_user
        usedForVision -> R.string.models_sees_note_vision
        o.visionKnown -> R.string.models_sees_note_listed
        else -> R.string.models_sees_note_unconfirmed
    })

    fun unverifiedNote(models: List<String>) = tr(R.string.models_unverified_note, models.joinToString(tr(R.string.list_sep)))
    val NOT_LISTED: String get() = tr(R.string.models_not_listed)
    val REFRESH: String get() = tr(R.string.refresh)
    val IN_USE: String get() = tr(R.string.models_in_use)
    val DELETE: String get() = tr(R.string.models_delete)
    val DELETE_CONFIRM: String get() = tr(R.string.confirm_delete)
    val CHATGPT_TITLE: String get() = tr(R.string.chatgpt_use_plan_title)
    val CHATGPT_BODY: String get() = tr(R.string.chatgpt_use_plan_body)
    val CHATGPT_CONTINUE: String get() = tr(R.string.chatgpt_continue)
    val CHATGPT_WAITING: String get() = tr(R.string.chatgpt_waiting)
    val CHATGPT_WELCOME_TITLE: String get() = tr(R.string.chatgpt_welcome_title)
    val CHATGPT_WELCOME_BODY: String get() = tr(R.string.chatgpt_welcome_body)
    val CHATGPT_GOT_IT: String get() = tr(R.string.chatgpt_got_it)
    val CHATGPT_ACCOUNT: String get() = tr(R.string.chatgpt_account)
    val CHATGPT_SIGNED_IN: String get() = tr(R.string.chatgpt_signed_in)
    val CHATGPT_SIGNED_OUT: String get() = tr(R.string.chatgpt_signed_out)
    val CHATGPT_SIGN_OUT: String get() = tr(R.string.chatgpt_sign_out)
    val CHATGPT_MANAGE_USAGE: String get() = tr(R.string.chatgpt_manage_usage)
    val CHATGPT_PLAN_NOTE: String get() = tr(R.string.chatgpt_plan_note)
    val CHATGPT_SIGNED_OUT_NOTE: String get() = tr(R.string.chatgpt_signed_out_note)
    val CHATGPT_REVOKE_UNCONFIRMED: String get() = tr(R.string.chatgpt_revoke_unconfirmed)

    /**
     * 用 ChatGPT 登录没成的原因(界面和回调网页共用)。说得出具体原因的照说;只有服务端说了套餐、权限,
     * 或授予的权限里没有套餐那一项时才提 Plus、Pro;别的被拒带上错误代码 [code](不是机密)。
     */
    fun chatGptFailure(reason: SignInFailure, code: String? = null): String = when (reason) {
        SignInFailure.Denied -> tr(R.string.chatgpt_fail_denied)
        SignInFailure.StateMismatch, SignInFailure.ClientMismatch -> tr(R.string.chatgpt_fail_mismatch)
        SignInFailure.NoClientId -> tr(R.string.chatgpt_fail_no_client)
        SignInFailure.TimedOut -> tr(R.string.chatgpt_fail_timed_out)
        SignInFailure.Exchange -> tr(R.string.chatgpt_fail_exchange, code?.takeIf { it.isNotBlank() } ?: "unknown")
        SignInFailure.CodeExpired -> tr(R.string.chatgpt_fail_code_expired)
        SignInFailure.Region -> tr(R.string.chatgpt_fail_region)
        SignInFailure.Blocked -> tr(R.string.chatgpt_fail_blocked)
        SignInFailure.IdToken -> tr(R.string.chatgpt_fail_id_token)
        SignInFailure.NoPlanScope -> tr(R.string.chatgpt_fail_no_plan)
        SignInFailure.Network -> tr(R.string.chatgpt_fail_network)
    }

    // ---------- 用账号登录(xAI、OpenRouter;ChatGPT 的在上面) ----------

    val SIGN_IN_GROUP: String get() = tr(R.string.signin_group)
    val KEY_GROUP: String get() = tr(R.string.signin_key_group)
    val SIGN_IN_RUNNING: String get() = tr(R.string.signin_running)
    val SIGN_IN_USE_KEY: String get() = tr(R.string.signin_use_key)
    val SIGN_IN_BUSY: String get() = tr(R.string.signin_busy)

    /** 界面上服务商的名字:授权来的 OpenRouter 加一句来历,和用户自己填 Key 的那一家分得开。 */
    fun providerName(p: Provider): String {
        if (p.signIn == ProviderStore.OPENROUTER_SIGN_IN) return tr(R.string.signin_openrouter_name, p.name)
        if (p.name == "Cloudflare Workers AI" && p.baseUrl.contains("/accounts/")) {
            val acc = p.baseUrl.substringAfter("/accounts/").substringBefore("/").trim()
            if (acc.isNotEmpty() && !acc.contains("{")) {
                val shortId = if (acc.length >= 8) "${acc.take(4)}…${acc.takeLast(4)}" else acc
                return "${p.name} ($shortId)"
            }
        }
        return p.name
    }
    val XAI_CODE_TITLE: String get() = tr(R.string.signin_xai_code_title)
    val XAI_CODE_BODY: String get() = tr(R.string.signin_xai_code_body)
    val XAI_CODE_COPY_REOPEN: String get() = tr(R.string.signin_xai_code_copy_reopen)
    val XAI_CODE_WAITING: String get() = tr(R.string.signin_xai_code_waiting)
    val XAI_403_NOTE: String get() = tr(R.string.signin_xai_403_note)
    val SET_MAIN: String get() = tr(R.string.signin_set_main)
    val SET_MAIN_NOTE: String get() = tr(R.string.signin_set_main_note)
    val SIGN_OUT_NOTE: String get() = tr(R.string.signin_sign_out_note)
    /** xAI 的账号页。 */
    const val ACCOUNT_MANAGE_URL = "https://accounts.x.ai"

    fun codeValid(minutes: Int) = tr(R.string.signin_xai_code_valid, minutes)

    /** 「用账号登录」一组里每家下面那一行小字。 */
    fun signInRowLine(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> tr(R.string.signin_row_chatgpt)
        SignInKind.Xai -> tr(R.string.signin_row_xai)
        SignInKind.OpenRouter -> tr(R.string.signin_row_openrouter)
    }

    fun signInTitle(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_TITLE
        SignInKind.Xai -> tr(R.string.signin_xai_title)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_title)
    }

    fun signInBody(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_BODY
        SignInKind.Xai -> tr(R.string.signin_xai_body)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_body)
    }

    /** 登录前要先说清的风险(黄底);没有为 null。 */
    fun signInRisk(kind: SignInKind): String? = if (kind == SignInKind.Xai) tr(R.string.signin_xai_risk) else null

    /** 登录前的补充说明(灰底);没有为 null。 */
    fun signInInfo(kind: SignInKind): String? = when (kind) {
        SignInKind.ChatGpt -> null
        SignInKind.Xai -> tr(R.string.signin_stored_note)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_note)
    }

    fun signInStart(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_CONTINUE
        SignInKind.Xai -> tr(R.string.signin_continue)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_authorize)
    }

    fun signInWaiting(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_WAITING
        SignInKind.Xai -> tr(R.string.signin_xai_starting)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_waiting)
    }

    fun accountManage(kind: SignInKind) = when (kind) {
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_manage)
        else -> tr(R.string.account_manage_xai)
    }

    /** 账号登录详情下面的说明:在用什么、退出后怎样。 */
    fun accountNote(kind: SignInKind, signedIn: Boolean) = when (kind) {
        SignInKind.OpenRouter -> tr(if (signedIn) R.string.signin_openrouter_signed_in_note else R.string.signin_openrouter_signed_out_note)
        else -> tr(if (signedIn) R.string.account_signed_in_note_xai else R.string.account_signed_out_note)
    }

    fun accountFailure(e: AccountSignInException): String = when (e.reason) {
        AccountSignInFailure.Denied -> tr(R.string.account_fail_denied)
        AccountSignInFailure.Expired -> tr(R.string.account_fail_expired)
        AccountSignInFailure.TimedOut -> tr(R.string.account_fail_timed_out)
        AccountSignInFailure.Rejected -> tr(R.string.account_fail_rejected, e.code ?: "unknown")
        AccountSignInFailure.NoSubscription -> tr(R.string.account_fail_no_sub)
        AccountSignInFailure.Network -> tr(R.string.account_fail_network)
        AccountSignInFailure.BadIssuer -> tr(R.string.account_fail_issuer)
    }

    /** 用途卡上的一行:[title] 是模型名([isModel])、「用主模型」「不用」或「未设置」([unset]),[subtitle] 是补充说明。 */
    data class RoleLine(
        val title: String, val subtitle: String, val isModel: Boolean = false, val unset: Boolean = false,
        /** 副标题用「可用」的绿色(语音转文字写「可离线」)。 */
        val subtitleOk: Boolean = false,
    )

    fun roleName(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main)
        ModelRole.Vision -> tr(R.string.role_vision)
        ModelRole.Light -> tr(R.string.role_light)
    }

    /** 这个用途做什么。 */
    fun roleWhat(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main_what)
        ModelRole.Vision -> tr(R.string.role_vision_what)
        ModelRole.Light -> tr(R.string.role_light_what)
    }

    fun roleLabel(role: ModelRole) = roleName(role)

    /** 选择页顶上的说明。 */
    fun roleHint(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main_hint)
        ModelRole.Vision -> tr(R.string.role_vision_hint)
        ModelRole.Light -> tr(R.string.role_light_hint)
    }

    /** 第四个用途「语音转文字」不在 [ModelRole] 里(它不是对话模型),卡片的名字、图标、右边两行在这里。 */
    val SPEECH_LABEL: String get() = "${tr(R.string.role_speech)} · ${tr(R.string.role_speech_what)}"
    val SPEECH_ICON: ImageVector get() = MikaIcons.Mic

    fun speechLine(ui: ModelSetupUi): RoleLine {
        val s = SpeechOptions.summary(ui.speech, ui.systemSpeech, ui.providers)
        val unset = s.title == UNSET
        return RoleLine(s.title, s.sub.orEmpty(), isModel = !unset && (ui.speech is SpeechChoice.Provider || ui.speech is SpeechChoice.Doubao || ui.speech is SpeechChoice.FunAsr || ui.speech is SpeechChoice.Bailian), unset = unset, subtitleOk = s.subOk)
    }

    /** 主模型备援(项目 3)的卡片图标、名字和右边两行:和用途卡同一套说法。 */
    val FALLBACK_ICON: ImageVector get() = MikaIcons.Refresh
    val FALLBACK_CLEAR: String get() = tr(R.string.main_fallback_clear)

    fun fallbackLabel(role: ModelRole, slot: Int) = "${roleName(role)} ${tr(R.string.main_fallback_name, slot + 1)} · ${tr(R.string.main_fallback_what)}"

    fun fallbackLabel(slot: Int) = fallbackLabel(ModelRole.Main, slot)

    fun fallbackLine(ui: ModelSetupUi, role: ModelRole, slot: Int): RoleLine {
        val cfg = ui.roles.fallbacks(role).getOrNull(slot)?.let { id -> ui.configs.firstOrNull { it.id == id } }
        return if (cfg != null) RoleLine(cfg.model, ui.providerOf(cfg)?.let(::providerName) ?: cfg.name, isModel = true)
        else RoleLine(UNSET, tr(R.string.main_fallback_pick), unset = true)
    }

    fun fallbackLine(ui: ModelSetupUi, slot: Int): RoleLine = fallbackLine(ui, ModelRole.Main, slot)

    /** 声音模型卡片显示实际选中的合成模型和服务商。 */
    val VOICE_ICON: ImageVector get() = MikaIcons.Sound

    fun voiceLine(providers: List<Provider>, choice: VoiceChoice?): RoleLine {
        if (choice != null) {
            val provider = providers.firstOrNull { it.id == choice.providerId }
            if (provider != null) return RoleLine(choice.model, providerName(provider), isModel = true)
        }
        return RoleLine(UNSET, tr(R.string.role_voice_what), unset = true)
    }

    /** 图片模型的入口说明和图标。 */
    val IMAGE_LABEL: String get() = "${tr(R.string.role_image)} · ${tr(R.string.role_image_what)}"
    val IMAGE_ICON: ImageVector get() = MikaIcons.Photo

    fun imageLine(ui: ModelSetupUi): RoleLine = RoleLine(tr(R.string.role_image), tr(R.string.role_image_what), unset = true)

    fun roleIcon(role: ModelRole): ImageVector = when (role) {
        ModelRole.Main -> MikaIcons.Spark
        ModelRole.Vision -> MikaIcons.Eye
        ModelRole.Light -> MikaIcons.Leaf
    }

    /** 用途卡上写什么:选了模型写模型名和服务商;「用主模型」写主模型是谁;「不用」写只靠文字读屏。 */
    fun roleLine(ui: ModelSetupUi, role: ModelRole): RoleLine {
        fun modelLine(cfg: ProviderConfig) = RoleLine(cfg.model, ui.providerOf(cfg)?.let(::providerName) ?: cfg.name, isModel = true)
        val main = ui.configFor(ModelRole.Main)
        return when (val choice = ui.roles[role]) {
            null -> RoleLine(UNSET, if (ui.providers.isEmpty()) MAIN_ADD_FIRST else MAIN_PICK, unset = true)
            RoleChoice.SameAsMain -> RoleLine(SAME_AS_MAIN, main?.model ?: MAIN_NOT_PICKED)
            RoleChoice.Off -> RoleLine(OFF, OFF_NOTE)
            is RoleChoice.Model -> ui.configs.firstOrNull { it.id == choice.configId }?.let(::modelLine)
                ?: RoleLine(UNSET, MAIN_PICK, unset = true)
            is RoleChoice.Group -> ui.groups.firstOrNull { it.id == choice.groupId }?.let { g ->
                RoleLine(g.name, ModelsCopy.groupCount(g.enabledMembers.size), isModel = false)
            } ?: RoleLine(UNSET, MAIN_PICK, unset = true)
        }
    }

    fun choiceTitle(choice: RoleChoice) = if (choice == RoleChoice.Off) OFF else SAME_AS_MAIN

    fun choiceNote(role: ModelRole, choice: RoleChoice) = when {
        choice == RoleChoice.Off -> OFF_NOTE
        role == ModelRole.Vision -> VISION_SAME_NOTE
        else -> LIGHT_SAME_NOTE
    }

    fun roleNames(roles: List<ModelRole>) = roles.joinToString(tr(R.string.list_sep)) { roleName(it) }

    fun deleteTitle(name: String) = tr(R.string.models_delete_title, name)

    /** 删除前说清会影响哪些用途、哪些语音模型。 */
    fun deleteBody(roles: List<ModelRole>, voiceModels: List<VoiceModelRef> = emptyList()) =
        tr(R.string.models_delete_body) +
            if (roles.isEmpty()) tr(R.string.models_delete_unused) else tr(R.string.models_delete_used, roleNames(roles)) +
            if (voiceModels.isEmpty()) "" else tr(R.string.models_delete_voice, voiceModelNames(voiceModels))

    private fun voiceModelNames(refs: List<VoiceModelRef>) =
        refs.map { it.modelId }.distinct().joinToString(tr(R.string.list_sep))

    fun more(n: Int) = trPlural(R.plurals.models_more, n)

    fun viewAll(n: Int) = trPlural(R.plurals.models_view_all, n)

    fun allModelsTitle(name: String) = tr(R.string.models_all_title, name)

    fun allModelsNote(n: Int) = trPlural(R.plurals.models_all_note, n)

    fun useAsSubtitle(name: String) = tr(R.string.models_use_as, name)

    fun kindLabel(kind: ProviderConfig.Kind) = when (kind) {
        ProviderConfig.Kind.OpenAiCompat -> tr(R.string.models_openai_compat)
        ProviderConfig.Kind.Anthropic -> "Anthropic"
        ProviderConfig.Kind.Gemini -> "Gemini"
        ProviderConfig.Kind.OpenAiResponses -> "OpenAI Responses"
        ProviderConfig.Kind.ChatGpt -> "ChatGPT"
        ProviderConfig.Kind.Xai -> "xAI Responses"
    }

    fun shortUrl(url: String) = url.removePrefix("https://").removePrefix("http://")

    /** 记住的模型数;还没列过时为空,服务不提供列表时说明。 */
    fun modelCount(p: Provider): String? = when {
        p.models == null -> null
        p.models.isEmpty() -> tr(R.string.models_no_list)
        else -> trPlural(R.plurals.models_count, p.models.count { it.usable })
    }

    /** 服务商一行的说明:已连接 · N 个模型 · 哪些用途在用;刷新失败时说明。 */
    fun providerLine(p: Provider, state: ListState?, roles: List<ModelRole>): String =
        if (state is ListState.Failed) LIST_FAILED
        else listOfNotNull(CONNECTED, modelCount(p), roles.takeIf { it.isNotEmpty() }?.let { tr(R.string.models_used_by, roleNames(it)) }).joinToString(" · ")
}
