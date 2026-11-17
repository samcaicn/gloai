package com.wangzan101.mika.about

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.net.SocketFactory

/** 设了网络代理时更新包由 Mika 自己经代理下:请求确实发给代理,下完整才留下文件,出错不留半截。 */
class ProxiedApkDownloadTest {
    private lateinit var proxy: MockWebServer
    private val dir: File = Files.createTempDirectory("apk").toFile()

    @Before fun setUp() { proxy = MockWebServer().also { it.start() } }
    @After fun tearDown() { proxy.shutdown() }

    private fun client() = NetProxy { "http://${proxy.hostName}:${proxy.port}" }.client()

    @Test fun downloadsThroughTheProxyWithProgress() = runTest {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        proxy.enqueue(MockResponse().setBody(okio.Buffer().write(body)))
        val target = File(dir, "update.apk")
        val seen = mutableListOf<Pair<Long, Long>>()
        ProxiedApkDownload.fetch(client(), "http://releases.example.test/mika.apk", target) { done, total -> seen += done to total }
        assertTrue(target.readBytes().contentEquals(body))
        assertEquals("GET http://releases.example.test/mika.apk HTTP/1.1", proxy.takeRequest().requestLine)
        assertEquals(body.size.toLong() to body.size.toLong(), seen.last())
        assertFalse(File(dir, "update.apk.part").exists())
    }

    @Test fun failedDownloadLeavesNoFile() = runTest {
        proxy.enqueue(MockResponse().setResponseCode(404))
        val target = File(dir, "update.apk")
        try {
            ProxiedApkDownload.fetch(client(), "http://releases.example.test/mika.apk", target) { _, _ -> }
            fail("expected an error")
        } catch (e: IOException) {
            assertFalse(target.exists())
            assertFalse(File(dir, "update.apk.part").exists())
        }
    }

    @Test fun wifiRoutesRedirectsByEachTargetHost() = runTest {
        val local = MockWebServer().also { it.start() }
        try {
            val http = ProxiedApkDownload.wifiBoundClient(client(), SocketFactory.getDefault(), Dns.SYSTEM)
            proxy.enqueue(MockResponse().setResponseCode(302).setHeader("Location", local.url("/local")))
            local.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://remote.test/final"))
            proxy.enqueue(MockResponse().setBody("good"))
            val target = File(dir, "redirect.apk")
            ProxiedApkDownload.fetch(http, "http://remote.test/start", target) { _, _ -> }
            assertEquals("good", target.readText())
            assertEquals("GET http://remote.test/start HTTP/1.1", proxy.takeRequest().requestLine)
            assertEquals("/local", local.takeRequest().path)
            assertEquals("GET http://remote.test/final HTTP/1.1", proxy.takeRequest().requestLine)
        } finally {
            local.shutdown()
        }
    }

    @Test fun wifiSocksFailureFallsBackToLocalSource() = runTest {
        val local = MockWebServer().also { it.start() }
        try {
            val socks = NetProxy { "socks5://127.0.0.1:1080" }.client()
            val http = ProxiedApkDownload.wifiBoundClient(socks, SocketFactory.getDefault(), Dns.SYSTEM)
            local.enqueue(MockResponse().setBody("good"))
            val target = File(dir, "socks-fallback.apk")
            val download = ProxiedApkDownload(http, this)
            download.start(listOf("http://remote.test/apk", local.url("/apk").toString()), target)
            advanceUntilIdle()
            assertEquals(DownloadPhase.Ready, download.state(target).phase)
            assertEquals("good", target.readText())
            assertEquals("/apk", local.takeRequest().path)
        } finally {
            local.shutdown()
        }
    }

