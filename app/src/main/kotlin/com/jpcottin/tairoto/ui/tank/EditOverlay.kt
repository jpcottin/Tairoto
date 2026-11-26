package com.jpcottin.tairoto.ui.tank

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Phishing
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jpcottin.tairoto.ui.theme.GlassBorder
import com.jpcottin.tairoto.ui.theme.Ocean
import kotlin.math.roundToInt

object EditTags {
    const val ARRANGE = "tank_arrange"
    const val BAR = "edit_bar"
    const val DONE = "edit_done"
    const val UNDO = "edit_undo"
    const val REDO = "edit_redo"
    const val MOVE = "edit_tool_move"
    const val NET = "edit_tool_net"
    const val ADD = "edit_add"
    const val SELECTION = "edit_selection"
    const val TURN = "edit_turn"
    const val REMOVE = "edit_remove"
    const val HINT = "edit_hint"
    const val RING = "edit_ring"
}

/** What the edit bar can do; the view model in the app, nothing in previews. */
interface EditActions {
    fun done()
    fun undo()
    fun redo()
    fun tool(tool: EditTool)
    fun add()
    fun turn()
    fun remove()
}

/**
 * Arranging the lagoon: a bar to finish, undo and redo, the two tools (move
 * decorations, net creatures), and for a selected decoration its name with
 * buttons to turn it and to take it away.
 */
