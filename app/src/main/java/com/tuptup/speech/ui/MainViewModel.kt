package com.tuptup.speech.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tuptup.speech.data.*
import com.tuptup.speech.inference.DecisionResult
import com.tuptup.speech.inference.JevEngine
import com.tuptup.speech.network.ContextApi
import com.tuptup.speech.network.ContextInfo
import com.tuptup.speech.network.LlmApi
import com.tuptup.speech.tts.TtsEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val lines: List<String> = emptyList(),
    val decision: DecisionResult? = null,
    val context: ContextInfo? = null,
    val status: String = "初始化中",
    val note: String = "",
    val generating: Boolean = false,
    val jevReady: Boolean = false,
    val jevProvider: String = "",
    val playingIndex: Int = -1
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val db = AppDatabase.get(app)
    private val jev = JevEngine.get(app)
    private val llm = LlmApi(settings)
    private val contextApi = ContextApi(settings)
    val tts = TtsEngine(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    init {
        refreshProducts()
        // 空闲预热：我的声音预热克隆模型，默认音色预热主播包，首次播讲不等待加载
        if (settings.useLocalVoice) tts.local.warmUp() else tts.anchor.warmUp()
        viewModelScope.launch {
            _ui.value = _ui.value.copy(status = "正在准备…")
            val ok = jev.init()
            _ui.value = _ui.value.copy(
                jevReady = ok, jevProvider = jev.provider,
                status = if (ok) "已就绪" else "准备完成 · 可生成"
            )
        }
    }

    fun generate(scene: String, count: Int) = viewModelScope.launch {
        _ui.value = _ui.value.copy(generating = true, status = "获取实时上下文…")
        try {
            val ctx = contextApi.build()
            _ui.value = _ui.value.copy(context = ctx, status = "正在生成…")

            // 1) 端侧 Jev 决策（GPU/NNAPI）
            val stateText = ctx.asText() + "\n店铺：${settings.shopName} ${settings.shopFeatures}"
            val decision = jev.decide(stateText, scene)
            val strategy = jev.strategyById(decision.selectedId)
            _ui.value = _ui.value.copy(decision = decision, status = "API 生成话术…")

            // 2) 文本 API 生成
            val prodList = db.productDao().all()
            val prodText = if (prodList.isEmpty()) "（未添加产品，围绕店铺特色介绍）"
                           else prodList.joinToString("\n") { "- ${it.name}：${it.points.replace("\n", "，")}" }
            val result = llm.generate(ctx.asText(), prodText, strategy, scene, count)

            _ui.value = _ui.value.copy(
                lines = result.lines, generating = false, note = result.note,
                jevReady = jev.isReady, jevProvider = jev.provider,
                status = if (result.mode == "api") "API已生成 · ${strategy.name}" else "模板话术 · ${strategy.name}"
            )
        } catch (e: Exception) {
            // 任何未预料异常都要回到可重试状态，不能让按钮永久转圈
            _ui.value = _ui.value.copy(
                generating = false,
                status = "生成失败：${e.message?.take(60) ?: "未知错误"}",
                note = "可重试；检查网络或店铺/产品数据"
            )
        }
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

    /** 恢复自动播讲（打开开关后调用） */
    fun resumePlayback() { pump() }

    /** 任意文本播讲：我的声音/主播包优先，失败自动降级 */
    fun speakText(text: String) {
        tts.speed = settings.ttsSpeed
        tts.speak(text, null, 1.0f)
    }

    // ---------- 播讲编排：永远连续；讲完再切；>20s 未回的评论跳过；无评论自动讲解不重复 ----------

    data class SpeechItem(val text: String, val isReply: Boolean, val ts: Long = System.currentTimeMillis())

    private val _queue = MutableStateFlow<List<SpeechItem>>(emptyList())
    /** 待回复条数（UI 显示积压情况） */
    val queueSize: StateFlow<Int> = _queue.map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    @Volatile private var speakingNow = false
    @Volatile private var explaining = false
    private val spokenHistory = LinkedHashSet<String>()   // 讲解去重
    private var ackIdx = 0

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 直播间新评论进入编排：命中关键词回规则回复，否则播欢迎语 */
    fun onNewComment(user: String, text: String, ts: Long) {
        if (!settings.commentSpeakEnabled) return
        val reply = com.tuptup.speech.live.LiveCommentClient.get(getApplication()).matchReply(text)
        val say = reply ?: ackFor(user)
        _queue.value = _queue.value + SpeechItem(say, true, ts)
        pump()
    }

    /** 欢迎语轮换（欢迎语按惯例可重复，讲解才去重） */
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

    /** 调度核心：单线程顺序播讲，评论优先、无评论讲产品 */
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
            mainHandler.post { pump() }   // 讲完这条再切下一条
        }
    }

    /** 无评论时自动生成产品讲解（保证不重复） */
    private fun explainNext() {
        explaining = true
        viewModelScope.launch {
            val line = try { generateExplanationLine() } catch (e: Exception) {
                _ui.value = _ui.value.copy(note = "讲解生成失败：${e.message?.take(40) ?: "稍后重试"}")
                null
            }
            explaining = false
            if (line == null) {
                mainHandler.postDelayed({ pump() }, 3000)   // 生成失败/全部重复 → 稍后重试
            } else {
                pump()
            }
        }
    }

    private suspend fun generateExplanationLine(): String? {
        val prodList = db.productDao().all()
        val prodText = if (prodList.isEmpty()) "（未添加产品，围绕店铺特色介绍）"
                       else prodList.joinToString("\n") { "- ${it.name}：${it.points.replace("\n", "，")}" }
        val ctx = try { contextApi.build() } catch (_: Exception) { null }
        val decision = try {
            jev.decide(ctx!!.asText() + "\n店铺：${settings.shopName} ${settings.shopFeatures}",
                "自动讲解·${settings.speakStyle}风格")
        } catch (_: Exception) { null }
        val strategy = jev.strategyById(decision?.selectedId ?: jev.strategies[0].id)
        val result = llm.generate(ctx?.asText() ?: "", prodText, strategy,
            "产品讲解·${settings.speakStyle}风格", 3)
        // 取第一条没讲过的；全部讲过就换一批角度重新生成
        val fresh = result.lines.firstOrNull { it !in spokenHistory }
        if (fresh == null) {
            spokenHistory.clear()
            spokenHistory.addAll(result.lines.take(3))
            return result.lines.firstOrNull()
        }
        spokenHistory.add(fresh)
        if (spokenHistory.size > 200) spokenHistory.remove(spokenHistory.first())
        _ui.value = _ui.value.copy(lines = (_ui.value.lines + fresh).takeLast(6))
        return fresh
    }

    fun settings(): Settings = settings

    fun jevEngine(): JevEngine = jev
    suspend fun testApi(): String = llm.testConnection()

    private val _dlProgress = MutableStateFlow(-1)
    val dlProgress: StateFlow<Int> = _dlProgress.asStateFlow()   // -1=未下载 0..100 101=完成

    /** 首次启动下载 606MB 端侧模型（hf-mirror），完成后自动加载 */
    fun downloadModel() = viewModelScope.launch {
        if (jev.isModelDownloaded) { jev.init(); return@launch }
        _ui.value = _ui.value.copy(status = "下载端侧模型中…")
        val ok = jev.downloadModel { p -> _dlProgress.value = p }
        _dlProgress.value = if (ok) 101 else -1
        val ready = ok && jev.init()
        _ui.value = _ui.value.copy(
            jevReady = ready, jevProvider = jev.provider,
            status = if (ready) "已就绪" else "准备中 · 稍后可重试"
        )
    }

    fun addProduct(name: String, points: String) = viewModelScope.launch {
        db.productDao().insert(Product("p_${System.currentTimeMillis()}", name.trim(), points.trim()))
        refreshProducts()
    }
    fun deleteProduct(id: String) = viewModelScope.launch { db.productDao().delete(id); refreshProducts() }

    private fun refreshProducts() = viewModelScope.launch { _products.value = db.productDao().all() }

    override fun onCleared() { super.onCleared(); tts.shutdown(); jev.close() }
}
