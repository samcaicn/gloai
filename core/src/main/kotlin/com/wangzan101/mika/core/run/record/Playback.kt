package com.wangzan101.mika.core.run.record

import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.normalize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

/** 截图上的标记，坐标沿用工具的 0–999；元素位置与滚动方向示意不声称是实际触点。 */
@Serializable
data class PlaybackMark(val kind: String, val x: Int, val y: Int, val endX: Int? = null, val endY: Int? = null, val approximate: Boolean = false) {
    fun valid(): Boolean = x in 0..999 && y in 0..999 && when (kind) {
        "tap" -> endX == null && endY == null
        "swipe" -> endX in 0..999 && endY in 0..999 && (x != endX || y != endY)
        else -> false
    }

    companion object {
        fun of(verb: Verb, args: JsonObject, element: UiElement?, screen: ScreenSnapshot?): PlaybackMark? {
            fun n(key: String) = (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0..999 }
            val mark = when (verb) {
                Verb.Tap -> {
                    val x = n("x") ?: element?.let { screen?.let { s -> normalize(it.centerX, s.width) } }
                    val y = n("y") ?: element?.let { screen?.let { s -> normalize(it.centerY, s.height) } }
                    if (x != null && y != null) PlaybackMark("tap", x, y, approximate = n("x") == null || n("y") == null) else null
                }
                Verb.Swipe -> {
                    val x = n("startX"); val y = n("startY"); val ex = n("endX"); val ey = n("endY")
                    if (x != null && y != null && ex != null && ey != null) PlaybackMark("swipe", x, y, ex, ey) else null
                }
                Verb.Scroll -> {
                    val dir = (args["direction"] as? JsonPrimitive)?.content
                    val amount = (args["amount"] as? JsonPrimitive)?.intOrNull ?: 3
                    if (dir !in listOf("up", "down", "left", "right") || amount !in 1..20) return null
                    val x = n("x") ?: 500; val y = n("y") ?: 500
                    val distance = minOf(650, amount * 100)
                    val dx = when (dir) { "left" -> distance; "right" -> -distance; else -> 0 }
                    val dy = when (dir) { "up" -> distance; "down" -> -distance; else -> 0 }
                    fun bound(v: Double) = v.roundToInt().coerceIn(50, 949)
                    PlaybackMark("swipe", bound(x - dx / 2.0), bound(y - dy / 2.0), bound(x + dx / 2.0), bound(y + dy / 2.0), approximate = true)
                }
                else -> null
            }
            return mark?.takeIf { it.valid() }
        }
    }
}

/** 只供本地记录的图像，不进入模型请求或工具结果 JSON。 */
class PlaybackCapture(val before: ByteArray?, val after: ByteArray?, val mark: PlaybackMark?)
