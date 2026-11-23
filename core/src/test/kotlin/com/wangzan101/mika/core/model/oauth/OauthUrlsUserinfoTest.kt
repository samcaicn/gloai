package com.wangzan101.mika.core.model.oauth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OauthUrls 的注释说 userinfo 一律拒绝;只写了密码、没写用户名时也不能例外。 */
class OauthUrlsUserinfoTest {
    private val hosts = setOf("auth.x.ai")

    @Test fun passwordOnlyUserInfoIsRejected() {
        assertFalse(OauthUrls.allowed("https://:secret@auth.x.ai/token", hosts))
        assertFalse(OauthUrls.allowed("https://user:@auth.x.ai/token", hosts))
        assertFalse(OauthUrls.allowed("https://user:pw@auth.x.ai/token", hosts))
    }

    @Test fun aPlainHttpsUrlOnAnAllowedHostStillPasses() {
        assertTrue(OauthUrls.allowed("https://auth.x.ai/oauth2/token", hosts))
        assertFalse(OauthUrls.allowed("http://auth.x.ai/oauth2/token", hosts))
        assertFalse(OauthUrls.allowed("https://evil.example/token", hosts))
    }
}
