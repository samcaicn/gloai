package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.learn.memory.SensitiveFilter
import java.text.Normalizer

/**
 * 归纳出来要落盘的短文字(话题名、联系人的显示名)字面上能查的两样:像网址、有一长串数字(单号、电话、验证码之类)。
 * 都对 NFKC 统一过全半角的文字查([nfkc]),免得全角写法绕过去。
 */
internal object LearnText {
    // 只认列出来的顶级域名,免得把 Node.js、Vue.js、README.md 这类正常的名字当成网址。所以没列的域名照旧认不出来;
    // 本身是常用词、常见人名或公司名后缀的(to、in、id、de、us、one、wang、au、page、art、ltd…)有意不列:
    // 「Mr.Wang」「Co.Ltd」不是网址
    private const val TLDS =
        "com|cn|net|org|io|co|me|app|top|xyz|info|cc|tv|ly|gl|link|site|shop|vip|ink|ai|" +
            "dev|edu|gov|mil|int|tech|club|online|store|live|pro|mobi|biz|cloud|work|fun|icu|red|asia|wiki|news|email|" +
            "uk|hk|tw|jp|kr|fr|ru|eu|ca|br|mx|sg"
    private val URL_LIKE = Regex(
        "(?:[a-z][a-z0-9+.-]*://|www\\.|[a-z0-9-]+\\.(?:$TLDS)(?![a-z0-9]))",
        RegexOption.IGNORE_CASE,
    )

