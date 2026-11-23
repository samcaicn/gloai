package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.choose
import com.wangzan101.mika.settings.configFor
import com.wangzan101.mika.settings.deleteGroup
import com.wangzan101.mika.settings.ensureConfig
import com.wangzan101.mika.settings.ensureConfigId
import com.wangzan101.mika.settings.isMainModel
import com.wangzan101.mika.settings.markTyped
import com.wangzan101.mika.settings.markVerified
import com.wangzan101.mika.settings.saveGroup
import com.wangzan101.mika.settings.setDailyTokenBudget
import com.wangzan101.mika.settings.setFallback
import com.wangzan101.mika.settings.setModelContext
import com.wangzan101.mika.settings.setModelServiceTier
import com.wangzan101.mika.settings.setModelVision
import com.wangzan101.mika.settings.setReasoningEffort
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.TranscribeMethod
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.settings.ui.models.ModelSetupViewModel.ProbeOutcome

// 模型设置:选用途、备用、模型组、语音模型与各模型设置
/** 把这家默认的那个模型(见 [ModelOptions.defaultMain])设为主模型:和 [pick] 一样先试调用,进度在 [ModelSetupUi.manualPick]。 */
fun ModelSetupViewModel.useAsMain(providerId: String) {
    val p = store.provider(providerId) ?: return
    val options = ModelOptions.ofProvider(p, store.list())
    val m = ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id ?: return
    pick(ModelRole.Main, providerId, m)
}

fun ModelSetupViewModel.setReasoningEffort(role: ModelRole, effort: String?) {
    store.setReasoningEffort(role, effort)
    sync()
}

fun ModelSetupViewModel.setModelContext(configId: String, settings: ModelContextSettings): Boolean {
    val saved = store.setModelContext(configId, settings)
    sync()
    return saved
}

fun ModelSetupViewModel.setDailyTokenBudget(providerId: String, limit: Long?): Boolean {
    val saved = store.setDailyTokenBudget(providerId, limit)
    sync()
    return saved
}

fun ModelSetupViewModel.setModelVision(providerId: String, model: String, sees: Boolean) {
    store.setModelVision(providerId, model, sees)
    sync()
}

fun ModelSetupViewModel.setModelServiceTier(providerId: String, model: String, tier: OpenAiServiceTier?): Boolean {
    val saved = store.setModelServiceTier(providerId, model, tier)
    sync()
    return saved
}

/**
 * 服务商详情「用作」里明确改选用途。
 * 主模型和 [pick] 一样先试调用;看图、轻量是手填的(列表里没有)时也先试调用(见 [verifyTyped])。没通过不改;
 * 进度和原因在 [ModelSetupUi.manualPick]。保存成功后调 [onDone]。
 */
fun ModelSetupViewModel.useFromProvider(role: ModelRole, providerId: String, model: String, onDone: () -> Unit = {}) {
    saveModelSelection(
        providerId, model, clearNote = role == ModelRole.Main,
        // 主模型照样先试调用;看图、轻量只有手填的(列表里没有)才试,列表里的不试
        verify = when {
            role == ModelRole.Main -> { p, name -> verifyMain(p, name) }
            else -> { p, name -> if (listed(p, name)) null else verifyTyped(p, name, role) }
        },
        onPicked = onDone,
    ) { provider, name ->
        val caps = ModelOptions.ofProvider(provider, store.list()).firstOrNull { it.id == name }?.capabilities
            ?: ModelOptions.baseOf(provider)
        if (store.use(role, provider.id, name, caps)) null else tr(R.string.model_context_save_failed)
    }
}

/**
 * 保存从列表里选的对话模型。主模型先试调用这一个模型(见 [verifyMain]),通过才换,
 * 没通过原来的主模型不动;看图、轻量不试调用。手填的走 [pickManual]。
 */
