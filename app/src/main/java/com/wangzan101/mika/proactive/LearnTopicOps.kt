package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactPool
import com.wangzan101.mika.core.proactive.ContactRelation
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.SpanKind
import com.wangzan101.mika.core.proactive.TopicPool
import kotlinx.coroutines.CancellationException
import com.wangzan101.mika.proactive.LearnRunner.Companion.PURGE_SENSITIVE
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_ALL
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_TOPIC
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_CONTACT
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_MERGE
import com.wangzan101.mika.proactive.LearnRunner.Companion.MERGE_INTO
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_NOTE
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_DAY
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_WEEK
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_MONTH

// 学习:主题与联系人的删改合并、清除与删除记录
internal fun LearnRunner.deleteTopic(id: String) {
    val p = loadedPool() ?: return
    if (p.delete(id)) {
        // 先记下来再存:没存上、进程又没了的,下次读出来时补做(见 [LearnPurges])
        val mark = MARK_TOPIC + id
        purges.topicPurges = purges.topicPurges - mark + mark
        topicPurgesApplied = topicPurgesApplied + mark
        topicsGone(setOf(id))
        save(p)
        dropBroken()
    }
}

/**
 * 把话题 [fromId] 并进 [intoId](用户在话题页上做的;规则见 [TopicPool.merge]):事件上、联系人上指着 [fromId] 的改指 [intoId]。
 * 不走删除那条路:并掉的名字不进「不再记录」,连接也不丢。
 *
 * 和删除一样先记下来再存(见 [LearnPurges]),存上了才划掉;没存上、进程又没了的,下次读出来时照记下的先后补做:
 * - 话题那份:不补做的话,文件里两个话题都还在。用户并完接着把并好的那个删了(删除是记着的、会补做),
 *   补做时文件里没有这次合并,并掉的那个就带着它的证据回来了,名字也不在「不再记录」里。
 * - 联系人那份:这会儿读不出来、或改了没存上的,文件里连着的还是并掉的那个 id;等话题那份存上了,
 *   下次整理就把这些连接当成指着空处的去掉了。
 * 这一笔两份一次记上([LearnPurges.addToTopicsAndContacts]):只记上话题那一半的话,补做时联系人上的连接就丢了。
 *
 * 记号里只有两个 id,补做时两个话题都得在文件里。有一个还不在文件里(刚归纳出来、没存上的)时先存一次,还存不上就不并——
 * 并了也记不住:进程一没,目标不在文件里,这次合并补不回来,并掉的那个又回来了。合并分不开,存不下的时候宁可不做。
 *
 * 先存联系人再存话题:中途进程没了,联系人上连着的是还在的那个话题。
 */
internal fun LearnRunner.mergeTopic(fromId: String, intoId: String) {
    val p = loadedPool() ?: return
    if (p[fromId] == null || p[intoId] == null || fromId == intoId) return
    if ((fromId !in savedTopics || intoId !in savedTopics) && !save(p)) {
        log("learn merge not done: topic not saved")
        return
    }
    if (!p.merge(fromId, intoId)) return
    val mark = MARK_MERGE + fromId + MERGE_INTO + intoId
    purges.addToTopicsAndContacts(mark)
    topicPurgesApplied = topicPurgesApplied + mark
    buffer.remapTopic(fromId, intoId)
    val c = contactPool
    if (c == null) {
        // 联系人还没读出来:读出来的那一刻把记着的(连同这一项)做掉;读不出来就留到读得出来时
        loadedContacts()
    } else {
        c.remapTopic(fromId, intoId)
        contactPurgesApplied = contactPurgesApplied + mark
        saveContacts(c)
    }
    save(p)
}

/** 记着的一次合并:并掉的话题 id、并进去的 id;不是合并的记号、写得不对的为 null。 */
internal fun LearnRunner.mergeIn(mark: String): Pair<String, String>? {
    if (!mark.startsWith(MARK_MERGE)) return null
    val ids = mark.removePrefix(MARK_MERGE).split(MERGE_INTO)
    return if (ids.size == 2 && ids[0].isNotEmpty() && ids[1].isNotEmpty()) ids[0] to ids[1] else null
}

/**
 * 清空全部:话题和联系人(联系人不记进「不再记录」,是重来)。哪一份读不出来,就把它的文件整份删掉:
 * 清空不能因为读不出来就什么都没做(联系人那份这样删时,「不再记录」的名单也没了:读不出来,留不住)。
 * 读得出来的照常清、存,另存的旧文件一并删。
 */
