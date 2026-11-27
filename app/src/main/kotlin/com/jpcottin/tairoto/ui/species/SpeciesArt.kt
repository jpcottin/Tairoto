package com.jpcottin.tairoto.ui.species

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jpcottin.tairoto.world.SpeciesCatalog
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small top-down portrait of a species, in the same palette as its shader,
 * for the creature picker. Drawn, so it stays sharp at any size.
 */
@Composable
fun SpeciesArt(speciesId: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawSandDisc()
        when (speciesId) {
            SpeciesCatalog.GreenSeaTurtle.id -> drawTurtle()
            SpeciesCatalog.SpottedEagleRay.id -> drawEagleRay()
            SpeciesCatalog.DayOctopus.id -> drawOctopus()
            SpeciesCatalog.HermitCrab.id -> drawHermitCrab()
            SpeciesCatalog.ConvictSurgeonfish.id -> drawSchool(FishArt.Manini)
            SpeciesCatalog.BluestripeSnapper.id -> drawSchool(FishArt.Taape)
            SpeciesCatalog.YellowstripeGoatfish.id -> drawSchool(FishArt.Ouma)
            SpeciesCatalog.ScissortailSergeant.id -> drawSchool(FishArt.Mamo)
            SpeciesCatalog.RaccoonButterflyfish.id -> drawPair()
            SpeciesCatalog.BlacktipReefShark.id -> drawShark()
            else -> drawChromisTrio()
        }
    }
}

internal fun DrawScope.drawSandDisc() {
    val r = size.minDimension / 2f
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFF2F7F7C), Color(0xFF15535A), Color(0xFF0A3138)),
            center = Offset(size.width * 0.38f, size.height * 0.32f),
            radius = r * 1.5f,
        ),
        radius = r,
    )
    // A few strands of caustic light.
    val light = Color(0x33E6FBF6)
    for (i in 0 until 5) {
        val p = Path()
        val y0 = size.height * (0.15f + i * 0.18f)
        p.moveTo(size.width * 0.05f, y0)
        p.cubicTo(size.width * 0.3f, y0 - r * 0.18f, size.width * 0.55f, y0 + r * 0.2f, size.width * 0.95f, y0 - r * 0.05f)
        drawPath(p, light, style = Stroke(width = r * 0.025f))
    }
}

private fun DrawScope.drawChromis(center: Offset, length: Float, angleDeg: Float) {
    rotate(angleDeg, center) {
        translate(center.x, center.y) {
            val w = length * 0.2f
            // Forked tail.
            val tail = Path().apply {
                moveTo(-length * 0.36f, 0f)
                lineTo(-length * 0.62f, -w * 0.95f)
                lineTo(-length * 0.5f, 0f)
                lineTo(-length * 0.62f, w * 0.95f)
                close()
            }
            drawPath(tail, Color(0x9978D8C6))
            val body = Path().apply {
                moveTo(length * 0.42f, 0f)
                cubicTo(length * 0.4f, -w * 0.9f, length * 0.05f, -w * 1.05f, -length * 0.38f, -w * 0.22f)
                lineTo(-length * 0.38f, w * 0.22f)
                cubicTo(length * 0.05f, w * 1.05f, length * 0.4f, w * 0.9f, length * 0.42f, 0f)
                close()
            }
            drawPath(
                body,
                Brush.verticalGradient(
                    listOf(Color(0xFF9BF0DD), Color(0xFF3E8F86), Color(0xFF9BF0DD)),
                    startY = -w, endY = w,
                ),
            )
            drawLine(Color(0x664FA89C), Offset(length * 0.2f, 0f), Offset(-length * 0.32f, 0f), strokeWidth = w * 0.18f)
            drawCircle(Color(0xFF0B0F0F), radius = w * 0.17f, center = Offset(length * 0.3f, -w * 0.5f))
            drawCircle(Color(0xFF0B0F0F), radius = w * 0.17f, center = Offset(length * 0.3f, w * 0.5f))
        }
    }
}

private fun DrawScope.drawChromisTrio() {
    val s = size.minDimension
    drawChromis(Offset(s * 0.42f, s * 0.36f), s * 0.42f, -18f)
    drawChromis(Offset(s * 0.62f, s * 0.58f), s * 0.38f, -12f)
    drawChromis(Offset(s * 0.36f, s * 0.7f), s * 0.34f, -24f)
}

