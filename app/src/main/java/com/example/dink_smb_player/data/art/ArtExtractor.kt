@file:OptIn(UnstableApi::class)

package com.example.dink_smb_player.data.art

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.example.dink_smb_player.data.source.Media3MediaDataSource
import com.example.dink_smb_player.data.source.ReadFailures
import com.example.dink_smb_player.data.source.ReadResult
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
 * Blocks (network) — call from Dispatchers.IO. Returns [ReadResult.Absent] when the file has
 * no picture (or is gone / unparseable) and [ReadResult.Error] when a transient failure (NAS
 * down, dropped connection, timeout) cut the read short — the cache must only remember the
 * former as "no art".
 */
object ArtExtractor {

    /** Embedded covers run ~50 KB–2 MB; allow headroom for a front-loaded APIC plus the
     *  container header. Capped so an art-less file with a huge moov can't pull forever. */
    private const val PROBE_BUDGET_BYTES = 24L * 1024 * 1024

    fun extract(context: Context, uri: String): ReadResult<ByteArray> {
        val retriever = MediaMetadataRetriever()
        val src = Media3MediaDataSource(context.applicationContext, uri, PROBE_BUDGET_BYTES)
        return try {
            retriever.setDataSource(src)
            classify(retriever.embeddedPicture, src.failure)
        } catch (t: Throwable) {
            // setDataSource's own exception is opaque ("setDataSource failed"); the data
            // source recorded the real I/O cause, if there was one.
            classify(null, src.failure)
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
     * so other schemes are Absent without any network round-trips. A transient failure on
     * any candidate is an Error (the rest would fail the same way, so stop there).
     */
    fun extractFolderImage(context: Context, sampleUri: String): ReadResult<ByteArray> {
        val uri = Uri.parse(sampleUri)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "smb" && scheme != "file") return ReadResult.Absent
        val segments = uri.pathSegments
        if (segments.size < 1) return ReadResult.Absent
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
            when (val r = readWhole(context, candidate)) {
                is ReadResult.Found, is ReadResult.Error -> return r
                ReadResult.Absent -> Unit // not there — try the next name
            }
        }
        return ReadResult.Absent
    }

    /** Found when [bytes] is a non-empty picture; otherwise Error if a transient [failure]
     *  cut the read short, else Absent. */
    internal fun classify(bytes: ByteArray?, failure: Throwable?): ReadResult<ByteArray> = when {
        bytes != null && bytes.isNotEmpty() -> ReadResult.Found(bytes)
        ReadFailures.isTransient(failure) -> ReadResult.Error(failure)
        else -> ReadResult.Absent
    }

    /** Read an entire (small) file via the Media3 data-source stack, capped. Absent when
     *  the file doesn't exist / is empty / exceeds the cap; Error on a transient failure. */
    private fun readWhole(context: Context, uri: String): ReadResult<ByteArray> {
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
                if (total > FOLDER_IMAGE_CAP) return ReadResult.Absent
            }
            classify(out.toByteArray(), null)
        } catch (t: Throwable) {
            classify(null, t)
        } finally {
            runCatching { ds.close() }
        }
    }
}
