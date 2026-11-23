package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.screen.observation.Place
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 调用参数与「在哪」的纯逻辑:坏参数按空对象处理,站点只从成功的网页读取结果里推。 */
class CallJsonTest {
    @Test fun parseArgsReturnsAnObjectOrAnEmptyOne() {
        assertEquals(1, (parseArgs("""{"a":1}""")["a"] as JsonPrimitive).content.toInt())
        assertEquals(emptyMap<String, kotlinx.serialization.json.JsonElement>(), parseArgs(""))
        assertEquals(emptyMap<String, kotlinx.serialization.json.JsonElement>(), parseArgs("   "))
        assertEquals(emptyMap<String, kotlinx.serialization.json.JsonElement>(), parseArgs("{broken"))
        assertEquals(emptyMap<String, kotlinx.serialization.json.JsonElement>(), parseArgs("[1,2]"))
        assertEquals(emptyMap<String, kotlinx.serialization.json.JsonElement>(), parseArgs("\"text\""))
        assertEquals(emptyMap<String, kotlinx.serialization.json.JsonElement>(), parseArgs("null"))
    }

    @Test fun placeOfPrefersTheOuterFieldsAndFallsBackToScreen() {
        val screen = buildJsonObject { put("currentApp", "com.screen.app"); put("currentSite", "screen.example") }
        assertEquals(Place.app("com.outer.app"), placeOf(buildJsonObject {
            put("currentApp", "com.outer.app"); put("screen", screen)
        }))
        assertEquals(Place.app("com.screen.app"), placeOf(buildJsonObject { put("screen", screen) }))
        // App 先于 Site,即使站点写在外层、App 只写在 screen 里
        assertEquals(Place.app("com.screen.app"), placeOf(buildJsonObject {
            put("currentSite", "outer.example"); put("screen", screen)
        }))
        assertEquals(Place.site("screen.example"), placeOf(buildJsonObject {
            put("screen", buildJsonObject { put("currentSite", "screen.example") })
        }))
        assertNull(placeOf(JsonObject(emptyMap())))
    }

    @Test fun placeAfterUsesTheUrlOnlyForSuccessfulSiteVerbs() {
        val ok = buildJsonObject { put("ok", true); put("url", "https://WWW.Example.COM/a?b=1") }
        assertEquals(Place.site("example.com"), placeAfter(Verb.Observe, ok))
        assertEquals(Place.site("example.com"), placeAfter(Verb.Navigate, ok))
        assertEquals(Place.site("example.com"), placeAfter(Verb.ReadPage, ok))
        assertNull(placeAfter(Verb.Wait, ok))
        assertNull(placeAfter(Verb.Observe, buildJsonObject { put("ok", false); put("url", "https://example.com") }))
        assertNull(placeAfter(Verb.Observe, buildJsonObject { put("url", "https://example.com") }))
        assertNull(placeAfter(Verb.Observe, ok - "url"))
    }

    @Test fun placeAfterKeepsAnExplicitPlace() {
        val both = buildJsonObject {
            put("currentApp", "com.tencent.mm")
            put("ok", true)
            put("url", "https://example.com")
        }
        assertEquals(Place.app("com.tencent.mm"), placeAfter(Verb.Observe, both))
    }

    private operator fun JsonObject.minus(key: String) = JsonObject(filterKeys { it != key })
}
