package dev.jcode.ext.android.newproject

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.Space

/**
 * The preview on a gallery card.
 *
 * Android Studio ships a bitmap per template; this pack ships none, and drawing them keeps the
 * gallery honest in a way an image cannot: the preview is generated from the same enum the recipe
 * hangs off, so a template cannot end up advertising a screen it does not scaffold. It also costs
 * nothing in the archive, and follows the theme — a PNG of a light-themed phone in a dark drawer is
 * exactly the sort of thing that makes an extension look bolted on.
 *
 * Each is a phone (or watch, or television) outline with the furniture that template produces: an
 * app bar, a FAB, a bottom bar, a drawer edge.
 */
@Composable
internal fun TemplatePreview(art: Art, modifier: Modifier = Modifier) {
    val frame = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    Box(modifier = modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().height(112.dp).padding(Space.sm)) {
            when (art) {
                Art.Wear -> drawWatch(frame, fill, accent)
                Art.Tv -> drawTv(frame, fill, accent, muted)
                else -> drawPhone(art, frame, fill, accent, muted)
            }
        }
    }
}

private fun DrawScope.phoneRect(): Pair<Offset, Size> {
    val h = size.height
    val w = h * 0.52f
    return Offset((size.width - w) / 2f, 0f) to Size(w, h)
}

private fun DrawScope.drawPhone(art: Art, frame: Color, fill: Color, accent: Color, muted: Color) {
    val (at, box) = phoneRect()
    val r = androidx.compose.ui.geometry.CornerRadius(box.width * 0.12f)
    drawRoundRect(color = fill, topLeft = at, size = box, cornerRadius = r)
    drawRoundRect(color = frame, topLeft = at, size = box, cornerRadius = r, style = Stroke(width = 2f))

    val barH = box.height * 0.12f
    // The app bar, which every one of these has.
    if (art != Art.Empty) {
        drawRect(color = accent.copy(alpha = 0.75f), topLeft = at.copy(y = at.y + 2f), size = Size(box.width, barH))
    }
    when (art) {
        Art.ComposeActivity -> {
            // A centred greeting: what the SDK's empty Compose activity actually renders.
            val lineW = box.width * 0.46f
            drawRoundRect(
                color = muted,
                topLeft = Offset(at.x + (box.width - lineW) / 2f, at.y + box.height * 0.46f),
                size = Size(lineW, box.height * 0.035f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
            )
        }
        Art.ViewsActivity -> {
            // A left-aligned TextView, the XML layout's default.
            drawRoundRect(
                color = muted,
                topLeft = Offset(at.x + box.width * 0.12f, at.y + barH + box.height * 0.10f),
                size = Size(box.width * 0.5f, box.height * 0.035f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
            )
        }
        Art.Navigation -> {
            // The drawer, pulled part-way in from the left.
            drawRect(
                color = accent.copy(alpha = 0.28f),
                topLeft = at.copy(y = at.y + barH + 2f),
                size = Size(box.width * 0.42f, box.height - barH - 4f),
            )
            repeat(3) { i ->
                drawRoundRect(
                    color = muted,
                    topLeft = Offset(at.x + box.width * 0.07f, at.y + barH + box.height * (0.10f + i * 0.09f)),
                    size = Size(box.width * 0.24f, box.height * 0.03f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f),
                )
            }
        }
        Art.BottomNavigation -> {
            val navH = box.height * 0.11f
            drawRect(
                color = accent.copy(alpha = 0.55f),
                topLeft = at.copy(y = at.y + box.height - navH - 2f),
                size = Size(box.width, navH),
            )
            repeat(3) { i ->
                drawCircle(
                    color = fill,
                    radius = box.width * 0.035f,
                    center = Offset(
                        at.x + box.width * (0.22f + i * 0.28f),
                        at.y + box.height - navH / 2f - 2f,
                    ),
                )
            }
        }
        Art.Empty -> Unit
        else -> Unit
    }
}

private fun DrawScope.drawWatch(frame: Color, fill: Color, accent: Color) {
    val d = size.height * 0.72f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(color = fill, radius = d / 2f, center = c)
    drawCircle(color = frame, radius = d / 2f, center = c, style = Stroke(width = 2f))
    drawCircle(color = accent.copy(alpha = 0.7f), radius = d * 0.16f, center = c)
}

private fun DrawScope.drawTv(frame: Color, fill: Color, accent: Color, muted: Color) {
    val w = size.width * 0.78f
    val h = w * 0.58f
    val at = Offset((size.width - w) / 2f, (size.height - h) / 2f)
    val r = androidx.compose.ui.geometry.CornerRadius(6f)
    drawRoundRect(color = fill, topLeft = at, size = Size(w, h), cornerRadius = r)
    drawRoundRect(color = frame, topLeft = at, size = Size(w, h), cornerRadius = r, style = Stroke(width = 2f))
    // A row of cards, which is what a leanback screen is.
    repeat(3) { i ->
        drawRoundRect(
            color = if (i == 0) accent.copy(alpha = 0.7f) else muted,
            topLeft = Offset(at.x + w * (0.08f + i * 0.30f), at.y + h * 0.42f),
            size = Size(w * 0.24f, h * 0.34f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
        )
    }
}
