package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.TtsProtocol
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.VoicePlayback
import com.wangzan101.mika.voice.VoicePlayer
import kotlinx.coroutines.launch

/**
 * 设置 › 模型 › 声音模型:选服务商、模型、接口、音色、语速;打开朗读/自动朗读/主动说话。
 * 不在 [com.wangzan101.mika.core.model.ModelRole] 里;和语音转写、图片模型一样独立。
 */
@Composable
fun VoiceModelScreen(store: ProviderStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { VoiceSettings.get(context) }
    val state by settings.state.collectAsState()
    val providers by store.providers.collectAsState()
    val player = remember { VoicePlayer(context) }
    val playback by player.state.collectAsState()
    val scope = rememberCoroutineScope()
    val c = MikaTheme.colors
    var model by remember { mutableStateOf(state.choice?.model.orEmpty()) }
    var voice by remember { mutableStateOf(state.choice?.voice.orEmpty()) }
    var speed by remember { mutableStateOf((state.choice?.speed ?: 1.0f).toString()) }
    var test by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    Page(tr(R.string.role_voice), onBack) {
        Text(tr(R.string.voice_hint), Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2)
        GroupCard {
            ListRow(
                null, tr(R.string.voice_enable), tr(R.string.voice_enable_note), first = true,
                trailing = { MikaSwitch(state.enabled, settings::setEnabled) },
            )
        }

        GroupLabel(tr(R.string.voice_provider), Modifier.padding(top = 12.dp))
        if (providers.isEmpty()) {
            FooterNote(tr(R.string.voice_no_providers))
        } else {
            GroupCard {
                providers.forEachIndexed { i, p ->
                    RadioRow(
                        ModelsCopy.providerName(p), selected = state.choice?.providerId == p.id, first = i == 0,
                        subtitle = p.baseUrl,
                        onClick = {
                            val protocol = if (p.kind == ProviderConfig.Kind.Gemini) TtsProtocol.GeminiTts else TtsProtocol.OpenAiSpeech
                            val speedValue = speed.toFloatOrNull()?.coerceIn(0.25f, 4.0f) ?: 1.0f
                            settings.setChoice(VoiceChoice(p.id, model, protocol, voice, speedValue))
                            test = null
                        },
                    )
                }
            }
        }

        state.choice?.let { choice ->
            GroupCard {
                InputRow(
                    tr(R.string.voice_model_name), model,
                    onChange = { model = it; settings.setChoice(choice.copy(model = it)); test = null },
                    placeholder = "gpt-4o-mini-tts / gemini-2.5-flash-preview-tts", first = true,
                )
                InputRow(
                    tr(R.string.voice_name), voice,
                    onChange = { voice = it; settings.setChoice(choice.copy(voice = it)); test = null },
                    placeholder = "alloy / Kore",
                )
                InputRow(
                    tr(R.string.voice_speed), speed,
                    onChange = {
                        speed = it
                        it.toFloatOrNull()?.let { v -> settings.setChoice(choice.copy(speed = v.coerceIn(0.25f, 4.0f))) }
                        test = null
                    },
                    placeholder = "1.0", keyboard = KeyboardType.Decimal,
                )
            }
            GroupLabel(tr(R.string.voice_protocol))
            GroupCard {
                RadioRow(
                    tr(R.string.voice_protocol_openai), choice.protocol == TtsProtocol.OpenAiSpeech, first = true,
                    onClick = { settings.setChoice(choice.copy(protocol = TtsProtocol.OpenAiSpeech)); test = null },
                )
                RadioRow(
                    tr(R.string.voice_protocol_gemini), choice.protocol == TtsProtocol.GeminiTts,
                    onClick = { settings.setChoice(choice.copy(protocol = TtsProtocol.GeminiTts)); test = null },
                )
            }
        }

        GroupCard {
            InputRow(
                tr(R.string.voice_max_chars), state.maxCharsPerSpeak.toString(),
                onChange = { it.toIntOrNull()?.let(settings::setMaxChars) }, placeholder = "2000",
                keyboard = KeyboardType.Number, first = true,
            )
        }

        MikaButton(
            tr(R.string.voice_test),
            onClick = {
                val choice = state.choice ?: return@MikaButton
                val caps = settings.capabilities() ?: return@MikaButton
                val tts = store.ttsModel(choice, caps) ?: return@MikaButton
                scope.launch {
                    test = tr(R.string.voice_testing)
                    test = try {
                        val response = tts.synthesize(
                            TtsRequest(
                                "你好，我是 Mika。这是一段声音测试。",
                                voice.ifBlank { null },
                                speed.toFloatOrNull()?.coerceIn(0.25f, 4.0f),
                                "wav",
                            ),
                        )
                        player.play(response.bytes, response.mime)
                        tr(R.string.voice_test_ok)
                    } catch (e: Exception) {
                        tr(R.string.voice_test_failed, e.message ?: e.javaClass.simpleName)
                    }
                }
            },
            enabled = state.choice != null && model.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        playback.let { p ->
            val line = when (p) {
                VoicePlayback.Playing -> tr(R.string.voice_playing)
                VoicePlayback.Loading -> tr(R.string.voice_loading)
                is VoicePlayback.Error -> tr(R.string.voice_play_failed, p.message)
                VoicePlayback.Idle -> null
            }
            line?.let { FooterNote(it, Modifier.padding(top = 8.dp)) }
        }
        test?.let { FooterNote(it, Modifier.padding(top = 8.dp)) }
        FooterNote(tr(R.string.voice_footer), Modifier.padding(top = 10.dp))
    }
}
