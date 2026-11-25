package com.wangzan101.mika.core.live

import java.util.Locale

/**
 * 直播间弹幕的纯逻辑部分:把一屏读出来的字挑成值得念的评论,再去重、限速。
 * 不碰 Android、不碰屏幕端口,可以在 JVM 上测。
 *
 * 直播间里除了评论还有一堆按钮、进场/送礼提示和统计数字。这些东西念出来只会吵,
 * 所以 [plausible] 只放行像人话的短句;[LiveDanmakuFilter] 再保证同一条不重复念、
 * 两次播报之间留出间隔(播报器本身是串行的,间隔防止新评论把队列淹没)。
 */
object LiveDanmakuText {
    private val WS = Regex("\\s+")
    private val LINKS = Regex("(https?://|www\\.|\\.com/|\\.cn/)", RegexOption.IGNORE_CASE)
    // 抖音的评论 TextView 常带 U+200E(从右到左标记)和前导星号,不去掉就匹配不到「欢迎」等前缀
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\uFEFF]")
    // 「* xxx 来了 · …」是进场提示,不是评论;注意「又来了」这种真评论不含空白,不能误杀
    private val ENTERED = Regex("\\s来了")

    /** 直播间的按钮、标签、状态栏文字。整行等于这些就不念。 */
    private val CHROME = setOf(
        "关注", "已关注", "关注主播", "点赞", "分享", "礼物", "排行榜", "榜一", "弹幕", "聊天",
        "发送", "说点什么", "说点什么...", "说点什么…", "直播间", "直播中", "正在直播", "更多",
        "设置", "全屏", "退出", "返回", "关闭", "清屏", "举报", "退出直播间", "进入直播间",
        "打开抖音", "立即下载", "安装", "去看一看", "去看看", "去抢购", "立即购买", "小黄车",
        "购物袋", "商品", "福袋", "抽奖", "关注我", "主页", "评论", "消息", "未关注",
    )

    /** 进场、送礼、关注这类系统提示不是评论,即使句子像人话也不念。 */
    private val EVENT_PREFIXES = listOf(
        "进入直播间", "来了", "关注了主播", "关注了", "送出了", "赠送了", "送出了礼物",
        "点亮了", "加入了粉丝团", "分享直播间", "点赞了", "关注主播", "感谢", "欢迎",
    )

    private const val MIN_CHARS = 2
    private const val MAX_CHARS = 120

    /** 去空白、统一大小写,只用于比较;原句照念。 */
    fun normalize(text: String): String = WS.replace(INVISIBLE.replace(text, "").trim(), " ")
        .trimStart('*').trim()

    /** 这行字像不像一条观众评论。宁可漏念,不要把界面文字念出来。 */
    fun plausible(text: String): Boolean {
        val s = normalize(text)
        if (s.length !in MIN_CHARS..MAX_CHARS) return false
        if (CHROME.contains(s)) return false
        if (EVENT_PREFIXES.any(s::startsWith)) return false
        if (ENTERED.containsMatchIn(s)) return false
        if (LINKS.containsMatchIn(s)) return false
        // 全数字、全标点、纯表情都没有可念的内容
        if (s.none(Char::isLetterOrDigit)) return false
        if (s.all(Char::isDigit)) return false
        return true
    }
}

/**
 * 评论在屏幕上的大致纵向位置。直播间布局是「视频在上、评论列表在下方、输入框在最底部」,
 * 写死像素会在长屏机上全军覆没(实测 1080x2412 的评论在 y≈1640..2080)。
 * 按屏高取 60%..90%:避开顶部主播信息和底部输入框,不同分辨率都适用。
 */
object LiveDanmakuLayout {
    fun commentRange(screenHeight: Int): IntRange =
        (screenHeight * 0.60f).toInt()..(screenHeight * 0.90f).toInt()
}

/**
 * 弹幕过滤器。窗口 [windowSize] 内同样的字只念一次;[ttlMs] 之后彻底忘掉,
 * 免得一场直播看下来窗口塞满、真正的新评论被当成旧的。
 */
class LiveDanmakuFilter(
    private val windowSize: Int = 160,
    private val minIntervalMs: Long = 1_500L,
    private val ttlMs: Long = 180_000L,
) {
    private val seen = LinkedHashMap<String, Long>()
    private var lastAcceptedAt = Long.MIN_VALUE

    /** 通过就返回可以念的原句(去掉首尾空白),否则返回 null。 */
    fun accept(text: String, nowMs: Long): String? {
        if (!LiveDanmakuText.plausible(text)) return null
        if (nowMs - lastAcceptedAt < minIntervalMs) return null
        val key = LiveDanmakuText.normalize(text).lowercase(Locale.ROOT)
        expire(nowMs)
        if (seen.containsKey(key)) return null
        seen[key] = nowMs
        lastAcceptedAt = nowMs
        return LiveDanmakuText.normalize(text)
    }

    /** 开关弹幕或换直播间时清掉记忆,避免把新直播间的评论当旧的。 */
    fun clear() {
        seen.clear()
        lastAcceptedAt = Long.MIN_VALUE
    }

    private fun expire(nowMs: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val (_, at) = iterator.next()
            if (nowMs - at >= ttlMs) iterator.remove() else break
        }
        while (seen.size > windowSize) {
            val oldest = seen.entries.iterator().next()
            seen.remove(oldest.key)
        }
    }
}
