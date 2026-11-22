package com.wangzan101.mika.voicecall

import com.wangzan101.mika.voicecall.RouteDevice.Bluetooth
import com.wangzan101.mika.voicecall.RouteDevice.Earpiece
import com.wangzan101.mika.voicecall.RouteDevice.Speaker
import com.wangzan101.mika.voicecall.RouteDevice.Wired
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * chooseRoute 全组合:可用设备集合 × 是否请求免提 × SDK 30/31。
 * 决策策略与 SDK 无关(SDK 只决定落地方式:31+ setCommunicationDevice,以下 speakerphone),
 * 所以每个集合在两个 SDK 上必须给出同一答案。
 */
class VoiceCallAudioRouteTest {

    private val sdks = listOf(30, 31)

    /** 各 SDK 上答案一致时,一次断言两个 SDK。 */
    private fun assertRoute(
        expected: RouteDevice,
        available: List<RouteDevice>,
        speakerRequested: Boolean?,
    ) {
        for (sdk in sdks) {
            assertEquals(
                "available=$available speakerRequested=$speakerRequested sdk=$sdk",
                expected,
                chooseRoute(available, sdk, speakerRequested),
            )
        }
    }

    // ---- 仅扬声器 ----

    @Test
    fun speakerOnly_withoutRequest_staysSpeaker() {
        assertRoute(Speaker, listOf(Speaker), speakerRequested = false)
    }

    @Test
    fun speakerOnly_withRequest_staysSpeaker() {
        assertRoute(Speaker, listOf(Speaker), speakerRequested = true)
    }

    // ---- 听筒在场 ----

    @Test
    fun earpiece_withoutRequest_picksEarpiece() {
        assertRoute(Earpiece, listOf(Speaker, Earpiece), speakerRequested = false)
    }

    @Test
    fun earpiece_withRequest_picksSpeaker() {
        assertRoute(Speaker, listOf(Speaker, Earpiece), speakerRequested = true)
    }

    // ---- 有线在场(优先于听筒)----

    @Test
    fun wired_withoutRequest_picksWired() {
        assertRoute(Wired, listOf(Speaker, Earpiece, Wired), speakerRequested = false)
    }

    @Test
    fun wired_withRequest_picksSpeaker() {
        assertRoute(Speaker, listOf(Speaker, Earpiece, Wired), speakerRequested = true)
    }

    // ---- 蓝牙在场(优先于听筒)----

    @Test
    fun bluetooth_withoutRequest_picksBluetooth() {
        assertRoute(Bluetooth, listOf(Speaker, Earpiece, Bluetooth), speakerRequested = false)
    }

    @Test
    fun bluetooth_withRequest_picksSpeaker() {
        assertRoute(Speaker, listOf(Speaker, Earpiece, Bluetooth), speakerRequested = true)
    }

    // ---- 全在场:有线优先于蓝牙,蓝牙优先于听筒 ----

    @Test
    fun allPresent_withoutRequest_picksWired() {
        assertRoute(Wired, listOf(Speaker, Earpiece, Wired, Bluetooth), speakerRequested = false)
    }

    @Test
    fun allPresent_withRequest_picksSpeaker() {
        assertRoute(Speaker, listOf(Speaker, Earpiece, Wired, Bluetooth), speakerRequested = true)
    }

    // ---- 无私密设备留扬声器(平板形态:无听筒,只有扬声器)----

    @Test
    fun noPrivateDevice_withoutRequest_staysSpeaker() {
        assertRoute(Speaker, listOf(Speaker), speakerRequested = false)
    }

    @Test
    fun noEarpiece_wiredAndBluetooth_picksWired() {
        assertRoute(Wired, listOf(Speaker, Wired, Bluetooth), speakerRequested = false)
    }

    // ---- 边界 ----

    @Test
    fun emptyAvailable_fallsBackToSpeaker() {
        assertRoute(Speaker, emptyList(), speakerRequested = false)
        assertRoute(Speaker, emptyList(), speakerRequested = true)
    }

    @Test
    fun speakerRequested_butSpeakerUnavailable_fallsBackToPrivate() {
        assertRoute(Earpiece, listOf(Earpiece), speakerRequested = true)
        assertRoute(Wired, listOf(Earpiece, Wired), speakerRequested = true)
    }

    @Test
    fun orderOfAvailableListDoesNotMatter() {
        assertRoute(Wired, listOf(Bluetooth, Wired, Earpiece, Speaker), speakerRequested = false)
        assertRoute(Bluetooth, listOf(Earpiece, Bluetooth, Speaker), speakerRequested = false)
    }

    // ---- 自动(用户还没表态):耳机接管,没人贴耳听助手,跳过听筒 ----

    @Test
    fun auto_withWired_picksWiredOverEverything() {
        assertRoute(Wired, listOf(Speaker, Earpiece, Wired, Bluetooth), speakerRequested = null)
    }

    @Test
    fun auto_withBluetooth_picksBluetooth() {
        assertRoute(Bluetooth, listOf(Speaker, Earpiece, Bluetooth), speakerRequested = null)
    }

    @Test
    fun auto_withEarpieceOnly_staysSpeaker() {
        assertRoute(Speaker, listOf(Speaker, Earpiece), speakerRequested = null)
    }

    @Test
    fun auto_withoutSpeaker_fallsBackToEarpiece() {
        assertRoute(Earpiece, listOf(Earpiece), speakerRequested = null)
    }

    @Test
    fun auto_speakerOnly_staysSpeaker() {
        assertRoute(Speaker, listOf(Speaker), speakerRequested = null)
        assertRoute(Speaker, emptyList(), speakerRequested = null)
    }
}
