package com.wangzan101.mika.settings.ui.models

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wangzan101.mika.ui.MainActivity

/** 第一次用 ChatGPT 登录成功后说明一次:Mika 里的用量走你的 ChatGPT 套餐。 */
@Composable
internal fun ChatGptWelcome(onDismiss: () -> Unit) {
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(ModelsCopy.CHATGPT_WELCOME_TITLE) },
        text = { Text(ModelsCopy.CHATGPT_WELCOME_BODY) },
        confirmButton = { TextButton(onDismiss) { Text(ModelsCopy.CHATGPT_GOT_IT) } },
    )
}

/**
 * 登录要用的系统能力:授权页用系统浏览器打开;回调页(换完令牌之后才给出)写成功或失败的原因,再放一个回到 Mika 的链接(intent 链接,点了由浏览器打开 Mika);
 * 登录完(成功或失败)把 Mika 带回前台。安卓限制后台打开界面,带不回来时用户点回调页的链接或从最近任务回来。
 */
internal class AndroidSignInBrowser(private val context: Context) : SignInBrowser {
    override fun open(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, tr(R.string.chatgpt_no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    override fun returnToApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    override fun page(failure: String?): String {
        val text = failure ?: tr(R.string.chatgpt_page_done)
        val back = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=${context.packageName};end"
        return SignInPage.html(tr(R.string.chatgpt_page_title), text, tr(R.string.chatgpt_page_back), back)
    }
}

/** 回调后浏览器里那一页:一句话加一个回到 App 的按钮。文字转义后放进去。 */
internal object SignInPage {
    fun html(title: String, text: String, back: String, backUrl: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<title>${esc(title)}</title><style>body{font-family:sans-serif;margin:0;padding:48px 24px;text-align:center;color:#1d1d1f;background:#fff}""" +
            """p{font-size:17px;line-height:1.5}a{display:inline-block;margin-top:16px;padding:12px 28px;border-radius:999px;background:#1d1d1f;""" +
            """color:#fff;text-decoration:none;font-size:16px}</style></head><body><p>${esc(text)}</p><a href="${esc(backUrl)}">${esc(back)}</a></body></html>"""
    }
}
