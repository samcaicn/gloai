package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactDrop
import com.wangzan101.mika.core.proactive.ContactPoolData
import com.wangzan101.mika.core.proactive.DigestData
import com.wangzan101.mika.core.proactive.LearnDrop
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeEntry
import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.NoticeJournalData
import com.wangzan101.mika.core.proactive.ObservedContact
import com.wangzan101.mika.core.proactive.TopicPoolData

enum class LearnStatus {
    Off,
    /** 归纳开着,但主动能力没在看守(完全退出期间)。 */
    Paused,
    NoModel,
    NeedsConsent,
    /** 「应用访问」设置读不出来:不知道哪些 App 不让碰,停着;攒下的不动。 */
    Suspended,
    Locked,
    /** 有任务在跑,等它结束。 */
    Busy,
    BudgetUsedUp,
    /** 话题池读不出来(密钥库一时不可用之类):不发,等下次有事时再试。 */
    StoreUnavailable,
    /** 有待归纳的,但还没有哪个 App 到期;或者上一批没归纳成,正停着(见 [LearnTuning.FAILURE_HOLD_MS])。 */
    Waiting,
    Sending,
    Idle,
}

/**
 * 一个 App 的计数(只在内存里,不含任何内容):收下几条、归纳了几条、发了几批、失败几批、认了几条、按原因丢了几条;
 * 联系人归类认了几条、按原因丢了几条(和话题分开算)。
 */
data class LearnFeed(
    val captured: Int = 0,
    val distilled: Int = 0,
    val batches: Int = 0,
    val failed: Int = 0,
    val accepted: Int = 0,
    val dropped: Map<LearnDrop, Int> = emptyMap(),
    val contactsAccepted: Int = 0,
    val contactDropped: Map<ContactDrop, Int> = emptyMap(),
) {
    val droppedTotal: Int get() = dropped.values.sum()
    val contactDroppedTotal: Int get() = contactDropped.values.sum()
}

/** 联系人给页面的样子:都有谁,和「不再记录」里有几个(只有个数)。 */
data class ContactsView(val contacts: List<ObservedContact> = emptyList(), val blocked: Int = 0)

data class LearnState(
    val status: LearnStatus = LearnStatus.Off,
    val buffered: Int = 0,
    val pending: Int = 0,
    val lastDistilledAtMs: Long? = null,
    val feed: Map<String, LearnFeed> = emptyMap(),
    /** 通知日志里现在留着几段通知原文(「当日消息归纳」关着时是 0)。 */
    val journal: Int = 0,
    /** 留着的通知原文出自哪些 App(只有包名):还没归纳出任何东西的 App 也要能单独清掉,见「清空来自它的」。 */
    val journalApps: Set<String> = emptySet(),
)

/** 页面上点开一句分段归纳、看它是照着哪些通知写的([LearnRunner.rawFor])。 */
sealed interface RawNotices {
    /** 还存着的那几段(可能一段都没有:过了保留期,或被清掉了)。 */
    data class Found(val entries: List<NoticeEntry>) : RawNotices
    /** 总开关或「当日消息归纳」关着:通知原文那时就删掉了。 */
    data object Off : RawNotices
    /** 这会儿读不出来(存储、「应用访问」读不出来)。 */
    data object Unavailable : RawNotices
}

/**
 * 话题池放在哪、怎么读写:正式用 [TopicPoolFile],测试给内存里的。读、写、删出错都抛出,由 [LearnRunner] 接住。
 */
interface TopicPoolStore {
    fun load(): TopicPoolData
    fun save(data: TopicPoolData)
    /** 删掉读不出来时另存下来的旧文件:用户删了东西,那些看不到的副本里可能还有。 */
    fun dropBroken()
    /** 整份删掉(文件、临时文件、另存的旧文件):「清空全部」时读不出来也能清。 */
    fun wipe()
}

/**
 * 联系人放在哪、怎么读写:正式用 [ContactPoolFile]。和话题池分开放:联系人读不出来不耽误归纳话题,
 * 页面上对联系人的操作也不用等话题池。反过来不成立:话题池读不出来时这一批不发(联系人是搭在同一个请求上的)。
 */
interface ContactPoolStore {
    fun load(): ContactPoolData
    fun save(data: ContactPoolData)
    /** 同 [TopicPoolStore.dropBroken]。 */
    fun dropBroken()
    /** 同 [TopicPoolStore.wipe]。 */
    fun wipe()
}

/**
 * 通知日志(通知原文,见 [NoticeJournal])放在哪:正式用 [NoticeJournalFile]。读出来没有为 null;读、写、删出错都抛出,由 [LearnRunner] 接住。
 */
interface NoticeJournalStore {
    fun load(): NoticeJournalData?
    fun save(data: NoticeJournalData)
    /** 整份删掉;读不出来时也能删。 */
    fun wipe()
}

/** 当日消息归纳攒下的句子放在哪:正式用 [DigestFile]。读出来没有为空的;读、写、删出错都抛出,由 [LearnRunner] 接住。 */
interface DigestStore {
    fun load(): DigestData
    fun save(data: DigestData)
    /** 整份删掉;读不出来时也能删。 */
    fun wipe()
}

/**
 * 还没落盘的删除:用户要删(关掉「敏感类推断」并删除、清掉某个 App 的、单删一个话题或联系人、一句、某天某周某月的归纳),
 * 当时那一份却读不出来,或者删了却没存上。记下来:读得出来时、下次启动时先补做,存上了才划掉。
 * 不记的话,删了没存上、进程又没了,文件里还是旧的,删掉的东西下次启动又回来了。
 *
 * 正式用 [ProactivePrefs](进程没了也还记着)。里面是包名(清掉这个 App 的),和以冒号开头的记号(包名里不会有冒号):
 * [LearnRunner.PURGE_SENSITIVE],以及单删的那些(只有编号、日子,没有内容;话题和句子的编号是随机的,
 * 联系人的是从 App 和会话名算出来的哈希——偏好文件不加密,这一项存上之前会在里面待一会儿)。记这一笔是尽力而为:
 * 存盘写不下去多半是存储满了,那时偏好多半也写不下去。
 *
 * 话题、联系人这两份是按记下的先后排着的(集合的遍历顺序就是先后),补做时照这个先后做:同一个话题名上,
 * 「删掉」和「并进别的」是后做的算数,合并的目标也得先于并进它的那一步存在。实现要把先后一起存下来。
 */
interface LearnPurges {
    /** 话题池上还没落盘的,按记下的先后。 */
    var topicPurges: Set<String>
    /** 联系人上还没落盘的,按记下的先后。 */
    var contactPurges: Set<String>

    /**
     * 同一个记号记到话题、联系人两份的末尾(一次合并两份都要改)。正式的实现([ProactivePrefs])一次落盘:
     * 分两次写的话,中间进程没了只记上一半,补做时话题并了、联系人上的连接没跟上。
     * 这里的默认写法分两次(先联系人),只给测试里的替身用。
     */
    fun addToTopicsAndContacts(mark: String) {
        contactPurges = contactPurges - mark + mark
        topicPurges = topicPurges - mark + mark
    }
    /** 分段归纳的句子和各级归纳上还没落盘的。 */
    var digestPurges: Set<String>
    /** 通知原文上还没落盘的:要去掉其全部原文的 App 包名,和「某个通知键某一阵的那几段」的记号(只有键的哈希和时间)。 */
    var journalPurges: Set<String>
}
