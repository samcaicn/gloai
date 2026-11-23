package com.wangzan101.mika.voice.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.TtsProtocol
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.settings.normalizeVoiceSpeed
import com.wangzan101.mika.settings.ttsModel
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.voice.VoicePlayback
import com.wangzan101.mika.voice.VoicePlayer
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceVoices
import com.wangzan101.mika.voice.model.settingsProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.wangzan101.mika.settings.ui.models.Page

/**
 * 设置 › 模型 › 声音模型:选服务商、模型、接口、音色、语速;打开朗读/自动朗读/主动说话。
 * 不在 [com.wangzan101.mika.core.model.ModelRole] 里;和语音转写、图片模型一样独立。
 */
@Composable
fun VoiceModelScreen(store: ProviderStore, onBack: () -> Unit, onOpenServices: (() -> Unit)? = null) {
    val context = LocalContext.current
    val settings = VoiceSettings.get(context)
    val voiceModels = remember { VoiceModelEnvironment.get(context) }
    if (voiceModels.tts.migrated) {
        MigratedVoiceModelContent(voiceModels, settings, store, onBack, onOpenServices)
    } else {
        VoiceModelContent(store, settings, onBack)
    }
}

@Composable
internal fun VoiceModelContent(store: ProviderStore, settings: VoiceSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by settings.state.collectAsState()
    val providers by store.providers.collectAsState()
    val choice = state.choice
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

        if (providers.isEmpty()) {
            FooterNote(tr(R.string.voice_no_providers))
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

/** 迁移后只选择目录里声明了 TTS 的模型;音色、语速、流式等使用偏好仍由 [VoiceSettings] 保存。 */
@Composable
private fun MigratedVoiceModelContent(
    voiceModels: VoiceModelEnvironment,
    settings: VoiceSettings,
    store: ProviderStore,
    onBack: () -> Unit,
    onOpenServices: (() -> Unit)?,
) {
    val context = LocalContext.current
    val voiceVm = rememberVoiceCatalogViewModel(voiceModels)
    val providers by store.providers.collectAsState()
    var voicesOpen by remember { mutableStateOf(false) }
    val state by settings.state.collectAsState()
    val catalog by voiceModels.catalog.state.collectAsState()
    val tts = voiceModels.tts
    val selection = catalog.selections.tts
    val ttsModels = catalog.models.filter { it.tts != null }
    val selectedModel = selection?.let { ref ->
        ttsModels.firstOrNull { it.serviceId == ref.serviceId && it.id == ref.modelId }
    }
    val player = remember { VoicePlayer(context) }
    val playback by player.state.collectAsState()
    var speed by remember(selection) { mutableStateOf(state.speed.toString()) }
    var test by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player, selection) { onDispose { player.stop() } }

    val previewScope = key(selection) { rememberCoroutineScope() }
    Page(tr(R.string.role_voice), onBack) {
        GroupCard {
            ListRow(
                null, tr(R.string.voice_enable), tr(R.string.voice_enable_note), first = true,
                trailing = { MikaSwitch(state.enabled, settings::setEnabled) },
            )
        }

        val choices = voiceOptions(VoiceCapability.TTS, catalog, providers)
        if (choices.isEmpty()) {
            FooterNote(tr(R.string.voice_no_tts_models))
        } else {
            GroupLabel(tr(R.string.voice_choices_label))
            VoiceOptionList(
                choices, selection, tr(R.string.voice_try_tts), voiceVm,
                onPick = { choice ->
                    tts.select(voiceVm.ensureInCatalog(choice))
                    test = null
                },
                onTest = { choice -> voiceVm.tryChoice(choice, tr(R.string.voice_try_sample)) },
            )
        }
        onOpenServices?.let { VoiceMoreServices(it) }

        selectedModel?.let { model ->
            GroupCard {
                val voices = VoiceVoices.tts(model)
                val pickable = voices.isNotEmpty() || model.tts?.protocol.let { it != null && it != com.wangzan101.mika.voice.model.TtsProtocol.AndroidSystem }
                if (pickable) {
                    ListRow(null, tr(R.string.voice_voice), first = true, onClick = { voicesOpen = true },
                        modifier = Modifier.testTag("tts-voice")) {
                        RowValue(state.voice.ifBlank { tr(R.string.voice_voice_default) }.let { VoiceVoices.label(model, it) })
                        RowChevron()
                    }
                }
                if (voicesOpen) {
                    VoicePickerSheet(
                        VoiceVoices.withCurrent(voices, state.voice),
                        onPick = { settings.setVoice(it); test = null },
                        onDismiss = { voicesOpen = false },
                        model = model,
                    )
                }
                InputRow(
                    tr(R.string.voice_speed), speed, first = !pickable,
                    onChange = {
                        speed = it
                        it.toFloatOrNull()?.takeIf { v -> v.isFinite() }?.let { v -> settings.setSpeed(normalizeVoiceSpeed(v)) }
                    },
                    placeholder = "1.0", keyboard = KeyboardType.Decimal,
                    modifier = Modifier.onFocusChanged { if (!it.hasFocus) speed = state.speed.toString() },
                )
                if (model.tts?.protocol == com.wangzan101.mika.voice.model.TtsProtocol.OpenAiSpeech) {
                    ListRow(
                        null, tr(R.string.voice_streaming), tr(R.string.voice_streaming_note),
                        trailing = {
                            MikaSwitch(state.streaming, onCheckedChange = {
                                settings.setStreaming(it)
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
                val ref = selection ?: return@MikaButton
                val protocol = selectedModel?.tts?.protocol ?: return@MikaButton
                val caps = settings.capabilities(protocol.settingsProtocol() ?: return@MikaButton)
                val model = try {
                    tts.createTts(context, ref, caps)
                } catch (_: VoiceModelError) {
                    return@MikaButton
                }
                previewScope.launch {
                    test = tr(R.string.voice_testing)
                    try {
                        player.play(
                            model,
                            TtsRequest(
                                "你好，我是 Mika。这是一段声音测试。",
                                state.voice.ifBlank { null },
                                state.speed,
                                "wav",
                            ),
                            streaming = state.streaming,
                        ) { test = tr(R.string.voice_test_ok) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        test = tr(R.string.voice_test_failed, e.message ?: e.javaClass.simpleName)
                    }
                }
            },
            enabled = selectedModel != null,
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
