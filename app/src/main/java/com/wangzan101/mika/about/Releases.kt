package com.wangzan101.mika.about

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 一个发布的版本:版本号(不带 v)、标题、更新说明(Markdown)、发布日期、发布页、APK 直链。 */
data class ReleaseInfo(
    val version: String,
    val name: String,
    /** 更新说明。 */
    val notes: String,
    /** 发布日期 yyyy-MM-dd;来源没写时为空。 */
    val date: String?,
    val pageUrl: String,
    val apkUrl: String?,
    /** APK 附件的字节数;接口没给时为空。 */
    val apkSize: Long? = null,
    /** APK 附件的摘要,形如 `sha256:<hex>`;较早的附件没有,这时只靠包名、版本号和签名把关。 */
    val apkDigest: String? = null,
    /** 已读到附件列表，空的 apkUrl 表示列表里没有兼容包。 */
    val apkListKnown: Boolean = false,
) {
    /** 「从 GitHub 下载」打开的地址:有 APK 直链就下 APK,没有就打开发布页。 */
    val downloadUrl: String get() = apkUrl ?: pageUrl
}

/**
 * 读 GitHub 发布页接口(releases/latest)的版本信息:tag_name、name、body、published_at、html_url,
 * 以及 assets 里适合本机的 APK 的 browser_download_url(见 [pickApk])。
 */
object Releases {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 发布里除了全架构的 `Mika-v<版本>.apk`,还可能有只带某个架构原生库的包,名字以 `-<后缀>.apk` 结尾。
     * 键是 Android 的 ABI 名,值是附件名后缀。
     */
    private val ABI_APK_SUFFIX = mapOf("arm64-v8a" to "arm64")

    /** 发布里的一个附件。检查更新走发布页兜底时,附件信息从网页里读出来,字段和 API 的一致。 */
    internal data class ReleaseAsset(val name: String, val url: String, val size: Long? = null, val digest: String? = null)

    /** [deviceAbis] 是本机支持的 ABI,按偏好排序(Build.SUPPORTED_ABIS);为空时只取全架构包。 */
    fun fromGitHub(text: String, deviceAbis: List<String> = emptyList()): ReleaseInfo {
        val o = json.parseToJsonElement(text).jsonObject
        val tag = o.str("tag_name") ?: error("no tag_name")
        val body = o.str("body").orEmpty()
        val assets = (o["assets"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.toAsset() }
        val apk = pickAsset(assets.orEmpty(), deviceAbis)
        val version = AppVersion.normalize(tag)
        return ReleaseInfo(
            version = version,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: version,
            notes = body.trim(),
            date = o.str("published_at")?.take(10)?.takeIf { it.length == 10 },
            pageUrl = o.str("html_url") ?: "${MikaLinks.RELEASES}/tag/$tag",
            apkUrl = apk?.url,
            apkSize = apk?.size,
            apkDigest = apk?.digest,
            apkListKnown = assets != null,
        )
    }

    /**
     * 从发布页兜底查到的版本:只有 tag,更新说明为空(说明只在 API 里)。
     * [assets] 为 null 表示附件页无法读取，此时按 Mika 的固定命名推测全架构包地址。
     * 已读取附件页时，只提供列表里与设备兼容的包。
     */
    internal fun fromPage(tag: String, assets: List<ReleaseAsset>?, deviceAbis: List<String>, releasesUrl: String): ReleaseInfo {
        val base = releasesUrl.trimEnd('/')
        val apk = assets?.let { pickAsset(it, deviceAbis) }
        val version = AppVersion.normalize(tag)
        return ReleaseInfo(
            version = version,
            name = version,
            notes = "",
            date = null,
            pageUrl = "$base/tag/$tag",
            apkUrl = apk?.url ?: if (assets == null) "$base/download/$tag/Mika-$tag.apk" else null,
            apkSize = apk?.size,
            apkDigest = apk?.digest,
            apkListKnown = assets != null,
        )
    }

    /**
     * 本机支持某个架构、发布里又有这个架构的专用包时取专用包(更小);否则取全架构包。
     * 只有别的架构的专用包时不取(装不上),这时打开发布页。
     */
    internal fun pickAsset(assets: List<ReleaseAsset>, deviceAbis: List<String>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        fun named(suffix: String) = apks.firstOrNull { it.name.endsWith("-$suffix.apk", ignoreCase = true) }
        val forDevice = deviceAbis.firstNotNullOfOrNull { abi -> ABI_APK_SUFFIX[abi]?.let(::named) }
        val universal = apks.firstOrNull { a -> ABI_APK_SUFFIX.values.none { a.name.endsWith("-$it.apk", ignoreCase = true) } }
        return forDevice ?: universal
    }

    /** 比较已选 APK 对本机的适配程度，与附件选择使用相同的 ABI 优先顺序。 */
    internal fun apkPreference(url: String?, deviceAbis: List<String>): Int {
        if (url == null) return 0
        val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val suffix = ABI_APK_SUFFIX.values.firstOrNull { name.endsWith("-$it.apk", ignoreCase = true) }
            ?: return 1
        val index = deviceAbis.indexOfFirst { ABI_APK_SUFFIX[it] == suffix }
        return if (index < 0) 0 else deviceAbis.size - index + 1
    }

    private fun JsonObject.toAsset(): ReleaseAsset? {
        val name = str("name") ?: return null
        val url = str("browser_download_url") ?: return null
        return ReleaseAsset(name, url, strOrNumber("size")?.toLongOrNull(), str("digest"))
    }

    /** 存盘用:记下最近一次查到的版本,下次打开 App 时红点还在。 */
    fun toJson(r: ReleaseInfo): JsonObject = buildJsonObject {
        put("version", r.version)
        put("name", r.name)
        put("notes", r.notes)
        r.date?.let { put("date", it) }
        put("pageUrl", r.pageUrl)
        r.apkUrl?.let { put("apkUrl", it) }
        r.apkSize?.let { put("apkSize", it) }
        r.apkDigest?.let { put("apkDigest", it) }
        if (r.apkListKnown) put("apkListKnown", true)
    }

    fun fromJson(o: JsonObject): ReleaseInfo? = runCatching {
        ReleaseInfo(
            version = o.str("version")!!, name = o.str("name") ?: o.str("version")!!, notes = o.str("notes").orEmpty(),
            date = o.str("date"), pageUrl = o.str("pageUrl") ?: MikaLinks.RELEASES, apkUrl = o.str("apkUrl"),
            apkSize = o.strOrNumber("apkSize")?.toLongOrNull(), apkDigest = o.str("apkDigest"),
            apkListKnown = (o["apkListKnown"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strOrNumber(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
