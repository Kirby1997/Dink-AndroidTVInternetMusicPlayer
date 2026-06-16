package com.example.dink_smb_player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.dink_smb_player.data.model.AlbumArtShape
import com.example.dink_smb_player.data.model.ArtPalette

/**
 * Procedural album art. Reproduces the 7 shape kinds from the design prototype's
 * SVG `ProviderGlyph` set, normalised to a 100×100 reference grid then scaled to the
 * actual canvas size. Use as the fallback when ID3 artwork isn't available — covers
 * 100% of the catalogue with a consistent house style.
 */
@Composable
fun AlbumArt(
    palette: ArtPalette,
    shape: AlbumArtShape,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
) {
    val (c1, c2, c3) = palette.colors()
    Canvas(
        modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
    ) {
        when (shape) {
            AlbumArtShape.Orbits -> drawOrbits(c1, c2, c3)
            AlbumArtShape.Horizon -> drawHorizon(c1, c2, c3)
            AlbumArtShape.Wave -> drawWave(c1, c2, c3)
            AlbumArtShape.Grid -> drawGrid(c1, c2, c3)
            AlbumArtShape.Rings -> drawRings(c1, c2, c3)
            AlbumArtShape.Diag -> drawDiag(c1, c2, c3)
            AlbumArtShape.Paper -> drawPaper(c1, c2, c3)
        }
    }
}

// All ref coordinates are in a 100×100 viewBox; scale via the local extension.
private fun DrawScope.refX(v: Float) = v / 100f * size.width
private fun DrawScope.refY(v: Float) = v / 100f * size.height
private fun DrawScope.refR(v: Float) = v / 100f * minOf(size.width, size.height)
private fun DrawScope.refOffset(x: Float, y: Float) = Offset(refX(x), refY(y))

private fun DrawScope.drawOrbits(c1: Color, c2: Color, c3: Color) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(c2, c1),
            center = refOffset(78f, 22f),
            radius = refR(120f),
        ),
    )
    val centre = refOffset(78f, 22f)
    listOf(36f, 24f, 14f).forEachIndexed { i, r ->
        drawCircle(
            color = c3.copy(alpha = 0.85f - i * 0.18f),
            center = centre,
            radius = refR(r),
            style = Stroke(width = refR(1.6f)),
        )
    }
    drawCircle(color = c3, center = centre, radius = refR(3f))
}

private fun DrawScope.drawHorizon(c1: Color, c2: Color, c3: Color) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(c2, c1, c3),
        ),
    )
    drawCircle(
        color = c3.copy(alpha = 0.9f),
        center = refOffset(50f, 62f),
        radius = refR(22f),
    )
    // Dark band below the horizon, fading down.
    drawRect(
        color = Color.Black.copy(alpha = 0.35f),
        topLeft = refOffset(0f, 62f),
        size = Size(size.width, refY(38f)),
    )
}

private fun DrawScope.drawWave(c1: Color, c2: Color, c3: Color) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(c3, c1),
        ),
    )
    for (i in 0 until 6) {
        val baseY = refY(30f + i * 10f)
        val path = Path().apply {
            moveTo(refX(-5f), baseY)
            // Quadratic that crests at midpoint, height varies with i.
            quadraticBezierTo(
                refX(50f), baseY - refY(8f - i * 0.6f),
                refX(105f), baseY,
            )
        }
        drawPath(
            path = path,
            color = c2.copy(alpha = 0.55f - i * 0.05f),
            style = Stroke(width = refR(1.8f - i * 0.1f)),
        )
    }
}

private fun DrawScope.drawGrid(c1: Color, c2: Color, c3: Color) {
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(c1, c3),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
    )
    val cell = refR(9f)
    val gap = refR(2f)
    for (row in 0 until 8) {
        for (col in 0 until 8) {
            val x = refX(10f) + col * (cell + gap)
            val y = refY(10f) + row * (cell + gap)
            val fill = (row * 8 + col) % 3 == 0
            if (fill) {
                drawRect(
                    color = c2.copy(alpha = 0.6f),
                    topLeft = Offset(x, y),
                    size = Size(cell, cell),
                )
            }
            drawRect(
                color = c2.copy(alpha = 0.25f),
                topLeft = Offset(x, y),
                size = Size(cell, cell),
                style = Stroke(width = refR(0.6f)),
            )
        }
    }
}

private fun DrawScope.drawRings(c1: Color, c2: Color, c3: Color) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(c2, c1),
            center = refOffset(50f, 50f),
            radius = refR(80f),
        ),
    )
    val centre = refOffset(50f, 50f)
    listOf(50f, 40f, 30f, 20f, 10f).forEachIndexed { i, r ->
        drawCircle(
            color = c3.copy(alpha = 0.85f - i * 0.13f),
            center = centre,
            radius = refR(r),
            style = Stroke(width = refR(1.2f)),
        )
    }
}

private fun DrawScope.drawDiag(c1: Color, c2: Color, c3: Color) {
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(c1, c3),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
    )
    val barW = refR(6f)
    val gap = refR(5f)
    rotate(degrees = -22f, pivot = refOffset(50f, 50f)) {
        for (i in 0 until 10) {
            val x = refX(-10f) + i * (barW + gap)
            drawRect(
                color = c2.copy(alpha = 0.18f + i * 0.07f),
                topLeft = Offset(x, refY(-10f)),
                size = Size(barW, size.height + refY(20f)),
            )
        }
    }
}

private fun DrawScope.drawPaper(c1: Color, c2: Color, c3: Color) {
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(c1, c2),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
    )
    val rect = Rect(
        offset = refOffset(14f, 18f),
        size = Size(refX(72f), refY(64f)),
    )
    drawRect(
        color = c3.copy(alpha = 0.22f),
        topLeft = rect.topLeft,
        size = rect.size,
    )
    // Stacked lines of varying widths to suggest paragraph text.
    val widths = listOf(64f, 58f, 60f, 50f, 62f, 44f, 56f, 38f, 52f, 30f)
    widths.forEachIndexed { i, w ->
        val y = refY(24f + i * 5.6f)
        drawRect(
            color = c3.copy(alpha = 0.55f),
            topLeft = refOffset(18f, y),
            size = Size(refX(w), refR(1.8f)),
        )
    }
}
