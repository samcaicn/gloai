package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.decision.DecisionConfig
import com.wangzan101.mika.core.decision.DecisionModel
import com.wangzan101.mika.core.decision.DecisionProtocol
import com.wangzan101.mika.settings.DecisionSettings
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

enum class JevPreset(val endpoint: String?, val model: String?, val protocol: DecisionProtocol?) {
    Google(DecisionConfig.GOOGLE_ENDPOINT, DecisionConfig.GOOGLE_MODEL, DecisionProtocol.Google),
    TypeSafe(DecisionConfig.TYPE_SAFE_ENDPOINT, DecisionConfig.TYPE_SAFE_MODEL, DecisionProtocol.SystemOne),
    OpenRouter(DecisionConfig.OPEN_ROUTER_ENDPOINT, DecisionConfig.OPEN_ROUTER_MODEL, DecisionProtocol.SystemOne),
    Custom(null, null, null);

    companion object {
        fun of(config: DecisionConfig) = entries.firstOrNull { it.endpoint == config.endpoint && it.model == config.model } ?: Custom
    }
}

/** This probe only sends this fixed synthetic message; no conversation, screen or saved memory is consulted. */
internal suspend fun testDecisionConnection(model: DecisionModel): Boolean {
    val result = model.choose(JsonPrimitive("Hello!"), "Classify this synthetic test message.",
        linkedMapOf("greeting" to "A greeting", "other" to "Anything else"), null)
    return result.choice == "greeting"
}

