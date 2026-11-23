package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.ProbeResult
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.connect
import com.wangzan101.mika.settings.isMainModel
import com.wangzan101.mika.settings.listModels
import com.wangzan101.mika.settings.markTyped
import com.wangzan101.mika.settings.markVerified
import com.wangzan101.mika.settings.probe
import com.wangzan101.mika.settings.providerKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.wangzan101.mika.settings.ui.models.ModelSetupViewModel.ProbeOutcome
import com.wangzan101.mika.settings.ui.models.ModelSetupViewModel.Companion.PROBE_TIMEOUT_MS
import com.wangzan101.mika.settings.ui.models.ModelSetupViewModel.Companion.MAX_NAME

// 模型设置:添加服务商表单、测试保存与模型探测
fun ModelSetupViewModel.openForm() = _state.update { it.copy(formOpen = true) }

fun ModelSetupViewModel.closeForm() {
    testJob?.cancel()
    _state.update { it.copy(formOpen = false, form = AddForm.of(ProviderPresets.all.first())) }
}

fun ModelSetupViewModel.selectPreset(label: String) {
    val p = AddForm.presetFor(label)
    if (p.label == _state.value.form.preset) return
    val form = _state.value.form
    edit { AddForm.of(p).copy(apiKey = form.apiKey, keyNotice = form.keyNotice) }
}

// 换了接口类型或地址,上次「没列出模型」就不作数了,要重新列
fun ModelSetupViewModel.setKind(kind: ProviderConfig.Kind) = edit { it.copy(kind = kind, noList = false) }

fun ModelSetupViewModel.setBaseUrl(url: String) = edit { it.copy(baseUrl = url, noList = false) }

fun ModelSetupViewModel.setAccountId(input: String) {
    val trimmed = input.trim()
    val accountId = when {
        trimmed.contains("/accounts/") -> trimmed.substringAfter("/accounts/").substringBefore("/").trim()
        trimmed.contains("dash.cloudflare.com/") -> trimmed.substringAfter("dash.cloudflare.com/").substringBefore("/").trim()
        else -> trimmed
    }
    val newUrl = if (accountId.isBlank()) {
        "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1"
    } else {
        "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1"
    }
    setBaseUrl(newUrl)
}

/** 粘贴或逐字改 Key:去掉零宽;换行和其它放不进请求头的字符原样留下并立刻标出来。 */
fun ModelSetupViewModel.setApiKey(key: String) {
    val accepted = ApiKeyText.accept(key)
    edit { it.copy(apiKey = accepted.text, keyNotice = accepted.notice) }
}

fun ModelSetupViewModel.setModel(model: String) = edit { it.copy(model = model) }

/** 「手动填写模型名」:打开时显示模型名一栏;收起时清掉填的(没列出模型时那一栏一直在)。 */
fun ModelSetupViewModel.setManual(on: Boolean) = edit { it.copy(manual = on, model = if (on || it.noList) it.model else "") }

// 按字符(码点)截,不把 emoji 截成半个
fun ModelSetupViewModel.setName(name: String) = edit {
    val n = name.codePointCount(0, name.length)
    it.copy(name = if (n <= MAX_NAME) name else name.substring(0, name.offsetByCodePoints(0, MAX_NAME)))
}

/** 连接信息变了:进行中的测试和上次的失败原因作废。 */
internal fun ModelSetupViewModel.edit(change: (AddForm) -> AddForm) {
    testJob?.cancel()
    _state.update { it.copy(form = change(it.form).copy(test = ListState.Idle)) }
}

/**
 * 测试并保存:列得出模型就存下这家服务商和列表(列不出时请用户手填,或 [saveWithoutTest] 先存下)。
 * 已经有主模型时只列模型、不发对话请求。还没有主模型时(首次上手、第一家)挑一个候选:手填了模型名的用它,
 * 否则按 [ModelOptions.defaultMain],再不行用第一个对话模型(标为 TTS 的不算);让它调用一次工具(见 [verifyMain]),
 * 通了当主模型;没通过服务商照样存下、不定主模型,说明原因并给「选主模型」的入口([ModelSetupUi.pickToProbe])。
 */
