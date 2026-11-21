package com.wangzan101.mika.calls

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Reads only a phone window or a call notification during a tracked incoming call. */
internal class IncomingCallLabels(context: Context) {
    private val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    private val phoneUis = context.packageManager.queryIntentServices(Intent(InCallService.SERVICE_INTERFACE),
        android.content.pm.PackageManager.GET_META_DATA).mapNotNull { resolved ->
        val service = resolved.serviceInfo
        service.packageName.takeIf { service.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            service.metaData?.getBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_UI) == true }
    }.toSet()

    fun read(service: AccessibilityService, event: AccessibilityEvent, number: String?, evidence: IncomingTypeEvidence): Boolean {
        val pkg = event.packageName?.toString() ?: return false
        val phone = pkg == dialer || pkg in phoneUis
        // A System UI notification row does not identify its publisher. Use the original
        // phone notification event for heads-up calls, even when it has no window ID.
        if (!phone) return false
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val notice = event.parcelableData as? Notification ?: return false
            return fromNotification(notice, number, evidence)
        }
        val root = service.windows.firstOrNull { it.id == event.windowId }?.root ?: return false
        if (root.packageName?.toString() != pkg) return false
        return fromCard(root, number, evidence, callSurface = pkg.endsWith(".incallui") && pkg in phoneUis)
    }

    @Suppress("DEPRECATION")
    private fun fromNotification(notice: Notification, number: String?, evidence: IncomingTypeEvidence): Boolean {
        if (notice.category == Notification.CATEGORY_MISSED_CALL ||
            notice.extras.getInt(Notification.EXTRA_CALL_TYPE, 0) !in 0..1 ||
            (notice.category != Notification.CATEGORY_CALL && !notice.channelId.equals("incall", true))) return false
        val numbers = mutableSetOf<String>()
        val labels = mutableSetOf<IncomingCallType>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notice.extras.getParcelable<Person>(Notification.EXTRA_CALL_PERSON)?.let { caller ->
                caller.uri?.takeIf { it.startsWith("tel:") }?.removePrefix("tel:")
                    ?.let(CallPolicy::incomingMatchKey)?.let(numbers::add)
                caller.name?.takeIf { it.length <= 80 }?.toString()?.let { name ->
                    CallPolicy.incomingMatchKey(name)?.let(numbers::add)
                    IncomingCallType.fromLabel(name, requireMark = true)?.let(labels::add)
                }
            }
        }
        for (field in listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT)) {
            val value = notice.extras.getCharSequence(field)?.takeIf { it.length <= 512 } ?: continue
            for (line in value.lineSequence().take(8)) {
                CallPolicy.incomingMatchKey(line)?.let(numbers::add)
                IncomingCallType.fromLabel(line, requireMark = field == Notification.EXTRA_TITLE)?.let(labels::add)
            }
        }
        return evidence.accept(number, numbers, labels)
    }

    private fun fromCard(root: AccessibilityNodeInfo, number: String?, evidence: IncomingTypeEvidence, callSurface: Boolean): Boolean {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        val numbers = mutableSetOf<String>()
        val labels = mutableSetOf<IncomingCallType>()
        nodes.add(root)
        var visited = 0
        var incomingUi = callSurface
        while (nodes.isNotEmpty()) {
            if (++visited > 220) return false
            val node = nodes.removeFirst()
            if (!node.isVisibleToUser) continue
            val id = node.viewIdResourceName.orEmpty().substringAfterLast('/').lowercase()
            if (id.contains("incoming_call") || id.contains("answer") || id.contains("accept_call")) incomingUi = true
            if (!node.isEditable) {
                for (value in listOf(node.text, node.contentDescription)) {
                    val text = value?.takeIf { it.length <= 80 }?.toString() ?: continue
                    CallPolicy.incomingMatchKey(text)?.let(numbers::add)
                    if (node.isClickable && text in answerActions) incomingUi = true
                    if (!node.isClickable && excluded.none(id::contains)) {
                        IncomingCallType.fromLabel(text, requireMark = tagFields.none(id::contains))?.let(labels::add)
                    }
                }
            }
            if (nodes.size + visited + node.childCount > 220) return false
            for (i in 0 until node.childCount) node.getChild(i)?.let(nodes::addLast)
        }
        return incomingUi && evidence.accept(number, numbers, labels)
    }

    private companion object {
        val excluded = listOf("contact_name", "caller_name", "primary_name", "button", "action")
        val answerActions = setOf("接听", "接聽", "Answer")
        val tagFields = listOf("number_mark", "number_tag", "caller_tag", "spam_label", "spam_type", "mark_category")
    }
}
