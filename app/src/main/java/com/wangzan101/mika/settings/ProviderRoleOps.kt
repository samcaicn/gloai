package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.core.model.usage.TokenBudgetLimits
import kotlinx.serialization.json.add
import java.io.IOException

// 服务商存储:用途选择、备用链、模型组与各模型设置
/**
 * 定一个用途:用户选什么就是什么,不看能力标记。选具体模型当主模型时把工具调用标记记为 true(运行时据此发工具定义);
 * 看图标记不改存着的,在 [configFor] 里按当下的选择推出来,换走了就跟着回去。之后没人用的配置删掉。
 * 配置不存在、模型名为空或这个用途不接受这种选择(见 [RoleChoices.with])时返回 false。
 */
fun ProviderStore.choose(role: ModelRole, choice: RoleChoice): Boolean {
    return synchronized(this) {
        if (choice is RoleChoice.Model && configById(choice.configId)?.canUseForChat() != true) return false
        if (choice is RoleChoice.Group && !groupUsable(choice.groupId, role)) return false
        val next = _roles.value.with(role, choice) ?: return false
        if (choice is RoleChoice.Model) {
            _configs.value = _configs.value.map { c ->
                if (c.id != choice.configId) c
                else if (role == ModelRole.Main) c.copy(capabilities = c.capabilities.copy(toolCalls = true))
                else c
            }
        }
        _roles.value = next
        dropMainFromFallbacks()
        prune()
        writeConfig()
        return true
    }
}

/**
 * 把服务商 [providerId] 下的 [model] 备成一条配置(没有就按 [caps] 建;工具调用标记记为 true,它要能当主模型用),
 * 返回配置 id;不派给任何用途。服务商不存在返回 null。
 */
fun ProviderStore.ensureConfigId(providerId: String, model: String, caps: Capabilities): String? {
    return synchronized(this) {
        val p = provider(providerId) ?: return null
        val name = model.trim()
        if (name.isEmpty()) return null
        val existing = _configs.value.firstOrNull { it.providerId == p.id && it.model == name }
        if (existing != null) {
            if (!existing.capabilities.toolCalls) {
                _configs.value = _configs.value.map { c ->
                    if (c.id == existing.id) c.copy(capabilities = c.capabilities.copy(toolCalls = true)) else c
                }
                writeConfig()
            }
            return existing.id
        }
        val id = newId()
        save(ProviderConfig(id, p.kind, p.baseUrl, name, caps.copy(toolCalls = true), p.name, p.id), null)
        return id
    }
}

/** 备援链里混进的角色自身配置清掉。 */
internal fun ProviderStore.dropRoleFromFallbacks(role: ModelRole) {
    val roleId = _roles.value.resolve(role) ?: return
    val current = _roles.value.fallbacks(role)
    if (roleId !in current) return
    _roles.value = _roles.value.withFallbacks(role, current.filter { it != roleId })
}

internal fun ProviderStore.dropMainFromFallbacks() {
    dropRoleFromFallbacks(ModelRole.Main)
}

/** [providerId] 下的 [model] 是不是当前主模型(备援手填时挡下用,MK-1 D1)。 */
fun ProviderStore.isMainModel(providerId: String, model: String): Boolean =
    configFor(ModelRole.Main)?.let { it.providerId == providerId && it.model == model } == true

/**
 * 改指定角色备援链的第 [slot] 位: [configId] 为 null 是撤掉这一位(后面的往前补);
 * 已在链里的配置是移动到目标位(不重复占位);新配置在目标位有空位时替换、链未满时接在末尾
 * (中间有空槽也照接,不留空位)。链最长 [MAX_FALLBACKS]。
 */
fun ProviderStore.setFallback(role: ModelRole, slot: Int, configId: String?): Boolean {
    return synchronized(this) {
        if (slot < 0 || slot >= MAX_FALLBACKS) return false
        if (configId != null && configById(configId)?.canUseForChat() != true) return false
        val current = _roles.value.fallbacks(role)
        val next = when {
            configId == null -> if (slot in current.indices) current.toMutableList().apply { removeAt(slot) } else return true
            configId in current -> {
                val rest = current.filter { it != configId }
                rest.toMutableList().apply { add(minOf(slot, rest.size), configId) }
            }
            slot in current.indices -> current.toMutableList().apply { this[slot] = configId }
            current.size < MAX_FALLBACKS -> current + configId
            else -> return false
        }
        if (next == current) return true
        _roles.value = _roles.value.withFallbacks(role, next)
        dropRoleFromFallbacks(role)
        prune()
        writeConfig()
        return true
    }
}

