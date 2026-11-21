package com.wangzan101.mika.settings

import com.wangzan101.mika.core.agent.GroupPool
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GroupMember
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/**
 * 模型组的全链路验收（不依赖设备）:ProviderStore 建两个服务商和组 → 主成员鉴权失败 → 第二成员接管 →
 * 用量按成员记 → 坏成员进入冷却 → 同一对话继续用接管后的成员。
 * 真机上的同样流程仍要跑一次（见 MODEL-GROUP-PLAN.md §18）。
 */
class ModelGroupAcceptanceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val seen = mutableListOf<Pair<UsageSource, Usage?>>()
    private val calls = mutableMapOf<String, Int>()

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, usage = { src, u -> seen += src to u }, userAgent = "Mika/test") { cfg, _, _, _ ->
        ScriptedModel(cfg.model, cfg.capabilities) {
            val n = (calls[cfg.model] ?: 0) + 1
            calls[cfg.model] = n
            if (cfg.model == "m1") throw ModelError.Auth()
            ModelReply(listOf(Part.Text("from $n")), StopReason.EndTurn, Usage(10, 5))
        }
    }

    private fun caps() = Capabilities(toolCalls = true, vision = false, contextTokens = 100_000, reasoning = false)

    private fun ProviderStore.twoProviderGroup(): Pair<Provider, Provider> {
        val a = connect("A", ProviderConfig.Kind.OpenAiCompat, "https://a.test/v1", "ka", listOf(ListedModel("m1")))
        val b = connect("B", ProviderConfig.Kind.OpenAiCompat, "https://b.test/v1", "kb", listOf(ListedModel("m2")))
        val a1 = ensureConfig(a.id, "m1", caps())!!
        val b1 = ensureConfig(b.id, "m2", caps())!!
        assertTrue(saveGroup(ModelGroup("g", "主力兜底", members = listOf(GroupMember(a1, 0), GroupMember(b1, 1)))))
        assertTrue(choose(ModelRole.Main, RoleChoice.Group("g")))
        return a to b
    }

    @Test
    fun failedPrimaryFailsOverThenSticksAndCoolsDown() = runTest {
        val s = store()
        val (a, b) = s.twoProviderGroup()

        val pool = s.modelFor(ModelRole.Main, ModelSession.of("conv")) as GroupPool
        val first = pool.complete("s", listOf(userMessage("hi")), emptyList())
        assertEquals(listOf(Part.Text("from 1")), first.parts)

        // m1 抛鉴权错误也被记了一笔(只有次数),m2 正常记 token
        assertEquals(
            listOf(
                UsageSource(a.id, "A", "m1", UsagePurpose.Main) to null,
                UsageSource(b.id, "B", "m2", UsagePurpose.Main) to Usage(10, 5),
            ),
            seen,
        )
        assertEquals(1, calls["m1"])
        assertEquals(1, calls["m2"])

        // 同一个池再来一次:m1 在冷却,直接走 m2;粘性也会把 m2 放在最前
        val second = pool.complete("s", listOf(userMessage("again")), emptyList())
        assertEquals(listOf(Part.Text("from 2")), second.parts)
        assertEquals(1, calls["m1"])
        assertEquals(2, calls["m2"])

        // 新任务(新池)同一对话也跳过冷却中的 m1
        val fresh = s.modelFor(ModelRole.Main, ModelSession.of("conv")) as GroupPool
        fresh.complete("s", listOf(userMessage("third")), emptyList())
        assertEquals(1, calls["m1"])
        assertEquals(3, calls["m2"])
        assertEquals("B · m2", fresh.ordered(com.wangzan101.mika.core.model.ModelNeed()).first().label)
    }

    private class ScriptedModel(
        override val name: String,
        override val capabilities: Capabilities,
        private val reply: () -> ModelReply,
    ) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = reply()
    }
}

/** 测试里的用户消息短写法;Author 必须写明,这里统一用 User。 */
private fun userMessage(text: String): Message =
    Message(com.wangzan101.mika.core.model.Role.User, listOf(Part.Text(text)), com.wangzan101.mika.core.model.Author.User)
