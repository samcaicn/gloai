package com.tuptup.speech.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 主播音色包：kokoro v1.1-zh int8（多音色，男女可选），完全离线。
 * 文件清单来自 csukuangfj/kokoro-int8-multi-lang-v1_1（hf-mirror 可直连），
 * 语法合成走 lexicon（v2.0.3 起 dataDir 已废弃，无需 espeak-ng-data）。
 */
class AnchorVoiceEngine(private val ctx: Context) {
    companion object {
        private const val TAG = "AnchorVoice"
        private const val MODEL_DIR = "anchor"
        private const val BASE = "https://hf-mirror.com/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/main"
        val FILES = listOf(
            "model.int8.onnx", "voices.bin", "tokens.txt",
            "lexicon-zh.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt",
            "date-zh.fst", "number-zh.fst", "phone-zh.fst"
        )

        data class VoiceInfo(val sid: Int, val name: String, val gender: String)

        /** sid 顺序 = voices.bin 排布：[af_maple, af_sol, bf_vale] + zf_* + zm_* */
        val VOICES: List<VoiceInfo> = listOf(
        VoiceInfo(3, "zf_001", "女声"),
        VoiceInfo(4, "zf_002", "女声"),
        VoiceInfo(5, "zf_003", "女声"),
        VoiceInfo(6, "zf_004", "女声"),
        VoiceInfo(7, "zf_005", "女声"),
        VoiceInfo(8, "zf_006", "女声"),
        VoiceInfo(9, "zf_007", "女声"),
        VoiceInfo(10, "zf_008", "女声"),
        VoiceInfo(11, "zf_017", "女声"),
        VoiceInfo(12, "zf_018", "女声"),
        VoiceInfo(13, "zf_019", "女声"),
        VoiceInfo(14, "zf_021", "女声"),
        VoiceInfo(15, "zf_022", "女声"),
        VoiceInfo(16, "zf_023", "女声"),
        VoiceInfo(17, "zf_024", "女声"),
        VoiceInfo(18, "zf_026", "女声"),
        VoiceInfo(19, "zf_027", "女声"),
        VoiceInfo(20, "zf_028", "女声"),
        VoiceInfo(21, "zf_032", "女声"),
        VoiceInfo(22, "zf_036", "女声"),
        VoiceInfo(23, "zf_038", "女声"),
        VoiceInfo(24, "zf_039", "女声"),
        VoiceInfo(25, "zf_040", "女声"),
        VoiceInfo(26, "zf_042", "女声"),
        VoiceInfo(27, "zf_043", "女声"),
        VoiceInfo(28, "zf_044", "女声"),
        VoiceInfo(29, "zf_046", "女声"),
        VoiceInfo(30, "zf_047", "女声"),
        VoiceInfo(31, "zf_048", "女声"),
        VoiceInfo(32, "zf_049", "女声"),
        VoiceInfo(33, "zf_051", "女声"),
        VoiceInfo(34, "zf_059", "女声"),
        VoiceInfo(35, "zf_060", "女声"),
        VoiceInfo(36, "zf_067", "女声"),
        VoiceInfo(37, "zf_070", "女声"),
        VoiceInfo(38, "zf_071", "女声"),
        VoiceInfo(39, "zf_072", "女声"),
        VoiceInfo(40, "zf_073", "女声"),
        VoiceInfo(41, "zf_074", "女声"),
        VoiceInfo(42, "zf_075", "女声"),
        VoiceInfo(43, "zf_076", "女声"),
        VoiceInfo(44, "zf_077", "女声"),
        VoiceInfo(45, "zf_078", "女声"),
        VoiceInfo(46, "zf_079", "女声"),
        VoiceInfo(47, "zf_083", "女声"),
        VoiceInfo(48, "zf_084", "女声"),
        VoiceInfo(49, "zf_085", "女声"),
        VoiceInfo(50, "zf_086", "女声"),
        VoiceInfo(51, "zf_087", "女声"),
        VoiceInfo(52, "zf_088", "女声"),
        VoiceInfo(53, "zf_090", "女声"),
        VoiceInfo(54, "zf_092", "女声"),
        VoiceInfo(55, "zf_093", "女声"),
        VoiceInfo(56, "zf_094", "女声"),
        VoiceInfo(57, "zf_099", "女声"),
        VoiceInfo(58, "zm_009", "男声"),
        VoiceInfo(59, "zm_010", "男声"),
        VoiceInfo(60, "zm_011", "男声"),
        VoiceInfo(61, "zm_012", "男声"),
        VoiceInfo(62, "zm_013", "男声"),
        VoiceInfo(63, "zm_014", "男声"),
        VoiceInfo(64, "zm_015", "男声"),
        VoiceInfo(65, "zm_016", "男声"),
        VoiceInfo(66, "zm_020", "男声"),
        VoiceInfo(67, "zm_025", "男声"),
        VoiceInfo(68, "zm_029", "男声"),
        VoiceInfo(69, "zm_030", "男声"),
        VoiceInfo(70, "zm_031", "男声"),
        VoiceInfo(71, "zm_033", "男声"),
        VoiceInfo(72, "zm_034", "男声"),
        VoiceInfo(73, "zm_035", "男声"),
        VoiceInfo(74, "zm_037", "男声"),
        VoiceInfo(75, "zm_041", "男声"),
        VoiceInfo(76, "zm_045", "男声"),
        VoiceInfo(77, "zm_050", "男声"),
        VoiceInfo(78, "zm_052", "男声"),
        VoiceInfo(79, "zm_053", "男声"),
        VoiceInfo(80, "zm_054", "男声"),
        VoiceInfo(81, "zm_055", "男声"),
        VoiceInfo(82, "zm_056", "男声"),
        VoiceInfo(83, "zm_057", "男声"),
        VoiceInfo(84, "zm_058", "男声"),
        VoiceInfo(85, "zm_061", "男声"),
        VoiceInfo(86, "zm_062", "男声"),
        VoiceInfo(87, "zm_063", "男声"),
        VoiceInfo(88, "zm_064", "男声"),
        VoiceInfo(89, "zm_065", "男声"),
        VoiceInfo(90, "zm_066", "男声"),
        VoiceInfo(91, "zm_068", "男声"),
        VoiceInfo(92, "zm_069", "男声"),
        VoiceInfo(93, "zm_080", "男声"),
        VoiceInfo(94, "zm_081", "男声"),
        VoiceInfo(95, "zm_082", "男声"),
        VoiceInfo(96, "zm_089", "男声"),
        VoiceInfo(97, "zm_091", "男声"),
        VoiceInfo(98, "zm_095", "男声"),
        VoiceInfo(99, "zm_096", "男声"),
        VoiceInfo(100, "zm_097", "男声"),
        VoiceInfo(101, "zm_098", "男声"),
        VoiceInfo(102, "zm_100", "男声"),
        )

        fun voice(sid: Int): VoiceInfo? = VOICES.firstOrNull { it.sid == sid }
    }

