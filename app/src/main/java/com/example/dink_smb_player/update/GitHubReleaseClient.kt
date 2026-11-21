package com.example.dink_smb_player.update

import android.content.Context
import com.example.dink_smb_player.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches the release list from the public GitHub API with ETag revalidation. The last good
 * response body lives in filesDir so a 304 (or being offline) still yields a changelog.
 * Blocking calls: run on Dispatchers.IO.
 */
class GitHubReleaseClient(
    private val context: Context,
    private val prefs: UpdatePrefs,
    private val http: OkHttpClient = defaultClient,
) {
    private val cacheFile get() = File(context.filesDir, CACHE_NAME)

    /** Releases from the on-disk cache, or empty when none/corrupt. */
    fun cached(): List<ReleaseInfo> = runCatching {
        val f = cacheFile
        if (f.exists()) ReleaseParser.parse(f.readText()) else emptyList()
    }.getOrDefault(emptyList())

    /** Network fetch with If-None-Match. Throws [IOException] when offline / on HTTP errors. */
    suspend fun fetch(): List<ReleaseInfo> {
        val etag = if (cacheFile.exists()) prefs.etag() else null
        val req = Request.Builder()
            .url(URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Dink-Android/${BuildConfig.VERSION_NAME}")
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 304) {
                val c = cached()
                if (c.isNotEmpty()) return c
                prefs.setEtag(null)
                throw IOException("Release cache was empty; try again")
            }
            if (!resp.isSuccessful) throw IOException("GitHub returned HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("Empty response from GitHub")
            val parsed = try {
                ReleaseParser.parse(body)
            } catch (e: Exception) {
                throw IOException("Couldn't read the release list", e)
            }
            val tmp = File(context.filesDir, "$CACHE_NAME.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile) }
            prefs.setEtag(resp.header("ETag"))
            return parsed
        }
    }

    companion object {
        const val REPO = "Kirby1997/Dink-AndroidTVInternetMusicPlayer"
        const val URL = "https://api.github.com/repos/$REPO/releases?per_page=20"
        private const val CACHE_NAME = "update_releases.json"

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
