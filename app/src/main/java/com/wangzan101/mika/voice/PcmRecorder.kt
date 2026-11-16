package com.wangzan101.mika.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * 录 16k 单声道 16 位 PCM。start 后每约 50ms 更新一次 [level](0..1,按每块 RMS 归一化);
 * stop 返回到目前为止的全部 PCM;录满 [maxMs] 自动停止,之后再 stop 仍返回已录的内容。
 * 录音权限由调用方先检查,这里拿不到麦克风就返回 false。stop 会短暂阻塞(等读循环退出),别在主线程调。
 */
class PcmRecorder(
    private val maxMs: Int = 60_000,
    private val streaming: Boolean = false,
    /** 流式时不留整段录音、录多久都行(不预留 [maxMs] 的缓冲,也不到点自停);[stop] 返回空。用于不封顶的会话。 */
    private val unbounded: Boolean = false,
) : StreamingRecorder {
    // At most five seconds while a connection is opening. Never silently drop a sample.
    private val audio = Channel<ShortArray>(100)
    override val chunks: ReceiveChannel<ShortArray> = audio
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var record: AudioRecord? = null
    private var job: Job? = null
    private var pcm = ShortArray(0)
    private var size = 0

    @SuppressLint("MissingPermission") // 权限由调用方在 start 之前检查
    override fun start(): Boolean {
        synchronized(lock) {
            if (record != null) return false
            val minBuf = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return false
            val chunk = Wav.RATE / 20 // 50ms 一块
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, Wav.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, chunk * 2 * 4),
                )
            } catch (_: SecurityException) {
                return false
            } catch (_: IllegalArgumentException) {
                return false
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            try {
                rec.startRecording()
            } catch (_: IllegalStateException) {
                rec.release()
                return false
            }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                return false
            }
            pcm = ShortArray(if (unbounded && streaming) 0 else Wav.RATE * maxMs / 1000)
            size = 0
            _level.value = 0f
            record = rec
            job = scope.launch { readLoop(rec, chunk) }
            return true
        }
    }

    private fun readLoop(rec: AudioRecord, chunk: Int) {
        val buf = ShortArray(chunk)
        while (scope.isActive) {
            val n = rec.read(buf, 0, chunk)
            if (n <= 0) {
                synchronized(lock) {
                    if (streaming && record === rec) audio.close(IOException("Audio capture failed"))
                }
                break // 读失败或已被停掉
            }
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            val lvl = (sqrt(sum / n) / 8000.0).coerceIn(0.0, 1.0).toFloat()
            val full = synchronized(lock) {
                if (record !== rec) return // 已被 stop/cancel
                // 在锁里、确认还没被停掉之后才写,免得 cancel 刚归零又被这一块的音量盖回去
                _level.value = lvl
                val take = if (unbounded && streaming) n else minOf(n, pcm.size - size)
                if (!(unbounded && streaming)) {
                    System.arraycopy(buf, 0, pcm, size, take)
                    size += take
                }
                if (streaming && audio.trySend(buf.copyOf(take)).isFailure) {
                    audio.close(IOException("Audio stream buffer exceeded"))
                    return@synchronized true
                }
                !(unbounded && streaming) && size >= pcm.size
            }
            if (full) break
        }
        // 录满自动停:释放麦克风,已录内容留给之后的 stop 取
        synchronized(lock) {
            if (record === rec) {
                release(rec)
                record = null
                _level.value = 0f // 不录了,波形别停在最后一块的音量上
            }
        }
        if (streaming) audio.close()
    }

    /** 停止并返回到目前为止录到的全部 PCM;没在录(含已自动停止)也安全。 */
    override fun stop(): ShortArray {
        val (rec, j) = synchronized(lock) {
            val r = record
            record = null
            if (r != null) release(r)
            r to job.also { job = null }
        }
        // 等读循环退出,保证之后取到的 PCM 不再变
        if (rec != null) runBlocking { j?.join() }
        if (streaming) audio.close()
        return synchronized(lock) {
            val out = pcm.copyOf(size)
            pcm = ShortArray(0)
            size = 0
            _level.value = 0f
            out
        }
    }

    /** 放弃这次录音,丢掉已录内容;可重复调用。 */
    override fun cancel() {
        synchronized(lock) {
            record?.let { release(it) }
            record = null
            job?.cancel()
            job = null
            pcm = ShortArray(0)
            size = 0
            _level.value = 0f
            audio.cancel()
        }
    }

    private fun release(rec: AudioRecord) {
        try { rec.stop() } catch (_: IllegalStateException) {}
        rec.release()
    }
}
