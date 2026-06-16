package com.example.dink_smb_player.data.index

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Singleton holder for the in-memory media index. Drop-in replacement for the
 * Room database that was originally planned — same access shape, no codegen.
 *
 * When KSP + Room support AGP 9 again, swap this for a `RoomDatabase` while
 * keeping [indexDao] returning an interface-shaped object.
 */
class MediaIndex private constructor() {

    private val tracksState = MutableStateFlow<List<TrackEntity>>(emptyList())
    private val sourcesState = MutableStateFlow<List<SourceEntity>>(emptyList())

    val dao: IndexDao = IndexDao(tracksState, sourcesState)

    fun indexDao(): IndexDao = dao

    companion object {
        @Volatile private var instance: MediaIndex? = null

        fun get(@Suppress("UNUSED_PARAMETER") context: Context): MediaIndex =
            instance ?: synchronized(this) {
                instance ?: MediaIndex().also { instance = it }
            }
    }
}
