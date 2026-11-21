package com.wangzan101.mika.core.model

/**
 * 一个组的策略。v1 实现 [Failover]；[Weighted]、[LeastLatency]、[CostFirst] 先留接口,
 * 选择和记录已经能跑,界面上逐步放开。
 */
enum class GroupStrategy(val wire: String) {
    /** 按 priority 顺序,前一个失败才用下一个。 */
    Failover("failover"),

    /** 新对话按 weight 分配一个成员,之后粘住;不是按每个请求轮询。 */
    Weighted("weighted"),

    /** 按近期成功率和延迟选;样本少的成员保留一点探索。 */
    LeastLatency("least-latency"),

    /** 按用户填的费用档位选;没有费用数据的成员排在后面。 */
    CostFirst("cost-first");

    companion object {
        fun of(wire: String?): GroupStrategy = entries.firstOrNull { it.wire == wire } ?: Failover
    }
}

/**
 * 组里的一个成员。[configId] 指向 [com.wangzan101.mika.settings.ProviderConfig] 的 id;
 * 组成员会让这条配置不被 `ProviderStore.prune` 删掉。
 */
data class GroupMember(
    val configId: String,
    /** 越小越先;同优先级按成员列表顺序。 */
    val priority: Int = 0,
    /** 1..100,只有 [GroupStrategy.Weighted] 用。 */
    val weight: Int = 1,
    val enabled: Boolean = true,
    /** 覆盖角色选的思考档位;null 跟随角色。成员不支持时忽略。 */
    val reasoningEffort: String? = null,
    /** 只有 [GroupStrategy.CostFirst] 用:越小越便宜;null 表示用户没填。 */
    val costRank: Int? = null,
)

/** 限额;v1 不读,v2 接。 */
data class GroupLimits(
    val requestsPerMinute: Int? = null,
    val maxConcurrent: Int? = null,
    val dailyTokens: Long? = null,
    val dailyCostMicros: Long? = null,
)

/**
 * 模型组:一组具体模型,按 [strategy] 选一个来回答。[members] 的顺序是界面上的顺序;
 * 实际先后看 [GroupMember.priority]。组不能套组。
 */
data class ModelGroup(
    val id: String,
    val name: String,
    val strategy: GroupStrategy = GroupStrategy.Failover,
    val members: List<GroupMember> = emptyList(),
    /** 同一对话尽量固定在一个成员上,保护前缀缓存;默认开。 */
    val sticky: Boolean = true,
    /** v3:允许无工具、只读的并行 racing;默认关。 */
    val raceReadOnly: Boolean = false,
    val limits: GroupLimits = GroupLimits(),
) {
    val enabledMembers: List<GroupMember> get() = members.filter { it.enabled }

    /**
     * 这个组能不能被某个用途选中。主模型要求全员支持工具调用;看图要求至少一个成员能看图;
     * 轻量不强制(不支持工具时 lightSteps 自己不会启用)。[capsOf] 取不到配置时按不满足算。
     */
    fun usableFor(role: ModelRole, capsOf: (String) -> Capabilities?): Boolean {
        val caps = enabledMembers.mapNotNull { capsOf(it.configId) }
        if (caps.isEmpty()) return false
        return when (role) {
            ModelRole.Main -> caps.all { it.toolCalls }
            ModelRole.Vision -> caps.any { it.vision }
            ModelRole.Light -> true
        }
    }
}

/** 成员的稳定身份:按服务商 + 模型名,不按配置 id(配置删了重建会变)。 */
data class MemberKey(val providerId: String, val model: String) {
    override fun toString(): String = if (providerId.isEmpty()) model else "$providerId/$model"
}

/** 这一次请求对成员的要求;由 ModelCaller 按实际请求算。 */
data class ModelNeed(
    val toolCalls: Boolean = false,
    val vision: Boolean = false,
    val images: Int = 0,
    /** 估算的整份请求 token(消息 + 固定开销),用来筛掉窗口太小的成员。 */
    val estimatedTokens: Int = 0,
)
