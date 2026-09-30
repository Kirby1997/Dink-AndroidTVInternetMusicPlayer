@file:OptIn(UnstableApi::class)

package com.example.dink_smb_player.data.source

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.dink_smb_player.data.source.smb.DinkDataSourceFactory

/**
 * Reads an MP3's playback duration the way FFmpeg / foobar2000 do — WITHOUT scanning the
 * whole file. The platform [android.media.MediaMetadataRetriever] on this MediaTek box
 * ignores the Xing/Info VBR header and rescans every frame to end-of-file, which over SMB
 * means pulling ~the entire track per probe (megabytes × 25k tracks, frequently timing out
 * and falling back to a bogus partial duration). Instead we:
 *
 *   1. Skip the ID3v2 tag(s) (each one's size is in its own 10-byte header — we never read
 *      the tag bytes, including any embedded cover art; we seek straight past them). Some
 *      taggers STACK several ID3v2 tags back to back, so we loop until audio.
 *   2. Read ~4KB at the first audio frame and parse its MPEG header — trusted only when the
 *      NEXT frame header (at header + frame length) is valid and matches too. A lone 0xFFEx
 *      pair in junk/padding otherwise passes for a sync and gives a wildly wrong duration.
 *   3. If a Xing/Info (or VBRI) VBR header is present, take the exact frame count → exact
 *      duration. Otherwise assume CBR and divide the audio byte length (minus any trailing
 *      ID3v1/APE tag) by the bitrate — but only after a frame from MID-file shows the same
 *      bitrate. A headerless VBR file fails that check and returns null, rather than storing
 *      a duration estimated from its first frame alone.
 *
 * Total transfer is a few short positioned reads (~8KB), not the whole file. Returns null on
 * anything it doesn't confidently understand (free-format, corrupt sync, non-MP3, headerless
 * VBR) so the caller can fall back to the platform retriever. Over SMB it reads through the
 * [SmbProbe] handle, shared with the rest of the file's probe when a session is active.
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

    /** Upper bound on stacked ID3v2 tags we walk past before giving up. */
    private const val MAX_ID3_TAGS = 8

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

    internal fun parse(r: RandomReader): Long? {
        val size = r.size()
        if (size <= 0) return null

        // --- ID3v2 skip (stacked tags too) --------------------------------------------------
        val h = ByteArray(10)
        var audioStart = 0L
        for (tag in 0 until MAX_ID3_TAGS) {
            if (r.readFully(audioStart, h, 10) < 10) return null
            if (!(h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte())) break
            // 28-bit sync-safe size (7 bits per byte) of the tag body, plus its 10-byte header
            // and an optional 10-byte footer.
            val tagSize = (h[6].toInt() and 0x7F shl 21) or (h[7].toInt() and 0x7F shl 14) or
                (h[8].toInt() and 0x7F shl 7) or (h[9].toInt() and 0x7F)
            val footer = if (h[5].toInt() and 0x10 != 0) 10 else 0
            audioStart += 10L + tagSize + footer
            if (audioStart >= size) return null
        }
        if (audioStart >= size) return null

        // --- First frame + VBR header --------------------------------------------------
        val buf = ByteArray(4096)
        val n = r.readFully(audioStart, buf, buf.size)
        if (n < 36) return null

        // Find the frame sync within the buffer (junk bytes can precede it). A candidate counts
        // only if the header one frame later is also valid and consistent.
        val first = findSync(r, buf, n, audioStart, size) ?: return null
        val (i, frame) = first
        val trailing = trailingTagBytes(r, size)
        return durationFor(r, frame, buf, i, n, audioStart + i, size - trailing)
    }

    /** First offset in [buf] holding a frame header whose successor frame header is valid and
     *  consistent with it, or null. [bufPos] is [buf]'s file offset. */
    private fun findSync(r: RandomReader, buf: ByteArray, n: Int, bufPos: Long, size: Long): Pair<Int, Frame>? {
        val next = ByteArray(4)
        var i = 0
        while (i <= n - 4) {
            if (buf[i] == 0xFF.toByte() && (buf[i + 1].toInt() and 0xE0) == 0xE0) {
                val frame = parseFrameHeader(buf, i)
                if (frame != null) {
                    val nextOff = i + frame.frameLen
                    val ok = if (nextOff + 4 <= n) {
                        sameStream(frame, parseFrameHeader(buf, nextOff))
                    } else {
                        val pos = bufPos + nextOff
                        pos + 4 <= size && r.readFully(pos, next, 4) == 4 &&
                            sameStream(frame, parseFrameHeader(next, 0))
                    }
                    if (ok) return i to frame
                }
            }
            i++
        }
        return null
    }

    /** True when [b] is a real header of the same stream as [a] (version, layer, sample rate —
     *  the fields that can't change between frames; the bitrate can, in VBR). */
    private fun sameStream(a: Frame, b: Frame?): Boolean =
        b != null && b.mpeg1 == a.mpeg1 && b.layer == a.layer && b.sampleRate == a.sampleRate

    /** Bytes of trailing ID3v1 (128) and APEv2 tags at EOF — not audio, so excluded from the
     *  CBR byte count (an APE tag with cover art can be a megabyte = a minute at 128 kbps). */
    private fun trailingTagBytes(r: RandomReader, size: Long): Long {
        if (size < 160) return 0
        val tail = ByteArray(160)
        if (r.readFully(size - 160, tail, 160) < 160) return 0
        var trailing = 0L
        if (tail[32] == 'T'.code.toByte() && tail[33] == 'A'.code.toByte() && tail[34] == 'G'.code.toByte()) trailing = 128
        // APE footer: 32 bytes ending at EOF, or just before the ID3v1 tag.
        val footerAt = if (trailing == 128L) 0 else 128
        if (String(tail, footerAt, 8, Charsets.US_ASCII) == "APETAGEX") {
            val tagSize = leInt(tail, footerAt + 12).toLong() and 0xFFFFFFFFL // items + footer
            val hasHeader = leInt(tail, footerAt + 20) and (1 shl 31) != 0
            val apeLen = tagSize + if (hasHeader) 32 else 0
            if (apeLen in 32..(size - trailing)) trailing += apeLen
        }
        return trailing
    }

    internal class Frame(
        val mpeg1: Boolean,
        val layer: Int,
        val mono: Boolean,
        val bitrateKbps: Int,
        val sampleRate: Int,
        val samplesPerFrame: Int,
        val frameLen: Int,
    )

    private fun parseFrameHeader(b: ByteArray, i: Int): Frame? {
        if (i < 0 || i + 4 > b.size) return null
        if (b[i] != 0xFF.toByte() || (b[i + 1].toInt() and 0xE0) != 0xE0) return null
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
        val bitrate = BITRATE[if (mpeg1) 1 else 0][layerIdx][brIndex]
        if (bitrate <= 0) return null
        val sampleRate = (SAMPLE_RATE[verBits] ?: return null)[srIndex]
        if (sampleRate <= 0) return null

        val samplesPerFrame = when {
            layerBits == 3 -> 384                  // Layer I
            layerBits == 2 -> 1152                 // Layer II
            else -> if (mpeg1) 1152 else 576       // Layer III
        }
        // Frame length in bytes (used to find the next frame and the VBR header tail).
        val frameLen = if (layerBits == 3) {
            ((12 * bitrate * 1000 / sampleRate) + padding) * 4
        } else {
            (samplesPerFrame / 8 * bitrate * 1000 / sampleRate) + padding
        }
        if (frameLen < 4) return null
        return Frame(mpeg1, layerIdx + 1, chMode == 3, bitrate, sampleRate, samplesPerFrame, frameLen)
    }

    private fun durationFor(
        r: RandomReader,
        f: Frame,
        b: ByteArray,
        frameStart: Int,
        bufLen: Int,
        firstFramePos: Long,
        audioEnd: Long,
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

        // CBR fallback: audio byte length / bitrate. For a true CBR file the first-frame
        // bitrate is the file bitrate, so this is exact. A headerless VBR file would come out
        // wrong (by the ratio of first-frame to average bitrate), so check a mid-file frame
        // first: a different bitrate there means VBR → null (let the exact scan decide).
        val audioBytes = audioEnd - firstFramePos
        if (audioBytes <= 0) return null
        val midBitrate = bitrateNear(r, f, firstFramePos + audioBytes / 2, audioEnd)
        if (midBitrate != null && midBitrate != f.bitrateKbps) return null
        return audioBytes * 8L / f.bitrateKbps // (bytes*8) / kbps == ms
    }

    /** Bitrate of the first confirmed frame at or after [pos] (two consistent consecutive
     *  headers, same stream as [f]), or null when none is found nearby. */
    private fun bitrateNear(r: RandomReader, f: Frame, pos: Long, audioEnd: Long): Int? {
        val buf = ByteArray(4096)
        val n = r.readFully(pos, buf, minOf(buf.size.toLong(), audioEnd - pos).toInt().coerceAtLeast(0))
        var i = 0
        while (i <= n - 4) {
            val a = parseFrameHeader(buf, i)
            if (a != null && sameStream(f, a) && i + a.frameLen + 4 <= n &&
                sameStream(f, parseFrameHeader(buf, i + a.frameLen))
            ) return a.bitrateKbps
            i++
        }
        return null
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

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or (b[o + 1].toInt() and 0xFF shl 8) or
            (b[o + 2].toInt() and 0xFF shl 16) or (b[o + 3].toInt() and 0xFF shl 24)

    private fun beInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF shl 24) or (b[o + 1].toInt() and 0xFF shl 16) or
            (b[o + 2].toInt() and 0xFF shl 8) or (b[o + 3].toInt() and 0xFF)

    // ---- Positioned byte access -------------------------------------------------------

    internal interface RandomReader {
        fun size(): Long
        /** Reads up to [len] bytes at absolute [pos], looping until [len] or EOF. Returns count. */
        fun readFully(pos: Long, dst: ByteArray, len: Int): Int
        fun close()
    }

    private fun openReader(context: Context, uri: String): RandomReader? {
        if (uri.startsWith("smb", ignoreCase = true)) {
            // The probe session's shared handle when one is active (see SmbProbe).
            val file = try {
                SmbProbe.open(uri)
            } catch (t: Throwable) {
                return null
            }
            return object : RandomReader {
                override fun size() = file.length
                override fun readFully(pos: Long, dst: ByteArray, len: Int): Int {
                    var got = 0
                    while (got < len) {
                        val n = file.readAt(pos + got, dst, got, len - got)
                        if (n <= 0) break
                        got += n
                    }
                    return got
                }
                override fun close() { file.release() }
            }
        }
        // Generic (file:// / http(s)://) — reopen per positioned read. Only a
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