internal fun LearnRunner.clearAll() {
    // 正在发的那一批取消,缓冲里没发的也清掉:不然回复回来又把刚清空的加回去,剩下的通知文字过后还会发给模型
    stopRequest()
    buffer.clear()
    val p = loadedPool()
    val topicsCleared: Boolean
    if (p != null) {
        topicsGone(p.all().map { it.id }.toSet())
        p.clear()
        // 清空了却没存上:文件里还是旧的,下次启动又读回来了。那就把文件删掉
        topicsCleared = save(p) || wipe("topics") { store.wipe() }
    } else {
        topicsCleared = wipe("topics") { store.wipe() }
        pool = TopicPool()
        topicsFlow.value = emptyList()
        topicsBlockedFlow.value = 0
    }
    val c = loadedContacts()
    val contactsCleared: Boolean
    if (c != null) {
        // 内存里本来就是空的也照存:可能是之前删了却没存上,文件里还是旧的——这时跳过不存,
        // 下面又把记着的待删划掉,旧的联系人下次启动就回来了
        c.clear()
        contactsCleared = saveContacts(c) || wipe("contacts") { contactStore.wipe() }
    } else {
        contactsCleared = wipe("contacts") { contactStore.wipe() }
        contactPool = ContactPool()
        contactsFlow.value = ContactsView()
    }
    dropBroken()
    // 清空了、落了盘的,没有还要补做的删除。既没存上也没删掉的(文件里还是旧的):记一个「全清」,
    // 内存里已经是空的了,下次存上时划掉;在那之前进程没了的,下次读出来时照它清空(见 purgeTopics、purgeContacts)
    purges.topicPurges = if (topicsCleared) emptySet() else setOf(MARK_ALL)
    topicPurgesApplied = purges.topicPurges
    purges.contactPurges = if (contactsCleared) emptySet() else setOf(MARK_ALL)
    contactPurgesApplied = purges.contactPurges
    // 通知原文和当日消息归纳也清掉(删不掉的,它们各自记着,见 dropJournal、dropDigest)
    dropJournal()
    dropDigest()
    if (host != null) evaluate()
}

/** 关掉「敏感类推断」并选了删除:删掉标了敏感的话题,去掉联系人上「伴侣」的票。当时读不出来的那一份记下来,读得出来时补做。 */
internal fun LearnRunner.deleteSensitive() = purge(PURGE_SENSITIVE)

/** 清掉某个 App 的:话题上它的证据,和它的联系人(同样不记进「不再记录」)。当时读不出来的那一份记下来,读得出来时补做。 */
internal fun LearnRunner.clearApp(packageName: String) = purge(packageName)

/** 删掉一个联系人:同一个会话以后也不再记。 */
internal fun LearnRunner.deleteContact(id: String) {
    loadedContacts()?.let {
        if (it.delete(id)) {
            val mark = MARK_CONTACT + id
            purges.contactPurges = purges.contactPurges + mark
            contactPurgesApplied = contactPurgesApplied + mark
            saveContacts(it)
            dropBroken()
        }
    }
}

/** 用户设关系;null 是交回给 Mika 判断。 */
internal fun LearnRunner.setContactRelation(id: String, relation: ContactRelation?) {
    loadedContacts()?.let { if (it.setRelation(id, relation)) saveContacts(it) }
}

/** 重新允许删掉过的话题(一次全部)。 */
internal fun LearnRunner.allowDeletedTopics() {
    loadedPool()?.let { if (it.allowAgain()) save(it) }
}

/** 重新允许删掉过的联系人(一次全部)。 */
internal fun LearnRunner.allowDeletedContacts() {
    loadedContacts()?.let { if (it.allowAgain()) saveContacts(it) }
}

/**
 * [what] 是 [PURGE_SENSITIVE] 或一个包名:两份里各删一遍。先记进 [purges] 再做,删过的那份存上了才划掉:
 * 所以当时读不出来的、删了却没存上就没了进程的,下次读出来时([loadedPool]、[loadedContacts])都会补做。
 * 存上之后就不再记着,不会过后再做一遍、把这之后新攒的也删掉。
 */
internal fun LearnRunner.purge(what: String) {
    if (what != PURGE_SENSITIVE) {
        // 这个 App 正在发的那一批(归纳话题的、分段归纳的)取消,缓冲里它还没发的也去掉:
        // 不然回复回来又把刚清掉的话题、联系人加回去,剩下的通知文字过后还会发给模型
        if (notesApp == what || buffer.sending().firstOrNull()?.packageName == what) stopRequest()
        buffer.purge { it != what }
        // 「清掉这个 App 的」也清通知原文里它的那些段;日志这时读不出来就整份删(原文不留着等以后补删)。
        // 开关关着时日志本来就删掉了
        if (on() && digest()) {
            if (loadedJournal() == null) {
                // 整份删;删不掉的话 dropJournal 记着,读得出来时补做
                dropJournal()
            } else {
                journalRemove(setOf(what))
            }
        }
        // 句子和各级归纳:开关关着时也还留着,所以照样清。这时读不出来(密钥库一时不可用)的,不整份删——
        // 里面有留两年的月归纳;记下来,读得出来时补做(见 loadedDigest)
        digestRemove(setOf(what), stopRequests = true)
    }
    purges.addToTopicsAndContacts(what)
    // 这一次才读出来的,读出来的那一刻已经把记着的(连同这一项)做掉了
    val hadTopics = pool != null
    val p = loadedPool()
    if (p != null && hadTopics) purgeTopics(p, setOf(what))
    val hadContacts = contactPool != null
    val c = loadedContacts()
    if (c != null && hadContacts) purgeContacts(c, setOf(what))
    dropBroken()
}