    fun nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)

    fun urlLike(nfkc: String): Boolean = URL_LIKE.containsMatchIn(nfkc)

    /** 有超过 [LearnTuning.DIGIT_RUN_MAX] 位连着的数字,或者一个分开写的号码([groupedEnd])。 */
    fun longDigits(nfkc: String): Boolean = longestDigitRun(nfkc) > LearnTuning.DIGIT_RUN_MAX || hasGrouped(nfkc)

    private val URL_SPAN = Regex(
        "(?:[a-z][a-z0-9+.-]*://\\S+|www\\.\\S+|[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:$TLDS)(?![a-z0-9])(?:/\\S*)?)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 归纳出的一句话(比话题名长,里面可能带着金额、日期)落盘前:网址整段换成「…」,超过 [maxDigits] 位连着的数字
     * (单号、卡号、电话)也换成「…」,分开写的号码(「138 1234 5678」,见 [groupedEnd])整个换成「…」;
     * 短的数字(金额、几点、几号)和日期留着。先按 NFKC 统一全半角。
     */
    fun maskLinksAndLongNumbers(s: String, maxDigits: Int): String {
        val text = URL_SPAN.replace(nfkc(s), "…")
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!Character.isDigit(cp)) {
                out.appendCodePoint(cp)
                i += Character.charCount(cp)
                continue
            }
            val date = dateEnd(text, i)
            if (date > 0) {
                out.append(text, i, date)
                i = date
                continue
            }
            val grouped = groupedEnd(text, i, maxDigits)
            if (grouped > 0) {
                out.append('…')
                i = grouped
                continue
            }
            var j = i
            var digits = 0
            while (j < text.length && Character.isDigit(text.codePointAt(j))) {
                j += Character.charCount(text.codePointAt(j))
                digits++
            }
            if (digits > maxDigits) out.append('…') else out.append(text, i, j)
            i = j
        }
        return out.toString()
    }

    /**
     * 归纳出来要落盘的文字(句子、话题名、联系人的显示名)过不过敏感信息这一关:主程序的 [SensitiveFilter] 之外,
     * 再查它不认的登录凭据写法([authCode])。命中的整条不要。[full] 是清洗过、没截短的整段。
     */
    fun sensitive(full: String): Boolean = SensitiveFilter.hit(full) != null || authCode(nfkc(full))

    // 字符集不以冒号开头(「[:」在安卓的正则引擎里可能被读成具名字符类的开头),冒号放到后面
    /**
     * 一份归纳的一句概述落盘前:清洗成一行;过不了敏感信息这一关的整句不要(返回 null);网址和长串数字遮掉;截到长度上限
     * (主要是中日韩文字的 [LearnTuning.BRIEF_MAX_CJK],别的 [LearnTuning.BRIEF_MAX])。空的为 null。
     */
    fun brief(raw: String): String? {
        val full = NoticeFilter.clean(raw, 1_000)
        if (full.isEmpty() || sensitive(full)) return null
        val masked = maskLinksAndLongNumbers(full, LearnTuning.NOTE_DIGIT_RUN_MAX)
        val max = if (mostlyCjk(masked)) LearnTuning.BRIEF_MAX_CJK else LearnTuning.BRIEF_MAX
        // 按字(码点)数,不按 UTF-16 单位:五十个汉字就是五十个
        val end = if (masked.codePointCount(0, masked.length) <= max) masked.length else masked.offsetByCodePoints(0, max)
        return masked.substring(0, end).trim().ifEmpty { null }
    }

    /** 文字里中日韩的字(汉字、假名、谚文)占三成以上。 */
    private fun mostlyCjk(s: String): Boolean {
        var cjk = 0
        var total = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!Character.isWhitespace(cp)) {
                total++
                val script = Character.UnicodeScript.of(cp)
                if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
                    script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) cjk++
            }
            i += Character.charCount(cp)
        }
        return total > 0 && cjk * 10 >= total * 3
    }

    // 数字一律写 \p{Nd}(各种文字的十进制数字,电脑上的 JVM 和安卓上意思一样),不写 \d;空白只认空格和制表符
    // (文字进来之前已经清洗成一行)。不开忽略大小写(JVM 上默认只管 ASCII):先整段转小写,词表全用小写写。
    private const val CODE = "(?:[a-z]{1,3}-)?\\p{Nd}{4,}|\\p{Nd}{3}[ -]\\p{Nd}{3,4}"
    private const val GAP = "[ \t:：=,，是为為叫(（\\[【「『《“\"]{0,4}(?:(?:is|was|are|es|é)[ \t]*[：:]?[ \t]*)?"
    // 密码这一类后面跟的可以是字母数字混着的(「密碼 abc12345」),但得带数字:不然拉丁字母的语言里后面随便一个词都算
    private const val SECRET = "(?=[a-z0-9]*\\p{Nd})[a-z0-9]{4,}"

    // [SensitiveFilter] 不认的「用来登录、确认身份的码」的叫法。只收这一类:取件码、兑换码、邀请码、邮编这些不是凭据,不在这里。
    private const val AUTH_WORDS =
        "登录码|登陆码|登入码|确认码|认证码|授权码|短信码|随机码|注册码|绑定码|核验码|交易码|提现码|" +
            "驗證碼|校驗碼|動態碼|安全碼|登錄碼|登入碼|確認碼|認證碼|授權碼|簡訊碼|隨機碼|註冊碼|綁定碼|" +
            "認証コード|確認コード|認証番号|暗証番号|セキュリティコード|" +
            "인증번호|인증 ?코드|보안 ?코드|확인 ?코드|" +
            "c[oó]digo de (?:verificaci[oó]n|verificação|seguridad|segurança|confirmaci[oó]n|confirmação|acceso|acesso)|" +
            "код (?:подтверждения|проверки|доступа|авторизации|для входа)|проверочный код|" +
            "kode (?:verifikasi|otp|keamanan|konfirmasi|login|masuk)|" +
            "رمز التحقق|رمز التأكيد|رمز الدخول|کد تایید|کد تأیید|کد ورود"
    // 「密码」在 [SensitiveFilter] 不认的写法和语言里的说法
    private const val PASSWORD_WORDS =
        "密碼|ワンタイムパスワード|パスワード|パスコード|비밀번호|contraseña|senha|пароль|kata sandi|كلمة المرور|رمز عبور"
    private val AUTH_THEN_CODE = Regex("(?:$AUTH_WORDS)$GAP(?:$CODE)")
    private val PASSWORD_THEN_SECRET = Regex("(?:$PASSWORD_WORDS)$GAP$SECRET")
    private val CODE_THEN_AUTH =
        Regex("(?<!\\p{Nd})\\p{Nd}{4,}[ \t]*(?:就是|是|为|為)(?:你的|您的|我的)?[ \t]*(?:$AUTH_WORDS|$PASSWORD_WORDS)")
    private val SPACES = Regex("[ \t]+")

    // 英文里单说一个 code:前面那个词说明它不是凭据的(zip code、error code…)不算。中间有冒号、等号、is 的,4 位就算;
    // 只隔着空格的要 5 位以上——4 位的多半是年份(「… of Code 2026」)
    private val ENGLISH_CODE = Regex(
        "(?<![a-z])codes?(?![a-z])(?:[ \t]*(?:[：:=]|(?:is|was)(?![a-z])[ \t]*[：:]?)[ \t]*(?:$CODE)" +
            "|[ \t]{1,2}(?:(?:[a-z]{1,3}-)?\\p{Nd}{5,}|\\p{Nd}{3}[ -]\\p{Nd}{3,4}))",
    )
    private val CODE_IS_YOUR = Regex("(?<!\\p{Nd})\\p{Nd}{4,} is (?:your|the) (?:[a-z-]+ ){0,3}?code(?![a-z])")
    private val NOT_AUTH = setOf(
        "zip", "postal", "post", "area", "country", "dial", "error", "status", "exit", "return", "response", "http",
        "promo", "promotion", "coupon", "discount", "voucher", "invite", "invitation", "referral",
        "pickup", "collection", "tracking", "booking", "reservation", "order", "product", "item", "model",
        "qr", "bar", "source", "color", "colour", "dress", "course", "class", "room", "gate", "door",
    )

    /**
     * 像不像带着一个登录凭据:「登录码 482913」「482913 是你的登录码」「Your code is 482913」这类——凭据的叫法紧挨着
     * 一个 4 位以上的码(中间只许很短的连接)。[nfkc] 是统一过全半角的文字。只是字面上能查的一部分,不是保证。
     */
    fun authCode(nfkc: String): Boolean {
        val t = nfkc.lowercase()
        if (AUTH_THEN_CODE.containsMatchIn(t) || PASSWORD_THEN_SECRET.containsMatchIn(t) || CODE_THEN_AUTH.containsMatchIn(t)) return true
        // 码中间夹着空格的(「登录码 4 8 2 9 1 3」):去掉空白再查一遍。只对「码」的叫法:密码那一条后面认的是字母数字,
        // 去掉空白会把后面的整句话并成一个词(「senha alterada em 12 de …」);英文的 code 要看前一个词,也不能并
        val compact = SPACES.replace(t, "")
        if (AUTH_THEN_CODE.containsMatchIn(compact)) return true
        if (ENGLISH_CODE.findAll(t).any { wordBefore(t, it.range.first) !in NOT_AUTH }) return true
        return CODE_IS_YOUR.findAll(t).any { m ->
            val at = m.value.lastIndexOf("code")
            wordBefore(t, m.range.first + at) !in NOT_AUTH
        }
    }

    /** [index] 前面紧挨着的那个英文词(隔着空格或连字符),没有为空串。 */
    private fun wordBefore(s: String, index: Int): String {
        var end = index
        while (end > 0 && (s[end - 1] == ' ' || s[end - 1] == '-' || s[end - 1] == '\t')) end--
        var start = end
        while (start > 0 && s[start - 1] in 'a'..'z') start--
        return s.substring(start, end)
    }

    /** 一段数字从 [i] 起到哪里为止(不含);[i] 上不是数字时就是 [i]。 */
    private fun digitsEnd(s: String, i: Int): Int {
        var j = i
        while (j < s.length && Character.isDigit(s.codePointAt(j))) j += Character.charCount(s.codePointAt(j))
        return j
    }

    private fun digitCount(s: String, from: Int, to: Int): Int = s.codePointCount(from, to)

    private fun hyphen(c: Char): Boolean = c == '-' || c == '‐' || c == '‑' || c == '–'

    private fun joiner(c: Char): Boolean = c == ' ' || hyphen(c)

    /**
     * [i] 起是不是一个日期:「年-月-日」(四位、一两位、一两位)或「日-月-年」(一两位、一两位、四位),用横线连着,
     * 前后不再紧挨着别的数字段。是的话返回它的结尾(不含),不是为 -1。
     * 日期要留着,也不能和后面的几点钟并成一个「分开写的号码」。只看形状,不核对月份天数。
     */
    private fun dateEnd(s: String, i: Int): Int {
        if (i > 0 && (Character.isDigit(s[i - 1]) || hyphen(s[i - 1]))) return -1
        val a = digitsEnd(s, i)
        if (a >= s.length || !hyphen(s[a])) return -1
        val b = digitsEnd(s, a + 1)
        if (b >= s.length || !hyphen(s[b])) return -1
        val c = digitsEnd(s, b + 1)
        val first = digitCount(s, i, a)
        val mid = digitCount(s, a + 1, b)
        val last = digitCount(s, b + 1, c)
        val shaped = mid in 1..2 && ((first == 4 && last in 1..2) || (first in 1..2 && last == 4))
        if (!shaped) return -1
        if (c + 1 < s.length && hyphen(s[c]) && Character.isDigit(s[c + 1])) return -1
        return c
    }

    /**
     * [i] 起是不是一个分开写的号码(电话、卡号常这样写:「138 1234 5678」「138-1234-5678」):几段数字之间各隔着一个空格
     * 或横线,加起来有 [GROUPED_DIGITS_MIN] 位以上,至少有一段是三位以上(一串比分、一串两位数不算),而且要么有三段以上,
     * 要么两段里有一段本来就超过 [maxDigits] 位(「010-12345678」:区号一起遮)。只有两段、每段都不长的是一个范围
     * (「15000-25000 元」),留着。是的话返回它的结尾(不含),不是为 -1。走到一个日期就停下,日期不并进来。
     */
    private fun groupedEnd(s: String, i: Int, maxDigits: Int): Int {
        var end = digitsEnd(s, i)
        var total = digitCount(s, i, end)
        var widest = total
        var groups = 1
        while (end + 1 < s.length && joiner(s[end]) && Character.isDigit(s.codePointAt(end + 1)) && dateEnd(s, end + 1) < 0) {
            val next = digitsEnd(s, end + 1)
            val n = digitCount(s, end + 1, next)
            total += n
            if (n > widest) widest = n
            groups++
            end = next
        }
        val shaped = groups >= 3 || (groups == 2 && widest > maxDigits)
        return if (shaped && total >= GROUPED_DIGITS_MIN && widest >= 3) end else -1
    }

    /** 分开写的号码加起来至少这么多位:手机号 11 位、带区号的座机 10 位以上、卡号 13 位以上;日期带上钟点到不了。 */
    private const val GROUPED_DIGITS_MIN = 10

    private fun hasGrouped(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!Character.isDigit(cp)) { i += Character.charCount(cp); continue }
            val date = dateEnd(s, i)
            if (date > 0) { i = date; continue }
            if (groupedEnd(s, i, LearnTuning.DIGIT_RUN_MAX) > 0) return true
            i = digitsEnd(s, i)
        }
        return false
    }

    /** 最长的一串连着的数字(各种文字的数字都算,不靠正则的 \d:电脑上的 JVM 和安卓上的意思不一样)。 */
    private fun longestDigitRun(s: String): Int {
        var best = 0
        var run = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            run = if (Character.isDigit(cp)) run + 1 else 0
            if (run > best) best = run
            i += Character.charCount(cp)
        }
        return best
    }
}