    private val dir: File
        get() {
            val target = File(ctx.filesDir, MODEL_DIR)
            if (!target.exists()) target.mkdirs()
            return target
        }

    @Volatile private var tts: OfflineTts? = null
    private val initLock = Any()

    val isDownloaded: Boolean
        get() = File(dir, "model.int8.onnx").length() > 50_000_000 &&
                File(dir, "voices.bin").length() > 20_000_000 &&
                File(dir, "tokens.txt").length() > 100

    val isReady: Boolean get() = tts != null

    /** 下载音色包；onProgress(已完成项, 总数, 当前文件名) */
    suspend fun downloadAll(onProgress: (Int, Int, String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            FILES.forEachIndexed { i, name ->
                onProgress(i, FILES.size, name)
                val out = File(dir, name)
                if (out.exists() && out.length() > 500) return@forEachIndexed
                download("$BASE/$name", out)
                Log.d(TAG, "downloaded $name ${out.length()}B")
            }
            onProgress(FILES.size, FILES.size, "完成")
            true
        } catch (e: Exception) {
            Log.w(TAG, "downloadAll failed: ${e.message}")
            false
        }
    }

    private fun download(url: String, out: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        try {
            val tmp = File(dir, out.name + ".part")
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { o ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n); total += n
                    }
                    Log.d(TAG, "fetch $url -> $total bytes")
                }
            }
            if (tmp.length() < 100) error("downloaded too small: ${tmp.length()}")
            tmp.renameTo(out)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun init(): Boolean = withContext(Dispatchers.Default) {
        initBlocking()
    }

    private fun initBlocking(): Boolean {
        synchronized(initLock) {
            if (tts != null) return true
            if (!isDownloaded) {
                Log.w(TAG, "pack not complete in ${dir.absolutePath}")
                return false
            }
            return try {
                val d = dir.absolutePath
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = "$d/model.int8.onnx",
                            voices = "$d/voices.bin",
                            tokens = "$d/tokens.txt",
                            lexicon = "$d/lexicon-zh.txt,$d/lexicon-us-en.txt,$d/lexicon-gb-en.txt"
                        ),
                        numThreads = 2
                    ),
                    ruleFsts = "$d/date-zh.fst,$d/number-zh.fst,$d/phone-zh.fst"
                )
                tts = OfflineTts(config = config)
                Log.d(TAG, "anchor init OK")
                true
            } catch (e: Exception) {
                Log.w(TAG, "anchor init failed: ${e.message}")
                false
            }
        }
    }

    /** 后台预热 */
    fun warmUp() {
        if (tts != null || !isDownloaded) return
        Thread {
            try {
                val t0 = System.currentTimeMillis()
                if (initBlocking()) Log.d(TAG, "warmUp OK in ${System.currentTimeMillis() - t0}ms")
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "anchor-warmup" }.start()
    }

    /** 合成到 WAV 文件；返回错误信息或 null */
    suspend fun synthesize(text: String, sid: Int, outFile: File, speed: Float = 1.0f): String? =
        withContext(Dispatchers.Default) {
            if (!initBlocking()) return@withContext "音色包未就绪"
            val engine = tts ?: return@withContext "音色包未初始化"
            try {
                val t0 = System.currentTimeMillis()
                val cfg = GenerationConfig(sid = sid, speed = speed)
                val audio = engine.generateWithConfig(text = text, config = cfg)
                if (audio.samples.isEmpty()) {
                    return@withContext "合成结果为空"
                }
                WaveWriter.write(outFile, audio.samples, audio.sampleRate)
                Log.d(TAG, "anchor synth ${text.length} chars ${audio.samples.size} samples in ${System.currentTimeMillis() - t0}ms")
                null
            } catch (e: Exception) {
                Log.w(TAG, "anchor synth failed: ${e.message}")
                "合成失败：${e.message?.take(60)}"
            }
        }

    /** 释放底层模型（切到克隆音色时腾内存） */
    fun releaseTts() {
        synchronized(initLock) {
            tts?.let { try { it.release() } catch (_: Exception) {} }
            tts = null
        }
    }
}

private object WaveWriter {
    fun write(out: File, samples: FloatArray, sampleRate: Int) {
        val bytes = samples.size * 2
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)); header.putInt(36 + bytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII)); header.putInt(16)
        header.putShort(1); header.putShort(1); header.putInt(sampleRate)
        header.putInt(sampleRate * 2); header.putShort(2); header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)); header.putInt(bytes)
        java.io.FileOutputStream(out).use { fos ->
            fos.write(header.array())
            val bb = java.nio.ByteBuffer.allocate(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (s in samples) bb.putShort((s * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            fos.write(bb.array())
        }
    }
}
