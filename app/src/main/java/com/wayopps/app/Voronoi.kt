package com.wayopps.app

import androidx.compose.foundation.Canvas
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.random.Random

/** Colours shared with the SLA web app and its Voronoi icon art. */
object Sla {
    val bg = Color(0xFF14181C)
    val panel = Color(0xFF1B1F24)
    val panel2 = Color(0xFF23282E)
    val line = Color(0xFF333B44)
    val text = Color(0xFFE8ECEF)
    val textDim = Color(0xFF9AA5B1)
    val textFaint = Color(0xFF6B7681)
    val accent = Color(0xFF3DA9FC)
    val go = Color(0xFF34C759)
    val stop = Color(0xFFFF453A)
    val warn = Color(0xFFFFB020)

    /** Slate tones sampled from the Voronoi cover art, darkest first. */
    val mesh = listOf(Color(0xFF192129), Color(0xFF29333F), Color(0xFF424E5E), Color(0xFF627084), Color(0xFF8B9BB2))
}

fun slaDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Sla.accent,
    onPrimary = Color(0xFF04121F),
    primaryContainer = Color(0xFF1C3A52),
    onPrimaryContainer = Color(0xFFCDE6FF),
    secondary = Sla.textDim,
    secondaryContainer = Sla.panel2,
    onSecondaryContainer = Sla.text,
    tertiary = Sla.warn,
    background = Sla.bg,
    onBackground = Sla.text,
    surface = Sla.bg,
    onSurface = Sla.text,
    surfaceVariant = Sla.panel2,
    onSurfaceVariant = Sla.textDim,
    surfaceContainerLowest = Color(0xFF0F1215),
    surfaceContainerLow = Color(0xFF171B1F),
    surfaceContainer = Sla.panel,
    surfaceContainerHigh = Sla.panel2,
    surfaceContainerHighest = Color(0xFF2B3138),
    outline = Sla.textFaint,
    outlineVariant = Sla.line,
    error = Sla.stop
)

fun slaLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF1F7FC8),
    secondaryContainer = Color(0xFFDCE3EB),
    surfaceContainer = Color(0xFFEFF2F5),
    surfaceContainerHigh = Color(0xFFE6EAEE),
    error = Color(0xFFD32F2F)
)

/** Voronoi cells as polygons in a w x h box, by clipping the box with perpendicular bisectors. */
private fun voronoiCells(w: Float, h: Float, sites: List<Pair<Float, Float>>): List<List<Pair<Float, Float>>> {
    fun clip(poly: List<Pair<Float, Float>>, px: Float, py: Float, nx: Float, ny: Float): List<Pair<Float, Float>> {
        // Keep the side where (p - P) . n <= 0
        val out = mutableListOf<Pair<Float, Float>>()
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val da = (a.first - px) * nx + (a.second - py) * ny
            val db = (b.first - px) * nx + (b.second - py) * ny
            if (da <= 0) out.add(a)
            if ((da < 0 && db > 0) || (da > 0 && db < 0)) {
                val t = da / (da - db)
                out.add(Pair(a.first + t * (b.first - a.first), a.second + t * (b.second - a.second)))
            }
        }
        return out
    }

    return sites.mapIndexed { i, (sx, sy) ->
        var cell = listOf(0f to 0f, w to 0f, w to h, 0f to h)
        for (j in sites.indices) {
            if (i == j || cell.size < 3) continue
            val (ox, oy) = sites[j]
            cell = clip(cell, (sx + ox) / 2, (sy + oy) / 2, ox - sx, oy - sy)
        }
        cell
    }
}

/**
 * Faceted slate Voronoi mesh in the style of the SLA / ingeniir cover art.
 * [cellSizeDp] is the rough cell diameter; the pattern is stable for a given [seed] and size.
 */
@Composable
fun VoronoiBackground(
    modifier: Modifier = Modifier,
    seed: Int = 7,
    cellSizePx: Float = 140f,
    palette: List<Color> = Sla.mesh,
    scrim: Color = Color.Transparent
) {
    val cache = remember(seed, cellSizePx, palette) { mutableMapOf<Size, List<Pair<Path, Color>>>() }
    Canvas(modifier = modifier) {
        val cells = cache.getOrPut(size) {
            val rnd = Random(seed)
            val n = ((size.width * size.height) / (cellSizePx * cellSizePx)).toInt().coerceIn(6, 120)
            val pad = cellSizePx
            val sites = List(n) {
                Pair(rnd.nextFloat() * (size.width + 2 * pad) - pad, rnd.nextFloat() * (size.height + 2 * pad) - pad)
            }
            voronoiCells(size.width, size.height, sites).filter { it.size >= 3 }.map { poly ->
                val path = Path().apply {
                    moveTo(poly[0].first, poly[0].second)
                    for (k in 1 until poly.size) lineTo(poly[k].first, poly[k].second)
                    close()
                }
                path to palette[rnd.nextInt(palette.size)]
            }
        }
        for ((path, color) in cells) {
            drawPath(path, color)
            // Same-colour outline closes the hairline seams between neighbouring cells.
            drawPath(path, color, style = Stroke(width = 1.2f))
        }
        if (scrim != Color.Transparent) drawRect(scrim)
    }
}
