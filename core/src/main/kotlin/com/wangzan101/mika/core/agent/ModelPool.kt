package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GroupStrategy
import com.wangzan101.mika.core.model.MemberKey
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelNeed
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.net.ProxyFailure
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/** 一次调用的失败该不该换成员、这个成员该冷却多久。 */
object FailoverPolicy {
    /**
     * 换成员能不能解决问题。限流、5xx、网络、鉴权、额度、模型不存在/不可用、不支持工具、登录失效都换;
     * 内容拒绝、上下文超长、请求本身不对、解析失败不换(见 [ModelErrors.transient] 的重试分类)。
     */
    fun canFailover(e: Throwable): Boolean = when (e) {
        is ModelError.RateLimited, is ModelError.Network, is ModelError.StreamCut -> true
        is ModelError.Proxy -> e.failure == ProxyFailure.Unreachable
        is ModelError.Server -> e.status >= 500 || e.status == 429
        is ModelError.Auth, is ModelError.QuotaExceeded, is ModelError.ModelNotFound, is ModelError.ModelNotAvailable,
        is ModelError.ToolsUnsupported, is ModelError.ChatGptLimitReached, is ModelError.ChatGptNotEligible,
        is ModelError.SignInExpired, is ModelError.AccountPlanNotAllowed -> true
        else -> false
    }

    /** 这个成员出 [e] 后冷却多久。连续失败翻倍;限流按 Retry-After;鉴权/额度类久一点。 */
    fun cooldownMs(e: Throwable, failures: Int): Long = when (e) {
        is ModelError.RateLimited -> (e.retryAfterMs ?: 30_000L).coerceIn(5_000L, 30 * 60_000L)
        is ModelError.Auth, is ModelError.QuotaExceeded, is ModelError.SignInExpired,
        is ModelError.ChatGptLimitReached, is ModelError.ChatGptNotEligible, is ModelError.AccountPlanNotAllowed ->
            5 * 60_000L
        else -> (30_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(10 * 60_000L)
    }
}

/** 一个成员最近一段时间的健康情况;只在内存里,进程重启清空。 */
data class MemberHealth(
    val consecutiveFailures: Int = 0,
    val calls: Long = 0,
    val failures: Long = 0,
    val cooldownUntilMs: Long = 0,
    val ewmaLatencyMs: Double = 0.0,
    val latencySamples: Int = 0,
    val lastError: String? = null,
) {
    /** 越小越优先;没有样本的成员排在后面,由策略的探索概率决定要不要试。 */
    fun score(): Double {
        if (latencySamples == 0) return Double.MAX_VALUE
        // 只用连续失败:成功一次就清零,一次早期的失败不会永久拉低分数
        return ewmaLatencyMs * (1 + 2.0 * consecutiveFailures)
    }
}

/** 每个成员的健康状态,按 [MemberKey] 共享:同一服务商的同一模型不管被几条配置、几个组引用,冷却一次就够。 */
class ModelHealthRegistry(private val now: () -> Long = System::currentTimeMillis) {
    private val states = HashMap<MemberKey, MemberHealth>()

    @Synchronized
    fun snapshot(key: MemberKey): MemberHealth = states[key] ?: MemberHealth()

    /** 冷却结束了(或从没失败过)就可用。冷却结束后先放一个请求进去,成功了自然恢复。 */
    @Synchronized
    fun available(key: MemberKey): Boolean = now() >= (states[key]?.cooldownUntilMs ?: 0)

    @Synchronized
    fun noteSuccess(key: MemberKey, latencyMs: Long) {
        val old = states[key] ?: MemberHealth()
        val samples = old.latencySamples + 1
        val ewma = if (old.latencySamples == 0) latencyMs.toDouble() else old.ewmaLatencyMs * 0.7 + latencyMs * 0.3
        states[key] = old.copy(
            consecutiveFailures = 0, calls = old.calls + 1, cooldownUntilMs = 0,
            ewmaLatencyMs = ewma, latencySamples = samples,
        )
    }

