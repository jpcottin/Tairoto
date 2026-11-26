package com.jpcottin.tairoto

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.jpcottin.tairoto.ui.preview.FormFactorPreviews
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
