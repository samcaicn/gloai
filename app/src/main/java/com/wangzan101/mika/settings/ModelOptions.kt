package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 列表里的一个可选模型。[capabilities] 是合并后的能力:接口给了的照用,没给的沿用预设的初值;
 * [toolCallsKnown]、[visionKnown] 记下哪些是接口明确说的(界面上据此标「未确认」)。
 * [chat] 是接口说没说它是对话模型(见 [ListedModel.chat]),没说为 null。
 * [typed] 为真是不在列出的模型里、用户手填的(界面上标「手填」)。
 * [visionByUser] 是用户说的能不能看图(见 [Provider.visionOverrides]),没说为 null;说了的已算进 [capabilities]。
 */
data class ModelOption(
    val id: String,
    val capabilities: Capabilities,
    val toolCallsKnown: Boolean,
    val visionKnown: Boolean,
    val contextKnown: Boolean,
    val displayName: String = "",
    val chat: Boolean? = null,
    val typed: Boolean = false,
    val visionByUser: Boolean? = null,
) {
    /** 供参考的能力标记;用户选模型时不按它们过滤。 */
    val canBeMain: Boolean get() = capabilities.toolCalls
    val canSee: Boolean get() = capabilities.vision
}

object ModelOptions {
    /**
     * 把列出的模型和预设能力合并。可当主模型的排前面,其余保持服务商给的顺序。
     * 只写了 Mika 不会说的接口协议的模型(见 [ListedModel.usable])对话调用必然失败,默认不列;
     * [includeUnsupported] 为真时也列(服务商详情、声音模型要看完整目录)。
     */
    fun merge(listed: List<ListedModel>, base: Capabilities, includeUnsupported: Boolean = false): List<ModelOption> =
        listed.filter { includeUnsupported || it.usable }.map { m ->
            ModelOption(
                id = m.id,
                capabilities = base.copy(
                    toolCalls = m.toolCalls ?: base.toolCalls,
                    vision = m.vision ?: base.vision,
                    contextTokens = m.contextTokens ?: base.contextTokens,
                    reasoning = m.reasoning ?: base.reasoning,
                    // 看图定位准不准要实测过才算,新连的一律未验证
                    groundingVerified = false,
                ),
                toolCallsKnown = m.toolCalls != null,
                visionKnown = m.vision != null,
                contextKnown = m.contextTokens != null,
                displayName = m.displayName,
                chat = m.chat,
            )
        }.sortedBy { if (it.canBeMain) 0 else 1 }

    /**
     * 拿来当主模型、试调用的先后:只要可当主模型、又不像别的用途的(见 [looksNonChat]);
     * 先自家的(模型名或显示名里有 [familyTokens] 里的词,见 [ProviderPreset.familyTokens]),
     * 再接口明确说能调用工具的;自家的同一档里按模型名里的版本新的在前(见 [ModelVersion]:
     * 同版本正式版在前,读不出版本的排在读得出的后面),其余照列表的顺序。
     * 服务商列表的顺序常常是老模型在前,老模型可能已下线;别家的模型各有各的版本号,不放在一起比。
     */
    fun candidates(options: List<ModelOption>, familyTokens: List<String> = emptyList()): List<String> =
        options.filter { it.canBeMain && !looksNonChat(it) }
            .map { Triple(it, inFamily(it, familyTokens), ModelVersion.of(it.id)) }
            .sortedWith(
                compareBy<Triple<ModelOption, Boolean, ModelVersion>>({ if (it.second) 0 else 1 }, { if (it.first.toolCallsKnown) 0 else 1 })
                    .thenComparator { a, b -> if (a.second) ModelVersion.NEWEST_FIRST.compare(a.third, b.third) else 0 },
            )
            .map { it.first.id }

    /** 默认选的主模型:[candidates] 的第一个;一个都没有就挑第一个可当主模型的。 */
    fun defaultMain(options: List<ModelOption>, familyTokens: List<String> = emptyList()): String? =
        candidates(options, familyTokens).firstOrNull() ?: options.firstOrNull { it.canBeMain }?.id

    /** 地址对得上的预设的自家模型词;认不出地址为空。 */
    fun familyOf(baseUrl: String): List<String> = ProviderPresets.forBaseUrl(baseUrl.trim())?.familyTokens.orEmpty()

    private fun inFamily(o: ModelOption, tokens: List<String>): Boolean =
        tokens.any { t -> o.id.contains(t, ignoreCase = true) || o.displayName.contains(t, ignoreCase = true) }

