package com.wangzan101.mika.voice.model

import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.DoubaoProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class VoicePresetsTest {
    private fun manager(catalog: VoiceCatalogStore) = VoiceAccountManager(
        catalog,
        VoiceCredentialStore(
            object : VoiceCredentialBackend {
                val values = mutableMapOf<String, String>()
                override fun read(secretId: String): String? = values[secretId]
                override fun write(secretId: String, value: String): Boolean { values[secretId] = value; return true }
                override fun delete(secretId: String) { values.remove(secretId) }
            },
            object : KeyCipher {
                override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
                override fun decrypt(data: ByteArray): ByteArray = data.reversedArray()
            },
        ),
    )

    @Test fun `volc takes one key for both input and calls and honours the plan`() {
        val plan = VoicePresets.plan(VoicePreset.Volc(apiKey = " k ", sttPlan = VolcSttPlan.Concurrent))
        assertEquals("k", plan.credentials?.apiKey)
        val stt = plan.models.single { it.stt != null }
        assertEquals(DoubaoProtocol.CONCURRENT, stt.stt?.resourceId)
        val realtime = plan.models.single { it.realtime != null }
        assertEquals(VolcRealtimeConfig.MODEL, realtime.id)
        assertEquals(RealtimeProtocol.VolcDuplex, realtime.realtime?.protocol)
    }

    @Test fun `volc accepts app id and token, and only what is ticked`() {
        val plan = VoicePresets.plan(VoicePreset.Volc(appId = "a", accessToken = "t", realtime = false))
        assertEquals("a", plan.credentials?.appId)
        assertEquals(listOf(VoiceCapability.STT), plan.refs.map { it.capability })
        assertThrows(VoiceModelError.IncompleteConfig::class.java) { VoicePresets.plan(VoicePreset.Volc(appId = "a")) }
        assertThrows(VoiceModelError.IncompleteConfig::class.java) {
            VoicePresets.plan(VoicePreset.Volc(apiKey = "k", stt = false, realtime = false))
        }
    }

    @Test fun `bailian is one key and a region`() {
        val plan = VoicePresets.plan(VoicePreset.Bailian("k", BailianRegion.Singapore))
        assertEquals("bailian:singapore", plan.service.id)
        assertEquals(BailianRegion.Singapore.endpoint(""), plan.models.single().stt?.endpoint)
    }

    @Test fun `openai compatible merges capabilities on one model id and derives the realtime socket`() {
        val plan = VoicePresets.plan(
            VoicePreset.OpenAiCompatible("", "https://api.example.com/v1/", "k", sttModel = "m", ttsModel = "m", realtimeModel = "rt"),
        )
        assertEquals("api.example.com", plan.service.name)
        assertEquals(2, plan.models.size)
        val m = plan.models.first { it.id == "m" }
        assertEquals(setOf(VoiceCapability.STT, VoiceCapability.TTS), m.capabilities)
        assertEquals("https://api.example.com/v1", m.tts?.endpoint)
        assertEquals("wss://api.example.com/v1/realtime", plan.models.first { it.id == "rt" }.realtime?.endpoint)
        assertEquals("wss://h:8443/realtime", VoicePresets.realtimeEndpoint(URI("https://h:8443")))
        assertThrows(VoiceModelError.IncompleteConfig::class.java) {
            VoicePresets.plan(VoicePreset.OpenAiCompatible("", "http://api.example.com", "k", sttModel = "m"))
        }
    }

    @Test fun `self hosted needs no account`() {
        val plan = VoicePresets.plan(VoicePreset.SelfHostedStt("192.168.1.5:10095"))
        assertEquals(VoiceAccount.None, plan.service.account)
        assertNull(plan.credentials)
        assertTrue(plan.models.single().stt?.endpoint.orEmpty().startsWith("ws://192.168.1.5:10095"))
    }

    @Test fun `saving a preset again replaces its models instead of adding copies`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        val manager = manager(catalog)
        VoicePresets.apply(manager, catalog, VoicePresets.plan(VoicePreset.Volc(apiKey = "k")))
        assertEquals(2, catalog.state.value.models.count { it.serviceId == VoicePresets.VOLC_SERVICE })
        VoicePresets.apply(manager, catalog, VoicePresets.plan(VoicePreset.Volc(apiKey = "k", realtime = false)))
        val left = catalog.state.value.models.filter { it.serviceId == VoicePresets.VOLC_SERVICE }
        assertEquals(listOf(VoicePresets.VOLC_STT_MODEL), left.map { it.id })
    }
}
