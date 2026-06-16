package com.example.dink_smb_player.data.art

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.example.dink_smb_player.data.source.Media3MediaDataSource
import com.example.dink_smb_player.data.source.smb.DinkDataSourceFactory
import java.io.ByteArrayOutputStream

/**
 * Pulls a track's EMBEDDED cover art (ID3 APIC / MP4 `covr` / FLAC PICTURE) — including
 * remote SMB / cloud tracks — WITHOUT downloading the whole file.
 *
 * Drives the platform [MediaMetadataRetriever] over [Media3MediaDataSource] (the same
 * bridge [com.example.dink_smb_player.data.source.DurationReader] uses), so the extractor
 * reads only the bytes around the picture atom over smbj / HTTP Range. A generous byte
 * budget is allowed because embedded art is bigger than a tag/duration probe (a few MB),
 * but it's still capped so a probe can never fall through to a full download.
 *
 * Blocks (network) — call from Dispatchers.IO. Returns null when there's no embedded
 * picture, the read times out, or anything goes wrong (caller falls back to procedural art).
 */
object ArtExtractor {

    /** Embedded covers run ~50 KB–2 MB; allow headroom for a front-loaded APIC plus the
     *  container header. Capped so an art-less file with a huge moov can't pull forever. */
    private const val PROBE_BUDGET_BYTES = 24L * 1024 * 1024

    fun extract(context: Context, uri: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        val src = Media3MediaDataSource(context.applicationContext, uri, PROBE_BUDGET_BYTES)
        return try {
            retriever.setDataSource(src)
            retriever.embeddedPicture
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { src.close() }
        }
    }

    /** Sidecar cover filenames to try, in order — the conventions ripping tools use. */
    private val FOLDER_IMAGE_NAMES = listOf(
        "cover.jpg", "folder.jpg", "front.jpg", "album.jpg", "albumart.jpg",
        "cover.png", "folder.png", "front.png",
    )

    /** Max sidecar image we'll pull whole (it's a separate file, so read fully — but cap
     *  so a stray huge file in the folder can't blow up the read). */
    private const val FOLDER_IMAGE_CAP = 8L * 1024 * 1024

    /**
     * Fallback for files with no embedded picture: look for a sibling cover image
     * (`cover.jpg` / `folder.jpg` / …) in the track's folder and read it whole. Many
     * ripped libraries store art this way instead of embedding it.
     *
     * Derives each candidate by swapping the last path segment of [sampleUri]; only
     * `smb://` and `file://` have a meaningful sibling path (cloud uses opaque file ids),
     * so other schemes return null without any network round-trips.
     */
    fun extractFolderImage(context: Context, sampleUri: String): ByteArray? {
        val uri = Uri.parse(sampleUri)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "smb" && scheme != "file") return null
        val segments = uri.pathSegments
        if (segments.size < 1) return null
        val parent = segments.dropLast(1)
        for (name in FOLDER_IMAGE_NAMES) {
            val candidate = Uri.Builder()
                .scheme(uri.scheme)
                .encodedAuthority(uri.encodedAuthority)
                .apply {
                    parent.forEach { appendPath(it) }
                    appendPath(name)
                }
                .encodedQuery(uri.encodedQuery) // preserve ?sid= for SMB
                .build()
                .toString()
            readWhole(context, candidate)?.let { return it }
        }
        return null
    }

    /** Read an entire (small) file via the Media3 data-source stack, capped. Returns null
     *  when the file doesn't exist / can't be opened / is empty / exceeds the cap. */
    private fun readWhole(context: Context, uri: String): ByteArray? {
        val ds = DinkDataSourceFactory(context.applicationContext).createDataSource()
        return try {
            ds.open(DataSpec(Uri.parse(uri)))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = ds.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
                total += n
                if (total > FOLDER_IMAGE_CAP) return null
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { ds.close() }
        }
    }
}
