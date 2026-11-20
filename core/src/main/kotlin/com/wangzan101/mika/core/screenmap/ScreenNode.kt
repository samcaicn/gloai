package com.wangzan101.mika.core.screenmap

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一次动作把图带去了哪：换了屏 / 同屏变了状态 / 没变 / 跳去别的 App / 未知。 */
enum class TransitionEffect { NAVIGATED, STATE_CHANGED, NONE, LEFT_APP, UNKNOWN }

/**
 * 一条点击导航边：在某屏点 [action]（目标元素 [target]）→ 到 [to] 屏。
 * [count] 走通几次，[offTrack] 点完没到预期几次。同一条边靠 [key] 去重。
 */
@Serializable
data class Transition(
    val action: String,
    val target: String?,
    val effect: TransitionEffect,
    val to: String?,
    val count: Int = 0,
    val offTrack: Int = 0,
) {
    /** 去重键：同一 (动作, 目标, 效果, 到哪屏) 是一条边，次数/跑偏另计。 */
    fun key(): String = "$action\u0000${target ?: ""}\u0000${effect.name}\u0000${to ?: ""}"
}

/**
 * 一个结构页（屏）。身份靠骨架锚点 [anchors] 自描述；名字/层级字段本版留空，供以后标。
 * [transitions] 键是 [Transition.key]，值是这条边。
 */
@Serializable
data class ScreenNode(
    val id: String,
    val app: String,
    val name: String = "",
    val anchors: Map<String, Int> = emptyMap(),
    val levels: Map<String, Int> = emptyMap(),
    val transitions: Map<String, Transition> = emptyMap(),
    val visits: Int = 0,
    val status: String = "provisional",
)

/** 确定性屏 id：同一 (app, 骨架签名) 永远同一个 id，可整体重建逐字节相同。 */
object ScreenId {
    fun of(app: String, signature: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest((app + "\u0000" + signature).toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** 骨架锚点签名：稳定骨架文字去重后排序拼接。建图与自检共用同一口径。 */
    fun signature(texts: List<String>): String = texts.distinct().sorted().joinToString("\u0000")
}

object ScreenNodeCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(n: ScreenNode): String = json.encodeToString(ScreenNode.serializer(), n)
    fun decode(s: String): ScreenNode = json.decodeFromString(ScreenNode.serializer(), s)
}
