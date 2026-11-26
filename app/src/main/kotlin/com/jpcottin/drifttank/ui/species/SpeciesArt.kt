package com.jpcottin.drifttank.ui.species

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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jpcottin.drifttank.world.SpeciesCatalog
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
            else -> drawChromisTrio()
        }
    }
}

private fun DrawScope.drawSandDisc() {
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

@Preview
@Composable
private fun SpeciesArtPreview() {
    Box(Modifier.size(120.dp)) { SpeciesArt(SpeciesCatalog.GreenSeaTurtle.id, Modifier.size(120.dp)) }
}
