package com.wangzan101.mika.core.learn.memory

import java.text.Normalizer

/**
 * 用户本人在这次任务里说的话:开局、插话和追问的回答里他自己的话(带的屏幕、浏览器页的页面说明、引的 Mika 的回答和附件行不算,
 * 引的他自己说过的话算)、接管交回时的备注。
 * 记忆的 `quote` 必须是其中某句的子串(全半角统一、去首尾空白后比较)——屏幕、网页、工具结果里的字不算。
 */
class UserUtterances {
    private val texts = mutableListOf<String>()
    /** 唯一可跨轮引用的原话:上一轮用户明确确认了再上一轮助手的建议/提问。 */
    private var previousConfirmation: String? = null

    @Synchronized fun add(text: String) {
        if (text.isNotBlank()) texts += text
    }

    /** 本轮用户的话;自动提取也只用它,不把上一轮确认当成这轮又说了一遍。 */
    val all: List<String> @Synchronized get() = texts.toList()

    /**
     * 登记仅有的跨轮例外。[confirmation] 是上一轮用户自己的话,[proposal] 是紧挨着它之前那轮助手的回答。
     * 建议/提问与明确肯定都要由原文看出来;否定、纠错、普通陈述和更早轮次一律不登记。
     */
    @Synchronized fun addPreviousConfirmation(confirmation: String, proposal: String, proposalStatus: String) {
        previousConfirmation = null
        if (proposalStatus != "succeeded" && proposalStatus != "unverified") return
        if (!proposal.contains('?') && !proposal.contains('？') && !PROPOSAL.containsMatchIn(proposal)) return
        val text = confirmation.trim()
        if (text.isEmpty() || NEGATION.containsMatchIn(text) || !CONFIRMATION.containsMatchIn(text)) return
        previousConfirmation = text
    }

    /** 每次任务开始清空:上一个任务里说的话不算这次的,跨轮确认也必须按本次上文重新登记。 */
    @Synchronized fun clear() {
        texts.clear()
        previousConfirmation = null
    }

    /**
     * [content] 里的具体值在 [quote] 和用户本次说的话里都找不到的那些:模型转述时从屏幕上抄来的值。
     * 具体值 = 引号内的内容、数字(中文数字、分隔开的数字先统一成一串阿拉伯数字)、3 个字母以上的英文/编号、
     * 地址片段(地名标志字连同前两个字,如「张江路」「一品苑」;只取两个字,模型多补一个区名不至于误拒)。
     * 标志字里单个字的那些(道、里、园、市、号…)也是很多普通词的结尾或开头:「知道」「公园」「城市」不是地址,
     * 转述时换个说法带出它们不算补了具体值([ordinaryWord])。
     * 比较前两边都去掉「用户(的)」「我(的)」,免得第三人称转述本身造成误拒。
     *
     * 局限:没加引号的中文人名、无标志字的地名认不出来(「发给王小明」);这类靠提示词和结果卡片上用户过目。
     */
    @Synchronized fun unsupportedSpecifics(content: String, quote: String): List<String> =
        unsupportedSpecifics(content, quote, texts)

    /**
     * 改一条记忆时用:同 [unsupportedSpecifics],另外把 [known](那一条原来的内容和关键词)里有的也算数——
     * 原样带过来的具体值不是新补的。「把那条改得简洁一点」时用户不会把里面的地址、数字再说一遍。
     */
    @Synchronized fun unsupportedEditSpecifics(content: String, quote: String, known: List<String>): List<String> =
        unsupportedSpecifics(content, quote, texts + known)

    /** memory_save 只有引文本身来自上一轮确认时才让那段话参与具体值核对;普通本轮写入保持原校验。 */
    @Synchronized fun unsupportedMemorySpecifics(content: String, quote: String): List<String> {
        val sources = if (matches(quote, texts)) texts else texts + listOfNotNull(previousConfirmation)
        return unsupportedSpecifics(content, quote, sources)
    }

    private fun unsupportedSpecifics(content: String, quote: String, sources: List<String>): List<String> {
        val c = comparable(content)
        val said = (sources + quote).map(::comparable)
        val specifics = QUOTED.findAll(c).map { it.groupValues[1] } + DIGITS.findAll(c).map { it.value } +
            LATIN.findAll(c).map { it.value } + PLACE.findAll(c).filterNot { ordinaryWord(c, it) }.map { it.value }
        return specifics.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .filter { v -> said.none { it.contains(v, ignoreCase = true) } }.toList()
    }

    private fun comparable(s: String) = PERSON.replace(NumberText.collapse(s), "")

    /**
     * [PLACE] 命中的这一段其实是个普通词,不是地址:以单个标志字结尾的常用词(「知道」「公园」「手机号」),
     * 或者标志字其实是后面那个词的开头(「城市」里的「城」、「里面」里的「里」)。
     * 名字正好以这些词结尾的地方(「朝阳公园」)因此认不出来;地址里通常还带着路名、门牌号,那些照查。
     */
    private fun ordinaryWord(text: String, m: MatchResult): Boolean {
        val v = m.value
        if (v.length != 3) return false
        if (v.substring(1) in ORDINARY_ENDINGS || v in ORDINARY_ENDINGS) return true
        val next = text.getOrNull(m.range.last + 1) ?: return false
        return "${v[2]}$next" in ORDINARY_STARTS
    }

    /** 不足 2 个字的引文不算数:一两个字在哪句话里都找得到。普通写入只认本轮。 */
    @Synchronized fun contains(quote: String): Boolean = matches(quote, texts)

    /** 改删记忆时用完整原话检查引文的上下文，例如引文前的否定。 */
    @Synchronized internal fun matchingCurrentMessages(quote: String): List<String> =
        matchingMessages(quote, texts).toList()

