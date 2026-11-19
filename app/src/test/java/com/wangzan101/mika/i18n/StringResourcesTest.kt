package com.wangzan101.mika.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 英文(默认)、简体和繁体三份界面文字要一一对上:同样的条目、同样的占位符(繁体对照简体);英文里不该留着没翻的中文。
 * 其他语言([others])也要和英文一一对上,复数按各自语言的数量词写全。
 */
class StringResourcesTest {
    /** 中英以外的一种界面语言:资源文件夹,和这种语言的复数要写哪几个数量词(按 CLDR 的复数规则)。 */
    private class Other(val folder: String, val locale: Locale, val quantities: Set<String>) {
        val texts by lazy { ResFileTexts(folder, locale) }
    }

    private val others = listOf(
        Other("values-ja", Locale.JAPANESE, setOf("other")),
        Other("values-ko", Locale.KOREAN, setOf("other")),
        Other("values-ar", Locale.forLanguageTag("ar"), setOf("zero", "one", "two", "few", "many", "other")),
        Other("values-fa", Locale.forLanguageTag("fa"), setOf("one", "other")),
        Other("values-es", Locale.forLanguageTag("es"), setOf("one", "many", "other")),
        Other("values-pt", Locale.forLanguageTag("pt"), setOf("one", "many", "other")),
        Other("values-ru", Locale.forLanguageTag("ru"), setOf("one", "few", "many", "other")),
        // 印尼语的资源文件夹沿用安卓的旧语言代码 in
        Other("values-in", Locale.forLanguageTag("id"), setOf("other")),
        Other("values-tr", Locale.forLanguageTag("tr"), setOf("other")),
    )

    private val en = ResFileTexts.english
    private val zh = ResFileTexts.chinese
    private val hant = ResFileTexts.traditional