internal fun LearnRunner.purgeTopics(p: TopicPool, what: Set<String>) {
    if (what.isEmpty()) return
    val gone = HashSet<String>()
    // 照记下的先后做(见 [LearnPurges]):并完才删的,删的是并好的那个,并进过它的名字要跟着进「不再记录」;
    // 先删了一个名字、后来又把同名的另一个并进别的,这个名字算并掉的。已经存上了的再做一遍什么都不会变:
    // 并掉、删掉的那个 id 不在了,id 也不会再发给别的话题
    for (w in what) when {
        w == MARK_ALL -> { gone += p.all().map { it.id }; p.clear() }
        w == PURGE_SENSITIVE -> gone += p.deleteSensitive()
        w.startsWith(MARK_MERGE) -> mergeIn(w)?.let { (from, into) -> p.merge(from, into) }
        w.startsWith(MARK_TOPIC) -> w.removePrefix(MARK_TOPIC).let { if (p.delete(it)) gone += it }
        // 别的记号不是话题池的事(也不能当包名去清)
        w.startsWith(":") -> {}
        else -> gone += p.clearApp(w)
    }
    topicPurgesApplied = topicPurgesApplied + what
    topicsGone(gone)
    save(p)
}

internal fun LearnRunner.purgeContacts(c: ContactPool, what: Set<String>) {
    if (what.isEmpty()) return
    // 照记下的先后做。记着的合并:连着并掉的那个话题的,改连并进去的那个
    for (w in what) when {
        w == MARK_ALL -> c.clear()
        w == PURGE_SENSITIVE -> c.deleteSensitive()
        w.startsWith(MARK_MERGE) -> mergeIn(w)?.let { (from, into) -> c.remapTopic(from, into) }
        // 不在了也要记进「不再记录」(见 [ContactPool.forget])
        w.startsWith(MARK_CONTACT) -> c.forget(w.removePrefix(MARK_CONTACT))
        w.startsWith(":") -> {}
        else -> c.clearApp(w)
    }
    contactPurgesApplied = contactPurgesApplied + what
    saveContacts(c)
}

/**
 * 在归纳那份上照 [marks] 删一遍(包名:去掉这个 App 的;记号:单删的那一句、那一天、那一周、那个月)。
 * 返回其中真删掉了东西的几项。
 */
internal fun LearnRunner.applyDigestPurges(d: DigestPool, marks: Set<String>): Set<String> = marks.filterTo(HashSet()) { w ->
    when {
        w == MARK_ALL -> d.clear()
        w.startsWith(MARK_NOTE) -> d.delete(w.removePrefix(MARK_NOTE))
        w.startsWith(MARK_DAY) -> w.removePrefix(MARK_DAY).toLongOrNull()?.let(d::deleteDay) ?: false
        w.startsWith(MARK_WEEK) -> w.removePrefix(MARK_WEEK).toLongOrNull()?.let { d.deleteSpan(SpanKind.Week, it) } ?: false
        w.startsWith(MARK_MONTH) -> w.removePrefix(MARK_MONTH).toLongOrNull()?.let { d.deleteSpan(SpanKind.Month, it) } ?: false
        w.startsWith(":") -> false
        else -> d.removePackages { it != w }
    }
}

/**
 * 从归纳那份里删东西([marks] 的写法见 [applyDigestPurges])。删掉了什么就先记进 [purges] 再存,存上了才划掉(见 [saveDigest]):
 * 没存上、进程又没了的,下次读出来时补做([loadedDigest])。这时读不出来的整个记下来,读得出来时补做。
 * 什么都没删掉、但其中有上回没存上的:再存一次。[stopRequests]:删掉了东西就取消在做的当日(周、月)归纳。
 */
internal fun LearnRunner.digestRemove(marks: Set<String>, stopRequests: Boolean = false) {
    if (marks.isEmpty()) return
    val pending = purges.digestPurges
    val d = loadedDigest()
    if (d == null) {
        if (!pending.containsAll(marks)) purges.digestPurges = pending + marks
        return
    }
    val done = applyDigestPurges(d, marks)
    if (done.isNotEmpty()) {
        if (stopRequests && (dayInFlight != null || spanInFlight != null)) stopRequest()
        if (!purges.digestPurges.containsAll(done)) purges.digestPurges = purges.digestPurges + done
        digestPurgesApplied = digestPurgesApplied + done
        saveDigest(d)
    } else if (marks.any { it in purges.digestPurges }) {
        saveDigest(d)
    }
}

/** 用户删了东西:两份另存的旧文件都删掉(读不出来的副本里可能还有刚删的)。删不掉只记一笔。 */
internal fun LearnRunner.dropBroken() {
    wipe("topics copies") { store.dropBroken() }
    wipe("contacts copies") { contactStore.dropBroken() }
}

/** 删掉了返回 true;删不掉只记一笔,返回 false。 */
internal inline fun LearnRunner.wipe(what: String, block: () -> Unit): Boolean =
    try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn could not delete $what: ${e.javaClass.simpleName}")
        false
    }
