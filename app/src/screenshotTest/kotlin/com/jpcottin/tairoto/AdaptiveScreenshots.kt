package com.jpcottin.tairoto

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.jpcottin.tairoto.ui.preview.DecorCardsPreviewContent
import com.jpcottin.tairoto.ui.preview.DecorGalleryPreviewContent
import com.jpcottin.tairoto.ui.preview.FormFactorPreviews
import com.jpcottin.tairoto.ui.preview.LagoonArrangingPreviewContent
import com.jpcottin.tairoto.ui.preview.LagoonEditingPreviewContent
import com.jpcottin.tairoto.ui.tank.EditTool
import com.jpcottin.tairoto.ui.preview.LagoonScreenPreviewContent
import com.jpcottin.tairoto.ui.preview.LagoonWithPanePreviewContent
import com.jpcottin.tairoto.ui.preview.NarrowPreviews
import com.jpcottin.tairoto.ui.preview.PreviewData
import com.jpcottin.tairoto.ui.preview.PreviewPane
import com.jpcottin.tairoto.ui.preview.SpeciesCardsPreviewContent
import com.jpcottin.tairoto.ui.preview.SpeciesGalleryPreviewContent
import com.jpcottin.tairoto.ui.theme.TairotoTheme

/*
 * The adaptive layout on every major form factor: a phone, a foldable opened
 * flat, a tablet and a desktop window. The same content as the previews in the
 * IDE (ui/preview), so what is reviewed there is what is checked here.
 */

@PreviewTest
@FormFactorPreviews
@Composable
fun Lagoon() = TairotoTheme { LagoonScreenPreviewContent() }

@PreviewTest
@FormFactorPreviews
@Composable
fun LagoonWithStats() = TairotoTheme { LagoonScreenPreviewContent(PreviewData.lagoonWithStats) }

/** A sheet over the lagoon when narrow, a pane beside it when wide. */
@PreviewTest
@FormFactorPreviews
@Composable
fun LagoonWithSpeciesPicker() = TairotoTheme { LagoonWithPanePreviewContent(PreviewPane.Species) }

/** The settings on their own when narrow, beside the lagoon when wide. */
@PreviewTest
@FormFactorPreviews
@Composable
fun LagoonWithSettings() = TairotoTheme { LagoonWithPanePreviewContent(PreviewPane.Settings) }

@PreviewTest
@NarrowPreviews
@Composable
fun EverySpeciesCard() = TairotoTheme { SpeciesCardsPreviewContent() }

@PreviewTest
@Preview(name = "wide", widthDp = 600, heightDp = 300)
@Preview(name = "narrow", widthDp = 360, heightDp = 420)
@Composable
fun SpeciesGallery() = TairotoTheme { SpeciesGalleryPreviewContent() }

/** The decorations tab of the picker: a sheet when narrow, a pane when wide. */
@PreviewTest
@FormFactorPreviews
@Composable
fun LagoonWithDecorPicker() = TairotoTheme { LagoonWithPanePreviewContent(PreviewPane.Decor) }

/** Arranging, a coral head selected. */
@PreviewTest
@FormFactorPreviews
@Composable
fun LagoonEditing() = TairotoTheme { LagoonEditingPreviewContent() }

/** Arranging with the net in hand. */
@PreviewTest
@NarrowPreviews
@Composable
fun LagoonNetting() = TairotoTheme { LagoonEditingPreviewContent(PreviewData.editing(EditTool.NET, selected = false)) }

@PreviewTest
@NarrowPreviews
@Composable
fun EveryDecorCard() = TairotoTheme { DecorCardsPreviewContent() }

@PreviewTest
@Preview(name = "decor", widthDp = 480, heightDp = 150)
@Composable
fun DecorGallery() = TairotoTheme { DecorGalleryPreviewContent() }

// The lagoon shared with other devices (milestone 5).
@PreviewTest
@FormFactorPreviews
@Composable
fun LagoonWithShareSheet() = TairotoTheme { LagoonWithPanePreviewContent(PreviewPane.Share, share = PreviewData.shareStart) }

@PreviewTest
@FormFactorPreviews
@Composable
fun LagoonSharedOnThreeScreens() = TairotoTheme {
    LagoonWithPanePreviewContent(PreviewPane.Share, PreviewData.lagoon.copy(share = PreviewData.sharedHost), PreviewData.sharedHost)
}

@PreviewTest
@NarrowPreviews
@Composable
fun SharedLagoonOutOfTouch() = TairotoTheme { LagoonWithPanePreviewContent(PreviewPane.Share, share = PreviewData.outOfTouch) }

@PreviewTest
@FormFactorPreviews
@Composable
fun LagoonArranging() = TairotoTheme { LagoonArrangingPreviewContent() }

@PreviewTest
@NarrowPreviews
@Composable
fun SharedTableArranged() = TairotoTheme { LagoonWithPanePreviewContent(PreviewPane.Share, share = PreviewData.sharedArranged) }

@PreviewTest
@NarrowPreviews
@Composable
fun LagoonOfferToArrange() = TairotoTheme {
    LagoonScreenPreviewContent(PreviewData.lagoon.copy(hintVisible = false, share = PreviewData.offerToArrange))
}
