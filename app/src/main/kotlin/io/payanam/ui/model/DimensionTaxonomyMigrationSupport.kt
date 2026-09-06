//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.model

/**
 * Migration-time shims for the dimension taxonomy switchover.
 */
object DimensionTaxonomyMigrationSupport {
    /**
     * App-setting keys are not dimension-scoped, so remapping is currently a
     * pass-through (kept as a seam for future key migrations).
     */
    fun remapAppSettingKey(key: String, canonicalIdRemap: Map<String, String>): String = key
}