fun ModelSetupViewModel.pick(role: ModelRole, providerId: String, model: String, onPicked: () -> Unit = {}) =
    pickRole(role, providerId, model, onPicked, if (role == ModelRole.Main) { p, name -> verifyMain(p, name) } else null)

/**
 * 选择页里手填的模型名:不论哪个用途都先试调用一次(见 [verifyTyped];主模型见 [verifyMain]),通了才用,
 * 列表里没有的记为手填;没通过说原因,原来的选择不动。
 */
fun ModelSetupViewModel.pickManual(role: ModelRole, providerId: String, model: String, onPicked: () -> Unit = {}) =
    pickRole(role, providerId, model, onPicked) { p, name ->
        if (role == ModelRole.Main) verifyMain(p, name) else verifyTyped(p, name, role)
    }

internal fun ModelSetupViewModel.pickRole(
    role: ModelRole, providerId: String, model: String, onPicked: () -> Unit,
    verify: (suspend (Provider, String) -> String?)?,
) {
    saveModelSelection(providerId, model, clearNote = role == ModelRole.Main, onPicked = onPicked, verify = verify) { provider, name ->
        if (use(role, provider, name, ModelOptions.ofProvider(provider, store.list()))) null
        else tr(R.string.model_context_save_failed)
    }
}

/** 保存从列表里选的备援模型,一般不试调用;主模型的备援例外见下。手填的走 [pickFallbackManual]。 */
fun ModelSetupViewModel.pickFallback(role: ModelRole, slot: Int, providerId: String, model: String, onPicked: () -> Unit = {}) =
    saveFallback(role, slot, providerId, model, onPicked, verify = if (role != ModelRole.Main) null else { p, name ->
        // 主模型的备援:跳过测试存下的那家、或列表里没有的(手填的),先试调用一次
        if (!p.savedUntested && listed(p, name)) null else verifyTyped(p, name, ModelRole.Main)
    })

/**
 * [model] 在这家列出的模型目录里,且 Mika 用得上([ListedModel.usable]:目录写明只有别的接口的不算)。
 * 用不上的按手填处理,先试调用——目录里有、Mika 不会说的模型不能被静默设成看图/轻量/备援。
 */
internal fun ModelSetupViewModel.listed(p: Provider, model: String): Boolean = p.models.orEmpty().any { it.id == model && it.usable }

/** 备援里手填的模型名:先试调用一次(见 [verifyTyped]),通了才设上;没通过这一位不动。 */
fun ModelSetupViewModel.pickFallbackManual(role: ModelRole, slot: Int, providerId: String, model: String, onPicked: () -> Unit = {}) =
    saveFallback(role, slot, providerId, model, onPicked) { p, name -> verifyTyped(p, name, role) }

internal fun ModelSetupViewModel.saveFallback(
    role: ModelRole, slot: Int, providerId: String, model: String, onPicked: () -> Unit,
    verify: (suspend (Provider, String) -> String?)?,
) {
    saveModelSelection(providerId, model, clearNote = true, onPicked = onPicked, verify = verify) { provider, name ->
        if (store.configFor(role)?.let { it.providerId == provider.id && it.model == name } == true) {
            return@saveModelSelection tr(if (role == ModelRole.Main) R.string.main_fallback_is_main else R.string.main_fallback_failed)
        }
        val caps = ModelOptions.ofProvider(provider, store.list()).firstOrNull { it.id == name }?.capabilities
            ?: ModelOptions.baseOf(provider)
        val targetCaps = if (role == ModelRole.Vision) caps.copy(vision = true) else caps.copy(toolCalls = true)
        val configId = store.ensureConfigId(provider.id, name, targetCaps)
        if (configId != null && store.setFallback(role, slot, configId)) null
        else tr(R.string.main_fallback_failed)
    }
}

/**
 * 选择页、「用作」面板保存一个模型:有 [verify] 的先过它(设主模型的试调用),
 * 再 [save]。进度和原因记在 [ModelSetupUi.manualPick]。
 */
