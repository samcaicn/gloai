package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 一格的归属:哪个服务商、哪个模型、哪个用途。 */
data class UsageKey(val providerId: String, val model: String, val purpose: UsagePurpose)

/** 一格里累计的数:输入(含缓存命中)、输出、缓存命中的 token,实际 HTTP 请求次数,其中没有对应最终用量的次数。 */
data class UsageCounts(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val calls: Long = 0,
    val callsWithoutUsage: Long = 0,
) {
    operator fun plus(o: UsageCounts) =
        UsageCounts(input + o.input, output + o.output, cached + o.cached, calls + o.calls, callsWithoutUsage + o.callsWithoutUsage)

    companion object {
        fun of(usage: Usage?, httpAttempts: Int = 1): UsageCounts {
            val calls = httpAttempts.coerceAtLeast(0).toLong()
            val withoutUsage = (calls - if (usage == null) 0 else 1).coerceAtLeast(0)
            return UsageCounts(
                usage?.inputTokens?.toLong() ?: 0,
                usage?.outputTokens?.toLong() ?: 0,
                usage?.cachedInputTokens?.toLong() ?: 0,
                calls = calls,
                callsWithoutUsage = withoutUsage,
            )
        }
    }
}

/** [hour] 是 UTC 整点桶:从 1970-01-01T00:00Z 起的第几个小时。 */
data class UsageRow(val hour: Long, val key: UsageKey, val counts: UsageCounts)

/** 账本此刻的全部内容;[providerNames] 是每个服务商最近一次记账时的名字(服务商删了也留着)。 */
data class UsageSnapshot(val rows: List<UsageRow>, val providerNames: Map<String, String>) {
    companion object {
        val EMPTY = UsageSnapshot(emptyList(), emptyMap())
    }
}

/**
 * 本机的 token 用量账本按 UTC 小时累计。若本地午夜落在小时内，午夜后的用量另存一格供每日预算使用。
 * 本地时区每次用时现取([zone]):进程没退出时手机改了时区,之后的拆格和「今天」就按新时区算。
 *
 * 记账([record])只在内存里加一笔,多个线程同时记也安全;改动后等 [flushDelayMs] 由一个后台线程整份写出
 * (先写临时文件再改名,写到一半断电不会坏掉原文件),进程被杀时最多丢掉最后这几秒。
 * 只留最近 [keepDays] 天,更早的在读入和写出时丢掉。文件读不出(损坏)时当作空账本重新开始。
 */