fun ModelSetupViewModel.testAndSave() {
    val f = _state.value.form
    if (!f.canTest) return
    val key = headerKey(f.apiKey) ?: return rejectKey()
    testJob?.cancel()
    _state.update { it.copy(form = it.form.copy(test = ListState.Running), probeNote = null, pickToProbe = null) }
    testJob = scope.launch {
        val baseUrl = f.baseUrl.trim()
        val typed = f.typedModel
        val models = when (val r = listSafely { store.listModels(f.kind, baseUrl, key.ifEmpty { null }, f.sessionHeader) }) {
            is ModelListResult.Ok -> r.models
            is ModelListResult.Failed -> {
                if (r.reason != ListFailure.NotSupported) {
                    _state.update { it.copy(form = it.form.copy(test = ListState.Failed(listMessage(r)), manual = true)) }
                    return@launch
                }
                emptyList()
            }
        }
        if (models.isEmpty()) {
            _state.update { it.copy(form = it.form.copy(test = ListState.Idle, noList = true)) }
            return@launch
        }
        val saved = try {
            withContext(io) {
                store.connect(
                    f.providerName, f.kind, baseUrl, key, models, f.sessionHeader,
                    typed = setOfNotNull(typed.takeIf { it.isNotEmpty() && models.none { model -> model.id == it } }),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(form = it.form.copy(test = ListState.Failed(listFailureText(e)))) }
            return@launch
        }
        var note: String? = null
        if (!store.roles.value.hasMain) {
            val options = ModelOptions.ofProvider(saved, store.list())
            // 手填的是列表里写明 Mika 调不了的(不在 options 里)就不试它
            val candidate = typed.takeIf { t -> t.isNotBlank() && (models.none { it.id == t } || options.any { it.id == t }) }
                ?: ModelOptions.defaultMain(options, f.familyTokens) ?: options.firstOrNull()?.id
            if (candidate != null) {
                val problem = verifyMain(saved, candidate)
                if (problem == null) {
                    withContext(io) { store.provider(saved.id)?.let { use(ModelRole.Main, it, candidate, options) } }
                } else {
                    note = tr(R.string.model_saved_no_main, problem)
                }
            }
        }
        _state.update {
            snapshot(it.copy(
                form = AddForm.of(ProviderPresets.all.first()), formOpen = false,
                probeNote = note, pickToProbe = saved.id.takeIf { note != null },
            ))
        }
    }
}

/**
 * 不测试,直接保存:只按填的接口类型、地址和 Key 存下这家服务商,不列模型、不试调用(见 [AddForm.canSaveUntested])。
 * 存下的这家带着 [Provider.savedUntested]，服务商页提供刷新目录的入口。
 *
 * 手填了模型名([AddForm.typedModel])时,只把它记为手填——列表里没有它、或这家列不出模型,刷新列表也不会丢。
 *
 * 列表参数给的是空列表(「列过但一个也没有」)而不是 null:这样「没列出模型」这件事和 [Provider.savedUntested]
 * 一起把两种情形分清楚了——真的没有列表,还是这次没去列。
 */
fun ModelSetupViewModel.saveWithoutTest() {
    val f = _state.value.form
    if (!f.canSaveUntested) return
    val key = headerKey(f.apiKey) ?: return rejectKey()
    testJob?.cancel()
    _state.update { it.copy(form = it.form.copy(test = ListState.Running), probeNote = null, pickToProbe = null) }
    testJob = scope.launch {
        val baseUrl = f.baseUrl.trim()
        val typed = f.typedModel
        try {
            withContext(io) {
                store.connect(
                    f.providerName, f.kind, baseUrl, key.ifEmpty { null }, emptyList(), f.sessionHeader, untested = true,
                ).also { p -> if (typed.isNotEmpty()) store.markTyped(p.id, typed) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(form = it.form.copy(test = ListState.Failed(listFailureText(e)))) }
            return@launch
        }
        _state.update {
            snapshot(it.copy(
                form = AddForm.of(ProviderPresets.all.first()), formOpen = false,
                probeNote = tr(R.string.model_saved_untested, f.providerName),
            ))
        }
    }
}

/**
 * 拿去放进请求头的 Key:再收一次(零宽去掉),首尾空白去掉。换行算放不进头。
 * 还含有放不进头的字符时为 null,调用方不能发请求、也不能保存。
 */
internal fun ModelSetupViewModel.headerKey(raw: String): String? {
    val accepted = ApiKeyText.accept(raw)
    if (accepted.notice == ApiKeyText.Notice.IllegalCharacters) return null
    val key = accepted.text.trim()
    if (key.isEmpty()) return ""
    val parsed = KeyPoolManager.shared.parseKeys(key)
    return if (parsed.isNotEmpty() && parsed.all { ApiKeyText.isHeaderLegal(it) }) key else null
}

/** 表单上的 Key 放不进请求头:标红,停在表单上,不发请求。 */
internal fun ModelSetupViewModel.rejectKey() {
    _state.update { it.copy(form = it.form.copy(keyNotice = ApiKeyText.Notice.IllegalCharacters, test = ListState.Idle)) }
}

/** 列模型。头值仍让 OkHttp 抛时收成失败结果,不让异常逃出协程把进程打掉。消息里不带头的原文。 */
internal suspend fun ModelSetupViewModel.listSafely(block: suspend () -> ModelListResult): ModelListResult = try {
    withContext(io) { block() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    listFailure(e)
}

internal fun ModelSetupViewModel.listFailure(e: Exception): ModelListResult.Failed = when (e) {
    is ModelError.IllegalHeader, is IllegalArgumentException -> ModelListResult.Failed(ListFailure.IllegalKey)
    else -> ModelListResult.Failed(ListFailure.of(e), e.message.orEmpty())
}

internal fun ModelSetupViewModel.listMessage(r: ModelListResult.Failed): String =
    if (r.reason == ListFailure.IllegalKey) tr(R.string.model_key_illegal_chars) else r.message

/** 列模型以外的失败给人看的一句。头值不合法时不把异常原文(可能含 Key)露出去。 */
internal fun ModelSetupViewModel.listFailureText(e: Exception): String = when (e) {
    is ModelError.IllegalHeader, is IllegalArgumentException -> tr(R.string.model_key_illegal_chars)
    else -> e.message.orEmpty()
}

/**
 * 让 [model] 调用一次工具,和真实任务走同一条建模型的路(请求头、会话号都一样):Key 不对、少了服务商要的请求头、
 * 模型名不对、模型不会调用工具,这里就露出来,不等到第一个任务。超过 [PROBE_TIMEOUT_MS] 没回算失败,服务端的报错原样带上;
 * 找不到模型时说明是哪个，以及 [exampleKind] 这一类接口的模型名示例。
 */
internal suspend fun ModelSetupViewModel.probeModel(
    kind: ProviderConfig.Kind, baseUrl: String, key: String?, sessionHeader: String?,
    model: String, endpoint: ModelEndpoint?, caps: Capabilities, exampleKind: ProviderConfig.Kind,
    providerId: String = "",
): ProbeOutcome {
    val result = try {
        withContext(io) {
            withTimeout(PROBE_TIMEOUT_MS) {
                store.probe(kind, baseUrl, key, sessionHeader, model, endpoint, caps, providerId)
            }
        }
    } catch (e: TimeoutCancellationException) {
        return ProbeOutcome.Failed(tr(R.string.model_probe_timeout, model, (PROBE_TIMEOUT_MS / 1000).toInt()))
    }
    return when (result) {
        ProbeResult.ToolCalled -> ProbeOutcome.Passed
        ProbeResult.NoToolCall -> ProbeOutcome.NoToolCall(model)
        is ProbeResult.Failed -> when (val e = result.error) {
            is ModelError.ModelNotAvailable -> {
                val detail = e.detail.ifEmpty { e.message.orEmpty() }
                ProbeOutcome.Failed(tr(R.string.model_probe_not_available, model, detail))
            }
            is ModelError.ToolsUnsupported -> ProbeOutcome.Failed(tr(R.string.model_probe_no_tools_pick, model))
            is ModelError.ModelNotFound -> ProbeOutcome.Failed(
                tr(R.string.model_probe_not_found, model, ModelSetupCopy.modelExample(exampleKind)),
            )
            // 代理那一段的问题:说是代理,不说成模型或服务商不行
            is ModelError.Proxy -> ProbeOutcome.Failed(tr(R.string.model_probe_failed, model, proxyReason(e)))
            is ModelError.IllegalHeader -> ProbeOutcome.Failed(tr(R.string.model_key_illegal_chars))
            else -> ProbeOutcome.Failed(tr(R.string.model_probe_failed, model, e.message.orEmpty()))
        }
    }
}

/**
 * 已连上的服务商里试调用 [model]:用存着的 Key、请求头,能力按已有的配置或预设。
 */
internal suspend fun ModelSetupViewModel.probeSaved(p: Provider, model: String): ProbeOutcome {
    val stored = store.provider(p.id)
        ?: return ProbeOutcome.Failed(tr(R.string.model_probe_not_found, model, ModelSetupCopy.modelExample(p.kind)))
    val options = ModelOptions.ofProvider(stored, store.list())
    val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(stored)
    val listed = stored.models?.firstOrNull { it.id == model }
    val example = ProviderPresets.forHost(stored.baseUrl)?.kind ?: stored.kind
    return probeModel(
        stored.kind, stored.baseUrl, store.providerKey(p.id), stored.sessionHeader, model, listed?.endpoint, caps, example,
        providerId = stored.id,
    )
}

/**
 * 设主模型前的把关:让 [model] 调用一次工具,只回话、不调用工具也算没通过(主模型要会调用工具)。
 * [Provider.verifiedModels] 只说明回复过,不作为跳过的依据;已经是主模型不再测。
 * 通过时记为核实过。返回没通过的原因,通过为 null。
 */
internal suspend fun ModelSetupViewModel.verifyMain(p: Provider, model: String): String? {
    // verifiedModels 只说明回复过(转写、无工具回复也算),不能证明会调用工具;只有已经是主模型的不再测
    if (store.isMainModel(p.id, model)) return null
    return when (val outcome = probeSaved(p, model)) {
        ProbeOutcome.Passed -> {
            withContext(io) { store.markVerified(p.id, model) }
            null
        }
        is ProbeOutcome.NoToolCall -> tr(R.string.model_probe_no_tools_pick, model)
        is ProbeOutcome.Failed -> outcome.message
    }
}

/**
 * 选择页里手填的模型名,看图、轻量和备援也先试调用这一个(主模型走 [verifyMain]):手填错了这里就露出来,不等到任务失败。
 * 只回话、不调用工具也算没通过,看图例外(看图不靠工具)。通过(含只回话的看图)时记为核实过。返回没通过的原因,通过为 null。
 */
internal suspend fun ModelSetupViewModel.verifyTyped(p: Provider, model: String, role: ModelRole): String? =
    when (val outcome = probeSaved(p, model)) {
        ProbeOutcome.Passed -> {
            withContext(io) { store.markVerified(p.id, model) }
            null
        }
        is ProbeOutcome.NoToolCall -> if (role == ModelRole.Vision) {
            withContext(io) { store.markVerified(p.id, model) }
            null
        } else tr(R.string.model_probe_no_tools_pick, model)
        is ProbeOutcome.Failed -> outcome.message
    }
