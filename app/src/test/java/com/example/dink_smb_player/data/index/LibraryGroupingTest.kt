package com.example.dink_smb_player.data.index

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryGroupingTest {

    @Test
    fun `normKey folds cosmetic variants`() {
        val cases = listOf(
            // Featured-artist suffixes need a name after the marker.
            "Artist feat. Guest" to "artist",
            "Artist (feat. Guest)" to "artist",
            "Artist [ft. Guest]" to "artist",
            "Artist ft Guest" to "artist",
            "Artist Featuring Guest" to "artist",
            "Artist feat.Guest" to "artist",
            // ...so a band whose name ENDS in feat keeps it (LIB-14).
            "Little Feat" to "littlefeat",
            "LITTLE FEAT" to "littlefeat",
            "Little Feat (Live)" to "littlefeatlive",
            "Little Feat." to "littlefeat",
            // Not a feat marker at all.
            "Daft Punk" to "daftpunk",
            "Feats Don't Fail Me Now" to "featsdontfailmenow",
            // Leading "The", punctuation, accents; non-Latin letters survive.
            "The Beatles" to "beatles",
            "AC/DC" to "acdc",
            "AC-DC" to "acdc",
            "Motörhead" to "motorhead",
            "Кино" to "кино",
            // Nothing alphanumeric left → falls back to the trimmed lowercase raw.
            "  ?!  " to "?!",
        )
        for ((raw, expected) in cases) assertEquals("normKey(\"$raw\")", expected, LibraryGrouping.normKey(raw))
    }

    private fun track(i: Int, artist: String?) = TrackEntity(
        id = "t$i",
        title = "Song $i",
        artist = artist,
        durationMs = 0L,
        sourceType = SourceType.Smb,
        sourceId = "s",
        path = "/m/$i.mp3",
        uri = "smb://nas/m/$i.mp3",
        sizeBytes = 0L,
        addedAtMs = 0L,
    )

    /** artistKey/artistLabel for [target], computed alongside the rest of [library]. */
    private fun keyFor(target: String, library: List<String>): Pair<String?, String?> {
        val rows = (library + target).mapIndexed { i, a -> track(i, a) }
        val out = LibraryGrouping.computeGroupingKeys(rows).last()
        return out.artistKey to out.artistLabel
    }

    @Test
    fun `primary artist attribution`() {
        data class Case(val artist: String, val library: List<String>, val key: String, val label: String)
        val cases = listOf(
            Case("Little Feat", emptyList(), "littlefeat", "Little Feat"),
            // A solo act called "Little" must not swallow the band.
            Case("Little Feat", listOf("Little"), "littlefeat", "Little Feat"),
            // Comma-separated names with no known solo member stay whole (LIB-14).
            Case("Crosby, Stills, Nash & Young", emptyList(), "crosbystillsnashyoung", "Crosby, Stills, Nash & Young"),
            Case("Earth, Wind & Fire", emptyList(), "earthwindfire", "Earth, Wind & Fire"),
            Case("Emerson, Lake & Palmer", listOf("Other Band"), "emersonlakepalmer", "Emerson, Lake & Palmer"),
            // ...but a known solo member still wins.
            Case("Apocalyptica, Guest Singer", listOf("Apocalyptica"), "apocalyptica", "Apocalyptica"),
            Case("Guest, Apocalyptica", listOf("Apocalyptica"), "apocalyptica", "Apocalyptica"),
            // Non-comma collaborations with no solo stats keep filing under the lead.
            Case("Artist A & Artist B", emptyList(), "artista", "Artist A"),
            Case("Artist A / Artist B", emptyList(), "artista", "Artist A"),
            Case("Lead feat. Guest", emptyList(), "lead", "Lead"),
            Case("Lead (feat. Guest)", emptyList(), "lead", "Lead"),
            // A recurring slash name is a name, not a collaboration.
            Case("AC/DC", listOf("AC/DC", "AC/DC"), "acdc", "AC/DC"),
        )
        for (c in cases) {
            assertEquals("key for \"${c.artist}\"", c.key to c.label, keyFor(c.artist, c.library))
        }
    }

    @Test
    fun `null artist groups as Unknown`() {
        assertEquals("unknown" to "Unknown", keyFor("Unknown", emptyList()))
        val out = LibraryGrouping.computeGroupingKeys(listOf(track(0, null))).single()
        assertEquals("unknown", out.artistKey)
    }

    // ---- LIB-6: album identity = album artist + title ----

    private fun albumRow(
        id: String,
        artist: String?,
        album: String?,
        folder: String,
        albumArtist: String? = null,
    ) = TrackEntity(
        id = id, title = id, artist = artist, albumTitle = album, albumArtist = albumArtist,
        durationMs = 0L, sourceType = SourceType.Smb, sourceId = "s",
        path = "$folder/$id.mp3", uri = "smb://nas$folder/$id.mp3", sizeBytes = 0L, addedAtMs = 0L,
    )

    /** Album key of each row, by id, computed over [rows] as one library. */
    private fun albumKeys(vararg rows: TrackEntity): Map<String, String?> =
        LibraryGrouping.computeGroupingKeys(rows.toList()).associate { it.id to it.albumKey }

    @Test
    fun `same title by different artists splits, same artist merges`() {
        val k = albumKeys(
            albumRow("q1", "Queen", "Greatest Hits", "/Queen/Greatest Hits"),
            albumRow("q2", "Queen", "Greatest Hits", "/Queen/Greatest Hits"),
            albumRow("a1", "ABBA", "Greatest Hits", "/ABBA/Greatest Hits"),
            // Same artist + title in another folder (a second rip, a CD2 folder) → same album.
            albumRow("q3", "Queen", "Greatest Hits", "/Queen/Greatest Hits/CD2"),
        )
        assertEquals("queen|greatesthits", k["q1"])
        assertEquals(k["q1"], k["q2"])
        assertEquals(k["q1"], k["q3"])
        assertEquals("abba|greatesthits", k["a1"])
    }

    @Test
    fun `folder-derived CD1 albums of different artists split`() {
        // Untagged: title = parent folder ("CD1"), artist = grandparent (the album folder).
        val k = albumKeys(
            albumRow("x", "Album One", "CD1", "/A/Album One/CD1"),
            albumRow("y", "Album Two", "CD1", "/B/Album Two/CD1"),
        )
        assertEquals("albumone|cd1", k["x"])
        assertEquals("albumtwo|cd1", k["y"])
    }

    @Test
    fun `case, punctuation and feat variants still merge`() {
        val k = albumKeys(
            albumRow("1", "The Beatles", "Abbey Road", "/b/1"),
            albumRow("2", "Beatles feat. Billy Preston", "ABBEY ROAD", "/b/2"),
            albumRow("3", "the beatles", "Abbey Road.", "/b/3"),
        )
        assertEquals(setOf("beatles|abbeyroad"), k.values.toSet())
    }

    @Test
    fun `album-artist tag wins`() {
        val k = albumKeys(
            // Tagged compilation: every track files under the tag, whatever its own artist.
            albumRow("1", "Artist A", "Tribute", "/t", albumArtist = "Various Artists"),
            albumRow("2", "Artist B", "Tribute", "/t", albumArtist = "Various Artists"),
            // Tag names a different act than the track artist.
            albumRow("3", "Guest Singer", "Live", "/l", albumArtist = "The Band"),
            albumRow("4", "The Band", "Live", "/l", albumArtist = "The Band"),
            // An untagged track in a tagged album's folder adopts the tag.
            albumRow("5", "Someone Else", "Live", "/l"),
            // VA spelled "VA".
            albumRow("6", "Artist C", "Mix", "/m", albumArtist = "VA"),
        )
        assertEquals("variousartists|tribute", k["1"])
        assertEquals(k["1"], k["2"])
        assertEquals("band|live", k["3"])
        assertEquals("band|live", k["4"])
        assertEquals("band|live", k["5"])
        assertEquals("variousartists|mix", k["6"])
    }

    @Test
    fun `untagged compilation folder stays one album`() {
        val k = albumKeys(
            albumRow("1", "Artist A", "Now 50", "/c/Now 50"),
            albumRow("2", "Artist B", "Now 50", "/c/Now 50"),
            albumRow("3", "Artist C", "Now 50", "/c/Now 50"),
            albumRow("4", "Artist D & Artist E", "Now 50", "/c/Now 50"),
            // Disc 2 in its own folder, still a compilation → same album.
            albumRow("5", "Artist F", "Now 50", "/c/Now 50/CD2"),
            albumRow("6", "Artist G", "Now 50", "/c/Now 50/CD2"),
        )
        assertEquals(setOf("variousartists|now50"), k.values.toSet())
    }

    @Test
    fun `artist album with a guest-led track stays with the artist`() {
        val k = albumKeys(
            // Guest is a known solo act elsewhere, so "Guest & Main" files under Guest as an
            // artist — but the track credits Main, whose album this folder is.
            albumRow("g0", "Guest", "Solo Record", "/guest"),
            albumRow("g1", "Guest", "Solo Record", "/guest"),
            albumRow("g2", "Guest", "Solo Record", "/guest"),
            albumRow("g3", "Guest", "Solo Record", "/guest"),
            albumRow("1", "Main", "Record", "/main/Record"),
            albumRow("2", "Main", "Record", "/main/Record"),
            albumRow("3", "Main", "Record", "/main/Record"),
            albumRow("4", "Guest & Main", "Record", "/main/Record"),
            // A flat folder mixing two artists' same-titled albums: the minority artist,
            // uncredited on the majority's tracks, keeps its own album.
            albumRow("q1", "Queen", "Greatest Hits", "/flat"),
            albumRow("q2", "Queen", "Greatest Hits", "/flat"),
            albumRow("q3", "Queen", "Greatest Hits", "/flat"),
            albumRow("a1", "ABBA", "Greatest Hits", "/flat"),
        )
        assertEquals(setOf("main|record"), listOf("1", "2", "3", "4").map { k[it] }.toSet())
        assertEquals("guest|solorecord", k["g0"])
        assertEquals("queen|greatesthits", k["q1"])
        assertEquals("abba|greatesthits", k["a1"])
    }

    @Test
    fun `stale keys are detected for migration`() {
        val keyed = LibraryGrouping.computeGroupingKeys(listOf(albumRow("1", "A", "B", "/f"))).single()
        assertEquals(false, LibraryGrouping.keysStale(keyed))
        assertEquals(true, LibraryGrouping.keysStale(keyed.copy(albumKey = "b")))  // pre-LIB-6
        assertEquals(true, LibraryGrouping.keysStale(keyed.copy(artistKey = null)))
        assertEquals(true, LibraryGrouping.keysStale(keyed.copy(albumKey = null)))
        assertEquals("a", LibraryGrouping.albumArtistKeyOf(keyed.albumKey!!))
        assertEquals(null, LibraryGrouping.albumArtistKeyOf("titleonly"))
        assertEquals("a|b", LibraryGrouping.fallbackAlbumKey("A", "B"))
        assertEquals("unknown|unknownalbum", LibraryGrouping.fallbackAlbumKey(null, null))
    }
}