    @Test fun wifiRejectsSocksOnRedirect() = runTest {
        val local = MockWebServer().also { it.start() }
        try {
            val socks = NetProxy { "socks5://127.0.0.1:1080" }.client()
            val http = ProxiedApkDownload.wifiBoundClient(socks, SocketFactory.getDefault(), Dns.SYSTEM)
            local.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://remote.test/apk"))
            val target = File(dir, "socks-redirect.apk")
            try {
                ProxiedApkDownload.fetch(http, local.url("/start").toString(), target) { _, _ -> }
                fail("expected SOCKS to be rejected")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("SOCKS"))
                assertFalse(target.exists())
            }
        } finally {
            local.shutdown()
        }
    }

    @Test fun stateFollowsTheFile() {
        val d = ProxiedApkDownload(client())
        val target = File(dir, "update.apk")
        assertEquals(DownloadPhase.Failed, d.state(target).phase)
        target.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(DownloadPhase.Ready, d.state(target).phase)
    }

    /** 镜像失败就换下一个来源;失败的那个不留半截文件。 */
    @Test fun startFallsBackToTheNextSource() = runTest {
        proxy.enqueue(MockResponse().setResponseCode(404))
        proxy.enqueue(MockResponse().setBody("good"))
        val target = File(dir, "fallback.apk")
        val d = ProxiedApkDownload(client(), this)
        d.start(listOf("http://mirror.test/mika.apk", "http://releases.example.test/mika.apk"), target)
        advanceUntilIdle()
        assertEquals(DownloadPhase.Ready, d.state(target).phase)
        assertEquals("good", target.readText())
        assertEquals(2, proxy.requestCount)
    }

    /** 下完了但校验(大小/摘要)不过,也算这个来源失败,继续试下一个。 */
    @Test fun verificationFailureMovesOnToTheNextSource() = runTest {
        proxy.enqueue(MockResponse().setBody("corrupt"))
        proxy.enqueue(MockResponse().setBody("good"))
        val target = File(dir, "verified.apk")
        val d = ProxiedApkDownload(client(), this)
        d.start(listOf("http://mirror.test/mika.apk", "http://releases.example.test/mika.apk"), target) { file ->
            if (file.readText() != "good") throw IOException("digest mismatch")
        }
        advanceUntilIdle()
        assertEquals("good", target.readText())
        assertEquals(2, proxy.requestCount)
    }

    @Test fun missingMetadataStillFallsBackOnApkIdentityFailures() = runTest {
        for ((reason, size) in ApkRejected.Reason.entries.flatMap { reason -> listOf(reason to null, reason to 4L) }) {
            proxy.enqueue(MockResponse().setBody("bad!"))
            proxy.enqueue(MockResponse().setBody("good"))
            val target = File(dir, "identity-$reason.apk")
            val d = ProxiedApkDownload(client(), this)
            d.start(listOf("http://mirror.test/mika.apk", "http://releases.example.test/mika.apk"), target) { file ->
                // 两个文件大小相同,缺少摘要时大小校验无法识别错误包。
                ApkIntegrity.verify(file, size, null)
                if (file.readText() == "bad!") throw ApkRejected(reason)
            }
            advanceUntilIdle()
            assertEquals(DownloadPhase.Ready, d.state(target).phase)
            assertEquals("good", target.readText())
        }
        assertEquals(4 * ApkRejected.Reason.entries.size, proxy.requestCount)
    }

    @Test fun cancellationDuringVerificationDoesNotTryAnotherSource() = runTest {
        proxy.enqueue(MockResponse().setBody("bad"))
        proxy.enqueue(MockResponse().setBody("good"))
        val target = File(dir, "cancelled.apk")
        val d = ProxiedApkDownload(client(), this)
        d.start(listOf("http://mirror.test/mika.apk", "http://releases.example.test/mika.apk"), target) {
            d.cancel()
            throw IOException("cancelled while verifying")
        }
        advanceUntilIdle()
        assertEquals(1, proxy.requestCount)
        assertEquals(DownloadPhase.Failed, d.state(target).phase)
        assertFalse(target.exists())
    }

    @Test fun cancellationBeforeExecuteSendsNoRequest() = runTest {
        val target = File(dir, "before-execute.apk")
        var cancelled = false
        val task = launch {
            val context = currentCoroutineContext()
            try {
                ProxiedApkDownload.fetch(client(), "http://mirror.test/mika.apk", target, onCall = {
                    context.cancel()
                }) { _, _ -> }
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        task.join()
        assertTrue(cancelled)
        assertEquals(0, proxy.requestCount)
        assertFalse(target.exists())
    }

    /** 所有来源都失败:状态是 Failed,目标文件和 .part 都不留下。 */
    @Test fun allSourcesFailingLeavesNoFile() = runTest {
        proxy.enqueue(MockResponse().setResponseCode(404))
        proxy.enqueue(MockResponse().setResponseCode(404))
        val target = File(dir, "failed.apk")
        val d = ProxiedApkDownload(client(), this)
        d.start(listOf("http://mirror.test/mika.apk", "http://releases.example.test/mika.apk"), target)
        advanceUntilIdle()
        assertEquals(DownloadPhase.Failed, d.state(target).phase)
        assertFalse(target.exists())
        assertFalse(File(dir, "failed.apk.part").exists())
    }
}
