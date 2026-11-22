package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.TtsProtocol
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.settings.normalizeVoiceSpeed
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.voice.VoicePlayback
import com.wangzan101.mika.voice.VoicePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 设置 › 模型 › 声音模型:选服务商、模型、接口、音色、语速;打开朗读/自动朗读/主动说话。
 * 不在 [com.wangzan101.mika.core.model.ModelRole] 里;和语音转写、图片模型一样独立。
 */
@Composable
fun VoiceModelScreen(store: ProviderStore, onBack: () -> Unit) {
    VoiceModelContent(store, VoiceSettings.get(LocalContext.current), onBack)
}

@Composable
internal fun VoiceModelContent(store: ProviderStore, settings: VoiceSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by settings.state.collectAsState()
    val providers by store.providers.collectAsState()
    val configs by store.configs.collectAsState()
    val ttsProviders = providers.filter { it.ttsModels.isNotEmpty() }
    val savedChoice = state.choice
    val selectedProvider = ttsProviders.firstOrNull { it.id == savedChoice?.providerId }
    val choice = savedChoice?.takeIf { selectedProvider != null && it.model in selectedProvider.ttsModels }
    val player = remember { VoicePlayer(context) }
    val playback by player.state.collectAsState()
    var speed by remember(choice?.providerId, choice?.model) { mutableStateOf((choice?.speed ?: 1.0f).toString()) }
    var test by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player, choice?.providerId, choice?.model) { onDispose { player.stop() } }

    fun saveChoice(next: VoiceChoice) {
        settings.setChoice(next)
        test = null
    }

    val previewScope = key(choice?.providerId, choice?.model) { rememberCoroutineScope() }
    Page(tr(R.string.role_voice), onBack) {
        GroupCard {
            ListRow(
                null, tr(R.string.voice_enable), tr(R.string.voice_enable_note), first = true,
                trailing = { MikaSwitch(state.enabled, settings::setEnabled) },
            )
        }

        if (ttsProviders.isEmpty()) {
            FooterNote(tr(if (providers.isEmpty()) R.string.voice_no_providers else R.string.voice_no_tts_models))
        } else {
            ModelPickerRows(
                groups = ttsProviders.map { provider ->
                    provider to ModelOptions.ofProvider(provider, configs, includeTts = true)
                        .filter { it.id in provider.ttsModels }
                },
                pick = { providerId, model ->
                    val provider = ttsProviders.firstOrNull { it.id == providerId && model in it.ttsModels }
                    if (provider != null) {
                        val previous = savedChoice?.takeIf { it.providerId == provider.id }
                        val protocol = if (provider.kind == ProviderConfig.Kind.Gemini) TtsProtocol.GeminiTts else TtsProtocol.OpenAiSpeech
                        saveChoice(previous?.copy(model = model) ?: VoiceChoice(provider.id, model, protocol))
                    }
                },
                isSelected = { providerId, model -> choice?.providerId == providerId && choice.model == model },
                isTts = { _, _ -> true },
            )
        }

        choice?.let {
            GroupCard {
                InputRow(
                    tr(R.string.voice_name), choice.voice, onChange = { saveChoice(choice.copy(voice = it)) },
                    placeholder = "alloy / Kore", first = true,
                )
                InputRow(
                    tr(R.string.voice_speed), speed,
                    onChange = {
                        speed = it
                        it.toFloatOrNull()?.takeIf { v -> v.isFinite() }?.let { v -> saveChoice(choice.copy(speed = normalizeVoiceSpeed(v))) }
                    },
                    placeholder = "1.0", keyboard = KeyboardType.Decimal,
                    modifier = Modifier.onFocusChanged { if (!it.hasFocus) speed = choice.speed.toString() },
                )
            }
            GroupLabel(tr(R.string.voice_protocol))
            GroupCard {
                RadioRow(
                    tr(R.string.voice_protocol_openai), choice.protocol == TtsProtocol.OpenAiSpeech, first = true,
                    onClick = { saveChoice(choice.copy(protocol = TtsProtocol.OpenAiSpeech)) },
                )
                RadioRow(
                    tr(R.string.voice_protocol_gemini), choice.protocol == TtsProtocol.GeminiTts,
                    onClick = { saveChoice(choice.copy(protocol = TtsProtocol.GeminiTts)) },
                )
                if (choice.protocol == TtsProtocol.OpenAiSpeech) {
                    ListRow(
                        null, tr(R.string.voice_streaming), tr(R.string.voice_streaming_note),
                        trailing = {
                            MikaSwitch(choice.streaming, onCheckedChange = {
                                settings.setChoice(choice.copy(streaming = it))
                                test = null
                            })
                        },
                    )
                }
            }
        }

        GroupCard {
            ListRow(
                null, tr(R.string.voice_auto_read), first = true,
                trailing = { MikaSwitch(state.autoRead, settings::setAutoRead) },
            )
            ListRow(
                null, tr(R.string.voice_speak_tool),
                trailing = { MikaSwitch(state.speakTool, settings::setSpeakTool) },
            )
            ListRow(
                null, tr(R.string.voice_save_enabled), tr(R.string.voice_save_note),
                trailing = { MikaSwitch(state.saveEnabled, settings::setSaveEnabled) },
            )
            ListRow(
                null, tr(R.string.voice_ask_before_save),
                trailing = { MikaSwitch(state.askBeforeSave, settings::setAskBeforeSave, enabled = state.saveEnabled) },
            )
            InputRow(
                tr(R.string.voice_max_chars), state.maxCharsPerSpeak.toString(),
                onChange = { it.toIntOrNull()?.let(settings::setMaxChars) }, placeholder = "2000",
                keyboard = KeyboardType.Number,
            )
        }

        MikaButton(
            tr(R.string.voice_test),
            onClick = {
                val choice = choice ?: return@MikaButton
                val caps = settings.capabilities() ?: return@MikaButton
                val tts = store.ttsModel(choice, caps) ?: return@MikaButton
                previewScope.launch {
                    test = tr(R.string.voice_testing)
                    try {
                        player.play(
                            tts,
                            TtsRequest(
                                "你好，我是 Mika。这是一段声音测试。",
                                choice.voice.ifBlank { null },
                                choice.speed,
                                "wav",
                            ),
                            streaming = choice.streaming,
                        ) { test = tr(R.string.voice_test_ok) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        test = tr(R.string.voice_test_failed, e.message ?: e.javaClass.simpleName)
                    }
                }
            },
            enabled = choice != null,
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
