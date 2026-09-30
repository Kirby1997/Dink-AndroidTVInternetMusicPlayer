package com.example.dink_smb_player.data.index

/**
 * How [IndexDao.upsertTracks] combines a writer's [incoming] row with the row CURRENTLY in the
 * index ([current], null = not indexed). Runs inside the DAO's atomic update, so a writer working
 * from a snapshot taken minutes earlier (a 25k walk, a retag chunk) can't overwrite fields another
 * writer changed meanwhile — lost plays, lost enrichment, resurrected rows (LIB-4 / SRC-6).
 * Returns the row to store, or null to drop the write (row vanished and this writer may not
 * re-create it).
 */
fun interface TrackMerge {
    fun merge(current: TrackEntity?, incoming: TrackEntity): TrackEntity?
}

/**
 * Per-writer merge policies. Field ownership:
 *  - index-owned: addedAtMs, playCount, lastPlayedMs — only [IndexDao.markPlayed], the first
 *    insert and [Restore] write them; every policy here keeps the current row's values. Play stats live in
 *    [IndexDao.playStats] (markPlayed patches that map, never a row); in memory the rows carry
 *    them as 0/null and [IndexDao.persistSnapshot] writes the live values to disk (LIB-15).
 *  - listing (walk): uri, path, sizeBytes, fileMtimeMs.
 *  - tags: title, artist, albumArtist, albumTitle, year, trackNumber, durationMs (+ the fields no
 *    reader fills yet: albumId, discNumber, bitrate, mimeType). albumArtist feeds the album key,
 *    so a writer that changes it must have the keys recomputed after (walk and retag both do).
 *  - grouping keys: artistKey, albumKey, artistLabel — [keys] (recompute) and [enrich].
 *  - retag stamps: retagAttemptedMs, retagVersion — [retag], and a walk's own tag read.
 */
object TrackMerges {

    /** Restore (boot, and its retries after a transient load failure): the on-disk row fills an
     *  id the index doesn't have. A row already there was written by this process after it
     *  started — a local refresh or walk that ran while the restore was failing — so it is newer
     *  than the file's and is kept, except for the index-owned first-seen time, which only the
     *  file knows (the early writer stamped "now"). Play stats are merged separately
     *  ([IndexDao.restoreTracks]). Was a whole-row replace, which on the retry path reverted
     *  those fresher rows to the file's copy. */
    val Restore = TrackMerge { current, incoming ->
        current?.copy(addedAtMs = incoming.addedAtMs) ?: incoming
    }

    /** SMB/cloud walk (import, monitor, mid-walk flush). A new file is inserted as read. A file
     *  whose size/mtime changed was re-read by the walk, so its tags + stamps come from [incoming].
     *  An unchanged file only refreshes its listing fields — its tags, plays and enrichment stay as
     *  they are NOW, not as the walk's snapshot saw them — unless the walk re-read it (no duration,
     *  never conclusively read: [incoming] carries a newer retag stamp). Then the tags it read are
     *  patched in too; taking only the stamp would mark the row as read and it would never be
     *  retried (filename titles stuck). Empty values never replace stored ones, and the walk builds
     *  a re-read row over the stored one (SmbImporter.rereadRow), so a file with no tags doesn't
     *  bring its filename/folder fallbacks back. */
    val Walk = TrackMerge { current, incoming ->
        when {
            current == null -> incoming
            fileChanged(current, incoming.sizeBytes, incoming.fileMtimeMs) ->
                incoming.withIndexOwnedFrom(current).copy(
                    artistKey = current.artistKey,
                    albumKey = current.albumKey,
                    artistLabel = current.artistLabel,
                )
            else -> {
                val newerStamp = (incoming.retagAttemptedMs ?: Long.MIN_VALUE) > (current.retagAttemptedMs ?: Long.MIN_VALUE)
                val listed = current.copy(
                    uri = incoming.uri,
                    path = incoming.path,
                    sizeBytes = if (incoming.sizeBytes > 0) incoming.sizeBytes else current.sizeBytes,
                    fileMtimeMs = incoming.fileMtimeMs ?: current.fileMtimeMs,
                    durationMs = if (current.durationMs > 0) current.durationMs else incoming.durationMs,
                )
                if (!newerStamp) listed
                else listed.copy(
                    title = incoming.title.ifBlank { null } ?: current.title,
                    artist = incoming.artist?.ifBlank { null } ?: current.artist,
                    albumArtist = incoming.albumArtist?.ifBlank { null } ?: current.albumArtist,
                    albumTitle = incoming.albumTitle?.ifBlank { null } ?: current.albumTitle,
                    year = incoming.year ?: current.year,
                    trackNumber = incoming.trackNumber ?: current.trackNumber,
                    durationMs = incoming.durationMs.takeIf { it > 0 } ?: listed.durationMs,
                    retagAttemptedMs = incoming.retagAttemptedMs,
                    retagVersion = incoming.retagVersion,
                )
            }
        }
    }

