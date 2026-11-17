package com.wangzan101.mika.plugin

import com.wangzan101.mika.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 插件 manifest(Schema 1);P1a 只接受技能,P1b/P2/P3 再逐步放开工具、页面与设置。 */
@Serializable
data class PluginManifest(
    val schema: Int = 0,
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val kind: String = KIND_CAPABILITY,
    val minApp: String? = null,
    val author: String? = null,
    val description: String? = null,
    val permissions: List<String> = emptyList(),
    val tools: List<PluginToolDecl> = emptyList(),
    val skills: List<PluginSkillDecl> = emptyList(),
    val pages: List<PluginPageDecl> = emptyList(),
    val settings: List<PluginSettingDecl> = emptyList(),
    val promptTransform: String? = null,
) {
    /**
     * 校验 manifest;返回问题编码(空表示通过)。P1a 的 fail closed:只接受只带技能的插件,
     * 任何权限、工具、页面或设置都拒绝安装。[currentAppVersion] 用于 `minApp` 比较。
     */
    fun validate(currentAppVersion: String = BuildConfig.VERSION_NAME): List<String> = buildList {
        if (schema != SCHEMA) add(ERR_SCHEMA)
        if (!ID.matches(id)) add(ERR_ID)
        if (name.isBlank()) add(ERR_NAME)
        if (!SemVer.isValid(version)) add(ERR_VERSION)
        if (kind != KIND_CAPABILITY) add(ERR_KIND)
        minApp?.let {
            if (!SemVer.isValid(it)) add(ERR_MIN_APP)
            else if (SemVer.compare(it, currentAppVersion) > 0) add(ERR_MIN_APP_TOO_HIGH)
        }
        for (p in permissions) if (p !in ALLOWED_PLUGIN_PERMISSIONS) add(ERR_INVALID_PERMISSION)
        if (pages.isNotEmpty() || settings.isNotEmpty()) add(ERR_ONLY_SKILLS)
        val toolNames = mutableSetOf<String>()
        for (t in tools) {
            if (!validScriptPath(t.script)) add(ERR_SCRIPT_NOT_SH)
            if (!toolNames.add(t.name)) add(ERR_TOOL)
        }
        if (promptTransform != null && !validScriptPath(promptTransform)) add(ERR_INVALID_PROMPT_TRANSFORM)
        val seen = mutableSetOf<String>()
        for (s in skills) {
            if (!SKILL_NAME.matches(s.name) || !seen.add(s.name)) add(ERR_SKILL)
            // 技能目录只能是包内 skills/<name>,不允许 ..、绝对路径或别名
            if (s.path != "skills/${s.name}" || s.path.contains("..")) add(ERR_SKILL_PATH)
        }
    }

    companion object {
        const val SCHEMA = 1
        const val KIND_CAPABILITY = "capability"

        /** 全局唯一的插件 id;同时用作 `files/plugins/<id>/` 目录名,正则保证拼不出路径。 */
        val ID = Regex("^[a-z0-9]+(\\.[a-z0-9_-]+)+$")
        val SKILL_NAME = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

        const val ERR_SCHEMA = "schema_unsupported"
        const val ERR_ID = "invalid_id"
        const val ERR_NAME = "missing_name"
        const val ERR_VERSION = "invalid_version"
        const val ERR_KIND = "unsupported_kind"
        const val ERR_MIN_APP = "invalid_min_app"
        const val ERR_MIN_APP_TOO_HIGH = "app_too_old"
        const val ERR_PERMISSIONS = "permissions_unsupported"
        const val ERR_ONLY_SKILLS = "only_skills_supported"
        const val ERR_SCRIPT_NOT_SH = "script_not_sh"
        const val ERR_INVALID_PERMISSION = "invalid_permission"
        const val ERR_INVALID_PROMPT_TRANSFORM = "invalid_prompt_transform"
        const val ERR_TOOL = "invalid_tool"
        const val ERR_SKILL = "invalid_skill"
        const val ERR_SKILL_PATH = "invalid_skill_path"

        private val json = Json { ignoreUnknownKeys = true }

        /** 未知字段忽略(向前兼容);格式错误抛 [kotlinx.serialization.SerializationException]。 */
        fun parse(text: String): PluginManifest = json.decodeFromString(text)
    }
}

@Serializable
data class PluginToolDecl(
    val name: String = "",
    val description: String = "",
    val script: String? = null,
    val timeoutSec: Int = 60,
    val risk: String? = null,
    val annotations: Map<String, Boolean> = emptyMap(),
    val maxCostUnits: Int? = null,
    val inputSchema: JsonObject? = null,
)

@Serializable
data class PluginSkillDecl(val name: String = "", val path: String = "", val display: String? = null)

@Serializable
data class PluginPageDecl(val id: String = "", val entry: String = "", val title: String? = null)

@Serializable
data class PluginSettingDecl(val key: String = "", val type: String = "string", val label: String? = null)

/** 注册表里的一条;manifest 是内容真源,这里存启用状态与安装信息。 */
@Serializable
data class PluginRecord(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean = true,
    val installedAt: Long = 0,
    val skillNames: List<String> = emptyList(),
    val description: String? = null,
    val permissions: List<String> = emptyList(),
)

/** 语义化版本的解析与比较(只用于 `minApp`)。 */
internal object SemVer {
    private val RE = Regex(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?" +
            "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
    )

    fun isValid(version: String): Boolean = RE.matches(version)

    /** -1/0/1;构建元数据不参与比较,预发布版本低于同版本正式版。 */
    fun compare(a: String, b: String): Int {
        val (ac, ap) = parts(a) ?: return a.compareTo(b)
        val (bc, bp) = parts(b) ?: return a.compareTo(b)
        for (i in 0 until 3) ac[i].compareTo(bc[i]).let { if (it != 0) return it.sign() }
        if (ap == null && bp == null) return 0
        if (ap == null) return 1
        if (bp == null) return -1
        val ai = ap.split('.')
        val bi = bp.split('.')
        for (i in 0 until minOf(ai.size, bi.size)) {
            val x = ai[i]
            val y = bi[i]
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val c = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c.sign()
        }
        return ai.size.compareTo(bi.size).sign()
    }

    private fun parts(version: String): Pair<List<Int>, String?>? {
        val m = RE.matchEntire(version) ?: return null
        return listOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) to
            m.groupValues[4].takeIf { it.isNotEmpty() }
    }

    private fun Int.sign(): Int = if (this < 0) -1 else if (this > 0) 1 else 0
}

/** 插件脚本目前能声明的权限白名单;不在这里的一律拒装。 */
private val ALLOWED_PLUGIN_PERMISSIONS = setOf("run-scripts", "rewrite-prompt")

/** 脚本路径必须落在插件包内且是 `.sh`:非空、以 `.sh` 结尾、非绝对路径、无反斜杠、不含 `..` 或空段。 */
private fun validScriptPath(p: String?): Boolean =
    p != null && p.endsWith(".sh") && !p.startsWith("/") && !p.contains('\\') &&
        p.split('/').none { it == ".." || it.isEmpty() }
