package com.wangzan101.mika.voice.model

/**
 * Voices each protocol offers, so pages show a list instead of a free-text voice id.
 * A saved voice outside the list (an older custom value, a cloned voice) is kept and listed first.
 */
object VoiceVoices {
    fun tts(protocol: TtsProtocol): List<String> = when (protocol) {
        TtsProtocol.AndroidSystem -> emptyList()
        TtsProtocol.OpenAiSpeech -> listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar")
        TtsProtocol.GeminiTts -> listOf(
            "Kore", "Puck", "Charon", "Zephyr", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe", "Enceladus",
            "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
            "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia",
            "Sadaltager", "Sulafat",
        )
        // 通义千问 TTS 官方音色表(非实时与实时同一套)
        TtsProtocol.BailianQwenTts -> listOf(
            "Cherry", "Serena", "Ethan", "Chelsie", "Momo", "Vivian", "Moon", "Maia", "Kai", "Nofish", "Bella", "Jennifer",
            "Ryan", "Katerina", "Aiden", "Eldric", "Mia", "Mochi", "Bellona", "Vincent", "Bunny", "Neil", "Elias", "Arthur",
            "Nini", "Seren", "Pip", "Stella", "Bodega", "Sonrisa", "Alek", "Dolce", "Sohee", "Ono", "Lenn", "Emilien",
            "Andre", "Radio", "Jada", "Dylan", "Li", "Marcus", "Roy", "Peter", "Sunny", "Eric", "Rocky", "Kiki",
        )
        else -> emptyList()
    }

    /** The voices [model] offers: its own list when the vendor registry gave one, else the protocol's. */
    fun tts(model: VoiceModel): List<String> {
        val binding = model.tts ?: return emptyList()
        return binding.voices.ifEmpty { tts(binding) }
    }

    /** The published list for a binding; Volcano's depends on the synthesis resource. */
    private fun tts(binding: TtsBinding): List<String> = when (binding.protocol) {
        TtsProtocol.VolcSpeech -> if (binding.region.startsWith("seed-tts-1")) VolcVoices.seedTts1 else VolcVoices.seedTts2
        else -> tts(binding.protocol)
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

    /** The vendor's own name for [voice] when the list gave one, else [label]. */
    fun label(model: VoiceModel?, voice: String): String = model?.tts?.voiceNames?.get(voice)?.takeIf { it.isNotBlank() } ?: label(voice)

    /** Short display name: the Volc ids carry locale and engine affixes users never need to read. */
    fun label(voice: String): String {
        VolcVoices.names[voice]?.let { return it }
        val match = VOLC_ID.matchEntire(voice) ?: return voice
        return match.groupValues[2].replaceFirstChar { it.uppercase() }
    }

    private val VOLC_ID = Regex("""zh_(female|male)_([a-z]+)_(?:jupiter|uranus|moon|mars)_bigtts""")
}
