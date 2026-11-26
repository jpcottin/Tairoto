package com.jpcottin.tairoto.ui.species

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jpcottin.tairoto.world.DecorCatalog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small top-down portrait of a decoration, in the palette of its shader,
 * for the picker. Drawn, so it stays sharp at any size.
 */
@Composable
fun DecorArt(kindId: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawSandDisc()
        when (kindId) {
            DecorCatalog.BranchingCoral.id -> drawBranchingCoral()
            DecorCatalog.GiantClam.id -> drawGiantClam()
            DecorCatalog.Stone.id -> drawStone()
            else -> drawCoralHead()
        }
    }
}

/** A closed blob around the centre, its radius wobbling with a few lobes. */
private fun blob(c: Offset, r: Float, wobble: Float, lobes: Int, phase: Float, squash: Float = 1f): Path = Path().apply {
    val n = 72
    for (i in 0..n) {
        val a = 2 * PI * i / n
        val rr = r * (1f + wobble * sin(lobes * a + phase).toFloat() + wobble * 0.5f * sin((lobes + 2) * a + phase * 1.7).toFloat())
        val p = Offset(c.x + rr * cos(a).toFloat(), c.y + rr * sin(a).toFloat() * squash)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun DrawScope.shadowUnder(path: Path, dx: Float, dy: Float) {
    val p = Path().apply { addPath(path, Offset(dx, dy)) }
    drawPath(p, Color(0x55041A20))
}

private fun DrawScope.drawCoralHead() {
    val r = size.minDimension * 0.34f
    val c = center
    val outline = blob(c, r, 0.07f, 5, 0.6f)
    shadowUnder(outline, r * 0.12f, r * 0.16f)
    drawPath(outline, Brush.radialGradient(listOf(Color(0xFFE8D7A8), Color(0xFFC6A86E), Color(0xFF6E5A38)), c - Offset(r * 0.3f, r * 0.35f), r * 1.5f))
    // Lobes with lit crowns and dark grooves between them.
    val lobes = listOf(-0.35f to -0.3f, 0.2f to -0.42f, 0.45f to 0.05f, -0.05f to 0.05f, -0.48f to 0.22f, 0.15f to 0.45f, -0.2f to 0.5f, 0.5f to 0.42f)
    lobes.forEach { (x, y) ->
        val p = c + Offset(x * r, y * r)
        drawCircle(Color(0x553A2A14), radius = r * 0.3f, center = p, style = Stroke(r * 0.04f))
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFF6DC), Color(0x00FFF6DC)), p - Offset(r * 0.08f, r * 0.08f), r * 0.24f), radius = r * 0.24f, center = p)
    }
    // A pair of Christmas tree worms.
    listOf(Offset(0.12f, -0.12f), Offset(0.24f, -0.08f)).forEachIndexed { i, o ->
        val p = c + Offset(o.x * r, o.y * r)
        drawCircle(if (i == 0) Color(0xFFD8241C) else Color(0xFF2050E0), radius = r * 0.075f, center = p)
        drawCircle(Color(0x99FFFFFF), radius = r * 0.075f, center = p, style = Stroke(r * 0.02f))
    }
    drawPath(outline, Color(0x66403018), style = Stroke(r * 0.03f))
}

private fun DrawScope.drawBranchingCoral() {
    val r = size.minDimension * 0.36f
    val c = center
    val tan = Color(0xFFA88A5C)
    val tip = Color(0xFFB08AD8)
    val n = 11
    // Shadows first, then branches from the shaded base out to the pale tips.
    for (pass in 0..1) {
        for (i in 0 until n) {
            val a = 2 * PI * (i + 0.3 * sin(i * 2.1)) / n
            val len = r * (0.75f + 0.25f * sin(i * 1.7).toFloat())
            val end = c + Offset((cos(a) * len).toFloat(), (sin(a) * len).toFloat())
            val mid = c + Offset((cos(a + 0.12) * len * 0.55f).toFloat(), (sin(a + 0.12) * len * 0.55f).toFloat())
            val fork = mid + Offset((cos(a + 0.6) * len * 0.4f).toFloat(), (sin(a + 0.6) * len * 0.4f).toFloat())
            if (pass == 0) {
                val s = Offset(r * 0.1f, r * 0.14f)
                drawLine(Color(0x44041A20), c + s, end + s, r * 0.16f, StrokeCap.Round)
                continue
            }
            val brush = Brush.linearGradient(listOf(Color(0xFF5E4A30), tan, tip), c, end)
            drawLine(brush, c, end, r * 0.15f, StrokeCap.Round)
            drawLine(Brush.linearGradient(listOf(tan, tip), mid, fork), mid, fork, r * 0.11f, StrokeCap.Round)
            drawCircle(Color(0xFFF4EFE8), radius = r * 0.045f, center = end)
            drawCircle(Color(0xFFF4EFE8), radius = r * 0.035f, center = fork)
        }
    }
    // Upright branches in the middle, seen end on.
    listOf(Offset(0.1f, -0.05f), Offset(-0.12f, 0.1f), Offset(0.02f, 0.18f), Offset(-0.05f, -0.16f)).forEach { o ->
        val p = c + Offset(o.x * r, o.y * r)
        drawCircle(tip, radius = r * 0.08f, center = p)
        drawCircle(Color(0xFFF7F3EE), radius = r * 0.035f, center = p)
    }
}

