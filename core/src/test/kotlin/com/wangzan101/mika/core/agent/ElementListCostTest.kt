package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.uistate.ScreenJson
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * 元素列表（页面结构）的成本剖析：用一张接近真实的屏（几十个元素）测
 * 当前序列化（bounds→size，无损）相对旧 bounds 格式的 token 差异。
 * 只做测量，不改生产代码。跑法：./gradlew :core:test --tests '*ElementListCostTest' --info
 */
class ElementListCostTest {

    private fun realisticScreen(): ScreenSnapshot {
        val els = mutableListOf<UiElement>()
        var n = 0
        fun el(role: String, name: String, y: Int, clickable: Boolean = false, editable: Boolean = false, scrollable: Boolean = false) {
            n++
            val top = (y - 40).coerceAtLeast(0)
            val bottom = (y + 40).coerceAtMost(2400)
            els += UiElement("@e$n", role, name, 540, y * 24 / 10, listOf(0, top, 999, bottom), null, clickable, editable, scrollable)
        }
        // 顶部栏 + 标题
        el("text", "返回", 60, clickable = true)
        el("text", "商品详情", 60)
        el("text", "收藏", 60, clickable = true)
        el("image", "商品主图", 300)
        el("text", "【旗舰店】新款无线降噪耳机 主动降噪 蓝牙5.3 长续航", 460)
        el("text", "¥ 899", 560)
        el("text", "已售 1.2万+", 620)
        // 服务标签一排
        el("text", "假一赔十", 700)
        el("text", "极速发货", 700)
        el("text", "7天无理由", 700)
        // 优惠券、服务
        el("text", "领券", 780, clickable = true)
        el("text", "满899减100", 780)
        el("text", "服务", 860, clickable = true)
        el("text", "支持分期 · 官方质保", 860)
        // 选择规格
        el("text", "颜色分类", 940, clickable = true)
        el("text", "曜石黑 · 象牙白 · 远峰蓝", 940)
        el("text", "数量", 1020, clickable = true)
        el("edit", "数量 1", 1020, editable = true)
        // 评价
        el("text", "商品评价(2.3万+)", 1120, clickable = true)
        el("text", "音质很好，降噪效果明显，戴着舒服", 1200)
        el("text", "续航给力，通勤一周充一次", 1260)
        // 图文详情
        el("text", "图文详情", 1360, clickable = true)
        el("text", "店铺推荐", 1460, clickable = true)
        el("text", "同店好物", 1560, clickable = true)
        // 猜你喜欢（可滚动列表）
        el("text", "猜你喜欢", 1680)
        el("text", "相似商品1 无线耳机 ￥399", 1780)
        el("text", "相似商品2 头戴耳机 ￥599", 1880)
        el("text", "相似商品3 运动耳机 ￥299", 1980)
        el("text", "相似商品4 充电盒 ￥129", 2080)
        el("text", "相似商品5 收纳包 ￥59", 2180)
        // 底部操作栏
        el("text", "店铺", 2360, clickable = true)
        el("text", "客服", 2360, clickable = true)
        el("text", "购物车", 2360, clickable = true)
        el("button", "加入购物车", 2360, clickable = true)
        el("button", "立即购买", 2360, clickable = true)
        // 弹窗：选择规格
        el("text", "选择 颜色分类 / 数量", 1200)
        el("text", "曜石黑", 1300, clickable = true)
        el("text", "象牙白", 1300, clickable = true)
        el("text", "远峰蓝", 1300, clickable = true)
        el("button", "确认", 1500, clickable = true)
        return ScreenSnapshot("s1", "com.example.shop", els, 1080, 2400)
    }

    private fun resultWith(elements: JsonArray) = buildJsonObject {
        put("snapshotId", "s1")
        put("currentApp", "com.example.shop")
        put("elements", elements)
    }

