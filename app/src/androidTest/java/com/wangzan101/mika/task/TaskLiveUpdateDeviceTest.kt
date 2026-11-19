package com.wangzan101.mika.task

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36)
class TaskLiveUpdateDeviceTest {
    @get:Rule val foreground = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun sameNotificationFollowsTheTaskAndDisappearsWhenItEnds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nm = context.getSystemService(NotificationManager::class.java)
        val thread = UUID.randomUUID().toString()
        val goal = "先在铁路12306查余票，再去携程看酒店，最后去小红书找攻略，先问我哪天出发。"
        val model = ScriptedModel().apply { intent = "ACT" }
        val target = TaskService.openApp(context, thread)
        // Optional time for an observer to inspect the OEM status bar outside Mika.
        val observeMs = InstrumentationRegistry.getArguments().getString("observeChipMs")?.toLongOrNull()?.coerceIn(0, 30_000) ?: 0
        fun progress() = nm.activeNotifications.firstOrNull {
            it.notification.channelId == TaskService.CHANNEL_PROGRESS && it.notification.contentIntent == target
        }
        fun awaitLabel(text: String) = requireNotNull(pollUntil(15_000) {
            progress()?.takeIf { it.notification.shortCriticalText == text }
        }) { "Missing task notification with label " + text }.also {
            assertTrue(it.notification.hasPromotableCharacteristics())
            assertTrue(it.notification.extras.getBoolean("android.requestPromotedOngoing"))
            Log.i("MikaLiveUpdateTest", "label=${it.notification.shortCriticalText} eligible=${it.notification.hasPromotableCharacteristics()} allowed=${nm.canPostPromotedNotifications()} promoted=${it.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0}")
            if (observeMs > 0) SystemClock.sleep(observeMs)
        }
        val env = testTasks
        val primary = env.modelFactory
        val light = env.lightModelFactory
        val vision = env.visionModelFactory
        check(!DeviceTaskState.busy.value)
        try {
            env.modelFactory = { _, _ -> model }
            env.lightModelFactory = { _, _ -> null }
            env.visionModelFactory = { _, _ -> null }
            TaskService.start(context, goal, thread)
            model.awaitCall()
            val running = awaitLabel("1/3 查票")
            // wait is local and side-effect-free; the progress block is the same one used by a real task.
            model.feed("wait", """{"seconds":0.3}""", """<task_progress>{"metric":{"name":"private items","done":2,"total":5}}</task_progress>""")
            model.awaitCall()
            assertEquals(running.key, awaitLabel("1/3 查票").key)
            model.feed("ask_user", """{"prompt":"你想哪天出发？"}""")
            assertEquals(running.key, awaitLabel(context.getString(R.string.chat_wait_answer)).key)
            val id = DeviceTaskState.runs.value.single { it.conversationId == thread }.key
            TaskService.answer(context, "周五", id)
            model.awaitCall()
            // After ask_user the agent can request one follow-up before accepting the final answer.
            repeat(2) { model.answer("周五。") }
            val cleared = pollUntil(15_000) { true.takeIf { !DeviceTaskState.busy.value && progress() == null } }
            assertNotNull("state=${DeviceTaskState.state.value.javaClass.simpleName}, busy=${DeviceTaskState.busy.value}, remaining=${progress()?.id}", cleared)
            assertEquals("周五。", (DeviceTaskState.state.value as TaskState.Succeeded).answer)
        } finally {
            DeviceTaskState.runs.value.firstOrNull { it.conversationId == thread }?.let {
                TaskService.cancel(context, it.key)
                pollUntil(10_000) { true.takeIf { !DeviceTaskState.busy.value } }
            }
            env.modelFactory = primary
            env.lightModelFactory = light
            env.visionModelFactory = vision
            ConversationStore.shared(context).delete(thread)
            nm.activeNotifications.filter { it.notification.contentIntent == target }.forEach { nm.cancel(it.tag, it.id) }
        }
    }
}
