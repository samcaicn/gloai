package com.wangzan101.mika.task

import com.wangzan101.mika.cap.web.TabBudget
import com.wangzan101.mika.core.screen.uistate.AppWait
import com.wangzan101.mika.takeover.TakeoverQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.locks.ReentrantLock

/** 进程内的共享资源入口。TaskCenter 管运行队列，这里管运行之间会互相争用的资源。 */
class ResourceScheduler(
    val tabBudget: TabBudget = TabBudget(),
    val takeoverQueue: TakeoverQueue = TakeoverQueue(),
    val clipboard: ReentrantLock = ReentrantLock(true),
) {
    /** App(包名,另一份用户的写成「包名@用户」)→ 占着它的任务。 */
    private val apps = HashMap<String, String>()
    /** 正在等别的任务放开 App 的任务 → 它等的那个 App。 */
    private val waiting = HashMap<String, String>()
    /** 有任务放开了它占着的 App 就加一:等着的任务据此马上再看一眼,不用等到下一次轮询。 */
    private val released = MutableStateFlow(0L)

    /** 占用与检查在同一把锁里；App 已被另一任务释放或关闭时允许接管。 */
    @Synchronized
    fun reserveApp(packageName: String, owner: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean {
        if (!appFree(packageName, owner, holderActive, stillUsed)) return false
        apps[packageName] = owner
        return true
    }

    /** [owner] 此刻能不能占 [packageName]:没人占、自己占着、占着的任务已经不在或不再用它([stillUsed] 为假)。只查不占。 */
    @Synchronized
    fun appFree(packageName: String, owner: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean {
        val holder = apps[packageName]
        return holder == null || holder == owner || !holderActive(holder) || !stillUsed()
    }

    @Synchronized fun releaseOwner(owner: String) {
        waiting.remove(owner)
        if (apps.values.removeAll { it == owner }) released.value++
    }

    @Synchronized fun appHolder(packageName: String): String? = apps[packageName]

    /**
     * [owner] 等 [packageName] 被放开,最多 [timeoutMs]。[free] 是调用方的完整判断(登记之外还看别的屏上有没有它),
     * 每 [pollMs] 看一次,有任务放开 App 时马上再看。只等不占:等到了调用方自己再去占、去开。
     *
     * 互相等的不等:占着它的任务(顺着「谁在等谁手里的 App」一路找下去)最后在等 [owner] 手里的 App 时,
     * 返回 [AppWait.WouldDeadlock];查与登记在同一把锁里,两个任务同时开始等时后来的那个看得见先来的。
     * [holderActive] 认占着的任务还在不在(不在的不算占着)。
     */
    suspend fun awaitApp(
        packageName: String, owner: String, timeoutMs: Long,
        holderActive: (String) -> Boolean, free: suspend () -> Boolean, pollMs: Long = POLL_MS,
    ): AppWait {
        if (!startWaiting(owner, packageName, holderActive)) return AppWait.WouldDeadlock
        try {
            return withTimeoutOrNull(timeoutMs) {
                while (!free()) {
                    val seen = released.value
                    withTimeoutOrNull(pollMs) { released.first { it != seen } }
                }
                AppWait.Free
            } ?: AppWait.TimedOut
        } finally {
            stopWaiting(owner)
        }
    }

    @Synchronized private fun startWaiting(owner: String, packageName: String, holderActive: (String) -> Boolean): Boolean {
        if (wouldDeadlock(owner, packageName, holderActive)) return false
        waiting[owner] = packageName
        return true
    }

    @Synchronized private fun stopWaiting(owner: String) {
        waiting.remove(owner)
    }

    /** 从 [packageName] 的占用者起,顺着「它在等谁手里的 App」往下找,绕回 [owner] 就是互相等。 */
    private fun wouldDeadlock(owner: String, packageName: String, holderActive: (String) -> Boolean): Boolean {
        fun holderOf(pkg: String) = apps[pkg]?.takeIf(holderActive)
        var holder = holderOf(packageName)?.takeIf { it != owner } ?: return false
        val seen = HashSet<String>()
        while (seen.add(holder)) {
            val next = holderOf(waiting[holder] ?: return false) ?: return false
            if (next == owner) return true
            holder = next
        }
        return false
    }

    private companion object {
        /** 别的屏上的 App 自己关掉不会通知这里:隔这么久再看一眼。 */
        const val POLL_MS = 1_000L
    }
}
