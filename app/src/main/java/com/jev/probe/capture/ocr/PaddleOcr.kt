package com.jev.probe.capture.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * On-device OCR via PaddleOCR (PP-OCRv6 ONNX models, bundled in :ppocr-sdk
 * assets — det + rec + dict, ~31MB). Fully offline: no Google Play services,
 * no model download, Chinese-first accuracy.
 *
 * Coordinates: the engine sees the (possibly cropped) screenshot, so a line's
 * quad is in bitmap space. We take the quad's bounding rect, add the crop
 * offset back and divide by the screenshot scale, handing the caller SCREEN
 * coordinates — the same space node bounds use. Set [scaleX]/[scaleY] from
 * the ScreenCapture result before each batch.
 *
 * Init: [warmUp] loads OpenCV + the ONNX models on IO threads ahead of time;
 * the first [recognize] before init finishes simply answers empty (the same
 * contract as MlKitOcr's failure path — never throws on the caller's thread).
 * Callbacks are posted to the main thread.
 */
object PaddleOcr : OcrEngine {

    /** Screenshot bitmap size / captured area size. Set per capture. */
    var scaleX: Float = 1f
    var scaleY: Float = 1f

    /** Where the captured area starts on screen (a window shot is not the whole
     *  display). Added back after unscaling, so boxes are screen coordinates. */
    var originX: Int = 0
    var originY: Int = 0

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var engine: PaddleOCR? = null
    @Volatile private var initStarted = false
    @Volatile private var appContext: Context? = null

    /**
     * Load OpenCV + ONNX models off the main thread. Call from a worker thread
     * when the service connects so the first real OCR does not pay for it
     * inside the screenshot callback.
     */
    fun warmUp(context: Context) {
        appContext = context.applicationContext
        if (engine != null || initStarted) return
        startInit()
    }

    private fun startInit() {
        val app = appContext ?: return
        initStarted = true
        scope.launch {
            try {
                check(OpenCVUtils.init(app)) { "opencv native load failed" }
                val t0 = System.currentTimeMillis()
                // Default asset paths: models/det/inference.onnx,
                // models/rec/inference.onnx, models/rec/inference.yml (dict).
                engine = PaddleOCR.create(app)
                Log.i(TAG, "paddle ocr ready in ${System.currentTimeMillis() - t0}ms")
            } catch (e: Exception) {
                Log.w(TAG, "paddle ocr init failed: ${e.javaClass.simpleName}: ${e.message}")
                initStarted = false // allow a retry on next recognize/warmUp
            }
        }
    }

    override fun recognize(bitmap: Bitmap, region: Rect?, cb: (List<OcrLine>) -> Unit) {
        val src: Bitmap
        val ox: Int
        val oy: Int
        val cropped: Boolean
        if (region != null) {
            val r = Rect(region)
            if (!r.intersect(0, 0, bitmap.width, bitmap.height) || r.width() < 8 || r.height() < 8) {
                main.post { cb(emptyList()) }; return
            }
            src = try {
                Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
            } catch (e: Exception) {
                Log.w(TAG, "ocr crop failed: ${e.javaClass.simpleName}")
                main.post { cb(emptyList()) }; return
            }
            ox = r.left; oy = r.top; cropped = true
        } else {
            src = bitmap; ox = 0; oy = 0; cropped = false
        }

        val e = engine
        if (e == null) {
            // Not ready yet (or a previous init failed): kick init, answer empty.
            if (!initStarted) startInit()
            if (cropped) src.recycle()
            main.post { cb(emptyList()) }
            return
        }

        val sx = if (scaleX > 0f) scaleX else 1f
        val sy = if (scaleY > 0f) scaleY else 1f
        val wx = originX
        val wy = originY

        scope.launch {
            val lines = ArrayList<OcrLine>()
            try {
                val res = e.recognize(src)
                for (r in res.results) {
                    val t = r.text.trim()
                    if (t.isEmpty()) continue
                    var l = Float.MAX_VALUE; var tp = Float.MAX_VALUE
                    var rt = -Float.MAX_VALUE; var bm = -Float.MAX_VALUE
                    for (p in r.box.points) {
                        if (p.x < l) l = p.x
                        if (p.x > rt) rt = p.x
                        if (p.y < tp) tp = p.y
                        if (p.y > bm) bm = p.y
                    }
                    lines.add(OcrLine(t, Rect(
                        ((l + ox) / sx).toInt() + wx,
                        ((tp + oy) / sy).toInt() + wy,
                        ((rt + ox) / sx).toInt() + wx,
                        ((bm + oy) / sy).toInt() + wy)))
                }
                lines.sortBy { it.bounds.top }
            } catch (ex: Exception) {
                Log.w(TAG, "ocr failed: ${ex.javaClass.simpleName}")
            } finally {
                if (cropped) src.recycle()
            }
            main.post { cb(lines) }
        }
    }

    private const val TAG = "JEVASSIST"
}
