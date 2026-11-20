package com.wangzan101.mika.core.cap.image

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.model.ImageAspect
import com.wangzan101.mika.core.model.ImageError
import com.wangzan101.mika.core.model.ImageRequest
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Entry
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.tool.strArray
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 相册审批卡上的 App 字段;不是真实包名,只说明这一步落在相册。 */
private const val GALLERY_CONFIRM_APP = "mika:gallery"

internal object ImageGenerate : AgentTool {
    override val capability: String get() = IMAGE
    override val verb = Verb.ImageGenerate
    override val label = "生成图片"
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val wideTarget = true
    override val entry = Entry.Opens
    override val def = ToolDef("image_generate",
        Prompts.tool("image_generate"),
        mapOf(
            "prompt" to str("What the image should show, 1-4000 characters."),
            "aspect" to str("Aspect ratio the model should produce: ${aspects()}.", ImageAspect.entries.map { it.wire }),
            "count" to int("How many images to make, 1-4 (default 1).", 1, 4),
            "name" to str("Optional file name to suggest, without a path."),
        ), listOf("prompt"))

    override fun available(ctx: ToolContext) = ctx[ImageCapability.KEY]?.port != null
    override fun offered(ctx: ToolContext) = available(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val cap = ctx[ImageCapability.KEY] ?: return fail("image_unavailable", "No image model is configured.")
        val port = cap.port ?: return fail("image_unavailable", "No image model is configured.")
        val prompt = a.str("prompt", max = 4000)!!.trim()
        val aspect = aspectOf(a.str("aspect"))
        val count = a.int("count", 1, 4) ?: 1
        if (count > port.capabilities.maxCount) throw ArgError("count", "this model makes at most ${port.capabilities.maxCount} images per call")
        if (aspect !in port.capabilities.aspects) throw ArgError("aspect", "this model does not support ${aspect.wire}")
        if (!cap.reserve(count)) {
            return fail("task_image_limit_reached",
                "This task has used ${cap.used()} of its ${cap.maxPerTask} images. Ask the user before making more, or finish with what you have.")
        }
        val response = try {
            port.generate(ImageRequest(prompt, aspect, count))
        } catch (e: CancellationException) {
            cap.refund(count)
            throw e
        } catch (e: Throwable) {
            cap.refund(count)
            return failure(e)
        }
        if (response.images.isEmpty()) {
            cap.refund(count)
            return fail("image_bad_response", "The image service returned no image.")
        }
        val stored = try {
            response.images.map { port.store(it, a.str("name", max = 120), prompt) }
        } catch (e: Throwable) {
            // 图已经生成并计费了,不把额度退回去;只是没写进工作区。
            return failure(e)
        }
        return result(port, cap, stored, response.images.map { it.bytes }, ctx, response.images.mapNotNull { it.revisedPrompt })
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["prompt"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(200)

    private fun aspects() = ImageAspect.entries.joinToString(" | ") { it.wire }
}

internal object ImageEdit : AgentTool {
    override val capability: String get() = IMAGE
    override val verb = Verb.ImageEdit
    override val label = "修改图片"
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("image_edit",
        Prompts.tool("image_edit"),
        mapOf(
            "paths" to strArray("1-4 workspace image paths to edit, e.g. /workspace/inbox/photo.jpg.", 4),
            "prompt" to str("How the image should change, 1-4000 characters."),
            "aspect" to str("Aspect ratio the model should produce: ${aspects()}.", ImageAspect.entries.map { it.wire }),
            "count" to int("How many images to make, 1-4 (default 1).", 1, 4),
            "name" to str("Optional file name to suggest, without a path."),
        ), listOf("paths", "prompt"))

    override fun available(ctx: ToolContext) = ctx[ImageCapability.KEY]?.port?.capabilities?.supportsEdit == true

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val cap = ctx[ImageCapability.KEY] ?: return fail("image_unavailable", "No image model is configured.")
        val port = cap.port ?: return fail("image_unavailable", "No image model is configured.")
        val paths = a.strList("paths", 1, port.capabilities.maxReferences.coerceAtLeast(1), itemMax = 500)!!
        if (paths.size > port.capabilities.maxReferences) {
            throw ArgError("paths", "this model takes at most ${port.capabilities.maxReferences} reference image(s)")
        }
        val prompt = a.str("prompt", max = 4000)!!.trim()
        val aspect = aspectOf(a.str("aspect"))
        val count = a.int("count", 1, 4) ?: 1
        if (count > port.capabilities.maxCount) throw ArgError("count", "this model makes at most ${port.capabilities.maxCount} images per call")
        if (aspect !in port.capabilities.aspects) throw ArgError("aspect", "this model does not support ${aspect.wire}")
        val references = try {
            paths.map { port.readReference(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failure(e)
        }
        if (!cap.reserve(count)) {
            return fail("task_image_limit_reached",
                "This task has used ${cap.used()} of its ${cap.maxPerTask} images. Ask the user before making more, or finish with what you have.")
        }
        val response = try {
            port.generate(ImageRequest(prompt, aspect, count, references))
        } catch (e: CancellationException) {
            cap.refund(count)
            throw e
        } catch (e: Throwable) {
            cap.refund(count)
            return failure(e)
        }
        if (response.images.isEmpty()) {
            cap.refund(count)
            return fail("image_bad_response", "The image service returned no image.")
        }
        val stored = try {
            response.images.map { port.store(it, a.str("name", max = 120), prompt) }
        } catch (e: Throwable) {
            return failure(e)
        }
        return result(port, cap, stored, response.images.map { it.bytes }, ctx, response.images.mapNotNull { it.revisedPrompt })
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["prompt"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(200)

    private fun aspects() = ImageAspect.entries.joinToString(" | ") { it.wire }
}

internal class ImageSave(private val ask: Boolean) : AgentTool {
    override val capability: String get() = IMAGE
    override val verb = Verb.ImageSave
    override val label = "保存图片"
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val def = ToolDef("image_save",
        Prompts.tool("image_save"),
        mapOf(
            "path" to str("Workspace image path, e.g. /workspace/out/images/cat.png."),
            "name" to str("Optional file name to save as, without a path (default: the image's own name)."),
        ), listOf("path"))

    override fun available(ctx: ToolContext) = ctx[ImageCapability.KEY]?.let { it.port != null && it.saveEnabled } == true

    /**
     * 保存是改到相册的一步:默认每次都要用户这一次的同意(和 root 命令同一条审批卡)。
     * 用户关掉「每次保存前先问我」后不再问;无人值守/远程/子任务仍由 [com.wangzan101.mika.core.action.ApprovalPolicy] 拒绝。
     */
    override fun approvalRisk(args: JsonObject): ApprovalRisk = if (ask) {
        ApprovalRisk(ActionRisk.Change, ask = ConfirmationRequest(
            IrrevAction.other, "", "保存图片到相册:${nameOf(args)}", null, GALLERY_CONFIRM_APP,
        ))
    } else {
        ApprovalRisk(ActionRisk.Change)
    }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val cap = ctx[ImageCapability.KEY] ?: return fail("image_unavailable", "No image model is configured.")
        val port = cap.port ?: return fail("image_unavailable", "No image model is configured.")
        val path = a.str("path", max = 500)!!
        return try {
            val saved = port.saveToGallery(path, a.str("name", max = 120))
            ToolExecution(okObj {
                put("path", path)
                put("savedAs", saved.savedAs)
                put("uri", saved.uri)
                put("bytes", saved.bytes)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure(e)
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun nameOf(args: JsonObject): String =
        ((args["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: (args["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.substringAfterLast('/')
            ?: "image").take(120)
}

private fun result(
    port: ImagePort,
    cap: ImageCapability,
    stored: List<StoredImage>,
    bytes: List<ByteArray>,
    ctx: ToolContext,
    revised: List<String>,
): ToolExecution = ToolExecution(okObj {
    put("model", port.modelName)
    put("taskUsed", cap.used())
    put("taskLimit", cap.maxPerTask)
    if (revised.isNotEmpty()) put("revisedPrompt", revised.singleOrNull() ?: revised.joinToString("\n"))
    putJsonArray("images") {
        stored.forEach { img ->
            addJsonObject {
                put("path", img.path)
                put("bytes", img.bytes)
                put("width", img.width)
                put("height", img.height)
            }
        }
    }
}, images = if (ctx.config.visionEnabled) bytes else emptyList())

/** 服务商/图片错误 -> 工具结果里稳定的错误码;说明写清楚下一步怎么做。 */
private fun failure(e: Throwable): ToolExecution = when (e) {
    is ImageError.Unsupported -> fail("image_unsupported", e.message ?: "This image model does not support that request.")
    is ImageError.BadImage -> fail("image_bad_response", e.message ?: "The image service returned something that is not an image.")
    is ImageError.TooLarge -> fail("image_too_large", "The image is larger than 20 MB; make a smaller one or choose another model.")
    is ModelError.Auth -> fail("image_auth_failed", "The image service rejected the API key or does not allow this model. Check Settings › Image model.", recoverable = false)
    is ModelError.QuotaExceeded -> fail("image_quota_exceeded", "The image service's quota is used up. Tell the user; do not retry.", recoverable = false)
    is ModelError.RateLimited -> fail("image_rate_limited", "The image service is rate limiting. Wait a little, then try once more if the task still needs it.")
    is ModelError.ContentRefused -> fail("image_content_filtered", "The image service refused this content. Rephrase the request or ask the user; do not try to bypass the filter.")
    is ModelError.ModelNotFound -> fail("image_model_missing", "The image service does not have this model. Check the model name in Settings › Image model.", recoverable = false)
    is ModelError.ModelNotAvailable -> fail("image_model_unavailable", "This account cannot use this image model. Check Settings › Image model or the provider's console.", recoverable = false)
    is ModelError.Network, is ModelError.Proxy -> fail("image_network_failed", "Could not reach the image service. Check the network or proxy, then try again.")
    is ModelError.Server -> fail("image_server_error", "The image service had a server error. Try again later.")
    is ModelError.BadResponse -> fail("image_bad_response", "The image service returned something unreadable (${e.message ?: "bad response"}).")
    else -> fail("image_failed", e.message?.take(300) ?: "Image generation failed.")
}

private fun aspectOf(wire: String?): ImageAspect =
    if (wire == null) ImageAspect.Square
    else ImageAspect.entries.firstOrNull { it.wire == wire }
        ?: throw ArgError("aspect", "must be one of ${ImageAspect.entries.joinToString(" | ") { it.wire }}")