    @Synchronized
    fun noteFailure(key: MemberKey, error: Throwable) {
        val old = states[key] ?: MemberHealth()
        val unhealthy = FailoverPolicy.canFailover(error)
        // 内容拒绝、上下文超长、请求本身不对不代表这个成员坏了:只记次数,不计入连续失败/冷却/延迟评分
        val failures = if (unhealthy) old.consecutiveFailures + 1 else old.consecutiveFailures
        val cooldown = if (unhealthy) now() + FailoverPolicy.cooldownMs(error, failures) else old.cooldownUntilMs
        states[key] = old.copy(
            consecutiveFailures = failures, calls = old.calls + 1, failures = old.failures + 1,
            cooldownUntilMs = cooldown, lastError = error::class.simpleName,
        )
    }

    @Synchronized
    fun reset() {
        states.clear()
    }
}

/** 同一对话固定在一个成员上;LRU 上限 [limit],进程重启清空。 */
class StickyTable(private val limit: Int = 512) {
    private val map = object : LinkedHashMap<String, MemberKey>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MemberKey>): Boolean = size > limit
    }

    @Synchronized
    fun get(key: String): MemberKey? = map[key]

    @Synchronized
    fun put(key: String, value: MemberKey) {
        map[key] = value
    }

    @Synchronized
    fun remove(key: String) {
        map.remove(key)
    }
}

/** 池里的一个成员:真实模型 + 选择用的元数据。 */
data class PoolMember(
    val key: MemberKey,
    val model: ChatModel,
    val priority: Int = 0,
    val weight: Int = 1,
    val costRank: Int? = null,
    /** 服务商在界面上的名字;只用于运行记录,不参与选择。 */
    val providerName: String = "",
) {
    /** 运行记录里的称呼:服务商 · 模型。 */
    val label: String get() = if (providerName.isEmpty()) key.model else "$providerName · ${key.model}"
}

/**
 * 一次调用可以选的一组模型。单模型也包成池(只有一个成员),这样 ModelCaller 只有一条路径;
 * [GroupPool] 是多个成员、带健康和粘性的实现。
 */
interface ModelPool {
    val members: List<PoolMember>
    val groupName: String?

    /** 池作为整体对调用方承诺的能力;不经过 ModelCaller 的调用方用它。 */
    val capabilities: Capabilities

    /** 按这次请求的要求和策略排出尝试顺序;空列表表示没有可用成员。 */
    fun ordered(need: ModelNeed): List<PoolMember>

    /** 一次尝试的结果:成功 [error] 为 null;用来更新健康和粘性。 */
    fun noteResult(member: PoolMember, latencyMs: Long?, error: Throwable?)

    /** [model] 是不是这个池里的成员(用来判断「轻量模型给的回复」)。 */
    fun owns(model: ChatModel): Boolean
}

/** 单成员池:行为和加组之前完全一样。 */
class SingleMemberPool(override val members: List<PoolMember>) : ModelPool {
    constructor(model: ChatModel) : this(listOf(PoolMember(MemberKey("", model.name), model)))

    override val groupName: String? = null
    override val capabilities: Capabilities get() = members.first().model.capabilities

    override fun ordered(need: ModelNeed): List<PoolMember> = members

    override fun noteResult(member: PoolMember, latencyMs: Long?, error: Throwable?) = Unit

    override fun owns(model: ChatModel): Boolean = members.any { it.model === model }
}

/** 任何 ChatModel 都能当池用;GroupPool 自己就是池。 */
fun ChatModel.asPool(): ModelPool = if (this is ModelPool) this else SingleMemberPool(this)

/** 加权轮询的当前累计值,按组共享:每个任务新建一个池,累计值不能跟着池一起丢。进程重启清空。 */
class GroupWeights {
    private val perGroup = HashMap<String, HashMap<MemberKey, Int>>()

    @Synchronized
    fun forGroup(groupId: String): MutableMap<MemberKey, Int> = perGroup.getOrPut(groupId) { HashMap() }
}

/**
 * 一个模型组建出来的池。除了 [ModelPool],还实现 [ChatModel]/[StreamingChatModel],给不经过 [ModelCaller]
 * 的旁路调用(起标题、分类、核查、看图)用;那条路径自己做一次轻量转移,不参与上下文压缩。
 *
 * [stickyKey] 是「组 + 对话」的键;为 null 时不做粘性(一次性调用)。[weightedWeights] 由 [GroupWeights] 按组给出。
 */
