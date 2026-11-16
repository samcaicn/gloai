package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 许可证页上第三方组件的数据;与仓库 licenses/THIRD_PARTY_NOTICES.md 的条目一一对应(LicensesTest 核对)。 */
object Licenses {
    data class Entry(val name: String, val license: String, val note: String)

    const val GPL_SOURCE_OFFER =
        "PRoot(GPL-2.0-or-later)与 talloc(LGPL-3.0-or-later)以独立程序的形式随 Mika 安装包发布,Mika 不链接它们。" +
            "它们的完整源码、Mika 打的补丁和编译脚本随每个版本放在 Mika 的发布页,文件名 mika-<版本>-gpl-sources.tar.gz;" +
            "源码仓库的 tools/sandbox/ 里也有源码地址、校验值、补丁与编译脚本,可以自己重新编译。"

    const val RUNTIME_NOTE =
        "运行 Python、Node 等程序用的系统随 Mika 安装包发布,来自 Alpine Linux 3.22,下面逐个列出其中的软件包与许可证。" +
            "其中 GPL、LGPL、MPL 软件包的源码(Alpine 的构建配方与上游源码)同样放在 mika-<版本>-gpl-sources.tar.gz 里。"

    /** 自带运行环境里的软件包(assets/runtime/packages.json,由 tools/sandbox/build-runtime.py 生成)。 */
    fun runtimePackages(json: String): List<Entry> =
        Json.parseToJsonElement(json).jsonObject.getValue("packages").jsonArray.map { e ->
            val o = e.jsonObject
            fun f(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            Entry(f("name"), f("license"), "版本 ${f("version")}。${f("url")}")
        }

    val ENTRIES = listOf(
        Entry("Mika", "Apache-2.0", "Mika 本身,包括运行环境的启动器、其中的 mika 命令与技能脚本。许可证全文见本页顶上。"),
        Entry("PRoot", "GPL-2.0-or-later", "运行 Python、Node 等程序用的环境,版本 5.4.0,打了一个补丁(PRoot 退出时结束所有子进程)。$GPL_SOURCE_OFFER"),
        Entry("talloc", "LGPL-3.0-or-later", "版本 2.4.3,未修改,静态链接进 PRoot 程序。可用 tools/sandbox/ 的源码与脚本重新链接。"),
        Entry("Apache Commons Compress", "Apache-2.0", "首次启动时解包自带的运行环境。Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons Codec", "Apache-2.0", "Apache Commons Compress 的依赖。Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons IO", "Apache-2.0", "Apache Commons Compress 的依赖。Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons Lang", "Apache-2.0", "Apache Commons Compress 的依赖。Copyright 2001-2025 The Apache Software Foundation."),
        Entry("Kotlin 标准库", "Apache-2.0", "kotlin-stdlib 2.1.20 等,以及 JetBrains annotations 23.0.0。https://kotlinlang.org/"),
        Entry("kotlinx.coroutines", "Apache-2.0", "版本 1.9.0。https://github.com/Kotlin/kotlinx.coroutines"),
        Entry("kotlinx.serialization", "Apache-2.0", "版本 1.7.3。https://github.com/Kotlin/kotlinx.serialization"),
        Entry("OpenTelemetry", "Apache-2.0", "详细 Trace 的 OTLP 消息定义。 https://opentelemetry.io/"),
        Entry("Protocol Buffers", "BSD-3-Clause", "详细 Trace 的 Protobuf 编码。 https://protobuf.dev/"),
        Entry("OkHttp", "Apache-2.0", "版本 4.12.0,用于访问模型接口。https://square.github.io/okhttp/"),
        Entry("Okio", "Apache-2.0", "版本 3.6.0,OkHttp 的依赖。https://github.com/square/okio/"),
        Entry("AndroidX(含 Jetpack Compose)", "Apache-2.0", "Compose 1.7.6、Material3 1.3.1、Activity 1.9.3、Lifecycle 2.8.3、Core 1.13.1、WebKit 1.12.1 等界面与系统组件库。https://developer.android.com/jetpack/androidx"),
        Entry("Guava ListenableFuture(由 AndroidX 库引入)", "Apache-2.0", "listenablefuture 1.0。https://github.com/google/guava"),
        Entry("scrcpy", "Apache-2.0", "以 shell 身份取得系统上下文的做法仿照 scrcpy,相关源文件头部保留出处。"),
        Entry("通话功能（calls/）实现", "Apache-2.0", "通话功能（拨号、接听、挂断、AI 接听、人工接管、PCM 音频桥、实时语音客户端）按 Apache-2.0 许可并入;实时语音经火山引擎（Volcengine）的 WebSocket 接口。"),
        Entry("Alpine Linux", "各软件包各自的许可证", RUNTIME_NOTE),
        Entry("XZ for Java", "0BSD", "版本 1.10,首次启动时解压自带的运行环境。https://tukaani.org/xz/java.html"),
        Entry("ZXing", "Apache-2.0", "版本 3.5.3,在本机生成微信绑定二维码。https://github.com/zxing/zxing"),
        Entry("commonmark-java", "BSD-2-Clause", "版本 0.30.1(含表格、删除线扩展),把回答里的 Markdown 解析成结构后显示。Copyright (c) 2015, Atlassian Pty Ltd. https://github.com/commonmark/commonmark-java"),
        Entry("xterm.js", "MIT", "版本 5.5.0(及 @xterm/addon-fit 0.10.0),用户终端页的终端仿真器。https://github.com/xtermjs/xterm.js"),
    )
}

/**
 * 许可证页(关于页底部「Apache-2.0 开源许可」进来):先是许可证原文(assets/licenses/Apache-2.0.txt),
 * 下面列出随 Mika 发布的第三方组件,一个组件一行(名称、许可证),说明在行下面;页脚指向完整文本。
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val licenseText = remember {
        runCatching { context.assets.open(LICENSE_ASSET).use { it.readBytes().decodeToString() } }.getOrDefault("")
    }
    val runtime = remember {
        runCatching { context.assets.open("runtime/packages.json").use { Licenses.runtimePackages(it.readBytes().decodeToString()) } }
            .getOrDefault(emptyList())
    }
    val thirdParty = Licenses.ENTRIES.filter { it.name != "Mika" } + runtime
    SubPage(AboutCopy.LICENSE_TITLE, onBack) {
        if (licenseText.isNotEmpty()) {
            MikaCard(Modifier.padding(bottom = 12.dp).testTag("license-text")) {
                Text(
                    licenseText.trimEnd(), Modifier.padding(16.dp),
                    style = MikaType.Hint.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 19.sp),
                    color = MikaTheme.colors.ink2,
                )
            }
        }
        GroupLabel(stringResource(R.string.licenses_third_party, thirdParty.size))
        GroupCard {
            thirdParty.forEachIndexed { i, e ->
                Column {
                    ListRow(null, e.name, e.license, first = i == 0)
                    Text(e.note, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MikaType.Hint, color = MikaTheme.colors.ink2)
                }
            }
        }
        FooterNote(stringResource(R.string.licenses_footer))
    }
}

private const val LICENSE_ASSET = "licenses/Apache-2.0.txt"
