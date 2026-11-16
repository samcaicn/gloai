package com.wangzan101.mika.voice

import android.Manifest
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 识别服务说没有权限时,缺权限的是谁:只有三样都确定才说是识别服务。 */
class SpeechServiceMicTest {
    private val mic = Manifest.permission.RECORD_AUDIO
    private val granted = PackageInfo.REQUESTED_PERMISSION_GRANTED

    // 申请了没给、申请了给了、没申请、读不全:分得清
    @Test fun readsTheRecordPermissionOfAnotherApp() {
        assertEquals(ServiceMic.Missing, SpeechServiceMic.of(arrayOf("a.b.OTHER", mic), intArrayOf(granted, 0)))
        assertEquals(ServiceMic.Granted, SpeechServiceMic.of(arrayOf("a.b.OTHER", mic), intArrayOf(0, granted)))
        // 标记里还有别的位(比如「永不过问」),只看「给了没有」那一位
        assertEquals(ServiceMic.Granted, SpeechServiceMic.of(arrayOf(mic), intArrayOf(granted or 1)))
        assertEquals(ServiceMic.Missing, SpeechServiceMic.of(arrayOf(mic), intArrayOf(1)))
        assertEquals(ServiceMic.NotRequested, SpeechServiceMic.of(arrayOf("a.b.OTHER"), intArrayOf(granted)))
        assertEquals(ServiceMic.NotRequested, SpeechServiceMic.of(null, null))
        assertEquals(ServiceMic.NotRequested, SpeechServiceMic.of(emptyArray(), intArrayOf()))
        // 申请了,但标记读不出来或对不上:不下结论
        assertEquals(ServiceMic.Unknown, SpeechServiceMic.of(arrayOf(mic), null))
        assertEquals(ServiceMic.Unknown, SpeechServiceMic.of(arrayOf("a.b.OTHER", mic), intArrayOf(granted)))
    }

    // 这次用的是哪个服务:点名的就是它;没点名的看系统设的默认;本机识别认不出来。不拿「登记的第一个」去猜
    @Test fun theServiceInUseIsTheNamedOneOrTheSystemDefaultNeverAGuess() {
        assertEquals("a.named", SpeechServiceMic.used("a.named", onDevice = false) { "a.default" })
        assertEquals("a.default", SpeechServiceMic.used(null, onDevice = false) { "a.default" })
        assertNull(SpeechServiceMic.used(null, onDevice = false) { null })
        assertNull(SpeechServiceMic.used(null, onDevice = true) { "a.default" })
        assertNull(SpeechServiceMic.used("a.named", onDevice = true) { "a.default" })
    }

    // 三样都确定才认定是识别服务缺权限;有一样不确定就不说
    @Test fun onlyAConfirmedMissingPermissionNamesTheService() {
        val asked = mutableListOf<String>()
        fun missing(self: Boolean, pkg: String?, state: ServiceMic) =
            SpeechServiceMic.missing(self, pkg, { asked += it; state }, { "名字 of $it" })
        assertEquals(SpeechServiceTarget("a.svc", "名字 of a.svc"), missing(true, "a.svc", ServiceMic.Missing))
        // 它有权限、没申请、查不到:照原来的说法
        assertNull(missing(true, "a.svc", ServiceMic.Granted))
        assertNull(missing(true, "a.svc", ServiceMic.NotRequested))
        assertNull(missing(true, "a.svc", ServiceMic.Unknown))
        // 认不出是哪个服务:不查、不说
        asked.clear()
        assertNull(missing(true, null, ServiceMic.Missing))
        assertNull(missing(true, " ", ServiceMic.Missing))
        // Mika 自己就没有录音权限:先解决自己的,不去说别人
        assertNull(missing(false, "a.svc", ServiceMic.Missing))
        assertEquals(emptyList<String>(), asked)
    }

    // 名字是别的应用自己起的:只留一行、截短
    @Test fun theLabelIsFlattenedAndCapped() {
        assertEquals("系统 语音 引擎", SpeechServiceMic.clean("  系统\n语音\t 引擎 "))
        assertEquals(40, SpeechServiceMic.clean("长".repeat(200)).length)
        assertEquals("", SpeechServiceMic.clean(" \n "))
    }
}
