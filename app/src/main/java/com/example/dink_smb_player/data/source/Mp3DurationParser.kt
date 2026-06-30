package com.example.dink_smb_player.data.source

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.dink_smb_player.data.source.smb.DinkDataSourceFactory
import com.example.dink_smb_player.data.source.smb.SmbDataSource

/**
 * Reads an MP3's playback duration the way FFmpeg / foobar2000 do — WITHOUT scanning the
 * whole file. The platform [android.media.MediaMetadataRetriever] on this MediaTek box
 * ignores the Xing/Info VBR header and rescans every frame to end-of-file, which over SMB
 * means pulling ~the entire track per probe (megabytes × 25k tracks, frequently timing out
 * and falling back to a bogus partial duration). Instead we:
 *
 *   1. Skip the ID3v2 tag (its size is in its own 10-byte header — we never read the tag
 *      bytes, including any embedded cover art; we seek straight past them).
 *   2. Read ~4KB at the first audio frame and parse its MPEG header.
 *   3. If a Xing/Info (or VBRI) VBR header is present, take the exact frame count → exact
 *      duration. Otherwise assume CBR and divide the audio byte length by the bitrate.
 *
 * Total transfer is two short positioned reads (~4KB), not the whole file. Returns null on
 * anything it doesn't confidently understand (free-format, corrupt sync, non-MP3) so the
 * caller can fall back to the platform retriever.
 */
object Mp3DurationParser {

