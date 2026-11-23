package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.screen.observation.PlaceKind
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.learn.knowledge.ChannelAdvisor
import com.wangzan101.mika.core.learn.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.learn.memory.MemoryContext
import com.wangzan101.mika.core.learn.memory.ProfileContext
import com.wangzan101.mika.core.learn.knowledge.toolsNote
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.proactive.ObservedContext
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.learn.replay.RouteAnchors
import com.wangzan101.mika.core.screen.map.ScreenMapRender
import com.wangzan101.mika.core.screen.uistate.interfaces
import kotlinx.coroutines.CancellationException
import com.wangzan101.mika.core.general.HISTORY_READ
import com.wangzan101.mika.core.general.VIEW_USER_SCREEN
import com.wangzan101.mika.core.agent.AgentLoop.LoopHistory
import com.wangzan101.mika.core.agent.AgentLoop.LoopOpening
import com.wangzan101.mika.core.agent.AgentLoop.Companion.STEER_MARKER

// Agent 循环:开场与历史准备、知识注入、运行状态与插话拼接
internal suspend fun AgentLoop.prepareOpening(raw: String, rawPrior: List<Turn>): LoopOpening {
    // 用户发来的屏幕:这一句给全(那句话、截图、详情),之前几轮里的收成一行;下面都用换好的这句
    val shots = UserScreenTurns(userScreens, zone, tools.seesImages, canAskVision = !tools.seesImages && tools.vision != null && tools.definitions().any { it.name == VIEW_USER_SCREEN },
        image = { tools.modelImage(it, null) }, seen = tools::noteUserScreen).also { screens = it }
    user.answerText = shots::asText
    val opening = shots.render(raw)
    val task = opening?.said ?: raw
    taskText = task
    latestWords = ownWords(raw, opening)
    val priorTurns = shots.collapse(rawPrior)
    // 通知提醒那一轮没有用户的话,不算进「之前几轮用户说的话」
    earlierGoals = priorTurns.filterNot(NoticeTurn::isNotice).map { it.userText }
    val context = Conversation.context(priorTurns)
    // 仅「上一轮用户明确确认 + 再上一轮助手建议/提问」可供 memory_save 跨轮引用;更早轮次与助手原文不收。
    val confirmation = rawPrior.lastOrNull()
    val proposal = rawPrior.dropLast(1).lastOrNull()
    // 对话里有通知提醒(第三方文字)时不开这个例外:提醒那一轮就是以问句收尾的,用户回一句「好的」不能成为引用通知内容的依据。
    // 看的是存着的全部轮次:提醒隔了几轮之后,助手的回答里照样可能转述着通知。
    // 这次任务带着「从通知里得来的」那一段时也不开:上一轮的建议可能就是照着那一段说的,用户回一句「好的」
    // 不能让里面的事变成「用户说的」记忆。用户明说的(本轮原话里有的)照常能记。
    if (confirmation != null && proposal != null && !NoticeTurn.tainted(rawPrior) && observed == null) {
        utterances?.addPreviousConfirmation(UserWords.said(confirmation.userText, tools), proposal.outcome, proposal.status)
    }
    utterances?.add(UserWords.said(opening?.words ?: raw, tools))
    // 记忆块:对话上文之后、任务原文之前;用到的条目由代码记下
    usedMemory = emptyList()
    // 上次没读出记忆文件(密钥库一时不可用)就再读一次;还读不出就这次不带记忆
    // 产品路径用 Auto:记忆总量装得下就全量注入(不漏检索),装不下才退回检索
    val block = memory?.takeIf { it.reloadIfNeeded() }?.let {
        MemoryContext.build(it.items.value, task, priorTurns.lastOrNull()?.userText, tools.legacy::rewrite,
            mode = MemoryContext.Mode.Auto)
    }
    if (block?.message != null) {
        usedMemory = block.usedIds
        memory?.markUsed(block.usedIds)
        emit(RunEvent.MemoryUsed(now(), block.usedIds))
    }
    return LoopOpening(task, priorTurns, opening, context, block?.message)
}

