package com.wangzan101.mika.core.frontdesk

/** 前台要说的一条汇报:[event] 是后台任务的变化,[title] 是派单时给这件任务起的短名。 */
data class DeskReport(val event: DeskEvent, val title: String)

/**
 * 此刻接着前台的入口(聊天页、语音通话、电话……),按自己的说话方式把汇报说出来。
 * 返回 false 表示入口不在线、没说;说到一半被用户打断时抛 [kotlinx.coroutines.CancellationException]。
 */
interface DeskSurface {
    suspend fun deliver(report: DeskReport): Boolean
}

/** 没有入口在线时的兜底:写回这条会话、发通知,由 app 层实现。 */
interface DeskSink {
    suspend fun record(conversationId: String, report: DeskReport)
}
