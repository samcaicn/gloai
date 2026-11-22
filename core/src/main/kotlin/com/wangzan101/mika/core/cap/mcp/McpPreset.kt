package com.wangzan101.mika.core.cap.mcp

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder

/** A versioned, secret-free catalog of declarative MCP connection templates. */
@Serializable
data class McpCatalog(
    val formatVersion: Int,
    val presets: List<McpPreset>,
) {
    companion object {
        const val CurrentFormatVersion = 1
        val Empty = McpCatalog(CurrentFormatVersion, emptyList())
    }
}

@Serializable
data class McpPreset(
    val id: String,
    val name: String,
    val summary: McpCopy,
    val category: String,
    val transport: String,
    val auth: String,
    val endpoint: String = "",
    val command: List<String> = emptyList(),
    val fields: List<McpPresetField> = emptyList(),
    val packageSpec: McpPackageSpec? = null,
    val officialSource: String,
    /** Fixed, non-secret headers. Dynamic values belong in [fields]. */
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class McpPresetField(
    val id: String,
    val label: McpCopy,
    val secret: Boolean,
    val required: Boolean,
    val help: McpCopy? = null,
    /** bearer, header, env, url or argv. Secret fields cannot use url/argv. */
    val target: String,
    /** Header or environment-variable name; empty for bearer, url and argv. */
    val name: String = "",
)

@Serializable(with = McpCopySerializer::class)
data class McpCopy(val values: Map<String, String>) {
    fun resolve(languageTag: String): String {
        val normalized = languageTag.replace('_', '-')
        return values[normalized]
            ?: values[normalized.substringBefore('-')]
            ?: values["en"]
            ?: values["default"]
            ?: values.values.firstOrNull()
            .orEmpty()
    }
}

private object McpCopySerializer : KSerializer<McpCopy> {
    private val delegate = MapSerializer(String.serializer(), String.serializer())
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: McpCopy) = delegate.serialize(encoder, value.values)
    override fun deserialize(decoder: Decoder): McpCopy = McpCopy(delegate.deserialize(decoder))
}

@Serializable
data class McpPackageSpec(
    val ecosystem: String,
    val name: String,
    val version: String,
    val source: String,
)

data class ResolvedMcpPreset(
    val url: String,
    val argv: List<String>,
    val headers: Map<String, String>,
    val environment: Map<String, String>,
    val auth: String,
)

object McpCatalogParser {
    private val json = Json
    private val idPattern = Regex("[a-z0-9][a-z0-9.-]*")
    private val fieldIdPattern = Regex("[A-Za-z][A-Za-z0-9_-]*")
    private val envPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val headerPattern = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val pinnedVersion = Regex("[0-9][A-Za-z0-9.+-]*")
    private val template = Regex("\\{\\{([A-Za-z][A-Za-z0-9_-]*)\\}\\}")
    private val reservedHeaders = setOf(
        "authorization", "accept", "content-type", "content-length", "host",
        "mcp-session-id", "mcp-protocol-version",
    )

