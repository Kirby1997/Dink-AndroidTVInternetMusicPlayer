package com.example.dink_smb_player.data.library

import android.content.Context
import com.example.dink_smb_player.data.index.IndexDao
import com.example.dink_smb_player.data.index.SourceEntity
import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Review findings 2/3/4/5: the persist gate starts closed until the boot restore ran, early
 *  writers wait for it, a transient restore is retried without the empty state, the retry's
 *  merge keeps rows written meanwhile, and a failed save reaches importScoped's caller. */
class LibraryRestoreRaceTest {

    private lateinit var ctx: Context
    private lateinit var tracks: MutableStateFlow<List<TrackEntity>>
    private lateinit var dao: IndexDao
    private val saves: MutableList<List<TrackEntity>> = Collections.synchronizedList(ArrayList())

    private val smb = SourceEntity(id = "nas", type = SourceType.Smb, displayName = "NAS", createdAtMs = 0)
    private val local = SourceEntity(id = "local-mediastore", type = SourceType.Local, displayName = "Local", createdAtMs = 0)

    private fun smbRow(id: String, title: String = id, addedAt: Long = 1) = TrackEntity(
        id = id, title = title, artist = "A", albumTitle = "B", durationMs = 1000,
        sourceType = SourceType.Smb, sourceId = "nas", path = "/nas/$id.mp3", uri = "smb://h/$id.mp3",
        sizeBytes = 100, addedAtMs = addedAt, fileMtimeMs = 5,
    )

    private fun localRow(id: String) = TrackEntity(
        id = id, title = id, artist = "L", albumTitle = "L", durationMs = 1000,
        sourceType = SourceType.Local, sourceId = "local-mediastore", path = "/sdcard/$id.mp3",
        uri = "content://media/$id", sizeBytes = 0, addedAtMs = 2,
    )

    private val disk = LibraryStore.Snapshot(listOf(smbRow("a"), smbRow("b")), listOf(smb))

    private fun io(load: suspend () -> LibraryStore.LoadResult, save: Result<Unit> = Result.success(Unit)) =
        object : LibraryRepository.IndexIo {
            override suspend fun load(context: Context) = load()
            override suspend fun save(context: Context, snapshot: () -> Pair<List<TrackEntity>, List<SourceEntity>>): Result<Unit> {
                saves += snapshot().first
                return save
            }
        }

    @Before
    fun setUp() {
        ctx = mock()
        whenever(ctx.applicationContext).thenReturn(ctx)
        tracks = MutableStateFlow(emptyList())
        dao = IndexDao(tracks, MutableStateFlow(emptyList())) { false }
        saves.clear()
    }

    @After
    fun tearDown() {
        LibraryRepository.restoreRetryBaseMs = 2_000L
        LibraryRepository.resetForTest(io({ LibraryStore.LoadResult.Missing }), null)
    }

    private fun assertEverySaveHas(vararg ids: String) {
        assertTrue("something was saved", saves.isNotEmpty())
        synchronized(saves) {
            saves.forEach { snap ->
                assertTrue("snapshot ${snap.map { it.id }} lost restored rows", snap.map { it.id }.containsAll(ids.toList()))
            }
        }
    }

    @Test
    fun `early writers during a slow boot restore never save a snapshot missing the restored rows`() = runBlocking {
        val loadGate = CompletableDeferred<Unit>()
        LibraryRepository.resetForTest(io({ loadGate.await(); LibraryStore.LoadResult.Ok(disk) }), dao)

        val boot = async(Dispatchers.Default) { LibraryRepository.ensureRestored(ctx) }
        // LocalStorageScreen's MediaLibrary.refresh, an import's mid-walk flush and a monitor
        // pass all land while the 25k-row file is still loading.
        val writers = listOf(
            async(Dispatchers.Default) { LibraryRepository.importSource(ctx, local, listOf(localRow("x"))) },
            async(Dispatchers.Default) { LibraryRepository.upsertBatch(ctx, listOf(smbRow("c"))); Result.success(Unit) },
            async(Dispatchers.Default) {
                LibraryRepository.importScoped(ctx, smb, listOf(smbRow("d")), listOf("/nas/d"), prune = false).map { }
            },
        )
        delay(100)
        assertTrue("nothing written before the restore finished", saves.isEmpty())
        loadGate.complete(Unit)
        boot.await()
        writers.awaitAll().forEach { assertTrue(it.isSuccess) }

        assertEverySaveHas("a", "b")
        assertEquals(setOf("a", "b", "c", "d", "x"), tracks.value.map { it.id }.toSet())
    }

