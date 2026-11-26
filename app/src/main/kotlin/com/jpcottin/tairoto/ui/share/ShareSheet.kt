package com.jpcottin.tairoto.ui.share

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jpcottin.tairoto.share.JoinTarget
import com.jpcottin.tairoto.share.NearbyLagoon
import com.jpcottin.tairoto.share.RelayAddress
import com.jpcottin.tairoto.share.ShareRoute
import com.jpcottin.tairoto.ui.tank.ShareDeviceUi
import com.jpcottin.tairoto.ui.tank.SharePhase
import com.jpcottin.tairoto.ui.tank.ShareRole
import com.jpcottin.tairoto.ui.tank.ShareUi
import com.jpcottin.tairoto.ui.theme.Ocean
import com.jpcottin.tairoto.world.TankSize
import java.util.Locale
import kotlin.math.min

object ShareTags {
    const val SHEET = "share_sheet"
    const val PANE = "share_pane"
    const val CLOSE = "share_close"
    const val SHARE_NEARBY = "share_nearby"
    const val SHARE_RELAY = "share_relay"
    const val JOIN_RELAY = "share_join_relay"
    const val RELAY_FIELD = "share_relay_field"
    const val COMPUTER = "share_computer"
    const val STOP = "share_stop"
    const val CANCEL = "share_cancel"
    const val MAP = "share_map"
    const val STATUS = "share_status"
    const val MESSAGE = "share_message"
    const val LOOKING = "share_looking"
    const val ALLOW = "share_allow"
    fun nearby(name: String) = "share_nearby_$name"
}

/** What the share sheet can ask for. */
interface ShareActions {
    fun share(route: ShareRoute)
    fun join(target: JoinTarget)
    fun stop()
    fun relayChanged(text: String)
    fun dismissMessage()
    /** Asks for the local network (Android 17), to look for lagoons nearby. */
    fun allowLocalNetwork() {}
}

/**
 * Sharing the lagoon with the devices on the table: start a shared lagoon,
 * join one nearby (or through a computer, for emulators), and once shared,
 * the table drawn to scale with every screen on it. A sheet over the lagoon
 * on a narrow window, a pane beside it on a wide one (with [onClose]).
 */
@Composable
fun ShareSheet(ui: ShareUi, actions: ShareActions, onClose: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .testTag(ShareTags.SHEET),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(ui)
            ui.message?.let { Message(it, actions::dismissMessage) }
            when (ui.phase) {
                SharePhase.OFF -> Start(ui, actions)
                SharePhase.CONNECTING -> Connecting(actions::stop)
                SharePhase.SHARED -> Shared(ui, actions::stop)
            }
        }
    }
    if (onClose == null) {
        content()
        return
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize().testTag(ShareTags.PANE)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))) {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 8.dp)) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.testTag(ShareTags.CLOSE)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }
            content()
        }
    }
}

@Composable
private fun Header(ui: ShareUi) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when {
                ui.phase != SharePhase.SHARED -> "Share the lagoon"
                ui.role == ShareRole.HOST -> "Your lagoon, on ${screens(ui.devices.size)}"
                else -> "In ${ui.hostName ?: "a shared"}'s lagoon"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = Ocean.Foam,
        )
        Text(
            if (ui.phase == SharePhase.SHARED) {
                "Lay the screens as shown, edge to edge. A fish swimming off one screen comes in on the next."
            } else {
                "Lay phones, foldables and tablets side by side on a table: one lagoon across all of them, the fish swimming from screen to screen."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun screens(n: Int) = if (n == 1) "1 screen" else "$n screens"

@Composable
private fun Message(text: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag(ShareTags.MESSAGE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun Start(ui: ShareUi, actions: ShareActions) {
    // Start one.
    Option(Icons.Outlined.Wifi, "Start a shared lagoon", "This lagoon, with everything in it. Devices on the same Wi-Fi join it.") {
        Button(onClick = { actions.share(ShareRoute.Nearby) }, modifier = Modifier.testTag(ShareTags.SHARE_NEARBY)) { Text("Share on this network") }
    }
    // Join one nearby.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Lagoons nearby", style = MaterialTheme.typography.titleSmall, color = Ocean.Foam)
        if (!ui.localNetwork) {
            Text(
                "Tairoto needs your permission to look for lagoons on this network.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = actions::allowLocalNetwork, modifier = Modifier.testTag(ShareTags.ALLOW)) { Text("Allow") }
        } else if (ui.nearby.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag(ShareTags.LOOKING)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ocean.SeaGlass)
                Text("Looking on this network…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ui.nearby.forEach { lagoon -> NearbyRow(lagoon) { actions.join(JoinTarget.Nearby(lagoon)) } }
    }
    ThroughAComputer(ui.relay, actions)
}

@Composable
private fun Option(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = Ocean.SeaGlass)
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ocean.Foam)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}

@Composable
private fun NearbyRow(lagoon: NearbyLagoon, onJoin: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag(ShareTags.nearby(lagoon.name))) {
        Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Devices, null, tint = Ocean.Sand)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("${lagoon.name}'s lagoon", style = MaterialTheme.typography.titleSmall, color = Ocean.Foam, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(lagoon.host, style = MaterialTheme.typography.labelMedium, color = Ocean.Mist)
            }
            FilledTonalButton(onClick = onJoin) { Text("Join") }
        }
    }
}

