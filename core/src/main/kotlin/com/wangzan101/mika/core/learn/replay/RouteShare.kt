package com.wangzan101.mika.core.learn.replay

import com.wangzan101.mika.core.tool.ToolIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * 分享出去的一条路线:本地路线原样([route]),外加给人看的名字与说明。
 * 路线格式本身不带个人数据(见 [RouteAnchors]:输入框只存提示语、原话里的可变部分参数化成槽位、
 * 锚点排除数字金额),分享前由界面把整份内容展示给用户确认;导入时先过 [RouteShareLint],
 * 能在导入时就拒绝的(不认识或不可回放的工具、指纹对不上、超限),不等到运行时。
 */
@Serializable
data class RouteShare(
    /** 共享格式的版本;导入只认自己认识的版本。 */
    val schema: Int = SCHEMA,
    val route: Route,
    val title: String,
    val summary: String = "",
    /** [RouteFingerprint] 算出的路径指纹:发布端按它查重(已有人分享过就不必再传),导入端核对内容没有被改过。 */
    val fingerprint: String,
) {
    init {
        require(schema == SCHEMA) { "unsupported route share schema: $schema" }
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun of(route: Route, title: String, summary: String = "") =
            RouteShare(route = route, title = title, summary = summary, fingerprint = RouteFingerprint.of(route))

        fun decode(s: String): RouteShare = json.decodeFromString(serializer(), s)
    }
}

/**
 * 路径指纹:[samePath] 语义的哈希化——每步的工具、参数、目标,加上模板、槽位数与涉及的 App。
 * 锚点不进指纹(每次读屏略有出入,不算不同;与 [samePath] 的取舍一致),所以不同设备、不同系统上
 * 录出的同一条路,指纹一致,发布端据此去重。编码逐字段写死,不依赖 data class 的 toString,
 * 保证跨版本稳定。
 */
object RouteFingerprint {
    fun of(r: Route): String {
        val d = MessageDigest.getInstance("SHA-256")
        fun line(vararg parts: String) {
            d.update(parts.joinToString("\u0000").toByteArray())
            d.update(LINE)
        }
        line(*r.packages.sorted().toTypedArray())
        line(r.template, r.slots.toString())
        r.steps.forEach { s -> line(s.tool, s.argsJson, s.target?.encoded().orEmpty()) }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Selector.encoded(): String =
        listOf(role, name, resourceId.orEmpty(), bounds.joinToString(","), ordinal.toString(), uniqueAtRecord.toString())
            .joinToString("\u0000")

    private val LINE = byteArrayOf(0x0a)
}

/**
 * 导入校验:结构性硬约束在这里拒绝;软性的(界面改版导致锚点对不上)由运行时的失配回退
 * 与「有人跑通了就更新」的版本演进处理,不在这里拦。返回的问题列表为空表示可收下。
 */
object RouteShareLint {
    fun check(share: RouteShare, tools: ToolIndex): List<String> {
        val problems = mutableListOf<String>()
        val r = share.route
        if (share.title.isBlank()) problems += "title is blank"
        if (r.template.isBlank()) problems += "template is blank"
        if (r.slots < 0) problems += "negative slot count"
        if (r.disabled) problems += "route is disabled"
        if (r.steps.size > MAX_ROUTE_STEPS) problems += "too many steps: ${r.steps.size} > $MAX_ROUTE_STEPS"
        if (share.encode().toByteArray(Charsets.UTF_8).size > MAX_ROUTE_BYTES) problems += "share exceeds $MAX_ROUTE_BYTES bytes"
        if (RouteFingerprint.of(r) != share.fingerprint) problems += "fingerprint does not match content"
        r.steps.forEachIndexed { i, s ->
            val info = tools[s.tool]
            when {
                info == null -> problems += "step ${i + 1}: unknown tool ${s.tool}"
                !info.routable -> problems += "step ${i + 1}: ${s.tool} is not a replayable action"
            }
            if (jsonObject(s.argsJson) == null) problems += "step ${i + 1}: args are not a JSON object"
        }
        return problems
    }

    /**
     * 路线实际会碰到的 App(锚点里记下的包名):审核「是否就是这个声明的任务」时,
     * 声明里写的 App 必须盖住它,步骤走到了声明之外的 App 就该拦下。每步动作前、后两屏的包名都算(和录路线时
     * [Route.packages] 里取锚点包名的那部分一样):一步点完跳进别的 App 时,那个 App 只出现在这一步的 after 里(最后一步之后没有下一步的 before)。
     */
    fun touchedPackages(share: RouteShare): Set<String> =
        share.route.steps.flatMap { listOfNotNull(it.before.packageName, it.after.packageName) }.toSet()

    private val json = Json { ignoreUnknownKeys = true }

    private fun jsonObject(s: String): JsonObject? = try {
        Json.parseToJsonElement(s.ifBlank { "{}" }) as? JsonObject
    } catch (e: SerializationException) {
        null
    }
}
