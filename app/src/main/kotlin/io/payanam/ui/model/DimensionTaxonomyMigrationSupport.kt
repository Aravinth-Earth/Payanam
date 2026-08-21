//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.model
object DimensionTaxonomyMigrationSupport {
    /**
     * Performs the remap app setting key.
     */
    fun remapAppSettingKey(key: String, canonicalIdRemap: Map<String, String>): String = key
}