    @Test
    fun `restore drops rows of the removed cloud source and rewrites the index without them`() = runBlocking {
        val cloud = SourceEntity(id = "cloud-gdrive", type = SourceType.Cloud, displayName = "Drive", createdAtMs = 0)
        val cloudRow = smbRow("g").copy(sourceType = SourceType.Cloud, sourceId = cloud.id, uri = "gdrive://file?pid=x")
        val withCloud = LibraryStore.Snapshot(listOf(smbRow("a"), cloudRow, smbRow("b")), listOf(smb, cloud))
        LibraryRepository.resetForTest(io({ LibraryStore.LoadResult.Ok(withCloud) }), dao)

        LibraryRepository.ensureRestored(ctx)

        assertEquals(setOf("a", "b"), tracks.value.map { it.id }.toSet())
        // The cleaned index is written back, so the rows are gone from disk as well.
        assertTrue("restore saved the cleaned index", saves.isNotEmpty())
        assertEquals(setOf("a", "b"), saves.last().map { it.id }.toSet())
    }

    @Test
    fun `transient restore keeps loading, is retried, and the retry keeps rows written meanwhile`() = runBlocking {
        LibraryRepository.restoreRetryBaseMs = 60_000L // the scheduled retry stays out of the way
        val loads = AtomicInteger()
        LibraryRepository.resetForTest(
            io({
                if (loads.incrementAndGet() <= 2) LibraryStore.LoadResult.Transient(IOException("EIO"))
                else LibraryStore.LoadResult.Ok(disk)
            }),
            dao,
        )

        LibraryRepository.ensureRestored(ctx)
        assertFalse("UI keeps its loading state, not 'no sources'", LibraryRepository.restoredState.value)

        // A monitor pass (restore still failing) writes a fresher copy of "a" and a new "c".
        val fresh = smbRow("a", title = "Retitled on NAS", addedAt = 999)
        val r = LibraryRepository.refreshMonitored(ctx, smb, listOf(fresh, smbRow("c", addedAt = 999)), listOf("/nas/"), prune = false)

        assertTrue("its persist retried the restore and saved", r.isSuccess)
        assertEquals(3, loads.get())
        assertTrue(LibraryRepository.restoredState.value)
        val a = tracks.value.single { it.id == "a" }
        assertEquals("the restore didn't revert the fresher row", "Retitled on NAS", a.title)
        assertEquals("first-seen time comes from disk", 1L, a.addedAtMs)
        assertEquals(setOf("a", "b", "c"), tracks.value.map { it.id }.toSet())
        assertEverySaveHas("a", "b", "c")
    }

    @Test
    fun `a transient restore failure schedules its own retry`() = runBlocking {
        LibraryRepository.restoreRetryBaseMs = 10L
        val loads = AtomicInteger()
        LibraryRepository.resetForTest(
            io({
                if (loads.incrementAndGet() == 1) LibraryStore.LoadResult.Transient(IOException("EIO"))
                else LibraryStore.LoadResult.Ok(disk)
            }),
            dao,
        )
        LibraryRepository.ensureRestored(ctx)
        assertFalse(LibraryRepository.restoredState.value)
        withTimeout(5_000) { while (!LibraryRepository.restoredState.value) delay(10) }
        assertEquals(setOf("a", "b"), tracks.value.map { it.id }.toSet())
    }

    @Test
    fun `importScoped reports a failed save instead of a count`() = runBlocking {
        LibraryRepository.resetForTest(
            io({ LibraryStore.LoadResult.Ok(disk) }, save = Result.failure(IOException("ENOSPC"))),
            dao,
        )
        val r = LibraryRepository.importScoped(ctx, smb, listOf(smbRow("c")), listOf("/nas/"), prune = false)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is IOException)
    }
}
