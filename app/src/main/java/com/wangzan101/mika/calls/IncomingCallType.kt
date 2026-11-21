package com.wangzan101.mika.calls

import com.wangzan101.mika.R

/** The phone app supplies the label. Exact aliases avoid treating names or button text as a category. */
enum class IncomingCallType(val title: Int, private vararg val labels: String) {
    TAKEOUT(R.string.calls_type_takeout, "外卖", "外卖配送", "外卖送餐", "外卖骑手", "餐饮外卖", "送餐服务", "送餐"),
    PARCEL(R.string.calls_type_parcel, "快递", "快递物流", "物流快递", "快递配送", "快递服务", "快递员"),
    DELIVERY(R.string.calls_type_delivery, "配送", "物流", "物流电话", "快递送餐", "快递外卖", "快递/外卖", "快递／外卖", "快递/送餐", "快递、外卖", "快递或外卖", "Delivery"),
    SALES(R.string.calls_type_sales, "广告", "广告推销", "广告推销电话", "电话推销", "营销电话", "推销电话", "广告营销", "推销"),
    BROKER(R.string.calls_type_broker, "房产中介", "房产中介电话", "房地产中介", "中介", "中介电话"),
    FINANCIAL_SALES(R.string.calls_type_financial_sales, "保险理财", "保险推销", "理财推销"),
    SPAM(R.string.calls_type_spam, "骚扰", "骚扰电话", "Spam", "Suspected spam"),
    FRAUD(R.string.calls_type_fraud, "诈骗", "诈骗电话", "欺诈电话", "钓鱼", "钓鱼电话"),
    HIGH_RISK(R.string.calls_type_high_risk, "高风险", "高风险电话", "高风险来电"),
    BUSINESS(R.string.calls_type_business, "银行", "银行客服", "银行服务", "运营商", "运营商客服", "运营商服务", "客服热线", "客服电话", "客服来电", "企业来电", "商家来电", "企业号码"),
    TRANSPORT(R.string.calls_type_transport, "出租车", "网约车");

    companion object {
        private val byLabel = entries.flatMap { type -> type.labels.map { it.lowercase() to type } }.toMap()
        private const val count = "[0-9]{1,5}(?:位用户|个用户|人|次)"
        private val prefix = Regex("^(?:(?:该号码|此号码)?(?:已被|被|已有|已)?$count(?:标记|标注)(?:为|成)?|(?:号码标记|来电标记)[：:])\\s*[：:]?\\s*")
        private val suffix = Regex("\\s*(?:[（(]\\s*(?:$count(?:标记|标注)|[^（）()]{1,10}(?:标记|标注))\\s*[）)]|$count(?:标记|标注))$")
        private val bareSource = Regex("\\s*[（(][^（）()]{1,10}[）)]$")

        internal fun fromLabel(text: String, requireMark: Boolean = false): IncomingCallType? {
            if (text.length !in 2..80) return null
            val raw = text.trim()
            var word = raw.replaceFirst(prefix, "")
            repeat(2) {
                word = word.replaceFirst(suffix, "").trim()
                // 已确认是标记语境时，末尾括号视为来源署名一并剥掉（不点名具体来源）
                if (word.contains(Regex("标记|标注"))) word = word.replaceFirst(bareSource, "").trim()
            }
            if (requireMark && word == raw) return null
            word = word.removeSurrounding("“", "”").removeSurrounding("「", "」").removeSurrounding("【", "】")
                .removeSurrounding("\"").trim().removePrefix("疑似").removePrefix("涉嫌")
            return byLabel[word.lowercase()]
        }
    }
}

/** Evidence belongs to one Telecom Call. Disagreement for that call is sticky until it ends. */
internal class IncomingTypeEvidence {
    private var key: String? = null
    private val types = mutableSetOf<IncomingCallType>()

    fun accept(number: String?, displayedNumbers: Set<String>, labels: Set<IncomingCallType>): Boolean {
        val current = CallPolicy.incomingMatchKey(number) ?: return false
        if (displayedNumbers.singleOrNull() != current || labels.isEmpty()) return false
        if (key != null && key != current) return false
        key = current
        return types.addAll(labels)
    }

    fun typeFor(number: String?): IncomingCallType? =
        types.singleOrNull()?.takeIf { key == CallPolicy.incomingMatchKey(number) }
}
