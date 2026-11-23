package com.wangzan101.mika.voice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceVendorsTest {
    private fun fill(vendor: VoiceVendor): Map<String, String> = vendor.fields.associate { field ->
        field.key to when {
            field.choices.isNotEmpty() -> field.choices.first()
            field.key == VoiceVendors.F_REGION -> "eastasia"
            field.key == VoiceVendors.F_WORKSPACE -> ""
            else -> " ${field.key}-value "
        }
    }

    @Test fun `every vendor builds a plan with trimmed credentials and models`() {
        for (vendor in VoiceVendors.all) {
            val plan = vendor.plan(fill(vendor))
            assertTrue(vendor.id, plan.models.isNotEmpty())
            assertTrue(vendor.id, plan.credentials?.configured == true)
            assertTrue(vendor.id, vendor.owns(plan.service.id))
            assertTrue(vendor.id, plan.models.all { it.serviceId == plan.service.id })
            assertEquals(vendor.id, plan.models.size, plan.models.map { it.id }.toSet().size)
        }
    }

    @Test fun `vendor ids are unique and lookups work`() {
        assertEquals(VoiceVendors.all.size, VoiceVendors.all.map { it.id }.toSet().size)
        assertNotNull(VoiceVendors.forService("azure:westus"))
        assertEquals("tencent", VoiceVendors.forService("tencent")?.id)
    }

    @Test fun `services earlier versions made belong to their vendor but are not rebuilt by its form`() {
        val bailian = VoiceVendors.forService("stt-bailian:beijing")
        assertEquals("bailian", bailian?.id)
        assertNotNull(bailian?.lister)
        assertEquals(false, bailian?.builds("stt-bailian:beijing"))
        assertEquals(true, bailian?.builds("bailian:singapore"))
        assertEquals(VoicePresets.VOLC_SERVICE, VoiceVendors.forService("stt-doubao")?.id)
        assertEquals(null, VoiceVendors.forService("stt-bailianx"))
    }

    @Test fun `every vendor model has an adapter for each capability`() {
        val registry = VoiceAdapterRegistry()
        for (vendor in VoiceVendors.all) for (model in vendor.preview) {
            val resolved = ResolvedVoiceModel(
                VoiceService(model.serviceId, vendor.name, VoiceAccount.None), model,
                ResolvedAccount.None, "",
            )
            if (model.stt != null) registry.stt(resolved)
            if (model.tts != null) registry.tts(resolved)
            if (model.realtime != null) registry.realtime(resolved)
        }
    }

    @Test fun `multi-field vendors map onto the three secret slots`() {
        val tencent = VoiceVendors.byId("tencent")!!.plan(mapOf(VoiceVendors.F_SECRET_ID to "sid", VoiceVendors.F_SECRET_KEY to "skey"))
        assertEquals("sid", tencent.credentials?.appId)
        assertEquals("skey", tencent.credentials?.apiKey)
        val xfyun = VoiceVendors.byId("xfyun")!!.plan(mapOf(
            VoiceVendors.F_APP_ID to "a", VoiceVendors.F_API_KEY to "k", VoiceVendors.F_API_SECRET to "s",
        ))
        assertEquals("s", xfyun.credentials?.accessKey)
    }

    @Test fun `missing required fields and bad azure region are rejected`() {
        assertThrows(VoiceModelError::class.java) { VoiceVendors.byId("openai")!!.plan(emptyMap()) }
        assertThrows(VoiceModelError::class.java) {
            VoiceVendors.byId("azure")!!.plan(mapOf(VoiceVendors.F_API_KEY to "k", VoiceVendors.F_REGION to "east asia"))
        }
    }

    @Test fun `volc accepts either a key or app id with token`() {
        val volc = VoiceVendors.byId("volc")!!
        assertEquals("k", volc.plan(mapOf(VoiceVendors.F_API_KEY to "k")).credentials?.apiKey)
        assertEquals("t", volc.plan(mapOf(VoiceVendors.F_APP_ID to "a", VoiceVendors.F_ACCESS_TOKEN to "t")).credentials?.accessKey)
        assertThrows(VoiceModelError::class.java) { volc.plan(mapOf(VoiceVendors.F_APP_ID to "a")) }
    }
}