class UsageLedger(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val flushDelayMs: Long = 2_000,
    private val keepDays: Int = KEEP_DAYS,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : UsageSink {
    private data class Cell(val hour: Long, val key: UsageKey, val offsetMs: Long = 0) {
        val startMs: Long get() = hour * HOUR_MS + offsetMs
    }

    private val lock = Any()
    private val writeLock = Any()
    private val cells = HashMap<Cell, UsageCounts>()
    private val names = HashMap<String, String>()
    private var dirty = false
    private var flushQueued = false

    private val writer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "usage-ledger").apply { isDaemon = true } }
    }

    private val _version = MutableStateFlow(0L)
    /** 每记一笔加一;界面跟着它重新统计。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    init {
        load()
    }

    override fun record(source: UsageSource, usage: Usage?) = record(source, usage, httpAttempts = 1)

    override fun record(source: UsageSource, usage: Usage?, httpAttempts: Int) {
        // 本地组包就失败、从未发请求时,运行记录仍算逻辑调用,但用量账本没有可计的次数或 token。
        if (httpAttempts <= 0 && usage == null) return
        val at = now()
        val hour = Math.floorDiv(at, HOUR_MS)
        val z = zone()
        val midnight = Instant.ofEpochMilli(at).atZone(z).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()
        val offset = (midnight - hour * HOUR_MS).coerceAtLeast(0)
        val cell = Cell(hour, UsageKey(source.providerId, source.model, source.purpose), offset)
        val counts = UsageCounts.of(usage, httpAttempts)
        val queue = synchronized(lock) {
            cells[cell] = (cells[cell] ?: UsageCounts()) + counts
            if (source.providerName.isNotEmpty()) names[source.providerId] = source.providerName
            dirty = true
            !flushQueued.also { flushQueued = true }
        }
        _version.update { it + 1 }
        if (queue) writer.schedule({ runCatching { flush() } }, flushDelayMs, TimeUnit.MILLISECONDS)
    }

    fun snapshot(): UsageSnapshot = synchronized(lock) {
        val hours = cells.entries.groupingBy { it.key.copy(offsetMs = 0) }
            .fold(UsageCounts()) { total, entry -> total + entry.value }
        UsageSnapshot(hours.map { (c, n) -> UsageRow(c.hour, c.key, n) }, HashMap(names))
    }

    /** 这家服务商在当前本地日历日的已报告输入加输出;主动能力的用量有自己的预算,不算在内。 */
    fun providerTokensToday(providerId: String): Long =
        tokensToday { it.providerId == providerId && it.purpose != UsagePurpose.Proactive }

    /** 主动能力在当前本地日历日的已报告输入加输出,各服务商合计。 */
    fun proactiveTokensToday(): Long = tokensToday { it.purpose == UsagePurpose.Proactive }

    /** 主动能力在当前本地日历日实际发出的请求数,各服务商合计;服务商不报用量时 Token 数是 0,靠它兜底。 */
    fun proactiveCallsToday(): Long = today({ it.purpose == UsagePurpose.Proactive }) { it.calls }

    /** 当前本地日历日里、[key] 选中的格子的输入加输出。 */
    private fun tokensToday(key: (UsageKey) -> Boolean): Long = today(key) { it.input + it.output }

    /** 当前本地日历日里、[key] 选中的格子各取 [amount] 再合计。 */
    private fun today(key: (UsageKey) -> Boolean, amount: (UsageCounts) -> Long): Long {
        val z = zone()
        val today = Instant.ofEpochMilli(now()).atZone(z).toLocalDate()
        val start = today.atStartOfDay(z).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        return synchronized(lock) {
            cells.entries.sumOf { (cell, counts) ->
                if (key(cell.key) && cell.startMs in start until end) amount(counts) else 0L
            }
        }
    }

    /** 有没写出的改动就当场写出(后台线程定时调;测试和退出前也可直接调)。写失败时留着改动,下次再写。 */
    fun flush() {
        synchronized(writeLock) {
            val text = synchronized(lock) {
                flushQueued = false
                if (!dirty) return
                dirty = false
                prune()
                serialize()
            }
            try {
                writeAtomically(text)
            } catch (e: Exception) {
                synchronized(lock) { dirty = true }
                throw e
            }
        }
    }

    private fun cutoffHour(): Long = Math.floorDiv(now(), HOUR_MS) - keepDays * 24L

    private fun prune() {
        val cutoff = cutoffHour()
        cells.keys.removeAll { it.hour < cutoff }
    }

    private fun serialize(): String {
        val keys = cells.keys.map { it.key }.distinct()
        val index = keys.withIndex().associate { (i, k) -> k to i }
        return buildJsonObject {
            put("version", FORMAT)
            putJsonObject("names") { names.forEach { (id, n) -> put(id, n) } }
            putJsonArray("keys") {
                keys.forEach { k -> addJsonObject { put("provider", k.providerId); put("model", k.model); put("purpose", k.purpose.key) } }
            }
            putJsonArray("rows") {
                cells.entries.sortedBy { it.key.hour }.forEach { (c, n) ->
                    addJsonArray {
                        add(JsonPrimitive(c.hour)); add(JsonPrimitive(index.getValue(c.key)))
                        add(JsonPrimitive(n.input)); add(JsonPrimitive(n.output)); add(JsonPrimitive(n.cached))
                        add(JsonPrimitive(n.calls)); add(JsonPrimitive(n.callsWithoutUsage))
                        if (c.offsetMs != 0L) add(JsonPrimitive(c.offsetMs))
                    }
                }
            }
        }.toString()
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["names"]?.jsonObject?.forEach { (id, n) -> names[id] = n.jsonPrimitive.content }
            val keys = root["keys"]?.jsonArray.orEmpty().map { e ->
                val o = e.jsonObject
                UsageKey(
                    o.getValue("provider").jsonPrimitive.content, o.getValue("model").jsonPrimitive.content,
                    UsagePurpose.of(o.getValue("purpose").jsonPrimitive.content),
                )
            }
            val cutoff = cutoffHour()
            root["rows"]?.jsonArray.orEmpty().forEach { e ->
                val r = (e as JsonArray).map { it.jsonPrimitive }
                val hour = r[0].long
                if (hour < cutoff) return@forEach
                // Older rows have no midnight split; keep their original hour-start timestamp.
                val offset = r.getOrNull(7)?.longOrNull?.takeIf { it in 0 until HOUR_MS } ?: 0L
                val cell = Cell(hour, keys[r[1].int], offset)
                val n = UsageCounts(r[2].long, r[3].long, r[4].long, r[5].long, r[6].long)
                cells[cell] = (cells[cell] ?: UsageCounts()) + n
            }
        }.onFailure {
            cells.clear()
            names.clear()
        }
    }

    private fun writeAtomically(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write ${file.name}" }
        }
    }

    companion object {
        const val KEEP_DAYS = 400
        const val HOUR_MS = 3_600_000L
        private const val FORMAT = 1
    }
}
