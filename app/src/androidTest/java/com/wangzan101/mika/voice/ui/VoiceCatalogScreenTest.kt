package com.wangzan101.mika.voice.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.voice.model.VoiceAccountResolver
import com.wangzan101.mika.voice.model.VoiceAdapterRegistry
import com.wangzan101.mika.voice.model.VoiceCatalogBackend
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceCredentialStore
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import com.wangzan101.mika.settings.ui.models.edit

/** 语音模型目录页:系统模型和能力徽章可见,添加/编辑/删除自己的模型可用。 */
@RunWith(AndroidJUnit4::class)
class VoiceCatalogScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private fun show(): VoiceModelEnvironment {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = VoiceCatalogStore(
            object : VoiceCatalogBackend {
                override fun read(): String? = null
                override fun write(value: String): Boolean = true
            },
            migrated = true,
        )
        val credentials = VoiceCredentialStore.create(context)
        val environment = VoiceModelEnvironment(
            catalog = catalog,
            accountResolver = VoiceAccountResolver(ProviderStore.get(context), credentials),
            registry = VoiceAdapterRegistry(),
            credentials = credentials,
            credentialStore = credentials,
        )
        compose.setContent { VoiceCatalogScreen(environment, onBack = {}) }
        return environment
    }

    @Test fun catalogListsSystemModelWithCapabilityBadge() {
        show()
        compose.onNodeWithTag("voice-model-system-stt").assertExists()
        compose.onNodeWithTag("voice-cap-stt-system-stt").assertExists()
    }

    @Test fun addEditAndDeleteSelfHostedModel() {
        show()
        val modelId = "test-" + UUID.randomUUID().toString().substring(0, 8)

        // Add
        compose.onNodeWithTag("voice-catalog-add").performClick()
        compose.onNodeWithTag("voice-catalog-service-name").performTextInput("本地服务")
        compose.onNodeWithTag("voice-catalog-model-id").performTextInput(modelId)
        compose.onNodeWithTag("voice-catalog-display-name").performTextInput("测试模型")
        compose.onNodeWithTag("voice-catalog-cap-stt").performClick()
        compose.onNodeWithTag("voice-catalog-save-unverified").performClick()
        compose.onNodeWithTag("voice-model-$modelId").assertExists()
        compose.onNodeWithTag("voice-cap-stt-$modelId").assertExists()

        // Edit
        compose.onNodeWithTag("voice-model-edit-$modelId").performClick()
        compose.onNodeWithTag("voice-catalog-display-name").performTextClearance()
        compose.onNodeWithTag("voice-catalog-display-name").performTextInput("测试模型X")
        compose.onNodeWithTag("voice-catalog-save-unverified").performClick()
        compose.onNodeWithText("测试模型X").assertExists()

        // Delete
        compose.onNodeWithTag("voice-model-delete-$modelId").performClick()
        compose.onNodeWithTag("voice-catalog-delete-confirm").performClick()
        compose.onNodeWithTag("voice-model-$modelId").assertDoesNotExist()
    }
}
