package com.tuptup.speech.data

/** 直播平台：登录入口 + 直播页（开播采集）+ 评论节点选择器。集中管理，设置登录浮层与开播采集共用。 */
data class LivePlatform(
    val name: String,
    val loginUrl: String,
    val liveUrl: String,
    val selector: String
)

object Platforms {
    // 登录页必须用桌面 UA 打开（见 LoginSheet）：移动 UA 下各站只显示「下载APP」、
    // 闪屏或 500，根本没有登录入口。下列 URL 均在真机 Chrome 桌面 UA 下实测能看到登录：
    //   抖音 www.douyin.com → 自动弹「登录后免费畅享高清视频」扫码/验证码登录框
    //   快手 www.kuaishou.com → 左侧红色「登录」按钮
    //   B站 passport.bilibili.com/login → 直接密码/短信/扫码登录页
    //   视频号 channels.weixin.qq.com/login.html → 视频号助手扫码登录页
    //   淘宝 live.taobao.com → 302 到 login.taobao.com 密码/短信/扫码登录
    // 注意 taobaolive.taobao.com 已返回 500，不可用。
    val ALL = listOf(
        LivePlatform("抖音", "https://www.douyin.com/", "https://live.douyin.com/",
            "[class*=chatroom] [class*=item], [class*=comment], [class*=danmu]"),
        LivePlatform("快手", "https://www.kuaishou.com/", "https://live.kuaishou.com/",
            "[class*=comment-item], [class*=comment], [class*=danmu]"),
        LivePlatform("B站", "https://passport.bilibili.com/login", "https://live.bilibili.com/",
            ".chat-item, [class*=danmaku], [class*=comment]"),
        LivePlatform("视频号", "https://channels.weixin.qq.com/login.html", "https://channels.weixin.qq.com/",
            "[class*=comment], [class*=danmu], .chat-item"),
        LivePlatform("淘宝直播", "https://live.taobao.com/", "https://live.taobao.com/",
            "[class*=comment], [class*=danmu], .chat-item")
    )

    fun byName(name: String): LivePlatform? = ALL.firstOrNull { it.name == name }
}