    /** Local MediaStore refresh. MediaStore is the tag authority for local rows, so listing + tags
     *  come from [incoming]; the index keeps first-seen time, plays, grouping keys (recomputed
     *  after) and any retag stamp / mtime / size MediaStore didn't supply. */
    val Local = TrackMerge { current, incoming ->
        if (current == null) incoming
        else incoming.withIndexOwnedFrom(current).copy(
            artistKey = current.artistKey,
            albumKey = current.albumKey,
            artistLabel = current.artistLabel,
            retagAttemptedMs = incoming.retagAttemptedMs ?: current.retagAttemptedMs,
            retagVersion = if (incoming.retagAttemptedMs != null) incoming.retagVersion else current.retagVersion,
            fileMtimeMs = incoming.fileMtimeMs ?: current.fileMtimeMs,
            sizeBytes = if (incoming.sizeBytes > 0) incoming.sizeBytes else current.sizeBytes,
        )
    }

    // Retag, recompute and enrich work from a row they read earlier ([bases], by id). Each patches
    // only the fields it OWNS, and of those only the ones it actually changed relative to that
    // base — so a field another writer updated in the meantime (enrichment landing mid-retag,
    // keys recomputed by an enrich) is not reverted to the writer's stale copy. A row that is
    // gone by the time the write lands (pruned, source removed) is never re-created.

    /** Retag: tag fields it re-read + its retag stamp. */
    fun retag(bases: Map<String, TrackEntity>) = TrackMerge { current, incoming ->
        if (current == null) return@TrackMerge null
        val base = bases[incoming.id] ?: current
        current.patchTags(base, incoming).copy(
            retagAttemptedMs = pick(base.retagAttemptedMs, incoming.retagAttemptedMs, current.retagAttemptedMs),
            retagVersion = pick(base.retagVersion, incoming.retagVersion, current.retagVersion),
        )
    }

    /** Grouping-key recompute: only the three keys. */
    fun keys(bases: Map<String, TrackEntity>) = TrackMerge { current, incoming ->
        if (current == null) return@TrackMerge null
        current.patchKeys(bases[incoming.id] ?: current, incoming)
    }

    /** Playback enrichment: tag fields the player parsed + the keys recomputed for them. */
    fun enrich(base: TrackEntity) = TrackMerge { current, incoming ->
        if (current == null) return@TrackMerge null
        val b = if (base.id == incoming.id) base else current
        current.patchTags(b, incoming).patchKeys(b, incoming)
    }

    /** True when the listing says the file at this path is not the one [stored] was read from:
     *  its size or last-write time differs. Unknown values (0 size, null mtime — rows indexed
     *  before mtime was recorded) never count as a change, so upgrading doesn't re-read 25k files. */
    fun fileChanged(stored: TrackEntity, sizeBytes: Long, mtimeMs: Long?): Boolean =
        (stored.sizeBytes > 0 && sizeBytes > 0 && stored.sizeBytes != sizeBytes) ||
            (stored.fileMtimeMs != null && mtimeMs != null && stored.fileMtimeMs != mtimeMs)

    private fun TrackEntity.withIndexOwnedFrom(current: TrackEntity): TrackEntity = copy(
        addedAtMs = current.addedAtMs,
        playCount = current.playCount,
        lastPlayedMs = current.lastPlayedMs,
    )

    /** The writer's value if it changed it (vs the [base] it read), else the current one. */
    private fun <T> pick(base: T, incoming: T, current: T): T = if (incoming != base) incoming else current

    private fun TrackEntity.patchTags(base: TrackEntity, incoming: TrackEntity): TrackEntity = copy(
        title = pick(base.title, incoming.title, title),
        artist = pick(base.artist, incoming.artist, artist),
        albumArtist = pick(base.albumArtist, incoming.albumArtist, albumArtist),
        albumTitle = pick(base.albumTitle, incoming.albumTitle, albumTitle),
        year = pick(base.year, incoming.year, year),
        trackNumber = pick(base.trackNumber, incoming.trackNumber, trackNumber),
        durationMs = pick(base.durationMs, incoming.durationMs, durationMs),
    )

    private fun TrackEntity.patchKeys(base: TrackEntity, incoming: TrackEntity): TrackEntity = copy(
        artistKey = pick(base.artistKey, incoming.artistKey, artistKey),
        albumKey = pick(base.albumKey, incoming.albumKey, albumKey),
        artistLabel = pick(base.artistLabel, incoming.artistLabel, artistLabel),
    )
}
