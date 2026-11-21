package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.wechat.WechatChannel

/**
 * 开跑时为定时任务、已授权的微信任务、或本机开关开着的界面任务提供解锁；实际解锁时仍重新读取对应授权。
 * 其他来源(MCP、子任务)永远拿不到解锁授权。
 */
internal fun taskUnlockAuthorization(
    origin: RunOrigin,
    peer: ChannelPeer?,
    localUnlockEnabled: () -> Boolean = { false },
    canUnlockWechat: (ChannelPeer?) -> Boolean,
): (() -> Boolean)? = when {
    origin == RunOrigin.Trigger -> ({ true })
    origin == RunOrigin.Channel && peer?.channelId == WechatChannel.ID && canUnlockWechat(peer) ->
        ({ canUnlockWechat(peer) })
    origin == RunOrigin.Ui && localUnlockEnabled() -> ({ localUnlockEnabled() })
    else -> null
}
