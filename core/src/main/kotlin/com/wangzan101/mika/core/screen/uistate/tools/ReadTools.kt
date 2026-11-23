package com.wangzan101.mika.core.screen.uistate.tools

import com.wangzan101.mika.core.safety.gate.IrreversibleDetector
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.screen.uistate.ScreenFamily
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.HINT_PROTECTED
import com.wangzan101.mika.core.tool.HINT_DISPLAY_CAPTURE
import com.wangzan101.mika.core.screen.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.screen.visual.Recall
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.screen.uistate.screenText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.screen.uistate.lastSnapshot
import com.wangzan101.mika.core.screen.uistate.screen
import com.wangzan101.mika.core.screen.uistate.session

/** 读这块屏:元素表(observe 按配置补截图)、单独截图。 */
internal class Observe(f: ScreenFamily) : ScreenTool(f, "observe") {
    override val verb = Verb.Observe
    override val label = "看屏幕"
    override val risk = Risk.Read
    override val def by lazy { ToolDef(name, describe()) }

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.session.observe(
        withImage = ctx.config.visionEnabled && !ctx.config.adaptiveVision,
        adaptiveImage = ctx.config.visionEnabled && ctx.config.adaptiveVision,
    )
}

internal class ListElements(f: ScreenFamily) : ScreenTool(f, "list_elements") {
    override val verb = Verb.ListElements
    override val label = "读取界面元素"
    override val risk = Risk.Read
    override val def by lazy { ToolDef(name, describe()) }

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.session.observe(withImage = false, recordFrame = false)
}

/** Seeing models receive the image; any task with a workspace can save it for delivery to the user. */
internal class Screenshot(f: ScreenFamily) : ScreenTool(f, "screenshot") {
    override val verb = Verb.Screenshot
    override val label = "截图"
    override val risk = Risk.Read
    override val def by lazy { ToolDef(name, describe(), mapOf(
        "savePath" to str("Optional new PNG path under /workspace. Save the screenshot there, then use share_file to give it to the user."))) }

    override fun usable(ctx: ToolContext) = ctx.config.visionEnabled || ctx.config.saveScreenshot != null
    override fun readOnly(args: JsonObject) = "savePath" !in args

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val savePath = a.str("savePath", max = 4096)
        if (savePath == null && !ctx.config.visionEnabled)
            return fail("save_path_required", "This model cannot view images. Set savePath to a new .png path under /workspace to capture a screenshot for the user.")
        if (savePath != null && ctx.config.saveScreenshot == null)
            return fail("screenshot_save_unavailable", "Saving screenshots is not available for this task.")
        val screen = ctx.screen
        if (screen.isProtected()) return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "protected_content"); put("protected", true)
            put("message", HINT_PROTECTED); put("recoverable", true)
        })
        val png = screen.screenshotPng()
        if (png == null) {
            val captureError = screen.peek().captureError
            if (captureError == ScreenDiagnosis.DISPLAY_CAPTURE_UNAVAILABLE) {
                return fail(captureError, HINT_DISPLAY_CAPTURE, recoverable = false)
            }
            return fail("screenshot_unavailable", Feedback["screenshot_unavailable"])
        }
        // Recheck before writing a persistent image if the screen changed during capture.
        if (savePath != null && screen.isProtected()) return fail("protected_content", HINT_PROTECTED)
        val saved = if (savePath == null) null else try { ctx.config.saveScreenshot!!.invoke(savePath, png) }
            catch (e: SandboxError) { return fail(e.code, e.message ?: e.code, e.recoverable) }
        val s = ctx.lastSnapshot
        val shots = ctx.shots
        // 和读屏一样登记进回看,旧图被裁掉后还能按 screenshotId 找回;付款页的图不留
        val shotId = shots.next()
        shots.remember(Recall(shotId, ctx.currentStep, s?.packageName, png, s?.let(::screenText).orEmpty()))
        return ToolExecution(okObj {
            s?.let { put("width", it.width); put("height", it.height) }; put("screenshotId", shotId)
            saved?.let { put("path", it); put("size", png.size); put("hint", "Use share_file with this path to deliver the screenshot to the user.") }
        }, image = png.takeIf { ctx.config.visionEnabled }, imageIds = if (ctx.config.visionEnabled) listOf(shotId) else emptyList(),
            followUpTools = if (saved == null) emptyList() else listOf(Verb.ShareFile))
    }
}