@Composable
private fun ThroughAComputer(relay: String, actions: ShareActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    val address = RelayAddress.parse(relay)
    Column {
        TextButton(onClick = { open = !open }, modifier = Modifier.testTag(ShareTags.COMPUTER)) {
            Icon(Icons.Outlined.Computer, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Through a computer (emulators)")
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Run the relay on the computer (relay/build/install/relay/bin/relay). Emulators reach it at 10.0.2.2.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = relay,
                    onValueChange = actions::relayChanged,
                    label = { Text("Relay") },
                    singleLine = true,
                    isError = address == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag(ShareTags.RELAY_FIELD),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { address?.let { actions.share(ShareRoute.Relay(it)) } },
                        enabled = address != null,
                        modifier = Modifier.testTag(ShareTags.SHARE_RELAY),
                    ) { Text("Share") }
                    OutlinedButton(
                        onClick = { address?.let { actions.join(JoinTarget.Relay(it)) } },
                        enabled = address != null,
                        modifier = Modifier.testTag(ShareTags.JOIN_RELAY),
                    ) { Text("Join") }
                }
            }
        }
    }
}

@Composable
private fun Connecting(onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), color = Ocean.SeaGlass)
        Text("Connecting…", style = MaterialTheme.typography.titleMedium, color = Ocean.Foam, modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel, modifier = Modifier.testTag(ShareTags.CANCEL)) { Text("Cancel") }
    }
}

@Composable
private fun Shared(ui: ShareUi, onStop: () -> Unit) {
    if (ui.tank != null && ui.devices.isNotEmpty()) TableMap(ui.devices, ui.tank)
    if (!ui.connected) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Out of touch with ${ui.hostName ?: "the host"}. The fish here wait until it answers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
    Text(
        buildString {
            append(if (ui.here == 1) "1 creature swims here" else "${ui.here} creatures swim here")
            append("  ·  ${ui.arrived} came over  ·  ${ui.left} swam on")
            ui.rttMs?.let { append(String.format(Locale.US, "  ·  %.0f ms", it)) }
        },
        style = MaterialTheme.typography.labelMedium,
        color = Ocean.Mist,
        modifier = Modifier.testTag(ShareTags.STATUS),
    )
    OutlinedButton(onClick = onStop, modifier = Modifier.testTag(ShareTags.STOP)) {
        Text(if (ui.role == ShareRole.HOST) "Stop sharing" else "Leave the lagoon")
    }
}

/**
 * The table seen from above, to scale: the water of the shared tank and
 * every screen lying on it, this one picked out. The layout editor of the
 * next milestone moves them; for now they lie side by side in the order they
 * joined.
 */
@Composable
fun TableMap(devices: List<ShareDeviceUi>, tank: TankSize, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "self").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse",
    )
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // As wide as there is room for, and never so tall that the sheet is all map.
        val mapW = maxWidth
        val mapH = minOf(mapW * (tank.heightMm / tank.widthMm), MAP_MAX_HEIGHT)
        Box(
            Modifier
                .size(mapW, mapH)
                .testTag(ShareTags.MAP)
                .semantics {
                    contentDescription = "The table: " + devices.joinToString { d ->
                        d.name + (if (d.self) " (this one)" else "") + (if (d.host) ", the host" else "")
                    }
                },
        ) {
            TableMapContent(devices, tank, pulse, mapW, mapH)
        }
    }
}

private val MAP_MAX_HEIGHT = 260.dp
private val NARROW_LABEL = 56.dp

