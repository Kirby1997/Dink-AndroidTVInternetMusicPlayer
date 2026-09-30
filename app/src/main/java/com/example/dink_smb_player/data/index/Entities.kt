package com.example.dink_smb_player.data.index

// Plain data classes — Room is deferred until KSP catches up with AGP 9 (current
// stable Kotlin Gradle Plugin still casts to the removed `BaseExtension` API).
// When the toolchain stabilises, add @Entity/@PrimaryKey + Room dao codegen here
// without changing call sites in MediaIndex/IndexDao.
//
// @Serializable so the in-memory index can be snapshotted to disk (LibraryStore)
// and survive process death — the stand-in for Room persistence.

import kotlinx.serialization.Serializable

/**
 * [Cloud] is legacy: the cloud (Google Drive) source was removed, but the constant stays so
 * an index written by an older version still decodes instead of being treated as corrupt.
 * Its rows are dropped at restore — see [withoutLegacyCloud].
 */
@Serializable
enum class SourceType { Smb, Cloud, Local }

@Serializable
data class TrackEntity(
    val id: String,                             // sha1(sourceType + sourceId + path)
    val title: String,
    val artist: String? = null,
    val albumArtist: String? = null,
    val albumId: String? = null,
    val albumTitle: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long,
    val bitrate: String? = null,                // "MP3 320", "FLAC 24/96"
    val mimeType: String? = null,
    val sourceType: SourceType,
    val sourceId: String,
    val path: String,
    val uri: String,
    val sizeBytes: Long,
    val addedAtMs: Long,
    // Play stats. Persisted per row (the on-disk JSON), but live values are in IndexDao.playStats:
    // rows are loaded stripped of them and IndexDao.persistSnapshot fills them back in (LIB-15).
    val lastPlayedMs: Long? = null,
    val playCount: Int = 0,
    // Grouping keys precomputed at import/retag by LibraryGrouping.computeGroupingKeys, so the
    // Albums/Artists views collapse duplicates with a plain groupBy instead of paying NFD +
    // regex normalization on 25k rows at display time (the section-load lag). Nullable +
    // defaulted so pre-precompute snapshots deserialize; a one-time migration fills them on the
    // next restore. artistKey folds collaborations to their primary artist (library-wide stats),
    // albumKey is "<albumArtistKey>|<normalized title>" (LIB-6: album artist + title, compilations
    // under "variousartists"), artistLabel is the clean feat-free display spelling.
    val artistKey: String? = null,
    val albumKey: String? = null,
    val artistLabel: String? = null,
    // Wall-clock of the last retag attempt on this row (any outcome). A normal retag skips rows
    // that already carry one, so the unfixable residue (already-correct titles that happen to
    // equal the filename, or genuinely untagged files) stops being re-checked on every press.
    // null = never attempted. A forced retag ignores it. Defaulted so old snapshots deserialize.
    val retagAttemptedMs: Long? = null,
    // Which retag logic wrote retagAttemptedMs. Stamps from before the reader distinguished
    // "no tags" from a transient read error (version 0) may mark files that merely failed to
    // read, so a normal retag treats them as unstamped once. Bump RETAG_VERSION in
    // LibraryRepository to re-read the residue after a reader improvement.
    val retagVersion: Int = 0,
    // Source file's last-modified time (epoch ms) as last seen by the walk; null = unknown.
    // Paired with retagAttemptedMs so a file changed in place can be re-tagged (SRC-8).
    val fileMtimeMs: Long? = null,
)

/** Live play stats of one track (see IndexDao.playStats). */
data class PlayStat(val count: Int, val lastPlayedMs: Long?)

@Serializable
data class SourceEntity(
    val id: String,
    val type: SourceType,
    val displayName: String,
    val createdAtMs: Long,
    val lastSyncMs: Long? = null,
    val trackCount: Int = 0,
    val sizeBytes: Long = 0,
    val statusJson: String? = null,
)

/** Tracks of the removed cloud source can no longer be played or refreshed; drop them. */
@JvmName("tracksWithoutLegacyCloud")
internal fun List<TrackEntity>.withoutLegacyCloud(): List<TrackEntity> =
    if (none { it.sourceType == SourceType.Cloud }) this else filter { it.sourceType != SourceType.Cloud }

@JvmName("sourcesWithoutLegacyCloud")
internal fun List<SourceEntity>.withoutLegacyCloud(): List<SourceEntity> =
    if (none { it.type == SourceType.Cloud }) this else filter { it.type != SourceType.Cloud }
