package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.model.ImageAspect
import com.wangzan101.mika.core.model.ImageCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 图片服务的接口形态。存名字，只能往后加、不能改名或删，旧设置才读得回来。 */
enum class ImageProtocol(val wire: String) {
    /** OpenAI Images：`/images/generations` 与 `/images/edits`。 */
    OpenAiImages("openai-images"),

    /** Gemini 图像模型：generateContent + responseModalities。 */
    GeminiImage("gemini-image");

    companion object {
        fun of(wire: String?): ImageProtocol? = entries.firstOrNull { it.wire == wire }
    }
}

/** 用户为图片生成选的服务商、模型和接口；Key 仍由 [ProviderStore] 按服务商保存，不复制到这里。 */
data class ImageChoice(val providerId: String, val model: String, val protocol: ImageProtocol)

/**
 * 图片相关的设置，存在 [FeatureFlags] 那份设置里，一个键、一段 JSON；读不出来一律当默认值。
 * [enabled] 是总开关，默认关；没有选模型时工具也不提供。保存到相册另有一个开关，默认关；
 * [askBeforeSave] 默认开，每次保存都走现有的审批卡；[askBeforeGenerate] 默认关，避免每张图都打断。
 */
data class ImageSettingsState(
    val enabled: Boolean = false,
    val choice: ImageChoice? = null,
    val maxPerTask: Int = DEFAULT_MAX_PER_TASK,
    val saveEnabled: Boolean = false,
    val askBeforeSave: Boolean = true,
    val askBeforeGenerate: Boolean = false,
    /** 服务商/模型不支持改图时用户在设置里关掉；关掉后 image_edit 不发给模型。 */
    val supportsEdit: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_PER_TASK = 8
        const val MAX_PER_TASK_LIMIT = 50
    }
}

class ImageModelSettings(private val prefs: SharedPreferences) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ImageSettingsState> = _state.asStateFlow()

    fun setEnabled(on: Boolean) = update { copy(enabled = on) }

    fun setChoice(choice: ImageChoice?) = update { copy(choice = choice) }

    fun setMaxPerTask(n: Int) = update {
        copy(maxPerTask = n.coerceIn(1, ImageSettingsState.MAX_PER_TASK_LIMIT))
    }

    fun setSaveEnabled(on: Boolean) = update { copy(saveEnabled = on) }

    fun setAskBeforeSave(on: Boolean) = update { copy(askBeforeSave = on) }

    fun setAskBeforeGenerate(on: Boolean) = update { copy(askBeforeGenerate = on) }

    fun setSupportsEdit(on: Boolean) = update { copy(supportsEdit = on) }

    /** 这次选的服务商/模型能做什么。能力按协议给初值，用户可再关掉改图。 */
    fun capabilities(): ImageCapabilities? {
        val choice = _state.value.choice ?: return null
        val base = when (choice.protocol) {
            ImageProtocol.OpenAiImages -> ImageCapabilities(
                supportsEdit = true, maxReferences = 4, maxCount = 4,
                aspects = setOf(ImageAspect.Square, ImageAspect.Portrait, ImageAspect.Landscape),
            )
            ImageProtocol.GeminiImage -> ImageCapabilities(
                supportsEdit = true, maxReferences = 4, maxCount = 4,
                aspects = ImageAspect.entries.toSet(),
            )
        }
        return if (_state.value.supportsEdit) base else base.copy(supportsEdit = false, maxReferences = 0)
    }

    private fun update(block: ImageSettingsState.() -> ImageSettingsState) {
        val next = _state.value.block()
        prefs.edit().putString(KEY, encode(next).toString()).apply()
        _state.value = next
    }

    private fun read(): ImageSettingsState {
        val raw = prefs.getString(KEY, null) ?: return ImageSettingsState()
        return runCatching { decode(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() ?: ImageSettingsState()
    }

    private fun encode(state: ImageSettingsState): JsonObject = buildJsonObject {
        put("enabled", state.enabled)
        put("maxPerTask", state.maxPerTask)
        put("saveEnabled", state.saveEnabled)
        put("askBeforeSave", state.askBeforeSave)
        put("askBeforeGenerate", state.askBeforeGenerate)
        put("supportsEdit", state.supportsEdit)
        state.choice?.let { choice ->
            put("providerId", choice.providerId)
            put("model", choice.model)
            put("protocol", choice.protocol.wire)
        }
    }

    private fun decode(obj: JsonObject): ImageSettingsState {
        fun bool(key: String, fallback: Boolean) = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: fallback
        val providerId = (obj["providerId"] as? JsonPrimitive)?.contentOrNull
        val model = (obj["model"] as? JsonPrimitive)?.contentOrNull
        val protocol = ImageProtocol.of((obj["protocol"] as? JsonPrimitive)?.contentOrNull)
        val choice = if (providerId.isNullOrBlank() || model.isNullOrBlank() || protocol == null) null
        else ImageChoice(providerId, model, protocol)
        return ImageSettingsState(
            enabled = bool("enabled", false),
            choice = choice,
            maxPerTask = ((obj["maxPerTask"] as? JsonPrimitive)?.intOrNull ?: ImageSettingsState.DEFAULT_MAX_PER_TASK)
                .coerceIn(1, ImageSettingsState.MAX_PER_TASK_LIMIT),
            saveEnabled = bool("saveEnabled", false),
            askBeforeSave = bool("askBeforeSave", true),
            askBeforeGenerate = bool("askBeforeGenerate", false),
            supportsEdit = bool("supportsEdit", true),
        )
    }

    companion object {
        private const val KEY = "image_settings"

        fun get(context: Context) = ImageModelSettings(
            context.applicationContext.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE),
        )
    }
}
