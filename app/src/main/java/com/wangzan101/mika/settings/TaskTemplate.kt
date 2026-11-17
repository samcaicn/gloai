package com.wangzan101.mika.settings

/** Explicitly enabled saved tasks only. Values are substituted once, never interpreted as more fields. */
object TaskTemplate {
    const val MAX_FIELDS = 12
    const val NAME_MAX = 40
    private val marker = Regex("\\{\\{([^{}\\r\\n]{1,40})\\}\\}")

    fun validName(name: String): Boolean = name.isNotBlank() && name.length <= NAME_MAX &&
        name.none { it == '{' || it == '}' || it == '\r' || it == '\n' }

    fun fields(prompt: String): List<String> = marker.findAll(prompt)
        .map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()

    fun render(prompt: String, values: Map<String, String>): String? {
        val fields = fields(prompt)
        if (fields.size > MAX_FIELDS || fields.any { values[it].isNullOrBlank() }) return null
        return marker.replace(prompt) { match ->
            values[match.groupValues[1].trim()]?.trim() ?: match.value
        }
    }
}
