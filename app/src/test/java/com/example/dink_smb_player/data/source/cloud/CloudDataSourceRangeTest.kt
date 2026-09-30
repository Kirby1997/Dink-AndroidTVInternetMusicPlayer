package com.example.dink_smb_player.data.source.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAY-16: a mid-file ranged open must get 206, or the extractor would read the file from byte 0. */
class CloudDataSourceRangeTest {

    @Test
    fun `a range from a non-zero position needs 206`() {
        assertTrue(CloudDataSource.rangeHonoured(0, 200))
        assertTrue(CloudDataSource.rangeHonoured(0, 206))
        assertTrue(CloudDataSource.rangeHonoured(1_000, 206))
        assertFalse(CloudDataSource.rangeHonoured(1_000, 200))
    }
}
