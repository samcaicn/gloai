package com.wangzan101.mika.calls

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingCallNotificationTest {
    @Test fun headsUpCallRequiresPhonePublisherAndMatchingNumber() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        assumeNotNull(dialer)
        val reader = IncomingCallLabels(context)
        val service = object : AccessibilityService() {
            override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit
            override fun onInterrupt() = Unit
        }
        val number = "13800138000"
        val evidence = IncomingTypeEvidence()
        fun read(pkg: String, title: String, body: String, category: String): Boolean {
            val event = AccessibilityEvent(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED).apply {
                packageName = pkg
                parcelableData = Notification.Builder(context, "phone-calls").setCategory(category)
                    .setContentTitle(title).setContentText(body).build()
            }
            return reader.read(service, event, number, evidence)
        }
        assertFalse(read(context.packageName, number, "快递", Notification.CATEGORY_CALL))
        assertFalse(read(dialer!!, number, "快递", Notification.CATEGORY_MISSED_CALL))
        assertFalse(read(dialer, "13800138001", "快递", Notification.CATEGORY_CALL))
        assertFalse(read(dialer, "快递", number, Notification.CATEGORY_CALL))
        assertNull(evidence.typeFor(number))
        assertTrue(read(dialer, "+86$number", "被12人标记为快递", Notification.CATEGORY_CALL))
        val config = CallConfig(enabled = true, autoAnswer = true, credentialsConfigured = true, audioVerified = true,
            incomingScope = IncomingScope.TYPES, allowedCallTypes = setOf(IncomingCallType.PARCEL))
        assertTrue(CallPolicy.canStartAutoAnswer(config, config, number, 300, type = evidence.typeFor(number)))
        assertTrue(read(dialer, number, "广告推销", Notification.CATEGORY_CALL))
        assertFalse(CallPolicy.callerAllowed(config, number, type = evidence.typeFor(number)))
    }
}