class GroupPool(
    private val group: ModelGroup,
    override val members: List<PoolMember>,
    private val health: ModelHealthRegistry,
    private val stickyTable: StickyTable,
    private val stickyKey: String?,
    private val weightedWeights: MutableMap<MemberKey, Int> = HashMap(),
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
) : ModelPool, ChatModel, StreamingChatModel {

    override val groupName: String get() = group.name.ifBlank { group.id }

    override val name: String get() = groupName

    override val capabilities: Capabilities = Capabilities(
        toolCalls = members.isNotEmpty() && members.all { it.model.capabilities.toolCalls },
        // 提示词的形状由排在最前的成员决定:主成员看不了图就不开截图,避免第一张图之后永久换到备用成员
        vision = (members.minByOrNull { it.priority })?.model?.capabilities?.vision == true,
        contextTokens = members.minOfOrNull { it.model.capabilities.contextTokens } ?: 0,
        reasoning = members.any { it.model.capabilities.reasoning },
        groundingVerified = members.isNotEmpty() && members.all { it.model.capabilities.groundingVerified },
        promptCaching = members.firstOrNull()?.model?.capabilities?.promptCaching ?: com.wangzan101.mika.core.model.PromptCaching.Implicit,
        maxImages = members.maxOfOrNull { it.model.capabilities.maxImages } ?: 0,
        compactionTriggerTokens = null,
    )

    private val orderIndex: Map<MemberKey, Int> = members.withIndex().associate { it.value.key to it.index }
    private val lock = Any()

    override fun ordered(need: ModelNeed): List<PoolMember> = synchronized(lock) {
        // 窗口不够的成员不直接排除:ModelCaller 会先压缩再发。粘性成员尤其要保留,
        // 否则长对话会静默从主成员切到一个窗口更大的备用成员。
        val relaxed = eligible(need.copy(estimatedTokens = 0))
        if (relaxed.isEmpty()) return emptyList()
        val strict = eligible(need)
        val stickyMember = if (group.sticky && stickyKey != null) {
            relaxed.firstOrNull { it.key == stickyTable.get(stickyKey) }
        } else null
        val pool = if (stickyMember != null) relaxed else if (strict.isNotEmpty()) strict else relaxed
        val first = stickyMember ?: when (group.strategy) {
            GroupStrategy.Failover -> pool.minWith(compareBy({ it.priority }, { orderIndex[it.key] ?: Int.MAX_VALUE }))
            GroupStrategy.Weighted -> weightedFirst(pool)
            GroupStrategy.LeastLatency -> leastLatencyFirst(pool)
            GroupStrategy.CostFirst -> pool.minWith(compareBy({ it.costRank ?: Int.MAX_VALUE }, { it.priority }))
        }
        val firstIndex = pool.indexOfFirst { it === first }
        val rest = pool.filterIndexed { i, _ -> i != firstIndex }
            .sortedWith(compareBy({ it.priority }, { orderIndex[it.key] ?: Int.MAX_VALUE }))
        listOf(first) + rest
    }

    private fun eligible(need: ModelNeed): List<PoolMember> = members.filter { m ->
        val caps = m.model.capabilities
        (!need.toolCalls || caps.toolCalls) &&
            (!need.vision || caps.vision) &&
            (need.images == 0 || need.images <= caps.maxImages) &&
            (need.estimatedTokens == 0 || need.estimatedTokens <= caps.contextTokens) &&
            health.available(m.key)
    }

    /** 样本少于 3 的成员保留 10% 探索,其余按近期速度和连续失败排序。 */
    private fun leastLatencyFirst(pool: List<PoolMember>): PoolMember {
        val unsampled = pool.filter { health.snapshot(it.key).latencySamples < 3 }
        if (unsampled.isNotEmpty() && random.nextDouble() < 0.1) return unsampled.random(random)
        return pool.minByOrNull { health.snapshot(it.key).score() } ?: pool.first()
    }

    /** 平滑加权轮询:只在「新对话」选一次,选完由粘性固定。累计值按组共享,不同任务同时选也安全。 */
    private fun weightedFirst(pool: List<PoolMember>): PoolMember = synchronized(weightedWeights) {
        var best: PoolMember? = null
        for (m in pool) {
            val w = (weightedWeights[m.key] ?: 0) + m.weight.coerceAtLeast(1)
            weightedWeights[m.key] = w
            if (best == null || w > (weightedWeights[best.key] ?: 0)) best = m
        }
        val total = pool.sumOf { it.weight.coerceAtLeast(1) }
        val chosen = best ?: pool.first()
        weightedWeights[chosen.key] = (weightedWeights[chosen.key] ?: 0) - total
        chosen
    }

    override fun noteResult(member: PoolMember, latencyMs: Long?, error: Throwable?) = synchronized(lock) {
        if (error == null) {
            health.noteSuccess(member.key, latencyMs ?: 0)
            if (group.sticky && stickyKey != null) stickyTable.put(stickyKey, member.key)
        } else {
            health.noteFailure(member.key, error)
            if (group.sticky && stickyKey != null && FailoverPolicy.canFailover(error)) stickyTable.remove(stickyKey)
        }
    }

    override fun owns(model: ChatModel): Boolean = members.any { it.model === model }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        attempt(system, messages, tools, onText = null)

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply = attempt(system, messages, tools, onText)

    /**
     * 旁路调用的一次转移。这些调用不经过 [ModelCaller],不知道每条消息是谁给的,所以一律去掉别家模型的
     * 思考内容和工具调用私有字段;一次性调用的消息本来就是新拼的,不会失去有用的上文。
     * 已经在流式里吐过字就先清空再看下一个。不做同成员重试(那是 [ModelCaller] 的事)。
     */
    private suspend fun attempt(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: ((String) -> Unit)?): ModelReply {
        val need = needOf(system, messages, tools)
        val candidates = ordered(need)
        if (candidates.isEmpty()) throw ModelError.GroupUnavailable(groupName, unavailableReason(need))
        val view = stripForeignPrivate(messages)
        var last: Throwable? = null
        for ((index, member) in candidates.withIndex()) {
            val started = now()
            try {
                val reply = if (onText != null && member.model is StreamingChatModel) {
                    (member.model as StreamingChatModel).completeStreaming(system, view, tools, onText)
                } else {
                    member.model.complete(system, view, tools).also { r ->
                        if (onText != null) {
                            r.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                                .takeIf { it.isNotEmpty() }?.let(onText)
                        }
                    }
                }
                noteResult(member, now() - started, null)
                return reply
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                noteResult(member, now() - started, e)
                last = e
                onText?.invoke("")
                if (!FailoverPolicy.canFailover(e) || index == candidates.lastIndex) throw e
            }
        }
        throw last ?: ModelError.GroupUnavailable(groupName)
    }

    private fun needOf(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelNeed {
        val images = messages.sumOf { m -> m.parts.count { it is Part.Image } }
        val overhead = TokenEstimate.of(system) + tools.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
        return ModelNeed(
            toolCalls = tools.isNotEmpty(), vision = images > 0, images = images,
            estimatedTokens = TokenEstimate.of(messages) + overhead,
        )
    }

    private fun unavailableReason(need: ModelNeed): String = when {
        members.isEmpty() -> "组里没有成员"
        need.toolCalls && members.none { it.model.capabilities.toolCalls } -> "没有支持工具调用的成员"
        need.vision && members.none { it.model.capabilities.vision } -> "没有能看图的成员"
        members.none { health.available(it.key) } -> "成员都在冷却"
        else -> "成员上下文窗口不够"
    }
}

/** 换成员时去掉别的模型给的思考内容和工具调用私有字段;它们只能原样回给产生它的模型。 */
internal fun stripForeignPrivate(messages: List<Message>): List<Message> = messages.map { m ->
    if (m.role != Role.Assistant || m.parts.none { it is Part.Thinking || (it is Part.ToolCall && it.opaque != null) }) m
    else m.copy(parts = m.parts.mapNotNull { p ->
        when {
            p is Part.Thinking -> null
            p is Part.ToolCall && p.opaque != null -> p.copy(opaque = null)
            else -> p
        }
    })
}