private fun DrawScope.drawGiantClam() {
    val w = size.minDimension * 0.7f
    val h = w * 0.56f
    val c = center
    val shell = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c - Offset(w / 2, h / 2), Size(w, h))) }
    shadowUnder(shell, w * 0.05f, w * 0.07f)
    drawPath(shell, Brush.verticalGradient(listOf(Color(0xFFE6E2D4), Color(0xFFA8A496)), c.y - h / 2, c.y + h / 2))
    // The fluted folds of the valves.
    for (i in -4..4) {
        val x = c.x + i * w * 0.1f
        drawLine(Color(0x55605848), Offset(x, c.y - h * 0.5f), Offset(x, c.y + h * 0.5f), w * 0.012f)
    }
    // The mantle between the scalloped lips: electric blue, spotted, a bright rim.
    val mantle = Path()
    val n = 60
    for (i in 0..n) {
        val t = i.toFloat() / n
        val x = -1f + 2f * t
        val span = kotlin.math.sqrt((1f - x * x).coerceAtLeast(0f))
        val y = (0.74f * span + 0.05f * sin(x * 26f)) * h / 2
        val p = Offset(c.x + x * w / 2 * 0.94f, c.y - y)
        if (i == 0) mantle.moveTo(p.x, p.y) else mantle.lineTo(p.x, p.y)
    }
    for (i in n downTo 0) {
        val t = i.toFloat() / n
        val x = -1f + 2f * t
        val span = kotlin.math.sqrt((1f - x * x).coerceAtLeast(0f))
        val y = (0.74f * span + 0.05f * sin(x * 26f + 1f)) * h / 2
        mantle.lineTo(c.x + x * w / 2 * 0.94f, c.y + y)
    }
    mantle.close()
    drawPath(mantle, Brush.radialGradient(listOf(Color(0xFF2A6BEA), Color(0xFF123C9E), Color(0xFF5CE3F0)), c, w * 0.5f))
    for (i in 0 until 14) {
        val p = c + Offset(w * (0.38f * sin(i * 2.3f)), h * (0.22f * cos(i * 1.9f)))
        drawCircle(Color(0xFF0A1C5A), radius = w * 0.022f, center = p)
    }
    drawCircle(Color(0xFF02060C), radius = w * 0.04f, center = c + Offset(w * 0.25f, 0f))
    drawCircle(Color(0xFF7DF0F8), radius = w * 0.06f, center = c + Offset(w * 0.25f, 0f), style = Stroke(w * 0.014f))
    drawPath(mantle, Color(0xAA7DF0F8), style = Stroke(w * 0.016f))
}

private fun DrawScope.drawStone() {
    val r = size.minDimension * 0.3f
    val c = center
    val outline = blob(c, r, 0.12f, 3, 1.4f, squash = 0.85f)
    shadowUnder(outline, r * 0.1f, r * 0.12f)
    drawPath(outline, Brush.radialGradient(listOf(Color(0xFF7E786E), Color(0xFF4A4640), Color(0xFF2A2824)), c - Offset(r * 0.35f, r * 0.35f), r * 1.4f))
    // Pink coralline crusts and green turf.
    listOf(Offset(0.45f, 0.2f) to 0.3f, Offset(-0.35f, 0.42f) to 0.22f, Offset(0.1f, -0.5f) to 0.18f).forEach { (o, s) ->
        drawPath(blob(c + Offset(o.x * r, o.y * r), r * s, 0.2f, 4, o.x * 5f), Color(0xFFC77A8C))
    }
    drawPath(blob(c + Offset(-0.15f * r, -0.1f * r), r * 0.32f, 0.25f, 5, 2f), Color(0xCC5A6E34))
    for (i in 0 until 9) {
        val p = c + Offset(r * 0.6f * sin(i * 2.7f), r * 0.5f * cos(i * 1.3f))
        drawCircle(Color(0x88181614), radius = r * 0.035f, center = p)
    }
}

@Preview(widthDp = 440, heightDp = 110)
@Composable
private fun DecorArtPreview() {
    Row {
        DecorCatalog.all.forEach { DecorArt(it.id, Modifier.size(110.dp).clip(CircleShape)) }
    }
}
