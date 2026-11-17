package com.wangzan101.mika.skills

import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillDrafter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
internal data class MarketEntry(val name: String, val display: String, val description: String, val sha256: String)

@Serializable
private data class MarketIndex(val version: Int, val skills: List<MarketEntry>)

/** One public GitHub repository; the catalog cannot supply arbitrary URLs or executable files. */
internal class SkillMarket(private val http: OkHttpClient = client) {
    companion object {
        // 官方技能市场仓库还没定;默认留空,用户自己填 owner/repository,不指向任何个人测试仓库。
        const val DEFAULT_REPO = ""
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS).build()
        private val repoPattern = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}")
        fun validRepo(value: String) = repoPattern.matches(value)
        private val hash = Regex("[0-9a-f]{64}")
        private val json = Json { ignoreUnknownKeys = true }
    }

    fun list(repo: String): List<MarketEntry> {
        val doc = json.decodeFromString<MarketIndex>(read(repo, "index.json", 128 * 1024))
        require(doc.version == 1 && doc.skills.size <= 100 && doc.skills.distinctBy { it.name }.size == doc.skills.size)
        require(doc.skills.all { e ->
            e.name.length <= 64 && PersonalSkills.NAME.matches(e.name) && e.display.isNotBlank() &&
                e.display.length <= 80 && e.description.isNotBlank() && e.description.length <= 300 && hash.matches(e.sha256)
        })
        return doc.skills
    }

    fun preview(repo: String, entry: MarketEntry): String {
        require(PersonalSkills.NAME.matches(entry.name))
        val text = read(repo, "skills/${entry.name}/SKILL.md", 4 * SkillDrafter.MAX_CHARS)
        val (fields, _) = FrontMatter.parse(text)
        require(text.length <= SkillDrafter.MAX_CHARS && PersonalSkills.fingerprint(text) == entry.sha256 &&
            fields["name"] == entry.name && fields["display"] == entry.display && fields["description"] == entry.description)
        return text
    }

    private fun read(repo: String, path: String, limit: Int): String {
        require(validRepo(repo))
        val request = Request.Builder().url("https://raw.githubusercontent.com/$repo/main/$path").build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body?.source() ?: throw IOException("Empty response")
            require(!source.request(limit.toLong() + 1))
            source.readByteArray().decodeToString(throwOnInvalidSequence = true)
        }
    }
}
