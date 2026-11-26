package com.tuptup.speech.live

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 直播间评论采集 + 自动回复客户端。
 *
 * 实现思路来自对 com.vboke.app (V播客 v1.3.0) 与 huituixia.zblm 的反编译分析：
 *  - 原 App 通过 WebView 加载已登录的直播间页面，evalJS("pinglun()") 注入采集脚本抓 DOM 评论；
 *  - 关键词规则由服务端 ApiLiveData/getlive 下发，reply_keywords_text(text) 触发自动回复；
 *  - 实时通道走 wss://<server>/wss，握手 {type:"khinit", aid, mid, platform}，25s 心跳。
 *
 * 本实现不内置任何第三方平台的私有协议/凭据，只提供通用能力：
 *  - 宿主提供直播间 URL（用户自己已登录的页面）与采集 CSS 选择器；
 *  - 采集到的评论通过 [comments] 流抛出，由上层决定是否播讲/回复；
 *  - 关键词规则由 [ReplyRule] 本地配置，命中后通过 [replyEvents] 抛出建议回复。
 */
class LiveCommentClient private constructor(private val ctx: Context) {

    data class Comment(val user: String, val text: String, val ts: Long = System.currentTimeMillis())
    data class ReplyRule(val keyword: String, val reply: String, val exact: Boolean = false)

    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments

    private val _replyEvents = MutableStateFlow<Comment?>(null)
    val replyEvents: StateFlow<Comment?> = _replyEvents

    private val _status = MutableStateFlow("未连接")
    val status: StateFlow<String> = _status

    private val _rules = MutableStateFlow<List<ReplyRule>>(emptyList())
    val rules: StateFlow<List<ReplyRule>> = _rules

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var connected = false
    /** 是否已接入直播间（决定是否自动讲解） */
    val isLive: Boolean get() = connected || webView != null
    private var lastReplyAt = 0L
    private var replyIntervalMs = 2000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // WebSocket 长连接
        .build()
    private var ws: WebSocket? = null
    private val heartbeat = object : Runnable {
        override fun run() {
            ws?.send(JSONObject().apply {
                put("type", "connect")
            }.toString())
            handler.postDelayed(this, 25_000)
        }
    }

    fun setRules(list: List<ReplyRule>) { _rules.value = list }