    /**
     * 看着不是对话模型(向量、重排、语音、画图、审核…):接口说了的照接口,没说看模型名按标点、斜杠切开的各段,
     * 有一段以 [NON_CHAT_WORDS] 里的词开头就算。
     */
    fun looksNonChat(o: ModelOption): Boolean =
        o.chat?.not() ?: o.id.lowercase().split(Regex("[^a-z0-9]+")).any { part -> NON_CHAT_WORDS.any { part.startsWith(it) } }

    private val NON_CHAT_WORDS = listOf(
        "embed", "rerank", "tts", "whisper", "transcribe", "speech", "audio", "realtime", "image", "dall", "moderation", "ocr", "asr",
    )

    /** 上下文长度的短写:64K、1M。 */
    fun contextLabel(tokens: Int): String = when {
        tokens >= 1_000_000 && tokens % 1_000_000 < 50_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1000 -> if (tokens % 1024 == 0) "${tokens / 1024}K" else "${(tokens + 500) / 1000}K"
        else -> "$tokens"
    }

    /**
     * 一个模型办事时算不算能看图:用户在这个模型上说过的([userSaid])为准;没说过的,看图也用它([usedForVision]:
     * 选它当看图模型,或看图「用主模型」)就是用户说了它能看;都没有才按 [stored](列表写明的,没写的是预设初值)。
     */
    fun seesImages(stored: Boolean, userSaid: Boolean?, usedForVision: Boolean): Boolean = userSaid ?: (usedForVision || stored)

    /** 服务商的能力初值:地址对得上预设的用预设的,否则用「自定义」的。 */
    fun baseOf(p: Provider): Capabilities = (ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.custom).capabilities

    /**
     * 一个服务商可选的全部模型:记住的列表(合并能力初值;已有配置的用配置里的能力),
     * 再加上已有配置、但列表里没有的模型(例如手动填的),和手填过、还没有配置的([Provider.typedModels])。
     * [includeTts] 为真时也列列表里写明 Mika 不会说的接口协议的模型(服务商详情要看完整目录)。
     * 用户说过能不能看图的([Provider.visionOverrides])按用户说的。
     */
    fun ofProvider(p: Provider, configs: List<ProviderConfig>, includeTts: Boolean = false): List<ModelOption> =
        listedOf(p, configs, includeTts).mapNotNull { o ->
            val said = p.visionOverrides[o.id] ?: return@mapNotNull o
            o.copy(capabilities = o.capabilities.copy(vision = said), visionByUser = said)
        }

    private fun listedOf(p: Provider, configs: List<ProviderConfig>, includeTts: Boolean): List<ModelOption> {
        val saved = configs.filter { it.providerId == p.id }
        val byModel = saved.associateBy { it.model }
        val base = baseOf(p)
        val listed = merge(p.models.orEmpty(), base, includeUnsupported = includeTts).map { o -> byModel[o.id]?.let { o.copy(capabilities = it.capabilities) } ?: o }
        val rest = saved.filter { c -> listed.none { it.id == c.model } }.map {
            // 列不出模型的那家,模型都是手填的;有列表的,列表后来没了的那个不算手填
            val typed = it.model in p.typedModels || p.models?.isEmpty() == true
            ModelOption(it.model, it.capabilities, toolCallsKnown = false, visionKnown = false, contextKnown = false, typed = typed)
        }
        val options = listed + rest
        val knownIds = options.mapTo(mutableSetOf()) { it.id }
        // 手填过、还没用在哪个用途上的(没有配置):照样列出来,能力按这家的初值
        val typed = p.typedModels.filter { it !in knownIds }.sorted().map {
            ModelOption(it, base, toolCallsKnown = false, visionKnown = false, contextKnown = false, typed = true)
        }
        return options + typed
    }
}

/**
 * 从模型名里读出的版本,只用来给同一家的模型排先后,不评哪一档(flash、pro、lite…)更好。
 * 模型名按 `-` `_` `:` `/` 等切成段(小写,保留小数点):
 * - [numbers] 是第一个像版本号的段:1–2 位数字,可带小数(`2.5`、`3.1`)、前面可带字母(`qwen3`、`v3.2`、`k2`、`x4`)、
 *   后面可带一个字母(`4o`、`1v`);以 b / k / m 结尾的是参数量或上下文长度(`72b`、`8k`),不算。
 *   紧跟着的 1–2 位纯数字段接成小版本(`claude-3-5` → 3.5、`seed-1-6` → 1.6)。
 * - [date] 是其余的纯数字段拼成的日期或快照号:有 4 位年份的按年月日(`-2025-08-07`、`-09-2025`),
 *   否则照写的数(`-0827`、`-20241022`、`-001`;6 位的当作年份两位的日期);没有为 null。
 * - [stage]:0 正式版;1 `latest` 这类会变的别名;2 预览、试验(`preview`、`exp`、`beta`、`alpha`、`rc`…)。
 * 版本号和日期都没有的读不出([parsed] 为假)。
 */
