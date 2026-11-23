package com.wangzan101.mika.wechat

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.cap.wechat.WechatAccount
import com.wangzan101.mika.core.cap.wechat.WechatOwnerPort
import com.wangzan101.mika.core.cap.wechat.WechatOwnerResult
import java.security.MessageDigest

/**
 * 机主结果推送的出口:构造时快照账号与投递目标,发送时重读比对——运行中途换绑、停用或线程失效都判
 * 不可用且一次都不发。投递经 [Outbox]:同一运行同一段文本只发一次,结局不明也不自动重发。
 */
class AndroidWechatOwnerPort internal constructor(
    private val store: WechatStore,
    private val channel: Channel,
    private val outbox: Outbox,
    private val runKey: String,
) : WechatOwnerPort {
    constructor(app: MikaApp, runKey: String) : this(app.wechat.store, app.wechat.channel, app.outbox, runKey)

    private val expectedAccount: WechatAccount? = store.account()
    private val expectedPeer = store.boundOwnerPeer()

    override suspend fun send(text: String): WechatOwnerResult {
        val account = expectedAccount
        val peer = expectedPeer
        if (account == null || peer == null) {
            return WechatOwnerResult(WechatOwnerResult.Status.Unavailable,
                "No WeChat chat is bound to the owner; nothing was sent.")
        }
        if (store.account() != account || store.boundOwnerPeer() != peer) {
            return WechatOwnerResult(WechatOwnerResult.Status.Unavailable,
                "The WeChat binding changed or was paused during this run; nothing was sent.")
        }
        val id = "owner-send:$runKey:${sha256(peer.threadId + text)}"
        val record = outbox.deliver(channel, peer, Delivery.Result(id, peer.threadId, text))
        return when (record.state) {
            DeliveryState.Delivered -> WechatOwnerResult(WechatOwnerResult.Status.Delivered)
            DeliveryState.Uncertain -> WechatOwnerResult(WechatOwnerResult.Status.Uncertain,
                "The message was handed to the channel but delivery was not confirmed; it was not resent.")
            else -> WechatOwnerResult(WechatOwnerResult.Status.Failed,
                "The WeChat channel did not accept the message.")
        }
    }

    private companion object {
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
    }
}
