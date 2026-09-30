package com.example.dink_smb_player.data.index

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The cloud (Google Drive) source was removed. An index written while it existed must
 * still load — a decode failure would be treated as a corrupt library — and its rows
 * are dropped, since nothing can play or refresh them any more.
 */
class LegacyCloudTest {

    private fun row(id: String, type: SourceType) = TrackEntity(
        id = id, title = id, artist = "A", albumTitle = "B", durationMs = 1000,
        sourceType = type, sourceId = "src-$type", path = "/x/$id.mp3", uri = "x://$id",
        sizeBytes = 1, addedAtMs = 1,
    )

    private fun source(id: String, type: SourceType) =
        SourceEntity(id = id, type = type, displayName = id, createdAtMs = 0)

    @Test
    fun `a row written by the cloud source still decodes`() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(TrackEntity.serializer(), row("c", SourceType.Cloud))
        assertEquals(SourceType.Cloud, json.decodeFromString(TrackEntity.serializer(), encoded).sourceType)
    }

    @Test
    fun `cloud tracks and sources are dropped, the rest kept in order`() {
        val tracks = listOf(row("a", SourceType.Smb), row("c", SourceType.Cloud), row("l", SourceType.Local))
        assertEquals(listOf("a", "l"), tracks.withoutLegacyCloud().map { it.id })

        val sources = listOf(source("nas", SourceType.Smb), source("gdrive", SourceType.Cloud))
        assertEquals(listOf("nas"), sources.withoutLegacyCloud().map { it.id })
    }

    @Test
    fun `nothing to drop returns the same list, so restore knows not to rewrite the index`() {
        val tracks = listOf(row("a", SourceType.Smb), row("l", SourceType.Local))
        assertSame(tracks, tracks.withoutLegacyCloud())
        val sources = listOf(source("nas", SourceType.Smb))
        assertSame(sources, sources.withoutLegacyCloud())
    }
}
