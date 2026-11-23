package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ListedModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 服务商存储:连接、记住模型、验证状态与移除服务商
/**
 * 新增或替换同 id 的模型配置(新增的要再交给 [choose],否则下次读入时没人用会被删掉)。
 * 配置挂到 [ProviderConfig.providerId] 指的服务商下(接口类型、地址、名字以服务商的为准);
 * 没指或指的不存在,就按接口类型、地址和名字找对应的服务商,找不到新建一个。
 * [apiKey] 是那个服务商的 Key:null 表示不改,空串表示清除。
 */
fun ProviderStore.save(cfg: ProviderConfig, apiKey: String?) {
    synchronized(this) {
        val p = provider(cfg.providerId) ?: findOrAddProvider(ProviderConnection.displayName(cfg), cfg.kind, cfg.baseUrl)
        val attached = cfg.copy(providerId = p.id, kind = p.kind, baseUrl = p.baseUrl, name = p.name)
        val list = _configs.value
        val i = list.indexOfFirst { it.id == cfg.id }
        if (attached.id in _roles.value.referenced && !attached.canUseForChat()) {
            require(list.getOrNull(i)?.let { it.providerId == p.id && it.model == attached.model } == true)
        }
        _configs.value = if (i >= 0) list.toMutableList().also { it[i] = attached } else list + attached
        if (apiKey != null) setKey(p.id, apiKey)
        writeConfig()
    }
}

/**
 * 「测试并保存」连上了一个服务商:存下它(名字、接口类型、地址、Key)和列出的模型。
 * 接口类型、地址和名字都相同的服务商已经有了,就更新那一个(换 Key、换列表),不重复添加。
 * [apiKey] 为 null 时不改已存的 Key。[verified] 是这次试调用得到回复的模型名,记进 [Provider.verifiedModels];
 * [typed] 是其中用户手填的,记进 [Provider.typedModels]。
 * [signIn] 是 Key 的来历(见 [Provider.signIn]):用户自己填 Key 的为 null,授权来的为那一家的标记。
 * 两种来历各是各的服务商(地址、名字一样也不合并):授权不会顶掉用户自己填的 Key,退出登录也只删授权来的那把。
 * [untested] 为真是用户跳过测试直接存下的(见 [Provider.savedUntested]):一个模型都没列、也没试调用。
 * 已经有这一家、而且它列过模型或核实过时,不会因为这个参数被重新标成没测过。
 */
fun ProviderStore.connect(
    name: String, kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, models: List<ListedModel>,
    sessionHeader: String? = ProviderPresets.forBaseUrl(baseUrl)?.sessionHeader,
    verified: Set<String> = emptySet(),
    signIn: String? = null,
    typed: Set<String> = emptySet(),
    untested: Boolean = false,
): Provider {
    return synchronized(this) {
        val found = findOrAddProvider(name, kind, baseUrl, signIn)
        // 跳过测试时没有列表可存:这家之前列过的留着,不要用空表把已有的列表盖掉
        val listed = if (untested && models.isEmpty()) found.models ?: models else models
        val p = found.copy(
            models = listed, sessionHeader = sessionHeader,
            verifiedModels = found.verifiedModels + verified, typedModels = found.typedModels + typed,
            // 这次没测、这家也没有核实过的模型、也没列过列表,才是「没测过」;别的情况一律按测过
            savedUntested = untested && verified.isEmpty() && found.verifiedModels.isEmpty() && listed.isEmpty(),
        )
        _providers.value = _providers.value.map { if (it.id == p.id) p else it }
        repairUnavailableReasoningEfforts()
        if (apiKey != null) setKey(p.id, apiKey)
        writeConfig()
        return p
    }
}

/** 记下服务商最近一次列出的模型。列出来了,就说明「没测过」不再成立(跳过测试存下的那家在这里清掉标记)。 */
fun ProviderStore.rememberModels(providerId: String, models: List<ListedModel>) {
    synchronized(this) {
        if (provider(providerId) == null) return
        _providers.value = _providers.value.map {
            if (it.id == providerId) it.copy(models = models, savedUntested = false) else it
        }
        repairUnavailableReasoningEfforts()
        writeConfig()
    }
}

/**
 * 记下 [model] 在这家试调用得到了回复(见 [Provider.verifiedModels])。
 * 试通过了就说明「没测过」不再成立,跳过测试存下的那家在这里清掉标记——这个模型本来就在
 * [Provider.verifiedModels] 里时也要清,不能直接返回。
 */
fun ProviderStore.markVerified(providerId: String, model: String) {
    synchronized(this) {
        val p = provider(providerId) ?: return
        if (model in p.verifiedModels && !p.savedUntested) return
        _providers.value = _providers.value.map {
            if (it.id == providerId) it.copy(verifiedModels = it.verifiedModels + model, savedUntested = false) else it
        }
        writeConfig()
    }
}

/** 记下用户在这家手填的 [model](见 [Provider.typedModels])。 */
fun ProviderStore.markTyped(providerId: String, model: String) {
    synchronized(this) {
        val p = provider(providerId) ?: return
        if (model in p.typedModels) return
        _providers.value = _providers.value.map { if (it.id == providerId) it.copy(typedModels = it.typedModels + model) else it }
        writeConfig()
    }
}

/**
 * 删掉一个服务商:它下面的模型配置和 Key 一起删。用着它的用途退回默认(主模型变成没选,看图不用,轻量用主模型);
 * 界面上删除前已经说明哪些用途要重新选。删的是 ChatGPT 的,连注册到的 client_id 一起忘掉(本机标识留着):
 * 下次登录重新注册,可以换一个账号。
 */
fun ProviderStore.removeProvider(providerId: String) {
    synchronized(this) {
        if (provider(providerId)?.kind == ProviderConfig.Kind.ChatGpt) {
            writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", chatGptRegistration().first) }.toString())
        }
        _providers.value = _providers.value.filterNot { it.id == providerId }
        val gone = _configs.value.filter { it.providerId == providerId }.map { it.id }.toSet()
        _configs.value = _configs.value.filterNot { it.providerId == providerId }
        // 组失去这些成员;成员全没了的组删掉,用它的用途退回默认
        _groups.value = _groups.value
            .map { g -> g.copy(members = g.members.filterNot { it.configId in gone }) }
            .filter { it.members.isNotEmpty() }
        repairGroupRoles()
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet(), usableGroupIds())
        prune()
        if (providerId in keys) setKey(providerId, "")
        _providerKeyRevisions.value -= providerId
        writeConfig()
    }
}

internal fun ProviderStore.findOrAddProvider(name: String, kind: ProviderConfig.Kind, baseUrl: String, signIn: String? = null): Provider {
    val key = ProviderConnection.keyOf(kind, baseUrl, name)
    _providers.value.firstOrNull { it.key == key && it.signIn == signIn }?.let { return it }
    val p = Provider(newId(), name.trim(), kind, baseUrl.trim(), signIn = signIn)
    _providers.value = _providers.value + p
    return p
}