@Composable
fun JevSettingsScreen(settings: DecisionSettings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var config by remember(settings) { mutableStateOf(settings.state.value) }
    // Secrets deliberately stay out of rememberSaveable and the Android saved-state bundle.
    var key by remember(settings) {
        mutableStateOf(
            settings.apiKey().ifBlank {
                if (JevPreset.of(settings.state.value) == JevPreset.Google) settings.storedGeminiApiKey() else ""
            },
        )
    }
    var preset by remember(settings) { mutableStateOf(JevPreset.of(config)) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    BackHandler(onBack = onBack)

    fun edit(next: DecisionConfig) {
        if (!busy) {
            val endpointChanged = next.endpoint != config.endpoint
            val modelChanged = next.model != config.model
            if (endpointChanged) key = ""
            if (endpointChanged || modelChanged) preset = JevPreset.of(next)
            config = if (endpointChanged || modelChanged) {
                next.copy(protocol = DecisionConfig.inferredProtocol(next.endpoint, next.model))
            } else {
                next
            }
            notice = null
        }
    }
    val aiPlatformUnsupported = DecisionConfig.isAiPlatformEndpoint(config.endpoint)
    val canSave = settings.canSave(config, key) && !aiPlatformUnsupported
    val canTest = settings.canTest(config, key) && !aiPlatformUnsupported
    JevSettingsContent(config, key, preset, busy, notice,
        canSave = canSave,
        canTest = canTest,
        aiPlatformUnsupported = aiPlatformUnsupported,
        onConfig = ::edit,
        onKey = { if (!busy) { key = it; notice = null } },
        onPreset = { p ->
            if (!busy) {
                if (p == JevPreset.Google) {
                    if (p.endpoint != config.endpoint || key.isBlank()) {
                        key = settings.storedGeminiApiKey()
                    }
                } else if (p.endpoint != null && p.endpoint != config.endpoint) {
                    key = ""
                }
                preset = p
                val nextEndpoint = p.endpoint ?: config.endpoint
                val nextModel = p.model ?: config.model
                config = config.copy(
                    endpoint = nextEndpoint,
                    model = nextModel,
                    protocol = p.protocol ?: DecisionConfig.inferredProtocol(nextEndpoint, nextModel),
                )
                notice = null
            }
        },
        onSave = {
            if (!busy) {
                busy = true
                notice = null
                val next = config
                val apiKey = key
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { settings.save(next, apiKey) }
                        config = settings.state.value
                        key = apiKey.trim().ifBlank {
                            if (JevPreset.of(settings.state.value) == JevPreset.Google) settings.storedGeminiApiKey() else ""
                        }
                        notice = R.string.jev_saved
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice = R.string.jev_save_failed }
                    finally { busy = false }
                }
            }
        },
        onTest = {
            if (!busy) {
                busy = true
                notice = null
                val next = config
                val apiKey = key
                scope.launch {
                    try {
                        val ok = withContext(Dispatchers.IO) {
                            testDecisionConnection(settings.testModel(next, apiKey))
                        }
                        notice = if (ok) R.string.jev_test_success else R.string.jev_test_failed
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice = R.string.jev_test_failed }
                    finally { busy = false }
                }
            }
        }, onBack = onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JevSettingsContent(
    config: DecisionConfig, apiKey: String, preset: JevPreset, busy: Boolean, notice: Int?,
    canSave: Boolean = DecisionSettings.valid(config, apiKey) && !DecisionConfig.isAiPlatformEndpoint(config.endpoint),
    canTest: Boolean = DecisionSettings.valid(config.copy(enabled = true), apiKey) && !DecisionConfig.isAiPlatformEndpoint(config.endpoint),
    aiPlatformUnsupported: Boolean = DecisionConfig.isAiPlatformEndpoint(config.endpoint),
    onConfig: (DecisionConfig) -> Unit, onKey: (String) -> Unit, onPreset: (JevPreset) -> Unit,
    onSave: () -> Unit, onTest: () -> Unit, onBack: () -> Unit,
) {
    var showKey by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.jev_title), onBack, Modifier.imePadding()) {
        GroupCard {
            ListRow(MikaIcons.Route, stringResource(R.string.jev_enabled), first = true) {
                MikaSwitch(config.enabled, { onConfig(config.copy(enabled = it)) }, Modifier.testTag("jev-enabled"), enabled = !busy)
            }
        }
        FooterNote(stringResource(R.string.jev_privacy), Modifier.testTag("jev-privacy"))
        GroupLabel(stringResource(R.string.jev_provider))
        FlowRow(Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JevPreset.entries.forEach { p ->
                val label = when (p) {
                    JevPreset.Google -> "Google"
                    JevPreset.TypeSafe -> "TypeSafe"
                    JevPreset.OpenRouter -> "OpenRouter"
                    JevPreset.Custom -> stringResource(R.string.preset_custom)
                }
                SelectChip(label, preset == p, { if (!busy) onPreset(p) }, Modifier.testTag("jev-preset-${p.name}"))
            }
        }
        GroupCard {
            InputRow(stringResource(R.string.jev_endpoint), config.endpoint, { if (!busy) onConfig(config.copy(endpoint = it)) },
                DecisionConfig.GOOGLE_ENDPOINT, first = true, keyboard = KeyboardType.Uri, modifier = Modifier.testTag("jev-endpoint"))
            InputRow(stringResource(R.string.jev_model), config.model, { if (!busy) onConfig(config.copy(model = it)) },
                DecisionConfig.GOOGLE_MODEL, modifier = Modifier.testTag("jev-model"))
            InputRow("API Key", apiKey, { if (!busy) onKey(it) }, stringResource(R.string.model_key_hint),
                keyboard = KeyboardType.Password, secret = !showKey, modifier = Modifier.testTag("jev-key")) {
                TextButton({ showKey = !showKey }) { Text(stringResource(if (showKey) R.string.hide else R.string.show)) }
            }
        }
        val endpointNote = if (config.protocol == DecisionProtocol.Google) R.string.jev_google_endpoint_note else R.string.jev_endpoint_note
        FooterNote(stringResource(endpointNote))
        if (!canSave) {
            FooterNote(
                stringResource(if (aiPlatformUnsupported) R.string.jev_aiplatform_unsupported else R.string.jev_invalid),
                Modifier.testTag("jev-invalid"),
                color = MikaTheme.colors.warn,
            )
        }
        FooterNote(stringResource(R.string.jev_apply_note))
        MikaButton(stringResource(R.string.save), onSave, Modifier.fillMaxWidth().testTag("jev-save"), enabled = canSave && !busy)
        Spacer(Modifier.height(10.dp))
        MikaButton(stringResource(R.string.jev_test), onTest,
            Modifier.fillMaxWidth().testTag("jev-test"), kind = ButtonKind.Secondary, enabled = canTest && !busy)
        Spacer(Modifier.height(10.dp))
        FooterNote(stringResource(R.string.jev_test_note))
        notice?.let { FooterNote(stringResource(it), Modifier.testTag("jev-result"),
            color = if (it == R.string.jev_test_failed || it == R.string.jev_save_failed) MikaTheme.colors.warn else MikaTheme.colors.ok) }
    }
}
