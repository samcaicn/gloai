package com.jev.probe.core

import android.content.Context

/**
 * 界面语言（中 / EN），默认中文。
 *
 * 本应用的历史文案全部硬编码在 Kotlin 里（不在 res/values），做不了系统级
 * per-app locale。这里的方案：每个界面字符串用扩展函数 [t] 写成
 * `t("中文", "English")`，取词时按 [code] 现判——切语言后对打开中的
 * Activity 调 `recreate()`、主页 onResume 本来就会 build()，悬浮球由
 * OverlayController 下次重绘生效。
 *
 * 存储直接用主 prefs 文件（[Prefs.PREFS_MAIN]），跟随云端备份一起迁移。
 */
object Lang {
    const val ZH = "zh"
    const val EN = "en"
    private const val K_UI_LANG = "ui_lang"

    /** 当前语言码，"zh"（默认）或 "en"。 */
    fun code(ctx: Context): String =
        ctx.getSharedPreferences(Prefs.PREFS_MAIN, Context.MODE_PRIVATE)
            .getString(K_UI_LANG, ZH) ?: ZH

    fun isEn(ctx: Context): Boolean = code(ctx) == EN

    /** 保存语言码（非 "en" 一律归一为 "zh"）。界面刷新由调用方负责。 */
    fun set(ctx: Context, code: String) {
        ctx.getSharedPreferences(Prefs.PREFS_MAIN, Context.MODE_PRIVATE)
            .edit().putString(K_UI_LANG, if (code == EN) EN else ZH).apply()
    }
}

/**
 * 界面文案取词。中文永远是第一参数（默认语言），英文第二参数。
 * 例：`t("还差 2 步", "2 steps left")`
 */
fun Context.t(zh: String, en: String): String =
    if (Lang.isEn(this)) en else zh
