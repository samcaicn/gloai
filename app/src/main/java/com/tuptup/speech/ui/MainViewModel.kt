package com.tuptup.speech.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tuptup.speech.data.*
import com.tuptup.speech.network.ContextApi
import com.tuptup.speech.network.LlmApi
import com.tuptup.speech.tts.TtsEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val lines: List<String> = emptyList(),
    val status: String = "初始化中",
    val note: String = "",
    val generating: Boolean = false,
    val playingIndex: Int = -1
)

/**
 * 极简链路：用户只维护「工作信息 + 产品信息」，其余（策略/风格/上下文/回复）全由 AI 判断。
 * 信息一变更就用新信息重新调用 AI。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val db = AppDatabase.get(app)
    private val llm = LlmApi(settings)
    private val contextApi = ContextApi(settings)
    val tts = TtsEngine(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    /** AI 生成的评论回复，CommentPage 收到后发送回直播间页面 */
    private val _aiReply = MutableStateFlow<String?>(null)
    val aiReply: StateFlow<String?> = _aiReply.asStateFlow()

    init {
        refreshProducts()
        // 空闲预热：我的声音预热克隆模型，默认音色预热主播包
        if (settings.useLocalVoice) tts.local.warmUp() else tts.anchor.warmUp()
        _ui.value = _ui.value.copy(status = "已就绪")
        // 专业音色包没下过就后台自动下一次：否则默认音色会落到干涩的系统 TTS。
        // 仅 WiFi 下自动拉取 180MB，避免走流量；成功后置标记不再重复。
        if (!settings.useLocalVoice && !tts.anchor.isDownloaded && !settings.anchorAutoDownloaded) {
            viewModelScope.launch {
                val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
                val wifi = cm?.activeNetworkInfo?.let {
                    it.isConnected && it.type == android.net.ConnectivityManager.TYPE_WIFI
                } == true
                if (!wifi) return@launch
                if (tts.anchor.downloadAll { _, _, _ -> Unit }) {
                    settings.anchorAutoDownloaded = true
                    tts.anchor.warmUp()
                }
            }
        }
    }

    private fun workInfo(): String = listOf(settings.shopName, settings.workInfo)
        .filter { it.isNotBlank() }.joinToString(" · ")

    private fun productsText(): String =
        if (_products.value.isEmpty()) ""
        else _products.value.joinToString("\n") { "- ${it.name}：${it.points.replace("\n", "，")}" }

    private suspend fun extraInfo(): String = try {
        contextApi.build().asText()
    } catch (_: Exception) { "" }

    /** 生成话术：全部交给 AI，端侧不再做策略决策 */
    fun generate() = viewModelScope.launch {
        _ui.value = _ui.value.copy(generating = true, status = "AI 生成话术…", note = "")
        try {
            val result = llm.generate(workInfo(), productsText(), extraInfo())
            _ui.value = _ui.value.copy(
                lines = result.lines, generating = false, note = result.note,
                status = if (result.mode == "api") "AI 已生成" else "本地模板"
            )
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(
                generating = false,
                status = "生成失败：${e.message?.take(60) ?: "未知错误"}",
                note = "可重试；检查网络与 AI 接口设置"
            )
        }
    }

    /** 工作信息/产品信息变更后调用：用新信息立即重新生成 */
    fun onInfoChanged() {
        refreshProducts()
        if (settings.autoRegen) generate()
    }

    fun saveWorkInfo(name: String, work: String, city: String) {
        settings.shopName = name.trim()
        settings.workInfo = work.trim()
        if (city.isNotBlank()) settings.city = city.trim()
        onInfoChanged()
    }

    fun speak(index: Int) {
        val text = _ui.value.lines.getOrNull(index) ?: return
        _ui.value = _ui.value.copy(playingIndex = index)
        val stylePitch = if (settings.hasVoiceProfile) settings.voicePitch else 1.0f
        tts.speed = settings.ttsSpeed
        tts.speak(text, null, stylePitch) {
            viewModelScope.launch { _ui.value = _ui.value.copy(playingIndex = -1) }
        }
    }

    fun stopSpeak() {
        _queue.value = emptyList()
        tts.stop()
        speakingNow = false
        _ui.value = _ui.value.copy(playingIndex = -1)
    }

    fun resumePlayback() { pump() }

    fun speakText(text: String) {
        tts.speed = settings.ttsSpeed
        tts.speak(text, null, 1.0f)
    }

    // ---------- 播讲编排：评论优先、讲完再切、>20s 未回的评论跳过 ----------

    data class SpeechItem(val text: String, val isReply: Boolean, val ts: Long = System.currentTimeMillis())

    private val _queue = MutableStateFlow<List<SpeechItem>>(emptyList())
    val queueSize: StateFlow<Int> = _queue.map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    @Volatile private var speakingNow = false
    @Volatile private var explaining = false
    private val spokenHistory = LinkedHashSet<String>()
    private var ackIdx = 0
    @Volatile private var lastAiReplyAt = 0L

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 新评论：AI 生成回复 + 播讲 */
    fun onNewComment(user: String, text: String, ts: Long) {
        if (!settings.commentSpeakEnabled) return
        val say = ackFor(user)
        _queue.value = _queue.value + SpeechItem(say, true, ts)
        pump()

        // AI 回复节流：8 秒一条，避免频繁调用
        val now = System.currentTimeMillis()
        if (now - lastAiReplyAt < 8_000) return
        lastAiReplyAt = now
        viewModelScope.launch {
            val result = llm.reply(workInfo(), productsText(), text)
            val reply = result.lines.firstOrNull()
            if (!reply.isNullOrBlank()) _aiReply.value = reply
        }
    }

    fun consumeAiReply() { _aiReply.value = null }

    private fun ackFor(user: String): String {
        val who = if (user.isBlank() || user.length > 8) "宝宝" else user
        val tpl = listOf(
            "欢迎$who 来到直播间，喜欢的宝宝点个关注",
            "谢谢$who 的支持，想要的宝宝在评论区扣个要",
            "$who 来啦，马上给大家介绍今天的福利",
            "看到$who 了，新进来的宝宝可以先看看讲解",
        )
        return tpl[ackIdx++ % tpl.size]
    }

    private fun pump() {
        if (speakingNow || explaining) return
        if (!settings.commentSpeakEnabled) return
        val now = System.currentTimeMillis()
        val q = _queue.value.filter { it.isReply || now - it.ts <= 20_000 }
        _queue.value = q
        val item = q.firstOrNull()
        if (item != null) {
            _queue.value = q - item
            speakItem(item)
        } else if (com.tuptup.speech.live.LiveCommentClient.get(getApplication()).isLive) {
            explainNext()
        }
    }

    private fun speakItem(item: SpeechItem) {
        speakingNow = true
        tts.speed = settings.ttsSpeed
        val stylePitch = if (settings.hasVoiceProfile) settings.voicePitch else 1.0f
        if (!item.isReply) {
            val idx = _ui.value.lines.indexOf(item.text)
            _ui.value = _ui.value.copy(playingIndex = idx)
        }
        tts.speak(item.text, null, stylePitch) {
            speakingNow = false
            viewModelScope.launch { _ui.value = _ui.value.copy(playingIndex = -1) }
            mainHandler.post { pump() }
        }
    }

    /** 无评论时自动生成产品讲解（AI 直出，去重） */
    private fun explainNext() {
        explaining = true
        viewModelScope.launch {
            val line = try { generateExplanationLine() } catch (e: Exception) {
                _ui.value = _ui.value.copy(note = "讲解生成失败：${e.message?.take(40) ?: "稍后重试"}")
                null
            }
            explaining = false
            if (line == null) mainHandler.postDelayed({ pump() }, 3000) else pump()
        }
    }

    private suspend fun generateExplanationLine(): String? {
        val result = llm.generate(workInfo(), productsText(), extraInfo())
        val fresh = result.lines.firstOrNull { it !in spokenHistory }
            ?: result.lines.firstOrNull()
            ?: return null
        if (fresh in spokenHistory) spokenHistory.clear()
        spokenHistory.add(fresh)
        if (spokenHistory.size > 200) spokenHistory.remove(spokenHistory.first())
        _ui.value = _ui.value.copy(lines = (_ui.value.lines + fresh).takeLast(6))
        return fresh
    }

    fun settings(): Settings = settings
    suspend fun testApi(): String = llm.testConnection()

    fun addProduct(name: String, points: String) = viewModelScope.launch {
        db.productDao().insert(Product("p_${System.currentTimeMillis()}", name.trim(), points.trim()))
        onInfoChanged()
    }
    fun deleteProduct(id: String) = viewModelScope.launch { db.productDao().delete(id); onInfoChanged() }

    private fun refreshProducts() = viewModelScope.launch { _products.value = db.productDao().all() }

    override fun onCleared() { super.onCleared(); tts.shutdown() }
}
