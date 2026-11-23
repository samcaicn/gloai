package com.wangzan101.mika.calls

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 请示主人的通知里「回复」的落点:清单里注册、不导出;回复原文交给 [OwnerAskNotifier.deliver]。 */
class OwnerReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OwnerAskNotifier.ACTION_REPLY) return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(OwnerAskNotifier.KEY_REPLY)?.toString()
        OwnerAskNotifier.deliver(context.applicationContext, intent.getStringExtra(OwnerAskNotifier.EXTRA_TOKEN), reply)
    }
}
