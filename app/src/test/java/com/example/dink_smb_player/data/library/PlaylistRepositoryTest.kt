package com.example.dink_smb_player.data.library

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/** LIB-8: playlists restore-before-mutate, corrupt load never overwritten, .bak fallback. */
class PlaylistRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var ctx: Context
    private val main get() = File(tmp.root, "playlists.json")

    @Before
    fun setUp() {
        ctx = mock()
        whenever(ctx.applicationContext).thenReturn(ctx)
        whenever(ctx.filesDir).thenReturn(tmp.root)
        PlaylistStore.resetForTest()
        PlaylistRepository.resetForTest()
    }

    @After
    fun tearDown() {
        PlaylistStore.resetForTest()
        PlaylistRepository.resetForTest()
    }

    /** Simulate a process restart: fresh in-memory state, same files. */
    private fun restart() {
        PlaylistStore.resetForTest()
        PlaylistRepository.resetForTest()
    }

    private fun names() = PlaylistRepository.playlists.value.map { it.name }

    @Test
    fun createPersistsAndSurvivesRestart() = runBlocking {
        val id = PlaylistRepository.create(ctx, "Road", seedSongId = "s1")
        assertNotNull(id)
        restart()
        assertTrue(PlaylistRepository.ensureRestored(ctx))
        assertEquals(listOf("Road"), names())
        assertEquals(listOf("s1"), PlaylistRepository.playlists.value.single().songIds)
    }

    @Test
    fun mutatorRestoresFirstSoEarlyCreateDoesNotWipe() = runBlocking {
        PlaylistRepository.create(ctx, "A")
        restart()
        // No explicit ensureRestored — e.g. the context menu fires before DinkApp's restore.
        assertNotNull(PlaylistRepository.create(ctx, "B"))
        restart()
        PlaylistRepository.ensureRestored(ctx)
        assertEquals(listOf("A", "B"), names())
    }

    @Test
    fun corruptLoadDisablesPersistenceAndPreservesFile() = runBlocking {
        main.writeText("{\"playlists\":[{\"id\":\"x\",")
        assertTrue(PlaylistRepository.ensureRestored(ctx))
        assertTrue(names().isEmpty())
        // The mutation applies in memory but reports "not saved" so the UI can say so...
        assertNull(PlaylistRepository.create(ctx, "New"))
        assertEquals(listOf("New"), names())
        assertFalse(PlaylistRepository.addSong(ctx, PlaylistRepository.playlists.value.single().id, "s"))
        // ...and nothing was written: the original bytes survive as a timestamped copy.
        assertFalse(main.exists())
        val copies = SafeFiles.corruptCopies(main)
        assertEquals(1, copies.size)
        assertEquals("{\"playlists\":[{\"id\":\"x\",", copies.single().readText())
    }

    @Test
    fun corruptLoadFallsBackToBackup() = runBlocking {
        PlaylistRepository.create(ctx, "A")
        restart()
        PlaylistRepository.create(ctx, "B")    // first save of this "process" rotates [A] into .bak
        main.writeText("not json")
        restart()
        assertTrue(PlaylistRepository.ensureRestored(ctx))
        assertEquals(listOf("A"), names())
        // Restored from backup → persistence enabled again, and the next save doesn't
        // copy the corrupt file over the backup.
        assertNotNull(PlaylistRepository.create(ctx, "C"))
        assertTrue(File(tmp.root, "playlists.bak.json").readText().contains("\"A\""))
        restart()
        PlaylistRepository.ensureRestored(ctx)
        assertEquals(listOf("A", "C"), names())
    }
}
