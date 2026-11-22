package com.wangzan101.mika.media

import android.content.Context
import com.wangzan101.mika.core.model.StreamingTtsModel
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.settingsProtocol

/** Check the current master switch before resolving a provider or synthesizing speech. */
internal suspend fun readAloud(
    text: String,
    settings: VoiceSettings,
    model: (VoiceChoice, TtsCapabilities) -> TtsModel?,
    play: suspend (TtsModel, List<TtsRequest>, Boolean) -> Unit,
) {
    val state = settings.state.value
    if (!state.enabled) return
    val choice = state.choice ?: return
    val limited = clipAtGraphemeBoundary(text, state.maxCharsPerSpeak)
    if (limited.isBlank()) return
    val caps = settings.capabilities() ?: return
    val tts = model(choice, caps) ?: return
    val requests = speechRequests(limited, choice.voice, choice.speed, choice.streaming, tts)
    play(tts, requests, choice.streaming)
}

/**
 * 迁移后的朗读入口:模型从目录解析、经同一控制器/注册表创建,音色/语速/流式偏好留在 [settings]。
 * 未选模型、关闭总开关或文本为空时静默跳过;解析失败抛给调用方显示。
 */
internal suspend fun readAloudMigrated(
    text: String,
    settings: VoiceSettings,
    context: Context,
    tts: TtsSelectionController,
    play: suspend (TtsModel, List<TtsRequest>, Boolean) -> Unit,
) {
    val state = settings.state.value
    if (!state.enabled) return
    val ref = tts.current() ?: return
    val protocol = tts.requireModel(ref).model.tts?.protocol?.settingsProtocol() ?: return
    val caps = settings.capabilities(protocol)
    val limited = clipAtGraphemeBoundary(text, state.maxCharsPerSpeak)
    if (limited.isBlank()) return
    val model = tts.createTts(context, ref, caps)
    val requests = speechRequests(limited, state.voice, state.speed, state.streaming, model)
    play(model, requests, state.streaming)
}

private fun speechRequests(
    limited: String,
    voice: String,
    speed: Float,
    streaming: Boolean,
    tts: TtsModel,
): List<TtsRequest> {
    val segments = if (streaming && speed == 1f && tts is StreamingTtsModel) {
        splitForSpeech(limited)
    } else {
        listOf(limited)
    }
    return segments.map { segment ->
        TtsRequest(segment, voice.ifBlank { null }, speed, "wav")
    }
}

private val GRAPHEME = Regex("\\X")
private val PARAGRAPH_BREAK = Regex("(?:\\r?\\n[ \t]*){2,}")

private fun clipAtGraphemeBoundary(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val end = GRAPHEME.findAll(text).takeWhile { it.range.last < maxChars }.lastOrNull()?.range?.last ?: -1
    return text.substring(0, end + 1)
}

// Budget for roughly 76 CJK characters or 228 Latin letters, with room for pauses.
private const val SPEECH_CHUNK_BUDGET = 228
private const val MIN_PARAGRAPH_WEIGHT = SPEECH_CHUNK_BUDGET * 2 / 3
private val CJK_SCRIPTS = setOf(
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO,
)

private fun speechWeight(grapheme: String): Int {
    val codePoint = grapheme.codePointAt(0)
    val block = Character.UnicodeBlock.of(codePoint)
    val wide = Character.UnicodeScript.of(codePoint) in CJK_SCRIPTS ||
        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    return if (wide) 3 else 1
}

/** Prefer complete sentences and list lines when a request would be too long. */
private fun splitForSpeech(text: String): List<String> {
    val graphemes = GRAPHEME.findAll(text).toList()
    val weights = graphemes.map { speechWeight(it.value) }
    if (weights.sum() <= SPEECH_CHUNK_BUDGET) return listOf(text)
    val paragraphEnds = PARAGRAPH_BREAK.findAll(text).map { it.range.last }.toSet()
    val parts = mutableListOf<String>()
    var start = 0
    while (start < graphemes.size) {
        var end = start
        var weight = 0
        var paragraphEnd = 0
        var sentenceEnd = 0
        var hasSpeech = false
        var clauseEnd = 0
        var wordEnd = 0
        while (end < graphemes.size) {
            val grapheme = graphemes[end].value
            weight += weights[end]
            if (weight > SPEECH_CHUNK_BUDGET) break
            hasSpeech = hasSpeech || grapheme.isNotBlank()
            end++
            if (hasSpeech && weight >= MIN_PARAGRAPH_WEIGHT && graphemes[end - 1].range.last in paragraphEnds) {
                paragraphEnd = end
            }
            // A boundary far from the budget can leave most of the request unused.
            if (weight < SPEECH_CHUNK_BUDGET / 2) continue
            when {
                grapheme.contains('\n') || grapheme.contains('\r') -> sentenceEnd = end
                grapheme in "。！？!?…" -> sentenceEnd = end
                grapheme == "." && graphemes.getOrNull(end)?.value?.isBlank() == true -> sentenceEnd = end
                grapheme in "，、；：,;:" -> clauseEnd = end
                grapheme.isBlank() -> wordEnd = end
            }
        }
        val boundary = when {
            // Keep a short closing paragraph in the same request when it fits.
            end == graphemes.size -> end
            paragraphEnd != 0 -> paragraphEnd
            sentenceEnd > start -> sentenceEnd
            clauseEnd > start -> clauseEnd
            wordEnd > start -> wordEnd
            else -> end
        }
        val part = text.substring(graphemes[start].range.first, graphemes[boundary - 1].range.last + 1)
        if (part.isNotBlank()) parts += part
        start = boundary
    }
    return parts
}
