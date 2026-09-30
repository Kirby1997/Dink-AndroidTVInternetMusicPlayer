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
        assertNotSame(a, SearchIndex.of(library.toList()))
        assertArrayEquals(arrayOf("wait and bleed", "duality", "bleed it out"), a.titleKeys)
    }

    @Test
    fun `facets match tokenised, case-insensitive`() = runBlocking {
        val idx = SearchIndex.of(library)
        assertEquals(listOf("3", "1"), search("BLEED", idx, SearchFacet.Songs).songs.map { it.id })
        assertEquals(listOf("1"), search("bleed wait", idx, SearchFacet.Songs).songs.map { it.id })
        assertEquals(listOf("Vol. 3"), search("vol", idx, SearchFacet.Albums).albums.map { it.title })
        val artists = search("slip", idx, SearchFacet.Artists).artists
        assertEquals(listOf("Slipknot"), artists.map { it.name })
        assertEquals(2, artists.single().trackCount)
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
        assertEquals(listOf("Queen"), artists.map { it.name })
        assertEquals(3, artists.single().trackCount)
        assertEquals(setOf("queen|greatesthits", "queen|hotspace"), artists.single().albums.map { it.key }.toSet())
    }
}
