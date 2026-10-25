//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins the rule that nothing the app stores may travel through Android cloud backup or
 * device-to-device transfer.
 *
 * The rule is stated in two files, because the platform reads a different one before and
 * after Android 12, and it is stated per domain per section — nine domains in three places.
 * That is a lot of lines to lose one of to a future edit, and the loss is silent: the app
 * still runs, the manifest still looks careful, and only the storage that was quietly
 * dropped from the list starts leaving the device.
 *
 * Every assertion here is about the DECLARATION, not about behaviour a device could still
 * override; it fails when a declaration regresses, which is the moment this can be caught.
 *
 * The inputs are files checked into this repository, so the parser sees no untrusted
 * content.
 */
class AndroidBackupDomainExclusionRulesTest {

    /**
     * The nine backup domains the platform offers. A domain missing from a rules file is a
     * hole, so this list is the whole policy and a new domain is one edit away.
     */
    private val backupDomains = listOf(
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref",
    )

    @Test
    fun `data extraction rules exclude every backup domain from cloud backup and device transfer`() {
        val document = parse(DATA_EXTRACTION_RULES)
        assertEquals(
            "root element of $DATA_EXTRACTION_RULES",
            "data-extraction-rules",
            document.documentElement.tagName,
        )

        val cloudBackup = onlySection(document, "cloud-backup", DATA_EXTRACTION_RULES)
        val deviceTransfer = onlySection(document, "device-transfer", DATA_EXTRACTION_RULES)

        assertExcludesEveryBackupDomain(cloudBackup, "cloud-backup")
        assertExcludesEveryBackupDomain(deviceTransfer, "device-transfer")
    }

    @Test
    fun `legacy backup rules exclude every backup domain`() {
        val document = parse(BACKUP_RULES)
        assertEquals(
            "root element of $BACKUP_RULES",
            "full-backup-content",
            document.documentElement.tagName,
        )

        assertExcludesEveryBackupDomain(document.documentElement, "full-backup-content")
    }

    @Test
    fun `no include element exists in either rules file`() {
        listOf(DATA_EXTRACTION_RULES, BACKUP_RULES).forEach { relativePath ->
            val document = parse(relativePath)
            assertTrue(
                "$relativePath must contain no <include> element: an <include> inverts the policy to " +
                    "\"back up only these files\" and silently re-enables every domain it does not name, " +
                    "which is the opposite of what these files say they do",
                document.getElementsByTagName("include").length == 0,
            )
        }
    }

    @Test
    fun `manifest disables backup and references both rule files`() {
        val document = parse(MANIFEST)
        val applications = document.getElementsByTagName("application")
        assertTrue(
            "$MANIFEST must declare exactly one <application> element",
            applications.length == 1,
        )
        val application = applications.item(0) as Element

        assertEquals(
            "$MANIFEST must keep android:allowBackup=\"false\"",
            "false",
            application.getAttribute("android:allowBackup"),
        )
        assertEquals(
            "$MANIFEST must point android:dataExtractionRules at @xml/data_extraction_rules",
            "@xml/data_extraction_rules",
            application.getAttribute("android:dataExtractionRules"),
        )
        assertEquals(
            "$MANIFEST must point android:fullBackupContent at @xml/backup_rules",
            "@xml/backup_rules",
            application.getAttribute("android:fullBackupContent"),
        )
    }

    /**
     * The domains [section] excludes, asserting on the way that each exclusion covers its
     * whole domain: a narrower `path` would let part of the domain through while the domain
     * still appeared to be listed.
     */
    private fun assertExcludesEveryBackupDomain(
        section: Element,
        sectionLabel: String,
    ) {
        val excluded = mutableSetOf<String>()
        val excludes = section.getElementsByTagName("exclude")
        for (index in 0 until excludes.length) {
            val exclude = excludes.item(index) as Element
            val domain = exclude.getAttribute("domain")
            assertEquals(
                "<exclude domain=\"$domain\"> in $sectionLabel must cover the whole domain with path=\".\"",
                ".",
                exclude.getAttribute("path"),
            )
            excluded += domain
        }

        val missing = backupDomains.filterNot(excluded::contains)
        assertTrue(
            "$sectionLabel must exclude all ${backupDomains.size} backup domains; " +
                "missing: $missing (a domain that is not excluded is backed up and transferred)",
            missing.isEmpty(),
        )
    }

    private fun onlySection(
        document: Document,
        sectionName: String,
        relativePath: String,
    ): Element {
        val sections = document.getElementsByTagName(sectionName)
        assertTrue(
            "$relativePath must contain exactly one <$sectionName> section, found ${sections.length}",
            sections.length == 1,
        )
        return sections.item(0) as Element
    }

    private fun parse(relativePath: String): Document {
        val file = resolveRepoRoot().resolve(relativePath)
        require(Files.isRegularFile(file)) {
            "Expected $relativePath at $file, resolved from the repository root " +
                "(found by walking up from ${Path.of("").toAbsolutePath()}). " +
                "This test fails rather than skips: a missing rules file means the exclusion " +
                "policy cannot be verified."
        }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
    }

    private fun resolveRepoRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath()
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent
        }
        error("Could not resolve the repository root from ${Path.of("").toAbsolutePath()}")
    }

    private companion object {
        const val DATA_EXTRACTION_RULES = "app/src/main/res/xml/data_extraction_rules.xml"
        const val BACKUP_RULES = "app/src/main/res/xml/backup_rules.xml"
        const val MANIFEST = "app/src/main/AndroidManifest.xml"
    }
}
