package com.example.dink_smb_player.data.source.smb

import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.model.ConnectionStatus
import com.example.dink_smb_player.data.model.SmbProtocol
import com.example.dink_smb_player.data.model.SmbShare
import com.example.dink_smb_player.data.source.smb.SmbImporter.EnumResult
import com.example.dink_smb_player.data.source.smb.SmbImporter.RootIssue
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Review #3 / #6: walk completeness is per root, a root that's gone (its parent says so) is a
 * settled issue rather than a forever-retry, and folders the walk won't descend into keep
 * their indexed rows instead of having them pruned.
 */
class SmbWalkRootsTest {

    private fun api(status: NtStatus) =
        SMBApiException(status.value, SMB2MessageCommandCode.SMB2_QUERY_DIRECTORY, "Query directory failed", null)

    /** Fake share: folder → child names, or the throwable listing it raises. */
    private fun issue(root: String, script: Map<String, Any>): RootIssue? = SmbImporter.rootIssue(
        root,
        list = { p ->
            when (val r = script[p]) {
                is Throwable -> throw r
                is List<*> -> r.map { it as String }
                else -> error("unscripted '$p'")
            }
        },
        nameOf = { it },
        isPathLevel = SmbImporter::isPathLevelError,
    )

    @Test
    fun `root missing from its parent is NotFound`() {
        assertEquals(RootIssue.NotFound, issue("Music", mapOf("" to listOf("Podcasts", "Video"))))
        assertEquals(RootIssue.NotFound, issue("Music\\Rock", mapOf("Music" to listOf("Jazz"))))
    }

    @Test
    fun `renamed ancestor is found by walking up`() {
        val script = mapOf(
            "Music" to api(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND),
            "" to listOf("Musik"),
        )
        assertEquals(RootIssue.NotFound, issue("Music\\Rock", script))
    }

    @Test
    fun `root present in its parent is Unreadable, case-insensitively`() {
        assertEquals(RootIssue.Unreadable, issue("Music", mapOf("" to listOf("MUSIC"))))
    }

    @Test
    fun `parent unreachable or share root leaves it unknown (retry)`() {
        assertNull(issue("Music", mapOf("" to SocketTimeoutException("Read timed out"))))
        assertNull(issue("", emptyMap()))
        assertNull(issue("\\", emptyMap()))
    }

    @Test
    fun `listFolder reports a path-level failure on a root only`() {
        val notFound = api(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND)
        var fired = 0
        val rootResult = SmbImporter.listFolder<String>(
            "Music", isRoot = true, AtomicBoolean(false),
            list = { throw notFound }, evict = {}, onRootPathError = { fired++ },
        )
        assertNull(rootResult)
        assertEquals(1, fired)
        // A subfolder with the same status is a skip (empty), not a root issue.
        val sub = SmbImporter.listFolder<String>(
            "Music\\Rock", isRoot = false, AtomicBoolean(false),
            list = { throw notFound }, evict = {}, onRootPathError = { fired++ },
        )
        assertEquals(emptyList<String>(), sub)
        // A connection failure on a root is not a root issue.
        SmbImporter.listFolder<String>(
            "Music", isRoot = true, AtomicBoolean(false),
            list = { throw SocketTimeoutException("Read timed out") }, evict = {}, onRootPathError = { fired++ },
        )
        assertEquals(1, fired)
    }

    @Test
    fun `completeness is per root`() {
        val res = EnumResult(
            tracks = emptyList(),
            incompleteRoots = setOf("Music"),
            rootIssues = mapOf("Old" to RootIssue.NotFound),
        )
        assertFalse(res.complete)
        assertFalse(res.rootComplete("Music"))
        assertFalse(res.rootComplete("Music\\Rock")) // nested monitored path under a bad root
        assertFalse(res.rootComplete("Old"))
        assertTrue(res.rootComplete("Podcasts"))
        assertTrue(res.rootComplete("Musical"))
        // A bad whole-share root covers everything.
        assertFalse(EnumResult(emptyList(), incompleteRoots = setOf("")).rootComplete("Podcasts"))
        assertTrue(EnumResult(emptyList()).complete)
    }

    @Test
    fun `root issue message`() {
        assertNull(SmbImporter.describeRootIssues(emptyMap()))
        assertEquals(
            "Folder \"Music/Rock\" not found on the share",
            SmbImporter.describeRootIssues(mapOf("Music\\Rock" to RootIssue.NotFound)),
        )
        assertEquals(
            "Folder \"A\" can't be read (access denied?) (+1 more)",
            SmbImporter.describeRootIssues(mapOf("B" to RootIssue.NotFound, "A" to RootIssue.Unreadable)),
        )
    }

    private val share = SmbShare(
        id = "smb-1", name = "NAS", host = "nas", port = 445, shareName = "music",
        mountPath = "/smb/nas/music", user = "u", protocol = SmbProtocol.Auto,
        status = ConnectionStatus.Idle, trackCount = 0, sizeBytes = 0, lastSyncMs = null, signal = 0f,
    )

    private fun row(id: String, path: String) = TrackEntity(
        id = id, title = id, durationMs = 1000L, sourceType = SourceType.Smb, sourceId = share.id,
        path = path, uri = "smb://nas/music/$id", sizeBytes = 1L, addedAtMs = 0L,
    )

    @Test
    fun `unwalked folder keeps its indexed rows`() {
        val existing = listOf(
            row("a", "/smb/nas/music/OneDrive/x.mp3"),
            row("b", "/smb/nas/music/OneDrive/Sub/y.flac"),
            row("c", "/smb/nas/music/OneDriveOther/z.mp3"),
            row("d", "/smb/nas/music/Rock/w.mp3"),
        ).associateBy { it.id }
        val out = HashMap<String, TrackEntity>()
        SmbImporter.keepIndexedUnder(share, "OneDrive", existing, out)
        assertEquals(setOf("a", "b"), out.keys)
        assertEquals(existing["a"], out["a"])
    }
}