    /**
     * 引文落在本轮哪句话里、压着其中哪几个分句(按句读切开):每句话一组;本轮没有哪句含着引文时为空。
     * 「别记我的地址,记住我喜欢喝茶」里引「别记我的地址」只压着前半句——后半句的「记住」不能借给它。
     */
    @Synchronized internal fun quotedClauses(quote: String): List<List<String>> {
        val q = norm(quote)
        if (q.length < MIN_QUOTE) return emptyList()
        return texts.map(::norm).mapNotNull { m ->
            val hits = generateSequence(m.indexOf(q)) { at -> m.indexOf(q, at + 1) }.takeWhile { it >= 0 }.toList()
            if (hits.isEmpty()) null
            else clauses(m).filter { c -> hits.any { at -> c.first < at + q.length && at <= c.last } }.map { m.substring(it.first, it.last + 1) }
        }
    }

    private fun clauses(m: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        for (end in CLAUSE_END.findAll(m)) {
            if (end.range.first > start) out += start until end.range.first
            start = end.range.last + 1
        }
        if (start < m.length) out += start until m.length
        return out
    }

    /** memory_save 另认已登记的上一轮明确确认;助手建议本身永远不是可引用的用户原话。 */
    @Synchronized fun containsMemoryQuote(quote: String): Boolean =
        matches(quote, texts + listOfNotNull(previousConfirmation))

    private fun matches(quote: String, sources: List<String>): Boolean = matchingMessages(quote, sources).any()

    private fun matchingMessages(quote: String, sources: List<String>): Sequence<String> {
        val q = norm(quote)
        if (q.length < MIN_QUOTE) return emptySequence()
        return sources.asSequence().map(::norm).filter { it.contains(q) }
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).trim()

    private companion object {
        const val MIN_QUOTE = 2
        // 句读;英文句号只在后面是空白或结尾时算(3.5、example.com 里的点不算)
        val CLAUSE_END = Regex("[,，。;；!！?？\\n]|\\.(?=\\s|$)")
        val CONFIRMATION = Regex("""^(?:(?:嗯+|恩+|呃|哦|噢|啊)[\s,，。.!！~～]*)?(?:好的?|好呀|好嘞|行|可以|对的?|是的?|没错|同意|就这样|就这么|ok(?:ay)?|yes|yeah|yep|sure|correct|exactly|agreed|sounds\s+good|go\s+ahead)(?=$|[\s,，。.!！:：;；~～])""", RegexOption.IGNORE_CASE)
        val NEGATION = Regex("""不|别|算了|取消|但是|不过|\b(?:no|not|don't|do\s+not|stop|cancel|wrong|but)\b""", RegexOption.IGNORE_CASE)
        val PROPOSAL = Regex("""建议|推荐|可以叫|叫我|命名为|起名|名字|要不要|不如|我可以|你觉得|怎么样|suggest|recommend|how\s+about|would\s+you\s+like|shall\s+(?:i|we)|you\s+can\s+call|i\s+(?:can|could)|let['’]s""", RegexOption.IGNORE_CASE)
        val QUOTED = Regex("[「『“\"]([^「」『』“”\"\n]+)[」』”\"]")
        val DIGITS = Regex("\\d+")
        val LATIN = Regex("[A-Za-z][A-Za-z0-9._-]{2,}")
        val PLACE = Regex("\\p{IsHan}{2}(?:小区|大厦|广场|公寓|花园|单元|路|街|巷|弄|镇|村|号|楼|栋|室|区|县|市|省|园|苑|城|道|里|湾)")
        val PERSON = Regex("用户的|用户|我的|我")
        // 以 [PLACE] 里单个标志字结尾、却不是地名的常用词。只收标志字明显属于这个词本身的:「大街」「家园」「大楼」「社区」
        // 这类同时也是真地名后缀的(长安大街、幸福家园)不收,宁可偶尔多拒一次。「一路」「马路」「山区」也不收:
        // 「科技一路」「二马路」「宝山区」都是真地名
        val ORDINARY_ENDINGS = setOf(
            "知道", "味道", "频道", "渠道", "通道", "地道", "报道", "难道", "道路", "思路", "套路", "出路", "销路",
            "这里", "那里", "哪里", "家里", "心里", "手里", "夜里", "屋里", "城里", "公里",
            "城市", "超市", "都市", "上市", "门市", "夜市", "公园", "校园", "乐园", "果园",
            "账号", "帐号", "型号", "编号", "信号", "符号", "称号", "问号", "外号", "口号", "手机号", "微信号", "公众号", "订单号",
            "地区", "市区", "郊区", "上楼", "下楼", "节省", "反省",
            "教室", "卧室", "浴室", "办公室", "会议室", "实验室", "休息室", "上街", "逛街", "乡村", "农村", "城镇", "乡镇",
        )
        // 标志字打头的常用词:前面两个字加上它被 [PLACE] 当成了地名。「路上」「路口」「街上」「村里」这类跟在真地名后面的
        // 方位说法不收(「人民路上的店」里的「人民路」是真的)。
        // 省、市、区、县、镇、村打头的一个也不收:行政区划后面常直接跟着机构名,「南京市民政局」「朝阳区分局」
        // 「广东省事业单位」「李家村民委员会」拆开看正好是「市民」「区分」「省事」「村民」,真地名会被当成普通词漏掉
        val ORDINARY_STARTS = setOf(
            "城市", "道理", "道歉", "道具", "道德", "里面", "里边", "里头",
            "号码", "号称", "号召", "楼梯", "室内", "室外", "栋梁",
        )
    }
}