internal data class ModelVersion(val numbers: List<Int>, val stage: Int, val date: Long?) {
    val parsed: Boolean get() = numbers.isNotEmpty() || date != null

    companion object {
        /** 读得出的在前;版本大的在前;同版本正式版、别名、预览依次;没写日期的(多半指向最新的快照)在前,再按日期新的在前。 */
        val NEWEST_FIRST: Comparator<ModelVersion> = Comparator { a, b ->
            when {
                a.parsed != b.parsed -> if (a.parsed) -1 else 1
                !a.parsed -> 0
                else -> compareNumbers(b.numbers, a.numbers).takeIf { it != 0 }
                    ?: a.stage.compareTo(b.stage).takeIf { it != 0 }
                    ?: compareValues(b.date ?: Long.MAX_VALUE, a.date ?: Long.MAX_VALUE)
            }
        }

        fun of(id: String): ModelVersion {
            val parts = id.lowercase().split(Regex("[^a-z0-9.]+")).filter { it.isNotEmpty() }
            var numbers = emptyList<Int>()
            var at = -1
            for ((i, p) in parts.withIndex()) {
                val m = VERSION.matchEntire(p) ?: continue
                if (m.groupValues[3] in SIZE_SUFFIXES) continue
                numbers = m.groupValues[2].split('.').map { it.toInt() }
                at = i
                break
            }
            var end = at + 1
            if (at >= 0 && numbers.size == 1) {
                // 用横线分开的小版本:claude-3-5、seed-1-6
                while (end < parts.size && numbers.size < 3 && MINOR.matches(parts[end])) numbers = numbers + parts[end++].toInt()
            }
            val rest = parts.filterIndexed { i, _ -> i < at || i >= end }
            val stage = when {
                rest.any { p -> PRE_RELEASE.any { p.startsWith(it) } } -> 2
                "latest" in rest -> 1
                else -> 0
            }
            return ModelVersion(numbers, stage, dateOf(rest.filter { p -> p.all { it.isDigit() } }))
        }

        private fun dateOf(digits: List<String>): Long? {
            if (digits.isEmpty()) return null
            val year = digits.firstOrNull { it.length == 4 && it.toInt() in 1990..2099 }
            if (year != null) {
                val md = (digits - year).take(2).map { it.take(2).toLong() }
                return year.toLong() * 10_000 + (md.getOrNull(0) ?: 0) * 100 + (md.getOrNull(1) ?: 0)
            }
            val joined = digits.joinToString("").take(14)
            return if (digits.size == 1 && joined.length == 6) 20_000_000 + joined.toLong() else joined.toLong()
        }

        private fun compareNumbers(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = (a.getOrNull(i) ?: 0).compareTo(b.getOrNull(i) ?: 0)
                if (c != 0) return c
            }
            return 0
        }

        private val VERSION = Regex("([a-z]*)((?:0|[1-9]\\d?)(?:\\.\\d{1,3})*)([a-z]?)")
        private val MINOR = Regex("[1-9]?\\d")
        private val SIZE_SUFFIXES = setOf("b", "k", "m")
        private val PRE_RELEASE = listOf("preview", "exp", "beta", "alpha", "rc", "dev", "test")
    }
}

/** 服务商的归组规则和显示名:同一接口类型、同一地址、同一名字算同一个服务商。 */
object ProviderConnection {
    fun keyOf(kind: ProviderConfig.Kind, baseUrl: String, name: String) = "${kind.name}|${normalizeUrl(baseUrl)}|${name.trim()}"

    /** 没起名字的用地址的主机名。 */
    fun displayName(c: ProviderConfig): String = c.name.ifBlank { nameForUrl(c.baseUrl) }

    fun nameForUrl(baseUrl: String): String =
        ProviderPresets.forBaseUrl(baseUrl)?.label ?: baseUrl.trim().toHttpUrlOrNull()?.host ?: baseUrl.trim()

    fun normalizeUrl(url: String) = url.trim().trimEnd('/')
}