/** 保持旧方法签名完全兼容 */
fun ProviderStore.setMainFallback(slot: Int, configId: String?): Boolean = synchronized(this) { setFallback(ModelRole.Main, slot, configId) }

/** 这个组;不存在为 null。 */
fun ProviderStore.group(id: String): ModelGroup? = _groups.value.firstOrNull { it.id == id }

/** 这个用途绑定的组;没绑组、或组已不存在时为 null。 */
fun ProviderStore.groupFor(role: ModelRole): ModelGroup? = _roles.value.resolveGroup(role)?.let(::group)

/**
 * 组能不能被 [role] 选中:主模型要求全员支持工具,看图要求至少一个成员能看图,轻量不强制。
 * 组为空、或成员指向的配置已经不在时按不可用算。
 */
fun ProviderStore.groupUsable(groupId: String, role: ModelRole): Boolean =
    group(groupId)?.usableFor(role) { id -> _configs.value.firstOrNull { it.id == id }?.capabilities } == true

/** 一个组能不能用于某个用途;UI 用它给组列表打标。 */
fun ProviderStore.groupUsable(group: ModelGroup, role: ModelRole): Boolean =
    group.usableFor(role) { id -> _configs.value.firstOrNull { it.id == id }?.capabilities }

/**
 * 新增或替换同 id 的组。成员指向的配置必须都在;主模型的组必须全员支持工具,看图的组至少要有一个能看图。
 * 不满足时返回 false,不改已有的组。
 */
fun ProviderStore.saveGroup(group: ModelGroup): Boolean {
    return synchronized(this) {
        if (group.members.any { m -> _configs.value.none { it.id == m.configId } }) return false
        // 新加进来的成员要有可用的模型配置;旧设置里已经在组里的留着,由用户在服务商详情里解决
        val before = group(group.id)?.members?.mapTo(hashSetOf()) { it.configId }.orEmpty()
        if (group.members.any { m -> m.configId !in before && configById(m.configId)?.canUseForChat() != true }) return false
        // 只看显式绑定的角色;Vision/Light 的「用主模型」不算自己在用这个组,不能拿它来拦保存
        val usedRoles = ModelRole.entries.filter { role -> (_roles.value[role] as? RoleChoice.Group)?.groupId == group.id }
        if (usedRoles.any { role -> !group.usableFor(role) { id -> _configs.value.firstOrNull { it.id == id }?.capabilities } }) return false
        val i = _groups.value.indexOfFirst { it.id == group.id }
        _groups.value = if (i >= 0) _groups.value.toMutableList().also { it[i] = group } else _groups.value + group
        repairGroupRoles()
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet(), usableGroupIds())
        prune()
        writeConfig()
        return true
    }
}

/** 删掉一个组;用它当主模型/看图/轻量的用途退回默认。成员配置没人再用时一起删。 */
fun ProviderStore.deleteGroup(groupId: String) {
    synchronized(this) {
        _groups.value = _groups.value.filterNot { it.id == groupId }
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet(), usableGroupIds())
        prune()
        writeConfig()
    }
}

/** 建组/加成员时用:有这个服务商的这个模型的配置就复用,没有就按 [caps] 建一条(不选用途)。 */
fun ProviderStore.ensureConfig(providerId: String, model: String, caps: Capabilities): String? {
    return synchronized(this) {
        val p = provider(providerId) ?: return null
        if (model.isBlank()) return null
        _configs.value.firstOrNull { it.providerId == p.id && it.model == model }?.let { return it.id }
        val id = newId()
        save(ProviderConfig(id, p.kind, p.baseUrl, model, caps, p.name, p.id), null)
        return id
    }
}

internal fun ProviderStore.usableGroupIds(): Set<String> = _groups.value.filter { g ->
    g.enabledMembers.any { m -> _configs.value.any { it.id == m.configId } }
}.map { it.id }.toSet()

/** 组失去成员或能力变化后,把显式绑在它上面、却已经不满足角色要求的用途退回默认。 */
internal fun ProviderStore.repairGroupRoles() {
    var roles = _roles.value
    for (role in ModelRole.entries) {
        val choice = roles[role]
        if (choice !is RoleChoice.Group || groupUsable(choice.groupId, role)) continue
        roles = when (role) {
            ModelRole.Main -> roles.copy(main = null, mainGroup = null)
            ModelRole.Vision -> roles.copy(vision = RoleChoice.Off)
            ModelRole.Light -> roles.copy(light = RoleChoice.SameAsMain)
        }
    }
    _roles.value = roles
}

