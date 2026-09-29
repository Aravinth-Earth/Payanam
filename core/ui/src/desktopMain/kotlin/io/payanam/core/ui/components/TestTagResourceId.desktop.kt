//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import androidx.compose.ui.Modifier

/**
 * Desktop's side of the seam: the identity.
 *
 * There is no resource-id indirection to publish a tag into — a Compose desktop test asserts on the
 * node's tag directly — so wrapping the subtree in anything here would add a semantics node for no
 * reader. Windows and Linux are the same case, so one `actual` covers both.
 */
actual fun Modifier.exposeTestTagsAsResourceIds(): Modifier = this
