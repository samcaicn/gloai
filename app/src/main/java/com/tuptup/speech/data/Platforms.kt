package com.tuptup.speech.data

/** 直播平台：登录入口 + 评论采集节点选择器。集中管理，设置浮层与采集页共用。 */
data class LivePlatform(val name: String, val loginUrl: String, val selector: String)

object Platforms {
    val ALL = listOf(
        LivePlatform("抖音", "https://www.douyin.com/", "[class*=comment], [class*=chat], [class*=danmu]"),
        LivePlatform("快手", "https://www.kuaishou.com/", "[class*=comment-item], [class*=comment], [class*=danmu]"),
        LivePlatform("B站", "https://www.bilibili.com/", ".chat-item, [class*=danmaku], [class*=comment]"),
        LivePlatform("视频号", "https://channels.weixin.qq.com/", "[class*=comment], [class*=danmu], .chat-item"),
        LivePlatform("淘宝直播", "https://taobaolive.taobao.com/", "[class*=comment], [class*=danmu], .chat-item")
    )

    fun byName(name: String): LivePlatform? = ALL.firstOrNull { it.name == name }
}
