package com.example.dink_smb_player.ui.screens.library

import com.example.dink_smb_player.data.model.Song
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-16: lowercase keys once per library instance; a superseded scan stops. */
class SearchIndexTest {

    private fun song(id: String, title: String, artist: String, album: String?) = Song(
        id = id, title = title, artist = artist, albumId = null, albumTitle = album,
        durationSec = 100, playCount = 0, sourcePath = "/$id.mp3", bitrate = "320",
    )

    private val library = listOf(
        song("1", "Wait and Bleed", "Slipknot", "Slipknot"),
        song("2", "Duality", "Slipknot", "Vol. 3"),
        song("3", "Bleed It Out", "Linkin Park", "Minutes to Midnight"),
    )

    @Test
    fun `index is reused for the same library instance and rebuilt for a new one`() {
        val a = SearchIndex.of(library)
        assertSame(a, SearchIndex.of(library))
        assertSame(a.titleKeys, SearchIndex.of(library).titleKeys)
        // Same content in a fresh list (a re-emission) reuses the index; a play-count-only
        // change does too; a title change rebuilds.
        assertSame(a, SearchIndex.of(library.toList()))
        assertSame(a, SearchIndex.of(library.map { it.copy(playCount = it.playCount + 5) }))
        assertNotSame(a, SearchIndex.of(library.map { it.copy(title = it.title + "!") }))
        // Folded title, then its punctuation-free form when that differs.
        assertArrayEquals(arrayOf("wait and bleed\nwaitandbleed", "duality", "bleed it out\nbleeditout"), a.titleKeys)
    }

    @Test
    fun `sameLibraryContent ignores play stats but not grouping fields`() {
        assertTrue(sameLibraryContent(library, library.map { it.copy(playCount = 9) }))
        assertFalse(sameLibraryContent(library, library.dropLast(1)))
        assertFalse(sameLibraryContent(library, library.map { it.copy(durationSec = 1) }))
        assertFalse(sameLibraryContent(library, library.map { it.copy(albumTitle = "X") }))
    }

    @Test
    fun `search shares GroupMemo groups with the screens`() {
        GroupMemo.clear()
        val idx = SearchIndex.of(library.map { it.copy(id = "z" + it.id) })
        val shared = GroupMemo.get("Artist", idx.library, ::artistGroups)
        assertSame(shared, GroupMemo.get("Artist", idx.library.toList(), ::artistGroups))
        assertEquals(shared.size, idx.artists.size)
        assertSame(shared, GroupMemo.get("Artist", idx.library, ::artistGroups))
    }

    @Test
    fun `facets match tokenised, case-insensitive`() = runBlocking {
        val idx = SearchIndex.of(library)
        assertEquals(listOf("3", "1"), search("BLEED", idx, SearchFacet.Songs).songs.map { it.id })
        assertEquals(listOf("1"), search("bleed wait", idx, SearchFacet.Songs).songs.map { it.id })
        assertEquals(listOf("Vol. 3"), search("vol", idx, SearchFacet.Albums).albums.map { it.title })
        val artists = search("slip", idx, SearchFacet.Artists).artists
        assertEquals(listOf("Slipknot"), artists.map { it.title })
        assertEquals(2, artists.single().songs.size)
        assertEquals(true, search("   ", idx, SearchFacet.Songs).isEmpty)
    }