internal suspend fun AgentLoop.prepareHistory(opening: LoopOpening): LoopHistory {
    val installed = try { installedApps() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    val labels = installed.toMap()
    val matched = knowledge?.matchTask(opening.task, Int.MAX_VALUE, installed).orEmpty()
    stages?.prepare(latestWords, installed, matched)
    val index = if (skills != null && recommendSkills) GuideRecommender.index(skills, opening.task) else null
    val start = ctx.start(
        opening.task, priorTurns = opening.context?.let { (it.parts.single() as Part.Text).text }?.let { prior ->
            // 之前几轮读到的东西查得到时说一句(只在有记过运行的轮次、提供了 history_read 时)
            if (tools.definitions().any { it.name == HISTORY_READ }) Feedback.format("history_hint", "turns" to prior) else prior
        },
        memory = listOfNotNull(ProfileContext.render(profileSnapshot), opening.memoryMessage).takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
        observed = observed,
        deskNote = deskNote,
        channel = if (knowledge != null) channelLine(matched) else null,
        knowledge = matched, knowledgeNote = toolsNote(tools.scriptsOffered()), withLinks = flags.deepLinks,
        skillsIndex = index,
        taskImages = opening.screen?.images.orEmpty(), screens = opening.screen?.details.orEmpty(),
    )
    val history = start.messages.toMutableList()
    // 带了「从通知里得来的」那一段:记一笔(只记长度)。给了就算,不看最后放没放得下
    observed?.let { emit(RunEvent.State(now(), ObservedContext.RUN_STATE, it.length.toString())) }
    start.knowledge.filter { it.trial }.forEach { emit(RunEvent.State(now(), "knowledge_trial_used", it.name)) }
    // 链接只在 open_link 可用时给,且只放行真的给了模型的那几份
    if (flags.deepLinks) start.knowledge.forEach { if (it.links.isNotEmpty()) tools.allowLinks(it.links) }
    return LoopHistory(history, labels)
}

/** 前台 App/站点的参考资料补进历史(确定性,不靠关键词猜);技能开局已全部列出,这里不再补。有屏图的 App 再随 APP.md 接一段页面结构。 */
internal suspend fun AgentLoop.injectKnowledge(history: MutableList<Message>, e: KnowledgeEntry?, pkg: String?) {
    ctx.knowledge(e, toolsNote(tools.scriptsOffered()), flags.deepLinks)?.let { m ->
        if (flags.deepLinks && e!!.links.isNotEmpty()) tools.allowLinks(e.links)
        history += m
        if (e?.trial == true) emit(RunEvent.State(now(), "knowledge_trial_used", e.name))
    }
    // 屏图只试一次:去重在前,没接线、试过或没图都不再读屏读盘
    if (pkg == null || screenMaps == null || !ctx.firstScreenMapAttempt(pkg)) return
    val graph = screenMaps.load(pkg)
    if (graph == null || graph.nodes.isEmpty()) return
    val skeleton = tools.interfaces.peek()?.let { s -> RouteAnchors.texts(s).toSet() }
    ScreenMapRender.renderMapFor(graph, skeleton)?.let { map ->
        history += ctx.screenMap(pkg, map)
    }
}

/**
 * 这一步的运行时状态接在本步最后一条消息的末尾(还没发过的那条),和这一步一起被裁剪、折叠;
 * 发过的消息一概不改,请求前缀逐步只增不变。最后一条是助手消息时(不会发生在正常流程里)单独追加一条。
 * 网页上的版本从浏览器当前标签实际用的版本读。
 */
internal suspend fun AgentLoop.appendRuntimeState(history: MutableList<Message>, place: Place?, labels: Map<String, String>, step: Int, pending: String?) {
    val mode = if (place?.kind == PlaceKind.Site) tools.placeMode() else null
    val state = ctx.runtimeState(place, place?.name?.let(labels::get), mode, step, maxSteps, now(), latestWords,
        listOfNotNull(pending, stages?.hint()).takeIf { it.isNotEmpty() }?.joinToString("\n"))
    val last = history.lastOrNull()
    if (last == null || last.role == Role.Assistant) history += Message(Role.User, listOf(state), Author.Mika)
    else history[history.lastIndex] = last.copy(parts = last.parts + state)
}

/**
 * 用户这句话里他这次打的字:屏幕的说明、浏览器页的页面说明、引的那一段和附件行先后去掉(见 [UserWords.typed];几样都带着时都去掉),
 * 都没有时是原话;只发了屏幕、只引了一段、只选了字、只带了附件时为空。
 * 给模型的那句带着固定的英文说明、插话标记,后面还可能接着运行时状态,不能拿来判断用户用的语言。
 */
internal fun AgentLoop.ownWords(raw: String, shown: UserScreenTurns.Rendered?): String = UserWords.typed(shown?.words ?: raw, tools)

internal suspend fun AgentLoop.channelLine(matched: List<KnowledgeEntry>): String? {
    val facts = channelFacts ?: return null
    val f = try { facts() } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
    return ChannelAdvisor.line(matched, tools.skillCatalog, f)
}

/**
 * 插话是独立的真实用户行;只在两步之间追加,绝不改写已经发给模型的消息。
 * 插话带着用户此刻的屏幕时和开局原话一样换好:那句话连同截图,后面跟着屏幕的详情。
 */
internal suspend fun AgentLoop.appendSteers(history: MutableList<Message>, inbox: RunInbox?): Boolean =
    appendSteers(history, inbox?.takeSteers().orEmpty())

internal suspend fun AgentLoop.appendSteers(history: MutableList<Message>, updates: List<RunInbox.UserSteer>): Boolean {
    updates.forEach { update ->
        val shown = screens?.render(update.text)
        val words = ownWords(update.text, shown)
        if (words.isNotBlank()) latestWords = words
        history += Message(Role.User, listOf(Part.Text("$STEER_MARKER\n${shown?.said ?: update.text}")) + shown?.images.orEmpty(), Author.User)
        shown?.details?.forEach { (source, body) -> history += ctx.userScreen(source, body) }
        utterances?.add(UserWords.said(shown?.words ?: update.text, tools))
        emit(RunEvent.State(now(), "steer_received", RunEvent.clip(Redactor.text(update.messageId))))
    }
    return updates.isNotEmpty()
}
