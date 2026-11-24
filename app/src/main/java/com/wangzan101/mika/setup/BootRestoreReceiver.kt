package com.wangzan101.mika.setup

import com.wangzan101.mika.cap.phone.setup.BootRestoreHandler
import com.wangzan101.mika.task.TriggerRuntime
import android.content.Context
import android.content.Intent
import android.app.AlarmManager

/** 开机广播的入口:清单里登记的是这个全名;实现在手机能力里。 */
class BootRestoreReceiver : BootRestoreHandler() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED) TriggerRuntime.rearm(context)
        // 换了时区:跟随设备时区的当地时间计划立刻按新时区重算(内部会重新布置闹钟),显式钉了其他时区的不动
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) TriggerRuntime.syncTimeZone(context)
        // 开机时局域网那种电脑连接开着就重新监听(USB 那种不跨重启:换了开机编号,这里已按关着算,上次的连接信息也清掉)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) (context.applicationContext as com.wangzan101.mika.MikaApp).mcp.ensureRunning()
        // AI 电话之前设置过:把通话运行时拉起来,内置助手恢复连接后它会自动重做开机后丢掉的那一步
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            com.wangzan101.mika.calls.CallTelecomAccess(context.applicationContext).preparedOnEarlierBoot()
        ) (context.applicationContext as com.wangzan101.mika.MikaApp).calls
    }
}
