package com.wangzan101.mika.ui.terminal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.ui.theme.appearanceDark
import com.wangzan101.mika.ui.theme.systemBarsFor

/**
 * 用户终端:一个独立页面,里面是这个对话工作区的 shell。不经过模型,不写运行记录;
 * 离开页面不会结束会话(转屏、按 Home 再回来都还在),进程被杀时随 App 一起结束。
 */
class TerminalActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION)
        val workspaceKey = intent.getStringExtra(EXTRA_WORKSPACE)?.let(WorkspaceKey::parse)
        if (conversationId == null && workspaceKey == null) { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE)
        val app = applicationContext as MikaApp
        setContent {
            val dark = appearanceDark()
            LaunchedEffect(dark) { systemBarsFor(dark) }
            MikaTheme(dark = dark, reduceMotion = animationsOff(this)) {
                TerminalRoute(app, conversationId, workspaceKey, title, onDone = ::finish)
            }
        }
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversationId"
        const val EXTRA_WORKSPACE = "workspaceKey"
        const val EXTRA_TITLE = "title"
    }
}

/** 从对话菜单或设置里的工作区行打开终端。 */
object TerminalLauncher {
    fun open(context: Context, conversationId: String, title: String?) {
        val intent = Intent(context, TerminalActivity::class.java)
            .putExtra(TerminalActivity.EXTRA_CONVERSATION, conversationId)
            .putExtra(TerminalActivity.EXTRA_TITLE, title)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openWorkspace(context: Context, workspace: WorkspaceKey, title: String?) {
        val intent = Intent(context, TerminalActivity::class.java)
            .putExtra(TerminalActivity.EXTRA_WORKSPACE, workspace.wire)
            .putExtra(TerminalActivity.EXTRA_TITLE, title)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
