package com.wangzan101.mika.skills

import com.wangzan101.mika.core.skills.PersonalSkills
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SkillMarketTest {
    private val text = "---\nname: test\ndisplay: 示例\ndescription: 示例说明\n---\n## 入口\n示例。\n"
    private val hash = PersonalSkills.fingerprint(text)
    private val entry = """{"name":"test","display":"示例","description":"示例说明","sha256":"$hash"}"""
    private fun index(rows: String = entry) = """{"version":1,"skills":[$rows]}"""
    private fun fake(body: (String) -> String) = SkillMarket(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertEquals("raw.githubusercontent.com", request.url.host)
        Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1)
            .body(body(request.url.encodedPath).toResponseBody()).build()
    }.build())

    @Test fun switchingRepositoriesReadsOnlyThatRepositoriesFiles() {
        for (repo in listOf("author/first", "community/other")) {
            val requests = mutableListOf<String>()
            val client = fake { path -> requests += path; if (path.endsWith(".json")) index() else text }
            val item = client.list(repo).single()
            assertEquals(text, client.preview(repo, item))
            assertEquals(listOf("/$repo/main/index.json", "/$repo/main/skills/test/SKILL.md"), requests)
        }
    }

    @Test fun invalidRepositoryPathsCannotSendRequests() {
        val market = fake { error("must not contact the network") }
        for (repo in listOf("a/..", "a/b/c", "https://evil.test/a/b", "a/%2e%2e", "a/repo?x")) {
            assertThrows(IllegalArgumentException::class.java) { market.list(repo) }
        }
    }

    @Test fun rejectsOutdatedOrMislabelledPreviews() {
        val market = fake { if (it.endsWith(".json")) index() else text + "改动" }
        val listed = market.list("a/b").single()
        assertThrows(IllegalArgumentException::class.java) { market.preview("a/b", listed) }
        assertThrows(IllegalArgumentException::class.java) { fake { text }.preview("a/b", listed.copy(display = "另一份")) }
    }

    @Test fun rejectsTraversalDuplicatesUnsupportedVersionsAndOversizedResponses() {
        for (bad in listOf(index(entry.replace("test", "../outside")), index("$entry,$entry"),
            index().replace("version\":1", "version\":2"), " ".repeat(128 * 1024 + 1) + index())) {
            assertThrows(IllegalArgumentException::class.java) { fake { bad }.list("a/b") }
        }
    }
}
