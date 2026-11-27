package com.jpcottin.tairoto.ui.share

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jpcottin.tairoto.ui.tank.ShareUi
import com.jpcottin.tairoto.ui.theme.GlassBorder
import com.jpcottin.tairoto.ui.theme.Ocean

object ArrangeTags {
    const val OVERLAY = "arrange_overlay"
    const val NAME = "arrange_name"
    const val DONE = "arrange_done"
}

/**
 * Over every screen of a shared lagoon while its screens are being arranged:
 * which device this is, its edges glowing where a finger can cross, and a
 * finger's path drawn from the middle off the side, the way to lay the next
 * screen beside it.
 */
@Composable
fun ArrangeOverlay(ui: ShareUi, onDone: () -> Unit, modifier: Modifier = Modifier, animate: Boolean = true) {
    val me = ui.devices.firstOrNull { it.self }
    val loop = rememberInfiniteTransition(label = "arrange")
    val glow by loop.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "glow")
    val path by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "path")
    val g = if (animate) glow else 0.8f
    val p = if (animate) path else 0.6f
    Box(modifier.fillMaxSize().testTag(ArrangeTags.OVERLAY)) {
        Canvas(Modifier.fillMaxSize()) {
            // The edges a finger crosses: a glow running all round the glass.
            val band = 22.dp.toPx()
            val c = Ocean.SeaGlass
            val fade = listOf(c.copy(alpha = 0.55f * g), Color.Transparent)
            drawRect(Brush.verticalGradient(fade, 0f, band), Offset.Zero, Size(size.width, band))
            drawRect(Brush.verticalGradient(fade.reversed(), size.height - band, size.height), Offset(0f, size.height - band), Size(size.width, band))
            drawRect(Brush.horizontalGradient(fade, 0f, band), Offset.Zero, Size(band, size.height))
            drawRect(Brush.horizontalGradient(fade.reversed(), size.width - band, size.width), Offset(size.width - band, 0f), Size(band, size.height))
            // A fingertip gliding from the middle off the right edge, leaving a fading trail.
            val y = size.height * 0.68f
            val x0 = size.width * 0.5f
            val x = x0 + (size.width - x0 + 12.dp.toPx()) * p
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, Ocean.Foam.copy(alpha = 0.6f)), x0, x),
                Offset(x0, y), Offset(x, y), strokeWidth = 5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            drawCircle(Ocean.Foam.copy(alpha = 0.9f * (1f - p * 0.6f)), 11.dp.toPx(), Offset(x, y))
        }
        Column(
            Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Not a Surface: one takes every touch, and a finger swiped across the screens starts anywhere.
            val shape = MaterialTheme.shapes.large
            Box(Modifier.background(Ocean.GlassStrong, shape).border(GlassBorder, shape)) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        me?.name ?: "This screen",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Ocean.Foam,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(ArrangeTags.NAME),
                    )
                    Text(
                        (if (me?.host == true) "the host  ·  " else "") + "${ui.devices.size} screens",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ocean.Mist,
                    )
                    Box(Modifier.height(12.dp))
                    Text(
                        "Swipe a finger from this screen onto the one beside it: they line up where they lie.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ocean.Foam,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 28.dp)) {
            FilledTonalButton(onClick = onDone, modifier = Modifier.align(Alignment.Center).testTag(ArrangeTags.DONE)) {
                Text("Done")
            }
        }
    }
}

@com.jpcottin.tairoto.ui.preview.FormFactorPreviews
@Composable
private fun ArrangeOverlayPreview() {
    com.jpcottin.tairoto.ui.theme.TairotoTheme { com.jpcottin.tairoto.ui.preview.LagoonArrangingPreviewContent() }
}
