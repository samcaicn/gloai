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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把一个工作区音频分享给一个 App(ACTION_SEND + EXTRA_STREAM),在 Mika 的屏上打开它的分享页:
 * 只打开,不发出;收件人和「发送」由模型在那一页上用屏幕工具完成,发送仍走现有的确认关卡。
 */
internal object ShareAudio : PhoneTool {
    override val verb = Verb.ShareAudio
    override val label = "分享音频到 App"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_share_audio", Prompts.tool("phone_share_audio"),
        mapOf(
            "package" to str("Exact package name of the app to share the audio to, e.g. com.example.app."),
            "path" to str("Workspace audio path, e.g. /workspace/out/audio/line.wav."),
            "text" to str("Optional caption to share with the audio, at most 2000 characters."),
        ),
        listOf("package", "path"))

    override fun available(ctx: ToolContext) = ctx.phone.screen.canShareAudio
    override fun offered(ctx: ToolContext) = available(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val path = a.str("path", max = 500)!!.trim()
        val prefix = SandboxPaths.WORKSPACE + "/"
        if (!path.startsWith(prefix) || path.removePrefix(prefix).split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw ArgError("path", "must be a workspace audio path under $prefix")
        }
        val text = a.str("text", max = 2000)
        ctx.refusedPay = null
        val r = ctx.action(ctx.phone.screen.shareAudio(p, path, text))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        return r
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["package"] as? JsonPrimitive)?.content

    override fun observations(o: JsonObject, r: ToolExecution, log: com.wangzan101.mika.core.tool.EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull((o["package"] as? JsonPrimitive)?.content?.let { p ->
            Observation(ObsKind.Fact, "$name 打开了 $p 的音频分享页(还没发出)", source = PHONE)
        })
    }
}