    // [version][layer][bitrateIndex] → kbps. version: 0=MPEG2/2.5 (LSF), 1=MPEG1.
    private val BITRATE = arrayOf(
        // MPEG2 / 2.5
        arrayOf(
            intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, -1), // Layer I
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1),       // Layer II
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1),       // Layer III
        ),
        // MPEG1
        arrayOf(
            intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, -1), // Layer I
            intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, -1),    // Layer II
            intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1),     // Layer III
        ),
    )

    // [versionBits] → sample rate. versionBits: 0=MPEG2.5, 2=MPEG2, 3=MPEG1.
    private val SAMPLE_RATE = mapOf(
        3 to intArrayOf(44100, 48000, 32000, -1), // MPEG1
        2 to intArrayOf(22050, 24000, 16000, -1), // MPEG2
        0 to intArrayOf(11025, 12000, 8000, -1),  // MPEG2.5
    )

    fun read(context: Context, uri: String): Long? {
        val reader = openReader(context.applicationContext, uri) ?: return null
        return try {
            parse(reader)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun parse(r: RandomReader): Long? {
        val size = r.size()
        if (size <= 0) return null

        // --- ID3v2 skip ----------------------------------------------------------------
        val h = ByteArray(10)
        if (r.readFully(0, h, 10) < 10) return null
        var audioStart = 0L
        if (h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
            // 28-bit sync-safe size (7 bits per byte) of the tag body, plus its 10-byte header
            // and an optional 10-byte footer.
            val tagSize = (h[6].toInt() and 0x7F shl 21) or (h[7].toInt() and 0x7F shl 14) or
                (h[8].toInt() and 0x7F shl 7) or (h[9].toInt() and 0x7F)
            val footer = if (h[5].toInt() and 0x10 != 0) 10 else 0
            audioStart = 10L + tagSize + footer
        }
        if (audioStart >= size) return null

        // --- First frame + VBR header --------------------------------------------------
        val buf = ByteArray(4096)
        val n = r.readFully(audioStart, buf, buf.size)
        if (n < 36) return null

        // Find the frame sync within the first part of the buffer (a few junk bytes can
        // precede it). Require a self-consistent header before trusting it.
        var i = 0
        while (i <= n - 4) {
            if (buf[i] == 0xFF.toByte() && (buf[i + 1].toInt() and 0xE0) == 0xE0) {
                val frame = parseFrameHeader(buf, i)
                if (frame != null) return durationFor(frame, buf, i, n, audioStart, size)
            }
            i++
        }
        return null
    }

    private class Frame(
        val mpeg1: Boolean,
        val layerIII: Boolean,
        val mono: Boolean,
        val bitrateKbps: Int,
        val sampleRate: Int,
        val samplesPerFrame: Int,
        val frameLen: Int,
    )

    private fun parseFrameHeader(b: ByteArray, i: Int): Frame? {
        val verBits = (b[i + 1].toInt() shr 3) and 0x3
        if (verBits == 1) return null // reserved
        val layerBits = (b[i + 1].toInt() shr 1) and 0x3
        if (layerBits == 0) return null // reserved
        val brIndex = (b[i + 2].toInt() shr 4) and 0xF
        if (brIndex == 0 || brIndex == 15) return null // free-format / invalid → bail
        val srIndex = (b[i + 2].toInt() shr 2) and 0x3
        if (srIndex == 3) return null
        val padding = (b[i + 2].toInt() shr 1) and 0x1
        val chMode = (b[i + 3].toInt() shr 6) and 0x3

        val mpeg1 = verBits == 3
        // layerBits: 3=I, 2=II, 1=III
        val layerIdx = 3 - layerBits // → 0=I,1=II,2=III
        val layerIII = layerBits == 1
        val bitrate = BITRATE[if (mpeg1) 1 else 0][layerIdx][brIndex]
        if (bitrate <= 0) return null
        val sampleRate = (SAMPLE_RATE[verBits] ?: return null)[srIndex]
        if (sampleRate <= 0) return null

        val samplesPerFrame = when {
            layerBits == 3 -> 384                  // Layer I
            layerBits == 2 -> 1152                 // Layer II
            else -> if (mpeg1) 1152 else 576       // Layer III
        }
        // Frame length in bytes (used to locate the VBR header tail and for sanity).
        val frameLen = if (layerBits == 3) {
            ((12 * bitrate * 1000 / sampleRate) + padding) * 4
        } else {
            (samplesPerFrame / 8 * bitrate * 1000 / sampleRate) + padding
        }
        return Frame(mpeg1, layerIII, chMode == 3, bitrate, sampleRate, samplesPerFrame, frameLen)
    }

    private fun durationFor(
        f: Frame,
        b: ByteArray,
        frameStart: Int,
        bufLen: Int,
        audioStart: Long,
        fileSize: Long,
    ): Long? {
        // Xing/Info sits after the side-information block; VBRI sits at a fixed offset 32.
        val sideInfo = if (f.mpeg1) (if (f.mono) 17 else 32) else (if (f.mono) 9 else 17)
        val xingOff = frameStart + 4 + sideInfo
        val vbriOff = frameStart + 4 + 32

        val totalFrames = readVbrFrameCount(b, bufLen, xingOff, vbriOff)
        if (totalFrames != null && totalFrames > 0) {
            // Exact: frames × samples/frame / sampleRate.
            return totalFrames.toLong() * f.samplesPerFrame * 1000L / f.sampleRate
        }

        // CBR fallback: audio byte length / bitrate. (For a true CBR file the first-frame
        // bitrate is the file bitrate, so this is exact; for a headerless VBR it's an
        // approximation — acceptable as we only reach here without a VBR header.)
        val audioBytes = fileSize - audioStart
        if (audioBytes <= 0) return null
        return audioBytes * 8L / f.bitrateKbps // (bytes*8) / kbps == ms
    }

    /** Returns the VBR frame count from a Xing/Info or VBRI header, or null if neither is present. */
    private fun readVbrFrameCount(b: ByteArray, bufLen: Int, xingOff: Int, vbriOff: Int): Int? {
        if (xingOff in 0..(bufLen - 12)) {
            val tag = String(b, xingOff, 4, Charsets.US_ASCII)
            if (tag == "Xing" || tag == "Info") {
                val flags = beInt(b, xingOff + 4)
                if (flags and 0x1 != 0) return beInt(b, xingOff + 8) // frames field present
            }
        }
        if (vbriOff in 0..(bufLen - 18)) {
            if (String(b, vbriOff, 4, Charsets.US_ASCII) == "VBRI") {
                return beInt(b, vbriOff + 14) // VBRI frame count
            }
        }
        return null
    }

    private fun beInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF shl 24) or (b[o + 1].toInt() and 0xFF shl 16) or
            (b[o + 2].toInt() and 0xFF shl 8) or (b[o + 3].toInt() and 0xFF)

    // ---- Positioned byte access -------------------------------------------------------

    private interface RandomReader {
        fun size(): Long
        /** Reads up to [len] bytes at absolute [pos], looping until [len] or EOF. Returns count. */
        fun readFully(pos: Long, dst: ByteArray, len: Int): Int
        fun close()
    }

    private fun openReader(context: Context, uri: String): RandomReader? {
        if (uri.startsWith("smb", ignoreCase = true)) {
            val ds = SmbDataSource()
            val total = try {
                ds.open(DataSpec.Builder().setUri(uri).setPosition(0).build())
            } catch (t: Throwable) {
                runCatching { ds.close() }
                return null
            }
            return object : RandomReader {
                override fun size() = total
                override fun readFully(pos: Long, dst: ByteArray, len: Int): Int {
                    var got = 0
                    while (got < len) {
                        val n = ds.readAtOffset(pos + got, dst, got, len - got)
                        if (n <= 0) break
                        got += n
                    }
                    return got
                }
                override fun close() { runCatching { ds.close() } }
            }
        }
        // Generic (file:// / http(s):// / gdrive://) — reopen per positioned read. Only a
        // couple of reads happen, so the reopen cost is negligible here.
        val factory = DinkDataSourceFactory(context)
        val sizeDs = factory.createDataSource()
        val total = try {
            val len = sizeDs.open(DataSpec(Uri.parse(uri)))
            if (len == C.LENGTH_UNSET.toLong()) -1L else len
        } catch (t: Throwable) {
            -1L
        } finally {
            runCatching { sizeDs.close() }
        }
        if (total <= 0) return null
        return object : RandomReader {
            override fun size() = total
            override fun readFully(pos: Long, dst: ByteArray, len: Int): Int {
                val ds = factory.createDataSource()
                return try {
                    ds.open(DataSpec.Builder().setUri(uri).setPosition(pos).build())
                    var got = 0
                    while (got < len) {
                        val n = ds.read(dst, got, len - got)
                        if (n == C.RESULT_END_OF_INPUT) break
                        got += n
                    }
                    got
                } catch (t: Throwable) {
                    0
                } finally {
                    runCatching { ds.close() }
                }
            }
            override fun close() {}
        }
    }
}
