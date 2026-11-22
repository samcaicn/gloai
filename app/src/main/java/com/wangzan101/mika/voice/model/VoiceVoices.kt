package com.wangzan101.mika.voice.model

/**
 * Voices each protocol offers, so pages show a list instead of a free-text voice id.
 * A saved voice outside the list (an older custom value, a cloned voice) is kept and listed first.
 */
object VoiceVoices {
    fun tts(protocol: TtsProtocol): List<String> = when (protocol) {
        TtsProtocol.AndroidSystem -> emptyList()
        TtsProtocol.OpenAiSpeech -> listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar")
        TtsProtocol.GeminiTts -> listOf("Kore", "Puck", "Charon", "Zephyr", "Fenrir", "Leda", "Orus", "Aoede")
        else -> emptyList()
    }

    /** The voices [model] offers: its own list when the vendor registry gave one, else the protocol's. */
    fun tts(model: VoiceModel): List<String> {
        val binding = model.tts ?: return emptyList()
        return binding.voices.ifEmpty { tts(binding.protocol) }
    }

    fun realtime(protocol: RealtimeProtocol): List<String> = when (protocol) {
        RealtimeProtocol.VolcDuplex -> listOf(
            "zh_female_vv_jupiter_bigtts",
            "zh_female_xiaohe_jupiter_bigtts",
            "zh_male_yunzhou_jupiter_bigtts",
            "zh_male_xiaotian_jupiter_bigtts",
        )
        RealtimeProtocol.OpenAiRealtime -> listOf("marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse")
    }

    /** [known] with [current] in front when it is set and not one of them. */
    fun withCurrent(known: List<String>, current: String): List<String> {
        val saved = current.trim()
        return if (saved.isEmpty() || saved in known) known else listOf(saved) + known
    }

    /** Short display name: the Volc ids carry locale and engine affixes users never need to read. */
    fun label(voice: String): String {
        val match = VOLC_ID.matchEntire(voice) ?: return voice
        return match.groupValues[2].replaceFirstChar { it.uppercase() }
    }

    private val VOLC_ID = Regex("""zh_(female|male)_([a-z]+)_(?:jupiter|uranus|moon|mars)_bigtts""")
}
