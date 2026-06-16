package com.example.dink_smb_player.data.source.cloud

import com.example.dink_smb_player.BuildConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google Drive client for the Phase 8 cloud source. Uses the OAuth 2.0
 * "limited-input device" flow — the only sane sign-in path on a TV with no
 * browser and a D-pad keyboard: we show the user a short code + URL, they
 * authorise on their phone, and we poll for the token.
 *
 * Client id/secret come from [BuildConfig] (set via gradle props, see app
 * build.gradle.kts). When unset, [isConfigured] is false and the UI explains
 * how to configure rather than failing mysteriously.
 *
 * All network calls block — call from Dispatchers.IO.
 *
 * Scope is `drive.readonly`: we list + stream the user's audio, never write.
 */
object GoogleDriveClient {

    private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val ABOUT_URL = "https://www.googleapis.com/drive/v3/about"
    private const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"

    private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val clientId get() = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
    private val clientSecret get() = BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET

    fun isConfigured(): Boolean = clientId.isNotBlank()

    // ---------- device flow ----------

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSec: Int,
        val expiresInSec: Int,
    )

    /** Stage 1: ask Google for a user code to show on screen. */
    fun requestDeviceCode(): Result<DeviceCode> = runCatching {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", SCOPE)
            .build()
        val json = post(DEVICE_CODE_URL, body)
        DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            // Google returns verification_url (device flow) — fall back to the
            // OAuth-standard verification_uri name just in case.
            verificationUrl = json.optString("verification_url",
                json.optString("verification_uri", "https://www.google.com/device")),
            intervalSec = json.optInt("interval", 5),
            expiresInSec = json.optInt("expires_in", 1800),
        )
    }

    sealed interface TokenResult {
        data class Success(val accessToken: String, val refreshToken: String?, val expiresInSec: Int) : TokenResult
        /** User hasn't authorised yet — keep polling at the given interval. */
        object Pending : TokenResult
        /** Google asked us to back off — add 5s to the poll interval. */
        object SlowDown : TokenResult
        object Denied : TokenResult
        object Expired : TokenResult
        data class Error(val message: String) : TokenResult
    }

    /** Stage 2: poll once for the token. Caller loops on [TokenResult.Pending] /
     *  [TokenResult.SlowDown] honouring the interval until success or terminal error. */
    fun pollOnce(deviceCode: String): TokenResult = runCatching {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("device_code", deviceCode)
            .add("grant_type", DEVICE_GRANT)
            .build()
        val (code, json) = postRaw(TOKEN_URL, body)
        if (code in 200..299) {
            TokenResult.Success(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifBlank { null },
                expiresInSec = json.optInt("expires_in", 3600),
            )
        } else {
            when (json.optString("error")) {
                "authorization_pending" -> TokenResult.Pending
                "slow_down" -> TokenResult.SlowDown
                "access_denied" -> TokenResult.Denied
                "expired_token" -> TokenResult.Expired
                else -> TokenResult.Error(json.optString("error_description", json.optString("error", "Token error")))
            }
        }
    }.getOrElse { TokenResult.Error(it.message ?: "Network error") }

    data class Refreshed(val accessToken: String, val expiresInSec: Int)

    /** Exchange a refresh token for a fresh access token. */
    fun refresh(refreshToken: String): Result<Refreshed> = runCatching {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val json = post(TOKEN_URL, body)
        Refreshed(json.getString("access_token"), json.optInt("expires_in", 3600))
    }

    // ---------- Drive ----------

    data class DriveAccount(val email: String, val displayName: String)

    fun about(accessToken: String): Result<DriveAccount> = runCatching {
        val url = "$ABOUT_URL?fields=user(emailAddress,displayName)"
        val json = get(url, accessToken).getJSONObject("user")
        DriveAccount(json.optString("emailAddress", "Google account"), json.optString("displayName", ""))
    }

    const val FOLDER_MIME = "application/vnd.google-apps.folder"
    /** Drive alias for "My Drive" root — accepted in `'<id>' in parents` queries. */
    const val ROOT_ID = "root"

    data class DriveItem(
        val id: String,
        val name: String,
        val isFolder: Boolean,
        val sizeBytes: Long,
        val mimeType: String,
    )

    /**
     * List the direct children of one folder (folders + audio files, non-trashed),
     * following pagination. Used by the lazy folder browser AND the recursive import
     * walk — one Drive query per folder, no upfront whole-account scan.
     * [folderId] = [ROOT_ID] lists My Drive.
     */
    fun listChildren(accessToken: String, folderId: String): Result<List<DriveItem>> = runCatching {
        val out = ArrayList<DriveItem>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append(FILES_URL)
                append("?q=").append(enc("'$folderId' in parents and trashed = false"))
                append("&fields=").append(enc("nextPageToken,files(id,name,size,mimeType)"))
                append("&pageSize=1000")
                append("&spaces=drive")
                append("&orderBy=folder,name")
                if (pageToken != null) append("&pageToken=").append(enc(pageToken!!))
            }
            val json = get(url, accessToken)
            val files = json.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val mime = f.optString("mimeType", "")
                    val isFolder = mime == FOLDER_MIME
                    // Keep folders + audio; skip docs/photos/etc.
                    if (!isFolder && !mime.startsWith("audio/")) continue
                    out += DriveItem(
                        id = f.getString("id"),
                        name = f.optString("name", f.getString("id")),
                        isFolder = isFolder,
                        sizeBytes = f.optString("size", "0").toLongOrNull() ?: 0L,
                        mimeType = mime.ifEmpty { "audio/*" },
                    )
                }
            }
            pageToken = json.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null)
        out
    }

    /** Streaming download endpoint for a file — used by [CloudDataSource]. Requires
     *  the bearer token in the Authorization header (added by the data source). */
    fun downloadUrl(fileId: String): String = "$FILES_URL/$fileId?alt=media"

    // ---------- http helpers ----------

    private fun post(url: String, body: FormBody): JSONObject {
        val (code, json) = postRaw(url, body)
        if (code !in 200..299) {
            throw RuntimeException(json.optString("error_description", json.optString("error", "HTTP $code")))
        }
        return json
    }

    private fun postRaw(url: String, body: FormBody): Pair<Int, JSONObject> {
        val req = Request.Builder().url(url).post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            return resp.code to json
        }
    }

    private fun get(url: String, accessToken: String): JSONObject {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $accessToken")
            .get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (resp.code !in 200..299) {
                throw RuntimeException(
                    json.optJSONObject("error")?.optString("message")
                        ?: json.optString("error_description", "HTTP ${resp.code}"),
                )
            }
            return json
        }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
