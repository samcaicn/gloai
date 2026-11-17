package com.wangzan101.mika.about

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 检查更新的三种来源:GitHub API;镜像把 `/releases/latest` 跳转(302);镜像自己跟到发布页(200)。
 * 发布页兜底时从附件页读 APK 文件名和 sha256。
 */
class UpdateProbeTest {
    private val server = MockWebServer()
    private lateinit var api: String
    private lateinit var releases: String
    private lateinit var mirror: String

    @Before fun start() {
        server.start()
        api = server.url("/api").toString()
        releases = server.url("/releases").toString()
        mirror = server.url("/mirror/").toString()
    }

    @After fun stop() = server.shutdown()

    private fun dispatcher(block: (String) -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = block(request.path.orEmpty())
    }

    private val digest = "sha256:" + "a".repeat(64)

    private fun assetsPage(vararg apks: Pair<String, String?>) = buildString {
        append("<ul>")
        for ((name, sha) in apks) {
            append("<li><a href=\"/wangzan101/Mika/releases/download/v0.3.0/$name\">$name</a>")
            sha?.let { append("<span>sha256:$it</span>") }
            append("</li>")
        }
        append("</ul>")
    }

    @Test fun readsReleaseFromGitHubApi() = runTest {
        server.dispatcher = dispatcher {
            MockResponse().setBody(
                """{"tag_name":"v0.3.0","name":"Mika 0.3.0","body":"- 新东西","published_at":"2026-11-01T00:00:00Z",
                   "html_url":"https://github.com/wangzan101/Mika/releases/tag/v0.3.0",
                   "assets":[{"name":"Mika-v0.3.0.apk","browser_download_url":"https://github.com/wangzan101/Mika/releases/download/v0.3.0/Mika-v0.3.0.apk","size":123,"digest":"$digest"}]}""",
            )
        }
        val found = UpdateProbe.fetch(UpdateChecker.defaultClient(), "", api, releases, emptyList(), "Mika/test")
        assertNotNull(found)
        assertEquals("0.3.0", found!!.version)
        assertEquals("https://github.com/wangzan101/Mika/releases/download/v0.3.0/Mika-v0.3.0.apk", found.apkUrl)
        assertEquals(123L, found.apkSize)
        assertEquals(digest, found.apkDigest)
    }

    @Test fun rejectsOversizedMetadataWithoutAContentLength() = runTest {
        val metadata = """{"tag_name":"v0.3.0","assets":[]}"""
        server.enqueue(MockResponse().setChunkedBody(metadata.padEnd(2 * 1024 * 1024 + 1), 8192))
        val found = UpdateProbe.fetch(UpdateChecker.defaultClient(), mirror, api, releases, emptyList(), "Mika/test")
        assertNull(found)
        assertEquals(1, server.requestCount)
    }

    @Test fun rejectsOversizedReleaseAndAssetPages() = runTest {
        val releasePage = """<a href="/releases/tag/v0.3.0">latest</a>"""
        server.dispatcher = dispatcher { path ->
            when {
                path.endsWith("/api") -> MockResponse().setResponseCode(403)
                path.endsWith("/latest") -> MockResponse().setBody(releasePage.padEnd(2 * 1024 * 1024 + 1))
                else -> MockResponse().setResponseCode(404)
            }
        }
        assertNull(UpdateProbe.fetch(UpdateChecker.defaultClient(), mirror, api, releases, emptyList(), "Mika/test"))
        server.dispatcher = dispatcher { path ->
            when {
                path.endsWith("/api") -> MockResponse().setResponseCode(403)
                path.endsWith("/latest") -> MockResponse().setBody(releasePage)
                else -> MockResponse().setBody(assetsPage("Mika-v0.3.0.apk" to null).padEnd(2 * 1024 * 1024 + 1))
            }
        }
        assertNull(UpdateProbe.fetch(UpdateChecker.defaultClient(), mirror, api, releases, emptyList(), "Mika/test"))
    }

    /** 镜像不代理 API(403)时退到发布页:302 的 Location 里取 tag,再从附件页取文件名和摘要。 */
    @Test fun mirrorApi403FallsBackToRedirectingReleasePage() = runTest {
        server.dispatcher = dispatcher { path ->
            when {
                path.endsWith("/api") -> MockResponse().setResponseCode(403)
                path.endsWith("/releases/latest") ->
                    MockResponse().setResponseCode(302)
                        .setHeader("Location", "https://github.com/wangzan101/Mika/releases/tag/v0.3.0")
                path.endsWith("/expanded_assets/v0.3.0") ->
                    MockResponse().setBody(assetsPage(
                        "Mika-v0.3.0-arm64.apk" to "b".repeat(64),
                        "Mika-v0.3.0.apk" to "a".repeat(64),
                    ))
                else -> MockResponse().setResponseCode(404)
            }
        }
        // 镜像设置下 API 的 403 只说明它不代理 API,还要继续试发布页。
        val found = UpdateProbe.fetch(UpdateChecker.defaultClient(), mirror, api, releases, listOf("arm64-v8a"), "Mika/test")
        assertNotNull(found)
        assertEquals("0.3.0", found!!.version)
        assertEquals(releases.trimEnd('/') + "/download/v0.3.0/Mika-v0.3.0-arm64.apk", found.apkUrl)
        assertEquals("sha256:" + "b".repeat(64), found.apkDigest)
        assertTrue(found.pageUrl.endsWith("/tag/v0.3.0"))
    }

