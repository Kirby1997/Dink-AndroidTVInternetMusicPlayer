package com.example.dink_smb_player.lyrics

import android.net.Uri
import com.example.dink_smb_player.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The directory a track lives in, as far as sidecar lookup needs it: listed ONCE per
 * resolve, then only the matching `.lrc` / `.txt` is read. Implemented for local files
 * ([LocalSidecarDir]) and SMB shares ([SmbSidecarDir]) — sidecars on SMB, the main
 * source, used to go through java.io.File and were never found (LYR-1).
 */
internal interface SidecarDir {
    /** The track's own file name, extension included. */
    val audioName: String

    /** File names in the directory. Throws on a transient failure (NAS unreachable). */
    suspend fun list(): List<String>

    /** Bytes of [name] in the directory (capped at [MAX_SIDECAR_BYTES]); null if unreadable. */
    suspend fun read(name: String): ByteArray?

    companion object {
        /** A lyric file is a few KB; anything past this isn't one worth reading. */
        const val MAX_SIDECAR_BYTES = 1024 * 1024
    }
}

/** Names of the sidecars found next to a track (either may be null). */
internal data class SidecarNames(val lrc: String?, val txt: String?)

/**
 * Sidecar `.lrc` / `.txt` lyric files next to the media file (foo_openlyrics-style —
 * user-curated, so they rank first). Matching, against ONE directory listing:
 *   1. `{basename}.lrc` — same name as the audio file, case-insensitive.
 *   2. A file whose name is the track's title (optionally `Artist - Title`, optionally
 *      with a leading track number) once case / punctuation / spacing are ignored —
 *      see [nameMatches]. Handles "Slipknot - Wait And Bleed.lrc" next to "WaitAndBleed.mp3".
 */
object SidecarLyrics {

    /** The track's directory, or null when its source has no listable directory (SAF
     *  single-document URIs). */
    internal fun dirFor(song: Song): SidecarDir? {
        val uri = song.mediaUri
        if (uri != null && uri.startsWith("smb://", ignoreCase = true)) {
            return SmbSidecarDir.parse(uri)
        }
        val path = when {
            uri != null && uri.startsWith("file://", ignoreCase = true) -> Uri.parse(uri).path
            else -> song.sourcePath
        }
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        return LocalSidecarDir(file, song.title, song.artist)
    }

    /** Pick the `.lrc` and `.txt` sidecars for a track out of one directory listing. */
    internal fun locate(names: List<String>, audioName: String, title: String, artist: String): SidecarNames {
        val base = audioName.substringBeforeLast('.', audioName)
        fun find(ext: String): String? {
            val files = names.filter { it.length > ext.length && it.endsWith(ext, ignoreCase = true) }
            return files.firstOrNull { it.dropLast(ext.length).equals(base, ignoreCase = true) }
                ?: files.firstOrNull { nameMatches(it.dropLast(ext.length), title, artist) }
        }
        return SidecarNames(lrc = find(".lrc"), txt = find(".txt"))
    }

    private val TRACK_NO_PREFIX = Regex("""^\d{1,3}\s*[-._)]?\s*""")

    /**
     * True when a sidecar basename is the same name as the track once case,
     * punctuation and spacing are ignored: `Title`, `Artist - Title`, either with
     * a leading track number (`01 - Title`, `01. Artist - Title`).
     */
    internal fun nameMatches(fileBase: String, title: String, artist: String): Boolean {
        val t = normalize(title)
        if (t.isBlank()) return false
        val a = normalize(artist)
        val wanted = if (a.isBlank()) setOf(t) else setOf(t, a + t)
        return normalize(fileBase) in wanted ||
            normalize(fileBase.replace(TRACK_NO_PREFIX, "")) in wanted
    }

    /** Explicit sidecar names to probe when a directory can't be listed (Android 13+
     *  scoped storage blocks File.listFiles() but not File.exists()). */
    internal fun probeCandidates(audioName: String, title: String, artist: String): List<String> {
        val bases = LinkedHashSet<String>()
        bases += audioName.substringBeforeLast('.', audioName)
        if (title.isNotBlank()) {
            bases += title
            if (artist.isNotBlank()) {
                bases += "$artist - $title"
                bases += "$artist-$title"
                bases += "${artist}_$title"
            }
        }
        return bases.flatMap { b -> listOf(".lrc", ".LRC", ".txt", ".TXT").map { b + it } }
    }

    // Unicode-aware so CJK / accented titles don't normalise to "" and never match.
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    private fun normalize(s: String): String =
        s.lowercase().replace(NON_ALNUM, "")
}

/** A local directory (file:// or a MediaStore DATA path). */
internal class LocalSidecarDir(
    private val audio: File,
    private val title: String,
    private val artist: String,
) : SidecarDir {
    override val audioName: String = audio.name

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        val dir = audio.parentFile ?: return@withContext emptyList()
        runCatching { dir.list()?.toList() }.getOrNull()
            ?: SidecarLyrics.probeCandidates(audioName, title, artist).filter { File(dir, it).isFile }
    }

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = File(audio.parentFile ?: return@withContext null, name)
        if (!f.isFile || !f.canRead() || f.length() > SidecarDir.MAX_SIDECAR_BYTES) null
        else runCatching { f.readBytes() }.getOrNull()
    }
}
