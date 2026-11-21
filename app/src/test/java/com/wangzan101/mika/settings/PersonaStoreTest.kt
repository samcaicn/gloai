package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.wangzan101.mika.core.prompt.PersonaStyle
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonaStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(dir: File = tmp.root) = PersonaStore(dir)

    @Test fun addPersistsAcrossInstances() {
        val dir = tmp.newFolder("p")
        val a = store(dir)
        val p = a.add("写作助手", "先列提纲", "先给结论，再展开。").getOrThrow()
        assertEquals(listOf(p), a.personas.value)
        assertEquals(listOf(p), store(dir).personas.value)
        assertTrue(File(dir, "personas.json").readText().contains("\"version\": 1"))
    }

    @Test fun updateBumpsRevisionAndKeepsCreatedAt() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val p = s.add("P", "", "a").getOrThrow()
        val next = s.update(p.copy(name = "Q", prompt = "b")).getOrThrow()
        assertEquals(p.createdAt, next.createdAt)
        assertEquals(p.revision + 1, next.revision)
        assertEquals("Q", store(dir).get(p.id)?.name)
    }

    @Test fun duplicateGetsNewIdAndRevision() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val p = s.add("P", "", "a").getOrThrow()
        val copy = s.duplicate(p.id).getOrThrow()
        assertNotEquals(p.id, copy.id)
        assertEquals(1, copy.revision)
        assertFalse(copy.builtin)
        assertEquals(2, store(dir).personas.value.size)
    }

    /** 选项跟着人格存下来,复制、改名都带着;只有选项、没写指令的人格也能存。 */
    @Test fun styleIsStoredAndCarriedByUpdateAndDuplicate() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val style = PersonaStyle(base = PersonaStyle.Base.Crisp, emoji = PersonaStyle.Level.Less)
        val p = s.add("干练", "", "", style).getOrThrow()
        assertEquals(style, store(dir).get(p.id)?.style)
        assertTrue(File(dir, "personas.json").readText().contains("\"base\": \"crisp\""))
        val renamed = s.update(p.copy(name = "更干练")).getOrThrow()
        assertEquals(style, renamed.style)
        val changed = s.update(renamed.copy(style = style.copy(length = PersonaStyle.Level.Less))).getOrThrow()
        assertEquals(PersonaStyle.Level.Less, store(dir).get(p.id)?.style?.length)
        assertEquals(changed.style, s.duplicate(p.id).getOrThrow().style)
    }

    /** 加选项之前存的文件没有 style:照常读出来,选项全是默认。 */
    @Test fun filesWrittenBeforeOptionsStillLoad() {
        val dir = tmp.newFolder("p")
        File(dir, "personas.json").writeText(
            """{"version":1,"personas":[{"id":"old-1","name":"写作助手","description":"","prompt":"先给结论。","revision":3,"createdAt":1,"updatedAt":2,"builtin":false}]}""",
        )
        val p = store(dir).get("old-1")!!
        assertEquals("先给结论。", p.prompt)
        assertEquals(3, p.revision)
        assertTrue(p.style.isDefault)
    }

    /** 不认识的选项取值(以后的版本、手改的文件)按默认读;文件不被改名成 .bad,别的人格也还在。 */
    @Test fun unknownOptionValuesLoadAsDefaultInsteadOfInvalidatingTheFile() {
        val dir = tmp.newFolder("p")
        File(dir, "personas.json").writeText(
            """{"version":1,"personas":[
              {"id":"a","name":"A","prompt":"x","style":{"base":"sarcastic","emoji":"more","length":"huge","future":true}},
              {"id":"b","name":"B","prompt":"y","style":{"base":"warm"}}]}""",
        )
        val s = store(dir)
        assertEquals(PersonaStyle(emoji = PersonaStyle.Level.More), s.get("a")?.style)
        assertEquals(PersonaStyle(base = PersonaStyle.Base.Warm), s.get("b")?.style)
        assertEquals(listOf("personas.json"), dir.list()!!.toList())
    }

    /** 设置里选的在用的那一套:记得住,只能指向列表里有的,删掉它就回到没有在用的。 */
    @Test fun activePersonaPersistsAndClearsWhenDeleted() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        assertNull(s.activeId.value)
        assertNull(s.active())
        val a = s.add("A", "", "a").getOrThrow()
        val b = s.add("B", "", "b").getOrThrow()
        assertNull("adding does not select", s.activeId.value)
        assertFalse(s.setActive("missing"))
        assertTrue(s.setActive(a.id))
        assertEquals(a, s.active())
        assertEquals(a.id, store(dir).activeId.value)
        // 改内容不动在用的;在用的拿到的是改完的那份
        val renamed = s.update(a.copy(name = "A2")).getOrThrow()
        assertEquals(renamed, store(dir).active())
        // 删别的不影响;删在用的,同一次写盘里清掉
        assertTrue(s.delete(b.id))
        assertEquals(a.id, s.activeId.value)
        assertTrue(s.delete(a.id))
        assertNull(s.activeId.value)
        assertNull(store(dir).activeId.value)
        assertTrue(s.setActive(null))
    }

    /** 旧文件没有 active;手改的文件里 active 指向不存在的人格:都当没有在用的,文件照常读。 */
    @Test fun missingOrDanglingActiveMeansNothingInUse() {
        val old = tmp.newFolder("old")
        File(old, "personas.json").writeText("""{"version":1,"personas":[{"id":"a","name":"A","prompt":"x"}]}""")
        assertNull(store(old).activeId.value)
        assertEquals("A", store(old).get("a")?.name)
        val dangling = tmp.newFolder("dangling")
        File(dangling, "personas.json").writeText("""{"version":1,"active":"gone","personas":[{"id":"a","name":"A","prompt":"x"}]}""")
        assertNull(store(dangling).activeId.value)
        assertEquals(listOf("personas.json"), dangling.list()!!.toList())
    }

    /** 人格页直接改在用的那一套:还没有时第一次改动才建一套并设为在用;改完等于默认的不建。 */
    @Test fun editActiveCreatesOnTheFirstRealChangeThenUpdatesInPlace() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        // 点了本来就选着的「默认」:什么都不留
        s.editActive("我的风格") { it.copy(style = it.style.copy(base = PersonaStyle.Base.Default)) }.getOrThrow()
        assertTrue(s.personas.value.isEmpty())
        assertNull(s.activeId.value)
        assertFalse(File(dir, "personas.json").exists())

        val first = s.editActive("我的风格") { it.copy(style = it.style.copy(base = PersonaStyle.Base.Crisp)) }.getOrThrow()
        assertEquals("我的风格", first.name)
        assertEquals(first.id, store(dir).activeId.value)
        assertEquals(1, first.revision)

        val second = s.editActive("不会用到") { it.copy(style = it.style.copy(emoji = PersonaStyle.Level.Less)) }.getOrThrow()
        assertEquals(first.id, second.id)
        assertEquals("我的风格", second.name)
        assertEquals(2, second.revision)
        assertEquals(PersonaStyle(base = PersonaStyle.Base.Crisp, emoji = PersonaStyle.Level.Less), store(dir).active()?.style)
        assertEquals(1, s.personas.value.size)

        // 再点一次已经选着的档位:不写、不涨修订号
        val same = s.editActive("不会用到") { it.copy(style = it.style.copy(emoji = PersonaStyle.Level.Less)) }.getOrThrow()
        assertEquals(2, same.revision)
        assertEquals(second.updatedAt, same.updatedAt)
    }

    /** 存着几套、但没有在用的(从按对话绑定的旧版升级上来就是这样):第一次改动新建一套并选用,原有的不动。 */
    @Test fun editActiveWithSavedPersonasButNoneInUseAddsANewOneAndLeavesTheRestAlone() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val old = s.add("Crisp", "", "", PersonaStyle(base = PersonaStyle.Base.Crisp)).getOrThrow()
        assertNull(s.activeId.value)
        val created = s.editActive("我的风格") { it.copy(style = it.style.copy(base = PersonaStyle.Base.Playful)) }.getOrThrow()
        assertNotEquals(old.id, created.id)
        assertEquals("我的风格", created.name)
        assertEquals(created.id, store(dir).activeId.value)
        assertEquals(old, store(dir).get(old.id))
        assertEquals(2, s.personas.value.size)
    }

    /** 称呼跟着人格存;只改称呼也算真的改动(会建一套并在用);带符号、太长的称呼存不进去。 */
    @Test fun nicknameIsStoredAndValidated() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val p = s.editActive("我的风格") { it.copy(nickname = "Kaguya") }.getOrThrow()
        assertEquals("Kaguya", store(dir).active()?.nickname)
        assertEquals("Kaguya", p.assistant)
        assertFalse(p.changesNothing)
        assertTrue(s.editActive("x") { it.copy(nickname = "Kaguya!") }.isFailure)
        assertTrue(s.editActive("x") { it.copy(nickname = "字".repeat(17)) }.isFailure)
        assertEquals("Kaguya", s.active()?.nickname)
        // 清空就是默认的 Mika;这一套别的也都是默认,就什么都不改了
        val cleared = s.editActive("x") { it.copy(nickname = "") }.getOrThrow()
        assertEquals("Mika", cleared.assistant)
        assertTrue(cleared.changesNothing)
    }

    /** 称呼填的就是 Mika、别的全默认:等于没改,不建。旧文件没有 nickname:按 Mika。 */
    @Test fun defaultNicknameChangesNothing() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        s.editActive("我的风格") { it.copy(nickname = " Mika ") }.getOrThrow()
        assertTrue(s.personas.value.isEmpty())
        File(dir, "personas.json").writeText("""{"version":1,"personas":[{"id":"a","name":"A","prompt":"x"}]}""")
        assertEquals("Mika", store(dir).get("a")?.assistant)
    }

    /** 新建的一套直接在用;改名只动名称;复制的名称由界面给、不是在用的。 */
    @Test fun createRenameAndDuplicate() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val a = s.create(" 工作 ").getOrThrow()
        assertEquals("工作", a.name)
        assertEquals(a.id, s.activeId.value)
        assertTrue(a.style.isDefault)
        val renamed = s.rename(a.id, "上班").getOrThrow()
        assertEquals("上班", store(dir).get(a.id)?.name)
        assertEquals(a.id, s.activeId.value)
        assertTrue(s.rename(a.id, "  ").isFailure)
        assertTrue(s.rename(a.id, "x".repeat(41)).isFailure)
        assertEquals("上班", s.get(a.id)?.name)
        val copy = s.duplicate(a.id) { "$it 副本" }.getOrThrow()
        assertEquals("上班 副本", copy.name)
        assertEquals(renamed.id, s.activeId.value)
        val long = s.create("y".repeat(40)).getOrThrow()
        assertEquals(40, s.duplicate(long.id) { "$it 副本" }.getOrThrow().name.length)
    }

    /** 最多 20 套:到了上限,新建、复制、第一次改动都不再加。 */
    @Test fun thereIsAnUpperLimitOnHowManyAreKept() {
        val s = store(tmp.newFolder("p"))
        repeat(PersonaStore.MAX_PERSONAS) { assertTrue(s.add("P$it", "", "x").isSuccess) }
        assertTrue(s.add("more", "", "x").isFailure)
        assertTrue(s.create("more").isFailure)
        assertTrue(s.duplicate(s.personas.value.first().id).isFailure)
        assertTrue(s.editActive("我的风格") { it.copy(prompt = "短一点") }.isFailure)
        assertEquals(PersonaStore.MAX_PERSONAS, s.personas.value.size)
    }

    @Test fun deleteRemovesOnlyThatPersona() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val a = s.add("A", "", "a").getOrThrow()
        val b = s.add("B", "", "b").getOrThrow()
        assertTrue(s.delete(a.id))
        assertNull(s.get(a.id))
        assertEquals(listOf(b), store(dir).personas.value)
        assertFalse(s.delete("missing"))
    }

    @Test fun invalidInputIsRejected() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        assertTrue(s.add("", "", "a").isFailure)
        assertTrue(s.add("x".repeat(PersonaPromptLimit.NAME + 1), "", "a").isFailure)
        assertTrue(s.add("P", "x".repeat(PersonaStore.MAX_DESCRIPTION + 1), "a").isFailure)
        assertTrue(s.add("P", "", "x".repeat(PersonaPromptLimit.CHARS + 1)).isFailure)
        assertTrue(s.personas.value.isEmpty())
    }

    @Test fun corruptFileIsSetAside() {
        val dir = tmp.newFolder("p")
        File(dir, "personas.json").writeText("{not json")
        val s = store(dir)
        assertTrue(s.personas.value.isEmpty())
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("personas.json.bad-") })
    }

    @Test fun emptyPromptIsAllowed() {
        val dir = tmp.newFolder("p")
        val p = store(dir).add("P", "", "").getOrThrow()
        assertEquals("", p.prompt)
    }

    @Test fun idsAreValidated() {
        assertTrue(PersonaStore.isValidId("abc-123_X"))
        assertFalse(PersonaStore.isValidId("../escape"))
        assertFalse(PersonaStore.isValidId(""))
        assertFalse(PersonaStore.isValidId(null))
    }

    /** 测试里用到的上限,和 PersonaPrompt 的常量对上;改名时只改这里一处。 */
    private object PersonaPromptLimit {
        const val NAME = com.wangzan101.mika.core.prompt.PersonaPrompt.MAX_NAME
        const val CHARS = com.wangzan101.mika.core.prompt.PersonaPrompt.MAX_PROMPT_CHARS
    }
}