    /** 连接实时评论通道（可选；纯 WebView 采集时不用） */
    fun connectWebSocket(server: String, aid: String, mid: String, platform: String = "android") {
        try {
            _status.value = "连接中…"
            val url = server.replace("https://", "wss://").replace("http://", "ws://")
                .trimEnd('/') + "/wss"
            val req = Request.Builder().url(url).build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected = true
                    _status.value = "WS已连接"
                    webSocket.send(JSONObject().apply {
                        put("type", "khinit")
                        put("data", JSONObject().apply {
                            put("aid", aid); put("mid", mid); put("platform", platform)
                        })
                    }.toString())
                    handler.post(heartbeat)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parsePush(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    handler.removeCallbacks(heartbeat)
                    _status.value = "WS断开: ${t.message ?: "未知"}"
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                    handler.removeCallbacks(heartbeat)
                    _status.value = "WS已关闭"
                }
            })
        } catch (e: Exception) {
            _status.value = "连接失败: ${e.message}"
        }
    }

    /** 解析服务端推送的评论消息，兼容多种常见字段命名 */
    private fun parsePush(text: String) {
        try {
            val o = JSONObject(text)
            if (o.optString("type", "") !in setOf("comment", "pinglun", "danmu", "barrage", "chat", "tokefu")) return
            val d = o.optJSONObject("data") ?: o
            val user = d.optString("nickname", d.optString("user", d.optString("from", "观众")))
            val content = d.optString("content", d.optString("comment", d.optString("text", d.optString("msg", ""))))
            if (content.isNotBlank()) onComment(user, content)
        } catch (_: Exception) { }
    }

    /**
     * 在 WebView 中注入 DOM 采集脚本。
     * @param selector 评论节点 CSS 选择器，例如 ".comment-item" / "[class*=danmu]"
     * @param userSelector 用户名节点选择器（相对评论节点），为空则取 "匿名"
     * @param intervalMs 采集间隔，默认 1500ms
     */
    /** 在 WebView 创建时就注入 JS 桥接对象（必须在 loadUrl 之前调用） */
    @SuppressLint("SetJavaScriptEnabled")
    fun setupBridge(wv: WebView) {
        webView = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onComment(user: String, text: String) {
                if (text.isNotBlank()) handler.post { this@LiveCommentClient.onComment(user, text) }
            }
        }, "TuptupCollector")
    }

    /** 页面加载完成后注入采集脚本 */
    fun attachWebView(wv: WebView, selector: String, userSelector: String = "", intervalMs: Long = 1500) {
        webView = wv
        _status.value = "WebView采集已挂载"
        val js = buildCollectScript(selector, userSelector, intervalMs)
        wv.evaluateJavascript(js) { result ->
            if (result == null || result == "null") _status.value = "采集脚本注入失败"
        }
    }

    /** 注入到直播间页面的采集脚本：定时扫描评论 DOM，去重后回调 */
    private fun buildCollectScript(selector: String, userSelector: String, interval: Long): String = """
(function(){
  if (window.__tuptupCollector) return;
  window.__tuptupCollector = { seen: {}, timer: null };
  var sel = ${JSONObject.quote(selector)};
  var userSel = ${JSONObject.quote(userSelector)};
  function read(){
    try {
      var nodes = document.querySelectorAll(sel);
      for (var i=0;i<nodes.length;i++){
        var n = nodes[i];
        var text = (n.innerText || n.textContent || '').trim().replace(/\s+/g,' ');
        if (!text || text.length > 300) continue;
        var key = text;
        if (window.__tuptupCollector.seen[key]) continue;
        window.__tuptupCollector.seen[key] = 1;
        var u = '观众';
        if (userSel) {
          var un = n.querySelector(userSel);
          if (un) u = (un.innerText || un.textContent || '').trim();
        }
        window.TuptupCollector.onComment(u, text);
      }
    } catch(e){}
  }
  window.__tuptupCollector.timer = setInterval(read, $interval);
  read();
  return 'ok';
})();
""".trimIndent()

    /** 注入自动回复脚本到直播间输入框（学原 App reply_keywords_text） */
    fun injectReplyScript(inputSelector: String, buttonSelector: String = "") {
        val js = """
(function(){
  window.__tuptupReply = function(text){
    try {
      var box = document.querySelector(${JSONObject.quote(inputSelector)});
      if (!box) return false;
      if (box.isContentEditable) box.innerText = text;
      else box.value = text;
      box.dispatchEvent(new Event('input', {bubbles:true}));
      var btn = document.querySelector(${JSONObject.quote(buttonSelector)});
      if (btn) btn.click();
      return true;
    } catch(e){ return false; }
  };
  return 'ok';
})();
""".trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    fun sendReplyToPage(text: String) {
        webView?.evaluateJavascript("window.__tuptupReply && window.__tuptupReply(${JSONObject.quote(text)})", null)
    }

    private fun onComment(user: String, text: String) {
        val c = Comment(user, text)
        _comments.value = (_comments.value + c).takeLast(200)
        // 关键词规则匹配 + 节流（原 App 用 2s 节流）
        val now = System.currentTimeMillis()
        if (now - lastReplyAt < replyIntervalMs) return
        val matched = _rules.value.firstOrNull {
            if (it.exact) text == it.keyword else text.contains(it.keyword, ignoreCase = true)
        } ?: return
        lastReplyAt = now
        _replyEvents.value = c.copy(text = matched.reply)
    }

    fun clearComments() { _comments.value = emptyList() }

    /** 关键词规则匹配：命中返回回复文案，未命中返回 null */
    fun matchReply(text: String): String? = _rules.value.firstOrNull {
        if (it.exact) text == it.keyword else text.contains(it.keyword, ignoreCase = true)
    }?.reply

    fun disconnect() {
        ws?.close(1000, "bye")
        ws = null
        connected = false
        handler.removeCallbacks(heartbeat)
        webView?.evaluateJavascript("window.__tuptupCollector && clearInterval(window.__tuptupCollector.timer)", null)
        webView = null
        _status.value = "未连接"
    }

    companion object {
        @Volatile private var instance: LiveCommentClient? = null
        fun get(ctx: Context): LiveCommentClient =
            instance ?: synchronized(this) {
                instance ?: LiveCommentClient(ctx.applicationContext).also { instance = it }
            }
    }
}
