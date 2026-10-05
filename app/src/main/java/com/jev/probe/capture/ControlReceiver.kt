package com.jev.probe.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.jev.probe.core.AutoReply

/**
 * 处理保活通知上的「暂停 / 开启自动收发」动作。
 *
 * 为什么单独一个 receiver：通知动作本质是一次 PendingIntent 广播，不能直接调
 * 悬浮窗（overlay 可能压根没建），也不能依赖 App 进程活着——用户在锁屏下拉通知栏
 * 就按下去的时候，进程可能刚被系统回收。这个 receiver 让「收权」这条路径完全不
 * 依赖任何 UI 状态，是整个自动收发链路上最短、最可靠的一条。
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != ACTION_TOGGLE_AUTO) return
        val msg = AutoReply.setOn(ctx, !AutoReply.isOn(ctx))
        // 立刻重画通知，让「暂停/开启」这两个字和状态栏文案马上变成切换后的样子，
        // 否则用户按完看不出到底切没切。
        KeepAliveService.refresh(ctx)
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION_TOGGLE_AUTO = "com.jev.probe.action.TOGGLE_AUTO"
    }
}