/** 只改这个用途;不支持的协议、跟随主模型的用途或列表明确不支持的档位不接受。 */
fun ProviderStore.setReasoningEffort(role: ModelRole, effort: String?): Boolean {
    return synchronized(this) {
        if (_roles.value[role] !is RoleChoice.Model && _roles.value[role] !is RoleChoice.Group) return false
        val cfgs = groupFor(role)?.enabledMembers?.mapNotNull { m -> _configs.value.firstOrNull { it.id == m.configId } }
            ?: listOfNotNull(configFor(role))
        if (cfgs.isEmpty()) return false
        if (effort != null && cfgs.none { cfg ->
                val options = ReasoningOptions.of(cfg, listedOf(cfg))
                options != null && effort in options.levels
            }) return false
        _roles.value = _roles.value.copy(reasoningEfforts =
            if (effort == null) _roles.value.reasoningEfforts - role else _roles.value.reasoningEfforts + (role to effort))
        writeConfig()
        return true
    }
}

fun ProviderStore.setModelContext(configId: String, settings: ModelContextSettings): Boolean {
    return synchronized(this) {
        val cfg = _configs.value.firstOrNull { it.id == configId } ?: return false
        val p = provider(cfg.providerId) ?: return false
        if (!settings.validFor(cfg.capabilities.contextTokens)) return false
        val saved = if (settings == ModelContextSettings()) p.contextSettings - cfg.model
            else p.contextSettings + (cfg.model to settings)
        return saveProviderSettings(p.copy(contextSettings = saved))
    }
}

fun ProviderStore.setModelServiceTier(providerId: String, model: String, tier: OpenAiServiceTier?): Boolean {
    return synchronized(this) {
        val p = provider(providerId) ?: return false
        if (model.isBlank() || !p.supportsServiceTier(model)) return false
        val tiers = if (tier == null) p.serviceTiers - model else p.serviceTiers + (model to tier)
        return saveProviderSettings(p.copy(serviceTiers = tiers))
    }
}

/** 修改这家每日 Token 预算；写盘失败时保留内存和盘上原值。 */
fun ProviderStore.setDailyTokenBudget(providerId: String, limit: Long?): Boolean {
    return synchronized(this) {
        if (limit != null && !TokenBudgetLimits.valid(limit)) return false
        val p = provider(providerId) ?: return false
        return saveProviderSettings(p.copy(dailyTokenBudget = limit))
    }
}

internal fun ProviderStore.saveProviderSettings(p: Provider): Boolean {
    val updated = _providers.value.map { if (it.id == p.id) p else it }
    try {
        writeAtomically(configFile, configJson(updated))
    } catch (_: Exception) {
        return false
    }
    _providers.value = updated
    return true
}

/** 记下用户说的 [model] 能不能看图(见 [Provider.visionOverrides]);服务商不存在时返回 false。 */
fun ProviderStore.setModelVision(providerId: String, model: String, sees: Boolean): Boolean {
    return synchronized(this) {
        val p = provider(providerId) ?: return false
        _providers.value = _providers.value.map { if (it.id == p.id) p.copy(visionOverrides = p.visionOverrides + (model to sees)) else it }
        writeConfig()
        return true
    }
}

internal fun ProviderStore.saveRoleSelection(
    providers: List<Provider>, configs: List<ProviderConfig>, roles: RoleChoices, groups: List<ModelGroup> = _groups.value,
): Boolean {
    try {
        writeAtomically(configFile, configJson(providers, configs, roles, groups))
    } catch (_: IOException) {
        return false
    }
    _providers.value = providers
    _configs.value = configs
    _groups.value = groups
    _roles.value = roles
    return true
}

/** 看图模型的「看图定位已验证」(开发者选项里改);看图用主模型时改的是主模型那条配置。没有看图模型时不动。 */
fun ProviderStore.setVisionGroundingVerified(verified: Boolean) {
    synchronized(this) {
        // 看图是具体模型时改那一条;是组(含「用主模型」跟到主组)时改组里所有启用成员
        val ids = _roles.value.resolveConfig(ModelRole.Vision)?.let { setOf(it) }
            ?: groupFor(ModelRole.Vision)?.enabledMembers?.map { it.configId }?.toSet()
            ?: return
        _configs.value = _configs.value.map { c ->
            if (c.id in ids) c.copy(capabilities = c.capabilities.copy(groundingVerified = verified)) else c
        }
        writeConfig()
    }
}
