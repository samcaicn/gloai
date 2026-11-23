package com.wangzan101.mika.teach

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.wangzan101.mika.task.ConfirmationNotifier

/**
 * 按系统意图识别桌面和默认浏览器,不认品牌、不写死包名。
 * 任何一步查不到都返回空集/回退包名:折叠因此 fail-open,不误删步骤。
 */
internal object TeachApps {
    fun launchers(context: Context): Set<String> = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    fun defaultBrowsers(context: Context): Set<String> = runCatching {
        val view = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("http://") }
        context.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    fun label(context: Context, packageName: String): String =
        runCatching { ConfirmationNotifier.appLabel(context, packageName) }.getOrDefault(packageName)
}
