package com.example.dink_smb_player.data.source.cloud

import androidx.compose.ui.graphics.Color
import com.example.dink_smb_player.data.model.AuthMethod
import com.example.dink_smb_player.data.model.ProviderGlyph

/**
 * Static catalog of cloud providers the Connect grid can offer. Only Google Drive
 * is wired in v1 ([available] = true); the rest render as "Coming soon" tiles so the
 * grid shows the roadmap without dead buttons.
 *
 * Brand colours drive the glyph badge tint (the app uses procedural glyphs rather
 * than bundling trademarked vendor bitmaps — consistent with the rest of the UI).
 */
data class CloudProviderSpec(
    val id: String,
    val name: String,
    val glyph: ProviderGlyph,
    val brandColor: Color,
    val auth: AuthMethod,
    val available: Boolean,
    /** Short status line shown on the tile when [available] is false. */
    val note: String = "Coming soon",
) {
    companion object {
        const val GOOGLE_DRIVE_ID = "googledrive"

        val all: List<CloudProviderSpec> = listOf(
            // Google Drive is PARKED: Google's limited-input (TV) device flow only
            // permits drive.file / drive.appdata, never drive.readonly — so we can't
            // browse/stream an existing library on a TV. Client + flow code is kept
            // (works if revisited via a companion sign-in handoff). See PHASES.md.
            CloudProviderSpec(
                id = GOOGLE_DRIVE_ID,
                name = "Google Drive",
                glyph = ProviderGlyph.Triangle,
                brandColor = Color(0xFF1FA463),
                auth = AuthMethod.OAuthDeviceFlow,
                available = false,
                note = "Needs phone sign-in",
            ),
            CloudProviderSpec(
                id = "dropbox",
                name = "Dropbox",
                glyph = ProviderGlyph.Diamond,
                brandColor = Color(0xFF0061FF),
                auth = AuthMethod.OAuthDeviceFlow,
                available = false,
            ),
            CloudProviderSpec(
                id = "onedrive",
                name = "OneDrive",
                glyph = ProviderGlyph.Cloud,
                brandColor = Color(0xFF0078D4),
                auth = AuthMethod.OAuthDeviceFlow,
                available = false,
            ),
            CloudProviderSpec(
                id = "box",
                name = "Box",
                glyph = ProviderGlyph.Cube,
                brandColor = Color(0xFF0061D5),
                auth = AuthMethod.OAuthDeviceFlow,
                available = false,
            ),
        )

        fun byId(id: String): CloudProviderSpec? = all.firstOrNull { it.id == id }
    }
}
