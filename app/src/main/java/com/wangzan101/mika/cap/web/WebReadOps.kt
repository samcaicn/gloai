package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.cap.web.PageText
import com.wangzan101.mika.core.cap.web.PageDigest
import com.wangzan101.mika.core.cap.web.PageRead
import com.wangzan101.mika.core.cap.web.WebBarrier
import com.wangzan101.mika.core.cap.web.WebDialogOpen
import com.wangzan101.mika.core.cap.web.WebTabCrashed
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.cap.web.SettlePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.min
import com.wangzan101.mika.cap.web.WebScreen.Companion.QUIET_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CAP_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CONTENT_POLL_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CONTENT_STABLE_POLLS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CONTENT_EMPTY_POLLS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CONTENT_CAP_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CONTENT_AFTER_SCROLL_CAP_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.BACKGROUND_READS
import com.wangzan101.mika.cap.web.WebScreen.Companion.backgroundReads
import com.wangzan101.mika.cap.web.WebScreen.Companion.BACKGROUND_CRASHED
import com.wangzan101.mika.cap.web.WebScreen.Companion.BACKGROUND_DOWNLOAD
import com.wangzan101.mika.cap.web.WebScreen.Companion.BACKGROUND_DIALOG
import com.wangzan101.mika.cap.web.WebScreen.Companion.NO_POOL
import com.wangzan101.mika.cap.web.WebScreen.Companion.NAVIGATE_TIMEOUT_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CLEARTEXT

// 网页屏:按标签页提炼正文与后台读取
/**
 * 读第一段([PageRead.lazy] 且 offset 为 0)前先等正文稳定,滚几屏触发懒加载再等一次,读完滚回原处;
 * 之后的段不等不滚。正文怎么拼、怎么折叠、质量怎么判见 [PageDigest]。
 */
internal suspend fun WebScreen.digestTab(tab: WebTab, req: PageRead): PageText {
    val s = script(tab)
    val first = req.lazy && req.offset == 0
    var lazy: String? = null
    try {
        if (first) {
            contentStable(tab, CONTENT_CAP_MS)
            lazy = s.startLazy()
            if (lazy != null) contentStable(tab, CONTENT_AFTER_SCROLL_CAP_MS)
        }
        val raw = s.digest()
        if (tab.crashed) throw WebTabCrashed(tab.id)
        raw ?: return PageText(url(tab).orEmpty(), title(tab), "", emptyList(), emptyList(), false, null)
        val d = PageDigest.parse(raw)
        val r = PageDigest.render(d, req.unfold)
        val status = tab.httpErrorStatus
        val pageUrl = d.url.ifEmpty { url(tab).orEmpty() }
        val check = WebBarrier.classify(PageDigest.barrierFacts(d, status, WebUrlPolicy.host(pageUrl)))
        val start = req.offset.coerceIn(0, r.text.length)
        val end = min(r.text.length, start + req.maxChars)
        return PageText(
            url = pageUrl, title = d.title, text = r.text.substring(start, end), links = r.links, images = d.images,
            truncated = end < r.text.length, nextOffset = end.takeIf { it < r.text.length },
            quality = PageDigest.quality(d, r, check, status),
        )
    } finally {
        lazy?.let { s.endLazy(it) }
    }
}

/** 正文(长度, 摘要)连续几次不变才算稳定,至多 [capMs]。 */
internal suspend fun WebScreen.contentStable(tab: WebTab, capMs: Long): Boolean = SettlePolicy.contentStable(
    clock, { if (tab.crashed) null else script(tab).contentSample() }, CONTENT_POLL_MS, CONTENT_STABLE_POLLS, CONTENT_EMPTY_POLLS, capMs,
)

/**
 * 在临时后台标签里打开 [url] 读一段,读完关掉(出错、取消也关)。同时至多 [BACKGROUND_READS] 个(全进程)。
 * 打不开、变成下载、崩溃都如实放在 [PageText.netError] 里。
 */
internal suspend fun WebScreen.readInBackground(req: PageRead, url: String): PageText {
    val pool = pool ?: throw UnsupportedOperationException(NO_POOL)
    val task = taskId!!
    return backgroundReads.withPermit {
        val tab = withContext(Dispatchers.Main) { pool.openBackground(task, host.width, host.height) }
        fun failed(error: String) = PageText(url, "", "", emptyList(), emptyList(), false, null, netError = error)
        try {
            tab.load(url, NAVIGATE_TIMEOUT_MS)
            if (!tab.crashed && tab.mainFrameError?.contains(CLEARTEXT) == true && url.startsWith("http://", ignoreCase = true)) {
                tab.load("https://" + url.substring("http://".length), NAVIGATE_TIMEOUT_MS)
            }
            when {
                tab.crashed -> failed(BACKGROUND_CRASHED)
                tab.loadBecameDownload -> failed(BACKGROUND_DOWNLOAD)
                tab.mainFrameError != null -> failed(tab.mainFrameError!!)
                else -> {
                    if (tab.lastLoadFinished) awaitQuiet(tab, QUIET_MS, CAP_MS)
                    // 和读当前标签同一道关:页面弹着 JS 对话框时不读(页面脚本此时不回话);临时标签马上就关,如实说
                    try { dialogGuard(tab); digestTab(tab, req) } catch (e: WebTabCrashed) { failed(BACKGROUND_CRASHED) } catch (e: WebDialogOpen) { failed(BACKGROUND_DIALOG) }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { pool.close(tab.id) }
        }
    }
}