private fun DrawScope.drawTurtle() {
    val s = size.minDimension
    val c = Offset(s * 0.5f, s * 0.52f)
    rotate(-30f, c) {
        translate(c.x, c.y) {
            val skin = Color(0xFFB7C48A)
            val skinDark = Color(0xFF6E7448)
            fun flipper(root: Offset, angle: Float, len: Float, width: Float) {
                rotate(angle, root) {
                    val p = Path().apply {
                        moveTo(root.x, root.y - width * 0.4f)
                        cubicTo(root.x + len * 0.4f, root.y - width, root.x + len * 0.85f, root.y - width * 0.3f, root.x + len, root.y)
                        cubicTo(root.x + len * 0.7f, root.y + width * 0.25f, root.x + len * 0.3f, root.y + width * 0.6f, root.x, root.y + width * 0.4f)
                        close()
                    }
                    drawPath(p, Brush.linearGradient(listOf(skin, skinDark), start = root, end = Offset(root.x + len, root.y)))
                }
            }
            // Front flippers sweep forward, rear ones trail.
            flipper(Offset(s * 0.06f, -s * 0.12f), -150f, s * 0.3f, s * 0.11f)
            flipper(Offset(s * 0.06f, s * 0.12f), 150f, s * 0.3f, s * 0.11f)
            flipper(Offset(-s * 0.13f, -s * 0.08f), -120f, s * 0.12f, s * 0.07f)
            flipper(Offset(-s * 0.13f, s * 0.08f), 120f, s * 0.12f, s * 0.07f)
            // Head.
            drawOval(skin, topLeft = Offset(s * 0.13f, -s * 0.055f), size = Size(s * 0.14f, s * 0.11f))
            drawCircle(Color(0xFF1A140C), radius = s * 0.012f, center = Offset(s * 0.22f, -s * 0.035f))
            drawCircle(Color(0xFF1A140C), radius = s * 0.012f, center = Offset(s * 0.22f, s * 0.035f))
            // Shell.
            val shell = Path().apply {
                moveTo(s * 0.16f, 0f)
                cubicTo(s * 0.16f, -s * 0.16f, -s * 0.12f, -s * 0.17f, -s * 0.2f, 0f)
                cubicTo(-s * 0.12f, s * 0.17f, s * 0.16f, s * 0.16f, s * 0.16f, 0f)
                close()
            }
            drawPath(
                shell,
                Brush.radialGradient(
                    listOf(Color(0xFFB98A3E), Color(0xFF7A5528), Color(0xFF3F2A12)),
                    center = Offset(-s * 0.01f, -s * 0.03f), radius = s * 0.22f,
                ),
            )
            // Scutes: a central column and the costals either side.
            val groove = Color(0xFF2A1B0B)
            val stroke = Stroke(width = s * 0.008f)
            for (i in 0 until 4) {
                val x = s * (0.09f - i * 0.075f)
                drawOval(Color(0x33F2C878), topLeft = Offset(x - s * 0.035f, -s * 0.035f), size = Size(s * 0.07f, s * 0.07f))
                drawOval(groove, topLeft = Offset(x - s * 0.035f, -s * 0.035f), size = Size(s * 0.07f, s * 0.07f), style = stroke)
            }
            for (side in listOf(-1f, 1f)) {
                for (i in 0 until 3) {
                    val x = s * (0.06f - i * 0.08f)
                    val y = side * s * 0.085f
                    drawOval(groove, topLeft = Offset(x - s * 0.04f, y - s * 0.03f), size = Size(s * 0.08f, s * 0.06f), style = stroke)
                }
            }
            drawPath(shell, Color(0x66E9D9A6), style = Stroke(width = s * 0.01f))
            // Sun highlight on the dome.
            val a = Math.toRadians(-40.0)
            drawCircle(
                Brush.radialGradient(listOf(Color(0x55FFFFFF), Color.Transparent), center = Offset(0f, 0f), radius = s * 0.08f),
                radius = s * 0.08f,
                center = Offset((cos(a) * s * 0.02).toFloat(), (sin(a) * s * 0.06).toFloat()),
            )
        }
    }
}

