package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.GroupMember
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.RoleChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型页的文字:用途卡、服务商一行、删除提示,以及列表排序。 */
class ModelsCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
    private val ds = Provider("p1", "DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com",
        listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-reasoner"), ListedModel("deepseek-coder")))
    private val qw = Provider("p2", "通义千问", ProviderConfig.Kind.OpenAiCompat, "https://dashscope.aliyuncs.com/compatible-mode/v1",
        listOf(ListedModel("qwen-vl", vision = true)))
    private val main = ProviderConfig("c1", ds.kind, ds.baseUrl, "deepseek-reasoner", caps, ds.name, ds.id)
    private val vl = ProviderConfig("c2", qw.kind, qw.baseUrl, "qwen-vl", caps, qw.name, qw.id)

    private fun ui(roles: RoleChoices, providers: List<Provider> = listOf(ds, qw), configs: List<ProviderConfig> = listOf(main, vl),
        groups: List<ModelGroup> = emptyList()) =
        ModelSetupUi(AddForm.of(ProviderPresets.all.first()), providers = providers, configs = configs, roles = roles, groups = groups)

    @Test fun inUseModelsComeFirstAndPickedIgnoresSameAsMain() {
        val u = ui(RoleChoices(main = "c1", vision = RoleChoice.SameAsMain))
        assertEquals(listOf("deepseek-reasoner", "deepseek-chat", "deepseek-coder"), u.sortedOptions(ds).map { it.id })
        assertEquals(listOf(ModelRole.Main, ModelRole.Vision, ModelRole.Light), u.rolesOf(ds.id, "deepseek-reasoner"))
        assertTrue(u.picked(ModelRole.Main, ds.id, "deepseek-reasoner"))
        assertFalse("用主模型不算明确选了它", u.picked(ModelRole.Vision, ds.id, "deepseek-reasoner"))
    }

    @Test fun pickerFixedChoicesPerRole() {
        assertEquals(emptyList<RoleChoice>(), fixedChoices(ModelRole.Main))
        assertEquals(listOf(RoleChoice.SameAsMain, RoleChoice.Off), fixedChoices(ModelRole.Vision))
        assertEquals(listOf(RoleChoice.SameAsMain), fixedChoices(ModelRole.Light))
    }

    @Test fun groupRoleLineShowsMemberCount() {
        val g = ModelGroup("g", "主力组", members = listOf(GroupMember("c1"), GroupMember("c2")))
        val u = ui(RoleChoices(mainGroup = "g"), groups = listOf(g))
        val line = ModelsCopy.roleLine(u, ModelRole.Main)
        assertEquals("主力组", line.title)
        assertEquals("2 个模型", line.subtitle)
    }
}
