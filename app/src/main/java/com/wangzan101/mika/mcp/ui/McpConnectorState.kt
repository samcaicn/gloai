package com.wangzan101.mika.mcp.ui

import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.core.cap.mcp.McpPreset
import com.wangzan101.mika.settings.McpServerConfig
import com.wangzan101.mika.settings.McpServerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 引导式接入的连接阶段。目录浏览与连接编排的状态机,UI 只读这份状态、不自行拼装 URL/header/argv。 */
sealed interface McpConnectStage {
    /** 正在浏览目录(或未打开任何预设)。 */
    data object Browsing : McpConnectStage

    /** 已打开某个预设:填字段 / 授权 / 确认 stdio 包 / 等待连接。 */
    data class Form(
        val preset: McpPreset,
        val values: Map<String, String>,
        val secretValues: Map<String, String>,
        val connecting: Boolean,
        val needsPackageConsent: Boolean,
        val error: String?,
    ) : McpConnectStage

    /** 连接成功并启用;tools 来自真实工具目录。 */
    data class Connected(val config: McpServerConfig, val tools: List<String>) : McpConnectStage
}

/**
 * 把目录预设编排成现有 [McpServerStore] 的连接路径:填字段 →(必要时授权/确认包)→ 连接并读取工具。
 * 秘密只经 store 的加密存储,不进这份状态之外的任何日志或普通配置。
 */
class McpConnectorState(
    private val store: McpServerStore,
    private val catalog: McpCatalog,
    private val scope: CoroutineScope,
) {
    private val _stage = MutableStateFlow<McpConnectStage>(McpConnectStage.Browsing)
    val stage: StateFlow<McpConnectStage> = _stage.asStateFlow()

    /** 进行中的连接/授权任务;测试用 join 等待落定,UI 用 [cancel] 中止。 */
    internal var inFlight: Job? = null
        private set

    /** 打开预设,只呈现该预设声明的字段;stdio 含包且尚未确认时进入待确认。重新打开同一预设保留未提交的非秘密草稿,秘密草稿丢弃。 */
    fun openPreset(id: String) {
        cancel()
        val preset = catalog.presets.find { it.id == id } ?: return
        val needsConsent = preset.packageSpec?.let { !store.packageAccepted(it) } == true
        val existing = _stage.value as? McpConnectStage.Form
        _stage.value = if (existing?.preset?.id == id) {
            existing.copy(connecting = false, error = null, needsPackageConsent = needsConsent, secretValues = emptyMap())
        } else {
            McpConnectStage.Form(
                preset = preset,
                values = emptyMap(),
                secretValues = emptyMap(),
                connecting = false,
                needsPackageConsent = needsConsent,
                error = null,
            )
        }
    }

    /** 更新字段:秘密字段与普通字段分开存放,秘密永不进入普通配置。 */
    fun updateField(id: String, value: String) {
        val form = _stage.value as? McpConnectStage.Form ?: return
        val isSecret = form.preset.fields.any { it.id == id && it.secret }
        _stage.value = if (isSecret) {
            form.copy(secretValues = form.secretValues + (id to value))
        } else {
            form.copy(values = form.values + (id to value))
        }
    }

    /** 确认 stdio 包(名 + 精确版本 + 来源)后继续连接。 */
    fun confirmPackage() {
        val form = _stage.value as? McpConnectStage.Form ?: return
        form.preset.packageSpec?.let { store.acceptPackage(it) }
        connect()
    }

    /** 无认证 / API Key / stdio(已确认包)的连接入口。OAuth 用 [authorize]。 */
    fun connect() {
        val form = _stage.value as? McpConnectStage.Form ?: return
        val preset = form.preset
        val spec = preset.packageSpec
        if (spec != null && !store.packageAccepted(spec)) {
            _stage.value = form.copy(needsPackageConsent = true, error = null)
            return
        }
        launch(form) {
            store.connectPreset(preset.id, form.values, form.secretValues).fold(
                onSuccess = { result ->
                    store.presetConfig(preset.id, form.values).fold(
                        onSuccess = { config -> McpConnectStage.Connected(config, result.tools) },
                        onFailure = { form.copy(connecting = false, error = it.message) },
                    )
                },
                onFailure = { form.copy(connecting = false, error = it.message) },
            )
        }
    }

    /** OAuth:保存配置后走现有发现/注册/PKCE 流程;回调拿到令牌后自动读取工具目录。重试/重新授权复用同一 config id,不产生重复条目。 */
    fun authorize(openBrowser: (String) -> Unit) {
        val form = _stage.value as? McpConnectStage.Form ?: return
        val preset = form.preset
        launch(form) {
            val config = store.presetConfig(preset.id, form.values).getOrElse {
                return@launch form.copy(connecting = false, error = it.message)
            }
            val saved = store.savePreset(config).getOrElse {
                return@launch form.copy(connecting = false, error = it.message)
            }
            store.authorize(saved, openBrowser).getOrElse {
                store.setEnabled(saved.id, false)
                return@launch form.copy(connecting = false, error = it.message)
            }
            store.test(saved).fold(
                onSuccess = { McpConnectStage.Connected(saved, it.tools) },
                onFailure = {
                    store.setEnabled(saved.id, false)
                    form.copy(connecting = false, error = it.message)
                },
            )
        }
    }

    /** 失败后重试:走原预设的当前字段与已确认状态。 */
    fun retry() = connect()

    /**
     * 只替换 [secrets] 中非空字段的秘密;空值保留原秘密。只写加密存储,不进普通配置。
     * 未在预设快照里声明的字段被忽略,防止把任意键写成秘密。
     */
    fun replaceSecrets(config: McpServerConfig, secrets: Map<String, String>) {
        val declared = config.presetSnapshot?.fields?.filter { it.secret }?.map { it.id }?.toSet()
        secrets.forEach { (fieldId, value) ->
            if (value.isNotBlank() && (declared == null || fieldId in declared)) {
                store.setSecret(config.id, fieldId, value)
            }
        }
    }

    /** 取消进行中的连接/下载;草稿(非秘密输入)保留在 Form 里。 */
    fun cancel() {
        inFlight?.cancel()
        inFlight = null
        val form = _stage.value as? McpConnectStage.Form ?: return
        if (form.connecting) _stage.value = form.copy(connecting = false)
    }

    /** 返回目录浏览。 */
    fun close() {
        cancel()
        _stage.value = McpConnectStage.Browsing
    }

    private fun launch(form: McpConnectStage.Form, block: suspend () -> McpConnectStage) {
        inFlight = scope.launch {
            _stage.value = form.copy(connecting = true, error = null)
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 缺失必填字段等预检错误也以失败态呈现,不冒泡崩溃。
                form.copy(connecting = false, error = e.message ?: e.javaClass.simpleName)
            }
            _stage.value = if (result is McpConnectStage.Form) {
                // 连接期间字段仍可编辑;落定时把当前草稿(非秘密值 + 秘密值 + 包确认态)带回来,别用点击瞬间的旧快照覆盖编辑。
                val current = _stage.value as? McpConnectStage.Form
                result.copy(
                    values = current?.values ?: result.values,
                    secretValues = current?.secretValues ?: result.secretValues,
                    needsPackageConsent = current?.needsPackageConsent ?: result.needsPackageConsent,
                )
            } else {
                result
            }
        }
    }
}