/** How a schooling fish looks from above, in the same palette as its shader. */
private enum class FishArt(
    val back: Color,
    val flank: Color,
    val fin: Color,
    val width: Float,
    /** Dark bars across the back, from the snout (0) to the tail (1). */
    val bars: List<Float> = emptyList(),
    val barWidth: Float = 0.05f,
    /** A coloured line along each side. */
    val stripe: Color? = null,
) {
    Manini(Color(0xFFAAB59C), Color(0xFFE6EBDF), Color(0xCCDCE0C8), 0.22f, listOf(0.12f, 0.28f, 0.44f, 0.6f, 0.76f, 0.9f), 0.035f),
    Taape(Color(0xFFE6BC40), Color(0xFFFADB5C), Color(0xDDF5CF48), 0.2f, stripe = Color(0xFF3D7DF0)),
    Ouma(Color(0xFF8E9184), Color(0xFFEDEDE5), Color(0xCCF2E6A0), 0.17f, stripe = Color(0xFFF7CF3A)),
    Mamo(Color(0xFFAABB98), Color(0xFFE5EDE0), Color(0xCCE5EDE0), 0.24f, listOf(0.24f, 0.4f, 0.56f, 0.72f, 0.86f), 0.07f),
    Paraharaha(Color(0xFFDB9E24), Color(0xFFFAD133), Color(0xDDF8CC38), 0.2f, listOf(0.1f), 0.07f),
}

/** Paraharaha go two by two: the black mask, and the white band behind it. */
private fun DrawScope.drawPair() {
    val s = size.minDimension
    for ((c, a) in listOf(Offset(s * 0.42f, s * 0.4f) to -24f, Offset(s * 0.6f, s * 0.62f) to -18f)) {
        drawFish(FishArt.Paraharaha, c, s * 0.46f, a)
        rotate(a, c) {
            val x = c.x + s * 0.46f * (0.42f - 0.8f * 0.18f)
            drawLine(Color(0xFFF4F2E8), Offset(x, c.y - s * 0.075f), Offset(x, c.y + s * 0.075f), strokeWidth = s * 0.022f)
        }
    }
}

/** A blacktip reef shark from above: a long grey body, swept fins, black tips. */
private fun DrawScope.drawShark() {
    val s = size.minDimension
    rotate(-32f, Offset(s / 2f, s / 2f)) {
        translate(s / 2f, s / 2f) {
            val len = s * 0.86f
            val w = len * 0.075f
            val grey = Color(0xFF7D786A)
            val tip = Color(0xFF111111)
            // Pectoral fins, swept back, tipped black.
            for (side in listOf(-1f, 1f)) {
                val fin = Path().apply {
                    moveTo(len * 0.18f, side * w * 0.8f)
                    lineTo(-len * 0.02f, side * w * 3.4f)
                    lineTo(len * 0.04f, side * w * 0.9f)
                    close()
                }
                drawPath(fin, grey)
                drawCircle(tip, radius = w * 0.45f, center = Offset(-len * 0.0f, side * w * 3.1f))
            }
            // The tail: a long upper lobe.
            val tail = Path().apply {
                moveTo(-len * 0.36f, 0f)
                lineTo(-len * 0.56f, -w * 1.6f)
                lineTo(-len * 0.48f, 0f)
                lineTo(-len * 0.52f, w * 1.1f)
                close()
            }
            drawPath(tail, grey)
            drawCircle(tip, radius = w * 0.4f, center = Offset(-len * 0.545f, -w * 1.45f))
            val body = Path().apply {
                moveTo(len * 0.44f, 0f)
                cubicTo(len * 0.36f, -w * 1.1f, len * 0.05f, -w * 1.2f, -len * 0.38f, -w * 0.25f)
                lineTo(-len * 0.38f, w * 0.25f)
                cubicTo(len * 0.05f, w * 1.2f, len * 0.36f, w * 1.1f, len * 0.44f, 0f)
                close()
            }
            drawPath(body, Brush.verticalGradient(listOf(Color(0xFFA7A291), Color(0xFF6E6A5C), Color(0xFFA7A291)), startY = -w, endY = w))
            // The dorsal fin seen from above: a dark ridge, its tip black.
            drawLine(Color(0xFF5E5A4E), Offset(len * 0.08f, 0f), Offset(-len * 0.06f, 0f), strokeWidth = w * 0.5f)
            drawCircle(tip, radius = w * 0.28f, center = Offset(-len * 0.06f, 0f))
            drawCircle(Color(0xFF0B0F0F), radius = w * 0.16f, center = Offset(len * 0.33f, -w * 0.6f))
            drawCircle(Color(0xFF0B0F0F), radius = w * 0.16f, center = Offset(len * 0.33f, w * 0.6f))
        }
    }
}

