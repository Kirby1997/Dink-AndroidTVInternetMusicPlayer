package com.example.dink_smb_player.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.ui.graphics.vector.ImageVector

enum class RailGroup { Top, Library, Sources, Bottom }

sealed class ScreenId(
    val key: String,
    val displayName: String,
    val icon: ImageVector,
    val group: RailGroup,
    val implemented: Boolean,
) {
    object Home        : ScreenId("home",        "Home",          Icons.Outlined.Home,             RailGroup.Top,     implemented = true)
    object Search      : ScreenId("search",      "Search",        Icons.Outlined.Search,           RailGroup.Top,     implemented = true)
    object NowPlaying  : ScreenId("nowplaying",  "Now Playing",   Icons.Outlined.PlayCircleOutline, RailGroup.Top,    implemented = false)

    object Songs       : ScreenId("songs",       "Songs",         Icons.Outlined.MusicNote,        RailGroup.Library, implemented = true)
    object Albums      : ScreenId("albums",      "Albums",        Icons.Outlined.Album,            RailGroup.Library, implemented = true)
    object Artists     : ScreenId("artists",     "Artists",       Icons.Outlined.Person,           RailGroup.Library, implemented = true)
    object Playlists   : ScreenId("playlists",   "Playlists",     Icons.Outlined.QueueMusic,       RailGroup.Library, implemented = true)
    object Folders     : ScreenId("folders",     "Folders",       Icons.Outlined.Folder,           RailGroup.Library, implemented = true)

    object LocalStorage: ScreenId("local",       "Local Storage", Icons.Outlined.SdStorage,        RailGroup.Sources, implemented = true)
    object SmbShares   : ScreenId("smb",         "SMB Shares",    Icons.Outlined.Storage,          RailGroup.Sources, implemented = true)
    object SmbBrowse   : ScreenId("smbbrowse",   "Browse Share",  Icons.Outlined.Folder,           RailGroup.Bottom,  implemented = true)
    object Cloud       : ScreenId("cloud",       "Cloud Storage", Icons.Outlined.Cloud,            RailGroup.Sources, implemented = true)
    object CloudBrowse : ScreenId("cloudbrowse", "Browse Cloud",  Icons.Outlined.Folder,           RailGroup.Bottom,  implemented = true)

    object Settings    : ScreenId("settings",    "Settings",      Icons.Outlined.Settings,         RailGroup.Bottom,  implemented = true)
    object AddShareWizard : ScreenId("addshare", "Add SMB Share", Icons.Outlined.Storage,          RailGroup.Bottom,  implemented = true)

    object Library : ScreenId("librarygroup", "Library", Icons.Outlined.LibraryMusic, RailGroup.Library, implemented = false)

    /** Transient detail screen for one album / artist / folder (its track list). Not a
     *  rail entry — reached by tapping a row on Albums/Artists/Folders; the active facet
     *  is held in `LibraryDetailNav`. */
    object LibraryDetail : ScreenId("librarydetail", "Detail", Icons.Outlined.Album, RailGroup.Bottom, implemented = true)

    companion object {
        // `by lazy` defers list construction until first read, so the nested `object`
        // declarations are fully initialised before they are referenced. An eagerly
        // initialised companion property would observe nulls because companion init
        // runs while the nested singletons are still being class-loaded.
        val railEntries: List<ScreenId> by lazy {
            listOf(
                Home, Search, NowPlaying,
                Songs, Albums, Artists, Playlists, Folders,
                // Cloud (Google Drive) parked at the device-flow OAuth scope wall — the
                // code lives on the `parked/cloud` branch. Omitted from the rail so the
                // dead "not configured" screen is unreachable in release. Re-add `Cloud`
                // here to restore the entry.
                LocalStorage, SmbShares,
                Settings,
            )
        }
    }
}