/** Lays its content out a quarter turn anticlockwise, reading upwards, in the space turned with it. */
private fun Modifier.sideways() = layout { measurable, constraints ->
    val p = measurable.measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
    layout(p.height, p.width) {
        p.placeWithLayer(-(p.width - p.height) / 2, (p.width - p.height) / 2) { rotationZ = -90f }
    }
}

@Composable
private fun TableMapContent(devices: List<ShareDeviceUi>, tank: TankSize, pulse: Float, mapW: Dp, mapH: Dp) {
    val density = LocalDensity.current
    val wPx = with(density) { mapW.toPx() }
    val hPx = with(density) { mapH.toPx() }
    val k = min(wPx / tank.widthMm, hPx / tank.heightMm)
    val ox = (wPx - tank.widthMm * k) / 2f
    val oy = (hPx - tank.heightMm * k) / 2f
    Canvas(Modifier.fillMaxSize()) {
        // The water, with the sand showing through toward the middle.
        drawRoundRect(
            Brush.radialGradient(
                listOf(Color(0xFF2E7F7A), Color(0xFF0E4A52), Color(0xFF082E36)),
                center = Offset(ox + tank.widthMm * k / 2f, oy + tank.heightMm * k / 2f),
                radius = tank.widthMm * k * 0.7f,
            ),
            topLeft = Offset(ox, oy),
            size = Size(tank.widthMm * k, tank.heightMm * k),
            cornerRadius = CornerRadius(14.dp.toPx()),
        )
        for (d in devices) {
            val tl = Offset(ox + d.rect.x * k, oy + d.rect.y * k)
            val sz = Size(d.rect.w * k, d.rect.h * k)
            val r = CornerRadius(min(sz.width, sz.height) * 0.08f)
            // A screen: dark glass with a bezel, this one glowing.
            drawRoundRect(Color(0xCC041A20), tl, sz, r)
            drawRoundRect(if (d.self) Ocean.SeaGlass.copy(alpha = pulse) else Ocean.Foam.copy(alpha = 0.55f), tl, sz, r, style = Stroke(if (d.self) 2.5.dp.toPx() else 1.5.dp.toPx()))
        }
    }
    for (d in devices) {
        val x = with(density) { (ox + d.rect.x * k).toDp() }
        val y = with(density) { (oy + d.rect.y * k).toDp() }
        val w = with(density) { (d.rect.w * k).toDp() }
        val h = with(density) { (d.rect.h * k).toDp() }
        // A screen drawn too narrow for its name (a phone beside a tablet) has it written up its side.
        val upright = w >= NARROW_LABEL || h < w
        Box(Modifier.offset(x, y).size(w, h).padding(4.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = if (upright) Modifier else Modifier.sideways()) {
                Text(
                    d.name,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (d.self) Ocean.SeaGlass else Ocean.Foam,
                    textAlign = TextAlign.Center,
                    maxLines = if (upright) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (d.host) "host" else if (d.self) "this one" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ocean.Mist,
                )
            }
        }
    }
}

/** A small glass button over the lagoon: share it, or how many screens it is on. */
@Composable
fun SharePill(ui: ShareUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = if (ui.phase == SharePhase.SHARED) Ocean.Teal.copy(alpha = 0.85f) else Ocean.Glass,
        border = com.jpcottin.tairoto.ui.theme.GlassBorder,
        shape = RoundedCornerShape(50),
        modifier = modifier.heightIn(min = 40.dp).widthIn(min = 48.dp).testTag(SharePillTag).semantics {
            contentDescription = when (ui.phase) {
                SharePhase.SHARED -> "Shared on ${screens(ui.devices.size)}"
                SharePhase.CONNECTING -> "Connecting"
                SharePhase.OFF -> "Share the lagoon"
            }
        },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.phase == SharePhase.CONNECTING) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ocean.SeaGlass)
            } else {
                Icon(Icons.Outlined.Devices, null, Modifier.size(18.dp), tint = if (ui.phase == SharePhase.SHARED) Ocean.SeaGlass else Ocean.Foam)
            }
            Text(
                when (ui.phase) {
                    SharePhase.SHARED -> screens(ui.devices.size) + if (!ui.connected) " · waiting" else ""
                    SharePhase.CONNECTING -> "Connecting"
                    SharePhase.OFF -> "Share"
                },
                style = MaterialTheme.typography.labelLarge,
                color = Ocean.Foam,
            )
        }
    }
}

const val SharePillTag = "share_pill"
