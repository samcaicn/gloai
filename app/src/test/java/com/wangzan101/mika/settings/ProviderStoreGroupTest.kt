package com.wangzan101.mika.settings

import com.wangzan101.mika.core.agent.GroupPool
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GroupMember
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class ProviderStoreGroupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store(dir: java.io.File = tmp.root) = ProviderStore(dir, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3") { cfg, _, _, _ ->
        FakeModel(cfg.model, cfg.capabilities)
    }

    private fun ProviderStore.connectOne(name: String = "P", vararg models: String): Provider =
        connect(name, ProviderConfig.Kind.OpenAiCompat, "https://${name.lowercase()}.test/v1", "k", models.map { ListedModel(it) })

    private fun caps(tools: Boolean = true, vision: Boolean = false) =
        Capabilities(toolCalls = tools, vision = vision, contextTokens = 64_000, reasoning = false)

    private fun ProviderStore.member(p: Provider, model: String, tools: Boolean = true, vision: Boolean = false): String {
        val id = ensureConfig(p.id, model, caps(tools, vision))!!
        return id
    }

    @Test
    fun groupRoundTripsAndKeepsMembersAlive() {
        val s = store()
        val p = s.connectOne(models = arrayOf("m1", "m2"))
        val a = s.member(p, "m1")
        val b = s.member(p, "m2")
        val g = ModelGroup("g", "主力兜底", members = listOf(GroupMember(a, 0), GroupMember(b, 1)))
        assertTrue(s.saveGroup(g))
        // 没选用途,组成员也不会被 prune 掉
        assertEquals(setOf(a, b), s.configs.value.map { it.id }.toSet())
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Group("g")))
        assertEquals("g", s.roles.value.mainGroup)
        assertNull(s.roles.value.main)

        val reloaded = store()
        assertEquals(listOf(g), reloaded.groups.value)
        assertEquals("g", reloaded.roles.value.resolveGroup(ModelRole.Main))
        val pool = reloaded.modelFor(ModelRole.Main, ModelSession.of("conv")) as GroupPool
        assertEquals(listOf("m1", "m2"), pool.members.map { it.model.name })
        assertEquals("主力兜底", pool.groupName)
    }

    @Test
    fun mainGroupRequiresEveryMemberToCallTools() {
        val s = store()
        val p = s.connectOne(models = arrayOf("ok", "plain"))
        val a = s.member(p, "ok")
        val b = s.member(p, "plain", tools = false)
        val g = ModelGroup("g", "g", members = listOf(GroupMember(a), GroupMember(b)))
        assertTrue(s.saveGroup(g))
        assertFalse(s.choose(ModelRole.Main, RoleChoice.Group("g")))
        // 看图组只要有能看图的成员就能选
        val v = ModelGroup("v", "v", members = listOf(GroupMember(a), GroupMember(s.member(p, "eye", vision = true))))
        assertTrue(s.saveGroup(v))
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.Group("v")))
    }

    @Test
    fun visionGroupNeedsAtLeastOneSeeingMember() {
        val s = store()
        val p = s.connectOne(models = arrayOf("plain"))
        val a = s.member(p, "plain")
        val g = ModelGroup("g", "g", members = listOf(GroupMember(a)))
        assertTrue(s.saveGroup(g))
        assertFalse(s.choose(ModelRole.Vision, RoleChoice.Group("g")))
    }

    @Test
    fun providerRemovalDropsMembersAndFallsBack() {
        val s = store()
        val p1 = s.connectOne("One", "m1")
        val p2 = s.connectOne("Two", "m2")
        val a = s.member(p1, "m1")
        val b = s.member(p2, "m2")
        assertTrue(s.saveGroup(ModelGroup("g", "g", members = listOf(GroupMember(a), GroupMember(b)))))
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Group("g")))
        s.removeProvider(p1.id)
        assertEquals(listOf(b), s.groups.value.single().members.map { it.configId })
        assertEquals("g", s.roles.value.mainGroup)
        s.removeProvider(p2.id)
        assertTrue(s.groups.value.isEmpty())
        assertNull(s.roles.value.mainGroup)
        assertNull(s.modelFor(ModelRole.Main))
    }

    @Test
    fun deletingGroupFallsBackAndPrunesItsMembers() {
        val s = store()
        val p = s.connectOne(models = arrayOf("m1"))
        val a = s.member(p, "m1")
        assertTrue(s.saveGroup(ModelGroup("g", "g", members = listOf(GroupMember(a)))))
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Group("g")))
        s.deleteGroup("g")
        assertTrue(s.groups.value.isEmpty())
        assertNull(s.roles.value.mainGroup)
        assertTrue(s.configs.value.isEmpty())
    }

    @Test
    fun groupUsesIndependentContextSettingsAndEffortPerMember() {
        val s = store()
        val p = s.connectOne(models = arrayOf("m1", "m2"))
        val a = s.member(p, "m1")
        val b = s.member(p, "m2")
        assertTrue(s.setModelContext(a, ModelContextSettings(windowTokens = 1_000_000)))
        assertTrue(s.saveGroup(ModelGroup("g", "g", members = listOf(GroupMember(a), GroupMember(b)))))
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Group("g")))
        val pool = s.modelFor(ModelRole.Main) as GroupPool
        assertEquals(1_000_000, pool.members[0].model.capabilities.contextTokens)
        assertEquals(64_000, pool.members[1].model.capabilities.contextTokens)
    }

    @Test
    fun editingTextOnlyMainGroupWithVisionSameAsMainIsAllowed() {
        val s = store()
        val p = s.connectOne(models = arrayOf("m1", "m2"))
        val a = s.member(p, "m1")
        val b = s.member(p, "m2")
        assertTrue(s.saveGroup(ModelGroup("g", "g", members = listOf(GroupMember(a), GroupMember(b)))))
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Group("g")))
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        // Vision=SameAsMain 会跟随主组,但不该拿「主组没有视觉成员」来拦保存
        assertTrue(s.saveGroup(ModelGroup("g", "主力改名", members = listOf(GroupMember(a), GroupMember(b)))))
        assertEquals("主力改名", s.group("g")!!.name)
        assertEquals("g", s.roles.value.resolveGroup(ModelRole.Main))
    }

    @Test
    fun removingSoleVisionMemberFallsBackVisionRole() {
        val s = store()
        val p1 = s.connectOne("One", "see")
        val p2 = s.connectOne("Two", "text")
        val a = s.member(p1, "see", vision = true)
        val b = s.member(p2, "text")
        assertTrue(s.saveGroup(ModelGroup("v", "v", members = listOf(GroupMember(a), GroupMember(b)))))
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.Group("v")))
        s.removeProvider(p1.id)
        assertEquals(RoleChoice.Off, s.roles.value.vision)
        assertNull(s.modelFor(ModelRole.Vision))
        assertEquals(RoleChoice.Off, store().roles.value.vision)
    }

    @Test
    fun groundingToggleUpdatesEveryVisionGroupMember() {
        val s = store()
        val p = s.connectOne(models = arrayOf("eye", "eye2"))
        val a = s.member(p, "eye", vision = true)
        val b = s.member(p, "eye2", vision = true)
        assertTrue(s.saveGroup(ModelGroup("v", "v", members = listOf(GroupMember(a), GroupMember(b)))))
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.Group("v")))
        s.setVisionGroundingVerified(true)
        assertTrue(s.configs.value.filter { it.id in setOf(a, b) }.all { it.capabilities.groundingVerified })
    }

    private class FakeModel(override val name: String, override val capabilities: Capabilities) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
            ModelReply(emptyList(), StopReason.EndTurn, Usage(3, 2))
    }
}
