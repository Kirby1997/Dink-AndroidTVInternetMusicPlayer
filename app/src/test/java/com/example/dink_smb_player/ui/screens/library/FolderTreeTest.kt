package com.example.dink_smb_player.ui.screens.library

import com.example.dink_smb_player.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTreeTest {

    private fun song(path: String, id: String = path, title: String = path.substringAfterLast('/')) = Song(
        id = id, title = title, artist = "A", albumId = null, albumTitle = null,
        durationSec = 60, playCount = 0, sourcePath = path, bitrate = "MP3",
    )

    /** Node id under the test share `//h/s` (SMB → dir keys are lowercase). */
    private fun hs(vararg dirs: String) = FolderTree.nodeId("//h/s", dirs.toList())
    private fun d(id: String) = TreeRow.folderKey(id)
    private fun t(songId: String) = TreeRow.trackKey(songId)
    private fun keys(rows: List<TreeRow>) = rows.map { it.key }

    @After
    fun reset() = FolderTreeMemo.clear()

    // ---- source splitting ----

    @Test
    fun smbPathSplitsAtShareAndUsesShareLabel() {
        val s = FolderTree.splitSource("//nas/music/Rock/a.mp3", mapOf("//nas/music" to "Attic NAS"))
        assertEquals("//nas/music", s.rootKey)
        assertEquals("Attic NAS", s.rootLabel)
        assertEquals(listOf("Rock", "a.mp3"), s.segments)
        assertTrue(s.caseInsensitive)
    }

    @Test
    fun smbRootKeyIsLowercasedButFallbackLabelKeepsSpelling() {
        val s = FolderTree.splitSource("//NAS/Music/a.mp3", emptyMap())
        assertEquals("//nas/music", s.rootKey)
        assertEquals("Music on NAS", s.rootLabel)
        assertEquals("Attic", FolderTree.splitSource("//NAS/Music/a.mp3", mapOf("//nas/music" to "Attic")).rootLabel)
    }

    @Test
    fun localVolumesSplitPerVolume() {
        val internal = FolderTree.splitSource("/storage/emulated/0/Music/a.mp3", emptyMap())
        assertEquals("/storage/emulated/0", internal.rootKey)
        assertEquals("Internal storage", internal.rootLabel)
        assertEquals(listOf("Music", "a.mp3"), internal.segments)
        assertFalse(internal.caseInsensitive)

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

    @Test
    fun idsFromDifferentSourcesNeverCollide() {
        // A device-root "music/share" dir vs an SMB share //music/share.
        val device = FolderTree.build(listOf(song("/music/share/a.mp3"))).single()
        val smb = FolderTree.build(listOf(song("//music/share/a.mp3"))).single()
        val deviceLeaf = device.children.single()
        assertTrue(deviceLeaf.id != smb.id)
        assertEquals("/", FolderTree.rootKeyOf(deviceLeaf.id))
        assertEquals("//music/share", FolderTree.rootKeyOf(smb.id))
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
    fun smbRootsAndDirsMergeIgnoringCaseShowingFirstSpelling() {
        val roots = FolderTree.build(
            listOf(
                song("//NAS/Music/Rock/a.mp3"),
                song("//nas/music/ROCK/b.mp3"),
                song("//Nas/Music/rock/Live/c.mp3"),
            ),
            mapOf("//nas/music" to "Attic"),
        )
        val root = roots.single()
        assertEquals("Attic", root.name)
        assertEquals(3, root.trackCount)
        val rock = root.children.single()
        assertEquals("Rock", rock.name)
        assertEquals(2, rock.tracks.size)
        assertEquals(FolderTree.nodeId("//nas/music", listOf("rock")), rock.id)
        assertEquals("Live", rock.children.single().name)
    }

    @Test
    fun localDirsStayCaseSensitive() {
        val root = FolderTree.build(
            listOf(song("/storage/emulated/0/Music/a.mp3"), song("/storage/emulated/0/music/b.mp3")),
        ).single()
        assertEquals(setOf("Music", "music"), root.children.map { it.name }.toSet())
        assertEquals(2, root.children.size)
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
        assertEquals("Bjork", chain.leafName)
        assertEquals(FolderTree.nodeId("//nas/music", listOf("music", "artists", "bjork")), chain.id)
        assertEquals(listOf("Debut", "Post"), chain.children.map { it.name })
        assertEquals(3, chain.trackCount)
    }

    @Test
    fun leafNameIsTheDirectoryItselfWhenNotCompacted() {
        val root = FolderTree.build(listOf(song("//h/s/Best of 1999-2004/x.mp3")), mapOf("//h/s" to "NAS")).single()
        val dir = root.children.single()
        assertEquals("Best of 1999-2004", dir.name)
        assertEquals("Best of 1999-2004", dir.leafName)
        assertEquals("NAS", root.leafName)
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
    private val openSet = setOf(hs(), hs("a"), hs("a", "b"))

    @Test
    fun collapsedTreeShowsOnlyRoots() {
        val rows = FolderTree.flatten(FolderTree.build(sample), emptySet())
        assertEquals(listOf(d(hs())), keys(rows))
        assertEquals(0, rows[0].depth)
    }

    @Test
    fun expandedRowsNestWithDepthAndParents() {
        val rows = FolderTree.flatten(FolderTree.build(sample), openSet)
        assertEquals(
            listOf(d(hs()), d(hs("a")), d(hs("a", "b")), t("1"), t("2"), d(hs("a", "c")), t("r")),
            keys(rows),
        )
        assertEquals(listOf(0, 1, 2, 3, 3, 2, 1), rows.map { it.depth })
        assertEquals(d(hs("a", "b")), rows[3].parentKey)
        assertEquals(d(hs()), rows[6].parentKey)
        val t2 = rows[4] as TreeRow.Track
        assertEquals(1, t2.indexInFolder)
        assertEquals(listOf("1", "2"), t2.folder.tracks.map { it.id })
    }

    @Test
    fun expandedChildOfCollapsedParentStaysHidden() {
        val rows = FolderTree.flatten(FolderTree.build(sample), setOf(hs("a", "b")))
        assertEquals(listOf(d(hs())), keys(rows))
    }

    // ---- pruning ----

    @Test
    fun pruneDropsVanishedFoldersOfPresentSourcesAndKeepsInstanceWhenClean() {
        val roots = FolderTree.build(sample)
        val clean = setOf(hs(), hs("a"))
        assertSame(clean, FolderTree.pruneExpanded(roots, clean))
        assertEquals(setOf(hs()), FolderTree.pruneExpanded(roots, setOf(hs(), hs("gone"))))
    }

    @Test
    fun pruneKeepsIdsOfAbsentSourcesAndIgnoresEmptyTree() {
        val roots = FolderTree.build(sample)
        val other = FolderTree.nodeId("//other/share", listOf("x"))
        val set = setOf(hs("a"), other)
        // //other/share isn't in the (partial) tree yet: its open folders survive.
        assertSame(set, FolderTree.pruneExpanded(roots, set))
        assertSame(set, FolderTree.pruneExpanded(emptyList(), set))
    }

    // ---- D-pad decisions ----

    private fun openRows() = FolderTree.flatten(FolderTree.build(sample), openSet)

    @Test
    fun leftCollapsesOpenFolderElseJumpsToParent() {
        val rows = openRows()
        assertEquals(TreeNav.Collapse(hs("a", "b")), TreeKeys.left(rows[2], openSet))
        assertEquals(TreeNav.FocusRow(d(hs("a", "b"))), TreeKeys.left(rows[3], openSet)) // track
        assertEquals(TreeNav.FocusRow(d(hs("a"))), TreeKeys.left(rows[5], openSet))      // collapsed C
        assertEquals(TreeNav.Collapse(hs()), TreeKeys.left(rows[0], openSet))           // open root
    }

    @Test
    fun leftOnCollapsedTopLevelRowPassesThroughToRail() {
        val rows = FolderTree.flatten(FolderTree.build(sample), emptySet())
        assertEquals(TreeNav.PassThrough, TreeKeys.left(rows[0], emptySet()))
    }

    @Test
    fun rightExpandsThenStepsIntoFirstChild() {
        val rows = openRows()
        assertEquals(TreeNav.Expand(hs("a", "c")), TreeKeys.right(rows[5], openSet))
        assertEquals(TreeNav.FocusRow(t("1")), TreeKeys.right(rows[2], openSet))      // tracks only
        assertEquals(TreeNav.FocusRow(d(hs("a"))), TreeKeys.right(rows[0], openSet))  // sub-folder first
        assertEquals(TreeNav.Consume, TreeKeys.right(rows[3], openSet))               // track
    }

    @Test
    fun keyDecisionsUseCurrentStateNotTheComposedRow() {
        // Row composed while collapsed; a key-repeat Right already expanded it.
        val staleRow = FolderTree.flatten(FolderTree.build(sample), emptySet())[0] as TreeRow.Folder
        assertFalse(staleRow.expanded)
        assertEquals(TreeNav.FocusRow(d(hs("a"))), TreeKeys.right(staleRow, setOf(hs())))
        assertEquals(TreeNav.Collapse(hs()), TreeKeys.left(staleRow, setOf(hs())))
    }

    // ---- memo ----

    @Test
    fun rapidExpandedUpdatesAreNeverLost() {
        // Burst of key-repeat events before any recomposition: each update reads the current set.
        FolderTreeMemo.expand(hs())
        FolderTreeMemo.expand(hs("a"))
        FolderTreeMemo.toggle(hs("a", "b"))
        FolderTreeMemo.toggle(hs("a", "c"))
        FolderTreeMemo.toggle(hs("a", "c"))
        assertEquals(setOf(hs(), hs("a"), hs("a", "b")), FolderTreeMemo.expanded.value)
        FolderTreeMemo.collapse(hs("a"))
        FolderTreeMemo.collapse(hs("a"))
        assertEquals(setOf(hs(), hs("a", "b")), FolderTreeMemo.expanded.value)
    }

    @Test
    fun memoPruneKeepsOpenFoldersOfAbsentSources() {
        val other = FolderTree.nodeId("//other/share", listOf("x"))
        FolderTreeMemo.expand(hs("a"))
        FolderTreeMemo.expand(hs("gone"))
        FolderTreeMemo.expand(other)
        FolderTreeMemo.prune(FolderTree.build(sample))
        assertEquals(setOf(hs("a"), other), FolderTreeMemo.expanded.value)
    }

    @Test
    fun memoReusesTreeForSameContentAndRebuildsOnChange() {
        val first = FolderTreeMemo.get(sample, emptyMap())
        assertSame(first, FolderTreeMemo.get(sample.toList(), emptyMap()))
        assertNotSame(first, FolderTreeMemo.get(sample, mapOf("//h/s" to "NAS")))
        val changed = sample + song("//h/s/new.mp3", id = "n")
        assertEquals(5, FolderTreeMemo.get(changed, mapOf("//h/s" to "NAS")).single().trackCount)
    }
}
