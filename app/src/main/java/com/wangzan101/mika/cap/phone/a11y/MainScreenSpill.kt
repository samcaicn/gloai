package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityEvent

/**
 * 判 Agent 的一个动作是不是让一个 App 开到了用户的主屏上。App 不是从自己在 Mika 屏上的页面、而是从后台
 * (服务、通知的跳转、系统的选择器、跨用户的转交)打开另一个 App 时,系统按默认放到主屏;Mika 的屏上看不出变化,
 * 模型会以为没点上、一遍遍再点。认出来就挪回 Mika 的屏(见 AndroidScreen)。
 *
 * 只看动作开始之后一小段时间里主屏最上面换上来的任务(内置助手记下的,见 MainScreenLog)。不算:
 * 不在这段时间里的;桌面、最近任务一类的页面,Mika 自己,桌面 App([excluded]);从别的屏挪过来的(接管、别的任务的接管);
 * 此刻已不在主屏上的;这段时间里用户在主屏上点过东西,或先回过桌面再从桌面/通知栏打开(当作用户自己开的);
 * 动作之前就在主屏最上面的那个任务又回到最上面(挪走开出来的那个后露出来的)。
 * 动作之前主屏最上面的任务里换了页面:是用户正在用的那个 App 被打开了,开在了用户眼前,不去动它([InUse])。
 * 冒出来的任务用户已经在点了(点的那个窗口属于它):也是用户在用,不挪。
 * 纯逻辑。
 */
internal object MainScreenSpill {
    const val MAIN = 0
    const val NEW = -1

    /** 主屏冒到最上面的一个任务;[from] 上一次看到它时在哪块屏(之前没见过为 [NEW]),[now] 此刻在哪块屏(不在了为 -1)。 */
    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val now: Int,
        val topActivity: String? = null,
    )

    /** 用户在主屏上点了一下:[at] 时刻,[packageName] 是点的那一下所在的包(读不到为 null)。 */
    data class Tap(val at: Long, val packageName: String?)

    sealed interface Verdict { val arrival: Arrival }
    /** 动作开到主屏上的:挪回 Mika 的屏。 */
    data class PullBack(override val arrival: Arrival) : Verdict
    /** 开到了用户正在主屏上用着的那个 App 里:不动它,告诉模型。 */
    data class InUse(override val arrival: Arrival) : Verdict

    /** 主屏上的这次事件能证明用户在亲手操作;系统触摸事件不要求 App 另发控件点击事件。 */
    fun isUserTap(eventType: Int): Boolean = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
        -> true
        else -> false
    }

    /**
     * [since] 动作开始的时刻,[windowMs] 之后冒出来的不算这个动作的;[topBefore] 动作开始时主屏最上面的任务;
     * [userTaps] 用户在主屏上点东西的时刻和包;[userActiveBefore] 动作开始前用户刚在主屏上动过手(见 ScreenSpill 的计数基线),
     * 归属说不清时不拉回。同一个任务只出一条(取最后一次),按先后排。
     */
    fun decide(
        arrivals: List<Arrival>,
        since: Long,
        windowMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        userTaps: List<Tap>,
        homePackages: Set<String> = emptySet(),
        userActiveBefore: Boolean = false,
    ): List<Verdict> {
        val ordered = arrivals.sortedBy { it.at }
        val homeReturns = ordered.filter { it.packageName in homePackages }.map { it.at }
        val out = LinkedHashMap<Int, Verdict>()
        for (a in ordered) {
            val v = verdict(a, since, windowMs, topBefore, excluded, userTaps, homeReturns, userActiveBefore) ?: continue
            out.remove(a.taskId)
            out[a.taskId] = v
        }
        return out.values.toList()
    }

    enum class Handling {
        /** 挪回 Mika 的屏(只挪任务,打开它的仍是原来那个 App)。 */
        Move,
        /** 系统在问用户打开哪一个:不挪,留给用户在自己屏上选,任务等着。 */
        LeaveForUser,
    }

    /**
     * 开到主屏上的这个怎么办:系统包的窗口,而且是系统的选择器、或窗口里的选项对得上装了两份的 App([optionsMatch]),
     * 就是在问打开哪一个,留给用户;其余挪回。Mika 从不替别的 App 重新打开它。
     */
    fun handling(systemPackage: Boolean, systemPicker: Boolean, optionsMatch: Boolean): Handling =
        if (systemPackage && (systemPicker || optionsMatch)) Handling.LeaveForUser else Handling.Move

    /**
     * 用户在主屏的选择框里选完(选择框在 [gone] 时已经不在)后开出来的那个:选择框之后冒到主屏上的(不算选择框自己 [chooserTask]
     * 和它出来前主屏最上面的 [topBefore]);用户刚点过选项,不按「用户点过」排除。看选择框关没关是隔一会儿看一次的,
     * 关掉之前 [slackMs] 内冒出来的也算。没有为 null。
     */
    fun picked(
        arrivals: List<Arrival>,
        chooserTask: Int,
        gone: Long,
        slackMs: Long,
        waitMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        homePackages: Set<String> = emptySet(),
    ): Arrival? =
        decide(arrivals.filter { it.taskId != chooserTask }, gone - slackMs, waitMs + slackMs, topBefore, excluded, emptyList(), homePackages)
            .filterIsInstance<PullBack>().lastOrNull()?.arrival

    /**
     * 选出来的 App 要用户亲手处理:窗口里有密码框,或树里读不出东西而主屏截图报防截屏窗口(付款、输密码一类页面常这样)。
     * 只是树读不出(有的 App 树本来就很少)不算。
     */
    fun needsUser(passwordField: Boolean, readable: Boolean, secureWindow: Boolean): Boolean = passwordField || (!readable && secureWindow)

    /**
     * 这个包是不是系统的账号登录服务:在认证器[authenticators]里,自己又没有桌面图标([hasLauncher] 为 false)。
     * 这种包冒到主屏上的只会是加账号、登录、验证一类页面,要用户亲手做,整屏、没有密码框也不能挪到 Mika 的屏上。
     * 有桌面图标的认证器包(同步联系人的聊天 App、邮箱 App)只是顺带登记了账号,它的页面照常按别的判据走。纯逻辑。
     */
    fun authenticatorUi(pkg: String, authenticators: Set<String>, hasLauncher: Boolean): Boolean =
        pkg in authenticators && !hasLauncher

    private fun verdict(
        a: Arrival,
        since: Long,
        windowMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        userTaps: List<Tap>,
        homeReturns: List<Long>,
        userActiveBefore: Boolean,
    ): Verdict? {
        if (a.at < since || a.at > since + windowMs) return null
        val pkg = a.packageName ?: return null
        if (!a.standard || pkg in excluded) return null
        if (a.from != NEW && a.from != MAIN) return null
        if (a.now != MAIN) return null
        // 用户先回主屏桌面，再从桌面或通知栏打开 App：这条导航链不依赖控件是否另发点击事件
        if (homeReturns.any { it in since..a.at }) return null
        if (userTaps.any { it.at in since..a.at }) return null
        // 动作开始前用户刚在主屏上动过手(两次动作之间,只有计数、没有具体时刻):这次冒出来的说不清是谁开的,
        // 保守起见不拉回,报成用户在用,免得抢走用户正在用的 App(#798/#651)
        if (userActiveBefore) return InUse(a)
        // 用户已经在点它了(出现之后点在这一个 App 的窗口上):留给用户,不挪
        if (userTaps.any { it.at >= a.at && it.packageName == pkg }) return InUse(a)
        if (a.taskId == topBefore) return if (a.sameTask) InUse(a) else null
        return PullBack(a)
    }
}