    fun parse(text: String): McpCatalog {
        val catalog = try {
            json.decodeFromString<McpCatalog>(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid MCP catalog JSON: ${e.message}", e)
        }
        require(catalog.formatVersion == McpCatalog.CurrentFormatVersion) {
            "Unsupported MCP catalog format ${catalog.formatVersion}"
        }
        val ids = mutableSetOf<String>()
        catalog.presets.forEach { preset ->
            require(ids.add(preset.id)) { "Duplicate MCP preset id ${preset.id}" }
            validatePreset(preset)
        }
        return catalog
    }

    /**
     * 宽松解析:目录级错误(JSON 结构、格式版本)仍失败;单条损坏或重复 ID 的预设跳过、保留可用项。
     * 供随 App 打包的目录加载使用——一条坏数据不阻塞其余条目。
     */
    fun parseLenient(text: String): McpCatalog {
        val catalog = try {
            json.decodeFromString<McpCatalog>(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid MCP catalog JSON: ${e.message}", e)
        }
        require(catalog.formatVersion == McpCatalog.CurrentFormatVersion) {
            "Unsupported MCP catalog format ${catalog.formatVersion}"
        }
        val seen = mutableSetOf<String>()
        val valid = catalog.presets.filter { preset ->
            seen.add(preset.id) && runCatching { validatePreset(preset) }.isSuccess
        }
        return McpCatalog(catalog.formatVersion, valid)
    }

    private fun validatePreset(preset: McpPreset) {
        require(idPattern.matches(preset.id)) { "Invalid MCP preset id" }
        require(preset.name.isNotBlank() && preset.category.isNotBlank()) { "MCP preset name/category is blank" }
        require(preset.summary.values.isNotEmpty() && preset.summary.values.values.all { it.isNotBlank() }) {
            "MCP preset summary is missing"
        }
        require(preset.transport in setOf("streamable-http", "http-sse", "stdio")) {
            "Unsupported MCP transport ${preset.transport}"
        }
        require(preset.auth in setOf("none", "oauth", "fields")) { "Unsupported MCP auth ${preset.auth}" }
        requireOfficialUrl(preset.officialSource, "official source")

        val stdio = preset.transport == "stdio"
        if (stdio) {
            require(preset.endpoint.isEmpty() && preset.command.isNotEmpty()) { "stdio preset needs argv only" }
            require(preset.command.all { it.isNotBlank() && !it.contains('\u0000') }) { "Invalid stdio argv" }
        } else {
            require(preset.endpoint.startsWith("https://") && preset.command.isEmpty()) {
                "Remote preset needs an official HTTPS endpoint"
            }
            require(preset.packageSpec == null) { "Remote preset cannot declare a stdio package" }
        }
        preset.packageSpec?.let { spec ->
            require(stdio) { "Package declared for non-stdio preset" }
            require(spec.ecosystem in setOf("npm", "pypi")) { "Unsupported package ecosystem" }
            require(spec.name.isNotBlank() && pinnedVersion.matches(spec.version)) { "Package version must be exact" }
            requireOfficialUrl(spec.source, "package source")
        }

        val fieldIds = mutableSetOf<String>()
        preset.fields.forEach { field ->
            require(fieldIdPattern.matches(field.id) && fieldIds.add(field.id)) { "Invalid or duplicate field id" }
            require(field.label.values.isNotEmpty() && field.label.values.values.all { it.isNotBlank() }) {
                "Field label is missing"
            }
            require(field.target in setOf("bearer", "header", "env", "url", "argv")) {
                "Unsupported field target ${field.target}"
            }
            if (field.secret) {
                require(field.target in setOf("bearer", "header", "env")) { "Secrets cannot enter URL or argv" }
                require(preset.auth == "fields") { "OAuth/no-auth presets cannot require secret fields" }
            }
            when (field.target) {
                "bearer" -> require(!stdio && field.secret && field.name.isEmpty()) { "Bearer target is invalid" }
                "header" -> {
                    require(!stdio && headerPattern.matches(field.name)) { "Invalid header target" }
                    require(field.secret || field.name.lowercase() !in reservedHeaders) { "Reserved header cannot be replaced" }
                    require(!field.secret || field.name.lowercase() != "authorization") { "Use bearer for Authorization" }
                }
                "env" -> require(stdio && envPattern.matches(field.name)) { "Invalid environment target" }
                "url" -> require(!stdio && !field.secret && field.name.isEmpty()) { "Invalid URL field" }
                "argv" -> require(stdio && !field.secret && field.name.isEmpty()) { "Invalid argv field" }
            }
        }
        val fields = preset.fields.associateBy { it.id }
        validateTemplates(preset.endpoint, fields, "url", requiredTarget = "url")
        preset.command.forEach { validateTemplates(it, fields, "argv", requiredTarget = "argv") }
        preset.fields.filter { it.target == "url" }.forEach { field ->
            require(template.containsMatchIn(preset.endpoint) && template.findAll(preset.endpoint).any { it.groupValues[1] == field.id }) {
                "URL field is not referenced"
            }
        }
        preset.fields.filter { it.target == "argv" }.forEach { field ->
            require(preset.command.any { part -> template.findAll(part).any { it.groupValues[1] == field.id } }) {
                "argv field is not referenced"
            }
        }
        preset.headers.forEach { (name, value) ->
            require(headerPattern.matches(name) && name.lowercase() !in reservedHeaders) { "Invalid fixed header" }
            require(value.isNotBlank() && !value.contains("\r") && !value.contains("\n") && !value.contains("{{")) {
                "Fixed headers must be non-secret literals"
            }
        }
    }

    private fun validateTemplates(
        value: String,
        fields: Map<String, McpPresetField>,
        label: String,
        requiredTarget: String,
    ) {
        val matches = template.findAll(value).toList()
        matches.forEach { match ->
            val field = fields[match.groupValues[1]] ?: throw IllegalArgumentException("Unknown $label template field")
            require(field.target == requiredTarget && field.required) { "$label template uses the wrong field target" }
        }
        require(!value.replace(template, "").contains("{{") && !value.replace(template, "").contains("}}")) {
            "Malformed $label template"
        }
    }

    private fun requireOfficialUrl(value: String, label: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        require(value.isNotBlank() && uri?.scheme == "https" && !uri.host.isNullOrBlank()) {
            "MCP $label must be an HTTPS URL"
        }
    }
}

class McpPresetResolver {
    private val envPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Full resolution: every required field (secret or not) must be present in [values]. */
    fun resolve(preset: McpPreset, values: Map<String, String>): ResolvedMcpPreset =
        resolveImpl(preset, values, includeSecrets = true)

    /**
     * Structural resolution for the connection address and non-secret delivery targets: url, argv,
     * non-secret headers and non-secret environment. Secret fields are ignored (they are supplied
     * later at connect time), so a preset can be built into a config before its secrets exist.
     */
    fun resolveStructure(preset: McpPreset, values: Map<String, String>): ResolvedMcpPreset =
        resolveImpl(preset, values, includeSecrets = false)

    private fun resolveImpl(preset: McpPreset, values: Map<String, String>, includeSecrets: Boolean): ResolvedMcpPreset {
        val fields = preset.fields.associateBy { it.id }
        val unknown = values.keys - fields.keys
        require(unknown.isEmpty()) { "Unknown preset fields: ${unknown.sorted().joinToString()}" }
        fields.values.forEach { field ->
            if (field.required && (includeSecrets || !field.secret)) {
                require(!values[field.id].isNullOrBlank()) { "Missing required preset field ${field.id}" }
            }
        }
        fun value(id: String): String = values[id].orEmpty()
        fun urlValue(id: String): String = URLEncoder.encode(value(id), Charsets.UTF_8).replace("+", "%20")
        fun replace(template: String, encode: Boolean): String = template.replace(Regex("\\{\\{([A-Za-z][A-Za-z0-9_-]*)\\}\\}")) { match ->
            val id = match.groupValues[1]
            if (encode) urlValue(id) else value(id)
        }

        val headers = LinkedHashMap(preset.headers)
        val environment = LinkedHashMap<String, String>()
        fields.values.forEach { field ->
            if (field.secret && !includeSecrets) return@forEach
            val fieldValue = value(field.id)
            if (fieldValue.isEmpty() && !field.required) return@forEach
            when (field.target) {
                "bearer" -> headers["Authorization"] = "Bearer ${headerValue(fieldValue)}"
                "header" -> headers[field.name] = headerValue(fieldValue)
                "env" -> {
                    require(envPattern.matches(field.name) && !fieldValue.contains('\u0000')) { "Invalid environment value" }
                    environment[field.name] = fieldValue
                }
                "url" -> Unit
                "argv" -> require(!fieldValue.contains('\u0000')) { "Invalid argv value" }
            }
        }
        val url = replace(preset.endpoint, encode = true)
        if (url.isNotEmpty()) {
            val uri = runCatching { URI(url) }.getOrNull()
            require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) { "Resolved preset URL is invalid" }
        }
        val argv = preset.command.map { replace(it, encode = false) }
        require(argv.none { it.contains('\u0000') }) { "Resolved argv contains NUL" }
        return ResolvedMcpPreset(url, argv, headers, environment, preset.auth)
    }

    private fun headerValue(value: String): String {
        require(!value.contains('\r') && !value.contains('\n')) { "Header values cannot contain newlines" }
        return value
    }
}