    @Test
    fun `a cancelled search stops scanning`() = runBlocking {
        var finished = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            search("a", SearchIndex.of(library), SearchFacet.Songs)
            finished = true
        }
        job.join()
        assertFalse(finished)
    }

    // ---- UI-17: albums/artists grouped by the library keys ----

    private fun keyed(id: String, title: String, artist: String, album: String, artistKey: String, albumKey: String, label: String) =
        song(id, title, artist, album).copy(artistKey = artistKey, albumKey = albumKey, artistLabel = label)

    @Test
    fun `albums and artists group by keys, not raw strings`() = runBlocking {
        val lib = listOf(
            keyed("1", "Bohemian Rhapsody", "Queen", "Greatest Hits", "queen", "queen|greatesthits", "Queen"),
            keyed("2", "Killer Queen", "QUEEN", "Greatest hits", "queen", "queen|greatesthits", "Queen"),
            keyed("3", "Dancing Queen", "ABBA", "Greatest Hits", "abba", "abba|greatesthits", "ABBA"),
            keyed("4", "Under Pressure", "Queen feat. David Bowie", "Hot Space", "queen", "queen|hotspace", "Queen"),
        )
        val idx = SearchIndex.of(lib)
        val albums = search("greatest", idx, SearchFacet.Albums).albums
        // Two "Greatest Hits" (by artist), each merging its case variants.
        assertEquals(listOf("abba|greatesthits", "queen|greatesthits"), albums.map { it.key }.sorted())
        val queenGh = albums.single { it.key == "queen|greatesthits" }
        assertEquals(setOf("1", "2"), queenGh.songs.map { it.id }.toSet())
        assertEquals("Greatest Hits", queenGh.title)
        assertEquals("Queen · 2 tracks", queenGh.subtitle)

        // "Queen", "QUEEN" and the feat. credit are one artist, named by the clean label.
        val artists = search("queen", idx, SearchFacet.Artists).artists
        assertEquals(listOf("Queen"), artists.map { it.title })
        assertEquals(3, artists.single().songs.size)
        assertEquals("3 tracks · 2 albums", artists.single().subtitle)
    }

    // ---- Artist/album results are the library's own groups, selectable as such ----

    @Test
    fun `artist and album results are the same groups the Artists and Albums screens show`() = runBlocking {
        val lib = listOf(
            keyed("1", "Bohemian Rhapsody", "Queen", "Greatest Hits", "queen", "queen|greatesthits", "Queen"),
            keyed("2", "Under Pressure", "Queen feat. David Bowie", "Hot Space", "queen", "queen|hotspace", "Queen"),
            keyed("3", "Dancing Queen", "ABBA", "Arrival", "abba", "abba|arrival", "ABBA"),
        )
        val idx = SearchIndex.of(lib)
        // Same key/title/subtitle/tracks → LibraryDetail shows exactly what the Artists tile opens.
        assertEquals(artistGroups(lib).single { it.key == "queen" }, search("queen", idx, SearchFacet.Artists).artists.single())
        assertEquals(albumGroups(lib).single { it.key == "queen|hotspace" }, search("hot space", idx, SearchFacet.Albums).albums.single())
    }

    @Test
    fun `All facet returns artists, albums and songs, each ranked`() = runBlocking {
        val lib = listOf(
            keyed("1", "Bohemian Rhapsody", "Queen", "Greatest Hits", "queen", "queen|greatesthits", "Queen"),
            keyed("2", "Killer Queen", "Queen", "Sheer Heart Attack", "queen", "queen|sheerheartattack", "Queen"),
            keyed("3", "Dancing Queen", "ABBA", "Arrival", "abba", "abba|arrival", "ABBA"),
            keyed("4", "Go With the Flow", "Queens of the Stone Age", "Songs for the Deaf", "queensofthestoneage", "queensofthestoneage|songsforthedeaf", "Queens of the Stone Age"),
            keyed("5", "Bigmouth Strikes Again", "The Smiths", "The Queen Is Dead", "smiths", "smiths|queenisdead", "The Smiths"),
        )
        val r = search("queen", SearchIndex.of(lib), SearchFacet.All)
        assertEquals(SearchFacet.All, r.facet)
        // Exact name first, then a prefix match.
        assertEquals(listOf("Queen", "Queens of the Stone Age"), r.artists.map { it.title })
        assertEquals(listOf("The Queen Is Dead"), r.albums.map { it.title })
        assertEquals(listOf("Dancing Queen", "Killer Queen"), r.songs.map { it.title })
        // A single facet computes only its own kind.
        val artistsOnly = search("queen", SearchIndex.of(lib), SearchFacet.Artists)
        assertTrue(artistsOnly.albums.isEmpty() && artistsOnly.songs.isEmpty())
    }

    @Test
    fun `All facet previews each section but counts every match`() = runBlocking {
        val lib = (1..7).map { song("$it", "Track $it", "Band $it", "Album $it") }
        val r = search("band", SearchIndex.of(lib), SearchFacet.All)
        assertEquals(5, r.artists.size)
        assertEquals(7, r.artistTotal)
        assertEquals(7, search("band", SearchIndex.of(lib), SearchFacet.Artists).artists.size)
    }

    @Test
    fun `matching ignores accents and punctuation like the grouping does`() = runBlocking {
        val lib = listOf(
            song("1", "Ace of Spades", "Motörhead", "Ace of Spades"),
            song("2", "Thunderstruck", "AC/DC", "The Razors Edge"),
            song("3", "Don't Stop Me Now", "Queen", "Jazz"),
        )
        val idx = SearchIndex.of(lib)
        assertEquals(listOf("Motörhead"), search("motorhead", idx, SearchFacet.Artists).artists.map { it.title })
        assertEquals(listOf("Motörhead"), search("MOTÖR", idx, SearchFacet.Artists).artists.map { it.title })
        assertEquals(listOf("AC/DC"), search("acdc", idx, SearchFacet.Artists).artists.map { it.title })
        assertEquals(listOf("AC/DC"), search("ac-dc", idx, SearchFacet.Artists).artists.map { it.title })
        assertEquals(listOf("AC/DC"), search("ac dc", idx, SearchFacet.Artists).artists.map { it.title })
        assertEquals(listOf("AC/DC"), search("ac dc", idx, SearchFacet.All).artists.map { it.title })
        assertEquals(listOf("AC/DC"), search("ac - dc", idx, SearchFacet.Artists).artists.map { it.title })
        assertEquals(listOf("3"), search("dont stop", idx, SearchFacet.Songs).songs.map { it.id })
        assertEquals(listOf("The Razors Edge"), search("razor's", idx, SearchFacet.Albums).albums.map { it.title })
    }

    @Test
    fun `blank query is empty for every facet`() = runBlocking {
        SearchFacet.entries.forEach { f ->
            val r = search("  ", SearchIndex.of(library), f)
            assertTrue(r.isEmpty)
            assertEquals(f, r.facet)
        }
    }

    @Test
    fun `matchRank orders exact, prefix, word, then loose`() {
        assertEquals(0, matchRank(searchFold("Queen"), "queen"))
        assertEquals(0, matchRank(searchFold("AC/DC"), "acdc"))
        assertEquals(1, matchRank(searchFold("Queensryche"), "queen"))
        assertEquals(2, matchRank(searchFold("Dead Queen"), "queen"))
        assertEquals(3, matchRank(searchFold("Requeened"), "queen"))
    }
}
