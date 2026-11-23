package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.tool.ScriptRunner
import java.io.File

/**
 * 提示词钩子:把拼好的系统提示词依次过每个启用了 `promptTransform` 的插件脚本(stdin 进原提示词、stdout 出改写后)。
 * 单个脚本 3 秒超时或非零退出就用原提示词继续;`MIKA_MODEL` 进脚本环境,按模型作用域。结果按(内容 + 模型)缓存。
 */
class PluginPromptHook(
    private val registry: PluginRegistry,
    private val runner: ScriptRunner,
    private val modelName: () -> String,
    private val env: () -> Map<String, String> = { emptyMap() },
    private val timeoutMs: Long = 3000,
) {
    private val cache = mutableMapOf<String, String>()

    suspend fun transform(prompt: String): String {
        val key = "$prompt\u0000${modelName()}"
        cache[key]?.let { return it }
        var out = prompt
        for (record in registry.enabledPlugins()) {
            val m = registry.manifest(record.id) ?: continue
            val scriptPath = m.promptTransform ?: continue
            val dir = registry.pluginDir(record.id) ?: continue
            val result = runner.runStdin(File(dir, scriptPath), out, env() + ("MIKA_MODEL" to modelName()), timeoutMs)
            if (!result.timedOut && result.exitCode == 0) out = result.stdout
        }
        cache[key] = out
        return out
    }
}
