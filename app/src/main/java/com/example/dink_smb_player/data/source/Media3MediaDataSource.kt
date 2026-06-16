package com.example.dink_smb_player.data.source

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.example.dink_smb_player.data.source.smb.DinkDataSourceFactory

/**
 * [MediaDataSource] adapter over a Media3 [androidx.media3.datasource.DataSource]
 * (smb:// / gdrive:// / file:// / http(s)://, dispatched by [DinkDataSourceFactory]).
 * Lets the platform [android.media.MediaMetadataRetriever] read a remote track's
 * header / metadata atom over smbj or HTTP Range — no full download.
 *
 * Opens a fresh positioned Media3 source per [readAt] — wasteful, but a metadata probe
 * issues only a handful of reads, and correctness over a re-mountable SMB stream matters
 * more than churn. A byte [budgetBytes] caps total transfer so a probe can never fall
 * through to pulling a whole file (past budget → EOF; the retriever stops).
 *
 * Used by [DurationReader] (duration) and [com.example.dink_smb_player.data.art.ArtExtractor]
 * (embedded cover art).
 */
internal class Media3MediaDataSource(
    context: Context,
    private val uri: String,
    private val budgetBytes: Long,
) : MediaDataSource() {

    private val factory = DinkDataSourceFactory(context.applicationContext)
    private var cachedSize: Long = -2 // -2 = not probed yet; -1 = unknown; >=0 = known
    private var consumed: Long = 0

    override fun getSize(): Long {
        if (cachedSize != -2L) return cachedSize
        val ds = factory.createDataSource()
        cachedSize = try {
            val len = ds.open(DataSpec(Uri.parse(uri)))
            if (len == C.LENGTH_UNSET.toLong()) -1 else len
        } catch (t: Throwable) {
            -1
        } finally {
            runCatching { ds.close() }
        }
        return cachedSize
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (consumed >= budgetBytes) return -1 // budget spent: stop the probe (EOF)
        val ds = factory.createDataSource()
        return try {
            ds.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(size.toLong()).build())
            var got = 0
            while (got < size) {
                val n = ds.read(buffer, offset + got, size - got)
                if (n == C.RESULT_END_OF_INPUT) break
                got += n
            }
            consumed += got
            if (got == 0) -1 else got
        } catch (t: Throwable) {
            -1
        } finally {
            runCatching { ds.close() }
        }
    }

    override fun close() {}
}
