package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.learn.memory.SensitiveFilter

/**
 * 联系人身份怎么算。用的是 [Title];另两种留着,是为了认得出以前按它们算出来的 id(存着的联系人、「不再记录」的名单)。
 *
 * 为什么不用通知键:键是发通知的 App 自己定的,不保证一个会话一个。真机上看到两种都有:有的 App 一个会话一个固定的键;
 * 有的 App 的通知 id 顺着往上加,同一个群隔一会儿就是另一个键。没测过的 App 是哪一种无从知道,所以不拿它认会话。
 */
enum class ContactIdStrategy(val prefix: String) {
    /** 只看通知键(不再用):要求键一个会话一个,而这一点不成立。 */
    Key("k"),
    /**
     * 只看会话名(现在用的):同一个 App 里名字一样的就是同一个联系人。名字是通知给的、去掉了未读条数的会话名
     * (带会话样式的群聊用系统另给的会话名,见 [NoticeShapes])。
     * 代价:同一个 App 里两个名字完全一样的会话会并成一个;陌生人把昵称改成和某个联系人一样,也会并进去。
     * 联系人只带名字和关系进任务,不带消息内容,这个代价可以接受。用户改了对方的备注名,就当成一个新的联系人从头算。
     */
    Title("t"),
    /** 键和会话名都看(以前用的):键一变就成了另一个联系人,见上。 */
    KeyAndTitle("b"),
}

/**
 * 联系人的身份由我们自己算,不由模型起名:只用(包名、通知键、标题)这三样,一个纯函数。
 * id 是 SHA-256 取前几位十六进制(和 [NoticeDedup.idOf] 同一个办法),前面带上策略的字母:id 里没有名字,也倒推不出键,
 * 换策略时旧的 id(包括「不再记录」里的)也不会和新的撞上。
 *
 * 已知的局限:群聊里的各个成员不单独记,一个群就是一个「群」类型的联系人。不用系统会话样式、又在标题里带着会变的东西
 * (这里认得的未读条数之外的写法)的 App,还是会把一个会话拆开:见到一种补一种([withoutUnreadCount])。
 */
object ContactIdentity {
    /** 算出来的身份;[name] 是这条通知能给的显示名(清洗过、过了敏感信息关卡),给不出时为 null。 */
    data class Identity(val id: String, val name: String?)

    // 真机上看到的:有的 App 的标题是「群名(2条新消息)」,条数一变标题就变。条数多了还会写成「99+」「99条以上」
    private val UNREAD_COUNT = Regex("\\s*[(（]\\s*[0-9]+\\+?\\s*条(?:以上)?新消息\\s*[)）]$")
    // 以前认的写法(没有「99+」):只用来重算以前的 id,不能改
    private val LEGACY_UNREAD_COUNT = Regex("\\s*[(（]\\s*[0-9]+\\s*条(?:以上)?新消息\\s*[)）]$")

    /**
     * 去掉标题结尾的未读条数(「(2条新消息)」这一类写法):它不是名字的一部分。不去掉的话,同一个会话每多一条未读就成了另一个联系人,
     * 永远攒不够天数,用户删掉的也会换个条数再回来。只认带着「条新消息」的结尾,名字里别处的括号和数字不动。
     */
    internal fun withoutUnreadCount(cleanTitle: String): String = UNREAD_COUNT.replace(cleanTitle, "")

    /**
     * 会话名:清洗、去掉结尾的未读条数、截到 [LearnTuning.CONTACT_NAME_MAX]。显示名和身份都从这一个字符串来——
     * 存着的联系人只有显示名,要能从它算出和新通知一样的身份(把以前的条目并过来时靠的就是这个,见 [ContactPool.adoptIdentities])。
     */
    internal fun conversationName(title: String): String =
        NoticeFilter.cap(withoutUnreadCount(NoticeFilter.clean(title, 1_000)), LearnTuning.CONTACT_NAME_MAX).trim()

    // 真机上看到的:有的 App 的会话名结尾带着一个括号里的数(「群名（1,234）」),同一个群隔一会儿数就变了
    private val TRAILING_COUNT = Regex("\\s*[(（]\\s*[0-9][0-9,，.]*\\+?\\s*[)）]$")

    /**
     * 认会话用的名字:和 [conversationName] 一样清洗、去掉未读条数,再去掉结尾括号里的数,然后才截断;比较时不分大小写、全半角、空白。
     * 那个数留在显示名里(它可能就是名字的一部分,「项目群(2)」),只是不拿它分会话:带着它认的话,数一变同一个群就成了另一个联系人。
     * 代价:「项目群(2)」和「项目群(3)」会并成一个。
     * 先去数、后截断:反过来的话,名字加上那个数刚好超过上限时,括号被截掉一半就认不出来了,数一变还是另一个联系人。
     * 存着的显示名是先带着那个数截断的:名字连同那个数都没超过上限时,从它算出来的和从通知算出来的一样;名字本身就超过上限的也一样
     * (截出来的是同一段开头)。只有夹在中间的(名字没超、加上数才超)不一样:显示名里的括号被截掉了一半。以前存下来的这种条目
     * 换身份时并不到新通知的那一条上,留着等它淡忘。
     */
    internal fun identityName(title: String): String {
        val full = withoutUnreadCount(NoticeFilter.clean(title, 1_000))
        return TopicPool.normalize(NoticeFilter.cap(TRAILING_COUNT.replace(full, ""), LearnTuning.CONTACT_NAME_MAX).trim())
    }

    /** 算不出身份(要看会话名的策略下会话名是空的)为 null。 */
    fun of(packageName: String, key: String, title: String, strategy: ContactIdStrategy = LearnTuning.CONTACT_ID): Identity? {
        val name = displayName(title)
        val material = when (strategy) {
            ContactIdStrategy.Key -> key
            ContactIdStrategy.Title -> identityName(title).ifEmpty { return null }
            // 以前的算法原样留着:按它算出来的 id 还存在「不再记录」的名单里
            ContactIdStrategy.KeyAndTitle -> key + "\n" +
                TopicPool.normalize(LEGACY_UNREAD_COUNT.replace(NoticeFilter.clean(title, LearnTuning.TITLE_MAX), "")).ifEmpty { return null }
        }
        return Identity(strategy.prefix + NoticeDedup.idOf("contact\u0000$packageName\u0000$material"), name)
    }

    /** 存着的一个联系人按 [strategy] 该是哪个 id:只有显示名可用,所以只对只看会话名的策略算得出来。 */
    internal fun ofStored(packageName: String, name: String, strategy: ContactIdStrategy = LearnTuning.CONTACT_ID): String? =
        if (strategy == ContactIdStrategy.Title) of(packageName, "", name, strategy)?.id else null

    /**
     * 显示名:按通知文字的办法清洗,截到 [LearnTuning.CONTACT_NAME_MAX]。空的为 null;和新话题名一样,像网址、有一长串数字、
     * 过不了 [SensitiveFilter] 的也为 null(对没截短的整段查):标题就是一个电话号码的发件人、带单号的推送,不当名字存下来。
     * 所以只显示成号码的发件人不会成为联系人(第一次见就给不出名字的不记)。
     */
    fun displayName(title: String): String? {
        val full = withoutUnreadCount(NoticeFilter.clean(title, 1_000))
        if (full.isEmpty()) return null
        val normalized = LearnText.nfkc(full)
        if (LearnText.urlLike(normalized) || LearnText.longDigits(normalized) || LearnText.sensitive(full)) return null
        return conversationName(title).ifEmpty { null }
    }
}
