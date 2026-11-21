package com.example.dink_smb_player.ui.screens.library

import com.example.dink_smb_player.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTreeTest {

    private fun song(path: String, id: String = path, title: String = path.substringAfterLast('/')) = Song(
        id = id, title = title, artist = "A", albumId = null, albumTitle = null,
        durationSec = 60, playCount = 0, sourcePath = path, bitrate = "MP3",
    )

    private fun ids(rows: List<TreeRow>) = rows.map { it.key }

    @After
    fun reset() = FolderTreeMemo.clear()

    // ---- source splitting ----

    @Test
    fun smbPathSplitsAtShareAndUsesShareLabel() {
        val s = FolderTree.splitSource("//nas/music/Rock/a.mp3", mapOf("//nas/music" to "Attic NAS"))
        assertEquals("//nas/music", s.rootKey)
        assertEquals("Attic NAS", s.rootLabel)
        assertEquals(listOf("Rock", "a.mp3"), s.segments)
    }

    @Test
    fun smbLabelFallsBackToShareOnHost() {
        assertEquals("music on nas", FolderTree.splitSource("//nas/music/a.mp3", emptyMap()).rootLabel)
    }

    @Test
    fun localVolumesSplitPerVolume() {
        val internal = FolderTree.splitSource("/storage/emulated/0/Music/a.mp3", emptyMap())
        assertEquals("/storage/emulated/0", internal.rootKey)
        assertEquals("Internal storage", internal.rootLabel)
        assertEquals(listOf("Music", "a.mp3"), internal.segments)

        val usb = FolderTree.splitSource("/storage/1A2B-3C4D/Jazz/b.flac", mapOf("/storage/1a2b-3c4d" to "SanDisk"))
        assertEquals("/storage/1A2B-3C4D", usb.rootKey)
        assertEquals("SanDisk", usb.rootLabel)
        assertEquals("USB / SD (X)", FolderTree.splitSource("/storage/X/b.flac", emptyMap()).rootLabel)
    }

    @Test
    fun backslashesAreNormalised() {
        val s = FolderTree.splitSource("\\\\nas\\music\\Rock\\a.mp3", emptyMap())
        assertEquals("//nas/music", s.rootKey)
        assertEquals(listOf("Rock", "a.mp3"), s.segments)
    }

    // ---- building, compaction, counts ----

    @Test
    fun buildsSourceRootsThenDirectoriesWithTracksAsLeaves() {
        val roots = FolderTree.build(
            listOf(
                song("//nas/music/Rock/AC/b.mp3"),
                song("//nas/music/Rock/AC/a.mp3"),
                song("//nas/music/Rock/Zep/c.mp3"),
                song("/storage/emulated/0/Music/d.mp3"),
            ),
            mapOf("//nas/music" to "NAS"),
        )
        assertEquals(listOf("Internal storage", "NAS"), roots.map { it.name })
        val nas = roots[1]
        assertTrue(nas.isRoot)
        assertEquals(3, nas.trackCount)
        // Rock has two sub-dirs, so it's not compacted.
        val rock = nas.children.single()
        assertEquals("Rock", rock.name)
        assertEquals(listOf("AC", "Zep"), rock.children.map { it.name })
        // Tracks sort by file name.
        assertEquals(listOf("a.mp3", "b.mp3"), rock.children[0].tracks.map { FolderTree.fileNameOf(it) })
        assertEquals(2, rock.children[0].trackCount)
    }

    @Test
    fun emptySingleChildChainsCompactButRootsDoNot() {
        val roots = FolderTree.build(
            listOf(
                song("//nas/music/Music/Artists/Bjork/Post/1.flac"),
                song("//nas/music/Music/Artists/Bjork/Post/2.flac"),
                song("//nas/music/Music/Artists/Bjork/Debut/1.flac"),
            ),
        )
        val root = roots.single()
        assertEquals("music on nas", root.name)
        val chain = root.children.single()
        assertEquals("Music / Artists / Bjork", chain.name)
        assertEquals("//nas/music/Music/Artists/Bjork", chain.id)
        assertEquals(listOf("Debut", "Post"), chain.children.map { it.name })
        assertEquals(3, chain.trackCount)
    }

    @Test
    fun directoryWithTracksIsNotCompactedIntoItsChild() {
        val roots = FolderTree.build(
            listOf(song("//h/s/A/x.mp3"), song("//h/s/A/B/y.mp3")),
        )
        val a = roots.single().children.single()
        assertEquals("A", a.name)
        assertEquals("B", a.children.single().name)
        assertEquals(1, a.tracks.size)
        assertEquals(2, a.trackCount)
    }

    @Test
    fun tracksDirectlyInTheSourceRootHangOffTheRoot() {
        val root = FolderTree.build(listOf(song("//h/s/loose.mp3"))).single()
        assertEquals(1, root.tracks.size)
        assertTrue(root.children.isEmpty())
    }

    @Test
    fun allTracksIsDisplayOrderSubfoldersFirst() {
        val root = FolderTree.build(
            listOf(song("//h/s/A/z.mp3"), song("//h/s/A/B/y.mp3"), song("//h/s/A/C/x.mp3")),
        ).single()
        val a = root.children.single()
        assertEquals(listOf("y.mp3", "x.mp3", "z.mp3"), a.allTracks().map { FolderTree.fileNameOf(it) })
    }

    // ---- flattening ----

    private val sample = listOf(
        song("//h/s/A/B/1.mp3", id = "1"),
        song("//h/s/A/B/2.mp3", id = "2"),
        song("//h/s/A/C/3.mp3", id = "3"),
        song("//h/s/root.mp3", id = "r"),
    )

    @Test
    fun collapsedTreeShowsOnlyRoots() {
        val rows = FolderTree.flatten(FolderTree.build(sample), emptySet())
        assertEquals(listOf("d://h/s"), ids(rows))
        assertEquals(0, rows[0].depth)
    }

    @Test
    fun expandedRowsNestWithDepthAndParents() {
        val roots = FolderTree.build(sample)
        val rows = FolderTree.flatten(roots, setOf("//h/s", "//h/s/A", "//h/s/A/B"))
        assertEquals(
            listOf("d://h/s", "d://h/s/A", "d://h/s/A/B", "t:1", "t:2", "d://h/s/A/C", "t:r"),
            ids(rows),
        )
        assertEquals(listOf(0, 1, 2, 3, 3, 2, 1), rows.map { it.depth })
        assertEquals("d://h/s/A/B", rows[3].parentKey)
        assertEquals("d://h/s", rows[6].parentKey)
        val t2 = rows[4] as TreeRow.Track
        assertEquals(1, t2.indexInFolder)
        assertEquals(listOf("1", "2"), t2.folder.tracks.map { it.id })
    }

    @Test
    fun expandedChildOfCollapsedParentStaysHidden() {
        val rows = FolderTree.flatten(FolderTree.build(sample), setOf("//h/s/A/B"))
        assertEquals(listOf("d://h/s"), ids(rows))
    }

    @Test
    fun pruneDropsVanishedIdsAndKeepsInstanceWhenClean() {
        val roots = FolderTree.build(sample)
        val clean = setOf("//h/s", "//h/s/A")
        assertSame(clean, FolderTree.pruneExpanded(roots, clean))
        assertEquals(setOf("//h/s"), FolderTree.pruneExpanded(roots, setOf("//h/s", "//gone")))
    }

    // ---- D-pad decisions ----

    private fun openRows() = FolderTree.flatten(FolderTree.build(sample), setOf("//h/s", "//h/s/A", "//h/s/A/B"))

    @Test
    fun leftCollapsesOpenFolderElseJumpsToParent() {
        val rows = openRows()
        assertEquals(TreeNav.Collapse("//h/s/A/B"), TreeKeys.left(rows, 2))
        assertEquals(TreeNav.FocusRow("d://h/s/A/B"), TreeKeys.left(rows, 3)) // track
        assertEquals(TreeNav.FocusRow("d://h/s/A"), TreeKeys.left(rows, 5))   // collapsed C
        assertEquals(TreeNav.Collapse("//h/s"), TreeKeys.left(rows, 0))       // open root
    }

    @Test
    fun leftOnCollapsedTopLevelRowPassesThroughToRail() {
        val rows = FolderTree.flatten(FolderTree.build(sample), emptySet())
        assertEquals(TreeNav.PassThrough, TreeKeys.left(rows, 0))
    }

    @Test
    fun rightExpandsThenStepsIntoFirstChild() {
        val rows = openRows()
        assertEquals(TreeNav.Expand("//h/s/A/C"), TreeKeys.right(rows, 5))
        assertEquals(TreeNav.FocusRow("t:1"), TreeKeys.right(rows, 2))
        assertEquals(TreeNav.FocusRow("d://h/s/A"), TreeKeys.right(rows, 0))
        assertEquals(TreeNav.Consume, TreeKeys.right(rows, 3)) // track
    }

    // ---- memo ----

    @Test
    fun memoReusesTreeForSameContentAndRebuildsOnChange() {
        val first = FolderTreeMemo.get(sample, emptyMap())
        assertSame(first, FolderTreeMemo.get(sample.toList(), emptyMap()))
        assertNotSame(first, FolderTreeMemo.get(sample, mapOf("//h/s" to "NAS")))
        val changed = sample + song("//h/s/new.mp3", id = "n")
        assertEquals(5, FolderTreeMemo.get(changed, mapOf("//h/s" to "NAS")).single().trackCount)
    }
}