private fun DrawScope.drawFish(art: FishArt, center: Offset, length: Float, angleDeg: Float) {
    rotate(angleDeg, center) {
        translate(center.x, center.y) {
            val w = length * art.width
            // Forked tail.
            val tail = Path().apply {
                moveTo(-length * 0.36f, 0f)
                lineTo(-length * 0.62f, -w * 0.95f)
                lineTo(-length * 0.5f, 0f)
                lineTo(-length * 0.62f, w * 0.95f)
                close()
            }
            drawPath(tail, art.fin)
            if (art == FishArt.Mamo) {
                drawLine(Color(0xFF151515), Offset(-length * 0.4f, -w * 0.2f), Offset(-length * 0.6f, -w * 0.85f), strokeWidth = w * 0.18f)
                drawLine(Color(0xFF151515), Offset(-length * 0.4f, w * 0.2f), Offset(-length * 0.6f, w * 0.85f), strokeWidth = w * 0.18f)
            }
            val body = Path().apply {
                moveTo(length * 0.42f, 0f)
                cubicTo(length * 0.4f, -w * 0.9f, length * 0.05f, -w * 1.05f, -length * 0.38f, -w * 0.22f)
                lineTo(-length * 0.38f, w * 0.22f)
                cubicTo(length * 0.05f, w * 1.05f, length * 0.4f, w * 0.9f, length * 0.42f, 0f)
                close()
            }
            drawPath(body, Brush.verticalGradient(listOf(art.flank, art.back, art.flank), startY = -w, endY = w))
            art.stripe?.let { c ->
                for (side in listOf(-1f, 1f)) {
                    drawLine(c, Offset(length * 0.3f, side * w * 0.55f), Offset(-length * 0.34f, side * w * 0.2f), strokeWidth = w * 0.16f)
                }
            }
            for (b in art.bars) {
                val x = length * (0.42f - 0.8f * b)
                val half = w * (1f - (b - 0.35f) * (b - 0.35f) * 1.6f).coerceAtLeast(0.25f)
                drawLine(Color(0xEE111111), Offset(x, -half), Offset(x, half), strokeWidth = length * art.barWidth)
            }
            drawCircle(Color(0xFF0B0F0F), radius = w * 0.17f, center = Offset(length * 0.3f, -w * 0.5f))
            drawCircle(Color(0xFF0B0F0F), radius = w * 0.17f, center = Offset(length * 0.3f, w * 0.5f))
            if (art == FishArt.Ouma) {
                drawLine(Color(0xFFF2E3A0), Offset(length * 0.42f, -w * 0.15f), Offset(length * 0.55f, -w * 0.4f), strokeWidth = w * 0.08f)
                drawLine(Color(0xFFF2E3A0), Offset(length * 0.42f, w * 0.15f), Offset(length * 0.55f, w * 0.4f), strokeWidth = w * 0.08f)
            }
        }
    }
}

private fun DrawScope.drawSchool(art: FishArt) {
    val s = size.minDimension
    drawFish(art, Offset(s * 0.44f, s * 0.34f), s * 0.44f, -20f)
    drawFish(art, Offset(s * 0.62f, s * 0.58f), s * 0.4f, -14f)
    drawFish(art, Offset(s * 0.34f, s * 0.7f), s * 0.36f, -26f)
}

