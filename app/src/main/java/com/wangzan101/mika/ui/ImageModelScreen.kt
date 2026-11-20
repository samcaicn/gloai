package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ImageChoice
import com.wangzan101.mika.settings.ImageModelSettings
import com.wangzan101.mika.settings.ImageProtocol
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.core.model.ImageAspect
import com.wangzan101.mika.core.model.ImageRequest
import kotlinx.coroutines.launch

/**
 * 设置 › 模型 › 图片模型:选服务商、模型、接口,打开总开关;保存到相册另有开关和每次询问。
 * 不在 [com.wangzan101.mika.core.model.ModelRole] 里:图片模型不参与对话、工具调用和上下文。
 */
@Composable
fun ImageModelScreen(store: ProviderStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { ImageModelSettings.get(context) }
    val state by settings.state.collectAsState()
    val providers by store.providers.collectAsState()
    val scope = rememberCoroutineScope()
    val c = MikaTheme.colors
    var model by remember { mutableStateOf(state.choice?.model.orEmpty()) }
    var test by remember { mutableStateOf<String?>(null) }

    Page(tr(R.string.role_image), onBack) {
        Text(tr(R.string.image_hint), Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2)
        GroupCard {
            ListRow(
                null, tr(R.string.image_enable), tr(R.string.image_enable_note), first = true,
                trailing = { MikaSwitch(state.enabled, settings::setEnabled) },
            )
        }

        GroupLabel(tr(R.string.image_provider), Modifier.padding(top = 12.dp))
        if (providers.isEmpty()) {
            FooterNote(tr(R.string.image_no_providers))
        } else {
            GroupCard {
                providers.forEachIndexed { i, p ->
                    RadioRow(
                        ModelsCopy.providerName(p), selected = state.choice?.providerId == p.id, first = i == 0,
                        subtitle = p.baseUrl,
                        onClick = {
                            val protocol = if (p.kind == ProviderConfig.Kind.Gemini) ImageProtocol.GeminiImage else ImageProtocol.OpenAiImages
                            settings.setChoice(ImageChoice(p.id, model, protocol))
                            test = null
                        },
                    )
                }
            }
        }

        state.choice?.let { choice ->
            GroupCard {
                InputRow(
                    tr(R.string.image_model_name), model,
                    onChange = { model = it; settings.setChoice(choice.copy(model = it)); test = null },
                    placeholder = "gpt-image-1 / gemini-3-pro-image-preview", first = true,
                )
            }
            GroupLabel(tr(R.string.image_protocol))
            GroupCard {
                RadioRow(
                    tr(R.string.image_protocol_openai), choice.protocol == ImageProtocol.OpenAiImages, first = true,
                    onClick = { settings.setChoice(choice.copy(protocol = ImageProtocol.OpenAiImages)); test = null },
                )
                RadioRow(
                    tr(R.string.image_protocol_gemini), choice.protocol == ImageProtocol.GeminiImage,
                    onClick = { settings.setChoice(choice.copy(protocol = ImageProtocol.GeminiImage)); test = null },
                )
            }
            GroupCard {
                ListRow(
                    null, tr(R.string.image_supports_edit), first = true,
                    trailing = { MikaSwitch(state.supportsEdit, settings::setSupportsEdit) },
                )
            }
        }

        GroupLabel(tr(R.string.image_limits), Modifier.padding(top = 12.dp))
        GroupCard {
            InputRow(
                tr(R.string.image_max_per_task), state.maxPerTask.toString(),
                onChange = { it.toIntOrNull()?.let(settings::setMaxPerTask) }, placeholder = "8",
                keyboard = KeyboardType.Number, first = true,
            )
            ListRow(
                null, tr(R.string.image_save_enabled), tr(R.string.image_save_enabled_note),
                trailing = { MikaSwitch(state.saveEnabled, settings::setSaveEnabled) },
            )
            ListRow(
                null, tr(R.string.image_ask_before_save),
                trailing = { MikaSwitch(state.askBeforeSave, settings::setAskBeforeSave, enabled = state.saveEnabled) },
            )
            ListRow(
                null, tr(R.string.image_ask_before_generate),
                trailing = { MikaSwitch(state.askBeforeGenerate, settings::setAskBeforeGenerate) },
            )
        }

        MikaButton(
            tr(R.string.image_test),
            onClick = {
                val choice = state.choice ?: return@MikaButton
                val caps = settings.capabilities() ?: return@MikaButton
                val port = store.imageModel(choice, caps) ?: return@MikaButton
                scope.launch {
                    test = tr(R.string.image_testing)
                    test = try {
                        val response = port.generate(ImageRequest("A small red apple on a white table", ImageAspect.Square, 1))
                        if (response.images.isEmpty()) tr(R.string.image_test_failed, "no image") else tr(R.string.image_test_ok)
                    } catch (e: Exception) {
                        tr(R.string.image_test_failed, e.message ?: e.javaClass.simpleName)
                    }
                }
            },
            enabled = state.choice != null && model.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        test?.let { FooterNote(it, Modifier.padding(top = 8.dp)) }
        FooterNote(tr(R.string.image_footer), Modifier.padding(top = 10.dp))
    }
}
