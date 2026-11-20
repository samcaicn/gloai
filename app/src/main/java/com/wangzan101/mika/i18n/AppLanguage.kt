package com.wangzan101.mika.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.util.ULocale
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * App 界面的语言:跟随系统,或者选定下面的一种。资源里英文是默认(`values/`),简体中文在 `values-zh/`、繁体中文在 `values-b+zh+Hant/`,
 * 阿拉伯语在 `values-ar/`,其他语言各在 `values-<语言>/`(印尼语沿用安卓的旧代码 `values-in/`)。安卓按语言和文字体系挑资源,
 * 所以「跟随系统」时系统语言有译文就显示它,没有就显示英文;某种语言缺了哪条,那条也显示英文。
 *
 * 安卓 13 起由系统的「应用语言」管(LocaleManager):系统记住选择、套到整个进程、重建界面,
 * 系统设置里的「应用语言」和这里的开关是同一个值。安卓 11、12 没有这项,由这里记在本地,
 * 经 [wrap] 套到界面的 Context、经 [resources] 套到界面以外取文字的地方,换了之后重建当前界面。
 */
enum class AppLanguage(val tag: String?, val speechTag: String?) {
    System(null, null),
    SimplifiedChinese("zh-Hans", "zh-CN"),
    TraditionalChinese("zh-Hant", "zh-TW"),
    Arabic("ar", "ar-MA"),
    Persian("fa", "fa-IR"),
    English("en", "en-US"),
    Japanese("ja", "ja-JP"),
    Korean("ko", "ko-KR"),
    Spanish("es", "es-ES"),
    Portuguese("pt", "pt-BR"),
    Russian("ru", "ru-RU"),
    Indonesian("id", "id-ID"),
    Turkish("tr", "tr-TR");

    /** 语言代码(不带文字体系和地区);跟随系统时为 null。 */
    val language: String? get() = tag?.let { Locale.forLanguageTag(it).language.let(::modernCode) }

    companion object {
        private const val PREFS = "app_language"
        private const val KEY_TAG = "tag"
        private const val KEY_PROMPTED = "language_prompted"

        /** 是否已经向用户展示过初次/更新语言选择提示。 */
        fun hasPrompted(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PROMPTED, false)

        /** 标记已提示或已设置，不再主动弹出询问。 */
        fun markPrompted(context: Context) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PROMPTED, true).apply()
        }

        /** 安卓 12 及以前选定的语言;null 表示跟随系统。进程里读一次,换的时候更新。 */
        @Volatile
        private var legacyTag: String? = null
        @Volatile
        private var legacyLoaded = false

        /** 安卓 12 及以前按选定语言建出的资源,跟着语言换。 */
        @Volatile
        private var cached: Pair<String, Resources>? = null

        private val systemManaged get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        fun current(context: Context): AppLanguage = fromTag(
            if (systemManaged) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
            } else {
                legacy(context)
            },
        )

        /**
         * 实际生效的语言:选定语言直接返回;跟随系统时按系统当前语言映射到支持的某一项
         * (中文按文字体系分简繁,不支持的落英文)。给界面上写停驻说明、语言指令等要用到
         * 确定语言标签、又拿不到用户明确选择的地方,避免把「跟随系统」当成中文。
         */
        fun resolved(context: Context): AppLanguage {
            val cur = current(context)
            if (cur != System) return cur
            return fromTag(Locale.getDefault().toLanguageTag())
        }

        /** 语言标签对应哪一项:见 [fromTag];标签里没写文字体系时,按 ICU 的常见写法补出来。 */
        fun fromTag(tag: String?): AppLanguage = fromTag(tag) { ULocale.addLikelySubtags(ULocale.forLanguageTag(it)).script }

        /**
         * 中文按文字体系分:写的或 [likelyScript] 补出的是 Hant 就是繁體中文,别的中文是简体中文;
         * 其他语言按语言代码对上一项(地区不管),对不上的是英文。以前选「中文」存下的 zh-Hans 仍是简体中文。
         */
        internal fun fromTag(tag: String?, likelyScript: (String) -> String): AppLanguage {
            if (tag.isNullOrEmpty()) return System
            val l = Locale.forLanguageTag(tag)
            val code = modernCode(l.language)
            if (code == "zh") return if (l.script.ifEmpty { likelyScript(tag) } == "Hant") TraditionalChinese else SimplifiedChinese
            return entries.firstOrNull { it.tag != null && it.language == code } ?: English
        }

        /** 安卓上 java.util.Locale 仍给几种语言用旧代码(印尼语是 in、希伯来语是 iw…),统一成现行代码再比。 */
        internal fun modernCode(language: String): String = when (language) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            else -> language
        }

        /** 换语言并重建界面。安卓 13 起交给系统(系统自己重建);之前记在本地,重建 [context] 所在的界面。 */
        fun set(context: Context, language: AppLanguage) {
            markPrompted(context)
            if (systemManaged) {
                context.getSystemService(LocaleManager::class.java).applicationLocales =
                    language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
                return
            }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, language.tag).commit()
            legacyTag = language.tag
            legacyLoaded = true
            cached = null
            applyDefaultLocale(context)
            context.activity()?.recreate()
        }

        /** App 启动时调:安卓 12 及以前选了语言时,把进程的默认 Locale(日期、数字的格式)也换成它。 */
        fun applyDefaultLocale(context: Context) {
            if (systemManaged) return
            val locales = legacyLocales(context) ?: Resources.getSystem().configuration.locales
            Locale.setDefault(locales[0])
        }

        /** 套上 App 语言的 Context:在 Activity 的 attachBaseContext 和非 Activity 建界面的地方用。安卓 13 起原样返回。 */
        fun wrap(base: Context): Context {
            val locales = legacyLocales(base) ?: return base
            return base.createConfigurationContext(Configuration().apply { setLocales(locales) })
        }

        /**
         * 界面以外取文字用的资源:跟着 App 语言。安卓 13 起也按系统记下的应用语言另建一份,
         * 不直接用 Application 的资源:进程不是系统按 App 正常拉起时(如测试启动),那份资源还是系统语言。
         */
        fun resources(app: Context): Resources {
            val tag = if (systemManaged) {
                app.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.toLanguageTags()
            } else {
                legacy(app)
            }
            if (tag == null) return app.resources
            cached?.takeIf { it.first == tag }?.let { return it.second }
            val r = app.createConfigurationContext(Configuration().apply { setLocales(LocaleList.forLanguageTags(tag)) }).resources
            cached = tag to r
            return r
        }

        private fun legacyLocales(context: Context): LocaleList? =
            if (systemManaged) null else legacy(context)?.let(LocaleList::forLanguageTags)

        private fun legacy(context: Context): String? {
            if (!legacyLoaded) {
                legacyTag = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)
                legacyLoaded = true
            }
            return legacyTag
        }

        private fun Context.activity(): Activity? {
            var c: Context? = this
            while (c is ContextWrapper) {
                if (c is Activity) return c
                c = c.baseContext
            }
            return null
        }
    }
}
