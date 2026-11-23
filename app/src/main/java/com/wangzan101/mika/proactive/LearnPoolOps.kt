package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactPool
import com.wangzan101.mika.core.proactive.TopicPool
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job

// 学习:主题池与联系人池的读写、额度与状态发布
/** 话题池没读出来时读一次;读不出来(密钥库一时不可用、IO)为 null,下次再试,绝不拿空池去盖文件。 */
internal fun LearnRunner.loadedPool(): TopicPool? {
    pool?.let { return it }
    return try {
        TopicPool(store.load()).also {
            pool = it
            savedTopics = it.all().mapTo(HashSet()) { t -> t.id }
            topicsFlow.value = it.all()
            topicsBlockedFlow.value = it.blockedCount
            purgeTopics(it, purges.topicPurges)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn pool unavailable: ${e.javaClass.simpleName}")
        null
    }
}

/** 联系人没读出来时读一次;读不出来为 null,下次再试,绝不拿空的去盖文件。 */
internal fun LearnRunner.loadedContacts(): ContactPool? {
    contactPool?.let { return it }
    return try {
        ContactPool(contactStore.load()).also {
            contactPool = it
            contactsFlow.value = ContactsView(it.all(), it.blockedCount)
            purgeContacts(it, purges.contactPurges)
            // 以前按通知键存下来的:同名的并成一条(要在上面补做完记着的删除之后,那些记的是以前的 id)
            if (it.adoptIdentities()) saveContacts(it)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn contacts unavailable: ${e.javaClass.simpleName}")
        null
    }
}

/** 存上了返回 true。 */
internal fun LearnRunner.saveContacts(c: ContactPool): Boolean {
    contactsFlow.value = ContactsView(c.all(), c.blockedCount)
    return try {
        contactStore.save(c.data())
        // 做过的补删这下落盘了
        if (contactPurgesApplied.isNotEmpty()) {
            purges.contactPurges = purges.contactPurges - contactPurgesApplied
            contactPurgesApplied = emptySet()
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn contacts save failed: ${e.javaClass.simpleName}")
        false
    }
}

/** 存上了返回 true。 */
internal fun LearnRunner.save(p: TopicPool): Boolean {
    topicsFlow.value = p.all()
    topicsBlockedFlow.value = p.blockedCount
    return try {
        store.save(p.data())
        savedTopics = p.all().mapTo(HashSet()) { it.id }
        // 做过的补删这下落盘了
        if (topicPurgesApplied.isNotEmpty()) {
            purges.topicPurges = purges.topicPurges - topicPurgesApplied
            topicPurgesApplied = emptySet()
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 存不下也不丢内存里的,下次有改动时再存
        log("learn pool save failed: ${e.javaClass.simpleName}")
        false
    }
}

internal fun LearnRunner.budgetLeft(now: Long): Boolean = budgetOutDay != dayOf(now) &&
    // 归纳按整份预算算(判定只用其中一部分,见 [ProactiveBudget.triageExhausted])
    ProactiveBudget.exhausted(hooks.tokensToday(), hooks.callsToday(), budgetLimit()) == null

internal fun LearnRunner.dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toEpochDay()

internal fun LearnRunner.stopRequest() {
    val job = inFlight ?: return
    inFlight = null
    notesApp = null
    dayInFlight = null
    spanInFlight = null
    job.cancel()
    buffer.abort()
    log("learn request cancelled")
    slot.release(this)
    slot.wakeOthers(this)
}

internal fun LearnRunner.stopAndPublish(s: LearnStatus) {
    stopRequest()
    publish(s)
}

internal fun LearnRunner.bump(pkg: String, f: (LearnFeed) -> LearnFeed) {
    feed[pkg] = f(feed[pkg] ?: LearnFeed())
    publish(status)
}

internal fun LearnRunner.publish(s: LearnStatus) {
    status = s
    val summary = buffer.state.value
    mutable.value = LearnState(s, summary.total, summary.pending, lastDistilledAtMs, HashMap(feed), journal?.size ?: 0, journal?.packages().orEmpty())
}
