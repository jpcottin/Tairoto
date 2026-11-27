package com.jpcottin.tairoto.ui.draw

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jpcottin.tairoto.ui.tank.DrawPhase
import com.jpcottin.tairoto.ui.tank.DrawUi
import com.jpcottin.tairoto.ui.theme.Ocean
import com.jpcottin.tairoto.world.Skin
import com.jpcottin.tairoto.world.TankScene
import com.jpcottin.tairoto.world.TemplatePage

object DrawTags {
    const val SHEET = "draw_sheet"
    const val PANE = "draw_pane"
    const val CLOSE = "draw_close"
    const val PRINT = "draw_print"
    const val CAMERA = "draw_camera"
    const val CHOOSE = "draw_choose"
    const val PREVIEW = "draw_preview"
    const val RELEASE = "draw_release"
    const val AGAIN = "draw_again"
    const val MESSAGE = "draw_message"
    const val SCANNING = "draw_scanning"
}

/** What the drawing sheet can do; the navigation wires it to the system (printing, the camera, photos) and the view model. */
interface DrawActions {
    fun print()
    fun takePhoto()
    fun choosePhoto()
    fun release()
    fun again()
}

/**
 * Drawing a fish of one's own: print the page, colour the fish in, photograph
 * it, see it cut out, and let it swim. A sheet over the lagoon on a narrow
 * window, a pane beside it (with [onClose]) on a wide one.
 */
@Composable
fun DrawSheet(ui: DrawUi, actions: DrawActions, onClose: (() -> Unit)?) {
    val content: @Composable () -> Unit = { DrawContent(ui, actions) }
    if (onClose == null) {
        content()
        return
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize().testTag(DrawTags.PANE)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.testTag(DrawTags.CLOSE)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }
            content()
        }
    }
}

@Composable
private fun DrawContent(ui: DrawUi, actions: DrawActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(DrawTags.SHEET)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Draw your own fish", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Colour in a fish on paper, take a photo, and it swims in the lagoon in your colours.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (ui.phase) {
            DrawPhase.FOUND, DrawPhase.FULL -> ui.skin?.let { Result(it, ui, actions) }
            DrawPhase.SCANNING -> Scanning()
            else -> Unit
        }
        Message(ui)
        Step(1, "Print the page") {
            PagePicture(Modifier.fillMaxWidth().widthIn(max = 420.dp))
            FilledTonalButton(onClick = actions::print, modifier = Modifier.testTag(DrawTags.PRINT)) { Text("Print the page") }
        }
        Step(2, "Colour it in") {
            Text(
                "Crayons, felt pens, paint: bright colours show best. Colour right up to the line, and keep the four black squares clean.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Step(3, "Take a photo of it") {
            Text(
                "Lay the page flat in good light, with all four black squares in the picture.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions::takePhoto, enabled = ui.phase != DrawPhase.SCANNING, modifier = Modifier.testTag(DrawTags.CAMERA)) {
                    Text("Take a photo")
                }
                OutlinedButton(onClick = actions::choosePhoto, enabled = ui.phase != DrawPhase.SCANNING, modifier = Modifier.testTag(DrawTags.CHOOSE)) {
                    Text("Choose a photo")
                }
            }
        }
        Text(
            when (ui.inLagoon) {
                0 -> "No drawn fish in the lagoon yet."
                1 -> "1 drawn fish in the lagoon, of ${TankScene.MAX_SKINS} at most."
                else -> "${ui.inLagoon} drawn fish in the lagoon, of ${TankScene.MAX_SKINS} at most."
            },
            style = MaterialTheme.typography.labelLarge,
            color = Ocean.Mist,
        )
    }
}

@Composable
private fun Step(n: Int, title: String, body: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            body()
        }
    }
}

/** The page to print, small. */
@Composable
fun PagePicture(modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .aspectRatio(TemplatePage.WIDTH_MM / TemplatePage.HEIGHT_MM)
            .semantics { contentDescription = "The page: a fish outline between four black squares" },
    ) {
        drawIntoCanvas { TemplateArt.draw(it.nativeCanvas, size.width, size.height) }
    }
}

@Composable
private fun Scanning() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(DrawTags.SCANNING),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Text("Finding your fish…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Result(skin: Skin, ui: DrawUi, actions: DrawActions) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Here is your fish", style = MaterialTheme.typography.titleMedium)
            FishPicture(skin, Modifier.fillMaxWidth().widthIn(max = 420.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = actions::release, enabled = ui.room && ui.phase == DrawPhase.FOUND, modifier = Modifier.testTag(DrawTags.RELEASE)) {
                    Text("Let it swim")
                }
                TextButton(onClick = actions::again, modifier = Modifier.testTag(DrawTags.AGAIN)) { Text("Try another photo") }
            }
        }
    }
}

/** A drawing on lagoon water, as it will swim. */
@Composable
fun FishPicture(skin: Skin, modifier: Modifier = Modifier) {
    val image = remember(skin.id) { skin.toImageBitmap() }
    Box(
        modifier
            .aspectRatio(2.4f)
            .background(Brush.verticalGradient(listOf(Color(0xFF1E8C9A), Color(0xFF0E5C6E))), MaterialTheme.shapes.medium)
            .testTag(DrawTags.PREVIEW),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = "Your fish, cut out of the photo", modifier = Modifier.fillMaxWidth(0.82f).aspectRatio(2f))
        }
    }
}

@Composable
private fun Message(ui: DrawUi) {
    val text = when (ui.phase) {
        DrawPhase.NO_PAGE -> "The four black squares weren't all in the photo. Lay the page flat, step back a little so all four show, and try again."
        DrawPhase.UNREADABLE -> "That photo couldn't be opened. Try taking another."
        DrawPhase.NO_CAMERA -> "There's no camera app to take the photo with: choose a photo of the page instead."
        DrawPhase.RELEASED -> "Your fish is swimming in the lagoon. Draw another?"
        DrawPhase.FULL -> if (!ui.room) "There's no room for another drawn fish: net one out of the lagoon first." else null
        else -> if (!ui.room) "The lagoon holds as many drawn fish as it can. Net one out to make room." else null
    } ?: return
    val error = ui.phase == DrawPhase.NO_PAGE || ui.phase == DrawPhase.UNREADABLE || ui.phase == DrawPhase.FULL || ui.phase == DrawPhase.NO_CAMERA
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(DrawTags.MESSAGE),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/** The drawing as a picture, or null when its data is damaged. */
fun Skin.toImageBitmap(): ImageBitmap? {
    val px = pixels() ?: return null
    val argb = IntArray(Skin.WIDTH * Skin.HEIGHT) { i ->
        val r = px[i * 4].toInt() and 0xFF
        val g = px[i * 4 + 1].toInt() and 0xFF
        val b = px[i * 4 + 2].toInt() and 0xFF
        val a = px[i * 4 + 3].toInt() and 0xFF
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(argb, Skin.WIDTH, Skin.HEIGHT, Bitmap.Config.ARGB_8888).asImageBitmap()
}
