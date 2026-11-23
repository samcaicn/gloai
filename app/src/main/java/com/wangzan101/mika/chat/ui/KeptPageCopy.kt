package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.core.cap.web.KeptPageClose
import com.wangzan101.mika.i18n.tr

/** 留在后台运行的那一页在界面上的说法:剩多久、为什么关了。 */
object KeptPageCopy {
    /** 「1 小时 52 分」「2 小时」「8 分钟」;不到一分钟按一分钟说。 */
    fun duration(ms: Long): String {
        val minutes = ((ms.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
        val h = (minutes / 60).toInt()
        val m = (minutes % 60).toInt()
        return when {
            h == 0 -> tr(R.string.kept_m, m)
            m == 0 -> tr(R.string.kept_h, h)
            else -> tr(R.string.kept_hm, h, m)
        }
    }

    /** 还剩 [ms] 自动关时的说法(拼在「…后自动关」里)。 */
    fun closesIn(ms: Long): String = duration(ms)

    /** 关掉的原因;认不出(旧记录、没记下)时按「Mika 退出了」说。 */
    fun reason(wire: String?): String = when (KeptPageClose.of(wire)) {
        KeptPageClose.Expired -> tr(R.string.kept_reason_expired)
        KeptPageClose.Memory -> tr(R.string.kept_reason_memory)
        KeptPageClose.Crashed -> tr(R.string.kept_reason_crashed)
        KeptPageClose.User -> tr(R.string.kept_reason_user)
        KeptPageClose.Replaced -> tr(R.string.kept_reason_replaced)
        KeptPageClose.AppExit, null -> tr(R.string.kept_reason_app_exit)
    }

    /** 那一轮下面那句:「后台网页已关闭(原因)」;原因还没记下(刚关、或 Mika 中途退出过)时不说原因。 */
    fun closedNote(wire: String?): String = if (KeptPageClose.of(wire) == null) tr(R.string.kept_closed_plain) else tr(R.string.kept_closed_note, reason(wire))
}