    /** 有的镜像自己跟到底:`/releases/latest` 直接返回 200 的发布页。 */
    @Test fun mirrorServingLatestPageDirectlyIsRead() = runTest {
        server.dispatcher = dispatcher { path ->
            when {
                path.endsWith("/api") -> MockResponse().setResponseCode(403)
                path.endsWith("/releases/latest") -> MockResponse().setBody(
                    "<html><a href=\"/wangzan101/Mika/releases/tag/v0.4.0\">latest</a></html>",
                )
                path.endsWith("/expanded_assets/v0.4.0") -> MockResponse().setBody(assetsPage("Mika-v0.4.0.apk" to "c".repeat(64)))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val found = UpdateProbe.fetch(UpdateChecker.defaultClient(), mirror, api, releases, emptyList(), "Mika/test")
        assertEquals("0.4.0", found?.version)
        assertEquals("sha256:" + "c".repeat(64), found?.apkDigest)
    }

    /** 附件页读不到时按固定命名拼出全架构包地址,至少下载时还能试。 */
    @Test fun missingAssetPageStillProducesTheUniversalApkUrl() = runTest {
        server.dispatcher = dispatcher { path ->
            when {
                path.endsWith("/api") -> MockResponse().setResponseCode(403)
                path.endsWith("/releases/latest") ->
                    MockResponse().setResponseCode(302).setHeader("Location", "https://github.com/wangzan101/Mika/releases/tag/v0.5.0")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val found = UpdateProbe.fetch(UpdateChecker.defaultClient(), mirror, api, releases, emptyList(), "Mika/test")
        assertEquals("0.5.0", found?.version)
        assertEquals(releases.trimEnd('/') + "/download/v0.5.0/Mika-v0.5.0.apk", found?.apkUrl)
        assertNull(found?.apkDigest)
    }

    @Test fun publishedAssetsWithoutACompatibleApkOnlyOfferTheReleasePage() = runTest {
        for (assets in listOf(assetsPage("Mika-v0.3.0-arm64.apk" to null), assetsPage())) {
            server.dispatcher = dispatcher { path ->
                when {
                    path.endsWith("/api") -> MockResponse().setResponseCode(403)
                    path.endsWith("/releases/latest") ->
                        MockResponse().setResponseCode(302).setHeader("Location", "/releases/tag/v0.3.0")
                    else -> MockResponse().setBody(assets)
                }
            }
            val found = UpdateProbe.fetch(UpdateChecker.defaultClient(), mirror, api, releases, listOf("x86_64"), "Mika/test")
            assertEquals("0.3.0", found?.version)
            assertEquals("$releases/tag/v0.3.0", found?.pageUrl)
            assertNull(found?.apkUrl)
        }
    }

    /** 直连 GitHub 时 API 明确回了 HTTP 错误(限流等),就认这一次没查到,不再多打一个网页请求。 */
    @Test fun directGitHubApiFailureDoesNotFetchTheReleasePage() = runTest {
        var requests = 0
        server.dispatcher = dispatcher {
            requests++
            MockResponse().setResponseCode(500)
        }
        assertNull(UpdateProbe.fetch(UpdateChecker.defaultClient(), "", api, releases, emptyList(), "Mika/test"))
        assertEquals(1, requests)
    }

    /** 网络错误(不是 HTTP 回应)时直连也退到发布页:GitHub 本身可能只是 API 不通。 */
    @Test fun directNetworkFailureFallsBackToReleasePage() = runTest {
        server.dispatcher = dispatcher { path ->
            when {
                path.endsWith("/api") -> MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)
                path.endsWith("/releases/latest") ->
                    MockResponse().setResponseCode(302).setHeader("Location", "https://github.com/wangzan101/Mika/releases/tag/v0.6.0")
                path.endsWith("/expanded_assets/v0.6.0") -> MockResponse().setBody(assetsPage("Mika-v0.6.0.apk" to null))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val found = UpdateProbe.fetch(UpdateChecker.defaultClient(), "", api, releases, emptyList(), "Mika/test")
        assertEquals("0.6.0", found?.version)
        assertNull(found?.apkDigest)
    }

    @Test fun cancellationInterruptsSlowResponseBody() = runBlocking {
        val headersReceived = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) {
                headersReceived.countDown()
            }
        }).build()
        server.enqueue(MockResponse().setBody("""{"tag_name":"v0.3.0","assets":[]}""")
            .setBodyDelay(3, TimeUnit.SECONDS))
        val job = launch(Dispatchers.IO) {
            UpdateProbe.fetch(client, "", api, releases, emptyList(), "Mika/test")
        }
        assertTrue(withContext(Dispatchers.IO) { headersReceived.await(2, TimeUnit.SECONDS) })
        delay(200) // 响应头已到,响应体仍在等待,让读取进入阻塞状态。
        withTimeout(1_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test fun mirrorTestLimitsTheWholeProbe() = runBlocking {
        val latestStarted = CountDownLatch(1)
        server.dispatcher = dispatcher { path ->
            if (path.endsWith("/api")) {
                MockResponse().setResponseCode(403).setHeadersDelay(150, TimeUnit.MILLISECONDS)
            } else {
                latestStarted.countDown()
                MockResponse().setBody("<a href=\"/releases/tag/v0.3.0\">latest</a>")
                    .setBodyDelay(2, TimeUnit.SECONDS)
            }
        }
        val start = System.nanoTime()
        val result = UpdateProbe.test(mirror, OkHttpClient(), api, releases, timeoutMs = 500)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(latestStarted.await(1, TimeUnit.SECONDS))
        assertNull(result)
        assertTrue("probe took ${elapsed}ms", elapsed < 1500)
    }

    @Test fun allSourcesFailingMeansNoResult() = runTest {
        server.dispatcher = dispatcher { MockResponse().setResponseCode(500) }
        assertNull(UpdateProbe.fetch(UpdateChecker.defaultClient(), mirror, api, releases, emptyList(), "Mika/test"))
    }
}
