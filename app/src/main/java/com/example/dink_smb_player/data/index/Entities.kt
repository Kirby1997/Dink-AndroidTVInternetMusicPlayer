package com.example.dink_smb_player.data.index

// Plain data classes — Room is deferred until KSP catches up with AGP 9 (current
// stable Kotlin Gradle Plugin still casts to the removed `BaseExtension` API).
// When the toolchain stabilises, add @Entity/@PrimaryKey + Room dao codegen here
// without changing call sites in MediaIndex/IndexDao.
//
// @Serializable so the in-memory index can be snapshotted to disk (LibraryStore)
// and survive process death — the stand-in for Room persistence.

import kotlinx.serialization.Serializable

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
    val lastPlayedMs: Long? = null,
    val playCount: Int = 0,
    // Grouping keys precomputed at import/retag by LibraryGrouping.computeGroupingKeys, so the
    // Albums/Artists views collapse duplicates with a plain groupBy instead of paying NFD +
    // regex normalization on 25k rows at display time (the section-load lag). Nullable +
    // defaulted so pre-precompute snapshots deserialize; a one-time migration fills them on the
    // next restore. artistKey folds collaborations to their primary artist (library-wide stats),
    // albumKey folds cosmetic title variants, artistLabel is the clean feat-free display spelling.
    val artistKey: String? = null,
    val albumKey: String? = null,
    val artistLabel: String? = null,
    // Wall-clock of the last retag attempt on this row (any outcome). A normal retag skips rows
    // that already carry one, so the unfixable residue (already-correct titles that happen to
    // equal the filename, or genuinely untagged files) stops being re-checked on every press.
    // null = never attempted. A forced retag ignores it. Defaulted so old snapshots deserialize.
    val retagAttemptedMs: Long? = null,
)

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
