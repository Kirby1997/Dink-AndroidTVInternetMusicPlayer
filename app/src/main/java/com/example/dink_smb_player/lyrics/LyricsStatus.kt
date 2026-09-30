package com.example.dink_smb_player.lyrics

/**
 * What the lyrics pane should show for the current track. Distinguishes "the
 * resolver hasn't answered yet" from "it answered with nothing", so the pane
 * doesn't claim "No lyrics available" while the provider chain is still running.
 */
enum class LyricsStatus { Loading, Loaded, None }