    /** 把当前 size 格式还原成旧 bounds 格式（[l,t,r,b]），模拟改动前发送的内容。 */
    private fun toOldBounds(e: JsonElement): JsonElement {
        val o = e.jsonObject
        val c = o["center"]?.jsonObject
        val sz = o["size"] as? JsonArray
        val cx = (c?.get("x") as? JsonPrimitive)?.content?.toIntOrNull()
        val cy = (c?.get("y") as? JsonPrimitive)?.content?.toIntOrNull()
        val w = (sz?.getOrNull(0) as? JsonPrimitive)?.content?.toIntOrNull()
        val h = (sz?.getOrNull(1) as? JsonPrimitive)?.content?.toIntOrNull()
        return buildJsonObject {
            o.forEach { (k, v) ->
                if (k == "size" && cx != null && cy != null && w != null && h != null)
                    put("bounds", JsonArray(listOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2).map(::JsonPrimitive)))
                else put(k, v)
            }
        }
    }

    private fun dropField(elements: JsonArray, drop: String) = JsonArray(elements.map { e ->
        val o = e.jsonObject
        buildJsonObject { o.forEach { (k, v) -> if (k != drop) put(k, v) } }
    })

    @Test fun measure() {
        val s = realisticScreen()
        val (full, _) = ScreenJson.elements(s, Int.MAX_VALUE)
        val oldBounds = JsonArray(full.map(::toOldBounds))
        val noSize = dropField(full, "size")
        val noCenter = dropField(full, "center")

        val fullRaw = resultWith(full).toString()
        val oldBoundsRaw = resultWith(oldBounds).toString()
        val noSizeRaw = resultWith(noSize).toString()
        val noCenterRaw = resultWith(noCenter).toString()
        val fullTable = ScreenElementTable.text(resultWith(full), true)
        val oldBoundsTable = ScreenElementTable.text(resultWith(oldBounds), true)

        val t = { x: String -> TokenEstimate.of(x) }
        val n = s.elements.size
        val sb = StringBuilder()
        sb.append("=== 元素列表成本剖析（$n 个元素，一张真实屏） ===\n\n")
        sb.append("%-24s %8s %8s\n".format("序列化方式", "token", "相对旧格式"))
        sb.append("%-24s %8d %8s\n".format("旧格式 bounds（改动前）", t(oldBoundsRaw), "—"))
        sb.append("%-24s %8d %7.0f%%\n".format("当前 size（无损，D）", t(fullRaw), t(fullRaw) * 100.0 / t(oldBoundsRaw)))
        sb.append("%-24s %8d %7.0f%%\n".format("再去掉 size", t(noSizeRaw), t(noSizeRaw) * 100.0 / t(oldBoundsRaw)))
        sb.append("%-24s %8d %7.0f%%\n".format("再去掉 center", t(noCenterRaw), t(noCenterRaw) * 100.0 / t(oldBoundsRaw)))
        sb.append("%-24s %8d %7.0f%%\n".format("旧格式 + 表格压缩", t(oldBoundsTable), t(oldBoundsTable) * 100.0 / t(oldBoundsRaw)))
        sb.append("%-24s %8d %7.0f%%\n".format("当前 + 表格压缩（已开）", t(fullTable), t(fullTable) * 100.0 / t(oldBoundsRaw)))
        sb.append("\n")

        fun fieldCost(drop: String): Int = t(fullRaw) - t(resultWith(dropField(full, drop)).toString())
        val perElem = t(fullRaw).toDouble() / n
        sb.append("每个元素平均 %6.1f token；字段占比（当前 JSON 里去掉该字段省的 token / 全量）：\n".format(perElem))
        for (f in listOf("size", "center", "name", "role", "ref", "resourceId", "hint")) {
            val cost = fieldCost(f)
            if (cost > 0) sb.append("  %-12s %5d token  (%4.0f%%)\n".format(f, cost, cost * 100.0 / t(fullRaw)))
        }

        // 换算到一次任务：8 次看屏、16 轮，每轮全量重发一次（差分链每 5 次断一次 ≈ 全量占一部分，这里按全量计是上界）
        val perScreenSave = t(oldBoundsRaw) - t(fullRaw)
        sb.append("\nD（bounds→size）每屏省 %d token；8 次看屏 ≈ 省 %d token（%d 计价单位，1.25× 首次写入）。\n".format(
            perScreenSave, perScreenSave * 8, (perScreenSave * 8 * 1.25).toInt()))
        println(sb)
    }
}