private fun DrawScope.drawEagleRay() {
    val s = size.minDimension
    val c = Offset(s * 0.5f, s * 0.48f)
    rotate(-35f, c) {
        translate(c.x, c.y) {
            val ink = Color(0xFF22262F)
            // The whip of a tail.
            val tail = Path().apply {
                moveTo(-s * 0.2f, 0f)
                cubicTo(-s * 0.32f, s * 0.02f, -s * 0.4f, -s * 0.05f, -s * 0.5f, -s * 0.02f)
            }
            drawPath(tail, ink, style = Stroke(width = s * 0.012f))
            // A diamond of a disc: head in front, pointed wings swept back.
            val disc = Path().apply {
                moveTo(s * 0.21f, 0f)
                cubicTo(s * 0.2f, -s * 0.035f, s * 0.15f, -s * 0.045f, s * 0.13f, -s * 0.045f)
                cubicTo(s * 0.06f, -s * 0.2f, -s * 0.02f, -s * 0.32f, -s * 0.05f, -s * 0.38f)
                cubicTo(-s * 0.08f, -s * 0.2f, -s * 0.14f, -s * 0.08f, -s * 0.2f, -s * 0.04f)
                lineTo(-s * 0.21f, 0f)
                lineTo(-s * 0.2f, s * 0.04f)
                cubicTo(-s * 0.14f, s * 0.08f, -s * 0.08f, s * 0.2f, -s * 0.05f, s * 0.38f)
                cubicTo(-s * 0.02f, s * 0.32f, s * 0.06f, s * 0.2f, s * 0.13f, s * 0.045f)
                cubicTo(s * 0.15f, s * 0.045f, s * 0.2f, s * 0.035f, s * 0.21f, 0f)
                close()
            }
            drawPath(disc, Brush.radialGradient(listOf(Color(0xFF39404F), ink), center = Offset(0f, 0f), radius = s * 0.35f))
            // White spots all over the back, smaller toward the tips.
            val spot = Color(0xFFE8EDF0)
            var k = 0
            for (i in -3..3) {
                for (j in -5..5) {
                    k++
                    val x = s * (0.045f * i - 0.02f + 0.012f * ((k * 7) % 3))
                    val y = s * (0.06f * j + 0.015f * ((k * 5) % 3 - 1))
                    if (x > s * 0.14f || x < -s * 0.18f) continue
                    val reach = s * (0.36f - 1.3f * abs(x + s * 0.05f) / s)
                    if (abs(y) > reach * 0.85f) continue
                    val r = s * (0.016f - 0.008f * abs(y) / (s * 0.38f))
                    drawCircle(spot, radius = r, center = Offset(x, y))
                }
            }
            drawCircle(Color(0xFF0B0C10), radius = s * 0.013f, center = Offset(s * 0.165f, -s * 0.04f))
            drawCircle(Color(0xFF0B0C10), radius = s * 0.013f, center = Offset(s * 0.165f, s * 0.04f))
        }
    }
}

private fun DrawScope.drawOctopus() {
    val s = size.minDimension
    val c = Offset(s * 0.48f, s * 0.54f)
    val skin = Color(0xFF9A6446)
    val skinDark = Color(0xFF5A3424)
    // Eight curling arms.
    for (k in 0 until 8) {
        val base = Math.toRadians(-60.0 + k * 45.0 + 8.0 * (k % 2))
        val curl = if (k % 2 == 0) 1f else -1f
        val path = Path()
        var x = c.x
        var y = c.y
        var a = base
        path.moveTo(x, y)
        val steps = 14
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            a += curl * 0.06 * (1 + 4 * t * t)
            x += (cos(a) * s * 0.028).toFloat()
            y += (sin(a) * s * 0.028).toFloat()
            path.lineTo(x, y)
        }
        drawPath(path, Brush.linearGradient(listOf(skinDark, skin), start = c, end = Offset(x, y)), style = Stroke(width = s * 0.04f, cap = StrokeCap.Round))
        drawPath(path, Color(0x55F1D9C0), style = Stroke(width = s * 0.012f))
    }
    // The web, then the mantle trailing up and left.
    drawCircle(skin, radius = s * 0.085f, center = c)
    rotate(-130f, c) {
        drawOval(
            Brush.radialGradient(listOf(Color(0xFFB9805D), skinDark), center = Offset(c.x + s * 0.17f, c.y - s * 0.02f), radius = s * 0.2f),
            topLeft = Offset(c.x + s * 0.03f, c.y - s * 0.09f),
            size = Size(s * 0.27f, s * 0.18f),
        )
        for (side in listOf(-1f, 1f)) {
            drawCircle(Color(0xFFE0C07A), radius = s * 0.025f, center = Offset(c.x + s * 0.05f, c.y + side * s * 0.065f))
            drawRect(Color(0xFF111111), topLeft = Offset(c.x + s * 0.043f, c.y + side * s * 0.065f - s * 0.016f), size = Size(s * 0.014f, s * 0.032f))
        }
    }
}

