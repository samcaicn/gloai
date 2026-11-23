package com.wangzan101.mika.core.device.access

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppClassifierTest {
    @Test fun ignoresInventedPackagesAndUnknownCategories() {
        val parsed = AppClassifier.parse("""[{"package":"bank","category":"Finance","reason":"银行"},{"package":"invented","category":"Game"},{"package":"other","category":"invalid"}]""", setOf("bank", "other"))
        assertEquals(setOf("bank", "other"), parsed.keys)
        assertEquals(AppCategory.Finance, parsed["bank"]?.category)
        assertEquals(AppCategory.Unknown, parsed["other"]?.category)
    }

    @Test fun malformedReplyDoesNotProducePermissions() {
        assertTrue(AppClassifier.parse("not json", setOf("bank")).isEmpty())
        assertNull(AppAccessState(classifications = mapOf("bank" to AppClassification(AppCategory.Finance))).denial("bank"))
    }

    /** 分类理由用界面语言写:语言和要分类的应用一起交给模型,不是写死在提示词里。 */
    @Test fun reasonLanguageIsSentWithTheApps() = runTest {
        val model = RecordingModel("""[{"package":"bank","category":"Finance","reason":"Banking app"}]""")
        val parsed = AppClassifier(model, language = "English").classify(listOf("bank" to "Bank App"))
        assertEquals(AppCategory.Finance, parsed["bank"]?.category)
        assertTrue("language missing in ${model.user}", model.user.contains("\"language\":\"English\""))
        assertTrue("apps missing in ${model.user}", model.user.contains("\"package\":\"bank\""))
        assertTrue("classification rules missing from system prompt", model.system.contains("Classify Android applications"))
    }

    /** 换语言只改理由的语言,分类结果本身不变。 */
    @Test fun languageOnlyChangesTheReasonLanguage() = runTest {
        val reply = """[{"package":"bank","category":"Finance","reason":"银行"}]"""
        val zh = AppClassifier(RecordingModel(reply), language = "Simplified Chinese").classify(listOf("bank" to "银行"))
        val en = AppClassifier(RecordingModel(reply), language = "English").classify(listOf("bank" to "Bank"))
        assertEquals(zh.mapValues { it.value.category }, en.mapValues { it.value.category })
    }

    private class RecordingModel(private val reply: String) : ChatModel {
        var system: String = ""
        var user: String = ""
        override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            this.system = system
            user = messages.flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString("") { it.text }
            return ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null)
        }
    }
}
