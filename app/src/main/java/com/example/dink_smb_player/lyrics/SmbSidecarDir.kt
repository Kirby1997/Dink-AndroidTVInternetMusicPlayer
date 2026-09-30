@file:OptIn(UnstableApi::class)

package com.example.dink_smb_player.lyrics

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.example.dink_smb_player.data.source.smb.SmbClient
import com.example.dink_smb_player.data.source.smb.SmbConnectionRegistry
import com.example.dink_smb_player.data.source.smb.SmbDataSource
import com.hierynomus.msfscc.FileAttributes
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An SMB track's directory (LYR-1). Lists it once over the shared (non-playback) smbj
 * connection via [SmbClient.share] — the same path the share browser uses — and reads a
 * matched sidecar through [SmbDataSource], so the reconnect-once / lease plumbing is
 * reused rather than re-implemented.
 *
 * Built from the track's `smb://host:port/share/dir/file.ext?sid=<id>` URI (see
 * SmbSync.mediaUriFor: each path segment URL-encoded, spaces as %20).
 */
internal class SmbSidecarDir private constructor(
    private val sid: String,
    /** Backslash path of the directory relative to the share root ("" = root). */
    val dirPath: String,
    override val audioName: String,
    /** The track URI up to and including the last '/', for building sibling URIs. */
    private val uriDirPrefix: String,
    /** "?sid=..." query, carried onto sibling URIs. */
    private val query: String,
) : SidecarDir {

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        val share = SmbConnectionRegistry.share(sid) ?: throw IOException("Unknown SMB share id: $sid")
        val creds = SmbConnectionRegistry.creds(sid)
        val disk = SmbClient.share(share.id, share.host, share.port, share.shareName, creds)
        val entries = try {
            disk.list(dirPath)
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            // smbj throws RuntimeExceptions (SMBApiException, TransportException wrappers).
            throw IOException("SMB list failed for '$dirPath'", t)
        }
        entries.mapNotNull { e ->
            val name = e.fileName
            val isDir = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            if (isDir || name == "." || name == "..") null else name
        }
    }

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(siblingUri(name))
        val src = SmbDataSource(playback = false)
        try {
            val len = src.open(DataSpec(uri))
            if (len > SidecarDir.MAX_SIDECAR_BYTES) return@withContext null
            val out = java.io.ByteArrayOutputStream(if (len > 0) len.toInt() else 8192)
            val buf = ByteArray(16 * 1024)
            while (out.size() <= SidecarDir.MAX_SIDECAR_BYTES) {
                val n = src.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            runCatching { src.close() }
        }
    }

    /** smb:// URI of [name] in the same directory. */
    internal fun siblingUri(name: String): String =
        uriDirPrefix + URLEncoder.encode(name, "UTF-8").replace("+", "%20") + query

    companion object {
        /** Null when [mediaUri] isn't a well-formed Dink smb:// track URI. */
        internal fun parse(mediaUri: String): SmbSidecarDir? {
            if (!mediaUri.startsWith("smb://", ignoreCase = true)) return null
            val qIdx = mediaUri.indexOf('?')
            val base = if (qIdx >= 0) mediaUri.substring(0, qIdx) else mediaUri
            val query = if (qIdx >= 0) mediaUri.substring(qIdx) else ""
            val sid = query.removePrefix("?").split('&')
                .firstOrNull { it.startsWith("sid=") }
                ?.let { runCatching { URLDecoder.decode(it.removePrefix("sid="), "UTF-8") }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: return null
            // smb://host:port/share/dir/.../file
            val afterScheme = base.substring("smb://".length)
            val segments = afterScheme.split('/').drop(1) // drop host:port
            if (segments.size < 2) return null            // need share + file
            val decoded = segments.map {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() ?: return null
            }
            val audioName = decoded.last().ifBlank { return null }
            val dirPath = decoded.drop(1).dropLast(1).joinToString("\\")
            val prefix = base.substring(0, base.lastIndexOf('/') + 1)
            return SmbSidecarDir(sid, dirPath, audioName, prefix, query)
        }
    }
}
