@file:Suppress("UNCHECKED_CAST", "USELESS_CAST", "INAPPLICABLE_JVM_NAME", "UNUSED_ANONYMOUS_PARAMETER", "NAME_SHADOWING", "UNNECESSARY_NOT_NULL_ASSERTION")
package uts.sdk.modules.LGFloat
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils.TruncateAt
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.OnClickListener
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ImageView.ScaleType
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import io.dcloud.uniapp.*
import io.dcloud.uniapp.extapi.*
import io.dcloud.uts.*
import io.dcloud.uts.Map
import io.dcloud.uts.Set
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import io.dcloud.uts.UTSAndroid
import io.dcloud.uts.UTSJSONObject
var wm: WindowManager? = null
var floatView: View? = null
var floatParams: WindowManager.LayoutParams? = null
var showing = false
var lastErrorMsg = ""
var attachedRoot: View? = null
var attachedWm: WindowManager? = null
var winGen: Int = 0
var actionHandler: ((action: String) -> Unit)? = null
var downX: Int = 0
var downY: Int = 0
var startX: Int = 0
var startY: Int = 0
var nameView: TextView? = null
var dotView: View? = null
var statusTextView: TextView? = null
var timeView: TextView? = null
var op1Text: TextView? = null
var op2Text: TextView? = null
var countView: TextView? = null
var scriptView: TextView? = null
var rowView: View? = null
var scriptTagView: TextView? = null
var fullCard: View? = null
var miniBar: View? = null
var miniNameView: TextView? = null
var miniDotView: View? = null
var miniStatusView: TextView? = null
var miniTimeView: TextView? = null
var miniCountView: TextView? = null
var lastStateCode = ""
var lastName = ""
var lastStatus = ""
var lastDuration = ""
var lastCount = ""
var lastScript = ""
var lastScriptMaxW: Int = 0
var styleLogged = false
var cachedLogoKey = ""
var cachedLogoBmp: Bitmap? = null
var lastStyleKey = ""
var op1Act = ""
var op2Act = ""
var themeText: Int = Color.argb(255, 242, 236, 224)
var themeRun: Int = Color.argb(255, 99, 219, 182)
var themePause: Int = Color.argb(255, 245, 184, 75)
var themeIdle: Int = Color.argb(255, 138, 129, 117)
var themePill: Int = Color.argb(255, 255, 91, 53)
var themeLogo: Int = Color.argb(255, 44, 111, 255)
var themeCount: Int = Color.argb(255, 255, 91, 53)
var themeFont: Number = 12
var C_BG: Int = Color.argb(255, 18, 16, 13)
var C_BONE: Int = Color.argb(255, 242, 236, 224)
var C_MINT: Int = Color.argb(255, 99, 219, 182)
var C_AMBER: Int = Color.argb(255, 245, 184, 75)
var C_SIGNAL: Int = Color.argb(255, 255, 91, 53)
var C_LOGOBLUE: Int = Color.argb(255, 44, 111, 255)
var C_IDLE: Int = Color.argb(255, 138, 129, 117)
var C_INK_DARK: Int = Color.argb(255, 26, 13, 7)
var C_INK_LIGHT: Int = Color.argb(255, 246, 239, 226)
val C_LOGO_SP: Number = 10
var themeSub: Int = Color.argb(150, 242, 236, 224)
var themeBorder: Int = Color.argb(40, 242, 236, 224)
var themeGhost: Int = Color.argb(26, 242, 236, 224)
var themeCountInk: Int = C_INK_DARK
var themeLogoInk: Int = C_INK_LIGHT
var themePillInk: Int = C_INK_DARK
fun runOnUi(fn: () -> Unit): Unit {
    val h = Handler(Looper.getMainLooper())
    h.post(fn)
}
fun isAttachedNow(): Boolean {
    val v = attachedRoot
    if (v == null) {
        return false
    }
    try {
        return v!!.isAttachedToWindow()
    }
     catch (e: Throwable) {
        return false
    }
}
fun detachLocked(): Unit {
    val v = attachedRoot
    val m = attachedWm
    attachedRoot = null
    attachedWm = null
    if (m != null && v != null) {
        try {
            m!!.removeView(v)
        }
         catch (e: Throwable) {
            console.log("LG-Float 移除悬浮窗失败: " + e)
        }
    }
}
fun dp(ctx: Context, v: Number): Int {
    val d = ctx.getResources().getDisplayMetrics().density
    return (v.toFloat() * d).toInt()
}
fun parseColor(hex: String?, fallback: Int): Int {
    if (hex == null || hex == "") {
        return fallback
    }
    try {
        return Color.parseColor(hex!!)
    }
     catch (e: Throwable) {
        return fallback
    }
}
fun withAlpha(base: Int, alphaPct: Number): Int {
    return Color.argb((alphaPct * 255 / 100).toInt(), Color.red(base), Color.green(base), Color.blue(base))
}
fun bgShape(ctx: Context, color: Int, radiusDp: Number, oval: Boolean): GradientDrawable {
    val gd = GradientDrawable()
    gd.setShape(if (oval) {
        GradientDrawable.OVAL
    } else {
        GradientDrawable.RECTANGLE
    }
    )
    gd.setColor(color)
    if (!oval) {
        gd.setCornerRadius(dp(ctx, radiusDp).toFloat())
    }
    return gd
}
fun bgCard(ctx: Context, color: Int, radiusDp: Number, strokeColor: Int, strokeDp: Number): GradientDrawable {
    val gd = bgShape(ctx, color, radiusDp, false)
    if (strokeDp > 0) {
        gd.setStroke(dp(ctx, strokeDp), strokeColor)
    }
    return gd
}
fun luminanceOf(c: Int): Number {
    return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
}
fun inkOn(bg: Int): Int {
    if (luminanceOf(bg) >= 150) {
        return C_INK_DARK
    }
    return C_INK_LIGHT
}
fun readableInk(bg: Int, text: Int): Int {
    val d = luminanceOf(bg) - luminanceOf(text)
    val diff = if (d < 0) {
        -d
    } else {
        d
    }
    return if (diff >= 90) {
        text
    } else {
        inkOn(bg)
    }
}
fun absDiff(a: Number, b: Number): Number {
    val d = a - b
    return if (d < 0) {
        -d
    } else {
        d
    }
}
fun unifiedInk(text: Int, bg1: Int, bg2: Int): Int {
    val t1 = absDiff(luminanceOf(bg1), luminanceOf(text))
    val t2 = absDiff(luminanceOf(bg2), luminanceOf(text))
    if (t1 >= 90 && t2 >= 90) {
        return text
    }
    val k1 = absDiff(luminanceOf(bg1), luminanceOf(C_INK_DARK))
    val k2 = absDiff(luminanceOf(bg2), luminanceOf(C_INK_DARK))
    val kMin = if (k1 < k2) {
        k1
    } else {
        k2
    }
    val l1 = absDiff(luminanceOf(bg1), luminanceOf(C_INK_LIGHT))
    val l2 = absDiff(luminanceOf(bg2), luminanceOf(C_INK_LIGHT))
    val lMin = if (l1 < l2) {
        l1
    } else {
        l2
    }
    return if (kMin >= lMin) {
        C_INK_DARK
    } else {
        C_INK_LIGHT
    }
}
fun parseOptions(json: String?): UTSJSONObject? {
    if (json == null || json == "") {
        return null
    }
    try {
        return JSON.parse(json!!) as UTSJSONObject
    }
     catch (e: Throwable) {
        console.log("LG-Float 参数 JSON 解析失败: " + json)
        return null
    }
}
fun jsonStr(o: UTSJSONObject?, key: String): String? {
    if (o == null) {
        return null
    }
    val v = o.getString(key)
    if (v == null || v == "") {
        return null
    }
    return v
}
fun jsonNum(o: UTSJSONObject?, key: String): Number? {
    if (o == null) {
        return null
    }
    return o.getNumber(key)
}
fun countTextOf(o: UTSJSONObject?): String {
    if (o == null) {
        return ""
    }
    val t = o.getString("countText")
    if (t != null && t != "") {
        return t!!
    }
    val n = o.getNumber("count")
    if (n != null) {
        return n!!.toInt().toString()
    }
    return ""
}
fun strOf(v: String?, fallback: String): String {
    if (v != null && v != "") {
        return v!!
    }
    return fallback
}
fun rawStr(o: UTSJSONObject?, key: String): String? {
    if (o == null) {
        return null
    }
    return o.getString(key)
}
fun scriptLine(text: String): String {
    val t = text.trim()
    return if (t != "") {
        t
    } else {
        "暂无话术"
    }
}
fun stateDot(code: String): Int {
    if (code == "running") {
        return themeRun
    }
    if (code == "paused") {
        return themePause
    }
    return themeIdle
}
fun localLogoPath(src: String): String {
    if (src == "") {
        return ""
    }
    if (src.charAt(0) == "/") {
        return src
    }
    if (src.indexOf("file://") == 0) {
        return src.substring(7)
    }
    return ""
}
fun decodeLogo(src: String): Bitmap? {
    val path = localLogoPath(src)
    if (path == "") {
        return null
    }
    try {
        return BitmapFactory.decodeFile(path)
    }
     catch (e: Throwable) {
        console.log("LG-Float 平台图标解码失败: " + src + " " + e)
        return null
    }
}
fun loadLogoBitmap(src: String, alt: String): Bitmap? {
    val key = src + "|" + alt
    if (key == cachedLogoKey) {
        return cachedLogoBmp
    }
    var bmp = decodeLogo(src)
    if (bmp == null && alt != "" && alt != src) {
        bmp = decodeLogo(alt)
    }
    cachedLogoKey = key
    cachedLogoBmp = bmp
    return bmp
}
fun bringAppToFront(): Unit {
    try {
        val ctx = UTSAndroid.getAppContext()!!
        val launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName())
        if (launch == null) {
            return
        }
        launch!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch!!.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.startActivity(launch!!)
    }
     catch (e: Throwable) {
        console.log("LG-Float 拉起 App 失败: " + e)
    }
}
open class FloatClickListener : OnClickListener {
    open var act: String = ""
    constructor(act: String){
        this.act = act
    }
    override fun onClick(v: View?): Unit {
        if (this.act == "open") {
            bringAppToFront()
        }
        val h = actionHandler
        if (h != null) {
            try {
                h(this.act)
            }
             catch (e: Throwable) {
                console.log("LG-Float 按钮回调失败: " + e)
            }
        }
    }
}
open class FloatFoldListener : OnClickListener {
    open var fold: Boolean = true
    open var full: View? = null
    open var mini: View? = null
    constructor(fold: Boolean, full: View, mini: View){
        this.fold = fold
        this.full = full
        this.mini = mini
    }
    override fun onClick(v: View?): Unit {
        val f = this.full
        val m = this.mini
        if (f == null || m == null) {
            return
        }
        if (this.fold) {
            f.setVisibility(View.GONE)
            m.setVisibility(View.VISIBLE)
        } else {
            m.setVisibility(View.GONE)
            f.setVisibility(View.VISIBLE)
        }
    }
}
fun applyFloatPos(nx: Int, ny: Int): Unit {
    val p = floatParams
    val w = wm
    val v = floatView
    if (p == null || w == null || v == null) {
        return
    }
    if (nx == p.x && ny == p.y) {
        return
    }
    p.x = nx
    p.y = ny
    try {
        w.updateViewLayout(v, p)
    }
     catch (e: Throwable) {
        console.log("LG-Float 拖动失败: " + e)
    }
}
open class FloatTouchListener : OnTouchListener {
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (event == null || floatParams == null || wm == null || floatView == null) {
            return false
        }
        val act = event.getAction()
        if (act == MotionEvent.ACTION_DOWN) {
            downX = event.getRawX().toInt()
            downY = event.getRawY().toInt()
            startX = floatParams!!.x
            startY = floatParams!!.y
            return true
        } else if (act == MotionEvent.ACTION_MOVE) {
            applyFloatPos(startX + (event.getRawX().toInt() - downX), startY + (event.getRawY().toInt() - downY))
            return true
        }
        return true
    }
}
open class FloatDragTapListener : OnTouchListener {
    open var slop: Int = 6
    open var moved: Boolean = false
    constructor(slopPx: Int){
        this.slop = slopPx
    }
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (event == null || floatParams == null || wm == null || floatView == null) {
            return false
        }
        val act = event.getAction()
        if (act == MotionEvent.ACTION_DOWN) {
            downX = event.getRawX().toInt()
            downY = event.getRawY().toInt()
            startX = floatParams!!.x
            startY = floatParams!!.y
            this.moved = false
            return true
        }
        if (act == MotionEvent.ACTION_MOVE) {
            val dx = event.getRawX().toInt() - downX
            val dy = event.getRawY().toInt() - downY
            val adx = if (dx < 0) {
                -dx
            } else {
                dx
            }
            val ady = if (dy < 0) {
                -dy
            } else {
                dy
            }
            if (!this.moved && (adx > this.slop || ady > this.slop)) {
                this.moved = true
            }
            if (this.moved) {
                applyFloatPos(startX + dx, startY + dy)
            }
            return true
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            val dragged = this.moved
            this.moved = false
            if (act == MotionEvent.ACTION_UP && !dragged && v != null) {
                v.performClick()
            }
            return true
        }
        return true
    }
}
fun buildView(ctx: Context, o: UTSJSONObject?): View {
    val bgHex = jsonStr(o, "bgColor")
    val bgAlphaV = jsonNum(o, "bgAlpha")
    val textHex = jsonStr(o, "textColor")
    val statusHex = jsonStr(o, "statusColor")
    val pauseHex = jsonStr(o, "pauseColor")
    val btnHex = jsonStr(o, "btnBgColor")
    val logoHex = jsonStr(o, "logoBgColor")
    val countHex = jsonStr(o, "countBgColor")
    val fontSizeV = jsonNum(o, "fontSize")
    val radiusV = jsonNum(o, "cornerRadius")
    if (!styleLogged) {
        styleLogged = true
        if (bgHex == null) {
            console.log("LG-Float 未收到悬浮窗样式（bgColor 为空），按内置默认渲染")
        } else {
            console.log("LG-Float 样式生效: bg=" + bgHex!!)
        }
    }
    val bgAlpha = if (bgAlphaV != null) {
        bgAlphaV!!
    } else {
        93
    }
    val fontSize = if (fontSizeV != null) {
        fontSizeV!!
    } else {
        12
    }
    val radius = if (radiusV != null) {
        radiusV!!
    } else {
        17
    }
    themeText = parseColor(textHex, C_BONE)
    themeRun = parseColor(statusHex, C_MINT)
    themePause = parseColor(pauseHex, C_AMBER)
    themeIdle = C_IDLE
    themePill = parseColor(btnHex, C_SIGNAL)
    themeLogo = parseColor(logoHex, C_LOGOBLUE)
    themeCount = parseColor(countHex, C_SIGNAL)
    themeSub = withAlpha(themeText, 60)
    themeBorder = withAlpha(themeText, 18)
    themeGhost = withAlpha(themeText, 10)
    themeCountInk = unifiedInk(themeText, themeCount, themePill)
    themeLogoInk = readableInk(themeLogo, themeText)
    themePillInk = themeCountInk
    themeFont = fontSize
    val cardColor = withAlpha(parseColor(bgHex, C_BG), bgAlpha)
    val roomName = strOf(jsonStr(o, "roomName"), strOf(jsonStr(o, "title"), "直播控制"))
    val logoText = strOf(jsonStr(o, "logoText"), roomName.substring(0, 1))
    val logoImage = strOf(jsonStr(o, "logoImage"), "")
    val logoImageAlt = strOf(jsonStr(o, "logoImageAlt"), "")
    val stateCode = strOf(jsonStr(o, "stateCode"), "idle")
    val statusText = strOf(jsonStr(o, "statusText"), strOf(jsonStr(o, "status"), "未开播"))
    val durationText = strOf(jsonStr(o, "durationText"), "00:00:00")
    val countType = strOf(jsonStr(o, "countType"), "语音回复")
    val countText = countTextOf(o)
    val countVal = strOf(countText, "0")
    val scriptText = strOf(rawStr(o, "scriptText"), "")
    val scriptTextLine = scriptLine(scriptText)
    val actionsText = strOf(jsonStr(o, "actionsText"), "pause,stop")
    val actions = actionsText.split(",")
    op1Act = ""
    op2Act = ""
    run {
        var i: Number = 0
        while(i < actions.length){
            val a = actions[i]
            if (a == "pause" || a == "resume" || a == "toggle") {
                if (op1Act == "") {
                    op1Act = a
                }
            } else if (a == "stop") {
                if (op2Act == "") {
                    op2Act = a
                }
            }
            i++
        }
    }
    val idle = stateCode == "idle"
    val op1Label = if (idle) {
        "开播"
    } else {
        if (stateCode == "paused") {
            "继续"
        } else {
            "暂停"
        }
    }
    val op2Label = if (idle) {
        "关闭"
    } else {
        "关播"
    }
    val subSize = if (fontSize > 11) {
        fontSize - 3
    } else {
        8
    }
    val tinySize = if (fontSize > 10) {
        fontSize - 4
    } else {
        8
    }
    val root = RelativeLayout(ctx)
    root.setKeepScreenOn(true)
    root.setOnTouchListener(FloatTouchListener())
    val card = LinearLayout(ctx)
    card.setOrientation(LinearLayout.VERTICAL)
    card.setBackground(bgCard(ctx, cardColor, radius, themeBorder, 1))
    card.setPadding(dp(ctx, 9), dp(ctx, 8), dp(ctx, 8), dp(ctx, 7))
    root.addView(card, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val row = LinearLayout(ctx)
    row.setOrientation(LinearLayout.HORIZONTAL)
    row.setGravity(Gravity.CENTER_VERTICAL)
    card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val logoBmp = loadLogoBitmap(logoImage, logoImageAlt)
    if (logoBmp != null) {
        val logoImg = ImageView(ctx)
        logoImg.setImageBitmap(logoBmp)
        logoImg.setScaleType(ScaleType.CENTER_CROP)
        logoImg.setBackground(bgShape(ctx, themeLogo, 18, true))
        logoImg.setClipToOutline(true)
        logoImg.setOnClickListener(FloatClickListener("open"))
        row.addView(logoImg, LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)))
    } else {
        val logoV = TextView(ctx)
        logoV.setText(logoText)
        logoV.setTextColor(themeLogoInk)
        logoV.setTextSize((fontSize + 2).toFloat())
        logoV.setGravity(Gravity.CENTER)
        logoV.setIncludeFontPadding(false)
        logoV.setBackground(bgShape(ctx, themeLogo, 18, true))
        logoV.setOnClickListener(FloatClickListener("open"))
        row.addView(logoV, LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)))
    }
    val nameCol = LinearLayout(ctx)
    nameCol.setOrientation(LinearLayout.VERTICAL)
    nameCol.setGravity(Gravity.CENTER_VERTICAL)
    val ncLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    ncLp.setMargins(dp(ctx, 7), 0, 0, 0)
    row.addView(nameCol, ncLp)
    val nameV = TextView(ctx)
    nameV.setText(roomName)
    nameV.setTextColor(themeText)
    nameV.setTextSize(fontSize.toFloat())
    nameV.setSingleLine(true)
    nameV.setMaxLines(1)
    nameV.setEllipsize(TruncateAt.END)
    nameV.setMaxWidth(dp(ctx, 116))
    nameV.setIncludeFontPadding(false)
    nameCol.addView(nameV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val statusRow = LinearLayout(ctx)
    statusRow.setOrientation(LinearLayout.HORIZONTAL)
    statusRow.setGravity(Gravity.CENTER_VERTICAL)
    val srLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    srLp.setMargins(0, dp(ctx, 2), 0, 0)
    nameCol.addView(statusRow, srLp)
    val dotV = View(ctx)
    dotV.setBackground(bgShape(ctx, stateDot(stateCode), 3, true))
    statusRow.addView(dotV, LinearLayout.LayoutParams(dp(ctx, 5), dp(ctx, 5)))
    val statusV = TextView(ctx)
    statusV.setText(statusText)
    statusV.setTextColor(themeSub)
    statusV.setTextSize(subSize.toFloat())
    statusV.setIncludeFontPadding(false)
    val stLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    stLp.setMargins(dp(ctx, 4), 0, 0, 0)
    statusRow.addView(statusV, stLp)
    val timeV = TextView(ctx)
    timeV.setText(durationText)
    timeV.setTextColor(themeSub)
    timeV.setTextSize(subSize.toFloat())
    timeV.setTypeface(Typeface.MONOSPACE)
    timeV.setIncludeFontPadding(false)
    val tmNote = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    tmNote.setMargins(dp(ctx, 4), 0, 0, 0)
    statusRow.addView(timeV, tmNote)
    val countBox = LinearLayout(ctx)
    countBox.setOrientation(LinearLayout.VERTICAL)
    countBox.setGravity(Gravity.CENTER)
    countBox.setBackground(bgShape(ctx, themeCount, 9, false))
    countBox.setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0)
    val cbLp = LinearLayout.LayoutParams(dp(ctx, 46), dp(ctx, 38))
    cbLp.setMargins(dp(ctx, 7), 0, 0, 0)
    row.addView(countBox, cbLp)
    val countTypeView = TextView(ctx)
    countTypeView.setText(countType)
    countTypeView.setTextColor(withAlpha(themeCountInk, 82))
    countTypeView.setTextSize(tinySize.toFloat())
    countTypeView.setSingleLine(true)
    countTypeView.setIncludeFontPadding(false)
    countBox.addView(countTypeView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val countV = TextView(ctx)
    countV.setText(countVal)
    countV.setTextColor(themeCountInk)
    countV.setTextSize((fontSize + 2).toFloat())
    countV.setIncludeFontPadding(false)
    countV.setTypeface(Typeface.MONOSPACE)
    countBox.addView(countV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    if (op1Act != "" || op2Act != "") {
        val opsCol = LinearLayout(ctx)
        opsCol.setOrientation(LinearLayout.VERTICAL)
        val opsLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        opsLp.setMargins(dp(ctx, 7), 0, 0, 0)
        row.addView(opsCol, opsLp)
        if (op1Act != "") {
            op1Text = addOpPill(ctx, opsCol, op1Label, op1Act, 0, true)
        }
        if (op2Act != "") {
            op2Text = addOpPill(ctx, opsCol, op2Label, op2Act, dp(ctx, 2), false)
        }
    }
    val foldV = TextView(ctx)
    foldV.setText("‹")
    foldV.setTextColor(themeText)
    foldV.setTextSize(tinySize.toFloat())
    foldV.setGravity(Gravity.CENTER)
    foldV.setIncludeFontPadding(false)
    foldV.setBackground(bgShape(ctx, themeGhost, 10, true))
    val fLp = LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20))
    fLp.setMargins(dp(ctx, 7), 0, 0, 0)
    row.addView(foldV, fLp)
    val divider = View(ctx)
    divider.setBackgroundColor(themeGhost)
    val dvLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1))
    dvLp.setMargins(0, dp(ctx, 5), 0, 0)
    card.addView(divider, dvLp)
    val scriptRow = LinearLayout(ctx)
    scriptRow.setOrientation(LinearLayout.HORIZONTAL)
    scriptRow.setGravity(Gravity.CENTER_VERTICAL)
    scriptRow.setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), 0)
    card.addView(scriptRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val scriptTag = TextView(ctx)
    scriptTag.setText(if (idle) {
        "待机"
    } else {
        "在播"
    }
    )
    scriptTag.setTextColor(themeText)
    scriptTag.setTextSize(tinySize.toFloat())
    scriptTag.setGravity(Gravity.CENTER)
    scriptTag.setIncludeFontPadding(false)
    scriptTag.setBackground(bgShape(ctx, themeGhost, 3, false))
    scriptTag.setPadding(dp(ctx, 4), dp(ctx, 1), dp(ctx, 4), dp(ctx, 1))
    scriptRow.addView(scriptTag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val scriptV = TextView(ctx)
    scriptV.setText(scriptTextLine)
    scriptV.setTextColor(themeSub)
    scriptV.setTextSize((if (fontSize > 11) {
        fontSize - 3
    } else {
        8
    }
    ).toFloat())
    scriptV.setSingleLine(true)
    scriptV.setMaxLines(1)
    scriptV.setEllipsize(TruncateAt.END)
    scriptV.setMaxWidth(dp(ctx, 160))
    scriptV.setIncludeFontPadding(false)
    val scLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    scLp.setMargins(dp(ctx, 5), 0, 0, 0)
    scriptRow.addView(scriptV, scLp)
    val mini = LinearLayout(ctx)
    mini.setOrientation(LinearLayout.HORIZONTAL)
    mini.setGravity(Gravity.CENTER_VERTICAL)
    mini.setBackground(bgCard(ctx, cardColor, radius, themeBorder, 1))
    mini.setPadding(dp(ctx, 7), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
    mini.setVisibility(View.GONE)
    mini.setMinimumHeight(dp(ctx, 32))
    mini.setOnClickListener(FloatFoldListener(false, card, mini))
    mini.setOnTouchListener(FloatDragTapListener(dp(ctx, 6)))
    root.addView(mini, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    if (logoBmp != null) {
        val miniLogoImg = ImageView(ctx)
        miniLogoImg.setImageBitmap(logoBmp)
        miniLogoImg.setScaleType(ScaleType.CENTER_CROP)
        miniLogoImg.setBackground(bgShape(ctx, themeLogo, 11, true))
        miniLogoImg.setClipToOutline(true)
        mini.addView(miniLogoImg, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)))
    } else {
        val miniLogo = TextView(ctx)
        miniLogo.setText(logoText)
        miniLogo.setTextColor(themeLogoInk)
        miniLogo.setTextSize(C_LOGO_SP.toFloat())
        miniLogo.setGravity(Gravity.CENTER)
        miniLogo.setIncludeFontPadding(false)
        miniLogo.setBackground(bgShape(ctx, themeLogo, 11, true))
        mini.addView(miniLogo, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)))
    }
    val miniNameV = TextView(ctx)
    miniNameV.setText(roomName)
    miniNameV.setTextColor(themeText)
    miniNameV.setTextSize((if (fontSize > 10) {
        fontSize - 2
    } else {
        9
    }
    ).toFloat())
    miniNameV.setSingleLine(true)
    miniNameV.setMaxLines(1)
    miniNameV.setEllipsize(TruncateAt.END)
    miniNameV.setMaxWidth(dp(ctx, 76))
    miniNameV.setIncludeFontPadding(false)
    val mnLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    mnLp.setMargins(dp(ctx, 6), 0, 0, 0)
    mini.addView(miniNameV, mnLp)
    val miniStatusRow = LinearLayout(ctx)
    miniStatusRow.setOrientation(LinearLayout.HORIZONTAL)
    miniStatusRow.setGravity(Gravity.CENTER_VERTICAL)
    val msrLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    msrLp.setMargins(dp(ctx, 6), 0, 0, 0)
    mini.addView(miniStatusRow, msrLp)
    val miniDotV = View(ctx)
    miniDotV.setBackground(bgShape(ctx, stateDot(stateCode), 2, true))
    miniStatusRow.addView(miniDotV, LinearLayout.LayoutParams(dp(ctx, 4), dp(ctx, 4)))
    val miniStatusV = TextView(ctx)
    miniStatusV.setText(statusText)
    miniStatusV.setTextColor(themeSub)
    miniStatusV.setTextSize(tinySize.toFloat())
    miniStatusV.setIncludeFontPadding(false)
    val mstLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    mstLp.setMargins(dp(ctx, 4), 0, 0, 0)
    miniStatusRow.addView(miniStatusV, mstLp)
    val miniTimeV = TextView(ctx)
    miniTimeV.setText(durationText)
    miniTimeV.setTextColor(themeSub)
    miniTimeV.setTextSize((if (fontSize > 10) {
        fontSize - 4
    } else {
        8
    }
    ).toFloat())
    miniTimeV.setTypeface(Typeface.MONOSPACE)
    miniTimeV.setIncludeFontPadding(false)
    val mtLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    mtLp.setMargins(dp(ctx, 5), 0, 0, 0)
    mini.addView(miniTimeV, mtLp)
    val miniCountChip = LinearLayout(ctx)
    miniCountChip.setGravity(Gravity.CENTER)
    miniCountChip.setBackground(bgShape(ctx, themeCount, 4, false))
    miniCountChip.setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2))
    val mcLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    mcLp.setMargins(dp(ctx, 5), 0, 0, 0)
    mini.addView(miniCountChip, mcLp)
    val miniCountV = TextView(ctx)
    miniCountV.setText(countVal)
    miniCountV.setTextColor(themeCountInk)
    miniCountV.setTextSize((if (fontSize > 10) {
        fontSize - 3
    } else {
        8
    }
    ).toFloat())
    miniCountV.setTypeface(Typeface.MONOSPACE)
    miniCountV.setIncludeFontPadding(false)
    miniCountChip.addView(miniCountV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val miniChev = TextView(ctx)
    miniChev.setText("▾")
    miniChev.setTextColor(themeText)
    miniChev.setTextSize(tinySize.toFloat())
    miniChev.setGravity(Gravity.CENTER)
    miniChev.setIncludeFontPadding(false)
    miniChev.setBackground(bgShape(ctx, themeGhost, 7, true))
    val mchLp = LinearLayout.LayoutParams(dp(ctx, 15), dp(ctx, 15))
    mchLp.setMargins(dp(ctx, 5), 0, 0, 0)
    mini.addView(miniChev, mchLp)
    foldV.setOnClickListener(FloatFoldListener(true, card, mini))
    foldV.setOnTouchListener(FloatDragTapListener(dp(ctx, 6)))
    nameView = nameV
    dotView = dotV
    statusTextView = statusV
    timeView = timeV
    countView = countV
    scriptView = scriptV
    scriptTagView = scriptTag
    rowView = row
    fullCard = card
    miniBar = mini
    miniNameView = miniNameV
    miniDotView = miniDotV
    miniStatusView = miniStatusV
    miniTimeView = miniTimeV
    miniCountView = miniCountV
    lastStateCode = stateCode
    lastName = roomName
    lastStatus = statusText
    lastDuration = durationText
    lastCount = countVal
    lastScript = scriptTextLine
    lastScriptMaxW = 0
    return root
}
fun addOpPill(ctx: Context, parent: LinearLayout, label: String, act: String, topMargin: Int, primary: Boolean): TextView {
    val box = LinearLayout(ctx)
    box.setOrientation(LinearLayout.HORIZONTAL)
    box.setGravity(Gravity.CENTER)
    box.setBackground(bgShape(ctx, if (primary) {
        themePill
    } else {
        themeGhost
    }
    , 9, false))
    val tx = TextView(ctx)
    tx.setText(label)
    tx.setTextColor(if (primary) {
        themePillInk
    } else {
        themeText
    }
    )
    tx.setTextSize((if (themeFont > 10) {
        themeFont - 4
    } else {
        8
    }
    ).toFloat())
    tx.setIncludeFontPadding(false)
    box.addView(tx, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    box.setOnClickListener(FloatClickListener(act))
    val lp = LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 18))
    lp.setMargins(0, topMargin, 0, 0)
    parent.addView(box, lp)
    return tx
}
fun hasPermission(): Boolean {
    val ctx = UTSAndroid.getAppContext()!!
    if (Build.VERSION.SDK_INT >= 23) {
        return Settings.canDrawOverlays(ctx)
    }
    return true
}
fun requestPermission(): Unit {
    val ctx = UTSAndroid.getAppContext()!!
    val pkgUri = Uri.parse("package:" + ctx.getPackageName())
    try {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        intent.setData(pkgUri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return
    }
     catch (e: Throwable) {
        console.log("LG-Float 打开悬浮窗权限设置失败（带包名），尝试通用入口: " + e)
    }
    try {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return
    }
     catch (e: Throwable) {
        console.log("LG-Float 打开悬浮窗权限设置失败（通用入口），尝试应用详情页: " + e)
    }
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.setData(pkgUri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
     catch (e: Throwable) {
        console.log("LG-Float 打开应用详情页失败: " + e)
    }
}
fun showFloat(optionsJson: String = "", onAction: ((action: String) -> Unit)? = null): Unit {
    val ctx = UTSAndroid.getAppContext()!!
    if (!hasPermission()) {
        lastErrorMsg = "未授予「显示在其他应用上层」权限"
        console.log("LG-Float 未授权悬浮窗权限，已跳转系统设置页")
        requestPermission()
        return
    }
    lastErrorMsg = ""
    actionHandler = onAction
    val o = parseOptions(optionsJson)
    val styleKey = styleKeyOf(o)
    if (showing && attachedRoot != null && isAttachedNow() && styleKey == lastStyleKey) {
        lastErrorMsg = ""
        updateFloat(optionsJson)
        return
    }
    lastStyleKey = styleKey
    val root = buildView(ctx, o)
    val posXV = jsonNum(o, "posX")
    val posYV = jsonNum(o, "posY")
    val posX = if (posXV != null) {
        posXV!!
    } else {
        12
    }
    val posY = if (posYV != null) {
        posYV!!
    } else {
        110
    }
    val type = if (Build.VERSION.SDK_INT >= 26) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_PHONE
    }
    val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
    params.gravity = Gravity.TOP or Gravity.LEFT
    params.x = dp(ctx, posX)
    params.y = dp(ctx, posY)
    val nv = nameView
    val dv = dotView
    val sv = statusTextView
    val tv = timeView
    val o1 = op1Text
    val o2 = op2Text
    val cv = countView
    val scv = scriptView
    val stv = scriptTagView
    val rwv = rowView
    val fc = fullCard
    val mb = miniBar
    val mnv = miniNameView
    val mdv = miniDotView
    val msv = miniStatusView
    val mtv = miniTimeView
    val mcv = miniCountView
    val a1 = op1Act
    val a2 = op2Act
    winGen += 1
    val gen = winGen
    runOnUi(fun(){
        if (gen != winGen) {
            return
        }
        detachLocked()
        try {
            wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm!!.addView(root, params)
            attachedRoot = root
            attachedWm = wm
            floatView = root
            floatParams = params
            showing = true
            lastErrorMsg = ""
        }
         catch (e: Throwable) {
            lastErrorMsg = "窗口添加失败：" + e
            console.log("LG-Float 添加悬浮窗失败: " + e)
            floatView = null
            floatParams = null
            wm = null
            attachedRoot = null
            attachedWm = null
            showing = false
            return
        }
        nameView = nv
        dotView = dv
        statusTextView = sv
        timeView = tv
        op1Text = o1
        op2Text = o2
        countView = cv
        scriptView = scv
        scriptTagView = stv
        rowView = rwv
        fullCard = fc
        miniBar = mb
        miniNameView = mnv
        miniDotView = mdv
        miniStatusView = msv
        miniTimeView = mtv
        miniCountView = mcv
        op1Act = a1
        op2Act = a2
    }
    )
}
fun styleKeyOf(o: UTSJSONObject?): String {
    val bg = strOf(jsonStr(o, "bgColor"), "")
    val bgAlpha = jsonNum(o, "bgAlpha")
    val text = strOf(jsonStr(o, "textColor"), "")
    val status = strOf(jsonStr(o, "statusColor"), "")
    val pause = strOf(jsonStr(o, "pauseColor"), "")
    val btn = strOf(jsonStr(o, "btnBgColor"), "")
    val logo = strOf(jsonStr(o, "logoBgColor"), "")
    val count = strOf(jsonStr(o, "countBgColor"), "")
    val logoImage = strOf(jsonStr(o, "logoImage"), "")
    val logoImageAlt = strOf(jsonStr(o, "logoImageAlt"), "")
    val actionsText = strOf(jsonStr(o, "actionsText"), "pause,stop")
    val fontSize = jsonNum(o, "fontSize")
    val radius = jsonNum(o, "cornerRadius")
    val posX = jsonNum(o, "posX")
    val posY = jsonNum(o, "posY")
    return bg + "|" + text + "|" + status + "|" + pause + "|" + btn + "|" + logo + "|" + count + "|" + logoImage + "|" + logoImageAlt + "|" + actionsText + "|" + (if (bgAlpha != null) {
        bgAlpha!!.toString(10)
    } else {
        ""
    }
    ) + "|" + (if (fontSize != null) {
        fontSize!!.toString(10)
    } else {
        ""
    }
    ) + "|" + (if (radius != null) {
        radius!!.toString(10)
    } else {
        ""
    }
    ) + "|" + (if (posX != null) {
        posX!!.toString(10)
    } else {
        ""
    }
    ) + "|" + (if (posY != null) {
        posY!!.toString(10)
    } else {
        ""
    }
    )
}
fun syncScriptWidth(): Unit {
    val rv = rowView
    val sc = scriptView
    if (rv == null || sc == null) {
        return
    }
    val w = rv.getWidth()
    if (w <= 0) {
        return
    }
    val ctx = UTSAndroid.getAppContext()!!
    val tg = scriptTagView
    val tagW = if (tg != null) {
        tg.getWidth()
    } else {
        dp(ctx, 28)
    }
    var maxW = w - dp(ctx, 4) - tagW - dp(ctx, 5)
    val floor = dp(ctx, 120)
    if (maxW < floor) {
        maxW = floor
    }
    if (maxW == lastScriptMaxW) {
        return
    }
    lastScriptMaxW = maxW
    sc.setMaxWidth(maxW)
}
fun updateFloat(optionsJson: String = ""): Unit {
    val o = parseOptions(optionsJson)
    if (o == null) {
        return
    }
    val roomName = strOf(jsonStr(o, "roomName"), strOf(jsonStr(o, "title"), ""))
    val stateCode = strOf(jsonStr(o, "stateCode"), "idle")
    val statusText = strOf(jsonStr(o, "statusText"), strOf(jsonStr(o, "status"), ""))
    val durationText = strOf(jsonStr(o, "durationText"), "")
    val countText = countTextOf(o)
    val scriptText = rawStr(o, "scriptText")
    val idle = stateCode == "idle"
    val op1Label = if (idle) {
        "开播"
    } else {
        if (stateCode == "paused") {
            "继续"
        } else {
            "暂停"
        }
    }
    val op2Label = if (idle) {
        "关闭"
    } else {
        "关播"
    }
    runOnUi(fun(){
        val nv = nameView
        if (nv != null && roomName != "" && roomName != lastName) {
            nv.setText(roomName)
            val mnv = miniNameView
            if (mnv != null) {
                mnv.setText(roomName)
            }
            lastName = roomName
        }
        if (stateCode != lastStateCode) {
            val ctx = UTSAndroid.getAppContext()!!
            val dv = dotView
            if (dv != null) {
                dv.setBackground(bgShape(ctx, stateDot(stateCode), 3, true))
            }
            val mdv = miniDotView
            if (mdv != null) {
                mdv.setBackground(bgShape(ctx, stateDot(stateCode), 2, true))
            }
            val o1 = op1Text
            if (o1 != null && op1Act != "") {
                o1.setText(op1Label)
            }
            val o2 = op2Text
            if (o2 != null && op2Act != "") {
                o2.setText(op2Label)
            }
            val tag = scriptTagView
            if (tag != null) {
                tag.setText(if (idle) {
                    "待机"
                } else {
                    "在播"
                }
                )
            }
            lastStateCode = stateCode
        }
        val sv = statusTextView
        if (sv != null && statusText != "" && statusText != lastStatus) {
            sv.setText(statusText)
            val msv = miniStatusView
            if (msv != null) {
                msv.setText(statusText)
            }
            lastStatus = statusText
        }
        val tv = timeView
        if (tv != null && durationText != "" && durationText != lastDuration) {
            tv.setText(durationText)
            val mtv = miniTimeView
            if (mtv != null) {
                mtv.setText(durationText)
            }
            lastDuration = durationText
        }
        val cv = countView
        if (cv != null && countText != "" && countText != lastCount) {
            cv.setText(countText)
            val mcv = miniCountView
            if (mcv != null) {
                mcv.setText(countText)
            }
            lastCount = countText
        }
        if (scriptText != null) {
            val line = scriptLine(scriptText!!)
            if (line != lastScript) {
                val sc = scriptView
                if (sc != null) {
                    sc.setText(line)
                }
                lastScript = line
            }
        }
        syncScriptWidth()
    }
    )
}
fun hideFloat(): Unit {
    winGen += 1
    floatView = null
    floatParams = null
    wm = null
    showing = false
    lastStyleKey = ""
    nameView = null
    dotView = null
    statusTextView = null
    timeView = null
    op1Text = null
    op2Text = null
    countView = null
    scriptView = null
    scriptTagView = null
    rowView = null
    fullCard = null
    miniBar = null
    miniNameView = null
    miniDotView = null
    miniStatusView = null
    miniTimeView = null
    miniCountView = null
    lastStateCode = ""
    lastName = ""
    lastStatus = ""
    lastDuration = ""
    lastCount = ""
    lastScript = ""
    lastScriptMaxW = 0
    runOnUi(fun(){
        detachLocked()
    }
    )
}
fun isShowing(): Boolean {
    if (!showing) {
        return false
    }
    return isAttachedNow()
}
fun getLastError(): String {
    return lastErrorMsg
}
fun hasPermissionByJs(): Boolean {
    return hasPermission()
}
fun requestPermissionByJs(): Unit {
    return requestPermission()
}
fun showFloatByJs(optionsJson: String = "", onAction: UTSCallback? = null): Unit {
    return showFloat(optionsJson, if (onAction == null) {
        null
    } else {
        if (onAction.fnJS != null) {
            onAction.fnJS
        } else {
            onAction.fnJS = fun(action: String){
                onAction(action)
            }
            onAction.fnJS
        }
         as (action: String) -> Unit
    }
    )
}
fun updateFloatByJs(optionsJson: String = ""): Unit {
    return updateFloat(optionsJson)
}
fun hideFloatByJs(): Unit {
    return hideFloat()
}
fun isShowingByJs(): Boolean {
    return isShowing()
}
fun getLastErrorByJs(): String {
    return getLastError()
}
