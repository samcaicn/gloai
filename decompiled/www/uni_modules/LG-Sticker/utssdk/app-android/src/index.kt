@file:Suppress("UNCHECKED_CAST", "USELESS_CAST", "INAPPLICABLE_JVM_NAME", "UNUSED_ANONYMOUS_PARAMETER", "NAME_SHADOWING", "UNNECESSARY_NOT_NULL_ASSERTION")
package uts.sdk.modules.LGSticker
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLSurfaceView.Renderer
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.View.OnClickListener
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import io.dcloud.uniapp.*
import io.dcloud.uniapp.extapi.*
import io.dcloud.uts.*
import io.dcloud.uts.Map
import io.dcloud.uts.Set
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import android.graphics.BitmapFactory.Options as BitmapFactoryOptions
import android.hardware.camera2.CameraCaptureSession.StateCallback as CamSessionListener
import android.hardware.camera2.CameraDevice.StateCallback as CamStateListener
import android.hardware.camera2.CaptureRequest.Builder as CaptureRequestBuilder
import android.media.MediaPlayer.OnCompletionListener as CompletionListener
import android.content.DialogInterface.OnDismissListener as DialogDismissListener
import android.graphics.Paint.Cap as PaintCap
import android.graphics.Paint.Join as PaintJoin
import android.graphics.Paint.Style as PaintStyle
import android.media.MediaPlayer.OnPreparedListener as PreparedListener
import android.widget.ScrollView as ScrollViewV
import android.widget.SeekBar.OnSeekBarChangeListener as SeekListener
import android.graphics.SurfaceTexture.OnFrameAvailableListener as SurfaceFrameListener
import io.dcloud.uts.UTSAndroid
import io.dcloud.uts.UTSJSONObject
val K_BG: Int = 0
val K_CAMERA: Int = 1
val K_MEDIA: Int = 2
val K_IMAGE = "image"
val K_VIDEO = "video"
val C_TEXT = "#FFFFFF"
val C_SUB = "#9AA0A6"
val C_ACCENT = "#07C160"
val C_PANEL = "#1C1E22"
var dlg: Dialog? = null
var glView: GLSurfaceView? = null
var renderer: StickerRenderer? = null
var overlay: RectOverlay? = null
var hintView: TextView? = null
var railBox: LinearLayout? = null
var panelBox: LinearLayout? = null
var railHolder: ScrollViewV? = null
var panelHolder: LinearLayout? = null
var reopenBtn: IconBtn? = null
@JvmField
var toolsHidden = false
var activityRef: Activity? = null
var mainHandler: Handler? = null
var layers: UTSArray<StickerLayer> = _uA()
var selected: StickerLayer? = null
var editing = false
var resolveRef: ((v: String) -> Unit)? = null
var rejectRef: ((e: UTSError) -> Unit)? = null
var eventCb: ((ev: String) -> Unit)? = null
var camFacing = "front"
var camZoom: Number = 1.0
var pickMode = false
var pickLayer: StickerLayer? = null
var pickPending = false
var pickX: Number = 0.0
var pickY: Number = 0.0
var viewW: Int = 0
var viewH: Int = 0
fun ctx(): Context {
    val a = activityRef
    if (a != null) {
        return a!!
    }
    return UTSAndroid.getAppContext()!!
}
fun dp(v: Number): Int {
    val c = ctx()
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.getResources().getDisplayMetrics()).toInt()
}
fun color(hex: String): Int {
    try {
        return Color.parseColor(hex)
    }
     catch (e: Throwable) {
        return Color.parseColor("#000000")
    }
}
fun shape(fill: String, radius: Number): GradientDrawable {
    val g = GradientDrawable()
    g.setShape(GradientDrawable.RECTANGLE)
    g.setColor(color(fill))
    g.setCornerRadius(dp(radius).toFloat())
    return g
}
fun mkText(v: String, size: Number, c: String): TextView {
    val t = TextView(ctx())
    t.setText(v)
    t.setTextSize(size.toFloat())
    t.setTextColor(color(c))
    t.setIncludeFontPadding(false)
    return t
}
fun clamp(v: Number, lo: Number, hi: Number): Number {
    if (v < lo) {
        return lo
    }
    if (v > hi) {
        return hi
    }
    return v
}
fun finite(v: Number): Boolean {
    if (v != v) {
        return false
    }
    val big: Number = 1.0e308
    if (v > big || v < -big) {
        return false
    }
    return true
}
fun iclamp(v: Int, lo: Int, hi: Int): Int {
    if (v < lo) {
        return lo
    }
    if (v > hi) {
        return hi
    }
    return v
}
fun hex2(v: Int): String {
    val s = iclamp(v, 0, 255).toString(16).toUpperCase()
    return if (s.length >= 2) {
        s
    } else {
        "0" + s
    }
}
fun rgbHex(r: Int, g: Int, b: Int): String {
    return "#" + hex2(r) + hex2(g) + hex2(b)
}
fun jstr(s: String): String {
    var out = ""
    run {
        var i: Number = 0
        while(i < s.length){
            val ch = s.charAt(i)
            if (ch == "\"") {
                out += "\\\""
            } else if (ch == "\\") {
                out += "\\\\"
            } else if (ch == "\n") {
                out += "\\n"
            } else if (ch == "\r") {
                out += "\\r"
            } else {
                out += ch
            }
            i++
        }
    }
    return "\"" + out + "\""
}
fun jnum(n: Number): String {
    if (!finite(n)) {
        return "0"
    }
    return n.toString(10)
}
fun jbool(b: Boolean): String {
    return if (b) {
        "true"
    } else {
        "false"
    }
}
fun numOf(o: UTSJSONObject?, key: String, def: Number): Number {
    if (o == null) {
        return def
    }
    val n = o.getNumber(key)
    if (n == null) {
        return def
    }
    return n!!
}
fun boolOf(o: UTSJSONObject?, key: String, def: Boolean): Boolean {
    if (o == null) {
        return def
    }
    val b = o.getBoolean(key)
    if (b == null) {
        return def
    }
    return b!!
}
fun strOf(o: UTSJSONObject?, key: String, def: String): String {
    if (o == null) {
        return def
    }
    val s = o.getString(key)
    if (s == null || s == "") {
        return def
    }
    return s!!
}
fun objOf(o: UTSJSONObject?, key: String): UTSJSONObject? {
    if (o == null) {
        return null
    }
    try {
        return o!!.getJSON(key)
    }
     catch (e: Throwable) {
        return null
    }
}
fun showHint(msg: String): Unit {
    console.log("LG-Sticker: " + msg)
    postMain(fun(){
        val h = hintView
        if (h != null) {
            h!!.setText(msg)
            h!!.setVisibility(View.VISIBLE)
        }
    }
    )
}
fun hideHint(): Unit {
    postMain(fun(){
        val h = hintView
        if (h != null) {
            h!!.setVisibility(View.GONE)
        }
    }
    )
}
fun postMain(fn: () -> Unit): Unit {
    var h = mainHandler
    if (h == null) {
        h = Handler(Looper.getMainLooper())
        mainHandler = h
    }
    h!!.post(UiTask(fn))
}
fun queueGl(fn: () -> Unit): Unit {
    val g = glView
    if (g == null) {
        return
    }
    g!!.queueEvent(UiTask(fn))
}
fun requestRender(): Unit {
    val g = glView
    if (g != null) {
        g!!.requestRender()
    }
}
open class StickerLayer {
    open var id: String = ""
    open var kind: Int = K_MEDIA
    open var mediaType: String = K_IMAGE
    open var path: String = ""
    open var x: Number = 0
    open var y: Number = 0
    open var w: Number = 0.3
    open var h: Number = 0.3
    open var aspect: Number = 0.0
    open var z: Number = 1
    open var loop: Boolean = true
    open var muted: Boolean = true
    open var chromaOn: Boolean = false
    open var keyColor: String = "#00FF00"
    open var similarity: Number = 0.28
    open var smoothness: Number = 0.12
    open var spill: Number = 0.15
    open var texId: Int = 0
    open var st: SurfaceTexture? = null
    open var surface: Surface? = null
    open var mp: MediaPlayer? = null
    constructor(id: String, kind: Int, mediaType: String, path: String){
        this.id = id
        this.kind = kind
        this.mediaType = mediaType
        this.path = path
    }
}
fun layerById(id: String): StickerLayer? {
    run {
        var i: Number = 0
        while(i < layers.length){
            if (layers[i].id == id) {
                return layers[i]
            }
            i++
        }
    }
    return null
}
fun cameraLayer(): StickerLayer? {
    run {
        var i: Number = 0
        while(i < layers.length){
            if (layers[i].kind == K_CAMERA) {
                return layers[i]
            }
            i++
        }
    }
    return null
}
fun backgroundLayer(): StickerLayer? {
    run {
        var i: Number = 0
        while(i < layers.length){
            if (layers[i].kind == K_BG) {
                return layers[i]
            }
            i++
        }
    }
    return null
}
fun nextZ(): Number {
    var m: Number = 0
    run {
        var i: Number = 0
        while(i < layers.length){
            if (layers[i].kind != K_BG && layers[i].z > m) {
                m = layers[i].z
            }
            i++
        }
    }
    return m + 1
}
val VS_COMMON = "attribute vec4 aPos;\nattribute vec2 aTex;\nuniform vec4 uRect;\nuniform mat4 uTexMatrix;\nvarying vec2 vTex;\nvoid main(){\n  vec2 p = aPos.xy * 0.5 + 0.5;\n  vec2 s = uRect.xy + p * uRect.zw;\n  vec2 ndc = s * 2.0 - 1.0;\n  gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);\n  vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;\n}\n"
val FS_RGBA = "precision mediump float;\nvarying vec2 vTex;\nuniform sampler2D uTex;\nuniform float uAlpha;\nuniform float uChroma;\nuniform vec3 uKey;\nuniform float uSim;\nuniform float uSmooth;\nuniform float uSpill;\nvoid main(){\n  vec4 c = texture2D(uTex, vec2(vTex.x, 1.0 - vTex.y));\n  if (uChroma > 0.5) {\n    float d = distance(c.rgb, uKey);\n    float a = smoothstep(uSmooth, uSmooth + uSim + 0.001, d);\n    if (uSpill > 0.0) {\n      float g = max(c.g - max(c.r, c.b), 0.0) * uSpill;\n      c.g = max(c.g - g, 0.0);\n      c.r = c.r + g * 0.15;\n      c.b = c.b + g * 0.15;\n    }\n    c.a = a;\n  }\n  gl_FragColor = vec4(c.rgb, c.a * uAlpha);\n}\n"
val FS_CAMERA = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nvarying vec2 vTex;\nuniform samplerExternalOES uTex;\nuniform float uAlpha;\nuniform float uChroma;\nuniform vec3 uKey;\nuniform float uSim;\nuniform float uSmooth;\nuniform float uSpill;\nvoid main(){\n  vec4 c = texture2D(uTex, vTex);\n  float a = 1.0;\n  if (uChroma > 0.5) {\n    float d = distance(c.rgb, uKey);\n    float k = smoothstep(uSmooth, uSmooth + uSim + 0.001, d);\n    if (uSpill > 0.0) {\n      float g = max(c.g - max(c.r, c.b), 0.0) * uSpill;\n      c.g = max(c.g - g, 0.0);\n      c.r = c.r + g * 0.15;\n      c.b = c.b + g * 0.15;\n    }\n    a = min(a, k);\n  }\n  gl_FragColor = vec4(c.rgb, a * uAlpha);\n}\n"
fun compileShader(type: Int, src: String): Int {
    val s = GLES20.glCreateShader(type)
    GLES20.glShaderSource(s, src)
    GLES20.glCompileShader(s)
    val ok = IntArray(1)
    GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
    if (ok[0] == 0) {
        console.log("LG-Sticker 着色器编译失败: " + GLES20.glGetShaderInfoLog(s))
        GLES20.glDeleteShader(s)
        return 0
    }
    return s
}
fun buildProgram(vs: String, fs: String): Int {
    val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
    val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
    if (v == 0 || f == 0) {
        return 0
    }
    val p = GLES20.glCreateProgram()
    GLES20.glAttachShader(p, v)
    GLES20.glAttachShader(p, f)
    GLES20.glLinkProgram(p)
    val ok = IntArray(1)
    GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
    if (ok[0] == 0) {
        console.log("LG-Sticker 程序链接失败: " + GLES20.glGetProgramInfoLog(p))
        GLES20.glDeleteProgram(p)
        return 0
    }
    return p
}
fun newTexture(): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    val id = ids[0]
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    return id
}
fun newExternalTexture(): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    val id = ids[0]
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    return id
}
fun keyR(hex: String): Number {
    val v = color(hex)
    return (Color.red(v).toFloat() / 255.0)
}
fun keyG(hex: String): Number {
    val v = color(hex)
    return (Color.green(v).toFloat() / 255.0)
}
fun keyB(hex: String): Number {
    val v = color(hex)
    return (Color.blue(v).toFloat() / 255.0)
}
fun identity(): FloatArray {
    val m = FloatArray(16)
    m[0] = 1.0.toFloat()
    m[5] = 1.0.toFloat()
    m[10] = 1.0.toFloat()
    m[15] = 1.0.toFloat()
    return m
}
fun makeQuad(): FloatBuffer {
    val arr = _uA(
        -1.0,
        -1.0,
        0.0,
        1.0,
        1.0,
        -1.0,
        1.0,
        1.0,
        -1.0,
        1.0,
        0.0,
        0.0,
        1.0,
        1.0,
        1.0,
        0.0
    ) as UTSArray<Number>
    val bb = ByteBuffer.allocateDirect((arr.length * 4).toInt())
    bb.order(ByteOrder.nativeOrder())
    val fb = bb.asFloatBuffer()
    var fi: Int = 0
    while(fi < arr.length){
        fb.put(fi, arr[fi].toFloat())
        fi++
    }
    fb.position(0)
    return fb
}
open class StickerRenderer : Renderer {
    open var progRgba: Int = 0
    open var progCam: Int = 0
    open var quad: FloatBuffer? = null
    override fun onSurfaceCreated(gl: GL10, cfg: EGLConfig): Unit {
        this.progRgba = buildProgram(VS_COMMON, FS_RGBA)
        this.progCam = buildProgram(VS_COMMON, FS_CAMERA)
        this.quad = makeQuad()
    }
    override fun onSurfaceChanged(gl: GL10, w: Int, h: Int): Unit {
        GLES20.glViewport(0, 0, w, h)
        viewW = w
        viewH = h
        postMain(fun(){
            return fitAllLayers()
        }
        )
    }
    override fun onDrawFrame(gl: GL10): Unit {
        GLES20.glClearColor(0.043.toFloat(), 0.047.toFloat(), 0.06.toFloat(), 1.0.toFloat())
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        run {
            var i: Number = 0
            while(i < layers.length){
                if (layers[i].kind != K_BG) {
                    i++
                    continue
                }
                ensureTexture(layers[i])
                if (layers[i].mediaType == K_VIDEO) {
                    drawExternal(layers[i], false)
                } else {
                    drawMedia(layers[i])
                }
                i++
            }
        }
        val rest: UTSArray<StickerLayer> = _uA()
        run {
            var i: Number = 0
            while(i < layers.length){
                if (layers[i].kind != K_BG) {
                    rest.push(layers[i])
                }
                i++
            }
        }
        rest.sort(fun(a: StickerLayer, b: StickerLayer): Number {
            return a.z - b.z
        }
        )
        run {
            var i: Number = 0
            while(i < rest.length){
                val l = rest[i]
                ensureTexture(l)
                if (l.kind == K_CAMERA) {
                    drawExternal(l, true)
                } else if (l.mediaType == K_VIDEO) {
                    drawExternal(l, false)
                } else {
                    drawMedia(l)
                }
                i++
            }
        }
        if (pickPending) {
            doPick()
        }
    }
}
fun doPick(): Unit {
    pickPending = false
    val l = pickLayer
    val w = viewW
    val h = viewH
    if (l == null || w <= 0 || h <= 0) {
        return
    }
    val cx = (clamp(pickX, 0.0, 1.0) * (w - 1).toFloat()).toInt()
    val cy = ((1.0 - clamp(pickY, 0.0, 1.0)) * (h - 1).toFloat()).toInt()
    val span: Int = 5
    val x0 = iclamp(cx - 2, 0, w - span)
    val y0 = iclamp(cy - 2, 0, h - span)
    val buf = ByteBuffer.allocateDirect(span * span * 4)
    buf.order(ByteOrder.nativeOrder())
    GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
    GLES20.glReadPixels(x0, y0, span, span, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
    var rs: Number = 0
    var gs: Number = 0
    var bs: Number = 0
    val total: Int = span * span
    var i: Int = 0
    while(i < total){
        val p: Int = i * 4
        rs += buf.get(p).toInt() and 0xFF
        gs += buf.get(p + 1).toInt() and 0xFF
        bs += buf.get(p + 2).toInt() and 0xFF
        i++
    }
    val hex = rgbHex((rs / total).toInt(), (gs / total).toInt(), (bs / total).toInt())
    postMain(fun(){
        l!!.keyColor = hex
        if (!l!!.chromaOn) {
            l!!.chromaOn = true
        }
        pickMode = false
        pickLayer = null
        showHint("已取色 " + hex + "，再点「吸管」可重取")
        val ov = overlay
        if (ov != null) {
            ov!!.invalidate()
        }
        rebuildPanel()
        requestRender()
    }
    )
}
fun cancelPick(): Unit {
    if (!pickMode) {
        return
    }
    pickMode = false
    pickLayer = null
    pickPending = false
    val ov = overlay
    if (ov != null) {
        ov!!.invalidate()
    }
}
fun bindQuad(prog: Int, quad: FloatBuffer): Unit {
    val aPos = GLES20.glGetAttribLocation(prog, "aPos")
    val aTex = GLES20.glGetAttribLocation(prog, "aTex")
    if (aPos >= 0) {
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
    }
    if (aTex >= 0) {
        GLES20.glEnableVertexAttribArray(aTex)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(0)
    }
}
fun drawMedia(l: StickerLayer): Unit {
    val r = renderer
    if (r == null || l.texId == 0 || r!!.quad == null || r!!.progRgba == 0) {
        return
    }
    val prog = r!!.progRgba
    val quad = r!!.quad!!
    GLES20.glUseProgram(prog)
    GLES20.glEnable(GLES20.GL_BLEND)
    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, l.texId)
    GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
    GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uRect"), l.x.toFloat(), l.y.toFloat(), l.w.toFloat(), l.h.toFloat())
    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAlpha"), 1.0.toFloat())
    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTexMatrix"), 1, false, identity(), 0)
    val chroma = l.mediaType == K_VIDEO && l.chromaOn && !(pickMode && pickLayer == l)
    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uChroma"), if (chroma) {
        1.0.toFloat()
    } else {
        0.0.toFloat()
    }
    )
    GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uKey"), keyR(l.keyColor).toFloat(), keyG(l.keyColor).toFloat(), keyB(l.keyColor).toFloat())
    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSim"), l.similarity.toFloat())
    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSmooth"), l.smoothness.toFloat())
    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSpill"), l.spill.toFloat())
    bindQuad(prog, quad)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GLES20.glDisable(GLES20.GL_BLEND)
}
fun drawExternal(l: StickerLayer, camera: Boolean): Unit {
    val r = renderer
    val st = l.st
    if (r == null || st == null || l.texId == 0 || r!!.quad == null || r!!.progCam == 0) {
        return
    }
    val prog = r!!.progCam
    val quad = r!!.quad!!
    st!!.updateTexImage()
    val tm = FloatArray(16)
    st!!.getTransformMatrix(tm)
    GLES20.glUseProgram(prog)
    GLES20.glEnable(GLES20.GL_BLEND)
    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, l.texId)
    GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
    val chroma = !camera && l.mediaType == K_VIDEO && l.chromaOn && !(pickMode && pickLayer == l)
    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uChroma"), if (chroma) {
        1.0.toFloat()
    } else {
        0.0.toFloat()
    }
    )
    GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uKey"), keyR(l.keyColor).toFloat(), keyG(l.keyColor).toFloat(), keyB(l.keyColor).toFloat())
    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSim"), l.similarity.toFloat())
    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSmooth"), l.smoothness.toFloat())
    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSpill"), l.spill.toFloat())
    GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uRect"), l.x.toFloat(), l.y.toFloat(), l.w.toFloat(), l.h.toFloat())
    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAlpha"), 1.0.toFloat())
    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTexMatrix"), 1, false, tm, 0)
    bindQuad(prog, quad)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GLES20.glDisable(GLES20.GL_BLEND)
}
fun ensureTexture(l: StickerLayer): Unit {
    if (l.texId != 0) {
        return
    }
    if (l.kind == K_CAMERA) {
        l.texId = newExternalTexture()
        val st = SurfaceTexture(l.texId)
        st.setDefaultBufferSize(1280, 720)
        l.st = st
        val h = camHandler
        if (h != null) {
            st.setOnFrameAvailableListener(FrameCb(l), h)
        } else {
            st.setOnFrameAvailableListener(FrameCb(l))
        }
        postMain(fun(){
            return startCamera()
        }
        )
        return
    }
    if (l.mediaType == K_VIDEO) {
        l.texId = newExternalTexture()
        val st = SurfaceTexture(l.texId)
        st.setDefaultBufferSize(1280, 720)
        l.st = st
        val hv = camHandler
        if (hv != null) {
            st.setOnFrameAvailableListener(FrameCb(l), hv)
        } else {
            st.setOnFrameAvailableListener(FrameCb(l))
        }
        val target = l
        postMain(fun(){
            return startVideo(target)
        }
        )
        return
    }
    if (l.path == "") {
        return
    }
    val bmp = decodeBitmap(l.path)
    if (bmp == null) {
        postMain(fun(){
            return showHint("图片解码失败，请重新选择素材")
        }
        )
        return
    }
    val id = newTexture()
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    if (l.aspect <= 0.0 && bmp.getHeight() > 0) {
        l.aspect = bmp.getWidth().toFloat() / bmp.getHeight().toFloat()
        postMain(fun(){
            return applyAspectFit(l, l.kind == K_BG)
        }
        )
    }
    bmp.recycle()
    l.texId = id
    requestRender()
}
fun decodeBitmap(path: String): Bitmap? {
    if (path == "") {
        return null
    }
    try {
        val b = BitmapFactory.decodeFile(path)
        if (b == null) {
            return null
        }
        return b!!
    }
     catch (e: Throwable) {
        return null
    }
}
fun mediaAspect(path: String, isVideo: Boolean): Number {
    if (path == "") {
        return 0.0
    }
    try {
        var w: Number = 0
        var h: Number = 0
        if (isVideo) {
            val md = MediaMetadataRetriever()
            try {
                md.setDataSource(path)
                val sw = md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val sh = md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val sr = md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                if (sw != null) {
                    w = parseInt(sw!!)
                }
                if (sh != null) {
                    h = parseInt(sh!!)
                }
                val rot: Number = if (sr != null) {
                    parseInt(sr!!)
                } else {
                    0
                }
                if (rot == 90 || rot == 270) {
                    val t = w
                    w = h
                    h = t
                }
            } catch (e: Throwable) {}
            try {
                md.release()
            } catch (e: Throwable) {}
        } else {
            val opt = BitmapFactoryOptions()
            opt.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, opt)
            w = opt.outWidth
            h = opt.outHeight
        }
        if (w <= 0 || h <= 0) {
            return 0.0
        }
        return w.toFloat() / h.toFloat()
    }
     catch (e: Throwable) {
        return 0.0
    }
}
fun applyAspectFit(l: StickerLayer, cover: Boolean): Unit {
    if (l.aspect <= 0.0 || viewW <= 0 || viewH <= 0) {
        return
    }
    val vw = viewW.toFloat()
    val vh = viewH.toFloat()
    if (cover) {
        if (l.aspect >= vw / vh) {
            l.h = 1.0
            l.w = l.aspect * vh / vw
        } else {
            l.w = 1.0
            l.h = vw / (l.aspect * vh)
        }
        l.x = (1.0 - l.w) * 0.5
        l.y = (1.0 - l.h) * 0.5
        return
    }
    val w = clamp(l.w, 0.02, 1.0)
    var h = w * vw / (l.aspect * vh)
    if (h > 0.95) {
        h = 0.95
        l.w = h * l.aspect * vh / vw
    }
    l.h = h
    l.x = clamp(l.x, 0.0, 1.0 - l.w)
    l.y = clamp(l.y, 0.0, 1.0 - l.h)
}
fun fitAllLayers(): Unit {
    run {
        var i: Number = 0
        while(i < layers.length){
            val l = layers[i]
            applyAspectFit(l, l.kind == K_BG)
            i++
        }
    }
    val ov = overlay
    if (ov != null) {
        ov!!.invalidate()
    }
    requestRender()
}
fun releaseTexture(l: StickerLayer): Unit {
    val id = l.texId
    l.texId = 0
    if (id == 0) {
        return
    }
    queueGl(fun(){
        val ids = IntArray(1)
        ids[0] = id
        GLES20.glDeleteTextures(1, ids, 0)
    }
    )
}
open class FrameCb : SurfaceFrameListener {
    open lateinit var layer: StickerLayer
    constructor(l: StickerLayer){
        this.layer = l
    }
    override fun onFrameAvailable(st: SurfaceTexture?): Unit {
        requestRender()
    }
}
var camDevice: CameraDevice? = null
var camSession: CameraCaptureSession? = null
var camHandler: Handler? = null
var camSurface: Surface? = null
var camStarting = false
var camGen: Int = 0
var camIdInUse = ""
fun ensureCamHandler(): Unit {
    if (camHandler != null) {
        return
    }
    val t = HandlerThread("lg-sticker-cam")
    t.start()
    t
    camHandler = Handler(t.getLooper())
}
open class CamStateCb : CamStateListener {
    open var gen: Int = 0
    constructor(g: Int){
        this.gen = g
    }
    override fun onOpened(dev: CameraDevice): Unit {
        if (this.gen != camGen) {
            try {
                dev.close()
            }
             catch (e: Throwable) {}
            return
        }
        camDevice = dev
        val prev = camSurface
        if (prev == null) {
            camStarting = false
            return
        }
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            req.addTarget(prev!!)
            val targets = _uA(
                prev!!
            ) as UTSArray<Surface>
            dev.createCaptureSession(targets, SessionCb(req, this.gen), camHandler)
        }
         catch (e: Throwable) {
            camStarting = false
            showHint("预览会话创建失败")
        }
    }
    override fun onDisconnected(dev: CameraDevice): Unit {
        if (this.gen == camGen) {
            stopCamera()
        }
    }
    override fun onError(dev: CameraDevice, err: Int): Unit {
        if (this.gen != camGen) {
            return
        }
        stopCamera()
        showHint("摄像头异常（错误码 " + err.toString() + "）")
    }
}
open class SessionCb : CamSessionListener {
    open lateinit var req: CaptureRequestBuilder
    open var gen: Int = 0
    constructor(r: CaptureRequestBuilder, g: Int){
        this.req = r
        this.gen = g
    }
    override fun onConfigured(s: CameraCaptureSession): Unit {
        if (this.gen != camGen) {
            try {
                s.close()
            }
             catch (e: Throwable) {}
            return
        }
        camSession = s
        try {
            val r = this.req
            r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            r.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            val zr = zoomRegion(camIdInUse, camZoom)
            if (zr != null) {
                r.set(CaptureRequest.SCALER_CROP_REGION, zr)
            }
            s.setRepeatingRequest(r.build(), null, camHandler)
            camStarting = false
            hideHint()
        }
         catch (e: Throwable) {
            camStarting = false
            showHint("预览启动失败")
        }
    }
    override fun onConfigureFailed(s: CameraCaptureSession): Unit {
        camStarting = false
        showHint("预览配置失败")
    }
}
fun zoomRegion(camId: String, zoom: Number): Rect? {
    val act = activityRef
    if (act == null || camId == "") {
        return null
    }
    try {
        val cm = act!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ch = cm.getCameraCharacteristics(camId)
        val rawFull = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (rawFull == null) {
            return null
        }
        val full = rawFull as Rect
        val f = full!!
        val z = clamp(zoom, 1.0, 10.0)
        val cw = (f.width() / z).toInt()
        val chh = (f.height() / z).toInt()
        val cx = f.centerX()
        val cy = f.centerY()
        return Rect(cx - cw / 2, cy - chh / 2, cx + cw / 2, cy + chh / 2)
    }
     catch (e: Throwable) {
        return null
    }
}
fun startCamera(): Unit {
    val l = cameraLayer()
    val st = l?.st
    if (l == null || st == null || camDevice != null || camStarting) {
        return
    }
    val act = activityRef
    if (act == null) {
        return
    }
    camStarting = true
    ensureCamHandler()
    try {
        val cm = act!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = cm.getCameraIdList()
        var picked = ""
        run {
            var i: Int = 0
            while(i < ids.size){
                val ch = cm.getCameraCharacteristics(ids[i])
                val rawFacing = ch.get(CameraCharacteristics.LENS_FACING)
                val facing = if (rawFacing == null) {
                    -1
                } else {
                    (rawFacing as Int)
                }
                val want = if (camFacing == "front") {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }
                if (facing == want) {
                    picked = ids[i]
                    break
                }
                i++
            }
        }
        if (picked == "" && ids.size > 0) {
            picked = ids[0]
        }
        if (picked == "") {
            camStarting = false
            showHint("未找到可用摄像头")
            return
        }
        camIdInUse = picked
        camSurface = Surface(st!!)
        cm.openCamera(picked, CamStateCb(camGen), camHandler)
    }
     catch (e: Throwable) {
        camStarting = false
        showHint("摄像头打开失败：请检查权限或被其它应用占用")
    }
}
fun stopCamera(): Unit {
    camGen++
    val s = camSession
    if (s != null) {
        try {
            s!!.close()
        }
         catch (e: Throwable) {}
        camSession = null
    }
    val d = camDevice
    if (d != null) {
        try {
            d!!.close()
        }
         catch (e: Throwable) {}
        camDevice = null
    }
    val sf = camSurface
    if (sf != null) {
        try {
            sf!!.release()
        }
         catch (e: Throwable) {}
        camSurface = null
    }
    camStarting = false
}
fun restartCamera(): Unit {
    stopCamera()
    postMain(fun(){
        return startCamera()
    }
    )
}
fun applyZoom(): Unit {
    val s = camSession
    val d = camDevice
    val sf = camSurface
    if (s == null || d == null || sf == null) {
        return
    }
    try {
        val req = d!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        req.addTarget(sf!!)
        val zr = zoomRegion(camIdInUse, camZoom)
        if (zr != null) {
            req.set(CaptureRequest.SCALER_CROP_REGION, zr)
        }
        s!!.setRepeatingRequest(req.build(), null, camHandler)
    }
     catch (e: Throwable) {
        restartCamera()
    }
}
open class PreparedCb : PreparedListener {
    open lateinit var layer: StickerLayer
    constructor(l: StickerLayer){
        this.layer = l
    }
    override fun onPrepared(mp: MediaPlayer?): Unit {
        try {
            mp!!.start()
        }
         catch (e: Throwable) {}
    }
}
open class LoopCb : CompletionListener {
    open lateinit var layer: StickerLayer
    constructor(l: StickerLayer){
        this.layer = l
    }
    override fun onCompletion(mp: MediaPlayer?): Unit {
        if (!this.layer.loop) {
            return
        }
        try {
            mp!!.seekTo(0)
            mp!!.start()
        }
         catch (e: Throwable) {}
    }
}
fun startVideo(l: StickerLayer): Unit {
    val st = l.st
    if (st == null || l.mp != null || l.path == "") {
        return
    }
    try {
        val mp = MediaPlayer()
        mp.setDataSource(l.path)
        val sf = Surface(st!!)
        l.surface = sf
        mp.setSurface(sf)
        mp.setLooping(l.loop)
        val vol = if (l.muted) {
            0.0
        } else {
            1.0
        }
        mp.setVolume(vol.toFloat(), vol.toFloat())
        mp.setOnPreparedListener(PreparedCb(l))
        mp.setOnCompletionListener(LoopCb(l))
        mp.prepareAsync()
        l.mp = mp
    }
     catch (e: Throwable) {
        showHint("视频加载失败，请重新选择素材")
    }
}
fun releaseMedia(l: StickerLayer): Unit {
    val mp = l.mp
    if (mp != null) {
        try {
            mp!!.stop()
            mp!!.release()
        }
         catch (e: Throwable) {}
        l.mp = null
    }
    val sf = l.surface
    if (sf != null) {
        try {
            sf!!.release()
        }
         catch (e: Throwable) {}
        l.surface = null
    }
    if (l.kind != K_CAMERA) {
        val st = l.st
        if (st != null) {
            try {
                st!!.release()
            }
             catch (e: Throwable) {}
            l.st = null
        }
    }
}
open class UiTask : Runnable {
    open lateinit var fn: (() -> Unit)
    constructor(f: () -> Unit){
        this.fn = f
    }
    override fun run(): Unit {
        try {
            this.fn()
        }
         catch (e: Throwable) {
            console.log("LG-Sticker 主线程任务失败: " + e)
        }
    }
}
open class ClickCb : OnClickListener {
    open lateinit var fn: (() -> Unit)
    constructor(f: () -> Unit){
        this.fn = f
    }
    override fun onClick(v: View?): Unit {
        this.fn()
    }
}
fun icoLine(can: Canvas, p: Paint, x1: Number, y1: Number, x2: Number, y2: Number): Unit {
    can.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), p)
}
fun icoDot(can: Canvas, p: Paint, x: Number, y: Number, r: Number): Unit {
    can.drawCircle(x.toFloat(), y.toFloat(), r.toFloat(), p)
}
fun icoBox(can: Canvas, p: Paint, l: Number, t: Number, r: Number, b: Number, rad: Number): Unit {
    can.drawRoundRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), rad.toFloat(), rad.toFloat(), p)
}
fun icoPath(can: Canvas, p: Paint, pts: UTSArray<Number>, closed: Boolean = false): Unit {
    if (pts.length < 4) {
        return
    }
    val path = Path()
    path.moveTo(pts[0].toFloat(), pts[1].toFloat())
    var i: Number = 2
    while(i + 1 < pts.length){
        path.lineTo(pts[i].toFloat(), pts[i + 1].toFloat())
        i += 2
    }
    if (closed) {
        path.close()
    }
    can.drawPath(path, p)
}
open class IconBtn : View {
    open var kind: String = ""
    open var active: Boolean = false
    open var primary: Boolean = false
    open var danger: Boolean = false
    open var badge: String = ""
    constructor(k: String, f: () -> Unit) : super(ctx()) {
        this.kind = k
        this.setWillNotDraw(false)
        this.setOnClickListener(ClickCb(f))
    }
    override fun onDraw(can: Canvas): Unit {
        val sz = this.getWidth()
        val hh = this.getHeight()
        if (sz <= 0) {
            return
        }
        val bg = Paint()
        bg.setAntiAlias(true)
        bg.setStyle(PaintStyle.FILL)
        if (this.active) {
            bg.setColor(color("#E607C160"))
        } else if (this.primary) {
            bg.setColor(color("#F2FFFFFF"))
        } else if (this.danger) {
            bg.setColor(color("#CC7A2A22"))
        } else {
            bg.setColor(color("#99090C10"))
        }
        can.drawRoundRect(dp(2).toFloat(), dp(2).toFloat(), (sz - dp(2)).toFloat(), (hh - dp(2)).toFloat(), dp(15).toFloat(), dp(15).toFloat(), bg)
        val p = Paint()
        p.setAntiAlias(true)
        p.setStyle(PaintStyle.STROKE)
        p.setStrokeWidth(dp(1.8).toFloat())
        p.setStrokeCap(PaintCap.ROUND)
        p.setStrokeJoin(PaintJoin.ROUND)
        if (this.active || this.primary) {
            p.setColor(color("#0A1A10"))
        } else {
            p.setColor(color("#F0F3F6"))
        }
        val cx = sz / 2
        val cy = hh / 2
        val gs = if (hh >= dp(40)) {
            dp(10)
        } else {
            dp(8)
        }
        this.glyph(can, p, cx, cy, gs)
        if (this.badge != "") {
            val bp = Paint()
            bp.setAntiAlias(true)
            bp.setTextSize(dp(10).toFloat())
            if (this.active) {
                bp.setColor(color("#0A1A10"))
            } else {
                bp.setColor(color("#FFD166"))
            }
            val tw = bp.measureText(this.badge)
            can.drawText(this.badge, sz.toFloat() - tw - dp(4).toFloat(), hh.toFloat() - dp(3).toFloat(), bp)
        }
    }
    open fun glyph(can: Canvas, p: Paint, cx: Int, cy: Int, s: Int): Unit {
        val k = this.kind
        if (k == "close") {
            val d = s * 0.62
            icoLine(can, p, cx - d, cy - d, cx + d, cy + d)
            icoLine(can, p, cx + d, cy - d, cx - d, cy + d)
            return
        }
        if (k == "check") {
            icoPath(can, p, _uA(
                cx - s * 0.8,
                cy + s * 0.04,
                cx - s * 0.22,
                cy + s * 0.62,
                cx + s * 0.86,
                cy - s * 0.6
            ))
            return
        }
        if (k == "bg") {
            icoPath(can, p, _uA(
                cx,
                cy - s * 0.72,
                cx + s * 0.88,
                cy - s * 0.24,
                cx,
                cy + s * 0.24,
                cx - s * 0.88,
                cy - s * 0.24
            ), true)
            icoPath(can, p, _uA(
                cx - s * 0.88,
                cy + s * 0.14,
                cx,
                cy + s * 0.66,
                cx + s * 0.88,
                cy + s * 0.14
            ))
            return
        }
        if (k == "camera") {
            icoBox(can, p, cx - s * 0.92, cy - s * 0.5, cx + s * 0.92, cy + s * 0.72, s * 0.28)
            icoDot(can, p, cx, cy + s * 0.1, s * 0.34)
            icoPath(can, p, _uA(
                cx - s * 0.3,
                cy - s * 0.5,
                cx - s * 0.2,
                cy - s * 0.8,
                cx + s * 0.2,
                cy - s * 0.8,
                cx + s * 0.3,
                cy - s * 0.5
            ))
            return
        }
        if (k == "image" || k == "imagePlus") {
            val plus = k == "imagePlus"
            val a = if (plus) {
                s * 0.8
            } else {
                s
            }
            val bx = if (plus) {
                cx - s * 0.16
            } else {
                cx
            }
            val by = if (plus) {
                cy - s * 0.16
            } else {
                cy
            }
            icoBox(can, p, bx - a * 0.95, by - a * 0.76, bx + a * 0.95, by + a * 0.76, a * 0.26)
            icoDot(can, p, bx + a * 0.42, by - a * 0.34, a * 0.17)
            icoPath(can, p, _uA(
                bx - a * 0.76,
                by + a * 0.5,
                bx - a * 0.14,
                by - a * 0.06,
                bx + a * 0.3,
                by + a * 0.34,
                bx + a * 0.52,
                by + a * 0.12,
                bx + a * 0.76,
                by + a * 0.5
            ))
            if (plus) {
                icoDot(can, p, cx + s * 0.58, cy + s * 0.58, s * 0.32)
                icoLine(can, p, cx + s * 0.58, cy + s * 0.34, cx + s * 0.58, cy + s * 0.82)
                icoLine(can, p, cx + s * 0.34, cy + s * 0.58, cx + s * 0.82, cy + s * 0.58)
            }
            return
        }
        if (k == "video" || k == "videoPlus") {
            val plus = k == "videoPlus"
            val a = if (plus) {
                s * 0.8
            } else {
                s
            }
            val bx = if (plus) {
                cx - s * 0.16
            } else {
                cx
            }
            val by = if (plus) {
                cy - s * 0.16
            } else {
                cy
            }
            icoBox(can, p, bx - a * 0.95, by - a * 0.74, bx + a * 0.95, by + a * 0.74, a * 0.26)
            icoPath(can, p, _uA(
                bx - a * 0.2,
                by - a * 0.36,
                bx + a * 0.46,
                by,
                bx - a * 0.2,
                by + a * 0.36
            ), true)
            if (plus) {
                icoDot(can, p, cx + s * 0.58, cy + s * 0.58, s * 0.32)
                icoLine(can, p, cx + s * 0.58, cy + s * 0.34, cx + s * 0.58, cy + s * 0.82)
                icoLine(can, p, cx + s * 0.34, cy + s * 0.58, cx + s * 0.82, cy + s * 0.58)
            }
            return
        }
        if (k == "collapse" || k == "expand") {
            icoBox(can, p, cx - s * 0.9, cy - s * 0.72, cx + s * 0.9, cy + s * 0.72, s * 0.26)
            icoLine(can, p, cx - s * 0.34, cy - s * 0.72, cx - s * 0.34, cy + s * 0.72)
            if (k == "collapse") {
                icoLine(can, p, cx + s * 0.54, cy, cx + s * 0.02, cy)
                icoPath(can, p, _uA(
                    cx + s * 0.26,
                    cy - s * 0.22,
                    cx + s * 0.02,
                    cy,
                    cx + s * 0.26,
                    cy + s * 0.22
                ))
            } else {
                icoLine(can, p, cx + s * 0.02, cy, cx + s * 0.56, cy)
                icoPath(can, p, _uA(
                    cx + s * 0.32,
                    cy - s * 0.22,
                    cx + s * 0.58,
                    cy,
                    cx + s * 0.32,
                    cy + s * 0.22
                ))
            }
            return
        }
        if (k == "swap") {
            icoLine(can, p, cx - s * 0.8, cy - s * 0.4, cx + s * 0.5, cy - s * 0.4)
            icoPath(can, p, _uA(
                cx + s * 0.22,
                cy - s * 0.66,
                cx + s * 0.54,
                cy - s * 0.4,
                cx + s * 0.22,
                cy - s * 0.14
            ))
            icoLine(can, p, cx + s * 0.8, cy + s * 0.4, cx - s * 0.5, cy + s * 0.4)
            icoPath(can, p, _uA(
                cx - s * 0.22,
                cy + s * 0.14,
                cx - s * 0.54,
                cy + s * 0.4,
                cx - s * 0.22,
                cy + s * 0.66
            ))
            return
        }
        if (k == "front") {
            icoLine(can, p, cx - s * 0.82, cy - s * 0.72, cx + s * 0.82, cy - s * 0.72)
            icoLine(can, p, cx, cy - s * 0.36, cx, cy + s * 0.74)
            icoPath(can, p, _uA(
                cx - s * 0.42,
                cy - s * 0.02,
                cx,
                cy - s * 0.42,
                cx + s * 0.42,
                cy - s * 0.02
            ))
            return
        }
        if (k == "trash") {
            icoLine(can, p, cx - s * 0.7, cy - s * 0.46, cx + s * 0.7, cy - s * 0.46)
            icoPath(can, p, _uA(
                cx - s * 0.28,
                cy - s * 0.46,
                cx - s * 0.28,
                cy - s * 0.72,
                cx + s * 0.28,
                cy - s * 0.72,
                cx + s * 0.28,
                cy - s * 0.46
            ))
            icoPath(can, p, _uA(
                cx - s * 0.52,
                cy - s * 0.2,
                cx - s * 0.4,
                cy + s * 0.72,
                cx + s * 0.4,
                cy + s * 0.72,
                cx + s * 0.52,
                cy - s * 0.2
            ))
            icoLine(can, p, cx - s * 0.16, cy + s * 0.02, cx - s * 0.12, cy + s * 0.5)
            icoLine(can, p, cx + s * 0.16, cy + s * 0.02, cx + s * 0.12, cy + s * 0.5)
            return
        }
    }
}
open class RectOverlay : View {
    constructor(c: Context) : super(c) {
        this.setWillNotDraw(false)
    }
    override fun onDraw(can: Canvas): Unit {
        val w = this.getWidth().toFloat()
        val h = this.getHeight().toFloat()
        if (pickMode) {
            val cx = clamp(pickX, 0.0, 1.0).toFloat() * w
            val cy = clamp(pickY, 0.0, 1.0).toFloat() * h
            val ring = Paint()
            ring.setColor(Color.parseColor("#FFFFFF"))
            ring.setStyle(PaintStyle.STROKE)
            ring.setStrokeWidth(dp(2).toFloat())
            can!!.drawCircle(cx, cy, dp(16).toFloat(), ring)
            val cross = Paint()
            cross.setColor(Color.parseColor("#07C160"))
            cross.setStyle(PaintStyle.STROKE)
            cross.setStrokeWidth(dp(2).toFloat())
            val gap = dp(22).toFloat()
            val inner = dp(6).toFloat()
            can!!.drawLine(cx - gap, cy, cx - inner, cy, cross)
            can!!.drawLine(cx + inner, cy, cx + gap, cy, cross)
            can!!.drawLine(cx, cy - gap, cx, cy - inner, cross)
            can!!.drawLine(cx, cy + inner, cx, cy + gap, cross)
            return
        }
        if (toolsHidden) {
            return
        }
        val l = selected
        if (l == null || l!!.kind == K_BG) {
            return
        }
        val p = Paint()
        p.setColor(Color.parseColor("#07C160"))
        p.setStyle(PaintStyle.STROKE)
        p.setStrokeWidth(dp(2).toFloat())
        val left = l!!.x.toFloat() * w
        val top = l!!.y.toFloat() * h
        can!!.drawRect(left, top, left + l!!.w.toFloat() * w, top + l!!.h.toFloat() * h, p)
    }
}
val MODE_NONE: Int = 0
val MODE_MOVE: Int = 1
val MODE_SCALE: Int = 2
var gestureMode = MODE_NONE
var touchStartX: Number = 0.0
var touchStartY: Number = 0.0
var layerStartX: Number = 0.0
var layerStartY: Number = 0.0
var layerStartW: Number = 0.0
var layerStartH: Number = 0.0
var pinchDist: Number = 0.0
fun hitTest(nx: Number, ny: Number): StickerLayer? {
    val rest: UTSArray<StickerLayer> = _uA()
    run {
        var i: Number = 0
        while(i < layers.length){
            if (layers[i].kind != K_BG) {
                rest.push(layers[i])
            }
            i++
        }
    }
    rest.sort(fun(a: StickerLayer, b: StickerLayer): Number {
        return b.z - a.z
    }
    )
    run {
        var i: Number = 0
        while(i < rest.length){
            val l = rest[i]
            if (nx >= l.x && nx <= l.x + l.w && ny >= l.y && ny <= l.y + l.h) {
                return l
            }
            i++
        }
    }
    return null
}
fun distOf(ev: MotionEvent): Number {
    if (ev.getPointerCount() < 2) {
        return 0.0
    }
    val dx = ev.getX(1) - ev.getX(0)
    val dy = ev.getY(1) - ev.getY(0)
    return Math.sqrt(dx * dx + dy * dy)
}
open class GestureCb : OnTouchListener {
    override fun onTouch(v: View?, ev: MotionEvent?): Boolean {
        if (ev == null) {
            return false
        }
        val w = v!!.getWidth().toFloat()
        val h = v!!.getHeight().toFloat()
        val nx = ev!!.getX().toFloat() / w
        val ny = ev!!.getY().toFloat() / h
        val act = ev!!.getActionMasked()
        if (pickMode) {
            if (act == MotionEvent.ACTION_DOWN) {
                pickX = clamp(nx, 0.0, 1.0)
                pickY = clamp(ny, 0.0, 1.0)
                pickPending = true
                requestRender()
                val ov = overlay
                if (ov != null) {
                    ov!!.invalidate()
                }
            }
            return true
        }
        if (act == MotionEvent.ACTION_DOWN) {
            selected = hitTest(nx, ny)
            gestureMode = MODE_NONE
            touchStartX = nx
            touchStartY = ny
            val l = selected
            if (l != null) {
                gestureMode = MODE_MOVE
                layerStartX = l!!.x
                layerStartY = l!!.y
                layerStartW = l!!.w
                layerStartH = l!!.h
            }
            if (!toolsHidden) {
                rebuildChips()
                rebuildPanel()
            }
            requestRender()
            val ov = overlay
            if (ov != null) {
                ov!!.invalidate()
            }
            return true
        }
        if (act == MotionEvent.ACTION_POINTER_DOWN) {
            val l = selected
            if (l != null && ev!!.getPointerCount() >= 2) {
                gestureMode = MODE_SCALE
                pinchDist = distOf(ev!!)
                layerStartW = l!!.w
                layerStartH = l!!.h
                layerStartX = l!!.x
                layerStartY = l!!.y
            }
            return true
        }
        if (act == MotionEvent.ACTION_MOVE) {
            val l = selected
            if (l == null) {
                return true
            }
            if (gestureMode == MODE_MOVE) {
                val dx = nx - touchStartX
                val dy = ny - touchStartY
                l!!.x = clamp(layerStartX + dx, 0.0 - l!!.w * 0.5, 1.0 - l!!.w * 0.5)
                l!!.y = clamp(layerStartY + dy, 0.0 - l!!.h * 0.5, 1.0 - l!!.h * 0.5)
            } else if (gestureMode == MODE_SCALE) {
                val d = distOf(ev!!)
                if (pinchDist > 1.0 && d > 1.0) {
                    val ratio = clamp(d / pinchDist, 0.2, 5.0)
                    var nw = layerStartW * ratio
                    var nh = layerStartH * ratio
                    var maxDim = nw
                    if (nh > maxDim) {
                        maxDim = nh
                    }
                    if (maxDim > 1.0) {
                        nw = nw / maxDim
                        nh = nh / maxDim
                    }
                    var minDim = nw
                    if (nh < minDim) {
                        minDim = nh
                    }
                    if (minDim < 0.05) {
                        val k2 = (0.05 as Number) / minDim
                        nw = nw * k2
                        nh = nh * k2
                    }
                    val cx = layerStartX + layerStartW / 2.0
                    val cy = layerStartY + layerStartH / 2.0
                    l!!.w = nw
                    l!!.h = nh
                    l!!.x = clamp(cx - nw / 2.0, 0.0 - nw * 0.5, 1.0 - nw * 0.5)
                    l!!.y = clamp(cy - nh / 2.0, 0.0 - nh * 0.5, 1.0 - nh * 0.5)
                }
            }
            requestRender()
            val ov2 = overlay
            if (ov2 != null) {
                ov2!!.invalidate()
            }
            return true
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            gestureMode = MODE_NONE
            return true
        }
        return true
    }
}
fun barLp(weight: Number): LinearLayout.LayoutParams {
    if (weight > 0) {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.weight = 1.0.toFloat()
        return lp
    }
    val lp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp2.setMargins(dp(4), 0, dp(4), 0)
    return lp2
}
fun pill(label: String, bg: String, fn: () -> Unit): TextView {
    val t = mkText(label, 13, C_TEXT)
    t.setGravity(Gravity.CENTER)
    t.setPadding(dp(14), dp(7), dp(14), dp(7))
    t.setBackground(shape(bg, 18))
    t.setOnClickListener(ClickCb(fn))
    return t
}
fun bottomBar(): LinearLayout {
    val bar = LinearLayout(ctx())
    bar.setOrientation(LinearLayout.VERTICAL)
    bar.setBackground(shape(C_PANEL, 18))
    bar.setPadding(dp(12), dp(8), dp(12), dp(10))
    val pbox = LinearLayout(ctx())
    pbox.setOrientation(LinearLayout.VERTICAL)
    panelBox = pbox
    bar.addView(pbox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    panelHolder = bar
    return bar
}
fun sideRail(): ScrollViewV {
    val sc = ScrollViewV(ctx())
    sc.setVerticalScrollBarEnabled(false)
    val col = LinearLayout(ctx())
    col.setOrientation(LinearLayout.VERTICAL)
    col.setGravity(Gravity.CENTER_HORIZONTAL)
    col.setPadding(dp(4), dp(6), dp(4), dp(6))
    col.setBackground(shape("#66070A0D", 22))
    sc.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    railBox = col
    railHolder = sc
    return sc
}
fun setToolsHidden(hide: Boolean): Unit {
    toolsHidden = hide
    val rail = railHolder
    if (rail != null) {
        rail!!.setVisibility(if (hide) {
            View.GONE
        } else {
            View.VISIBLE
        }
        )
    }
    val panel = panelHolder
    if (panel != null) {
        panel!!.setVisibility(if (hide) {
            View.GONE
        } else {
            View.VISIBLE
        }
        )
    }
    val rb = reopenBtn
    if (rb != null) {
        rb!!.setVisibility(if (hide) {
            View.VISIBLE
        } else {
            View.GONE
        }
        )
    }
    val ov = overlay
    if (ov != null) {
        ov!!.invalidate()
    }
    if (!hide) {
        rebuildChips()
        rebuildPanel()
    }
}
fun selectLayer(l: StickerLayer): Unit {
    selected = l
    rebuildChips()
    rebuildPanel()
    val ov = overlay
    if (ov != null) {
        ov!!.invalidate()
    }
    requestRender()
}
fun rebuildChips(): Unit {
    val col = railBox
    if (col == null) {
        return
    }
    col!!.removeAllViews()
    col!!.addView(railBtn("close", false, false, fun(){
        return cancelEditor()
    }
    ))
    col!!.addView(railBtn("check", false, true, fun(){
        return saveAndClose()
    }
    ))
    col!!.addView(railBtn("collapse", false, false, fun(){
        return setToolsHidden(true)
    }
    ))
    col!!.addView(railGap())
    val bg = backgroundLayer()
    if (bg != null) {
        val on = selected != null && selected!!.id == bg!!.id
        col!!.addView(railBtn("bg", on, false, fun(){
            return selectLayer(bg!!)
        }
        ))
    }
    var mediaIndex: Int = 0
    run {
        var i: Number = 0
        while(i < layers.length){
            val l = layers[i]
            if (l.kind == K_BG) {
                i++
                continue
            }
            val on = selected != null && selected!!.id == l.id
            if (l.kind == K_CAMERA) {
                col!!.addView(railBtn("camera", on, false, fun(){
                    return selectLayer(l)
                }
                ))
                i++
                continue
            }
            mediaIndex = mediaIndex + 1
            val kind = if (l.mediaType == K_VIDEO) {
                "video"
            } else {
                "image"
            }
            col!!.addView(railBtn(kind, on, false, fun(){
                return selectLayer(l)
            }
            , mediaIndex.toString()))
            i++
        }
    }
    col!!.addView(railGap())
    col!!.addView(railBtn("imagePlus", false, false, fun(){
        return requestPick(K_IMAGE, true, "")
    }
    ))
    col!!.addView(railBtn("videoPlus", false, false, fun(){
        return requestPick(K_VIDEO, true, "")
    }
    ))
}
fun railBtn(kind: String, on: Boolean, primary: Boolean, fn: () -> Unit, badge: String = ""): IconBtn {
    val b = IconBtn(kind, fn)
    b.active = on
    b.primary = primary
    b.badge = badge
    val lp = LinearLayout.LayoutParams(dp(46), dp(46))
    lp.setMargins(0, dp(3), 0, dp(3))
    b.setLayoutParams(lp)
    return b
}
fun headBtn(kind: String, danger: Boolean, fn: () -> Unit): IconBtn {
    val b = IconBtn(kind, fn)
    b.danger = danger
    val lp = LinearLayout.LayoutParams(dp(34), dp(34))
    lp.setMargins(dp(6), 0, 0, 0)
    lp.gravity = Gravity.CENTER_VERTICAL
    b.setLayoutParams(lp)
    return b
}
fun railGap(): View {
    val v = View(ctx())
    v.setBackgroundColor(color("#33FFFFFF"))
    val lp = LinearLayout.LayoutParams(dp(26), dp(1))
    lp.setMargins(0, dp(5), 0, dp(5))
    lp.gravity = Gravity.CENTER_HORIZONTAL
    v.setLayoutParams(lp)
    return v
}
fun rebuildPanel(): Unit {
    if (pickMode && pickLayer != selected) {
        cancelPick()
    }
    val box = panelBox
    if (box == null) {
        return
    }
    box!!.removeAllViews()
    val l = selected
    if (l == null) {
        box!!.addView(mkText("点击画面中的图层可选中编辑：拖动移动、双指缩放", 11, C_SUB))
        return
    }
    val head = LinearLayout(ctx())
    head.setOrientation(LinearLayout.HORIZONTAL)
    head.setGravity(Gravity.CENTER_VERTICAL)
    val titleLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
    titleLp.weight = 1.0.toFloat()
    head.addView(mkText(if (l!!.kind == K_CAMERA) {
        "摄像头层"
    } else {
        if (l!!.kind == K_BG) {
            "背景层"
        } else {
            "贴图层"
        }
    }
    , 12, C_TEXT), titleLp)
    if (l!!.kind != K_CAMERA) {
        head.addView(headBtn("swap", false, fun(){
            return requestPick(l!!.mediaType, false, l!!.id)
        }
        ))
    }
    if (l!!.kind == K_MEDIA) {
        head.addView(headBtn("front", false, fun(){
            l!!.z = nextZ()
            rebuildChips()
        }
        ))
        head.addView(headBtn("trash", true, fun(){
            return removeLayer(l!!)
        }
        ))
    }
    box!!.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    if (l!!.kind == K_CAMERA) {
        val engines = LinearLayout(ctx())
        engines.setOrientation(LinearLayout.HORIZONTAL)
        engines.setPadding(0, dp(6), 0, 0)
        engines.addView(pill(if (camFacing == "front") {
            "前置"
        } else {
            "后置"
        }
        , "#2A2D33", fun(){
            camFacing = if (camFacing == "front") {
                "back"
            } else {
                "front"
            }
            restartCamera()
            rebuildPanel()
        }
        ), barLp(0))
        box!!.addView(engines, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box!!.addView(sliderRow("变焦", (camZoom - 1.0) / 9.0, fun(v: Number){
            camZoom = 1.0 + v * 9.0
            applyZoom()
        }
        ))
        return
    }
    if (l!!.kind == K_BG) {
        val typeRow = LinearLayout(ctx())
        typeRow.setOrientation(LinearLayout.HORIZONTAL)
        typeRow.setPadding(0, dp(6), 0, 0)
        typeRow.addView(pill("选择图片", if (l!!.mediaType == K_IMAGE) {
            "#2E7D57"
        } else {
            "#2A2D33"
        }
        , fun(){
            return requestPick(K_IMAGE, false, l!!.id)
        }
        ), barLp(0))
        typeRow.addView(pill("选择视频", if (l!!.mediaType == K_VIDEO) {
            "#2E7D57"
        } else {
            "#2A2D33"
        }
        , fun(){
            return requestPick(K_VIDEO, false, l!!.id)
        }
        ), barLp(0))
        box!!.addView(typeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (l!!.mediaType == K_VIDEO) {
            val sw = Switch(ctx())
            sw.setText("抠绿幕")
            sw.setTextSize(12.0.toFloat())
            sw.setTextColor(color(C_TEXT))
            sw.setChecked(l!!.chromaOn)
            sw.setOnCheckedChangeListener(SwitchCb(fun(on: Boolean){
                l!!.chromaOn = on
                rebuildPanel()
                requestRender()
            }
            ))
            box!!.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box!!.addView(chromaColorRow(l!!))
            if (l!!.chromaOn) {
                box!!.addView(sliderRow("容差", l!!.similarity, fun(v: Number){
                    l!!.similarity = v
                    requestRender()
                }
                ))
                box!!.addView(sliderRow("平滑", l!!.smoothness, fun(v: Number){
                    l!!.smoothness = v
                    requestRender()
                }
                ))
                box!!.addView(sliderRow("溢色", l!!.spill, fun(v: Number){
                    l!!.spill = v
                    requestRender()
                }
                ))
            }
        }
        return
    }
    if (l!!.mediaType == K_VIDEO) {
        val sw = Switch(ctx())
        sw.setText("抠绿幕")
        sw.setTextSize(12.0.toFloat())
        sw.setTextColor(color(C_TEXT))
        sw.setChecked(l!!.chromaOn)
        sw.setOnCheckedChangeListener(SwitchCb(fun(on: Boolean){
            l!!.chromaOn = on
            rebuildPanel()
            requestRender()
        }
        ))
        box!!.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box!!.addView(chromaColorRow(l!!))
        if (l!!.chromaOn) {
            box!!.addView(sliderRow("容差", l!!.similarity, fun(v: Number){
                l!!.similarity = v
                requestRender()
            }
            ))
            box!!.addView(sliderRow("平滑", l!!.smoothness, fun(v: Number){
                l!!.smoothness = v
                requestRender()
            }
            ))
            box!!.addView(sliderRow("溢色", l!!.spill, fun(v: Number){
                l!!.spill = v
                requestRender()
            }
            ))
        }
    }
}
open class SwitchCb : OnCheckedChangeListener {
    open lateinit var fn: ((on: Boolean) -> Unit)
    constructor(f: (on: Boolean) -> Unit){
        this.fn = f
    }
    override fun onCheckedChanged(btn: CompoundButton, checked: Boolean): Unit {
        this.fn(checked)
    }
}
open class SeekCb : SeekListener {
    open lateinit var label: TextView
    open lateinit var prefix: String
    open lateinit var fn: ((v: Number) -> Unit)
    constructor(lb: TextView, prefix: String, f: (v: Number) -> Unit){
        this.label = lb
        this.prefix = prefix
        this.fn = f
    }
    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean): Unit {
        val v = p.toDouble() / 100.0
        this.label.setText(this.prefix + " " + p.toString())
        this.fn(v)
    }
    override fun onStartTrackingTouch(sb: SeekBar?): Unit {}
    override fun onStopTrackingTouch(sb: SeekBar?): Unit {}
}
fun chromaColorRow(l: StickerLayer): LinearLayout {
    val row = LinearLayout(ctx())
    row.setOrientation(LinearLayout.HORIZONTAL)
    row.setPadding(0, dp(4), 0, 0)
    val names = _uA(
        "绿幕",
        "蓝幕",
        "深绿"
    ) as UTSArray<String>
    val codes = _uA(
        "#00FF00",
        "#0000FF",
        "#008000"
    ) as UTSArray<String>
    run {
        var i: Number = 0
        while(i < codes.length){
            val code = codes[i]
            val name = names[i]
            val on = l.keyColor.toUpperCase() == code
            row.addView(pill(name, if (on) {
                "#2E7D57"
            } else {
                "#2A2D33"
            }
            , fun(){
                l.keyColor = code
                cancelPick()
                rebuildPanel()
                requestRender()
            }
            ), barLp(0))
            i++
        }
    }
    val picking = pickMode && pickLayer == l
    row.addView(pill(if (picking) {
        "取色中"
    } else {
        "吸管"
    }
    , if (picking) {
        C_ACCENT
    } else {
        "#2A2D33"
    }
    , fun(){
        if (picking) {
            cancelPick()
            showHint("已取消取色")
            rebuildPanel()
            requestRender()
            return
        }
        pickMode = true
        pickLayer = l
        pickPending = false
        pickX = 0.5
        pickY = 0.5
        showHint("点击画面吸取要抠除的颜色")
        val ov = overlay
        if (ov != null) {
            ov!!.invalidate()
        }
        rebuildPanel()
        requestRender()
    }
    ), barLp(0))
    row.addView(mkText(l.keyColor, 11, C_SUB), barLp(0))
    return row
}
fun sliderRow(name: String, value: Number, fn: (v: Number) -> Unit): LinearLayout {
    val row = LinearLayout(ctx())
    row.setOrientation(LinearLayout.HORIZONTAL)
    row.setGravity(Gravity.CENTER_VERTICAL)
    row.setPadding(0, dp(4), 0, 0)
    val lb = mkText(name + " " + (clamp(value, 0.0, 1.0) * 100.0).toInt().toString(), 11, C_SUB)
    lb.setWidth(dp(74))
    row.addView(lb, barLp(0))
    val sb = SeekBar(ctx())
    sb.setMax(100)
    sb.setProgress((clamp(value, 0.0, 1.0) * 100.0).toInt())
    sb.setOnSeekBarChangeListener(SeekCb(lb, name, fn))
    val seekLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
    seekLp.weight = 1.0.toFloat()
    row.addView(sb, seekLp)
    return row
}
fun requestPick(kind: String, isAdd: Boolean, layerId: String): Unit {
    val cb = eventCb
    if (cb == null) {
        showHint("当前环境无法选择素材")
        return
    }
    val target = if (isAdd) {
        ""
    } else {
        layerId
    }
    cb("{\"type\":\"pick\",\"kind\":" + jstr(kind) + ",\"target\":" + jstr(target) + ",\"layerId\":" + jstr(layerId) + "}")
}
fun removeLayer(l: StickerLayer): Unit {
    if (l.kind == K_CAMERA) {
        showHint("摄像头层不可删除")
        return
    }
    releaseMedia(l)
    releaseTexture(l)
    val next: UTSArray<StickerLayer> = _uA()
    run {
        var i: Number = 0
        while(i < layers.length){
            if (layers[i].id != l.id) {
                next.push(layers[i])
            }
            i++
        }
    }
    layers = next
    selected = null
    rebuildChips()
    rebuildPanel()
    requestRender()
    val ov = overlay
    if (ov != null) {
        ov!!.invalidate()
    }
}
fun mediaTypeOf(path: String): String {
    val p = path.toLowerCase()
    if (p.indexOf(".mp4") >= 0 || p.indexOf(".mov") >= 0 || p.indexOf(".m4v") >= 0 || p.indexOf(".webm") >= 0 || p.indexOf(".mkv") >= 0) {
        return K_VIDEO
    }
    return K_IMAGE
}
fun localId(): String {
    return "local_" + Date.now().toString(10) + "_" + Math.floor(Math.random() * 1000).toInt().toString()
}
fun openStickerEditor(payloadJson: String, onEvent: ((ev: String) -> Unit)? = null): UTSPromise<String> {
    return UTSPromise<String>(fun(resolve, reject){
        console.log("LG-Sticker: openStickerEditor 进入，payload 长度 " + payloadJson.length.toString(10))
        if (editing) {
            reject(UTSError("贴图编辑器已在打开状态"))
            return
        }
        resolveRef = resolve
        rejectRef = reject
        eventCb = onEvent
        postMain(fun(){
            try {
                buildAndShow(payloadJson)
            }
             catch (e: Throwable) {
                editing = false
                dlg = null
                val rj = rejectRef
                rejectRef = null
                resolveRef = null
                if (rj != null) {
                    try {
                        rj!!(e as UTSError)
                    }
                     catch (err: Throwable) {}
                }
            }
        }
        )
    }
    )
}
fun addStickerMedia(kind: String, path: String): Unit {
    postMain(fun(){
        if (path == "") {
            return
        }
        val t = mediaTypeOf(path)
        val mt = if (kind == K_VIDEO) {
            K_VIDEO
        } else {
            t
        }
        val l = StickerLayer(localId(), K_MEDIA, mt, path)
        l.aspect = mediaAspect(path, mt == K_VIDEO)
        l.z = nextZ()
        l.w = 0.32
        l.h = 0.32
        l.x = 0.34
        l.y = 0.3
        applyAspectFit(l, false)
        layers.push(l)
        selected = l
        rebuildChips()
        rebuildPanel()
        requestRender()
    }
    )
}
fun replaceStickerLayer(layerId: String, path: String): Unit {
    postMain(fun(){
        if (path == "") {
            return
        }
        val l = layerById(layerId)
        if (l == null) {
            addStickerMedia(mediaTypeOf(path), path)
            return
        }
        releaseMedia(l!!)
        releaseTexture(l!!)
        l!!.path = path
        if (l!!.kind != K_CAMERA) {
            l!!.mediaType = mediaTypeOf(path)
            val old = l!!.st
            if (old != null) {
                try {
                    old!!.release()
                }
                 catch (e: Throwable) {}
                l!!.st = null
            }
        }
        l!!.aspect = if (l!!.kind == K_CAMERA) {
            (16.0 as Number) / 9.0
        } else {
            mediaAspect(path, l!!.mediaType == K_VIDEO)
        }
        applyAspectFit(l!!, l!!.kind == K_BG)
        requestRender()
        rebuildPanel()
    }
    )
}
fun isStickerEditorOpen(): Boolean {
    return editing
}
var scDlg: Dialog? = null
var scGl: GLSurfaceView? = null
var scTex: Int = 0
var scSt: SurfaceTexture? = null
var scSurface: Surface? = null
var scDevice: CameraDevice? = null
var scSession: CameraCaptureSession? = null
var scHandler: Handler? = null
var scGen: Int = 0
var scStarting = false
var scFacing = "front"
var scZoom: Number = 1.0
var scId = ""
var scGranted = false
var scX: Int = 0
var scY: Int = 0
var scW: Int = 1
var scH: Int = 1
open class StageCamFrameCb : SurfaceFrameListener {
    override fun onFrameAvailable(st: SurfaceTexture?): Unit {
        val g = scGl
        if (g != null) {
            g!!.requestRender()
        }
    }
}
open class StageCamRenderer : Renderer {
    open var prog: Int = 0
    open var quad: FloatBuffer? = null
    override fun onSurfaceCreated(gl: GL10, cfg: EGLConfig): Unit {
        this.prog = buildProgram(VS_COMMON, FS_CAMERA)
        this.quad = makeQuad()
        ensureStageCamTexture()
    }
    override fun onSurfaceChanged(gl: GL10, w: Int, h: Int): Unit {
        GLES20.glViewport(0, 0, w, h)
    }
    override fun onDrawFrame(gl: GL10): Unit {
        GLES20.glClearColor(0.0.toFloat(), 0.0.toFloat(), 0.0.toFloat(), 1.0.toFloat())
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val prog = this.prog
        val q = this.quad
        val st = scSt
        if (prog == 0 || q == null || st == null) {
            return
        }
        st!!.updateTexImage()
        val tm = FloatArray(16)
        st!!.getTransformMatrix(tm)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, scTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uChroma"), 0.0.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAlpha"), 1.0.toFloat())
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uRect"), 0.0.toFloat(), 0.0.toFloat(), 1.0.toFloat(), 1.0.toFloat())
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTexMatrix"), 1, false, tm, 0)
        bindQuad(prog, q!!)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
fun ensureScHandler(): Handler {
    val h = scHandler
    if (h != null) {
        return h!!
    }
    val t = HandlerThread("lg-sticker-stage-cam")
    t.start()
    t
    val nh = Handler(t.getLooper())
    scHandler = nh
    return nh
}
fun ensureStageCamTexture(): Unit {
    if (scTex != 0) {
        return
    }
    scTex = newExternalTexture()
    val st = SurfaceTexture(scTex)
    st.setDefaultBufferSize(1280, 720)
    scSt = st
    val h = ensureScHandler()
    st.setOnFrameAvailableListener(StageCamFrameCb(), h)
    postMain(fun(){
        if (scSurface == null && scSt != null) {
            scSurface = Surface(scSt!!)
        }
        openStageCam()
    }
    )
}
fun openStageCam(): Unit {
    val st = scSurface
    val act = activityRef
    if (st == null || act == null || scDevice != null || scStarting) {
        return
    }
    scStarting = true
    val h = ensureScHandler()
    try {
        val cm = act!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = cm.getCameraIdList()
        var picked = ""
        run {
            var i: Int = 0
            while(i < ids.size){
                val ch = cm.getCameraCharacteristics(ids[i])
                val rawFacing = ch.get(CameraCharacteristics.LENS_FACING)
                val facing = if (rawFacing == null) {
                    -1
                } else {
                    (rawFacing as Int)
                }
                val want = if (scFacing == "front") {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }
                if (facing == want) {
                    picked = ids[i]
                    break
                }
                i++
            }
        }
        if (picked == "" && ids.size > 0) {
            picked = ids[0]
        }
        if (picked == "") {
            scStarting = false
            return
        }
        scId = picked
        cm.openCamera(picked, StageCamStateCb(scGen), h)
    }
     catch (e: Throwable) {
        scStarting = false
    }
}
fun stopStageCam(): Unit {
    scGen++
    val s = scSession
    if (s != null) {
        try {
            s!!.close()
        }
         catch (e: Throwable) {}
        scSession = null
    }
    val d = scDevice
    if (d != null) {
        try {
            d!!.close()
        }
         catch (e: Throwable) {}
        scDevice = null
    }
    val sf = scSurface
    if (sf != null) {
        try {
            sf!!.release()
        }
         catch (e: Throwable) {}
        scSurface = null
    }
    scStarting = false
}
fun restartStageCam(): Unit {
    stopStageCam()
    postMain(fun(){
        val st = scSt
        if (scSurface == null && st != null) {
            scSurface = Surface(st!!)
        }
        openStageCam()
    }
    )
}
fun applyStageZoom(): Unit {
    val s = scSession
    val d = scDevice
    val sf = scSurface
    if (s == null || d == null || sf == null) {
        return
    }
    try {
        val req = d!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        req.addTarget(sf!!)
        val zr = zoomRegion(scId, scZoom)
        if (zr != null) {
            req.set(CaptureRequest.SCALER_CROP_REGION, zr)
        }
        s!!.setRepeatingRequest(req.build(), null, scHandler)
    }
     catch (e: Throwable) {
        restartStageCam()
    }
}
open class StageCamStateCb : CamStateListener {
    open var gen: Int = 0
    constructor(g: Int){
        this.gen = g
    }
    override fun onOpened(dev: CameraDevice): Unit {
        if (this.gen != scGen) {
            try {
                dev.close()
            }
             catch (e: Throwable) {}
            return
        }
        scDevice = dev
        val sf = scSurface
        if (sf == null) {
            scStarting = false
            return
        }
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            req.addTarget(sf!!)
            val targets = _uA(
                sf!!
            ) as UTSArray<Surface>
            dev.createCaptureSession(targets, StageCamSessionCb(req, this.gen), scHandler)
        }
         catch (e: Throwable) {
            scStarting = false
        }
    }
    override fun onDisconnected(dev: CameraDevice): Unit {
        if (this.gen == scGen) {
            stopStageCam()
        }
    }
    override fun onError(dev: CameraDevice, err: Int): Unit {
        if (this.gen == scGen) {
            stopStageCam()
        }
    }
}
open class StageCamSessionCb : CamSessionListener {
    open lateinit var req: CaptureRequestBuilder
    open var gen: Int = 0
    constructor(r: CaptureRequestBuilder, g: Int){
        this.req = r
        this.gen = g
    }
    override fun onConfigured(s: CameraCaptureSession): Unit {
        if (this.gen != scGen) {
            try {
                s.close()
            }
             catch (e: Throwable) {}
            return
        }
        scSession = s
        try {
            val r = this.req
            r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            r.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            val zr = zoomRegion(scId, scZoom)
            if (zr != null) {
                r.set(CaptureRequest.SCALER_CROP_REGION, zr)
            }
            s.setRepeatingRequest(r.build(), null, scHandler)
            scStarting = false
        }
         catch (e: Throwable) {
            scStarting = false
        }
    }
    override fun onConfigureFailed(s: CameraCaptureSession): Unit {
        scStarting = false
    }
}
fun applyStageCamRect(rect: UTSJSONObject?): Unit {
    val act = activityRef
    var sw: Int = 1080
    var sh: Int = 1920
    if (act != null) {
        val dm = act!!.getResources().getDisplayMetrics()
        sw = dm.widthPixels
        sh = dm.heightPixels
    }
    scX = (clamp(numOf(rect, "x", 0.0), 0.0, 1.0) * sw).toInt()
    scY = (clamp(numOf(rect, "y", 0.0), 0.0, 1.0) * sh).toInt()
    var w = (clamp(numOf(rect, "w", 0.4), 0.02, 1.0) * sw).toInt()
    var h = (clamp(numOf(rect, "h", 0.34), 0.02, 1.0) * sh).toInt()
    if (w < 1) {
        w = 1
    }
    if (h < 1) {
        h = 1
    }
    scW = w
    scH = h
}
fun updateStageCamWindow(): Unit {
    val d = scDlg
    if (d == null) {
        return
    }
    val win = d.getWindow()
    if (win == null) {
        return
    }
    win!!.setLayout(scW, scH)
    val lp = win!!.getAttributes()
    lp.gravity = Gravity.TOP or Gravity.LEFT
    lp.x = scX
    lp.y = scY
    win!!.setAttributes(lp)
}
fun releaseStageCamTexture(): Unit {
    val st = scSt
    scSt = null
    if (st != null) {
        try {
            st!!.release()
        }
         catch (e: Throwable) {}
    }
    val tex = scTex
    scTex = 0
    if (tex == 0) {
        return
    }
    val g = scGl
    if (g == null) {
        return
    }
    g!!.queueEvent(UiTask(fun(){
        val ids = IntArray(1)
        ids[0] = tex
        GLES20.glDeleteTextures(1, ids, 0)
    }
    ))
}
fun showStageCamera(json: String): Unit {
    postMain(fun(){
        val act = UTSAndroid.getUniActivity()
        if (act == null) {
            return
        }
        activityRef = act!!
        var rect: UTSJSONObject? = null
        var facing = "front"
        var zoom: Number = 1.0
        try {
            val root = JSON.parse(json) as UTSJSONObject
            rect = objOf(root, "rect")
            facing = strOf(root, "facing", "front")
            zoom = clamp(numOf(root, "zoom", 1.0), 1.0, 10.0)
        }
         catch (e: Throwable) {}
        if (scDlg == null) {
            val nd = Dialog(act!!)
            nd.requestWindowFeature(1)
            val g = GLSurfaceView(act!!)
            g.setEGLContextClientVersion(2)
            g.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            g.setRenderer(StageCamRenderer())
            g.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY)
            scGl = g
            val root2 = FrameLayout(act!!)
            root2.addView(g, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            nd.setContentView(root2)
            val win = nd.getWindow()
            if (win != null) {
                win!!.setBackgroundDrawable(shape("#00000000", 0))
                win!!.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                win!!.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                win!!.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            nd.setCancelable(false)
            scDlg = nd
            nd.show()
        }
        applyStageCamRect(rect)
        updateStageCamWindow()
        val facingChanged = scFacing != facing
        scFacing = facing
        scZoom = zoom
        if (scGranted) {
            if (facingChanged) {
                restartStageCam()
            } else {
                if (scSurface != null) {
                    openStageCam()
                }
                applyStageZoom()
            }
            return
        }
        val perms = _uA(
            "android.permission.CAMERA"
        ) as UTSArray<String>
        UTSAndroid.requestSystemPermission(act!!, perms, fun(all: Boolean, granted: UTSArray<String>){
            if (!all) {
                return
            }
            scGranted = true
            restartStageCam()
        }
        , fun(doNotAskAgain: Boolean, granted: UTSArray<String>){})
    }
    )
}
fun hideStageCamera(): Unit {
    postMain(fun(){
        stopStageCam()
        releaseStageCamTexture()
        val d = scDlg
        scDlg = null
        if (d != null) {
            try {
                d!!.dismiss()
            }
             catch (e: Throwable) {}
        }
        scGl = null
        scGranted = false
        scZoom = 1.0
        scFacing = "front"
        scId = ""
    }
    )
}
fun buildAndShow(payloadJson: String): Unit {
    val act = UTSAndroid.getUniActivity()
    if (act == null) {
        finishWith("{\"action\":\"cancel\"}")
        return
    }
    activityRef = act!!
    mainHandler = Handler(Looper.getMainLooper())
    ensureCamHandler()
    layers = parsePayload(payloadJson)
    selected = cameraLayer()
    if (cameraLayer() == null) {
        val cam = StickerLayer("camera", K_CAMERA, K_IMAGE, "")
        cam.x = 0.56
        cam.y = 0.62
        cam.w = 0.4
        cam.h = 0.34
        cam.aspect = (16.0 as Number) / 9.0
        cam.z = 1
        layers.push(cam)
        selected = cam
    }
    editing = true
    val d = Dialog(act!!)
    d.requestWindowFeature(1)
    val root = FrameLayout(act!!)
    root
    val g = GLSurfaceView(act!!)
    g.setEGLContextClientVersion(2)
    g.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
    val r = StickerRenderer()
    renderer = r
    g.setRenderer(r)
    g.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY)
    glView = g
    root.addView(g, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val ov = RectOverlay(act!!)
    ov.setOnTouchListener(GestureCb())
    overlay = ov
    root.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val rail = sideRail()
    val railLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    railLp.gravity = Gravity.TOP or Gravity.LEFT
    railLp.setMargins(dp(8), dp(40), 0, 0)
    root.addView(rail, railLp)
    val bottom = bottomBar()
    val bl = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    bl.gravity = Gravity.BOTTOM
    bl.setMargins(dp(8), 0, dp(8), dp(8))
    root.addView(bottom, bl)
    toolsHidden = false
    val rb = IconBtn("expand", fun(){
        return setToolsHidden(false)
    }
    )
    rb.setVisibility(View.GONE)
    reopenBtn = rb
    val rbl = FrameLayout.LayoutParams(dp(46), dp(46))
    rbl.gravity = Gravity.TOP or Gravity.LEFT
    rbl.setMargins(dp(8), dp(40), 0, 0)
    root.addView(rb, rbl)
    val hint = mkText("", 11, "#FFD166")
    hint.setPadding(dp(12), dp(6), dp(12), dp(6))
    hint.setVisibility(View.GONE)
    hintView = hint
    val hl = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    hl.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    hl.topMargin = dp(56)
    root.addView(hint, hl)
    d.setContentView(root)
    val win = d.getWindow()
    if (win != null) {
        win!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        win!!.setBackgroundDrawable(shape("#000000", 0))
        win!!.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    d.setOnDismissListener(DismissCb())
    dlg = d
    d.show()
    rebuildChips()
    rebuildPanel()
    val perms = _uA(
        "android.permission.CAMERA"
    ) as UTSArray<String>
    UTSAndroid.requestSystemPermission(act!!, perms, fun(all: Boolean, granted: UTSArray<String>){
        if (all) {
            restartCamera()
            return
        }
        showHint("未授予相机权限，摄像头层不会预览")
    }
    , fun(doNotAskAgain: Boolean, granted: UTSArray<String>){
        showHint("未授予相机权限，摄像头层不会预览")
    }
    )
}
open class DismissCb : DialogDismissListener {
    override fun onDismiss(d: DialogInterface): Unit {
        teardown()
        finishWith("{\"action\":\"cancel\"}")
    }
}
fun parsePayload(json: String): UTSArray<StickerLayer> {
    val out: UTSArray<StickerLayer> = _uA()
    try {
        val root = JSON.parse(json) as UTSJSONObject
        val scene = objOf(root, "scene")
        val bg = objOf(scene, "background")
        val bgLayer = StickerLayer("background", K_BG, strOf(bg, "type", K_IMAGE), strOf(bg, "path", ""))
        bgLayer.x = 0
        bgLayer.y = 0
        bgLayer.w = 1
        bgLayer.h = 1
        bgLayer.z = 0
        bgLayer.aspect = mediaAspect(bgLayer.path, bgLayer.mediaType == K_VIDEO)
        bgLayer.loop = boolOf(bg, "loop", true)
        bgLayer.muted = boolOf(bg, "muted", true)
        val bgChroma = objOf(bg, "chroma")
        bgLayer.chromaOn = boolOf(bgChroma, "enabled", false)
        bgLayer.keyColor = strOf(bgChroma, "keyColor", "#00FF00")
        bgLayer.similarity = numOf(bgChroma, "similarity", 0.28)
        bgLayer.smoothness = numOf(bgChroma, "smoothness", 0.12)
        bgLayer.spill = numOf(bgChroma, "spill", 0.15)
        out.push(bgLayer)
        val cam = objOf(scene, "camera")
        val camLayer = StickerLayer("camera", K_CAMERA, K_IMAGE, "")
        camLayer.x = numOf(objOf(cam, "rect"), "x", 0.56)
        camLayer.y = numOf(objOf(cam, "rect"), "y", 0.62)
        camLayer.w = numOf(objOf(cam, "rect"), "w", 0.4)
        camLayer.h = numOf(objOf(cam, "rect"), "h", 0.34)
        camLayer.z = 1
        camFacing = strOf(cam, "facing", "front")
        camZoom = numOf(cam, "zoom", 1.0)
        camLayer.aspect = (16.0 as Number) / 9.0
        out.push(camLayer)
        val arr = root.getArray<UTSJSONObject>("children")
        if (arr != null) {
            val list = arr!!
            run {
                var i: Number = 0
                while(i < list.length){
                    val c = list[i]
                    val l = StickerLayer(strOf(c, "id", localId()), K_MEDIA, strOf(c, "type", K_IMAGE), strOf(c, "mediaPath", ""))
                    l.aspect = mediaAspect(l.path, l.mediaType == K_VIDEO)
                    val rect = objOf(c, "rect")
                    l.x = numOf(rect, "x", 0.1)
                    l.y = numOf(rect, "y", 0.1)
                    l.w = numOf(rect, "w", 0.3)
                    l.h = numOf(rect, "h", 0.3)
                    l.z = numOf(c, "z", 1.0 + i)
                    l.loop = boolOf(c, "loop", true)
                    l.muted = boolOf(c, "muted", true)
                    val ch = objOf(c, "chroma")
                    l.chromaOn = boolOf(ch, "enabled", false)
                    l.keyColor = strOf(ch, "keyColor", "#00FF00")
                    l.similarity = numOf(ch, "similarity", 0.28)
                    l.smoothness = numOf(ch, "smoothness", 0.12)
                    l.spill = numOf(ch, "spill", 0.15)
                    out.push(l)
                    i++
                }
            }
        }
    }
     catch (e: Throwable) {
        console.log("LG-Sticker 载荷解析失败: " + e)
    }
    return out
}
fun saveAndClose(): Unit {
    val json = buildResultJson()
    val r = resolveRef
    resolveRef = null
    rejectRef = null
    val d = dlg
    if (d != null) {
        try {
            d!!.dismiss()
        }
         catch (e: Throwable) {}
    }
    if (r != null) {
        try {
            r!!(json)
        }
         catch (e: Throwable) {}
    }
}
fun cancelEditor(): Unit {
    val d = dlg
    if (d != null) {
        try {
            d!!.dismiss()
        }
         catch (e: Throwable) {}
        return
    }
    finishWith("{\"action\":\"cancel\"}")
}
fun finishWith(json: String): Unit {
    val r = resolveRef
    resolveRef = null
    rejectRef = null
    if (r != null) {
        try {
            r!!(json)
        }
         catch (e: Throwable) {}
    }
}
fun buildResultJson(): String {
    var bg = "{}"
    run {
        var i: Number = 0
        while(i < layers.length){
            val l = layers[i]
            if (l.kind != K_BG) {
                i++
                continue
            }
            bg = "{\"type\":" + jstr(l.mediaType) + ",\"path\":" + jstr(l.path) + ",\"loop\":" + jbool(l.loop) + ",\"muted\":" + jbool(l.muted) + ",\"chroma\":" + chromaJson(l) + "}"
            i++
        }
    }
    var cam = "{}"
    run {
        var i: Number = 0
        while(i < layers.length){
            val l = layers[i]
            if (l.kind != K_CAMERA) {
                i++
                continue
            }
            cam = "{\"rect\":" + rectJson(l) + ",\"facing\":" + jstr(camFacing) + ",\"zoom\":" + jnum(camZoom) + "}"
            i++
        }
    }
    val kids: UTSArray<String> = _uA()
    val rest: UTSArray<StickerLayer> = _uA()
    run {
        var i: Number = 0
        while(i < layers.length){
            if (layers[i].kind == K_MEDIA) {
                rest.push(layers[i])
            }
            i++
        }
    }
    rest.sort(fun(a: StickerLayer, b: StickerLayer): Number {
        return a.z - b.z
    }
    )
    run {
        var i: Number = 0
        while(i < rest.length){
            val l = rest[i]
            kids.push("{\"id\":" + jstr(l.id) + ",\"type\":" + jstr(l.mediaType) + ",\"mediaPath\":" + jstr(l.path) + ",\"rect\":" + rectJson(l) + ",\"z\":" + jnum(l.z) + ",\"loop\":" + jbool(l.loop) + ",\"muted\":" + jbool(l.muted) + ",\"chroma\":" + chromaJson(l) + "}")
            i++
        }
    }
    return "{\"action\":\"save\",\"background\":" + bg + ",\"camera\":" + cam + ",\"children\":[" + kids.join(",") + "]}"
}
fun rectJson(l: StickerLayer): String {
    return "{\"x\":" + jnum(l.x) + ",\"y\":" + jnum(l.y) + ",\"w\":" + jnum(l.w) + ",\"h\":" + jnum(l.h) + "}"
}
fun chromaJson(l: StickerLayer): String {
    return "{\"enabled\":" + jbool(l.chromaOn) + ",\"keyColor\":" + jstr(l.keyColor) + ",\"similarity\":" + jnum(l.similarity) + ",\"smoothness\":" + jnum(l.smoothness) + ",\"spill\":" + jnum(l.spill) + "}"
}
fun teardown(): Unit {
    cancelPick()
    eventCb = null
    viewW = 0
    viewH = 0
    editing = false
    stopCamera()
    run {
        var i: Number = 0
        while(i < layers.length){
            val l = layers[i]
            releaseMedia(l)
            releaseTexture(l)
            val st = l.st
            if (st != null) {
                try {
                    st!!.release()
                }
                 catch (e: Throwable) {}
                l.st = null
            }
            i++
        }
    }
    layers = _uA()
    selected = null
    overlay = null
    hintView = null
    railBox = null
    panelBox = null
    railHolder = null
    panelHolder = null
    reopenBtn = null
    toolsHidden = false
    glView = null
    renderer = null
    null
    dlg = null
    if (resolveRef != null) {
        val r = resolveRef
        resolveRef = null
        try {
            r!!("{\"action\":\"cancel\"}")
        }
         catch (e: Throwable) {}
    }
}
suspend fun openStickerEditorByJs(payloadJson: String, onEvent: UTSCallback? = null): Deferred<String> {
    return toDeferred(openStickerEditor(payloadJson, if (onEvent == null) {
        null
    } else {
        if (onEvent.fnJS != null) {
            onEvent.fnJS
        } else {
            onEvent.fnJS = fun(ev: String){
                onEvent(ev)
            }
            onEvent.fnJS
        }
         as (ev: String) -> Unit
    }
    ))
}
fun addStickerMediaByJs(kind: String, path: String): Unit {
    return addStickerMedia(kind, path)
}
fun replaceStickerLayerByJs(layerId: String, path: String): Unit {
    return replaceStickerLayer(layerId, path)
}
fun isStickerEditorOpenByJs(): Boolean {
    return isStickerEditorOpen()
}
fun showStageCameraByJs(json: String): Unit {
    return showStageCamera(json)
}
fun hideStageCameraByJs(): Unit {
    return hideStageCamera()
}
