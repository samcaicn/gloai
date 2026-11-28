package com.tuptup.speech.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.WaveReader
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 端侧零样本声音克隆（sherpa-onnx + ZipVoice-distill int8）。
 * 完全离线：模型放 filesDir/zipvoice/，参考音频 3 秒即可克隆。
 *
 * 模型文件（首次用 downloadAll 下载，或 ADB push 到同目录）：
 *   encoder.int8.onnx / decoder.int8.onnx / tokens.txt /
 *   lexicon.txt / vocos_24khz.onnx / espeak-ng-data/
 */
class ZipVoiceEngine(private val ctx: Context) {
    companion object {
        private const val TAG = "ZipVoice"
        const val MODEL_DIR = "zipvoice"
        // hf-mirror 国内可达；vocoder/lexicon 走同一镜像的镜像仓库（可在真机 push 覆盖）
        // 下载源铁律（用户定）：① HF 加速镜像 → ② GitHub 源(azure) → ③ R2 兜底
        const val HF_BASE = "https://hf-mirror.com/k2-fsa/ZipVoice/resolve/main/zipvoice_distill"
        // vocoder 官方源（GitHub release 国内可能不通，手机端建议预置或用 R2 镜像）
        const val GH_BASE_VOCODER = "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models"
        // R2 兜底桶：配置后拼文件名。格式：https://<bucket>.r2.dev/zipvoice/
        // 用户可把自己服务器上的模型镜像填这里，作为三级兜底最后一环。
        const val R2_BASE = ""

        /** 生成某文件的三级候选 URL（HF → GitHub/azure → R2） */
        fun sources(name: String): List<String> {
            val hf = when (name) {
                "encoder.int8.onnx" -> "$HF_BASE/text_encoder_int8.onnx"
                "decoder.int8.onnx" -> "$HF_BASE/fm_decoder_int8.onnx"
                "tokens.txt" -> "$HF_BASE/tokens.txt"
                "lexicon.txt" -> "$HF_BASE/lexicon.txt"
                // 注意：不能用 wetdog/vocos-mel-24khz-onnx 的 mel_spec_24khz.onnx——
                // 格式与 sherpa-onnx 的 ComputeMelSpectrogram 不兼容，合成时 native 空指针 SIGSEGV。
                // 必须用官方 vocos_24khz.onnx（GitHub release vocoder-models）。
                "vocos_24khz.onnx" -> "$GH_BASE_VOCODER/vocos_24khz.onnx"
                else -> "$HF_BASE/$name"
            }
            val list = mutableListOf(hf)
            // vocoder 官方源国内通常不通；用户可把官方 vocos_24khz.onnx 镜像到自己的
            // R2/服务器，填 R2_BASE 作为国内兜底，或直接 ADB push 到模型目录（推荐）。
            if (R2_BASE.isNotBlank()) list += R2_BASE + name
            return list
        }

        /** 录音采样写标准 16bit WAV（录制参考音用） */
        fun writeWav(pcm: ShortArray, sampleRate: Int, out: File) {
            val bytes = pcm.size * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII)); header.putInt(36 + bytes)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII)); header.putInt(16)
            header.putShort(1); header.putShort(1); header.putInt(sampleRate)
            header.putInt(sampleRate * 2); header.putShort(2); header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII)); header.putInt(bytes)
            FileOutputStream(out).use { fos ->
                fos.write(header.array())
                val bb = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (s in pcm) bb.putShort(s)
                fos.write(bb.array())
            }
        }
    }

    /**
     * 模型目录：优先用外部私有目录（可 adb push 预置模型，也方便用户自行放文件），
     * 回退内部 filesDir。外部目录 Android/data/<pkg>/files 无需 root 即可读写。
     */
    private val dir: File
        get() {
            val ext = try { ctx.getExternalFilesDir(null) } catch (_: Exception) { null }
            val target = if (ext != null) File(ext, MODEL_DIR) else File(ctx.filesDir, MODEL_DIR)
            if (!target.exists()) target.mkdirs()
            return target
        }
    private var tts: OfflineTts? = null
    // 参考音频缓存：录一次读一次，避免每条评论都解析 WAV
    @Volatile private var cachedRef: FloatArray? = null
    @Volatile private var cachedRefPath: String? = null
    @Volatile private var cachedRefLen: Long = -1L
    @Volatile private var cachedRefRate: Int = 16000
    // init 并发保护：预热与首次合成可能同时进来
    private val initLock = Any()

    val modelDir: File get() = dir
    val isDownloaded: Boolean
        get() = File(dir, "encoder.int8.onnx").length() > 1_000_000 &&
                File(dir, "decoder.int8.onnx").length() > 10_000_000 &&
                File(dir, "vocos_24khz.onnx").length() > 1_000_000
    val isReady: Boolean get() = tts != null

    fun referenceFile(): File = File(dir, "reference.wav")
    fun hasReference(): Boolean = referenceFile().let { it.exists() && it.length() > 3000 }
    /** 录音完成后调用，让参考音频缓存失效 */
    fun invalidateRef() { cachedRef = null; cachedRefPath = null; cachedRefLen = -1L }

    /** 下载全部模型文件；onProgress(已完成项, 总数, 当前文件名) */
    suspend fun downloadAll(onProgress: (Int, Int, String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val files = listOf(
            "encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt",
            "vocos_24khz.onnx", "lexicon.txt"
        )
        try {
            files.forEachIndexed { i, name ->
                onProgress(i, files.size, name)
                val out = File(dir, name)
                if (out.exists() && out.length() > 1000) return@forEachIndexed
                // 三级源逐个 try，全部失败才抛错
                var lastErr: Exception? = null
                var ok = false
                for (url in sources(name)) {
                    try {
                        download(url, out)
                        ok = true
                        Log.d(TAG, "downloaded $name via $url")
                        break
                    } catch (e: Exception) {
                        lastErr = e
                        Log.w(TAG, "source failed $url: ${e.message}")
                    }
                }
                if (!ok) throw lastErr ?: IOException("no source for $name")
            }
            onProgress(files.size, files.size, "完成")
            true
        } catch (e: Exception) {
            Log.w(TAG, "downloadAll failed: ${e.message}")
            false
        }
    }

    private fun download(url: String, out: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        try {
            val tmp = File(dir, out.name + ".part")
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                    }
                }
            }
            if (tmp.length() < 1000) error("downloaded too small: ${tmp.length()}")
            tmp.renameTo(out)
            Log.d(TAG, "downloaded ${out.name} ${out.length()} bytes")
        } finally {
            conn.disconnect()
        }
    }

    /** 释放底层模型（切到主播音色包时腾内存） */
    fun releaseTts() {
        synchronized(initLock) {
            tts?.let { try { it.release() } catch (_: Exception) {} }
            tts = null
        }
    }

    /** 后台预热模型：App 空闲时调用，首次合成不再等模型加载 */
    fun warmUp() {
        if (tts != null || !isDownloaded) return
        Thread {
            try {
                val t0 = System.currentTimeMillis()
                if (initBlocking()) Log.d(TAG, "warmUp OK in ${System.currentTimeMillis() - t0}ms")
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "zipvoice-warmup" }.start()
    }

    /** 初始化 OfflineTts；模型不全返回 false */
    suspend fun init(): Boolean = withContext(Dispatchers.Default) { initBlocking() }

    private fun initBlocking(): Boolean {
        if (tts != null) return true
        synchronized(initLock) {
            if (tts != null) return true
            if (!isDownloaded) {
                Log.w(TAG, "model not complete in ${dir.absolutePath}")
                return false
            }
            return try {
                val lexicon = File(dir, "lexicon.txt")
                val config = getOfflineTtsConfig(
                    modelDir = dir.absolutePath,
                    lexicon = if (lexicon.exists()) "lexicon.txt" else "",
                    isZipvoice = true,
                    zipvoiceEncoder = "encoder.int8.onnx",
                    zipvoiceDecoder = "decoder.int8.onnx",
                    zipvoiceVocoder = File(dir, "vocos_24khz.onnx").absolutePath,
                    numThreads = 4
                )
                tts = OfflineTts(config = config)
                Log.d(TAG, "ZipVoice init OK")
                true
            } catch (e: Exception) {
                Log.w(TAG, "ZipVoice init failed: ${e.message}")
                false
            }
        }
    }

    /**
     * 用已保存的参考音频合成语音，写 WAV 文件返回。
     * referenceText 必须是参考音频里说的原话（ZipVoice 靠它对齐音色）。
     */
    suspend fun synthesize(text: String, referenceText: String, outFile: File): String? = withContext(Dispatchers.Default) {
        if (!init()) return@withContext "模型未就绪"
        val ref = referenceFile()
        if (!ref.exists()) { cachedRef = null; return@withContext "未录制参考音色" }
        try {
            // 参考音频缓存：文件没变就复用采样，省掉每次 WAV 解析
            val samples = if (cachedRef != null && cachedRefPath == ref.absolutePath &&
                cachedRefLen == ref.length()) cachedRef!! else {
                val wave = WaveReader.readWave(ref.absolutePath)
                cachedRef = wave.samples
                cachedRefPath = ref.absolutePath
                cachedRefLen = ref.length()
                cachedRefRate = wave.sampleRate
                wave.samples
            }
            // numSteps 3→8：steps 越多波形越平滑，干涩/金属感明显下降（端侧慢一点但可接受）。
            // silenceScale 略压缩句间停顿，避免机械拖沓。
            val cfg = GenerationConfig(
                referenceAudio = samples,
                referenceSampleRate = cachedRefRate,
                referenceText = referenceText,
                numSteps = 8,
                silenceScale = 0.85f,
                extra = mapOf("min_char_in_sentence" to "10")
            )
            val t0 = System.currentTimeMillis()
            val audio = tts!!.generateWithConfig(text = text, config = cfg)
            if (audio.samples.isEmpty()) return@withContext "合成结果为空"
            // FloatArray -> 16bit PCM WAV
            writeWav(audio.samples, audio.sampleRate, outFile)
            Log.d(TAG, "synthesized ${text.length} chars ${audio.samples.size / audio.sampleRate}s in ${System.currentTimeMillis() - t0}ms")
            null
        } catch (e: Exception) {
            Log.w(TAG, "synthesize failed: ${e.message}")
            e.message
        }
    }

    private fun writeWav(samples: FloatArray, sampleRate: Int, out: File) {
        // 峰值归一化 + tanh 软限幅：克隆输出常偏小发虚，硬放大又会削波刺耳
        var peak = 0f
        for (s in samples) if (kotlin.math.abs(s) > peak) peak = kotlin.math.abs(s)
        val gain = if (peak > 0.001f) 0.92f / peak else 1f
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i] * gain).coerceIn(-1f, 1f)
            val shaped = if (kotlin.math.abs(v) > 0.75f)
                kotlin.math.tanh(v * 1.3f) / kotlin.math.tanh(1.3f) else v
            val sv = (shaped * 32767).toInt().coerceIn(-32768, 32767).toShort()
            pcm[i * 2] = sv.toByte()
            pcm[i * 2 + 1] = (sv.toInt() shr 8).toByte()
        }
        FileOutputStream(out).use { f ->
            val dataLen = pcm.size
            fun s4(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun s2(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            f.write("RIFF".toByteArray()); f.write(s4(36 + dataLen)); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.write(s4(16)); f.write(s2(1)); f.write(s2(1))
            f.write(s4(sampleRate)); f.write(s4(sampleRate * 2)); f.write(s2(2)); f.write(s2(16))
            f.write("data".toByteArray()); f.write(s4(dataLen)); f.write(pcm)
        }
    }

    fun release() {
        try { tts?.release() } catch (_: Exception) {}
        tts = null
    }
}
