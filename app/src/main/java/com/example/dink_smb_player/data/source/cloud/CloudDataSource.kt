@file:OptIn(UnstableApi::class)

package com.example.dink_smb_player.data.source.cloud

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Media3 [DataSource] that STREAMS bytes from a cloud provider over HTTP Range —
 * nothing is written to device storage. URI shape:
 *
 *   `gdrive://file/<fileId>?pid=<providerId>`
 *
 * `fileId` is the last path segment (kept in the path, not the authority, so its
 * case survives — Drive ids are case-sensitive). `pid` is the registry key looked
 * up in [CloudConnectionRegistry] for the access token, refreshed on the fly.
 *
 * Seek = a fresh ranged request (`Range: bytes=<pos>-`), which the Drive download
 * endpoint honours. No local cache: limited TV storage is exactly why we stream.
 */
class CloudDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var currentUri: Uri? = null
    private var response: Response? = null
    private var stream: InputStream? = null
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        currentUri = uri
        transferInitializing(dataSpec)

        val pid = uri.getQueryParameter("pid")
            ?: throw IOException("cloud URI missing pid query parameter: $uri")
        val fileId = uri.lastPathSegment?.let { URLDecoder.decode(it, "UTF-8") }
            ?: throw IOException("cloud URI missing file id: $uri")

        val token = CloudConnectionRegistry.validAccessToken(pid)
            ?: throw IOException("No cloud token for provider $pid (was it disconnected?)")

        val position = dataSpec.position
        val length = dataSpec.length
        val rangeHeader = if (length == C.LENGTH_UNSET.toLong()) {
            "bytes=$position-"
        } else {
            "bytes=$position-${position + length - 1}"
        }

        val req = Request.Builder()
            .url(GoogleDriveClient.downloadUrl(fileId))
            .header("Authorization", "Bearer $token")
            .header("Range", rangeHeader)
            .get()
            .build()

        val resp = try {
            http.newCall(req).execute()
        } catch (t: Throwable) {
            throw IOException("Failed to open cloud stream for $fileId", t)
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw IOException("Cloud stream HTTP $code for $fileId")
        }
        // A 200 to a mid-file Range request is the WHOLE file from byte 0: reading it as
        // if it started at `position` would feed the extractor the wrong bytes (PLAY-16).
        if (!rangeHonoured(position, resp.code)) {
            val code = resp.code
            resp.close()
            throw IOException("Cloud stream ignored Range (HTTP $code at $position) for $fileId")
        }
        response = resp
        val body = resp.body ?: throw IOException("Empty cloud response body for $fileId")

        // Content-Length is the length of THIS (possibly ranged) response, i.e. the
        // bytes remaining from `position`. Fall back to dataSpec.length / UNSET.
        val contentLen = body.contentLength()
        bytesRemaining = when {
            length != C.LENGTH_UNSET.toLong() -> length
            contentLen != -1L -> contentLen
            else -> C.LENGTH_UNSET.toLong()
        }
        stream = body.byteStream()

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        val n = stream!!.read(buffer, offset, want)
        if (n == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            runCatching { stream?.close() }
            runCatching { response?.close() }
        } finally {
            stream = null
            response = null
            bytesRemaining = 0L
            currentUri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = CloudDataSource()
    }

    companion object {
        // Finite read timeout: it bounds a single socket read, so a paused player (no
        // read outstanding) isn't affected, but a stalled server fails the read instead
        // of hanging the loader thread forever (PLAY-16). Connect stays bounded too.
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** A request from [position] > 0 must come back 206 Partial Content. */
        internal fun rangeHonoured(position: Long, code: Int): Boolean = position == 0L || code == 206
    }
}
