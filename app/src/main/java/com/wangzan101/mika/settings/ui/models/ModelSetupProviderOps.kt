package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.accountSession
import com.wangzan101.mika.settings.addProviderKey
import com.wangzan101.mika.settings.chatGptSession
import com.wangzan101.mika.settings.hasKey
import com.wangzan101.mika.settings.listModels
import com.wangzan101.mika.settings.rememberModels
import com.wangzan101.mika.settings.removeProvider
import com.wangzan101.mika.settings.removeProviderKey
import com.wangzan101.mika.settings.signOutAccount
import com.wangzan101.mika.settings.signOutChatGpt
import com.wangzan101.mika.settings.updateProviderKey
import com.wangzan101.mika.voice.model.VoiceModelRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.chat.ui.isActive

// 模型设置:刷新列表、换 Key 与移除服务商
/** 重新列一次这个服务商的模型并记下;失败时记下原因。 */
fun ModelSetupViewModel.refresh(providerId: String) {
    val p = store.provider(providerId) ?: return
    if (refreshJobs[providerId]?.isActive == true) return
    _state.update { it.copy(listing = it.listing + (providerId to ListState.Running)) }
    refreshJobs[providerId] = scope.launch {
        val r = listSafely { store.listModels(p) }
        val models: List<ListedModel>? = when (r) {
            is ModelListResult.Ok -> r.models
            is ModelListResult.Failed -> if (r.reason == ListFailure.NotSupported) emptyList() else null
        }
        if (models != null) withContext(io) { store.rememberModels(p.id, models) }
        val after = if (r is ModelListResult.Failed && models == null) ListState.Failed(listMessage(r)) else ListState.Idle
        _state.update { snapshot(it.copy(listing = it.listing + (providerId to after))) }
    }
}

/** 选择页、服务商页打开时:还没记住列表的服务商列一次。 */
fun ModelSetupViewModel.ensureListed() {
    _state.value.providers.filter { it.models == null && _state.value.listing[it.id] == null && listable(it) }.forEach { refresh(it.id) }
}

/** 退出了登录的列不了模型,等再登录。 */
internal fun ModelSetupViewModel.listable(p: Provider) = if (p.signIn != null) store.hasKey(p.id) else when (p.kind) {
    ProviderConfig.Kind.ChatGpt -> store.chatGptSession(p.id)?.signedIn == true
    ProviderConfig.Kind.Xai -> store.accountSession(p.id)?.signedIn == true
    else -> true
}

/**
 * 换 Key,然后用新 Key 重新列一次模型。零宽先去掉;换行和其它放不进请求头的字符不保存、不列模型。
 */
fun ModelSetupViewModel.changeKey(providerId: String, apiKey: String) {
    val k = headerKey(apiKey) ?: return
    if (k.isEmpty()) return
    scope.launch {
        try {
            withContext(io) { store.updateProviderKey(providerId, k) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(listing = it.listing + (providerId to ListState.Failed(listFailureText(e)))) }
            return@launch
        }
        refreshJobs.remove(providerId)?.cancel()
        refresh(providerId)
        sync()
    }
}

fun ModelSetupViewModel.addProviderKey(providerId: String, apiKey: String) {
    val k = headerKey(apiKey) ?: return
    if (k.isEmpty()) return
    scope.launch {
        try {
            withContext(io) { store.addProviderKey(providerId, k) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(listing = it.listing + (providerId to ListState.Failed(listFailureText(e)))) }
            return@launch
        }
        refreshJobs.remove(providerId)?.cancel()
        refresh(providerId)
        sync()
    }
}

fun ModelSetupViewModel.removeProviderKey(providerId: String, keyToRemove: String) {
    scope.launch {
        try {
            withContext(io) { store.removeProviderKey(providerId, keyToRemove) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(listing = it.listing + (providerId to ListState.Failed(listFailureText(e)))) }
            return@launch
        }
        refreshJobs.remove(providerId)?.cancel()
        refresh(providerId)
        sync()
    }
}

/** 删服务商前预览它名下的语音模型能力引用;删除面板靠它说清影响。 */
fun ModelSetupViewModel.providerVoiceUsage(providerId: String): List<com.wangzan101.mika.voice.model.VoiceModelRef> =
    voiceAccounts?.providerVoiceUsage(providerId) ?: emptyList()

/** 删掉服务商和它的 Key;用着它的用途退回默认,依赖它的语音模型一并清掉。 */
fun ModelSetupViewModel.removeProvider(providerId: String) {
    scope.launch {
        withContext(io) {
            // ChatGPT 的先在服务端注销登录,再删
            when (store.provider(providerId)?.kind) {
                ProviderConfig.Kind.ChatGpt -> store.signOutChatGpt(providerId)
                ProviderConfig.Kind.Xai -> store.signOutAccount(providerId)
                else -> Unit
            }
            // OpenRouter 授权来的 Key 随服务商一起删;OpenRouter 那边的 Key 要用户自己到 Keys 页面删
            store.removeProvider(providerId)
            voiceAccounts?.deleteProviderWithVoiceUsage(providerId)
        }
        _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
    }
}
