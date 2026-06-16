package com.example.dink_smb_player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.dink_smb_player.data.art.AlbumArtCache
import com.example.dink_smb_player.data.model.AlbumArtShape
import com.example.dink_smb_player.data.model.ArtPalette
import com.example.dink_smb_player.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Album art that prefers the track's REAL embedded cover ([AlbumArtCache]) and falls back
 * to procedural [AlbumArt] while/if no cover is available. Drop-in for `AlbumArt` anywhere
 * a [Song] is in hand — pass the same [palette]/[shape] you'd give `AlbumArt` for the
 * fallback. Resolution is lazy + cached, so the first paint is procedural and the real
 * cover swaps in a moment later (then stays instant).
 */
@Composable
fun CoverArt(
    song: Song?,
    palette: ArtPalette,
    shape: AlbumArtShape,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
) {
    val cover = rememberCoverBitmap(song)
    if (cover != null) {
        Image(
            bitmap = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
        )
    } else {
        AlbumArt(palette = palette, shape = shape, modifier = modifier, cornerRadius = cornerRadius)
    }
}

/** Resolve a track's embedded cover, or null while unresolved / when it has none. Memory
 *  hits paint on the first frame; misses resolve off-main and recompose. */
@Composable
fun rememberCoverBitmap(song: Song?): ImageBitmap? {
    val context = LocalContext.current
    // Only remote/playable tracks can be probed; mock songs (no uri) stay procedural.
    val key = remember(song?.id, song?.albumTitle, song?.artist) { song?.let { AlbumArtCache.keyFor(it) } }
    val uri = song?.mediaUri
    var bitmap by remember(key) { mutableStateOf(key?.let { AlbumArtCache.peek(it)?.asImageBitmap() }) }
    LaunchedEffect(key, uri) {
        if (key != null && uri != null && bitmap == null) {
            val resolved = withContext(Dispatchers.IO) { AlbumArtCache.resolve(context, key, uri) }
            if (resolved != null) bitmap = resolved.asImageBitmap()
        }
    }
    return bitmap
}