private fun DrawScope.drawHermitCrab() {
    val s = size.minDimension
    val c = Offset(s * 0.46f, s * 0.54f)
    rotate(-40f, c) {
        translate(c.x, c.y) {
            val red = Color(0xFFC8381E)
            // Legs out to the sides.
            for (side in listOf(-1f, 1f)) {
                for (pair in 0..1) {
                    val root = Offset(s * (0.16f - 0.04f * pair), side * s * 0.05f)
                    val knee = Offset(s * (0.22f - 0.1f * pair), side * s * (0.17f + 0.02f * pair))
                    val foot = Offset(s * (0.27f - 0.16f * pair), side * s * (0.27f + 0.02f * pair))
                    drawLine(red, root, knee, strokeWidth = s * 0.03f, cap = StrokeCap.Round)
                    drawLine(red, knee, foot, strokeWidth = s * 0.022f, cap = StrokeCap.Round)
                    drawCircle(Color(0xFF1C0F0A), radius = s * 0.012f, center = foot)
                }
            }
            // The turban shell, spire at the back.
            val shell = Path().apply {
                moveTo(s * 0.16f, 0f)
                cubicTo(s * 0.16f, -s * 0.15f, -s * 0.1f, -s * 0.15f, -s * 0.24f, -s * 0.01f)
                cubicTo(-s * 0.1f, s * 0.15f, s * 0.16f, s * 0.15f, s * 0.16f, 0f)
                close()
            }
            drawPath(shell, Brush.radialGradient(listOf(Color(0xFFB37440), Color(0xFF6E3A17), Color(0xFF3A1F0C)), center = Offset(-s * 0.04f, -s * 0.03f), radius = s * 0.22f))
            for (i in 0 until 4) {
                val r = s * (0.03f + 0.035f * i)
                drawArc(Color(0xCCEAD9B4), startAngle = 200f - i * 20f, sweepAngle = 220f, useCenter = false,
                    topLeft = Offset(-s * 0.13f - r, -r), size = Size(r * 2f, r * 2f), style = Stroke(width = s * 0.012f))
            }
            drawCircle(Brush.radialGradient(listOf(Color(0x88FFFFFF), Color.Transparent), radius = s * 0.06f, center = Offset(-s * 0.03f, -s * 0.06f)), radius = s * 0.06f, center = Offset(-s * 0.03f, -s * 0.06f))
            // Claws, the left one bigger, and eyes on stalks.
            for ((side, k) in listOf(-1f to 1.25f, 1f to 0.9f)) {
                drawOval(red, topLeft = Offset(s * 0.17f, side * s * 0.05f - s * 0.035f * k), size = Size(s * 0.12f * k, s * 0.07f * k))
                drawCircle(Color(0xFFF3EEE4), radius = s * 0.01f * k, center = Offset(s * (0.2f + 0.02f * k), side * s * 0.05f))
            }
            for (side in listOf(-1f, 1f)) {
                drawLine(red, Offset(s * 0.17f, side * s * 0.015f), Offset(s * 0.26f, side * s * 0.04f), strokeWidth = s * 0.012f)
                drawCircle(Color(0xFF0A0D14), radius = s * 0.014f, center = Offset(s * 0.265f, side * s * 0.042f))
                drawLine(Color(0xFFD85A3A), Offset(s * 0.2f, side * s * 0.01f), Offset(s * 0.46f, side * s * 0.16f), strokeWidth = s * 0.005f)
            }
        }
    }
}

@Preview
@Composable
private fun SpeciesArtPreview() {
    Box(Modifier.size(120.dp)) { SpeciesArt(SpeciesCatalog.GreenSeaTurtle.id, Modifier.size(120.dp)) }
}
