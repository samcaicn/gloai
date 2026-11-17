@file:Suppress("UNCHECKED_CAST", "USELESS_CAST", "INAPPLICABLE_JVM_NAME", "UNUSED_ANONYMOUS_PARAMETER", "NAME_SHADOWING", "UNNECESSARY_NOT_NULL_ASSERTION")
package uts.sdk.modules.LGNearbyShare
import io.dcloud.uniapp.*
import io.dcloud.uniapp.extapi.*
import io.dcloud.uts.*
import io.dcloud.uts.Map
import io.dcloud.uts.Set
import io.dcloud.uts.UTSAndroid
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.Runnable
import java.lang.Thread
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
var server: ServerSocket? = null
var running = false
var transferred: Number = 0
var total: Number = 0
var bodyLen: Number = 0
@JvmField
var assets: UTSArray<String> = _uA()
var bodyServed = false
var servedAssets: Number = 0
open class IfaceInfo {
    open var name: String = ""
    open var ip: String = ""
    constructor(name: String, ip: String){
        this.name = name
        this.ip = ip
    }
}
fun normPath(p: String): String {
    var s = if (p == null) {
        ""
    } else {
        p
    }
    s = s.trim()
    if (s.indexOf("file://") == 0) {
        s = s.substring(7)
    }
    return s
}
fun mimeOf(name: String): String {
    val lower = name.toLowerCase()
    val dot = lower.lastIndexOf(".")
    val ext = if (dot >= 0) {
        lower.substring(dot + 1)
    } else {
        ""
    }
    if (ext == "mp4") {
        return "video/mp4"
    }
    if (ext == "m4v") {
        return "video/x-m4v"
    }
    if (ext == "mov") {
        return "video/quicktime"
    }
    if (ext == "webm") {
        return "video/webm"
    }
    if (ext == "png") {
        return "image/png"
    }
    if (ext == "webp") {
        return "image/webp"
    }
    if (ext == "gif") {
        return "image/gif"
    }
    if (ext == "bmp") {
        return "image/bmp"
    }
    if (ext == "jpg" || ext == "jpeg") {
        return "image/jpeg"
    }
    if (ext == "mp3") {
        return "audio/mpeg"
    }
    if (ext == "wav") {
        return "audio/wav"
    }
    if (ext == "m4a" || ext == "aac") {
        return "audio/mp4"
    }
    if (ext == "ogg") {
        return "audio/ogg"
    }
    if (ext == "amr") {
        return "audio/amr"
    }
    if (ext == "flac") {
        return "audio/flac"
    }
    return "application/octet-stream"
}
fun scoreOf(name: String, ip: String): Number {
    val n = name.toLowerCase()
    if (n.indexOf("wlan") >= 0 || n.indexOf("wifi") >= 0 || n.indexOf("softap") >= 0 || n.indexOf("p2p") >= 0 || n.indexOf("ap") == 0 || n.indexOf("eth") == 0) {
        return 0
    }
    if (ip.indexOf("192.168.") == 0) {
        return 1
    }
    if (ip.indexOf("10.") == 0) {
        return 2
    }
    return 3
}
fun collectIfaces(): UTSArray<IfaceInfo> {
    val list: UTSArray<IfaceInfo> = _uA()
    try {
        val en = NetworkInterface.getNetworkInterfaces()
        while(en.hasMoreElements()){
            val ni = en.nextElement() as NetworkInterface
            var name = ""
            try {
                val nm = ni.getName()
                if (nm != null) {
                    name = nm
                }
            }
             catch (e: Throwable) {
                name = ""
            }
            try {
                if (!ni.isUp()) {
                    continue
                }
                if (ni.isLoopback()) {
                    continue
                }
            }
             catch (e: Throwable) {
                continue
            }
            val addrs = ni.getInetAddresses()
            while(addrs.hasMoreElements()){
                val a = addrs.nextElement() as InetAddress
                if (a.isLoopbackAddress()) {
                    continue
                }
                if (a.isLinkLocalAddress()) {
                    continue
                }
                val host = a.getHostAddress()
                if (host == null) {
                    continue
                }
                if (host.indexOf(":") >= 0) {
                    continue
                }
                list.push(IfaceInfo(name, host))
            }
        }
    }
     catch (e: Throwable) {
        console.log("LG-NearbyShare 读取本机地址失败: " + e)
    }
    run {
        var i: Number = 0
        while(i < list.length){
            var m = i
            run {
                var j = i + 1
                while(j < list.length){
                    if (scoreOf(list[j].name, list[j].ip) < scoreOf(list[m].name, list[m].ip)) {
                        m = j
                    }
                    j++
                }
            }
            if (m != i) {
                val tmp = list[i]
                list[i] = list[m]
                list[m] = tmp
            }
            i++
        }
    }
    return list
}
fun queryIntOf(path: String, key: String): Number {
    val i = path.indexOf(key)
    if (i < 0) {
        return -1
    }
    var s = path.substring(i + key.length)
    val amp = s.indexOf("&")
    if (amp >= 0) {
        s = s.substring(0, amp)
    }
    var n: Number = 0
    var got: Number = 0
    val DIGITS = "0123456789"
    run {
        var k: Number = 0
        while(k < s.length){
            val d = DIGITS.indexOf(s.substring(k, k + 1))
            if (d < 0) {
                break
            }
            n = n * 10 + d
            got++
            k++
        }
    }
    return if (got > 0) {
        n
    } else {
        -1
    }
}
fun shutdown(): Unit {
    running = false
    val srv = server
    server = null
    if (srv != null) {
        try {
            srv.close()
        }
         catch (e: Throwable) {}
    }
}
fun maybeStop(): Unit {
    if (!bodyServed) {
        return
    }
    if (servedAssets < assets.length) {
        return
    }
    shutdown()
}
open class ServeTask : Runnable {
    open var body: String = ""
    open var fileName: String = ""
    open var token: String = ""
    constructor(body: String, fileName: String, token: String){
        this.body = body
        this.fileName = fileName
        this.token = token
    }
    open fun sendText(s: Socket, status: String, payload: String): Unit {
        val w = OutputStreamWriter(s.getOutputStream(), "UTF-8")
        w.write("HTTP/1.1 " + status + "\r\n")
        w.write("Content-Type: text/plain; charset=utf-8\r\n")
        w.write("Cache-Control: no-store\r\n")
        w.write("Connection: close\r\n")
        w.write("\r\n")
        w.write(payload)
        w.flush()
    }
    open fun sendFile(s: Socket, path: String): Number {
        val f = File(path)
        if (!f.exists() || !f.isFile()) {
            this.sendText(s, "404 Not Found", "{\"ok\":false}")
            return -1
        }
        val size = f.length()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: " + mimeOf(path) + "\r\n" + "Content-Length: " + size + "\r\n" + "Cache-Control: no-store\r\n" + "Connection: close\r\n\r\n"
        val os = s.getOutputStream()
        val hw = OutputStreamWriter(os, "UTF-8")
        hw.write(head)
        hw.flush()
        val fis = FileInputStream(f)
        val arr = ByteBuffer.allocate(65536.toInt()).array()
        var sent: Number = 0
        var n = fis.read(arr)
        while(n > 0){
            os.write(arr, 0, n)
            sent = sent + n
            n = fis.read(arr)
        }
        fis.close()
        os.flush()
        return sent
    }
    open fun handle(s: Socket): Unit {
        try {
            s.setSoTimeout(20000)
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), "UTF-8"))
            val line = reader.readLine()
            var path = ""
            if (line != null) {
                val a = line.indexOf(" ")
                if (a >= 0) {
                    val b = line.indexOf(" ", a + 1)
                    path = if (b > a) {
                        line.substring(a + 1, b)
                    } else {
                        line.substring(a + 1)
                    }
                }
            }
            var raw = reader.readLine()
            var guard: Number = 0
            while(raw != null && raw.length > 0 && guard < 40){
                raw = reader.readLine()
                guard++
            }
            val t = "t=" + this.token
            if (path.indexOf(t) < 0) {
                this.sendText(s, "404 Not Found", "{\"ok\":false}")
            } else if (path.indexOf("/file") == 0) {
                this.sendText(s, "200 OK", this.body)
                transferred = transferred + this.body.length
                bodyServed = true
                maybeStop()
            } else if (path.indexOf("/asset") == 0) {
                val idx = queryIntOf(path, "i=")
                if (idx < 0 || idx >= assets.length) {
                    this.sendText(s, "404 Not Found", "{\"ok\":false}")
                } else {
                    val n = this.sendFile(s, assets[idx])
                    if (n >= 0) {
                        transferred = transferred + n
                    }
                    servedAssets = servedAssets + 1
                    maybeStop()
                }
            } else if (path.indexOf("/info") == 0) {
                this.sendText(s, "200 OK", "{\"ok\":true,\"fileName\":\"" + this.fileName + "\",\"size\":" + this.body.length + ",\"files\":" + assets.length + "}")
            } else {
                this.sendText(s, "404 Not Found", "{\"ok\":false}")
            }
        }
         catch (e: Throwable) {
            console.log("LG-NearbyShare 请求处理失败: " + e)
        }
        try {
            s.close()
        }
         catch (e: Throwable) {}
    }
    override fun run(): Unit {
        val srv = server
        if (srv == null) {
            return
        }
        val startedAt = Date.now()
        while(running){
            if (Date.now() - startedAt > 600000) {
                break
            }
            var s: Socket? = null
            try {
                s = srv.accept()
            }
             catch (e: Throwable) {
                if (!running) {
                    break
                }
                continue
            }
            if (s == null) {
                continue
            }
            this.handle(s)
        }
        running = false
        try {
            srv.close()
        }
         catch (e: Throwable) {}
        if (server == srv) {
            server = null
        }
    }
}
fun bindServer(): ServerSocket? {
    val base: Number = 18080
    run {
        var i: Number = 0
        while(i < 20){
            try {
                val port = base + i
                return ServerSocket(port.toInt())
            }
             catch (e: Throwable) {}
            i++
        }
    }
    try {
        return ServerSocket(0)
    }
     catch (e: Throwable) {
        return null
    }
}
fun startServer(body: String, fileName: String, token: String): String {
    stopServer()
    val srv = bindServer()
    if (srv == null) {
        return "{\"ok\":false,\"error\":\"端口绑定失败\"}"
    }
    val port = srv.getLocalPort()
    server = srv
    running = true
    transferred = 0
    bodyServed = false
    servedAssets = 0
    assets = _uA()
    bodyLen = body.length
    total = bodyLen
    try {
        srv.setSoTimeout(30000)
    }
     catch (e: Throwable) {}
    Thread(ServeTask(body, fileName, token)).start()
    val list = collectIfaces()
    var ipJson = ""
    var ifJson = ""
    run {
        var i: Number = 0
        while(i < list.length){
            if (i > 0) {
                ipJson = ipJson + ","
                ifJson = ifJson + ","
            }
            ipJson = ipJson + "\"" + list[i].ip + "\""
            ifJson = ifJson + "{\"name\":\"" + list[i].name + "\",\"ip\":\"" + list[i].ip + "\"}"
            i++
        }
    }
    return ("{\"ok\":true,\"port\":" + port + ",\"ips\":[" + ipJson + "],\"ifaces\":[" + ifJson + "]" + ",\"token\":\"" + token + "\",\"error\":\"\"}")
}
fun setAssets(paths: UTSArray<String>): Unit {
    assets = _uA()
    var t = bodyLen
    run {
        var i: Number = 0
        while(i < paths.length){
            val p = normPath(paths[i])
            assets.push(p)
            if (p == "") {
                i++
                continue
            }
            val f = File(p)
            if (f.exists() && f.isFile()) {
                t = t + f.length()
            }
            i++
        }
    }
    total = t
}
fun stopServer(): Unit {
    running = false
    val srv = server
    server = null
    if (srv != null) {
        try {
            srv.close()
        }
         catch (e: Throwable) {}
    }
    assets = _uA()
    bodyLen = 0
    total = 0
    bodyServed = false
    servedAssets = 0
}
fun localIps(): String {
    val list = collectIfaces()
    var s = ""
    run {
        var i: Number = 0
        while(i < list.length){
            if (i > 0) {
                s = s + ","
            }
            s = s + list[i].ip
            i++
        }
    }
    return s
}
fun transferredCount(): Number {
    return transferred
}
fun totalCount(): Number {
    return total
}
fun isRunning(): Boolean {
    return running
}
fun startServerByJs(body: String, fileName: String, token: String): String {
    return startServer(body, fileName, token)
}
fun setAssetsByJs(paths: UTSArray<String>): Unit {
    return setAssets(paths)
}
fun stopServerByJs(): Unit {
    return stopServer()
}
fun localIpsByJs(): String {
    return localIps()
}
fun transferredCountByJs(): Number {
    return transferredCount()
}
fun totalCountByJs(): Number {
    return totalCount()
}
fun isRunningByJs(): Boolean {
    return isRunning()
}
