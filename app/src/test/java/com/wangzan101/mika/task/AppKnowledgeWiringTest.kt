package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDraft
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.knowledge.AppKnowledgeFiles
import com.wangzan101.mika.settings.MemorySwitches
import com.wangzan101.mika.memory.ui.SkillsUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppKnowledgeWiringTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()
    private val knowledge = KnowledgeStore(KnowledgeStore.loadBundled())
    private val facts = "- 首页顶部的搜索框进入搜索页,结果分商品和用户两个标签。"
    private val more = "$facts\n- 「我的」页里的「我发布的」列出自己挂出的商品。"
    private val unreviewed = TaskState.Succeeded("已查看", verified = false, reviewed = false)

    private fun runEvents(trial: Boolean = false, steps: Int = 5): List<RunEvent> {
        val out = mutableListOf<RunEvent>(RunEvent.Observed(1, 1, "闲鱼首页", place = Place.app("com.taobao.idlefish")))
        listOf("phone_launch_app", "phone_tap", "phone_tap", "phone_scroll", "phone_tap").take(steps).forEachIndexed { i, name ->
            out += RunEvent.ModelReply((i + 2).toLong(), i + 1, "", name,
                if (i == 0) """{"package":"com.taobao.idlefish"}""" else """{"x":500,"y":500}""", null, null, null)
            out += RunEvent.ToolResult((i + 20).toLong(), i + 1, name, """{"ok":true}""", true, null)
        }
        if (trial) out += RunEvent.State(99, "knowledge_trial_used", "xianyu")
        out += RunEvent.TaskEnded(100, "succeeded", "done")
        return out
    }

    private fun aftermath(events: List<RunEvent>, end: TaskState = unreviewed, outcome: RunOutcome = RunOutcome.Done, origin: RunOrigin = RunOrigin.Ui) =
        RunAftermath("c1", "r1", "查看闲鱼", end, emptyList(), emptyList(), emptyList(), emptySet(), knownTools(), { events },
            origin = origin, knowledge = knowledge, outcome = outcome)

    private var seq = 0
    private fun learning(files: AppKnowledgeFiles, model: TextModel, learn: Boolean = true) = RunLearning(MemorySwitches(learnSkills = learn), null, MapSkills(),
        GuideCatalog.loadBundled(), null, null, { "d${++seq}" }, appDrafter = AppKnowledgeDrafter(model), appDrafts = files)

    @Test fun draftRequiresApprovalThenDoneTrialVerifies() = runBlocking {
        val files = AppKnowledgeFiles(tmp.root)
        val learning = learning(files, TextModel(facts))
        val notes = learning.learn(aftermath(runEvents()))
        assertEquals(AppKnowledgeStage.Draft, files.all().single().stage)
        assertEquals("Mika 记下了几条关于闲鱼的知识，等你确认", notes.skillNotes.single().text)
        assertTrue(files.notes().isEmpty())
        files.approve(files.all().single().id)
        assertTrue(files.notes().getValue("xianyu").trial)
        // 判下来办成、没跑核查:试用转正式
        learning.learn(aftermath(runEvents(trial = true, steps = 1)))
        assertEquals(AppKnowledgeStage.Verified, files.all().single().stage)
    }

    @Test fun failedRunLearnsALessonAndLaterRevisionReplaces() = runBlocking {
        val files = AppKnowledgeFiles(tmp.root)
        val gaveUp = TaskState.Failed("找不到「我发布的」入口", FailKind.GaveUp)
        learning(files, TextModel(facts)).learn(aftermath(runEvents(steps = 3), gaveUp, RunOutcome.Unknown))
        val first = files.all().single()
        assertTrue(first.lesson)
        files.approve(first.id)
        // 再来一次:对在用的那份起草修订
        val notes = learning(files, TextModel(more)).learn(aftermath(runEvents()))
        assertEquals("Mika 修订了闲鱼的知识，等你确认", notes.skillNotes.single().text)
        val ui = SkillsUi.of(MapSkills(), files).appKnowledge.single { it.stage == AppKnowledgeStage.Draft }
        assertTrue(ui.revision)
        assertEquals(listOf("+ - 「我的」页里的「我发布的」列出自己挂出的商品。"), ui.changedLines)
        // 已有一份待确认的:不再请模型
        val idle = TextModel()
        learning(files, idle).learn(aftermath(runEvents()))
        assertTrue(idle.requests.isEmpty())
    }

    @Test fun versionShadowedByARevisionOnTrialIsShownAsReplaced() {
        val files = AppKnowledgeFiles(tmp.root)
        files.putPending(AppKnowledgeDraft("v1", "xianyu", "p", facts, "r", AppKnowledgeStage.Draft, 1))
        files.approve("v1"); files.recordTrial("xianyu", true)
        files.putPending(AppKnowledgeDraft("v2", "xianyu", "p", more, "r", AppKnowledgeStage.Draft, 2, revises = "v1"))
        assertTrue(SkillsUi.of(MapSkills(), files).appKnowledge.none { it.replaced })
        files.approve("v2")
        val rows = SkillsUi.of(MapSkills(), files).appKnowledge.associateBy { it.id }
        assertTrue(rows.getValue("v1").replaced)
        assertFalse(rows.getValue("v2").replaced)
    }

    @Test fun notLearnedWhenOffCancelledOrNotFromChat() = runBlocking {
        for ((a, learn) in listOf(
            aftermath(runEvents()) to false,
            aftermath(runEvents(), TaskState.Cancelled, RunOutcome.Unknown) to true,
            aftermath(runEvents(), origin = RunOrigin.Trigger) to true,
        )) {
            val files = AppKnowledgeFiles(tmp.newFolder())
            val model = TextModel(facts)
            learning(files, model, learn).learn(a)
            assertTrue(files.all().isEmpty() && model.requests.isEmpty())
        }
    }
}
