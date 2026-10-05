package com.jev.probe.core

import android.content.Context

/**
 * 「自动收发」这一个开关的单一事实源。
 *
 * 自动发送是全应用唯一会替用户把消息真的发出去的开关，所以「开启 / 暂停」必须
 * 在三个入口（悬浮球菜单、通知栏动作、设置页）表现完全一致，否则会出现「菜单里
 * 显示已暂停、通知里却还在自动发」这种危险的错位。三个入口都调这里。
 *
 * 语义：**发送权默认在人手里**。[setOn] 只在用户明确点「开启」时才把 autoSend 打开；
 * 暂停则同时关掉 autoFillBest，保证「只给建议、不代发」这个承诺是真的。
 */
object AutoReply {

    /** 自动收发是否处于「全自动」状态（读 → 选 → 填 → 发都归我）。 */
    fun isOn(ctx: Context): Boolean {
        val p = Prefs(ctx)
        return p.autoSend && p.autoFillBest
    }

    /**
     * 设置自动收发状态，返回给用户看的一句话反馈（各入口直接拿去 toast）。
     * [on] = true 开启全自动；false 退回到「只给建议并自动填好，发送由你点」。
     */
    fun setOn(ctx: Context, on: Boolean): String {
        val p = Prefs(ctx)
        p.autoFillBest = on
        p.autoSend = on
        return if (on) "已开启自动收发：对方发消息 → 我读 → 自动回复并发送"
        else "已暂停自动收发：只给建议并自动填好，发送由你点"
    }

    /** 通知栏 / 气泡上显示的一行状态说明。 */
    fun statusText(ctx: Context): String = if (isOn(ctx))
        "自动收发已开启 · 在聊天旁读消息并自动回复"
    else
        "在聊天旁读消息、给回复建议"
}
