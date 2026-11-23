package com.wangzan101.mika.chat.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.RecommendedTaskStore
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 空输入框可直接填推荐任务;已有草稿时,误触只会询问,明确确认后才替换。 */
@RunWith(AndroidJUnit4::class)
class ChatRecommendationTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) {}
        override fun cancel() {}
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) {}
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }

    private fun show() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ConversationStore(File(context.cacheDir, "chat-recommendation-test-" + UUID.randomUUID()))
        val recommendations = RecommendedTaskStore(File(context.cacheDir, "recommendations-" + UUID.randomUUID()))
        recommendations.ensureLoaded { ChatCopy.recommendationSeeds() }
        val vm = ChatViewModel(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions, store,
        )
        compose.setContent {
            ChatScreen(
                vm, Prerequisites.Check(emptyList()), MutableStateFlow<Bitmap?>(null), MutableStateFlow(false),
                reduceMotion = true, onOpenDrawer = {}, openSettings = {}, recommendations = recommendations,
            )
        }
        compose.waitForIdle()
    }

    @Test fun emptyDraftIsPrefilledDirectly() {
        show()
        val recommendation = ChatCopy.examples.first()
        compose.onNodeWithText(recommendation.title).performClick()
        compose.onNodeWithTag("composer-field").assertTextEquals(recommendation.prompt)
        compose.onNodeWithText("替换当前草稿？").assertDoesNotExist()
    }

    @Test fun nonemptyDraftIsReplacedOnlyAfterConfirmation() {
        show()
        val original = "已经写好、还没发送的长草稿"
        val recommendation = ChatCopy.examples.first()
        compose.onNodeWithTag("composer-field").performTextInput(original)

        compose.onNodeWithText(recommendation.title).performClick()
        compose.onNodeWithText("替换当前草稿？").assertExists()
        compose.onNodeWithTag("composer-field").assertTextEquals(original)
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("composer-field").assertTextEquals(original)

        compose.onNodeWithText(recommendation.title).performClick()
        compose.onNodeWithTag("confirm-replace-draft").performClick()
        compose.onNodeWithTag("composer-field").assertTextEquals(recommendation.prompt)
    }
}
