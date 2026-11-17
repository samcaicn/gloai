package com.wangzan101.mika.about

import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import javax.net.SocketFactory
import kotlin.coroutines.coroutineContext

/**
 * 应用内更新下载,用于绑定 Wi-Fi 的自动下载以及使用应用代理的手动下载。
 * 先写到 `<目标>.part`,下完整了再改名成目标文件,所以「目标文件在」就是下好了。
 * 进程里只有一份(同一时间只下一个);进程被杀后没下完的算失败,用户重试即可。
 */
class ProxiedApkDownload(
    private val http: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Volatile private var job: Job? = null
    @Volatile private var call: Call? = null
    @Volatile private var target: File? = null
    @Volatile private var bytes = 0L
    @Volatile private var total = -1L

    /**
     * 按 [sources] 的顺序下载,前一个失败就换下一个;每个来源下完都先过 [verify](大小、摘要),
     * 不通过也当这个来源失败。全部失败时进程里不留目标文件,状态回到 Failed,用户可重试。
     */
    @Synchronized
    fun start(
        sources: List<String>,
        file: File,
        wifi: Network? = null,
        verify: (File) -> Unit = {},
    ) {
        require(sources.isNotEmpty()) { "no download source" }
        cancel()
        target = file
        bytes = 0
        total = -1
        // 使用 Wi-Fi 自己的 DNS 和 socket，断开后连接失败，不会切到移动数据继续下载。
        val client = wifi?.let { network ->
            wifiBoundClient(http, network.socketFactory, object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
        } ?: http
        val download = scope.launch(start = CoroutineStart.LAZY) {
            val current = coroutineContext[Job]!!
            for (url in sources) {
                coroutineContext.ensureActive()
                synchronized(this@ProxiedApkDownload) {
                    if (job === current) { bytes = 0; total = -1 }
                }
                try {
                    fetch(client, url, file, onCall = { next ->
                        synchronized(this@ProxiedApkDownload) {
                            if (job === current && current.isActive) call = next else next.cancel()
                        }
                    }) { done, size ->
                        synchronized(this@ProxiedApkDownload) {
                            if (job === current) { bytes = done; total = size }
                        }
                    }
                    coroutineContext.ensureActive()
                    verify(file)
                    coroutineContext.ensureActive()
                    return@launch
                } catch (e: CancellationException) {
                    file.delete()
                    throw e
                } catch (_: Exception) {
                    // 取消导致的连接异常不能触发下一个来源。
                    file.delete()
                    coroutineContext.ensureActive()
                }
            }
        }
        job = download
        download.start()
    }

    /** [file] 这一份的状态:正在下、下好了,或失败(没在下、也没有完整的文件)。 */
    @Synchronized
    fun state(file: File): ApkDownloadState = when {
        job?.isActive == true && target == file -> ApkDownloadState(DownloadPhase.Downloading, bytes, total)
        file.isFile -> ApkDownloadState(DownloadPhase.Ready, file.length(), file.length())
        else -> ApkDownloadState(DownloadPhase.Failed)
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        // 连带掐断连接,不等读完这一块
        call?.cancel()
        job = null
        call = null
        target?.let { File(it.path + PART).delete() }
    }

    companion object {
        private const val PART = ".part"

        /** 每次路由（包括重定向）都按目标选代理，禁止绕过 Wi-Fi socket factory 的 SOCKS。 */
        internal fun wifiBoundClient(http: OkHttpClient, sockets: SocketFactory, dns: Dns): OkHttpClient {
            val delegate = http.proxySelector
            val selector = object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    val proxies = http.proxy?.let { listOf(it) } ?: delegate.select(uri)
                    if (proxies.any { it.type() == Proxy.Type.SOCKS }) {
                        // IOException is retried by OkHttp during route selection, so fail without retrying.
                        throw IllegalStateException("SOCKS proxy cannot be bound to Wi-Fi")
                    }
                    return proxies
                }

                override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
                    delegate.connectFailed(uri, address, error)
                }
            }
            return http.newBuilder().proxy(null).proxySelector(selector).socketFactory(sockets).dns(dns)
                .connectionPool(ConnectionPool()).build()
        }

        /** 把 [url] 下到 [file]:边下边报进度(已下、总共,不知道总共为 -1);不是 2xx、或没下完整就抛错,不留目标文件。 */
        suspend fun fetch(http: OkHttpClient, url: String, file: File, onCall: (Call) -> Unit = {}, progress: (Long, Long) -> Unit) {
            val part = File(file.path + PART)
            try {
                val call = http.newCall(Request.Builder().url(url).build())
                onCall(call)
                coroutineContext.ensureActive()
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    val size = body.contentLength()
                    var done = 0L
                    progress(0, size)
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                progress(done, size)
                            }
                        }
                    }
                    if (size >= 0 && done != size) throw IOException("incomplete download")
                }
                coroutineContext.ensureActive()
                if (!part.renameTo(file)) throw IOException("cannot move download")
            } finally {
                part.delete()
            }
        }
    }
}
