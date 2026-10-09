package com.jev.probe.core

import android.content.Context
import com.jev.probe.core.t

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

    /**
     * 自动收发是否处于「全自动」状态（读 → 选 → 填 → 发都归我）。
     *
     * 注意这只描述**发送**这一段，不含「自动分析」：助手在暂停状态下仍可能读界面、
     * 出建议、挂气泡（那是只读且不打扰的），只是不再替用户发消息。两个概念在
     * 悬浮球菜单里是分开的两项，见 [setOn] 与气泡菜单的「自动分析」开关。
     */
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
        return if (on) ctx.t("已开启自动收发：对方发消息 → 我读 → 自动回复并发送",
            "Auto send/receive on: when they message, I read, reply and send automatically")
        else ctx.t("已暂停自动收发：只给建议并自动填好，发送由你点",
            "Auto send/receive paused: suggestions only, auto-filled — you tap send")
    }

    /**
     * 通知栏 / 气泡上显示的一行状态说明。
     *
     * 要把「自动分析」和「自动发送」两层都讲出来，只说发送会让人误判：
     * 看到「在聊天旁读消息、给回复建议」时，助手其实仍在自动读界面出建议，
     * 只是不会替他发消息——这正是 [setOn] 关闭后的真实状态。
     */
    fun statusText(ctx: Context): String {
        val p = Prefs(ctx)
        return when {
            isOn(ctx) -> ctx.t("自动收发已开启 · 自动读消息并自动回复发送",
                "Auto send/receive on · reads messages and replies automatically")
            p.autoAnalyze -> ctx.t("自动分析已开启 · 只给建议，发送由你点",
                "Auto-analyze on · suggestions only, you tap send")
            else -> ctx.t("助手已待命 · 只在你点气泡时分析",
                "Assistant standing by · analyzes only when you tap the bubble")
        }
    }
}
