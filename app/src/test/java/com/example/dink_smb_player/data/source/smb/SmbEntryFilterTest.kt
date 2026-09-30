package com.example.dink_smb_player.data.source.smb

import com.hierynomus.msfscc.FileAttributes
import org.junit.Assert.assertEquals
import org.junit.Test

/** SRC-7: which listing entries the walk and the share browser drop. */
class SmbEntryFilterTest {

    private fun attrs(vararg a: FileAttributes): Long = a.fold(0L) { acc, x -> acc or x.value }

    private val dir = attrs(FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
    private val file = attrs(FileAttributes.FILE_ATTRIBUTE_ARCHIVE)

    @Test
    fun `entry table`() {
        val cases = listOf(
            // name, attributes, ignored?
            Triple("Music", dir, false),
            Triple("song.mp3", file, false),
            Triple("song.flac", attrs(FileAttributes.FILE_ATTRIBUTE_NORMAL), false),
            Triple(".hidden-but-not-flagged.mp3", file, false),
            Triple("#recycled album", dir, false),
            Triple("eaDir", dir, false),
            // NAS housekeeping folders, any case, even without the hidden bit.
            Triple("@eaDir", dir, true),
            Triple("@EADIR", dir, true),
            Triple("#recycle", dir, true),
            Triple("#snapshot", dir, true),
            Triple("\$RECYCLE.BIN", dir, true),
            Triple("System Volume Information", dir, true),
            Triple("@Recycle", dir, true),
            Triple("@Recently-Snapshot", dir, true),
            Triple(".@__thumb", dir, true),
            Triple(".AppleDouble", dir, true),
            // A FILE that happens to be called like a housekeeping folder is not special.
            Triple("@eaDir", file, false),
            // Hidden / system — files and folders.
            Triple("Private", attrs(FileAttributes.FILE_ATTRIBUTE_DIRECTORY, FileAttributes.FILE_ATTRIBUTE_HIDDEN), true),
            Triple("song.mp3", attrs(FileAttributes.FILE_ATTRIBUTE_HIDDEN), true),
            Triple("Boot", attrs(FileAttributes.FILE_ATTRIBUTE_DIRECTORY, FileAttributes.FILE_ATTRIBUTE_SYSTEM), true),
            Triple("desktop.ini", attrs(FileAttributes.FILE_ATTRIBUTE_SYSTEM, FileAttributes.FILE_ATTRIBUTE_ARCHIVE), true),
            // Reparse points: a junction/symlink FOLDER can loop; a reparse FILE is real data
            // (Windows dedup, cloud placeholder) and is kept.
            Triple("Loop", attrs(FileAttributes.FILE_ATTRIBUTE_DIRECTORY, FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT), true),
            Triple("dedup.flac", attrs(FileAttributes.FILE_ATTRIBUTE_ARCHIVE, FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT), false),
            // AppleDouble resource forks — files only.
            Triple("._song.mp3", file, true),
            Triple("._", file, true),
            Triple("._Folder", dir, false),
        )
        for ((name, a, expected) in cases) {
            assertEquals("'$name' attrs=0x${a.toString(16)}", expected, isIgnoredSmbEntry(name, a))
        }
    }

    // Review #6: the walk prunes under Junk, but keeps indexed rows under Unwalked folders.
    @Test
    fun `walk kind table`() {
        val hiddenDir = attrs(FileAttributes.FILE_ATTRIBUTE_DIRECTORY, FileAttributes.FILE_ATTRIBUTE_HIDDEN)
        val cases = listOf(
            Triple("Music", dir, SmbEntryKind.Keep),
            Triple("song.mp3", file, SmbEntryKind.Keep),
            Triple("dedup.flac", attrs(FileAttributes.FILE_ATTRIBUTE_ARCHIVE, FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT), SmbEntryKind.Keep),
            // Housekeeping stays junk — even when also hidden/system (as Windows marks them).
            Triple("@eaDir", dir, SmbEntryKind.Junk),
            Triple("\$RECYCLE.BIN", attrs(FileAttributes.FILE_ATTRIBUTE_DIRECTORY, FileAttributes.FILE_ATTRIBUTE_HIDDEN, FileAttributes.FILE_ATTRIBUTE_SYSTEM), SmbEntryKind.Junk),
            Triple("System Volume Information", hiddenDir, SmbEntryKind.Junk),
            Triple(".@__thumb", hiddenDir, SmbEntryKind.Junk),
            Triple("._song.mp3", file, SmbEntryKind.Junk),
            Triple("song.mp3", attrs(FileAttributes.FILE_ATTRIBUTE_HIDDEN), SmbEntryKind.Junk),
            // Junctions / OneDrive Files-On-Demand folders / generic hidden folders: not walked,
            // but what's indexed under them is kept.
            Triple("Loop", attrs(FileAttributes.FILE_ATTRIBUTE_DIRECTORY, FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT), SmbEntryKind.Unwalked),
            Triple("OneDrive Music", attrs(FileAttributes.FILE_ATTRIBUTE_DIRECTORY, FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT, FileAttributes.FILE_ATTRIBUTE_READONLY), SmbEntryKind.Unwalked),
            Triple("Private", hiddenDir, SmbEntryKind.Unwalked),
            Triple("Boot", attrs(FileAttributes.FILE_ATTRIBUTE_DIRECTORY, FileAttributes.FILE_ATTRIBUTE_SYSTEM), SmbEntryKind.Unwalked),
        )
        for ((name, a, expected) in cases) {
            assertEquals("'$name' attrs=0x${a.toString(16)}", expected, smbEntryKind(name, a))
        }
    }
}
