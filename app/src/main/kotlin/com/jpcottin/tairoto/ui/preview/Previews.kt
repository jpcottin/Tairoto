package com.jpcottin.tairoto.ui.preview

import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview

/**
 * Every major form factor, for previews and screenshot tests: a phone, a
 * foldable opened flat, a tablet in landscape and a desktop window.
 */
@Preview(name = "Phone", device = Devices.PHONE, showBackground = true)
@Preview(name = "Foldable", device = Devices.FOLDABLE, showBackground = true)
@Preview(name = "Tablet", device = Devices.TABLET, showBackground = true)
@Preview(name = "Desktop", device = Devices.DESKTOP, showBackground = true)
annotation class FormFactorPreviews

/** A phone and a foldable's narrow cover screen, for things that only matter when narrow. */
@Preview(name = "Phone", device = Devices.PHONE, showBackground = true)
@Preview(name = "Cover screen", widthDp = 360, heightDp = 780, showBackground = true)
annotation class NarrowPreviews
