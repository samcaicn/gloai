package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.chat.BranchOrigin
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.core.usage.DeepSeekCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ChatCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun sixDistinctExamplesStartWithGithubStar() {
        val examples = ChatCopy.examples
        assertEquals(6, examples.size)
        assertEquals(6, examples.map { it.title }.filter { it.isNotBlank() }.toSet().size)
        assertEquals(6, examples.map { it.prompt }.filter { it.isNotBlank() }.toSet().size)
        assertEquals(ChatCopy.ExampleKind.Web, examples.first().kind)
        assertTrue(examples.first().prompt.contains("https://github.com/wangzan101/mika"))
        // 短标题上卡片、完整提示词进输入框:两者分开存
        assertEquals(ChatCopy.examples.map { it.title }, ChatCopy.recommendationSeeds().map { it.first })
        assertEquals(ChatCopy.examples.map { it.prompt }, ChatCopy.recommendationSeeds().map { it.second })
    }

    @Test fun taskCostShowsTwoDecimalPlaces() {
        assertEquals("约 ¥1.20", ChatTokensCopy.cost(DeepSeekCost("1.2", "1.2")))
        assertEquals("约 ¥0.00", ChatTokensCopy.cost(DeepSeekCost("0.001", "0.004")))
        assertEquals("约 ¥0.01–¥0.02", ChatTokensCopy.cost(DeepSeekCost("0.005", "0.015")))
    }

    /** 分支标题:源标题后面加后缀,总长不超过 24 字;截的是源标题,后缀留着,emoji 不截半个。 */
    @Test fun branchTitleKeepsTheSuffixWhenTruncating() {
        val suffix = " · 分支"
        val long = "一二三四五六七八九十一二三四五六七八九十一二三四五"
        val title = ChatCopy.branchTitle(long, suffix)
        assertTrue(title.endsWith(suffix))
        assertEquals(24, title.length)
        assertEquals("短标题$suffix", ChatCopy.branchTitle("短标题", suffix))
        val emoji = ChatCopy.branchTitle("😀".repeat(21), suffix)
        assertTrue(emoji.endsWith(suffix))
        assertFalse(emoji.dropLast(suffix.length).lastOrNull()?.isHighSurrogate() ?: false)
    }

    @Test fun titleIsTheFirstLineOfTheFirstMessage() {
        val first = Exchange("查车次\n附件:/workspace/inbox/a.pdf(1 KB)", emptyList(), ExchangeStatus.Working, live = true)
        assertEquals("查车次", ChatCopy.title(listOf(first, first.copy(goal = "再查"))))
        assertEquals("", ChatCopy.title(emptyList()))
        // 只发了附件:不拿路径当标题
        assertEquals("照片", ChatCopy.title(listOf(first.copy(goal = "附件:/workspace/inbox/a.jpg(1 KB)"))))
        assertEquals("a.pdf", ChatCopy.plainTitle("附件:/workspace/inbox/a.pdf(1 KB)"))
        assertEquals(ScreenCopy.TAG, ChatCopy.plainTitle("[[screen:s1]]"))
        // 在浏览器页上对着一页问的:页面说明不当标题
        val paged = com.wangzan101.mika.core.agent.AttachedPage.compose(com.wangzan101.mika.core.agent.AttachedPage.Page("https://a.example/", "页"), "挑一个\n第二行")
        assertEquals("挑一个", ChatCopy.plainTitle(paged))
    }

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun drawerListsRunningThenRecent() {
        val now = at(2026, 10, 27, 15)
        val all = listOf(
            ConversationSummary("a", "附近的川菜", at(2026, 10, 27, 14)),
            ConversationSummary("b", "明天的高铁票", at(2026, 10, 26)),
            ConversationSummary("c", "PDF 要点整理", at(2026, 10, 26)),
        )
        val current = CurrentConversation("a", running = true, title = "附近的川菜", subtitle = "正在操作外卖")
        val s = drawerSections(
            all, current, others = listOf("c", "d"), otherTitle = { if (it == "d") "第二个任务" else null },
            canSwitch = true, now = now, zone = zone,
        )
        assertEquals(listOf("a", "c", "d"), s.running.map { it.id })
        assertEquals(listOf("附近的川菜", "PDF 要点整理", "第二个任务"), s.running.map { it.title })
        assertEquals("正在操作外卖", s.running.first().subtitle)
        assertTrue(s.running.first().current)
        assertEquals(listOf("b"), s.recent.map { it.id })
        assertEquals("", s.recent.single().subtitle)
    }

    /** 置顶的对话在「置顶」一段,最近置顶的在前,不在「最近」里重复;有任务在跑的只列在进行中(带图钉)。 */
    @Test fun drawerListsPinnedAboveRecent() {
        val all = listOf(
            ConversationSummary("a", "一", 40L),
            ConversationSummary("b", "二", 30L, pinnedAt = 100L),
            ConversationSummary("c", "三", 20L),
            ConversationSummary("d", "四", 10L, pinnedAt = 200L),
            ConversationSummary("e", "五", 5L, pinnedAt = 300L),
        )
        val s = drawerSections(all, CurrentConversation("a", false, "一", ""), others = listOf("e"), { null }, canSwitch = true, now = 0L, zone = zone)
        assertEquals(listOf("e"), s.running.map { it.id })
        assertTrue(s.running.single().pinned)
        assertEquals(listOf("d", "b"), s.pinned.map { it.id })
        assertTrue(s.pinned.all { it.pinned })
        assertEquals(listOf("a", "c"), s.recent.map { it.id })
        assertTrue(s.recent.none { it.pinned })
        // 取消置顶:回到「最近」里按改动时间排的位置
        val unpinned = drawerSections(all.map { if (it.id == "b") it.copy(pinnedAt = null) else it }, CurrentConversation("a", false, "一", ""), emptyList(), { null }, true, 0L, zone)
        assertEquals(listOf("e", "d"), unpinned.pinned.map { it.id })
        assertEquals(listOf("a", "b", "c"), unpinned.recent.map { it.id })
    }

    /** 在浏览器页上对着一页开始的对话:侧栏那一条带着网站(画成「网页」小标签和网站名),进行中、最近两段都是。 */
    @Test fun drawerMarksConversationsStartedFromAPage() {
        val page = com.wangzan101.mika.chat.PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索")
        val all = listOf(ConversationSummary("a", "一", 0L, page = page), ConversationSummary("b", "二", 0L), ConversationSummary("c", "三", 0L, page = page))
        val s = drawerSections(all, CurrentConversation("x", false, "", ""), others = listOf("c"), { null }, canSwitch = true, now = 0L, zone = zone)
        assertEquals(listOf("shop.example.com", null), s.recent.map { it.site })
        assertEquals("shop.example.com", s.running.single().site)
    }

    /** 分支出来的对话在侧栏带分支标记;普通对话不带。 */
    @Test fun drawerMarksBranchedConversations() {
        val origin = BranchOrigin("parent", 1, afterTurn = true, at = 5L)
        val all = listOf(ConversationSummary("a", "一", 0L, branchOf = origin), ConversationSummary("b", "二", 0L))
        val s = drawerSections(all, CurrentConversation("x", false, "", ""), emptyList(), { null }, canSwitch = true, now = 0L, zone = zone)
        assertEquals(listOf(true, false), s.recent.map { it.branch })
    }

    /** 任务进行中、没开并行:只有当前对话能点。 */
    @Test fun drawerLocksOtherConversationsWhileBusy() {
        val all = listOf(ConversationSummary("a", "一", 0L), ConversationSummary("b", "二", 0L))
        val s = drawerSections(all, CurrentConversation("a", false, "一", ""), emptyList(), { null }, canSwitch = false, now = 0L, zone = zone)
        assertEquals(listOf(true, false), s.recent.map { it.enabled })
        assertEquals(listOf(true, false), s.recent.map { it.current })
        // 当前对话还没存进列表时(刚发出第一句)也列在进行中
        val fresh = drawerSections(emptyList(), CurrentConversation(null, true, "", "正在处理"), emptyList(), { null }, false, 0L, zone)
        assertEquals("新对话", fresh.running.single().title)
        assertTrue(fresh.running.single().enabled)
    }

    /** 归档的对话不进置顶和最近;当前正在看的那个留着(带归档标记),还在跑任务的仍列在进行中。 */
    @Test fun drawerHidesArchivedExceptCurrentAndRunning() {
        val all = listOf(
            ConversationSummary("a", "一", 30L),
            ConversationSummary("b", "二", 20L, archivedAt = 5L),
            ConversationSummary("c", "三", 10L, archivedAt = 6L),
            ConversationSummary("d", "四", 5L, archivedAt = 7L),
        )
        val s = drawerSections(all, CurrentConversation("a", false, "一", ""), others = listOf("d"), { null }, canSwitch = true, now = 0L, zone = zone)
        assertEquals(listOf("d"), s.running.map { it.id })
        assertTrue(s.running.single().archived)
        assertEquals(listOf("a"), s.recent.map { it.id })
        assertTrue(s.recent.none { it.archived })
        // 当前看的就是归档的那个:留着,不然不知道自己在哪
        val cur = drawerSections(all, CurrentConversation("b", false, "二", ""), emptyList(), { null }, canSwitch = true, now = 0L, zone = zone)
        assertEquals(listOf("a", "b"), cur.recent.map { it.id })
        assertTrue(cur.recent.last().archived)
        assertTrue(cur.recent.last().current)
        // 没归档时一条不少
        val plain = drawerSections(all.map { it.copy(archivedAt = null) }, CurrentConversation("a", false, "一", ""), emptyList(), { null }, true, 0L, zone)
        assertEquals(listOf("a", "b", "c", "d"), plain.recent.map { it.id })
    }

    /** 多选的勾选范围跟着视图走:普通侧栏是列出来的对话,归档视图里是归档的那些;草稿没有编号,勾不了。 */
    @Test fun drawerSelectableIdsFollowTheView() {
        val sections = DrawerSections(
            running = listOf(DrawerConversation("a", "一", "", running = true, current = true, enabled = true)),
            recent = listOf(
                DrawerConversation("b", "二", "", running = false, current = false, enabled = true),
                DrawerConversation(null, "新对话", "", running = false, current = false, enabled = true),
            ),
        )
        val archived = listOf(DrawerConversation("c", "三", "", running = false, current = false, enabled = true, archived = true))
        assertEquals(setOf("a", "b"), drawerSelectableIds(sections, archived, archivedView = false))
        assertEquals(setOf("c"), drawerSelectableIds(sections, archived, archivedView = true))
    }

    /** 搜索筛选:全部含归档,未归档/已归档各取一边;删掉的不搜。 */
    @Test fun drawerSearchCandidatesFilterByArchiveState() {
        val all = listOf(
            ConversationSummary("a", "附件:/workspace/inbox/a.pdf(1 KB)", 3L),
            ConversationSummary("b", "二", 2L, archivedAt = 1L),
            ConversationSummary("c", "三", 1L),
        )
        val deleted = setOf("c")
        assertEquals(listOf("a", "b"), drawerSearchCandidates(all, deleted, SearchArchiveFilter.All).map { it.id })
        assertEquals(listOf("a"), drawerSearchCandidates(all, deleted, SearchArchiveFilter.Active).map { it.id })
        assertEquals(listOf("b"), drawerSearchCandidates(all, deleted, SearchArchiveFilter.Archived).map { it.id })
        // 标题还是侧栏里显示的样子(去掉附件行)
        assertEquals("a.pdf", drawerSearchCandidates(all, deleted, SearchArchiveFilter.All).first().title)
    }

    private fun archivedRow(id: String, archivedAt: Long) =
        DrawerConversation(id, id, "", running = false, current = false, enabled = true, archived = true, archivedAt = archivedAt)

    /** 归档视图按归档时间分段:本周(周一起算)、本月、更早;没记归档时间的归到更早,不会漏。 */
    @Test fun archiveViewGroupsByWeekMonthAndOlder() {
        val now = at(2026, 11, 12, 15) // 周四
        val rows = listOf(
            archivedRow("a", at(2026, 11, 12, 9)),
            archivedRow("b", at(2026, 11, 9, 20)),
            archivedRow("c", at(2026, 11, 2)),
            archivedRow("d", at(2026, 9, 30)),
        )
        val groups = archiveGroups(rows, now, zone)
        assertEquals(listOf(ArchivePeriod.ThisWeek, ArchivePeriod.ThisMonth, ArchivePeriod.Earlier), groups.map { it.period })
        assertEquals(listOf("a", "b"), groups[0].rows.map { it.id })
        assertEquals(listOf("c"), groups[1].rows.map { it.id })
        assertEquals(listOf("d"), groups[2].rows.map { it.id })
        val unknown = archiveGroups(
            listOf(DrawerConversation("e", "五", "", running = false, current = false, enabled = true, archived = true)),
            now, zone,
        )
        assertEquals(listOf(ArchivePeriod.Earlier), unknown.map { it.period })
    }

    /** 一行写清归档了多久:当天是「今天归档」,其余是「归档于 N 天前」。 */
    @Test fun archivedAgoSaysTodayOrDays() {
        val now = at(2026, 11, 12, 15)
        assertEquals("今天归档", archivedAgo(at(2026, 11, 12, 9), now, zone))
        assertEquals("归档于 3 天前", archivedAgo(at(2026, 11, 9, 15), now, zone))
        assertEquals("归档于 40 天前", archivedAgo(at(2026, 10, 3, 15), now, zone))
        assertEquals("本周", archivePeriodLabel(ArchivePeriod.ThisWeek))
        assertEquals("本月", archivePeriodLabel(ArchivePeriod.ThisMonth))
        assertEquals("更早", archivePeriodLabel(ArchivePeriod.Earlier))
    }

    @Test fun greetingFollowsTheTimeOfDay() {
        assertEquals("早上好", ChatCopy.greeting(7))
        assertEquals("中午好", ChatCopy.greeting(12))
        assertEquals("下午好", ChatCopy.greeting(15))
        assertEquals("晚上好", ChatCopy.greeting(20))
        assertEquals("夜深了", ChatCopy.greeting(2))
        assertEquals("夜深了", ChatCopy.greeting(23))
    }

    @Test fun clockLabel() {
        assertEquals("0:00", ChatCopy.clock(0))
        assertEquals("0:24", ChatCopy.clock(24_900))
        assertEquals("12:05", ChatCopy.clock(725_000))
        assertEquals("1:02:03", ChatCopy.clock(3_723_000))
        assertEquals("0:00", ChatCopy.clock(-5))
    }

    @Test fun subtitleSaysWhatIsGoingOn() {
        val working = Exchange("x", emptyList(), ExchangeStatus.Working, live = true)
        assertEquals("正在处理" to ChatCopy.Tone.Accent, ChatCopy.subtitle(working, null))
        assertEquals("正在操作设置" to ChatCopy.Tone.Accent, ChatCopy.subtitle(working, "设置"))
        assertEquals("等你处理" to ChatCopy.Tone.Warn, ChatCopy.subtitle(working.copy(status = ExchangeStatus.NeedsInput("?", null)), null))
        assertEquals("等你处理" to ChatCopy.Tone.Warn, ChatCopy.subtitle(working.copy(status = ExchangeStatus.Paused("why")), null))
        assertEquals("等你解锁手机" to ChatCopy.Tone.Warn, ChatCopy.subtitle(working.copy(status = ExchangeStatus.WaitingUnlock), null))
        assertNull(ChatCopy.subtitle(working.copy(status = ExchangeStatus.Done("好", null)), "设置"))
        assertNull(ChatCopy.subtitle(null, null))
    }

    @Test fun progressLineSaysPausedWhileWaitingForYou() {
        val ex = Exchange("x", emptyList(), ExchangeStatus.Working, live = true, line = "按评分排序")
        assertEquals("按评分排序", ChatCopy.progressLine(ex))
        assertEquals("已暂停", ChatCopy.progressLine(ex.copy(status = ExchangeStatus.NeedsTakeover("login", "登录"))))
        assertEquals("已暂停", ChatCopy.progressLine(ex.copy(status = ExchangeStatus.NeedsConfirmation("t", "b", 1))))
        assertEquals("已暂停", ChatCopy.progressLine(ex.copy(status = ExchangeStatus.WaitingUnlock)))
        assertEquals("正在处理", ChatCopy.progressLine(ex.copy(line = "", waitingForModel = true)))
    }

    @Test fun placeholderFollowsTheComposer() {
        assertEquals(ChatCopy.EMPTY_PROMPT, ChatCopy.placeholder(ComposerMode.Idle, hasHistory = false))
        assertEquals("继续说点什么…", ChatCopy.placeholder(ComposerMode.Idle, hasHistory = true))
        assertEquals("回答 Mika 的问题", ChatCopy.placeholder(ComposerMode.Answer, hasHistory = true))
        assertEquals("补充或改要求…", ChatCopy.placeholder(ComposerMode.Busy, hasHistory = true))
    }

    @Test fun drawerSectionsGroupsProjectChatsAndIncludesThemInSelectableIds() {
        val p1 = com.wangzan101.mika.chat.Project(id = "p1", name = "科研")
        val p2 = com.wangzan101.mika.chat.Project(id = "p2", name = "空项目")
        val all = listOf(
            ConversationSummary("c-p1-recent", "普通成员", 10L, projectId = p1.id),
            ConversationSummary("c-p1-pinned", "置顶成员", 5L, pinnedAt = 99L, projectId = p1.id),
            ConversationSummary("c-solo", "独立对话", 20L),
            ConversationSummary("c-orphan", "已删项目的对话", 15L, projectId = "missing"),
        )
        val s = drawerSections(
            all,
            CurrentConversation(null, false, "", ""),
            emptyList(),
            { null },
            canSwitch = true,
            now = 0L,
            zone = zone,
            projects = listOf(p1, p2),
        )
        assertEquals(2, s.projects.size)
        assertEquals(listOf("c-p1-pinned", "c-p1-recent"), s.projects[0].rows.map { it.id })
        assertEquals(listOf("科研", "科研"), s.projects[0].rows.map { it.projectName })
        assertEquals(emptyList<String>(), s.projects[1].rows.map { it.id })
        assertEquals(listOf("c-solo", "c-orphan"), s.recent.map { it.id })
        assertEquals(setOf("c-p1-pinned", "c-p1-recent", "c-solo", "c-orphan"), drawerSelectableIds(s, emptyList(), archivedView = false))
    }

    @Test fun sandboxCopyMarksProjectWorkspacesDistinctly() {
        val projectWs = com.wangzan101.mika.cap.sandbox.WorkspaceUsage(
            dirName = "p-proj1", conversationId = null, title = "Mika 开发", bytes = 1024L, busy = false, projectId = "proj1",
        )
        val chatWs = com.wangzan101.mika.cap.sandbox.WorkspaceUsage(
            dirName = "c-chat1", conversationId = "chat1", title = "闲聊", bytes = 512L, busy = false, projectId = null,
        )
        assertEquals("项目 · Mika 开发", SandboxCopy.workspaceTitle(projectWs))
        assertEquals("闲聊", SandboxCopy.workspaceTitle(chatWs))
    }
}
