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

        /** sid = voices.bin 中的固定序号；只保留 6 个常用中文音色，界面直接显示汉字名 */
        val VOICES: List<VoiceInfo> = listOf(
            VoiceInfo(3, "温柔女声", "女声"),   // zf_001
            VoiceInfo(9, "清亮女声", "女声"),   // zf_007
            VoiceInfo(21, "甜美女声", "女声"),  // zf_032
            VoiceInfo(58, "沉稳男声", "男声"),  // zm_009
            VoiceInfo(66, "磁性男声", "男声"),  // zm_020
            VoiceInfo(77, "活力男声", "男声")   // zm_050
        )

        fun voice(sid: Int): VoiceInfo? = VOICES.firstOrNull { it.sid == sid }

        /** 老版本存的已下架 sid 回退到第一个音色 */
        fun normalizeSid(sid: Int): Int = voice(sid)?.sid ?: VOICES.first().sid
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
                // 老版本可能存着已精简掉的 sid，落到第一个可用音色，避免合成报错
                val effectiveSid = voice(sid)?.sid ?: VOICES.first().sid
                val cfg = GenerationConfig(sid = effectiveSid, speed = speed)
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