internal fun ModelSetupViewModel.saveModelSelection(
    providerId: String, model: String, clearNote: Boolean, onPicked: () -> Unit,
    verify: (suspend (Provider, String) -> String?)? = null,
    save: (Provider, String) -> String?,
) {
    val name = model.trim()
    if (name.isEmpty() || _state.value.manualPick[providerId] == ListState.Running) return
    _state.update { it.copy(manualPick = it.manualPick + (providerId to ListState.Running)) }
    scope.launch {
        val problem = try {
            val checked = store.provider(providerId)
            when {
                checked == null -> tr(R.string.model_context_save_failed)
                else -> verify?.invoke(checked, name)
            } ?: withContext(io) {
                val provider = store.provider(providerId) ?: return@withContext tr(R.string.model_context_save_failed)
                val failure = save(provider, name)
                if (failure == null && provider.models.orEmpty().none { it.id == name }) {
                    store.markTyped(providerId, name)
                }
                failure
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            tr(R.string.model_context_save_failed)
        }
        _state.update {
            snapshot(it.copy(
                manualPick = if (problem == null) it.manualPick - providerId
                    else it.manualPick + (providerId to ListState.Failed(problem)),
                probeNote = if (problem == null && clearNote) null else it.probeNote,
                pickToProbe = if (problem == null && clearNote) null else it.pickToProbe,
            ))
        }
        if (problem == null) onPicked()
    }
}

/** 撤掉指定角色的第 [slot] 位备援。 */
fun ModelSetupViewModel.clearFallback(role: ModelRole, slot: Int) {
    scope.launch {
        withContext(io) { store.setFallback(role, slot, null) }
        sync()
    }
}

/** 撤掉第 [slot] 位主模型备援(项目 3)。 */
fun ModelSetupViewModel.clearFallback(slot: Int) = clearFallback(ModelRole.Main, slot)

/** 看图、轻量的固定选项:「用主模型」或(只有看图)「不用」。 */
fun ModelSetupViewModel.choose(role: ModelRole, choice: RoleChoice) {
    scope.launch {
        withContext(io) { store.choose(role, choice) }
        sync()
    }
}

/** 把某个用途切到一个模型组;组不可用时不做。 */
fun ModelSetupViewModel.chooseGroup(role: ModelRole, groupId: String) {
    scope.launch {
        withContext(io) { store.choose(role, RoleChoice.Group(groupId)) }
        sync()
    }
}

/** 保存组(新建或替换同 id);成员配置不存在、或主模型组不满足全员支持工具时返回 false。 */
suspend fun ModelSetupViewModel.saveGroup(group: ModelGroup): Boolean = withContext(io) { store.saveGroup(group) }

suspend fun ModelSetupViewModel.deleteGroup(groupId: String) = withContext(io) { store.deleteGroup(groupId) }

/** 组编辑时把一个「服务商 + 模型」加成成员:复用已有配置,没有就按当前能力建一条。 */
suspend fun ModelSetupViewModel.ensureConfig(providerId: String, model: String): String? = withContext(io) {
    val p = store.provider(providerId) ?: return@withContext null
    val caps = ModelOptions.ofProvider(p, store.list()).firstOrNull { it.id == model }?.capabilities
        ?: ModelOptions.baseOf(p)
    store.ensureConfig(providerId, model, caps)
}

/** 选「手机自带」或「不用」,当场生效。 */
fun ModelSetupViewModel.pickSpeech(choice: SpeechChoice) {
    if (choice is SpeechChoice.Provider) return
    if (stt?.migrated == true) {
        when (choice) {
            SpeechChoice.Auto, SpeechChoice.System, SpeechChoice.Off -> {
                if (choice != SpeechChoice.Off) speech?.retrySystem()
                stt.select(choice)
            }
            else -> return
        }
    } else {
        speech?.set(choice)
    }
    _speechProbe.value = emptyMap()
    sync()
}

/** 迁移后的目录选择只写模型引用,不试转、不探测协议。 */
fun ModelSetupViewModel.pickSttModel(ref: VoiceModelRef, onPicked: () -> Unit = {}) {
    val controller = stt?.takeIf { it.migrated } ?: return
    controller.select(ref)
    _speechProbe.value = emptyMap()
    sync()
    onPicked()
}

/**
 * 迁移后按目录引用直接选择;迁移前不再拿静音试转探测接口,直接按服务商类型落成显式选择(默认接口),
 * 然后调 [onPicked]。旧选择经迁移后仍带着显式 [TranscribeMethod],运行时不重新探测。
 */
fun ModelSetupViewModel.pickSpeechModel(providerId: String, model: String, onPicked: () -> Unit = {}) {
    val m = model.trim()
    if (m.isEmpty() || _state.value.speechTesting != null) return
    val migrated = stt?.takeIf { it.migrated }
    if (migrated != null) {
        val snapshot = migrated.catalog.state.value
        val matches = snapshot.models.filter { candidate ->
            candidate.id == m && candidate.stt != null && snapshot.services.any { service ->
                service.id == candidate.serviceId && (
                    service.id == "provider:$providerId" ||
                        (service.account as? VoiceAccount.ExistingProvider)?.providerId == providerId
                    )
            }
        }.map { VoiceModelRef(it.serviceId, it.id, VoiceCapability.STT) }
        if (matches.size == 1) pickSttModel(matches.single(), onPicked)
        return
    }
    val settings = speech ?: return
    val p = store.provider(providerId) ?: return
    // 迁移前没有目录可依,按服务商类型落成显式选择;默认接口,不再试转探测
    val method = if (p.kind == ProviderConfig.Kind.Gemini) TranscribeMethod.GeminiInline else TranscribeMethod.AudioTranscriptions
    settings.set(SpeechChoice.Provider(p.id, m, method))
    sync()
    onPicked()
}

internal fun ModelSetupViewModel.proxyReason(e: ModelError.Proxy): String = tr(
    if (e.failure == ProxyFailure.AuthFailed) R.string.net_proxy_auth_failed else R.string.net_proxy_unreachable,
)

/** 选择页打开时,上次试转的结果作废。 */
fun ModelSetupViewModel.clearSpeechProbe() { _speechProbe.value = emptyMap() }

/** 选择页、「用作」面板打开时清掉上次保存的结果;还在试调用的留着(结束时自己更新)。 */
fun ModelSetupViewModel.clearManualPick() = _state.update { it.copy(manualPick = it.manualPick.filterValues { v -> v == ListState.Running }) }

/** 只测试用户在服务商页确认的这一个模型。 */
fun ModelSetupViewModel.retestModel(providerId: String, model: String) {
    val provider = store.provider(providerId) ?: return
    val key = providerId to model
    if (model !in _state.value.usedChatModels(provider) || _state.value.retest[key] == ListState.Running) return
    _state.update { it.copy(retest = it.retest + (key to ListState.Running)) }
    scope.launch {
        val outcome = probeSaved(provider, model)
        val problem = when {
            outcome is ProbeOutcome.Failed -> outcome.message
            outcome is ProbeOutcome.NoToolCall && store.isMainModel(providerId, model) ->
                tr(R.string.model_probe_no_tools_pick, model)
            else -> null
        }
        if (problem == null) withContext(io) { store.markVerified(providerId, model) }
        _state.update {
            snapshot(it.copy(retest = if (problem == null) it.retest - key else it.retest + (key to ListState.Failed(problem))))
        }
    }
}

internal fun ModelSetupViewModel.use(role: ModelRole, p: Provider, model: String, options: List<ModelOption>): Boolean {
    val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(p)
    return store.use(role, p.id, model, caps)
}
