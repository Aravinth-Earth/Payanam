//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Android's side of the seam: publishes every `testTag` in this subtree as a view resource id, which
 * is what UIAutomator and Espresso match against (see `payanam-e2e-testing`).
 */
actual fun Modifier.exposeTestTagsAsResourceIds(): Modifier = semantics { testTagsAsResourceId = true }
