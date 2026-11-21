package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * Agent 专用的虚拟屏。画面输出到本进程的 ImageReader,截图不依赖无障碍截图接口。
 * 帧处理限速到 ≤10 帧/秒:来帧时若离上次处理不足 100ms 就延后到期再取最新一帧,
 * 期间旧帧由 acquireLatestImage 丢弃。
 */
class AgentDisplay(
    private val shell: IMikaShell,
    @Volatile private var bufferWidth: Int,
    @Volatile private var bufferHeight: Int,
    private var dpi: Int,
    private val displayManager: DisplayManager,
) {
    private val thread = HandlerThread("agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader = ImageReader.newInstance(bufferWidth, bufferHeight, PixelFormat.RGBA_8888, 2)

    val displayId: Int

    // ImageReader 使用当前缓冲区的物理尺寸;应用的逻辑坐标随虚拟屏旋转,与主屏的方向无关。
    private val geometry get() = DisplayFrameGeometry(bufferWidth, bufferHeight,
        displayManager.getDisplay(displayId)?.rotation ?: Surface.ROTATION_0)
    val width: Int get() = geometry.width
    val height: Int get() = geometry.height

    private val _frames = MutableStateFlow<Bitmap?>(null)
    /** 实时画面:限速 ≤10 帧/秒,普通预览短边 ≤360，双栏调整虚拟屏后短边 ≤720。 */
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null // 带行填充的整帧,宽 = rowStride / pixelStride
    private var hasFrame = false
    private var frameGeometry = DisplayFrameGeometry(bufferWidth, bufferHeight, Surface.ROTATION_0)
    private var lastProcessedAt = 0L
    private var pending = false
    /** 虚拟屏生产者的单调时钟纳秒时间戳，与整帧和缩略帧一起在 frameLock 下发布。 */
    private var frameTimestampNanos = 0L
    @Volatile private var released = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var previewLimit = 360
    internal val previewWidth get() = geometry.let { it.previewSize(it.previewWidth(previewLimit)).first }
    internal val previewHeight get() = geometry.let { it.previewSize(it.previewWidth(previewLimit)).second }

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }

    init {
        val id = try {
            shell.createDisplay(reader.surface, bufferWidth, bufferHeight, dpi)
        } catch (t: Throwable) {
            closeLocal()
            throw t
        }
        if (id < 0) {
            closeLocal()
            throw IllegalStateException("createDisplay failed")
        }
        displayId = id
        reader.setOnImageAvailableListener({ schedule() }, handler)
        // 建屏期间可能已经有帧排进来;先有正确的屏号,再处理这些帧。
        handler.post { schedule() }
    }

    /** 在帧线程上换接收器，保持同一 displayId 和应用任务；失败时继续使用旧画面。 */
    suspend fun resize(newWidth: Int, newHeight: Int, newDpi: Int): Boolean = kotlin.coroutines.suspendCoroutine { continuation ->
        if (released) { continuation.resumeWith(Result.success(false)); return@suspendCoroutine }
        val posted = handler.post {
            if (released) { continuation.resumeWith(Result.success(false)); return@post }
            val target = geometry.resized(newWidth, newHeight)
            if (bufferWidth == target.bufferWidth && bufferHeight == target.bufferHeight && dpi == newDpi) {
                continuation.resumeWith(Result.success(true)); return@post
            }
            var next: ImageReader? = null
            val resized = try {
                next = ImageReader.newInstance(target.bufferWidth, target.bufferHeight, PixelFormat.RGBA_8888, 2)
                if (!shell.resizeDisplay(displayId, next.surface, target.bufferWidth, target.bufferHeight, newDpi)) false else {
                    reader.setOnImageAvailableListener(null, null)
                    reader.close()
                    synchronized(frameLock) {
                        previewLimit = 720
                        bufferWidth = target.bufferWidth
                        bufferHeight = target.bufferHeight
                        dpi = newDpi
                        fullFrame?.recycle()
                        fullFrame = null
                        hasFrame = false
                        frameTimestampNanos = 0L
                        _frames.value = null
                    }
                    reader = next
                    next = null
                    reader.setOnImageAvailableListener({ schedule() }, handler)
                    schedule()
                    true
                }
            } catch (_: Exception) { false }
            finally { next?.close() }
            continuation.resumeWith(Result.success(resized))
        }
        if (!posted) continuation.resumeWith(Result.success(false))
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            // 没拿到新帧时沿用现有null/新鲜度保护;留下取帧失败证据,不把旧帧当新帧。
            Log.w(TAG, "acquireLatestImage failed: ${t.javaClass.simpleName}", t)
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            val capturedGeometry = geometry
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, bufferHeight, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                // 限速或线程调度会延后消费，不能把复制位图的时刻记成帧时刻。
                frameTimestampNanos = image.timestamp
                frameGeometry = capturedGeometry
                _frames.value = uprightDisplayFrame(bmp, capturedGeometry, capturedGeometry.previewWidth(previewLimit), paint)
            }
        } finally {
            image.close()
        }
    }

    /** 最新一帧的整幅 PNG;还没收到画面时返回 null。 */
    fun latestFramePng(): ByteArray? {
        val crop = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            crop.recycle()
        }
    }

    /** 最新已发布帧的单调时钟纳秒时间戳。 */
    fun lastFrameAt(): Long? = synchronized(frameLock) {
        frameTimestampNanos.takeIf { !released && it > 0 }
    }

    /** OCR 使用原始尺寸的画面副本，避免 PNG 编码和解码；调用方负责 recycle。 */
    fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else uprightDisplayFrame(bmp, frameGeometry, paint = paint)
    }

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        closeLocal()
        _frames.value = null
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacks(process)
        // 在帧线程上收尾,避免与正在处理的帧并发
        handler.post {
            reader.close()
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                hasFrame = false
            }
        }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "AgentDisplay"
        const val FRAME_INTERVAL_MS = 100L
    }
}
