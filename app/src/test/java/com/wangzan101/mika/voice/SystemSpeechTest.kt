package com.wangzan101.mika.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemSpeechTest {
    @Test
    fun errorCodesMapToFailures() {
        assertNull(SystemSpeech.failureOf(SpeechRecognizer.ERROR_NO_MATCH))
        assertNull(SystemSpeech.failureOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(SpeechFailure.Busy, SystemSpeech.failureOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(SpeechFailure.Network, SystemSpeech.failureOf(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(SpeechFailure.Network, SystemSpeech.failureOf(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals(SpeechFailure.NoPermission, SystemSpeech.failureOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(SpeechFailure.AudioError, SystemSpeech.failureOf(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemFailed), SystemSpeech.failureOf(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test
    fun silenceFromAServiceThatNeverAnsweredIsNoResponse() {
        assertEquals(
            SystemSpeech.Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)),
            SystemSpeech.afterSilence(answered = false),
        )
        // 回过话的服务只是这一段卡住:按已听到的收尾,不记成不响应
        assertEquals(SystemSpeech.Outcome.Finish, SystemSpeech.afterSilence(answered = true))
    }

    @Test
    fun releaseWaitStillEndsWhenTheRecognizerNeverAnswered() {
        assertEquals(
            SystemSpeech.Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)),
            SystemSpeech.afterReleaseWait(answered = false),
        )
        assertEquals(SystemSpeech.Outcome.Finish, SystemSpeech.afterReleaseWait(answered = true))
    }

    @Test
    fun languageTagsFollowAppLanguage() {
        assertEquals("zh-CN", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.SimplifiedChinese, java.util.Locale.US))
        assertEquals("zh-TW", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.TraditionalChinese, java.util.Locale.US))
        assertEquals("en-US", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.English, java.util.Locale.CHINA))
        assertEquals("ar-MA", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.Arabic, java.util.Locale.US))
        assertEquals("fr-FR", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.System, java.util.Locale.FRANCE))
        // 选的语言和系统不同时用这种语言最常见的地区;相同时沿用系统的地区
        assertEquals("ja-JP", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.Japanese, java.util.Locale.US))
        assertEquals("pt-BR", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.Portuguese, java.util.Locale.US))
        assertEquals("pt-PT", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.Portuguese, java.util.Locale.forLanguageTag("pt-PT")))
        assertEquals("es-MX", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.Spanish, java.util.Locale.forLanguageTag("es-MX")))
        assertEquals("id-ID", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.Indonesian, java.util.Locale.forLanguageTag("in-ID")))
        // 中文按选的简繁定地区,不跟系统
        assertEquals("zh-CN", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.SimplifiedChinese, java.util.Locale.TAIWAN))
        assertEquals("zh", SystemSpeech.isoCode("zh-TW"))
        assertEquals("en", SystemSpeech.isoCode("en-US"))
    }

    @Test
    fun appLanguageWithoutRegionFallsBackToItsUsualRegion() {
        // 在 App 里选了英语后进程默认语言是不带地区的 en:没有地区可沿用,不能只送 en
        assertEquals("en-US", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.English, java.util.Locale.forLanguageTag("en")))
        assertEquals("ja-JP", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.Japanese, java.util.Locale.forLanguageTag("ja")))
        assertEquals("en-GB", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.English, java.util.Locale.UK))
    }

    @Test
    fun installedTagMatchesTheSameLanguageWrittenDifferently() {
        // Pixel 10 Pro 的本机识别实际报的:装了台湾中文,但登记的写法带文字子标签
        val pixel = listOf("en-US", "zh-Hant-TW", "cmn-Hant-TW")
        assertEquals("zh-Hant-TW", SystemSpeech.installedTag("zh-TW", pixel))
        assertEquals("en-US", SystemSpeech.installedTag("en-US", pixel))
        assertEquals("en-US", SystemSpeech.installedTag("en", pixel))
        assertEquals("cmn-Hans-CN", SystemSpeech.installedTag("zh-CN", listOf("en-US", "cmn-Hans-CN")))
        assertEquals("id-ID", SystemSpeech.installedTag("in-ID", listOf("id-ID")))
        // 地区不同、语言不同的不算
        assertNull(SystemSpeech.installedTag("zh-CN", pixel))
        assertNull(SystemSpeech.installedTag("en-GB", pixel))
        assertNull(SystemSpeech.installedTag("ja-JP", pixel))
        assertNull(SystemSpeech.installedTag("zh-TW", emptyList()))
    }

    @Test
    fun systemLanguageDropsScriptAndExtensions() {
        val system = com.wangzan101.mika.i18n.AppLanguage.System
        assertEquals("zh-CN", SystemSpeech.speechTag(system, java.util.Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("zh-TW", SystemSpeech.speechTag(system, java.util.Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals("sr-RS", SystemSpeech.speechTag(system, java.util.Locale.forLanguageTag("sr-Latn-RS")))
        assertEquals("de-DE", SystemSpeech.speechTag(system, java.util.Locale.forLanguageTag("de-DE-u-co-phonebk")))
        assertEquals("ja", SystemSpeech.speechTag(system, java.util.Locale.forLanguageTag("ja")))
        val english = com.wangzan101.mika.i18n.AppLanguage.English
        assertEquals("en-GB", SystemSpeech.speechTag(english, java.util.Locale.forLanguageTag("en-Latn-GB")))
    }

    private fun svc(name: String, open: Boolean = true) = RecognizerService("a.b/.$name", open, null)

    /** 只有一个识别服务时它就是默认的:只试默认的,不点名再试一遍白等。 */
    @Test
    fun aSingleServiceIsOnlyTriedAsTheDefault() {
        assertEquals(listOf<String?>(null), SystemSpeech.attempts(listOf(svc("One")), remembered = null))
        assertEquals(listOf<String?>(null), SystemSpeech.attempts(emptyList(), remembered = null))
    }

    /** 默认的不回话时,再点名试其余的,对别的 App 开放的在前;总数有上限。 */
    @Test
    fun otherServicesFollowTheDefaultOpenOnesFirst() {
        val services = listOf(svc("Closed", open = false), svc("Open"), svc("Third"))
        assertEquals(listOf(null, "a.b/.Open", "a.b/.Third"), SystemSpeech.attempts(services, remembered = null))
        assertEquals(SystemSpeech.MAX_ATTEMPTS, SystemSpeech.attempts(services + svc("Fourth"), null).size)
    }

    /** 之前点名试通的排最前、不重复;它已经不在手机上了就不管它。 */
    @Test
    fun theServiceThatAnsweredBeforeGoesFirst() {
        val services = listOf(svc("One"), svc("Two"))
        assertEquals(listOf("a.b/.Two", null, "a.b/.One"), SystemSpeech.attempts(services, remembered = "a.b/.Two"))
        assertEquals(listOf(null, "a.b/.One", "a.b/.Two"), SystemSpeech.attempts(services, remembered = "a.b/.Gone"))
    }

    @Test fun compatibilityIsLimitedToTheVerifiedBuildFamilyAndApi() {
        assertEquals(true, SystemSpeech.serviceCompatibility("Xiaomi", "OS3.0.1.0.TEST", 36))
        assertEquals(true, SystemSpeech.serviceCompatibility("POCO", "OS3.1.0.0.TEST", 36))
        assertEquals(true, SystemSpeech.serviceCompatibility(" Redmi ", " OS4.0.0.0.TEST ", 37))
        assertEquals(false, SystemSpeech.serviceCompatibility("Other", "OS3.0.1.0.TEST", 36))
        assertEquals(false, SystemSpeech.serviceCompatibility("Xiaomi", "AOSP.TEST", 36))
        assertEquals(false, SystemSpeech.serviceCompatibility("Xiaomi", "V14.0.1.0.TEST", 36))
        assertEquals(true, SystemSpeech.serviceCompatibility("Xiaomi", "OS1.0.1.0.TEST", 34))
        assertEquals(true, SystemSpeech.serviceCompatibility("Xiaomi", "OS2.0.1.0.TEST", 35))
        assertEquals(false, SystemSpeech.serviceCompatibility("Xiaomi", "OS1.0.1.0.TEST", 33))
        assertEquals(false, SystemSpeech.serviceCompatibility("Xiaomi", "OS-preview", 36))
    }

    @Test fun compatibleSingleServiceStillHasAnExplicitFallback() {
        val one = listOf(svc("Only"))
        assertEquals(listOf(null, "a.b/.Only"), SystemSpeech.attempts(one, null, compatibility = true))
        assertEquals(listOf("a.b/.Only", null), SystemSpeech.attempts(one, "a.b/.Only", compatibility = true))
        assertEquals(listOf<String?>(null), SystemSpeech.attempts(emptyList(), null, compatibility = true))
        assertEquals(SystemSpeech.MAX_ATTEMPTS,
            SystemSpeech.attempts(listOf(svc("One"), svc("Two"), svc("Three"), svc("Four")), null, compatibility = true).size)
    }

    @Test fun clientFallbackIsBoundedAndNeverReopensAfterReleaseOrLosesText() {
        val client = SpeechRecognizer.ERROR_CLIENT
        assertEquals(SystemSpeech.Outcome.NextService, SystemSpeech.afterError(client, true, false, canTryNext = true))
        assertEquals(SystemSpeech.Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemFailed)),
            SystemSpeech.afterError(client, true, false, canTryNext = false))
        assertEquals(SystemSpeech.Outcome.Finish, SystemSpeech.afterError(client, false, false, canTryNext = true))
        assertEquals(SystemSpeech.Outcome.Finish, SystemSpeech.afterError(client, true, true, canTryNext = true))
        assertEquals(SystemSpeech.Outcome.Fail(SpeechFailure.NoPermission),
            SystemSpeech.afterError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, true, false, canTryNext = true))
        assertEquals(SystemSpeech.Outcome.Fail(SpeechFailure.AudioError),
            SystemSpeech.afterError(SpeechRecognizer.ERROR_AUDIO, true, false, canTryNext = true))
        assertEquals(SystemSpeech.Outcome.Fail(SpeechFailure.Network),
            SystemSpeech.afterError(SpeechRecognizer.ERROR_NETWORK, true, false, canTryNext = true))
    }

}
