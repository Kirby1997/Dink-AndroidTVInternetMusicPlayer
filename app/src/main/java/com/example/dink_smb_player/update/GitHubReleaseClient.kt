package com.example.dink_smb_player.update

import android.content.Context
import com.example.dink_smb_player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** GitHub refused the request because of rate limiting. */
class RateLimitException(message: String) : IOException(message)

/**
 * Fetches the release list from the public GitHub API with ETag revalidation. The last good
 * response body lives in filesDir so a 304 (or being offline) still yields a changelog.
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

    /** Network fetch with If-None-Match, on IO, cancelling the HTTP call if the coroutine is cancelled.
     *  Throws [RateLimitException] / [IOException]. */
    suspend fun fetch(): List<ReleaseInfo> = withContext(Dispatchers.IO) {
        val etag = if (cacheFile.exists()) prefs.etag() else null
        val req = Request.Builder()
            .url(URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Dink-Android/${BuildConfig.VERSION_NAME}")
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        execute(http.newCall(req)).use { resp ->
            if (resp.code == 304) {
                val c = cached()
                if (c.isNotEmpty()) return@withContext c
                prefs.setEtag(null)
                throw IOException("Release cache was empty; try again")
            }
            if (!resp.isSuccessful) {
                rateLimitMessage(resp)?.let { throw RateLimitException(it) }
                throw IOException("GitHub returned HTTP ${resp.code}")
            }
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
            parsed
        }
    }

    private suspend fun execute(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        const val REPO = "Kirby1997/Dink-AndroidTVInternetMusicPlayer"
        const val URL = "https://api.github.com/repos/$REPO/releases?per_page=20"
        private const val CACHE_NAME = "update_releases.json"

        /** User-facing text when [resp] is a rate-limit refusal (403/429), else null. */
        internal fun rateLimitMessage(resp: Response, now: Long = System.currentTimeMillis()): String? {
            if (resp.code != 403 && resp.code != 429) return null
            val retryAfter = resp.header("Retry-After")?.trim()?.toLongOrNull()
            val remaining = resp.header("X-RateLimit-Remaining")?.trim()
            val reset = resp.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()
            if (resp.code == 403 && retryAfter == null && remaining != "0") return null
            val resetAtMs = when {
                retryAfter != null -> now + retryAfter * 1000
                reset != null -> reset * 1000
                else -> null
            }
            val at = resetAtMs?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
            return if (at != null) "GitHub rate limit reached, try again after $at." else "GitHub rate limit reached, try again later."
        }

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** APK download client: every hop (including redirects) must be https on a GitHub host. */
        val downloadClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor { chain ->
                    val url = chain.request().url
                    if (!DownloadUrlPolicy.isAllowed(url.toString())) {
                        throw IOException("Blocked download from ${url.host}")
                    }
                    chain.proceed(chain.request())
                }
                .build()
        }
    }
}
