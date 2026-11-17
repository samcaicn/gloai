@file:Suppress("UNCHECKED_CAST", "USELESS_CAST", "INAPPLICABLE_JVM_NAME", "UNUSED_ANONYMOUS_PARAMETER", "NAME_SHADOWING", "UNNECESSARY_NOT_NULL_ASSERTION")
package uts.sdk.modules.LGAccessibility
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import uts.sdk.modules.LGAccessibility.R
import android.accessibilityservice.GestureDescription.Builder as GestureDescriptionBuilder
import io.dcloud.uts.UTSAndroid
val SERVICE_CLASS = "uts.sdk.modules.LGAccessibility.LGAccessibilityService"
val GESTURE_START_MS: Number = 0
var serviceRef: LGAccessibilityService? = null
open class Matcher {
    open lateinit var kind: String
    open lateinit var value: String
    open var equals: Boolean by Delegates.notNull()
    constructor(kind: String, value: String, isEquals: Boolean){
        this.kind = kind
        this.value = value
        this.equals = isEquals
    }
}
fun matcherOf(kind: String, value: String, isEquals: Boolean): Matcher {
    return Matcher(kind, value, isEquals)
}
fun nodeClassName(node: AccessibilityNodeInfo): String {
    val cs = node.getClassName()
    return if (cs == null) {
        ""
    } else {
        cs.toString()
    }
}
fun nodeText(node: AccessibilityNodeInfo): String {
    val cs = node.getText()
    return if (cs == null) {
        ""
    } else {
        cs.toString()
    }
}
fun nodeDesc(node: AccessibilityNodeInfo): String {
    val cs = node.getContentDescription()
    return if (cs == null) {
        ""
    } else {
        cs.toString()
    }
}
fun checkOk(node: AccessibilityNodeInfo, m: Matcher): Boolean {
    if (m.kind == "className") {
        val v = nodeClassName(node)
        return if (m.equals) {
            v == m.value
        } else {
            v.indexOf(m.value) >= 0
        }
    }
    if (m.kind == "text") {
        val v = nodeText(node)
        return if (m.equals) {
            v == m.value
        } else {
            v.indexOf(m.value) >= 0
        }
    }
    if (m.kind == "contentDescription") {
        val v = nodeDesc(node)
        return if (m.equals) {
            v == m.value
        } else {
            v.indexOf(m.value) >= 0
        }
    }
    return false
}
fun collectMatches(node: AccessibilityNodeInfo?, m: Matcher, out: UTSArray<AccessibilityNodeInfo>): Unit {
    if (node == null) {
        return
    }
    val count = node.getChildCount()
    run {
        var i: Number = 0
        while(i < count){
            val child = node.getChild(i.toInt())
            if (child == null) {
                i++
                continue
            }
            if (checkOk(child, m)) {
                out.push(child)
            } else {
                collectMatches(child, m, out)
            }
            i++
        }
    }
}
fun findFirstByMatcher(root: AccessibilityNodeInfo, m: Matcher): AccessibilityNodeInfo? {
    if (checkOk(root, m)) {
        return root
    }
    val out: UTSArray<AccessibilityNodeInfo> = _uA()
    collectMatches(root, m, out)
    return if (out.length > 0) {
        out[0]
    } else {
        null
    }
}
fun findFirstByClassNames(root: AccessibilityNodeInfo, names: UTSArray<String>): AccessibilityNodeInfo? {
    run {
        var i: Number = 0
        while(i < names.length){
            val hit = findFirstByMatcher(root, matcherOf("className", names[i], true))
            if (hit != null) {
                return hit
            }
            i++
        }
    }
    return null
}
fun findFirstByText(root: AccessibilityNodeInfo, texts: UTSArray<String>, isEquals: Boolean): AccessibilityNodeInfo? {
    run {
        var i: Number = 0
        while(i < texts.length){
            val t = texts[i]
            val found = root.findAccessibilityNodeInfosByText(t)
            if (found == null || found.size == 0) {
                i++
                continue
            }
            if (!isEquals) {
                return found.get(0)
            }
            run {
                var j: Number = 0
                while(j < found.size){
                    val one = found.get(j.toInt())
                    if (nodeText(one) == t) {
                        return one
                    }
                    j++
                }
            }
            i++
        }
    }
    return null
}
fun clickView(node: AccessibilityNodeInfo?): Boolean {
    var cur = node
    while(cur != null){
        if (cur.isClickable()) {
            cur.performAction(0x10)
            return true
        }
        cur = cur.getParent()
    }
    return false
}
fun editViewText(node: AccessibilityNodeInfo?, text: String): Boolean {
    if (node == null) {
        return false
    }
    if (!node.isEditable()) {
        return false
    }
    node.performAction(1)
    val args = Bundle()
    args.putCharSequence("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE", text)
    node.performAction(0x200000, args)
    return true
}
fun postOnMain(fn: () -> Unit): Unit {
    try {
        val h = Handler(Looper.getMainLooper())
        h.post(fn)
    }
     catch (e: Throwable) {
        console.log("LG-Accessibility 主线程投递失败，改为当前线程执行: " + e)
        fn()
    }
}
fun dispatchGestureLongClick(svc: LGAccessibilityService, x: Int, y: Int, durationMs: Number): Unit {
    val path = Path()
    path.moveTo((x - 1).toFloat(), (y - 1).toFloat())
    path.lineTo(x.toFloat(), (y - 1).toFloat())
    path.lineTo(x.toFloat(), y.toFloat())
    path.lineTo((x - 1).toFloat(), y.toFloat())
    val builder = GestureDescriptionBuilder()
    builder.addStroke(StrokeDescription(path, GESTURE_START_MS.toLong(), durationMs.toLong()))
    svc.dispatchGesture(builder.build(), null, null)
}
fun sleep(ms: Number): UTSPromise<Unit> {
    return UTSPromise<Unit>(fun(resolve, reject){
        setTimeout(fun(){
            resolve(Unit)
        }
        , ms)
    }
    )
}
open class LGAccessibilityService : AccessibilityService {
    constructor() : super() {}
    override fun onServiceConnected(): Unit {
        super.onServiceConnected()
        serviceRef = this
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent): Unit {}
    override fun onInterrupt(): Unit {
        serviceRef = null
    }
    override fun onDestroy(): Unit {
        serviceRef = null
        super.onDestroy()
    }
}
open class PlatformPlan {
    open lateinit var listClassNames: UTSArray<String>
    open lateinit var textClassNames: UTSArray<String>
    open var requireColon: Boolean by Delegates.notNull()
    open lateinit var excludeWords: UTSArray<String>
    open lateinit var editClassNames: UTSArray<String>
    open lateinit var sendTexts: UTSArray<String>
    open lateinit var longPressMs: Number
    open lateinit var settleMs: Number
    constructor(listClassNames: UTSArray<String>, textClassNames: UTSArray<String>, requireColon: Boolean, excludeWords: UTSArray<String>, editClassNames: UTSArray<String>, sendTexts: UTSArray<String>, longPressMs: Number, settleMs: Number){
        this.listClassNames = listClassNames
        this.textClassNames = textClassNames
        this.requireColon = requireColon
        this.excludeWords = excludeWords
        this.editClassNames = editClassNames
        this.sendTexts = sendTexts
        this.longPressMs = longPressMs
        this.settleMs = settleMs
    }
}
fun planOf(key: String): PlatformPlan? {
    if (key == "pinduoduo") {
        val p = PlatformPlan(_uA(
            "android.support.v7.widget.RecyclerView",
            "androidx.recyclerview.widget.RecyclerView"
        ), _uA(
            "android.widget.TextView"
        ), true, _uA(
            "公告",
            "平台提醒",
            "[主播]",
            "提示"
        ), _uA(
            "android.widget.EditText"
        ), _uA(
            "发送"
        ), 1000, 700)
        return p
    }
    if (key == "xiaohongshu") {
        val p = PlatformPlan(_uA(
            "androidx.recyclerview.widget.RecyclerView",
            "android.support.v7.widget.RecyclerView"
        ), _uA(
            "android.widget.TextView"
        ), true, _uA(
            "公告",
            "平台提醒",
            "[主播]",
            "提示"
        ), _uA(
            "android.widget.EditText"
        ), _uA(
            "发送"
        ), 1000, 700)
        return p
    }
    if (key == "ali1688_guonei") {
        val p = PlatformPlan(_uA(
            "androidx.recyclerview.widget.RecyclerView",
            "android.support.v7.widget.RecyclerView"
        ), _uA(
            "android.widget.TextView"
        ), true, _uA(
            "公告",
            "平台提醒",
            "[主播]",
            "提示"
        ), _uA(
            "android.widget.EditText"
        ), _uA(
            "发送"
        ), 1000, 700)
        return p
    }
    return null
}
fun isDanmakuNode(node: AccessibilityNodeInfo, plan: PlatformPlan): Boolean {
    var classNameOk = false
    run {
        var i: Number = 0
        while(i < plan.textClassNames.length){
            if (nodeClassName(node) == plan.textClassNames[i]) {
                classNameOk = true
            }
            i++
        }
    }
    if (!classNameOk) {
        return false
    }
    val text = nodeText(node)
    if (text == "") {
        return false
    }
    if (plan.requireColon && text.indexOf("：") < 0) {
        return false
    }
    run {
        var i: Number = 0
        while(i < plan.excludeWords.length){
            if (text.indexOf(plan.excludeWords[i]) >= 0) {
                return false
            }
            i++
        }
    }
    return true
}
fun findDanmakuNode(container: AccessibilityNodeInfo, plan: PlatformPlan): AccessibilityNodeInfo? {
    val rows = container.getChildCount()
    run {
        var i: Number = 0
        while(i < rows){
            val row = container.getChild(i.toInt())
            if (row == null) {
                i++
                continue
            }
            val cells = row.getChildCount()
            run {
                var j: Number = 0
                while(j < cells){
                    val node = row.getChild(j.toInt())
                    if (node == null) {
                        j++
                        continue
                    }
                    if (isDanmakuNode(node, plan)) {
                        return node
                    }
                    j++
                }
            }
            i++
        }
    }
    val out: UTSArray<AccessibilityNodeInfo> = _uA()
    collectByPredicate(container, plan, out)
    return if (out.length > 0) {
        out[0]
    } else {
        null
    }
}
fun collectByPredicate(node: AccessibilityNodeInfo, plan: PlatformPlan, out: UTSArray<AccessibilityNodeInfo>): Unit {
    val count = node.getChildCount()
    run {
        var i: Number = 0
        while(i < count){
            val child = node.getChild(i.toInt())
            if (child == null) {
                i++
                continue
            }
            if (isDanmakuNode(child, plan)) {
                out.push(child)
            } else {
                collectByPredicate(child, plan, out)
            }
            i++
        }
    }
}
fun isAccessibilityEnabled(): Boolean {
    if (serviceRef != null) {
        return true
    }
    try {
        val ctx = UTSAndroid.getAppContext()!!
        val enabled = Settings.Secure.getString(ctx.getContentResolver(), "enabled_accessibility_services")
        if (enabled == null) {
            return false
        }
        val s = enabled.toString()
        if (s.indexOf(SERVICE_CLASS) >= 0) {
            return true
        }
        if (s.indexOf(ctx.getPackageName() + "/" + SERVICE_CLASS) >= 0) {
            return true
        }
        return false
    }
     catch (e: Throwable) {
        console.log("LG-Accessibility 读取无障碍开关失败: " + e)
        return false
    }
}
fun openAccessibilitySettings(): Unit {
    val ctx = UTSAndroid.getAppContext()!!
    try {
        val intent = Intent("android.settings.ACCESSIBILITY_SETTINGS")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
     catch (e: Throwable) {
        console.log("LG-Accessibility 打开无障碍设置失败: " + e)
        try {
            val fallback = Intent("android.settings.SETTINGS")
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(fallback)
        }
         catch (e2: Throwable) {
            console.log("LG-Accessibility 打开系统设置也失败: " + e2)
        }
    }
}
fun replyByAccessibility(key: String, text: String): UTSPromise<Boolean> {
    return wrapUTSPromise(suspend w@{
            try {
                val svc = serviceRef
                if (svc == null) {
                    console.log("LG-Accessibility 回复失败：服务未连接（未开启或未绑定）")
                    return@w false
                }
                if (Build.VERSION.SDK_INT < 24) {
                    console.log("LG-Accessibility 回复失败：系统版本低于 7.0，不支持 dispatchGesture")
                    return@w false
                }
                val plan = planOf(key)
                if (plan == null) {
                    console.log("LG-Accessibility 回复失败：未登记的平台 key=" + key)
                    return@w false
                }
                val content = if (text == null) {
                    ""
                } else {
                    text.trim()
                }
                if (content == "") {
                    console.log("LG-Accessibility 回复失败：文案为空")
                    return@w false
                }
                val root = svc.getRootInActiveWindow()
                if (root == null) {
                    console.log("LG-Accessibility 回复失败：当前窗口节点树为空（目标页面未在前台？）")
                    return@w false
                }
                val container = findFirstByClassNames(root, plan.listClassNames)
                if (container == null) {
                    console.log("LG-Accessibility 回复失败：未找到公屏列表容器")
                    return@w false
                }
                val target = findDanmakuNode(container, plan)
                if (target == null) {
                    console.log("LG-Accessibility 回复失败：未找到可回复的弹幕文本")
                    return@w false
                }
                val rect = Rect()
                target.getBoundsInScreen(rect)
                val cx = rect.centerX()
                val cy = rect.centerY()
                postOnMain(fun(){
                    try {
                        dispatchGestureLongClick(svc, cx, cy, plan.longPressMs)
                    }
                     catch (e: Throwable) {
                        console.log("LG-Accessibility 长按手势派发失败: " + e)
                    }
                }
                )
                await(sleep(plan.settleMs))
                val root2 = svc.getRootInActiveWindow()
                if (root2 == null) {
                    console.log("LG-Accessibility 回复失败：长按后节点树为空")
                    return@w false
                }
                val edit = findFirstByClassNames(root2, plan.editClassNames)
                if (edit == null) {
                    console.log("LG-Accessibility 回复失败：未找到回复输入框")
                    return@w false
                }
                if (!editViewText(edit, content)) {
                    console.log("LG-Accessibility 回复失败：输入框不可编辑，写入失败")
                    return@w false
                }
                val send = findFirstByText(root2, plan.sendTexts, true)
                if (send == null) {
                    console.log("LG-Accessibility 回复失败：未找到「发送」节点")
                    return@w false
                }
                if (!clickView(send)) {
                    console.log("LG-Accessibility 回复失败：点击「发送」未命中可点击节点")
                    return@w false
                }
                return@w true
            }
             catch (e: Throwable) {
                console.log("LG-Accessibility 回复异常（已按失败处理）: " + e)
                return@w false
            }
    })
}
open class LGAccessibilityServiceByJs : LGAccessibilityService {
    constructor() : super() {}
    open fun onServiceConnectedByJs(): Unit {
        return this.onServiceConnected()
    }
    open fun onAccessibilityEventByJs(event: AccessibilityEvent): Unit {
        return this.onAccessibilityEvent(event)
    }
    open fun onInterruptByJs(): Unit {
        return this.onInterrupt()
    }
    open fun onDestroyByJs(): Unit {
        return this.onDestroy()
    }
}
fun isAccessibilityEnabledByJs(): Boolean {
    return isAccessibilityEnabled()
}
fun openAccessibilitySettingsByJs(): Unit {
    return openAccessibilitySettings()
}
suspend fun replyByAccessibilityByJs(key: String, text: String): Deferred<Boolean> {
    return toDeferred(replyByAccessibility(key, text))
}
