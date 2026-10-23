//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import androidx.compose.ui.Modifier

/**
 * Surfaces the test tags of this subtree as the platform's own test-resource ids.
 *
 * On Android a `testTag` lives only inside Compose, so a UI test driving the app through UIAutomator
 * or Espresso cannot address a node by it; this is what publishes the tag as a view resource id.
 * Desktop has no such indirection — a Compose test reads the tag directly — so there the modifier is
 * the identity.
 *
 * A seam rather than an inline call because `testTagsAsResourceId` exists only in the Android
 * artifact of Compose UI, so a shared component that called it directly would not compile for
 * desktop.
 */
expect fun Modifier.exposeTestTagsAsResourceIds(): Modifier
