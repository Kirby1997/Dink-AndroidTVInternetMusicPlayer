package com.example.dink_smb_player.data.model

import kotlinx.serialization.Serializable

enum class ConnectionStatus { Connected, Syncing, Offline, Expired, Idle }

enum class SmbProtocol { Auto, Smb3, Smb2 }

enum class AuthMethod { Password, Guest, Kerberos, OAuthDeviceFlow, OAuthPkce, AppPassword, ApiKey, BasicAuth, AccessKey }

enum class SyncSchedule { Auto, Hourly, Daily, Manual }

enum class ProviderGlyph { Triangle, Diamond, Hexagon, Octagon, Cloud, Cube, Wave, Bolt }

@Serializable
data class SmbShare(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val shareName: String,
    val mountPath: String,
    val user: String,
    val protocol: SmbProtocol,
    val status: ConnectionStatus,
    val trackCount: Int,
    val sizeBytes: Long,
    val lastSyncMs: Long?,
    val signal: Float,
    val syncSchedule: SyncSchedule = SyncSchedule.Auto,
    /** Folder smbPaths (backslash, relative to share root; "" = whole share) the
     *  user has imported into the library. Empty = nothing imported yet. */
    val importPaths: List<String> = emptyList(),
    /** Subset of [importPaths] the monitor worker re-scans on its schedule, so files
     *  added on the NAS *inside those folders* surface automatically. Per-folder, not
     *  whole-share — unless "" (root) is monitored, which covers everything. */
    val monitoredPaths: List<String> = emptyList(),
)

/** A cloud folder the user imported/monitors. [id] is the provider's native folder
 *  id (Drive: folder id, or "root" for My Drive). [path] is the human breadcrumb of
 *  folder names relative to root ("" = root) — it's what [TrackEntity.path] is built
 *  from, so monitor reconciliation can scope by prefix exactly like SMB. */
@Serializable
data class CloudFolderRef(val id: String, val path: String)

@Serializable
data class CloudProvider(
    val id: String,
    val name: String,
    val auth: AuthMethod,
    val status: ConnectionStatus,
    val account: String,
    val trackCount: Int,
    val cacheSize: String,
    val lastSyncMs: Long?,
    val glyph: ProviderGlyph,
    val syncSchedule: SyncSchedule = SyncSchedule.Auto,
    /** Folders the user imported into the library. Empty = nothing imported yet
     *  (connecting no longer auto-indexes the whole account — the user picks folders
     *  in the cloud browser, mirroring SMB). Serializable defaults keep old blobs valid. */
    val importFolders: List<CloudFolderRef> = emptyList(),
    /** Subset of [importFolders] the monitor worker re-scans on its schedule. */
    val monitoredFolders: List<CloudFolderRef> = emptyList(),
)

/** A mounted local volume — internal storage, external SD card, or attached USB OTG drive. */
@Serializable
data class LocalVolume(
    val id: String,
    val label: String,
    val path: String,
    val isRemovable: Boolean,
    val isPrimary: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
    val state: ConnectionStatus,
    val trackCount: Int = 0,
    val lastSyncMs: Long? = null,
)
