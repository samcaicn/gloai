package com.tuptup.speech.inference

import ai.onnxruntime.*
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.exp

/**
 * 端侧 Jev-like 决策引擎（Verdict/OpenJev 151M，ONNX Runtime Android）。
 *
 * 安卓优化：
 *  - arm64-v8a only（build.gradle 已限制 ABI）
 *  - Execution Provider 优先级：NNAPI（GPU/NPU）→ XNNPACK（CPU）
 *  - 推理在 Dispatchers.Default 后台线程，不阻塞 UI
 *  - 模型单例复用 session，避免重复加载
 *  - graphOptimizationLevel=ALL + 动态 shape
 *
 * prompt 契约来自官方 webgpu-demo/prompt_contract.json：
 *  label_marker="<>", sep_marker="<>", input_template="Question: {question}\n\nContext:\n{context}"
 *  feeds: input_ids / attention_mask；输出 logits 前 N 个为候选概率。
 */
data class Strategy(val id: String, val name: String, val desc: String, val keywords: List<String>)

data class DecisionResult(
    val selectedId: String,
    val confidence: Float,
    val probabilities: FloatArray,
    val latencyMs: Long,
    val mode: String            // "nnapi" / "xnnpack" / "rule"
)

class JevEngine private constructor(private val ctx: Context) {
    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var tokenizer: BpeTokenizer? = null
    private var activeEp = "rule"
    private var ready = false

    private val labelMarker = "<>"
    private val sepMarker = "<>"
    private val inputTemplate = "Question: {question}\n\nContext:\n{context}"
    private val defaultQuestion = "Which sales pitch strategy fits this store situation best?"
    private val temperature = 1.0716f   // Verdict 官方校准温度 T

    val strategies = listOf(
        Strategy("greet", "热情迎客", "Greet warmly and invite the customer to look around", listOf("日常迎客", "早上", "上午", "进店")),
        Strategy("weather", "天气关怀", "Care about the weather and introduce seasonal products", listOf("冷", "热", "下雨", "雨", "晴", "降温", "天气")),
        Strategy("hotspot", "热点借势", "Use current hot topics to start a conversation", listOf("热搜", "热点", "新闻", "抖音", "微博", "百度")),
        Strategy("promo", "促销推荐", "Emphasize discounts and limited-time offers", listOf("促销", "优惠", "折扣", "周末", "活动", "特价")),
        Strategy("product", "产品聚焦", "Focus on the main product selling points", listOf("新品", "招牌", "推荐", "产品介绍", "要点")),
        Strategy("weekend", "周末闲聊", "Casual weekend chat to build rapport", listOf("周末", "星期六", "星期日", "休息"))
    )

    companion object {
        @Volatile private var INSTANCE: JevEngine? = null
        fun get(ctx: Context): JevEngine = INSTANCE ?: synchronized(this) {
            INSTANCE ?: JevEngine(ctx.applicationContext).also { INSTANCE = it }
        }
    }

    val isReady: Boolean get() = ready
    val provider: String get() = activeEp
    val isModelDownloaded: Boolean get() = modelFile().exists() && modelFile().length() > 10_000_000

    fun modelFile(): File = File(ctx.filesDir, "verdict.onnx")