    /** 标了 translatable="false" 的条目只在默认文件里。 */
    private val fixed: Set<String> = ResFileTexts.resFiles("values").flatMap { f ->
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("translatable") == "false" }.map { it.getAttribute("name") }
    }.toSet()

    @Test
    fun everyStringHasBothLanguages() {
        assertEquals(en.strings.keys - fixed, zh.strings.keys)
        assertEquals(en.plurals.keys, zh.plurals.keys)
        assertTrue(en.strings.size > 300)
    }

    @Test
    fun placeholdersMatch() {
        // 日期两种语言取的字段不同(中文写月份数字,英文写月份名)
        for (name in zh.strings.keys - setOf("date_month_day", "date_year_month_day")) assertEquals(name, args(en.strings.getValue(name)), args(zh.strings.getValue(name)))
        for (name in zh.plurals.keys) {
            val expected = args(zh.plurals.getValue(name).getValue("other"))
            en.plurals.getValue(name).values.forEach { assertEquals(name, expected, args(it)) }
        }
    }

    @Test
    fun traditionalMatchesSimplified() {
        assertEquals(zh.strings.keys, hant.strings.keys)
        assertEquals(zh.plurals.keys, hant.plurals.keys)
        for (name in zh.strings.keys) assertEquals(name, args(zh.strings.getValue(name)), args(hant.strings.getValue(name)))
        for (name in zh.plurals.keys) {
            assertEquals(name, zh.plurals.getValue(name).keys, hant.plurals.getValue(name).keys)
            zh.plurals.getValue(name).forEach { (q, v) -> assertEquals("$name/$q", args(v), args(hant.plurals.getValue(name).getValue(q))) }
        }
    }

    @Test
    fun otherLanguagesMatchEnglish() {
        for (o in others) {
            val t = o.texts
            assertEquals(o.folder, en.strings.keys - fixed, t.strings.keys)
            assertEquals(o.folder, en.plurals.keys, t.plurals.keys)
            for (name in t.strings.keys - setOf("date_month_day", "date_year_month_day")) {
                assertEquals("${o.folder}/$name", args(en.strings.getValue(name)), args(t.strings.getValue(name)))
            }
            for (name in t.plurals.keys) {
                val forms = t.plurals.getValue(name)
                assertEquals("${o.folder}/$name", o.quantities, forms.keys)
                val expected = args(en.plurals.getValue(name).getValue("other"))
                forms.forEach { (q, v) ->
                    val actual = args(v)
                    val mayOmitCount = o.folder == "values-ar" && q in setOf("one", "two") && "%1\$d" in expected
                    if (mayOmitCount) {
                        assertTrue("${o.folder}/$name/$q", actual == expected || actual == expected - "%1\$d")
                    } else {
                        assertEquals("${o.folder}/$name/$q", expected, actual)
                    }
                }
            }
        }
    }

    /** 不用汉字的语言里不该留着没翻的中文;日语本来就用汉字,不查。 */
    @Test
    fun otherLanguagesHaveNoChineseLeftOver() {
        val cjk = Regex("[\\u4e00-\\u9fff]")
        for (o in others.filter { it.folder != "values-ja" }) {
            val left = o.texts.strings.filter { (_, v) -> cjk.containsMatchIn(v) }.keys +
                o.texts.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
            assertEquals(o.folder, emptySet<String>(), left)
        }
    }

    /** 每种界面语言在资源里都有对应的文件夹,ui_locale 写的就是这种语言。 */
    @Test
    fun everyAppLanguageHasItsTexts() {
        val byLanguage = others.associateBy { AppLanguage.modernCode(it.locale.language) }
        for (l in AppLanguage.entries) {
            val code = l.language ?: continue
            if (code == "zh" || code == "en") continue
            val o = byLanguage[code]
            assertTrue("$l has no folder", o != null)
            assertEquals(l.toString(), code, AppLanguage.modernCode(Locale.forLanguageTag(o!!.texts.strings.getValue("ui_locale")).language))
        }
    }

    @Test
    fun englishHasNoChineseLeftOver() {
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val left = en.strings.filter { (k, v) -> k !in fixed && cjk.containsMatchIn(v) }.keys +
            en.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
        assertEquals(emptySet<String>(), left)
    }

    @Test
    fun languageFromTag() {
        // 单测里没有安卓的 ICU:补文字体系这一步换成假的,只认一个私用标签是繁体
        val likely = { tag: String -> if (tag == "zh-x-hant") "Hant" else "Hans" }
        fun of(tag: String?) = AppLanguage.fromTag(tag, likely)
        assertEquals(AppLanguage.System, of(null))
        assertEquals(AppLanguage.System, of(""))
        // 以前选「中文」存下的是 zh-Hans
        assertEquals(AppLanguage.SimplifiedChinese, of("zh-Hans"))
        assertEquals(AppLanguage.TraditionalChinese, of("zh-Hant"))
        assertEquals(AppLanguage.SimplifiedChinese, of("zh"))
        // 没写文字体系时按补出来的算
        assertEquals(AppLanguage.TraditionalChinese, of("zh-x-hant"))
        // 写了文字体系就按写的,不再补
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromTag("zh-Hans") { "Hant" })
        assertEquals(AppLanguage.English, of("en"))
        assertEquals(AppLanguage.English, of("fr-FR"))
        assertEquals(AppLanguage.Arabic, of("ar"))
        assertEquals(AppLanguage.Arabic, of("ar-MA"))
        assertEquals(AppLanguage.Persian, of("fa-IR"))
        // 其他语言只看语言代码,地区不管
        assertEquals(AppLanguage.Japanese, of("ja-JP"))
        assertEquals(AppLanguage.Portuguese, of("pt-PT"))
        assertEquals(AppLanguage.Portuguese, of("pt-BR"))
        assertEquals(AppLanguage.Spanish, of("es-419"))
        // 印尼语:现行代码和安卓 Locale 还在用的旧代码都认
        assertEquals(AppLanguage.Indonesian, of("id-ID"))
        assertEquals(AppLanguage.Indonesian, of("in-ID"))
    }

    @Test
    fun eachLanguageHasItsOwnTag() {
        for (l in AppLanguage.entries) assertEquals(l, AppLanguage.fromTag(l.tag) { "Hans" })
    }

    private fun args(s: String): Set<String> = Regex("%(\\d+\\$)?[sd]").findAll(s).map { it.value }.toSet()
}
