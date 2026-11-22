package com.wangzan101.mika.settings

import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.core.cap.mcp.McpCatalogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpCatalogAssetsTest {
    private fun bundled(): McpCatalog = McpCatalogAssets.parse(File("src/main/assets/mcp/catalog.json").readText())

    @Test fun bundledCatalogHasAtLeast200UniqueUsablePresets() {
        val catalog = bundled()
        assertTrue("need at least 200 usable presets, got ${catalog.presets.size}", catalog.presets.size >= 200)
        assertEquals(catalog.presets.size, catalog.presets.map { it.id }.toSet().size)
    }

    @Test fun everyPresetHasAllLocaleCopyAndValidOfficialSource() {
        val locales = McpCatalogAssets.REQUIRED_LOCALES
        for (preset in bundled().presets) {
            for (locale in locales) {
                assertTrue("${preset.id}: summary missing $locale", preset.summary.values.containsKey(locale))
            }
            preset.fields.forEach { field ->
                for (locale in locales) {
                    assertTrue("${preset.id}.${field.id}: label missing $locale", field.label.values.containsKey(locale))
                }
            }
            if (preset.transport != "stdio") {
                assertTrue("${preset.id}: official source must be https", preset.officialSource.startsWith("https://"))
            }
            preset.packageSpec?.let { spec ->
                assertTrue("${preset.id}: package source must be https", spec.source.startsWith("https://"))
                assertTrue("${preset.id}: package version must be exact", spec.version.isNotBlank())
            }
        }
    }

    @Test fun filesystemFamilyStdioPresetsDeclareAServeDirectory() {
        // server-filesystem / server-everything 启动必须给「允许访问的目录」参数,否则进程直接退出。
        val byId = bundled().presets.associateBy { it.id }
        for (id in listOf("filesystem", "everything")) {
            val preset = byId.getValue(id)
            assertEquals("$id: transport", "stdio", preset.transport)
            assertTrue("$id must declare a directory argument, got ${preset.command}",
                preset.command.size >= 2 && preset.command.drop(1).any { it.startsWith("/") })
        }
    }

    @Test fun catalogLoaderSkipsOnlyMalformedEntries() {
        fun preset(id: String, transport: String = "streamable-http", endpoint: String = "https://example.com/mcp") =
            """
            {"id":"$id","name":"Demo","summary":{"en":"Demo","zh":"示例","zh-Hant":"示例","ar":"عرض","fa":"نمونه","ja":"例","ko":"예","es":"Ejemplo","pt":"Exemplo","ru":"Пример","id":"Contoh","tr":"Örnek"},
             "category":"dev","transport":"$transport","auth":"none","endpoint":"$endpoint","officialSource":"https://example.com/docs"}
            """.trimIndent()

        val text = """{"formatVersion":1,"presets":[${preset("good")},${preset("bad", transport = "bogus")},${preset("good")}]}"""
        val catalog = McpCatalogAssets.parse(text)
        assertEquals(listOf("good"), catalog.presets.map { it.id })
    }

    @Test fun strictParseRejectsButLenientSkipsMalformed() {
        val text = """{"formatVersion":1,"presets":[{"id":"bad","name":"x","summary":{"en":"x"},"category":"c","transport":"bogus","auth":"none","officialSource":"https://e/x"}]}"""
        val lenient = McpCatalogParser.parseLenient(text)
        assertEquals(0, lenient.presets.size)
    }
}