    /**
     * 下载 606MB Verdict 模型（hf-mirror 国内镜像，首次启动用户点一次即可）。
     * 不打进 APK；下载到 filesDir，断点不续传但失败可重试。
     */
    suspend fun downloadModel(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://hf-mirror.com/heman10x/rlcd-modernbert-151m/resolve/main/model.onnx"
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 20000; readTimeout = 30000
                instanceFollowRedirects = true
            }
            val total = conn.contentLengthLong
            val tmp = File(ctx.filesDir, "verdict.onnx.part")
            java.io.BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); read += n
                        if (total > 0) onProgress((read * 100 / total).toInt())
                    }
                }
            }
            conn.disconnect()
            if (tmp.length() < 10_000_000) { tmp.delete(); return@withContext false }
            tmp.renameTo(modelFile())
            true
        } catch (e: Exception) {
            File(ctx.filesDir, "verdict.onnx.part").delete()
            false
        }
    }

    /** 加载模型：filesDir/verdict.onnx（下载后）建立 session；无模型则保持规则降级 */
    suspend fun init(): Boolean = withContext(Dispatchers.Default) {
        if (ready) return@withContext true
        if (!isModelDownloaded) return@withContext false
        try {
            env = OrtEnvironment.getEnvironment()
            val modelFile = modelFile()
            // 依次尝试 NNAPI（GPU/NPU）→ XNNPACK（CPU）
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
            }
            var built = false
            try {
                opts.addNnapi()
                built = true
                activeEp = "nnapi"
            } catch (e: Exception) { /* NNAPI 不可用，回退 CPU */ }
            try {
                if (!built) { opts.addXnnpack(emptyMap()); activeEp = "xnnpack" }
            } catch (e: Exception) { activeEp = "cpu" }

            session = env!!.createSession(modelFile.absolutePath, opts)
            tokenizer = try { BpeTokenizer.fromAssets(ctx) } catch (e: Exception) { BpeTokenizer.fallback() }
            ready = true
            // 预热一次（首帧延迟转移到 init）
            warmup()
            true
        } catch (e: Exception) {
            activeEp = "rule"
            ready = false
            false
        }
    }

    private fun warmup() {
        val enc = tokenizer!!.encode("$labelMarker warming up strategy$sepMarker Question: warmup Context: test")
        val ids = OnnxTensor.createTensor(env, arrayOf(enc.inputIds))
        val mask = OnnxTensor.createTensor(env, arrayOf(enc.attentionMask))
        session?.run(mapOf("input_ids" to ids, "attention_mask" to mask))
        ids.close(); mask.close()
    }

    /**
     * 决策：给定状态文本 + 场景，从候选策略里选最合适的一条。
     * state 建议含店铺/天气/热点/时间，JSON 或自然语言均可。
     */
    suspend fun decide(stateText: String, scene: String): DecisionResult = withContext(Dispatchers.Default) {
        if (!ready || session == null || tokenizer == null) return@withContext ruleDecide(stateText, scene)

        val candidates = strategies
        try {
            // prompt 构造（严格按官方契约）
            val labelPrefix = candidates.joinToString("") { "$labelMarker${it.desc}" }
            val formatted = inputTemplate
                .replace("{question}", defaultQuestion)
                .replace("{context}", "$stateText 场景:$scene")
            val prompt = "$labelPrefix$sepMarker$formatted"

            val enc = tokenizer!!.encode(prompt)
            val ids = OnnxTensor.createTensor(env, arrayOf(enc.inputIds))
            val mask = OnnxTensor.createTensor(env, arrayOf(enc.attentionMask))
            val t0 = System.nanoTime()
            val result = session!!.run(mapOf("input_ids" to ids, "attention_mask" to mask))
            val latency = (System.nanoTime() - t0) / 1_000_000
            ids.close(); mask.close()

            val logits = (result.get(0) as? OnnxTensor)?.floatBuffer
                ?: return@withContext ruleDecide(stateText, scene)
            val n = candidates.size
            val slice = FloatArray(n) { logits.get(it) }
            val max = slice.maxOrNull() ?: 0f
            val exps = slice.map { exp((it - max) / temperature) }
            val sum = exps.sum()
            val probs = FloatArray(n) { (exps[it] / sum).toFloat() }
            var best = 0
            for (i in 1 until n) if (probs[i] > probs[best]) best = i
            result.close()
            DecisionResult(candidates[best].id, probs[best], probs, latency, activeEp)
        } catch (e: Exception) {
            ruleDecide(stateText, scene)
        }
    }

    /** 规则降级：关键词命中打分，接口与模型一致 */
    fun ruleDecide(stateText: String, scene: String): DecisionResult {
        val text = "$stateText $scene"
        val scores = strategies.map { s ->
            val hit = s.keywords.count { text.contains(it) }
            exp(hit * 0.8).toFloat()
        }
        val sum = scores.sum()
        val probs = FloatArray(scores.size) { scores[it] / sum }
        var best = 0
        for (i in 1 until probs.size) if (probs[i] > probs[best]) best = i
        return DecisionResult(strategies[best].id, probs[best], probs, 0, "rule")
    }

    fun strategyById(id: String): Strategy = strategies.firstOrNull { it.id == id } ?: strategies[0]

    fun close() { session?.close(); session = null; ready = false }
}