@Composable
fun EditOverlay(edit: EditUi, actions: EditActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().testTag(EditTags.BAR), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = actions::done,
                    color = Ocean.SeaGlass,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.height(48.dp).testTag(EditTags.DONE).semantics { contentDescription = "Done arranging" },
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(22.dp), tint = Ocean.Abyss)
                        Spacer(Modifier.width(8.dp))
                        Text("Done", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = Ocean.Abyss)
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassAction(actions::undo, edit.undo.undo?.let { "Undo $it" } ?: "Nothing to undo", EditTags.UNDO, enabled = edit.undo.canUndo) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, null, Modifier.size(24.dp), tint = Ocean.Foam)
                    }
                    GlassAction(actions::redo, edit.undo.redo?.let { "Redo $it" } ?: "Nothing to redo", EditTags.REDO, enabled = edit.undo.canRedo) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, null, Modifier.size(24.dp), tint = Ocean.Foam)
                    }
                    GlassAction(actions::add, "Add a decoration", EditTags.ADD) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(26.dp), tint = Ocean.Foam)
                    }
                }
            }
            ToolSwitch(edit.tool, actions::tool, Modifier.align(Alignment.CenterHorizontally))
        }

        val selection = edit.selection
        AnimatedVisibility(
            visible = selection != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            Surface(color = Ocean.GlassStrong, border = GlassBorder, shape = RoundedCornerShape(50), modifier = Modifier.testTag(EditTags.SELECTION).semantics(mergeDescendants = true) {}) {
                Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(selection?.name ?: "", style = MaterialTheme.typography.titleMedium, color = Ocean.Foam)
                    Spacer(Modifier.width(12.dp))
                    GlassAction(actions::turn, "Turn it", EditTags.TURN) {
                        Icon(Icons.Rounded.RotateRight, null, Modifier.size(24.dp), tint = Ocean.Foam)
                    }
                    Spacer(Modifier.width(6.dp))
                    GlassAction(actions::remove, "Take it away", EditTags.REMOVE) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(22.dp), tint = Color(0xFFFFB4A8))
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = selection == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
        ) {
            Surface(color = Ocean.Glass, border = GlassBorder, shape = RoundedCornerShape(50), modifier = Modifier.testTag(EditTags.HINT)) {
                Text(
                    if (edit.tool == EditTool.NET) "Tap a creature to net it" else "Drag a decoration to move it  ·  Twist to turn it",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ocean.Foam,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp).widthIn(max = 320.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolSwitch(tool: EditTool, onTool: (EditTool) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = Ocean.Glass, border = GlassBorder, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolChip("Move", tool == EditTool.MOVE, EditTags.MOVE, { onTool(EditTool.MOVE) }) {
                Icon(Icons.Outlined.PanTool, null, Modifier.size(18.dp), tint = it)
            }
            ToolChip("Net", tool == EditTool.NET, EditTags.NET, { onTool(EditTool.NET) }) {
                Icon(Icons.Outlined.Phishing, null, Modifier.size(18.dp), tint = it)
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, on: Boolean, tag: String, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val fg = if (on) Ocean.Abyss else Ocean.Foam
    Surface(
        onClick = onClick,
        color = if (on) Ocean.Foam else Color.Transparent,
        shape = RoundedCornerShape(50),
        modifier = Modifier.height(40.dp).testTag(tag).semantics { selected = on },
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            icon(fg)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}

@Composable
private fun GlassAction(onClick: () -> Unit, label: String, tag: String, enabled: Boolean = true, icon: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Ocean.Glass,
        border = GlassBorder,
        shape = CircleShape,
        modifier = Modifier.size(48.dp).alpha(if (enabled) 1f else 0.4f).testTag(tag).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

/** A dashed ring that slowly turns round the selected decoration. */
@Composable
fun SelectionRing(selection: SelectionUi?, modifier: Modifier = Modifier) {
    if (selection == null) return
    val turn by rememberInfiniteTransition(label = "ring").animateFloat(
        0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart), label = "turn",
    )
    Canvas(modifier.fillMaxSize().testTag(EditTags.RING)) {
        val c = Offset(selection.centerPx.x, selection.centerPx.y)
        val r = selection.radiusPx
        drawCircle(Color(0x332FE0C8), radius = r + 10f, center = c, style = Stroke(14f))
        rotate(turn, c) {
            drawCircle(
                Ocean.Foam, radius = r, center = c,
                style = Stroke(4f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 14f))),
            )
        }
    }
}

/**
 * The net: a hoop of mesh swept through the water where the finger tapped,
 * and a word on what it caught (or that it came up empty).
 */
@Composable
fun NetSweepEffect(sweep: NetSweep?, modifier: Modifier = Modifier) {
    if (sweep == null) return
    val t = remember(sweep.seq) { Animatable(0f) }
    LaunchedEffect(sweep.seq) { t.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    if (t.value >= 1f) return
    Box(modifier.fillMaxSize().clipToBounds()) {
        Canvas(Modifier.fillMaxSize()) {
            val p = t.value
            val c = Offset(sweep.centerPx.x, sweep.centerPx.y)
            val r = sweep.radiusPx * (0.55f + 0.5f * p)
            val fade = 1f - smooth(0.55f, 1f, p)
            rotate(-40f + 80f * p, c) {
                val hoop = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
                clipPath(hoop) {
                    drawCircle(Color(0x22E6FBF6), radius = r, center = c)
                    val step = r / 4.5f
                    var k = -r * 1.5f
                    while (k <= r * 1.5f) {
                        drawLine(Color(0x99E6FBF6).copy(alpha = 0.6f * fade), Offset(c.x + k - r, c.y - r), Offset(c.x + k + r, c.y + r), 2.2f)
                        drawLine(Color(0x99E6FBF6).copy(alpha = 0.6f * fade), Offset(c.x + k + r, c.y - r), Offset(c.x + k - r, c.y + r), 2.2f)
                        k += step
                    }
                }
                drawCircle(Color(0xFFF2D28C).copy(alpha = fade), radius = r, center = c, style = Stroke(6f))
                // The handle, trailing behind the sweep.
                drawLine(Color(0xFFB98B4E).copy(alpha = fade), Offset(c.x + r * 0.7f, c.y + r * 0.7f), Offset(c.x + r * 1.9f, c.y + r * 1.9f), 9f, StrokeCap.Round)
            }
        }
        val density = LocalDensity.current
        Text(
            sweep.caught?.let { "Netted: $it" } ?: "Nothing in the net",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Ocean.Foam,
            modifier = Modifier
                .offset { IntOffset((sweep.centerPx.x - with(density) { 70.dp.toPx() }).roundToInt(), (sweep.centerPx.y - sweep.radiusPx - with(density) { 40.dp.toPx() } * t.value).roundToInt()) }
                .alpha(1f - smooth(0.6f, 1f, t.value)),
        )
    }
}

private fun smooth(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
