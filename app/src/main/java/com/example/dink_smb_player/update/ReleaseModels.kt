package com.example.dink_smb_player.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A downloadable APK attached to a release. [sha256] is the lower-case hex digest when GitHub supplied one. */
data class ReleaseAsset(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String?,
)

/** One published GitHub release, reduced to what the updater and changelog reader need. */
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val body: String,
    /** ISO-8601 publish timestamp as GitHub returns it (e.g. 2026-10-12T09:30:00Z). */
    val publishedAt: String,
    val htmlUrl: String,
    val apk: ReleaseAsset?,
) {
    /** Tag without the leading `v`, for display and comparison. */
    val version: String get() = tag.trim().removePrefix("v").removePrefix("V")

    /** `2026-10-12` from [publishedAt], or empty. */
    val dateLabel: String get() = publishedAt.take(10)
}

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
internal data class AssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
    val digest: String? = null,
)

object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses the GitHub `releases` array. Drafts, prereleases and tagless entries are skipped;
     *  result is sorted newest version first. Malformed JSON throws [kotlinx.serialization.SerializationException]. */
    fun parse(body: String): List<ReleaseInfo> =
        json.decodeFromString<List<ReleaseDto>>(body)
            .filter { !it.draft && !it.prerelease && it.tagName.isNotBlank() }
            .map { dto ->
                ReleaseInfo(
                    tag = dto.tagName,
                    name = dto.name?.takeIf { it.isNotBlank() } ?: dto.tagName,
                    body = dto.body.orEmpty(),
                    publishedAt = dto.publishedAt.orEmpty(),
                    htmlUrl = dto.htmlUrl.orEmpty(),
                    apk = pickApk(dto.assets, dto.tagName.trim().removePrefix("v").removePrefix("V")),
                )
            }
            .sortedWith { a, b -> Semver.compare(b.tag, a.tag) }

    /** Prefers exactly `dink-<version>.apk`, then other dink*.apk names containing "release",
     *  then any other dink*.apk. Never returns a name containing "debug". */
    internal fun pickApk(assets: List<AssetDto>, version: String): ReleaseAsset? {
        val apks = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) && it.url.isNotBlank() &&
                !it.name.contains("debug", ignoreCase = true)
        }
        val dink = apks.filter { it.name.startsWith("dink", ignoreCase = true) }
        val chosen = dink.firstOrNull { it.name.equals("dink-$version.apk", ignoreCase = true) }
            ?: dink.firstOrNull { it.name.contains("release", ignoreCase = true) }
            ?: dink.firstOrNull()
        return chosen?.let {
            val digest = it.digest?.trim()?.lowercase().orEmpty()
            ReleaseAsset(
                name = it.name,
                url = it.url,
                size = it.size,
                sha256 = if (digest.startsWith("sha256:")) digest.removePrefix("sha256:").ifEmpty { null } else null,
            )
        }
    }
}

/** Where release APKs may be downloaded from: https on GitHub's own hosts only (checked on every redirect hop). */
object DownloadUrlPolicy {
    fun isAllowed(url: String): Boolean = runCatching {
        val u = java.net.URI(url)
        u.scheme.equals("https", ignoreCase = true) && u.userInfo == null && isAllowedHost(u.host)
    }.getOrDefault(false)

    fun isAllowedHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "github.com" || h.endsWith(".githubusercontent.com")
    }
}
