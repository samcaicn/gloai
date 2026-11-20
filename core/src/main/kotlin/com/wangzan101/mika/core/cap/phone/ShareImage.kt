package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.LinkPolicy
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.uistate.action
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把工作区里的一张图片分享给一个 App(ACTION_SEND + EXTRA_STREAM),在 Mika 的屏上打开它的分享页:
 * 只打开,不发出;收件人和「发送」由模型在那一页上用屏幕工具完成,发送仍走现有的确认关卡。
 */
internal object ShareImage : PhoneTool {
    override val verb = Verb.ShareImage
    override val label = "分享图片到 App"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_share_image", Prompts.tool("phone_share_image"),
        mapOf(
            "package" to str("Exact package name of the app to share the image to, e.g. com.example.app."),
            "path" to str("Workspace image path, e.g. /workspace/out/images/cat.png or /workspace/inbox/photo.jpg."),
            "text" to str("Optional caption to share with the image, at most 2000 characters."),
        ),
        listOf("package", "path"))

    override fun available(ctx: ToolContext) = ctx.phone.screen.canShareImage
    override fun offered(ctx: ToolContext) = available(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val path = a.str("path", max = 500)!!.trim()
        val prefix = SandboxPaths.WORKSPACE + "/"
        if (!path.startsWith(prefix) || path.removePrefix(prefix).split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw ArgError("path", "must be a workspace image path under $prefix")
        }
        val text = a.str("text", max = 2000)
        // 换了 App,之前被拒的付款申报就不是这次的了
        ctx.refusedPay = null
        val r = ctx.action(ctx.phone.screen.shareImage(p, path, text))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        return r
    }

    override fun logTarget(o: kotlinx.serialization.json.JsonObject, r: ToolExecution, ctx: ToolContext) =
        (o["package"] as? JsonPrimitive)?.content

    override fun observations(o: kotlinx.serialization.json.JsonObject, r: ToolExecution, log: com.wangzan101.mika.core.tool.EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull((o["package"] as? JsonPrimitive)?.content?.let { p ->
            Observation(ObsKind.Fact, "$name 打开了 $p 的图片分享页(还没发出)", source = PHONE)
        })
    }
}
