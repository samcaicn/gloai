package com.wangzan101.mika.chat

import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.record.RunMetricsStore
import com.wangzan101.mika.core.usage.ConversationUsage
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.chat.GeneratedAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ConversationStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun failedFileDeletionKeepsHistoryAndAllowsRetry() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        store.append("a", Turn("问题", "结果", "succeeded"))
        store.setCurrent(CurrentChat.Open("a"))
        val blocker = File(dir, "a.json.tmp")
        assertTrue(blocker.mkdir())
        val child = File(blocker, "keep")
        child.writeText("data")

        assertThrows(IOException::class.java) { store.delete("a") }
        assertEquals(CurrentChat.Open("a"), store.currentChat())
        assertEquals(listOf("a"), ConversationStore(dir).list().map { it.id })
        assertTrue(store.rename("a", "保留的对话"))
        assertTrue(child.isFile)

        assertTrue(child.delete())
        assertTrue(blocker.delete())
        store.delete("a")
        assertTrue(ConversationStore(dir).list().isEmpty())
        assertNull(store.currentChat())
    }

    @Test fun usageSurvivesHistoryPruningAndNeverChargesAnotherChat() {
        val dir = tmp.newFolder("chats")
        val store = ConversationStore(dir)
        val history = RunMetricsStore(File(dir.parentFile, RunMetricsStore.FILE_NAME), keep = 1)
        store.append("a", Turn("one", "ok", "succeeded", runId = "r1"))
        history.append(RunMetrics(runId = "r1", allInputTokens = 80, allOutputTokens = 4))
        val live = store.meter("a")
        live.record(Usage(100, 10, 40), 1)
        history.append(RunMetrics(runId = "r2"))
        store.append("a", Turn("two", "ok", "succeeded", runId = "r2"))
        live.record(Usage(5, 1), 1)
        val saved = ConversationStore(dir).usageSnapshot("a")
        assertEquals(185L, saved.input)
        assertEquals(15L, saved.output)
        assertTrue(saved.partial)
        assertEquals(0.4, saved.cacheRate!!, 0.00001)
        assertTrue(store.branch("a", "b", 0, origin = BranchOrigin("a", 0, true, 1)))
        assertEquals(ConversationUsage(), store.usageSnapshot("b"))
        val branch = store.meter("b")
        branch.record(Usage(20, 3, cacheReported = true), 1)
        assertEquals(0.0, store.usageSnapshot("b").cacheRate!!, 0.0)
        assertEquals(saved, store.usageSnapshot("a"))
        store.delete("a")
        live.record(Usage(2, 1), 1)
        assertEquals(ConversationUsage(), store.usageSnapshot("a"))
        assertFalse(File(dir, "a.json.usage").exists())
    }

    @Test fun overlappingAuxiliaryCallsAccumulateOnce() {
        val store = ConversationStore(tmp.newFolder())
        val meter = store.meter("chat")
        val jobs = List(4) { Thread { repeat(10) { meter.record(Usage(6, 1), 1) } } }
        jobs.forEach(Thread::start)
        jobs.forEach(Thread::join)
        assertEquals(ConversationUsage(input = 240, output = 40), store.usageSnapshot("chat"))
    }

    @Test fun persistsAcrossInstances() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "晴", "succeeded"))
        a.append(id, Turn("订票", "验证码\n\"过不去\"", "failed"))
        val b = ConversationStore(dir)
        assertEquals(listOf(Turn("查天气", "晴", "succeeded"), Turn("订票", "验证码\n\"过不去\"", "failed")), b.turns(id).map { it.copy(atMs = null) })
        assertEquals(emptyList<Turn>(), b.turns(b.newConversation()))
        assertNotEquals(id, b.newConversation())
    }

    @Test fun appendStampsNewTurnsAndPreservesExplicitTimes() {
        val dir = tmp.newFolder("times")
        var now = 1_000L
        val store = ConversationStore(dir, clock = { now })
        store.append("c", Turn("一", "甲", "succeeded"))
        now = 2_000L
        store.append("c", Turn("二", "乙", "succeeded", atMs = 42L))
        now = 3_000L
        store.append("c", Turn("三", "丙", "succeeded"))
        assertEquals(listOf(1_000L, 42L, 3_000L), ConversationStore(dir).turns("c").map { it.atMs })
        val saved = Json.parseToJsonElement(java.io.File(dir, "c.json").readText()) as JsonArray
        assertEquals(listOf("1000", "42", "3000"), saved.map { (it as JsonObject).getValue("atMs").toString() })
    }

    @Test fun oldTurnKeepsUnknownTimeWhenANewTurnIsAppended() {
        val dir = tmp.newFolder("old")
        java.io.File(dir, "c.json").writeText("""[{"userText":"旧","outcome":"好","status":"succeeded"}]""")
        val store = ConversationStore(dir, clock = { 99L })
        assertNull(store.turns("c").single().atMs)
        store.append("c", Turn("新", "也好", "succeeded"))
        val read = ConversationStore(dir).turns("c")
        assertEquals(listOf(null, 99L), read.map { it.atMs })
        val saved = Json.parseToJsonElement(java.io.File(dir, "c.json").readText()) as JsonArray
        assertFalse("atMs" in (saved[0] as JsonObject))
        assertEquals("99", (saved[1] as JsonObject).getValue("atMs").toString())
    }

    @Serializable
    private data class LegacyTurn(val userText: String, val outcome: String, val status: String)

    @Test fun oldReaderIgnoresTheNewOptionalTimeField() {
        val dir = tmp.newFolder("compat")
        ConversationStore(dir).append("c", Turn("问题", "答案", "succeeded", atMs = 123L))
        val raw = java.io.File(dir, "c.json").readText()
        val old = Json { ignoreUnknownKeys = true }.decodeFromString<List<LegacyTurn>>(raw)
        assertEquals(listOf(LegacyTurn("问题", "答案", "succeeded")), old)
    }

    /** 留在后台运行的那一页(站点)和它后来关掉的原因跟着那一轮存下;没留页的轮次不写这两个字段。 */
    @Test fun persistsKeptPage() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("播放课程,挂着别关", "开始播放了", "succeeded", runId = "r1", keptPage = "course.example.com"))
        a.append(id, Turn("谢谢", "不客气", "succeeded", runId = "r2"))
        assertEquals(listOf("course.example.com", null), ConversationStore(dir).turns(id).map { it.keptPage })
        a.updateTurn(id, "r1") { it.copy(keptPageClosed = "expired") }
        assertEquals("expired", ConversationStore(dir).turns(id).first().keptPageClosed)
        assertFalse("keptPage" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** 这一轮用过的能力组跟着存下(下一轮开局据此预先展开);没用过的不写这个字段。 */
    @Test fun persistsUsedGroups() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("打开设置", "好了", "succeeded", groups = listOf("phone", "web")))
        a.append(id, Turn("谢谢", "不客气", "succeeded"))
        assertEquals(listOf(listOf("phone", "web"), emptyList()), ConversationStore(dir).turns(id).map { it.groups })
        assertFalse("groups" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** 插话打断的那段回复和插话一一对应存下;没打断过的轮次不写这个字段,旧文件读出来是空的。 */
    @Test fun persistsInterruptedReplies() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), interruptedReplies = listOf("今天晴", "")))
        a.append(id, Turn("谢谢", "不客气", "succeeded", steers = listOf("好")))
        val read = ConversationStore(dir).turns(id)
        assertEquals(listOf(listOf("今天晴", ""), emptyList()), read.map { it.interruptedReplies })
        assertEquals(listOf("改成查明天", "顺便看看后天"), read.first().steers)
        assertFalse("interruptedReplies" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** 取消占位结果的来源跟着存下;后续摘要清掉来源,旧文件和普通轮次读出来为空。 */
    @Test fun persistsOutcomeSystemType() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查资料", "已取消", "cancelled", runId = "r1", outcomeSystemType = "task_cancelled"))
        a.append(id, Turn("谢谢", "已取消", "succeeded", runId = "r2"))
        assertEquals(listOf("task_cancelled", null), ConversationStore(dir).turns(id).map { it.outcomeSystemType })
        assertFalse("outcomeSystemType" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
        a.updateTurn(id, "r1") { it.copy(outcome = "找到两篇", outcomeSystemType = null) }
        assertEquals(null, ConversationStore(dir).turns(id).first().outcomeSystemType)
    }

    /** 插话之前已经说完的回复同样一一对应存下;没有时不写这个字段。 */
    @Test fun persistsFinishedReplies() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天"), finishedReplies = listOf("今天晴,最高 25 度。")))
        a.append(id, Turn("谢谢", "不客气", "succeeded", steers = listOf("好")))
        assertEquals(listOf(listOf("今天晴,最高 25 度。"), emptyList()), ConversationStore(dir).turns(id).map { it.finishedReplies })
        assertFalse("finishedReplies" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** 删对话:对话文件、标题、挪到一边的损坏文件、临时文件都删掉,列表里没有了,别的对话不动;删完发一次改动。 */
    @Test fun deleteRemovesTheConversationAndItsSidecars() = kotlinx.coroutines.runBlocking {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        val other = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded"))
        store.setTitle(id, "天气")
        store.setPinned(id, true, at = 5L)
        store.setProject(id, "proj-sidecar")
        java.io.File(dir, "$id.json.corrupt-1").writeText("x")
        java.io.File(dir, "$id.json.tmp").writeText("x")
        store.append(other, Turn("订票", "好了", "succeeded"))
        val changes = mutableListOf<String>()
        val deletions = mutableListOf<String>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.changes.collect { changes += it } }
        val gone = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.deletions.collect { deletions += it } }
        store.delete(id)
        kotlinx.coroutines.yield()
        job.cancel()
        gone.cancel()
        assertEquals(listOf(id), changes)
        assertEquals(listOf(id), deletions)
        assertEquals(listOf("$other.json"), dir.list()!!.sorted())
        assertEquals(listOf(other), store.list().map { it.id })
        assertEquals(emptyList<Turn>(), store.turns(id))
        assertEquals(null, store.title(id))
        assertEquals(null, store.pinnedAt(id))
    }

    /** 用户改的名字说了算:后台起标题晚到也不盖掉;起好了的标题用户照样能改;空的不改。 */
    @Test fun renameByUserWinsOverAutoTitle() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        store.append(id, Turn("查一下明天上海的天气", "晴", "succeeded"))
        assertTrue(store.rename(id, "  天气  "))
        assertFalse(store.setTitle(id, "上海天气"))
        assertEquals("天气", ConversationStore(dir).title(id))

        val other = store.newConversation()
        store.append(other, Turn("订票", "好了", "succeeded"))
        assertTrue(store.setTitle(other, "订高铁票"))
        assertTrue(store.rename(other, "回家的票"))
        assertFalse(store.rename(other, "   "))
        assertEquals("回家的票", store.title(other))
        assertEquals(setOf("天气", "回家的票"), store.list().map { it.title }.toSet())

        store.delete(other)
        assertFalse(store.rename(other, "再改"))
    }

    /** 置顶记在 `.pin` 里(置顶的时间),重开还在;列表带上置顶时间,不改对话的先后;取消置顶就没了。 */
    @Test fun pinPersistsAndShowsInTheList() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("一", "好", "succeeded"))
        store.append("b", Turn("二", "好", "succeeded"))
        assertTrue(store.setPinned("a", true, at = 123L))
        val again = ConversationStore(dir)
        assertEquals(123L, again.pinnedAt("a"))
        assertEquals(null, again.pinnedAt("b"))
        assertEquals(mapOf("a" to 123L, "b" to null), again.list().associate { it.id to it.pinnedAt })
        assertTrue(again.setPinned("a", false))
        assertEquals(null, again.pinnedAt("a"))
        assertFalse(java.io.File(dir, "a.pin").exists())
        // 删掉的对话不再置顶回来
        again.delete("b")
        assertFalse(again.setPinned("b", true))
    }

    /** 删之后才到的补写(后台起好的标题、收尾后补的结局)不把对话写回来;再存进一轮就是重新用这个编号。 */
    @Test fun lateWritesDoNotReviveADeletedConversation() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded", runId = "r1"))
        store.delete(id)
        assertFalse(store.setTitle(id, "天气"))
        store.updateTurn(id, "r1") { it.copy(outcome = "补上的") }
        assertTrue(dir.list()!!.isEmpty())

        store.append(id, Turn("再来", "好", "succeeded"))
        assertTrue(store.setTitle(id, "再来"))
        assertEquals(listOf("再来"), store.list().map { it.title })
    }

    @Test fun corruptFileReadsAsEmpty() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        java.io.File(dir, "x.json").writeText("{not json")
        assertEquals(emptyList<Turn>(), s.turns("x"))
        s.append("x", Turn("a", "b", "cancelled"))
        assertEquals(1, s.turns("x").size)
    }

    @Test fun corruptFileIsMovedAsideNotOverwritten() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        java.io.File(dir, "x.json").writeText("{not json")
        s.append("x", Turn("a", "b", "cancelled"))
        val aside = dir.listFiles().orEmpty().filter { it.name.startsWith("x.json.corrupt-") }
        assertEquals(1, aside.size)
        assertEquals("{not json", aside.single().readText())
        assertEquals(listOf(Turn("a", "b", "cancelled")), s.turns("x").map { it.copy(atMs = null) })
        assertEquals(listOf("x"), s.list().map { it.id })
    }

    @Test fun listsConversationsNewestFirst() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        val a = s.newConversation()
        val b = s.newConversation()
        s.append(a, Turn("查天气", "晴", "succeeded"))
        s.append(b, Turn("订票", "好了", "succeeded"))
        s.append(b, Turn("改签", "好了", "succeeded"))
        java.io.File(dir, "$a.json").setLastModified(1_000)
        java.io.File(dir, "$b.json").setLastModified(2_000)
        java.io.File(dir, "broken.json").writeText("{")
        assertEquals(listOf(b to "订票", a to "查天气"), s.list().map { it.id to it.title })
    }

    @Test fun unverifiedTurnKeepsItsNote() {
        val dir = tmp.newFolder()
        val id = ConversationStore(dir).let { s -> s.newConversation().also { s.append(it, Turn("查余额", "余额 10 元", "unverified", note = "防截屏")) } }
        assertEquals(listOf(Turn("查余额", "余额 10 元", "unverified", note = "防截屏")), ConversationStore(dir).turns(id).map { it.copy(atMs = null) })
    }

    @Test fun turnKeepsItsRunId() {
        val dir = tmp.newFolder()
        val s = ConversationStore(dir)
        val id = s.newConversation()
        s.append(id, Turn("查天气", "晴", "succeeded", runId = "20261023-120000-abc123"))
        s.append(id, Turn("旧的", "好", "succeeded"))
        assertEquals(listOf("20261023-120000-abc123", null), ConversationStore(dir).turns(id).map { it.runId })
    }

    @Test fun memoryNotesStoredWithoutContentAndStripped() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val note = com.wangzan101.mika.core.memory.MemoryNote(com.wangzan101.mika.core.memory.NoteKind.added, "m1", "t1", null)
        val other = com.wangzan101.mika.core.memory.MemoryNote(com.wangzan101.mika.core.memory.NoteKind.pending, "m2", "t2", "m2")
        store.append("c1", Turn("点外卖", "ok", "succeeded", runId = "r1"))
        store.updateTurn("c1", "r1") { it.copy(memoryNotes = listOf(note, other), usedMemory = listOf("m1", "m3")) }
        val raw = java.io.File(dir, "c1.json").readText()
        assertTrue(raw.contains("\"memoryId\":\"m1\""))
        assertFalse(raw.contains("text"))
        assertEquals(listOf(note, other), ConversationStore(dir).turns("c1").single().memoryNotes)
        store.stripMemory(setOf("m1"))
        val t = ConversationStore(dir).turns("c1").single()
        assertEquals(listOf(other), t.memoryNotes)
        assertEquals(listOf("m3"), t.usedMemory)
        store.stripMemory(null)
        assertTrue(ConversationStore(dir).turns("c1").single().memoryNotes.isEmpty())
    }

    @Test fun filesKeptPerTurn() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        val f = SharedFile("图.png", 1234, "Download/Mika/图.png", "content://media/external/downloads/7")
        a.append(id, Turn("画图", "好了", "succeeded"), listOf(f))
        a.append(id, Turn("再来", "好", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals(listOf(listOf(f), emptyList()), b.records(id).map { it.second })
        assertEquals(2, b.turns(id).size)
    }

    // 任务结束后补记忆提示、删记忆时,这一轮的文件卡片不能丢
    @Test fun filesSurviveNoteUpdatesAndStrip() {
        val dir = tmp.newFolder("d")
        val store = ConversationStore(dir)
        val f = SharedFile("表.csv", 10, "Download/Mika/表.csv", "content://media/external/downloads/8")
        store.append("c1", Turn("导出", "好了", "succeeded", runId = "r1"), listOf(f))
        val note = com.wangzan101.mika.core.memory.MemoryNote(com.wangzan101.mika.core.memory.NoteKind.added, "m1", "t1", null)
        store.updateTurn("c1", "r1") { it.copy(memoryNotes = listOf(note)) }
        store.stripMemory(null)
        val r = ConversationStore(dir).records("c1").single()
        assertEquals(listOf(f), r.second)
        assertTrue(r.first.memoryNotes.isEmpty())
    }

    // 写过哪个对话就发出它的编号:存进一轮、改一轮、去掉记忆提示
    @Test fun announcesChangedConversations() = kotlinx.coroutines.test.runTest {
        val s = ConversationStore(tmp.newFolder("c"))
        val seen = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.changes.collect { seen += it } }
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.deletions.collect { deleted += it } }
        s.append("a", Turn("查天气", "晴", "succeeded", runId = "r1", usedMemory = listOf("m1")))
        s.append("b", Turn("订票", "好了", "succeeded"))
        s.updateTurn("a", "r1") { it.copy(outcome = "晴转多云") }
        s.updateTurn("a", "missing") { it }
        s.stripMemory(setOf("m1"))
        assertEquals(listOf("a", "b", "a", "a"), seen)
        // 只有删对话才发 deletions
        assertEquals(emptyList<String>(), deleted)
    }

    /** 从哪一页开始的对话:记在旁边的 .page 文件里,列表里带着;旧对话没有它照常读;删对话一起删掉。 */
    @Test fun pageOriginPersistsAndIsOptional() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val plain = a.newConversation()
        a.append(plain, Turn("查天气", "晴", "succeeded"))
        val paged = a.newConversation()
        assertTrue(a.setPageOrigin(paged, PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索")))
        a.append(paged, Turn("挑一个", "好", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals(PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索"), b.pageOrigin(paged))
        assertEquals(null, b.pageOrigin(plain))
        val listed = b.list().associateBy { it.id }
        assertEquals("shop.example.com", listed.getValue(paged).page?.host)
        assertEquals(null, listed.getValue(plain).page)
        // 对话文件的格式不变:没有多出来的字段
        assertFalse(java.io.File(dir, "$paged.json").readText().contains("shop.example.com"))
        // 坏了的 .page 当作没有
        java.io.File(dir, "$plain.page").writeText("{not json")
        assertEquals(null, b.pageOrigin(plain))
        b.delete(paged)
        assertFalse(java.io.File(dir, "$paged.page").exists())
        assertFalse(b.setPageOrigin(paged, PageOrigin("https://a.example/", "a.example")))
    }

    /**
     * 「用户此刻在哪个对话」也记下来:进程重启后胶囊据此接着那个对话,而不是靠文件时间猜最近的;
     * 新开还没说话的对话记成 [CurrentChat.Fresh];删掉它时一起清掉;这个文件不进对话列表。
     */
    @Test fun remembersTheConversationOnScreen() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "晴", "succeeded"))
        assertEquals(null, a.currentChat())

        a.setCurrent(CurrentChat.Fresh)
        assertEquals(CurrentChat.Fresh, a.currentChat())
        // 新开的对话还没有对话文件,重启后照样读得回来
        assertEquals(CurrentChat.Fresh, ConversationStore(dir).currentChat())
        assertEquals(listOf(id), ConversationStore(dir).list().map { it.id })

        a.setCurrent(CurrentChat.Open(id))
        assertEquals(CurrentChat.Open(id), ConversationStore(dir).currentChat())
        assertEquals(CurrentChat.Open(id), ConversationStore(dir).current.value)
        // 记在旁边的 current 文件里,不动对话文件、不进列表
        assertEquals(setOf("$id.json", "current"), dir.list()!!.toSet())
        assertEquals(listOf(id), a.list().map { it.id })

        // 删掉这个对话:指向它的记录一起清掉,胶囊回落到最近那个
        a.delete(id)
        assertEquals(null, ConversationStore(dir).currentChat())
    }

    /** 分支复制到选定的那一轮(含在内),之后的不复制;源对话原样不动。 */
    @Test fun branchCopiesThroughTheChosenTurn() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded", atMs = 11L))
        store.append(source, Turn("二", "乙", "succeeded", atMs = 22L))
        store.append(source, Turn("三", "丙", "succeeded", atMs = 33L))
        val target = store.newConversation()
        assertTrue(store.branch(source, target, throughTurn = 1))
        assertEquals(
            listOf(Turn("一", "甲", "succeeded", atMs = 11L), Turn("二", "乙", "succeeded", atMs = 22L)),
            ConversationStore(dir).turns(target),
        )
        // 源对话仍是三轮
        assertEquals(listOf("一", "二", "三"), store.turns(source).map { it.userText })
    }

    /** 分支带走标题、网页来源和分支来源,不带走置顶。 */
    @Test fun branchCopiesTitlePageAndOriginButNotPin() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("查天气", "晴", "succeeded"))
        store.setTitle(source, "原对话")
        store.setPinned(source, true, at = 1L)
        val page = PageOrigin("https://a.example/", "a.example", "示例")
        store.setPageOrigin(source, page)
        val target = store.newConversation()
        val origin = BranchOrigin(source, 0, afterTurn = true, at = 123L)
        assertTrue(store.branch(source, target, 0, title = "原对话 · 分支", origin = origin))
        val again = ConversationStore(dir)
        assertEquals("原对话 · 分支", again.title(target))
        assertEquals(page, again.pageOrigin(target))
        assertEquals(origin, again.branchOrigin(target))
        assertEquals(null, again.pinnedAt(target))
    }

    /** 源不存在、源文件损坏、轮次越界、目标已有对话文件:都返回 false,什么都不写。 */
    @Test fun branchRejectsBadInput() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        val target = store.newConversation()

        assertFalse(store.branch("missing", target, 0))
        assertFalse(java.io.File(dir, "$target.json").exists())

        java.io.File(dir, "broken.json").writeText("{not json")
        assertFalse(store.branch("broken", target, 0))

        assertFalse(store.branch(source, target, throughTurn = 2))
        assertFalse(store.branch(source, target, throughTurn = -1))
        assertFalse(java.io.File(dir, "$target.json").exists())
        assertFalse(java.io.File(dir, "$target.branch").exists())

        store.append(target, Turn("已有", "好", "succeeded"))
        assertFalse(store.branch(source, target, 0))
        assertEquals(listOf("已有"), store.turns(target).map { it.userText })
    }

    /** 分支可以再分支,轮数按上一级的分支内容算,来源指向上一个对话。 */
    @Test fun branchOfBranchWorks() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        val middle = store.newConversation()
        assertTrue(store.branch(source, middle, 2, origin = BranchOrigin(source, 2, afterTurn = true, at = 1L)))
        val leaf = store.newConversation()
        assertTrue(store.branch(middle, leaf, 0, origin = BranchOrigin(middle, 0, afterTurn = true, at = 2L)))
        assertEquals(listOf("一"), store.turns(leaf).map { it.userText })
        assertEquals(middle, store.branchOrigin(leaf)?.parentId)
        assertEquals(source, store.branchOrigin(middle)?.parentId)
    }

    /** 删对话时分支来源一起删掉。 */
    @Test fun deleteRemovesBranchSidecar() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val target = store.newConversation()
        assertTrue(store.branch(source, target, 0, origin = BranchOrigin(source, 0, afterTurn = true, at = 1L)))
        val origin = java.io.File(dir, "$target.branch")
        assertTrue(origin.isFile)
        store.delete(target)
        assertFalse(origin.exists())
        assertEquals(null, store.branchOrigin(target))
    }

    /** 列表带上分支来源;普通对话、没有 .branch 的旧对话为 null。 */
    @Test fun listExposesBranchOrigin() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val target = store.newConversation()
        val origin = BranchOrigin(source, 0, afterTurn = true, at = 9L)
        assertTrue(store.branch(source, target, 0, origin = origin))
        val listed = ConversationStore(dir).list().associateBy { it.id }
        assertEquals(origin, listed.getValue(target).branchOf)
        assertEquals(null, listed.getValue(source).branchOf)
    }

    /** 从用户消息重新问:只复制这一轮之前的轮次,来源记 after=false。 */
    @Test fun branchBeforeCopiesOnlyEarlierTurns() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        val page = PageOrigin("https://a.example/", "a.example", "示例")
        store.setPageOrigin(source, page)
        val target = store.newConversation()
        val origin = BranchOrigin(source, 1, afterTurn = false, at = 2L)

        assertTrue(store.branchBefore(source, target, beforeTurn = 1, title = "原对话 · 分支", origin = origin))

        assertEquals(listOf("一"), ConversationStore(dir).turns(target).map { it.userText })
        assertEquals(origin, store.branchOrigin(target))
        assertEquals(page, store.pageOrigin(target))
        assertEquals(listOf("一", "二", "三"), store.turns(source).map { it.userText })
    }

    /** 第一轮之前没有轮次:分支是空的,不进侧栏;标题和来源已经写好,第一句话存进来后接着用。 */
    @Test fun branchBeforeTheFirstTurnMakesAnEmptyBranch() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val target = store.newConversation()
        val origin = BranchOrigin(source, 0, afterTurn = false, at = 3L)

        assertTrue(store.branchBefore(source, target, beforeTurn = 0, title = "一 · 分支", origin = origin))

        assertEquals(emptyList<Turn>(), store.turns(target))
        assertTrue(java.io.File(dir, "$target.json").isFile)
        assertTrue(store.list().none { it.id == target })
        assertEquals("一 · 分支", store.title(target))
        assertEquals(origin, store.branchOrigin(target))
        store.append(target, Turn("换个问法", "另一个答", "succeeded"))
        assertEquals(listOf("换个问法"), store.turns(target).map { it.userText })
        assertTrue(store.list().any { it.id == target })
    }

    /** 重新问也拒绝坏输入:源不存在、轮次越界、目标已有文件。 */
    /** 人格绑定读得回来;切换只记变化,同一个再写不重复记;null 表示回到默认。 */
    @Test fun personaBindingRoundTripsAndRecordsSwitches() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        assertNull(store.persona(id))
        assertTrue(store.setPersona(id, "p1", at = 10L))
        assertEquals("p1", ConversationStore(dir).persona(id)?.personaId)
        assertEquals(emptyList<PersonaSwitch>(), store.persona(id)?.history)
        assertTrue(store.setPersona(id, "p2", at = 20L))
        assertEquals(listOf(PersonaSwitch(20L, "p1", "p2")), store.persona(id)?.history)
        assertTrue(store.setPersona(id, "p2", at = 30L))
        assertEquals(1, store.persona(id)?.history?.size)
        assertTrue(store.setPersona(id, null))
        assertNull(store.persona(id))
        assertFalse(java.io.File(dir, "$id.persona").exists())
    }

    /** 分支复制当前人格,不复制切换记录;删对话连旁文件一起删。 */
    @Test fun personaBindingFollowsBranchesAndDeletion() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.setPersona(source, "p1", at = 10L)
        store.setPersona(source, "p2", at = 20L)
        val target = store.newConversation()
        assertTrue(store.branch(source, target, throughTurn = 0))
        assertEquals("p2", store.persona(target)?.personaId)
        assertEquals(emptyList<PersonaSwitch>(), store.persona(target)?.history)
        store.delete(source)
        assertFalse(java.io.File(dir, "$source.persona").exists())
    }

    /** 不合法的人格 id 不写;旁文件里的坏 id 当没有。 */
    @Test fun personaBindingRejectsBadIds() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        assertFalse(store.setPersona(id, "../escape"))
        assertNull(store.persona(id))
        java.io.File(dir, "$id.persona").writeText("""{"current":"../escape"}""")
        assertNull(store.persona(id))
    }

    /** 归档的对话默认不进侧栏;要全部时用 includeArchived;换个实例也还在,取消归档后回来。 */
    @Test fun archivedConversationsHiddenUntilRequested() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))

        assertTrue(store.setArchived("a", true, at = 123L))
        assertEquals(123L, store.archivedAt("a"))
        assertEquals(listOf("b"), store.list().map { it.id })
        assertEquals(setOf("a", "b"), store.list(includeArchived = true).map { it.id }.toSet())
        assertEquals(123L, store.list(includeArchived = true).first { it.id == "a" }.archivedAt)

        val reopened = ConversationStore(dir)
        assertEquals(123L, reopened.archivedAt("a"))
        assertEquals(listOf("b"), reopened.list().map { it.id })

        assertTrue(reopened.setArchived("a", false))
        assertNull(reopened.archivedAt("a"))
        assertEquals(setOf("a", "b"), reopened.list().map { it.id }.toSet())
    }

    /** 取消归档记下恢复时间(自动归档据此给一段宽限);删对话时一起删。 */
    @Test fun unarchiveRecordsTheRestoreTime() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setArchived("a", true, at = 1L)

        assertTrue(store.setArchived("a", false, at = 456L))
        assertEquals(456L, store.restoredAt("a"))
        assertEquals(456L, ConversationStore(dir).list(includeArchived = true).single().restoredAt)

        store.delete("a")
        assertFalse(File(dir, "a.restored").exists())
        assertNull(ConversationStore(dir).restoredAt("a"))
    }

    /** 删掉归档的对话时,旁文件一起删;用新实例看,免得进程里的删除记录挡住。 */
    @Test fun deleteRemovesArchiveSidecar() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setArchived("a", true, at = 1L)
        assertTrue(File(dir, "a.archive").isFile)

        store.delete("a")

        assertFalse(File(dir, "a.archive").exists())
        assertNull(ConversationStore(dir).archivedAt("a"))
        assertTrue(ConversationStore(dir).list(includeArchived = true).isEmpty())
    }

    /** 从归档的对话分出来的分支是新的、活跃的,不继承归档。 */
    @Test fun branchDoesNotInheritArchive() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.setArchived(source, true, at = 1L)
        val target = store.newConversation()

        assertTrue(store.branch(source, target, 0))

        assertNull(store.archivedAt(target))
        assertTrue(store.list().any { it.id == target })
        assertTrue(store.list().none { it.id == source })
    }

    /** 归档旁文件读不懂时当作没归档,不挡住列表。 */
    @Test fun corruptArchiveFileIsTreatedAsActive() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("查天气", "晴", "succeeded"))
        File(dir, "a.archive").writeText("not-a-time")

        assertNull(store.archivedAt("a"))
        assertEquals(listOf("a"), store.list().map { it.id })
    }

    /** 归档的对话来了新的一轮就自动取消归档;只改已有那一轮、只改标题不算。 */
    @Test fun appendClearsArchiveButEditingAnExistingTurnDoesNot() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setArchived("a", true, at = 1L)

        store.updateTurn("a", runId = "missing") { it }
        store.setTitle("a", "天气")
        assertEquals(1L, store.archivedAt("a"))

        store.append("a", Turn("那明天呢", "也晴", "succeeded"))

        assertNull(store.archivedAt("a"))
        assertTrue(store.list().any { it.id == "a" })
    }

    @Test fun generatedAudiosSurviveReopen() {
        val dir = tmp.newFolder("audio")
        val store = ConversationStore(dir)
        val audios = listOf(
            GeneratedAudio("/workspace/out/audio/line.wav", 1_200L, "你好", 42L),
            GeneratedAudio("/workspace/out/audio/two.mp3", null, null, null),
        )
        store.append("a", Turn("说一段", "好", "succeeded", audios = audios))
        assertEquals(audios, ConversationStore(dir).turns("a").single().audios)
    }

    @Test fun projectSidecarPersistsInheritsOnBranchAndCleansOnDelete() {
        val dir = tmp.newFolder("project-sidecar")
        val store = ConversationStore(dir)
        val a = store.newConversation()
        store.append(a, Turn("第一轮", "好了", "succeeded"))
        assertTrue(store.setProject(a, "proj-1"))
        assertFalse(store.setProject(a, "../bad"))
        assertEquals("proj-1", ConversationStore(dir).projectId(a))
        assertEquals("proj-1", store.list().single { it.id == a }.projectId)

        val branched = store.newConversation()
        assertTrue(store.branch(a, branched, 0))
        assertEquals("proj-1", ConversationStore(dir).projectId(branched))

        // 删除源对话连带清掉它的 .project 旁文件,分支保留
        store.delete(a)
        assertNull(store.projectId(a))
        assertFalse(File(dir, "$a.project").exists())
        assertFalse(store.setProject(a, "proj-2"))
        assertEquals("proj-1", store.projectId(branched))

        // 解绑回到独立对话
        assertTrue(store.setProject(branched, null))
        assertNull(store.projectId(branched))
        assertFalse(File(dir, "$branched.project").exists())
    }
}
