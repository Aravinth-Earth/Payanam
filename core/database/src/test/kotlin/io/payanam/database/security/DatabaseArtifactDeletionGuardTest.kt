//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Enforces [DatabaseFileGuard]'s own documented rule: "Every in-app delete of the production DB file
 * (or its WAL/SHM sidecars) must go through [safeDelete] with an explicit [DatabaseFileGuard.DeleteIntent]
 * so the operation is traceable in logs."
 *
 * WHY A SOURCE SCAN AND NOT A BEHAVIOURAL TEST: the rule is about a call site's SHAPE — which API a
 * deletion goes through — and no amount of running the code observes that. A behavioural test can only
 * see the files one particular flow happened to touch, so the next call site written with `File.delete()`
 * would ship green. The rule lives in a KDoc precisely because the compiler cannot enforce it; this is
 * the check that makes it bind rather than merely be believed.
 *
 * WHY IT IS WORTH A TEST AT ALL: the rule had two live violators inside the app, and both were silent.
 * `AndroidForgottenPassphraseReset.deleteAllDatabaseFiles` deleted a user's entire artifact family
 * through `File.delete()` and discarded the boolean, so a delete that failed was indistinguishable from
 * a file that was never there. `DatabaseEncryptionMigrationSupport.deleteCompanionFiles` discarded the
 * same boolean on the `-wal`/`-shm`/`-journal` family, and a surviving `-wal` beside a migrated main
 * file is how a migration the app reported as complete leaves the old rows readable. Both now go through
 * the guard; this test is what keeps them that way, and what names the next one.
 *
 * SCOPE, AND WHY IT IS NARROWER THAN "NO RAW DELETE ANYWHERE": the rule is about the production database
 * file and its sidecars. A `.bak` snapshot, a temp-backup directory or a backup the janitor prunes is not
 * the live database, and deleting one of those is out of scope by the KDoc's own wording. So the check
 * is scoped to the REGION that iterates the shared artifact family — a lambda whose receiver came from
 * [DatabaseArtifacts.filesFor] or [DatabaseArtifacts.sidecarFilesFor], directly or through a name bound
 * from it — rather than to a whole file. A
 * file that legitimately deletes a backup elsewhere is not a finding, and a guard that reported it would
 * be a guard that got switched off.
 *
 * AND WHY IT COVERS THE MODULES THAT CAN REACH THE GUARD, NOT EVERYONE: [DatabaseFileGuard] lives in
 * `:core:database`, and only modules depending on it can call [safeDelete]. `:core:presentation` — which
 * has a fourth live violator, `DatabasePassphraseSetupViewModel.deleteAllDatabaseFiles` — does NOT
 * depend on `:core:database`, so routing it through the guard would be a module-graph change rather than
 * a local edit. It is therefore named in [KNOWN_UNREACHABLE] instead of being silently passed over, and
 * the guard is scoped to the three modules that do depend on `:core:database`: `:app`, `:core:database`
 * and `:core:database-model`. That is a stated limit, not a claim of full coverage — the desktop's own
 * `DesktopForgottenPassphraseReset` logs a failed delete explicitly and so honours the rule's INTENT
 * without importing an Android-only object.
 *
 * ITS OTHER LIMITS, stated rather than implied. It is a TEXT check: a caller that deletes a database file
 * through a name assembled in another file, or through a reflective or NIO path, would not be caught. So
 * this is a tripwire for the defect shape that actually occurred, not a proof that no unguarded deletion
 * exists anywhere. Comments and KDoc are stripped before matching, because this file's own prose and
 * every comment explaining WHY the guard exists contains the string `.delete()` — matching those would
 * turn the documentation of the rule into a violation of it.
 */
class DatabaseArtifactDeletionGuardTest {
    /**
     * Production files allowed to delete a member of the artifact family WITHOUT the guard, each with
     * its reason.
     *
     * Empty is the goal, so every entry would be a finding someone accepted rather than a default. Read
     * this list before adding to it: an entry is a claim that this particular deletion does not need to be
     * traceable, and that claim has to be true for a user rather than for the test to go green.
     */
    private val allowedUnguarded = mapOf<String, String>()

    /**
     * Artifact-family deleters this guard does NOT cover, each with the reason it cannot.
     *
     * Named rather than left to a reader's imagination, because an uncovered call site that is not
     * written down is indistinguishable from one nobody has found yet. Adding a path here is a claim
     * that the guard cannot be applied to it — read the reason before believing that.
     */
    private val knownUnreachable =
        mapOf(
            // Deletes the whole artifact family with `File.delete()`, discarding the boolean. Cannot call
            // the guard: `:core:presentation` does not depend on `:core:database`, and adding that edge
            // for one call site is a module-graph decision rather than a local repair.
            "core/presentation/src/main/kotlin/io/payanam/presentation/database/" +
                "DatabasePassphraseSetupViewModel.kt" to
                "module does not depend on :core:database",
            // Its own implementation, and it already logs a failed delete explicitly rather than
            // discarding it, so the rule's intent is honoured without an Android-only import.
            "desktop/src/main/kotlin/io/payanam/desktop/security/DesktopForgottenPassphraseReset.kt" to
                "desktop platform, own logger; failure is already reported, not discarded",
        )

    /**
     * Every path in [knownUnreachable] still deletes an artifact family unguarded.
     *
     * The opposite direction from the allow-list: an entry is a KNOWN GAP, so if its caller is ever fixed
     * this fails and the gap has to be removed rather than left as a stale excuse.
     */
    @Test
    fun everyKnownUnreachableGap_stillExists() {
        knownUnreachable.forEach { (path, reason) ->
            assertThat(reason).isNotEmpty()
            assertThat(File(repositoryRoot, path).exists()).isTrue()
            assertThat(violationsIn(stripComments(File(repositoryRoot, path).readText()), path)).isNotEmpty()
        }
    }

    @Test
    fun everyProductionDatabaseArtifactDelete_goesThroughTheGuard() {
        val sources = productionSources()
        assertThat(sources).isNotEmpty()

        val unguarded = mutableListOf<String>()
        for (source in sources) {
            val relative = source.relativeTo(repositoryRoot).path
            unguarded.addAll(violationsIn(stripComments(source.readText()), relative))
        }
        assertThat(unguarded).isEmpty()
    }

    /**
     * The check finds the regions it is meant to find, so it cannot go green by scanning nothing.
     *
     * Without this a regex that stopped matching the artifact family would leave the scan vacuously
     * empty — the failure mode a source-shape guard is most exposed to.
     */
    @Test
    fun theScan_findsTheArtifactFamilyRegions_itExistsToCheck() {
        val found = mutableListOf<String>()
        for (source in productionSources()) {
            val relative = source.relativeTo(repositoryRoot).path
            found.addAll(artifactFamilyRegions(stripComments(source.readText()), relative).map { it.path })
        }
        // Both known sites plus every legitimate artifact-family deleter, so the count cannot silently
        // fall to zero as the codebase is cleaned up.
        assertThat(found).containsAtLeast(RESET_SOURCE, MIGRATION_SOURCE)
    }

    /**
     * Every allow-listed exception is still needed, so an entry left behind after its caller was fixed
     * cannot quietly become permanent permission.
     */
    @Test
    fun everyAllowedUnguardedException_stillHasAViolation() {
        allowedUnguarded.forEach { (path, reason) ->
            val source = File(repositoryRoot, path)
            assertThat(reason).isNotEmpty()
            assertThat(source.exists()).isTrue()
            assertThat(violationsIn(stripComments(source.readText()), path)).isNotEmpty()
        }
    }

    /**
     * The two violations this check was written for, asserted against the shipped functions directly.
     *
     * Asserted per FUNCTION rather than per file, because both files legitimately delete other things —
     * backup snapshots, temp copies — that this rule does not govern. Scoping to the whole file would
     * have made this test fail for the wrong reason the moment either file grew an unrelated cleanup.
     */
    @Test
    fun theTwoKnownViolators_deleteTheArtifactFamilyThroughTheGuard() {
        val reset = functionBody(File(repositoryRoot, RESET_SOURCE).path, RESET_FUNCTION)
        val migration = functionBody(File(repositoryRoot, MIGRATION_SOURCE).path, MIGRATION_FUNCTION)

        assertThat(reset).isNotEmpty()
        assertThat(migration).isNotEmpty()
        assertThat(reset).contains("DatabaseFileGuard.safeDelete")
        assertThat(reset).contains("FORGOT_PASSPHRASE_RESET")
        assertThat(migration).contains("DatabaseFileGuard.safeDelete")
        assertThat(reset).doesNotContain(".delete()")
        assertThat(migration).doesNotContain(".delete()")
    }

    /**
     * The [DatabaseFileGuard.DeleteIntent] values the rule requires, pinned by name and order.
     *
     * Pinned by value because the enum IS the log vocabulary: the guard's whole promise is that the
     * session log says which of these things happened, and a renamed value silently rewrites the meaning
     * of every older session log written against the old one.
     */
    @Test
    fun theDeleteIntents_arePinnedByName() {
        assertThat(DatabaseFileGuard.DeleteIntent.entries.map { it.name })
            .containsExactly(
                "USER_DELETE_ALL",
                "IMPORT_REPLACE",
                "ADMIN_ARTIFACT_CLEANUP",
                "JANITOR_CLEANUP",
                "FORGOT_PASSPHRASE_RESET",
            )
            .inOrder()
    }

    /**
     * Every [DatabaseFileGuard.DeleteIntent] value is actually used in production.
     *
     * The enum is the guard's whole output: the promise the KDoc makes is that a session log says WHICH
     * thing happened. A value no production code can pass is a promise the log cannot keep — and it is
     * the shape by which a real defect stays invisible rather than being caught. Measured: `IMPORT_REPLACE`
     * had zero call sites anywhere in the tree while `AndroidDatabaseTransferGateway.importFrom` deleted the
     * live database with a discarded `File.delete()` boolean — an import replacing a user's database
     * without a trace, in a module this same class claims to cover. The family-region scan missed it
     * because that delete names one file rather than iterating `DatabaseArtifacts`, so nothing about the
     * region shape said "live database". This check is the other half of the coverage: the scan finds
     * unguarded deletes, and this finds an intent that exists only because someone believed a path was
     * guarded.
     *
     * [GUARD_SOURCE] IS EXCLUDED, and that exclusion is the entire difficulty: the enum's own declaration
     * names all five values, so counting it would make this test green for every intent no matter how many
     * had zero callers — the vacuous-scan failure mode. A value only counts when a file OTHER than the
     * guard's own declaration passes it to `safeDelete`, and a file counts only when the text is real code
     * rather than a comment quoting the rule.
     */
    @Test
    fun everyDeleteIntent_isUsedBySomeProductionCaller() {
        val callers = productionSources()
            .filter { it.absolutePath != File(repositoryRoot, GUARD_SOURCE).absolutePath }
            .flatMap { source -> source.readText().let { stripComments(it) }.lineSequence().toList() }
        assertThat(callers).isNotEmpty()

        val unused =
            DatabaseFileGuard.DeleteIntent.entries
                .filter { intent -> callers.none { line -> line.contains("DeleteIntent.${intent.name}") } }
                .map { it.name }
        assertThat(unused).isEmpty()
    }

    /**
     * The intent-coverage check can go green by looking at the wrong text, so it is proved on a real call.
     *
     * Two ways it could pass without meaning anything: a scan pointed at a directory that holds no sources
     * (so `callers` is empty and every intent looks unused — or, worse, a filter that matches everything and
     * finds the enum's own declaration), and one that reads comments. This asserts the positive case — the
     * intent this check was added for is found in the gateway's real code — and the negative case, that the
     * guard's own declaration is not a caller.
     */
    @Test
    fun theIntentCoverageCheck_readsRealCalls_notTheDeclarationOrProse() {
        val guardText = stripComments(File(repositoryRoot, GUARD_SOURCE).readText())
        val gatewayText =
            stripComments(File(repositoryRoot, TRANSFER_GATEWAY_SOURCE).readText())

        assertThat(guardText).contains("IMPORT_REPLACE")
        assertThat(gatewayText).contains("DeleteIntent.IMPORT_REPLACE")
        assertThat(guardText.lines().filter { it.contains("DeleteIntent.IMPORT_REPLACE") }).isEmpty()
    }

    /**
     * The import-replace swap deletes the live database through the guard, and refuses when it cannot.
     *
     * Asserted per FUNCTION because the same class legitimately deletes other things — a staged `.incoming`
     * file — that this rule does not govern. The behavioural half is in the gateway's own test suite; this
     * pins that the production swap carries the intent, since a scan of region shape cannot see a
     * single-file delete at all.
     */
    @Test
    fun theImportReplaceSwap_deletesTheLiveDatabaseThroughTheGuard() {
        val swap = functionBody(File(repositoryRoot, TRANSFER_GATEWAY_SOURCE).path, IMPORT_FUNCTION)

        assertThat(swap).isNotEmpty()
        assertThat(swap).contains("DatabaseFileGuard.safeDelete")
        assertThat(swap).contains("IMPORT_REPLACE")
        assertThat(swap).doesNotContain("live.delete()")
    }

    /**
     * The deletion lines inside [source]'s artifact-family regions that bypass the guard, each reported
     * with the path and line the reader would find it on.
     */
    private fun violationsIn(source: String, relativePath: String): List<String> {
        if (allowedUnguarded.containsKey(relativePath)) {
            return emptyList()
        }
        return artifactFamilyRegions(source, relativePath)
            .flatMap { region ->
                region.body.lineSequence()
                    .mapIndexed { offset, line -> (region.startLine + offset) to line }
                    .filter { (_, line) -> DELETES.containsMatchIn(line) }
                    .filter { (_, line) -> !line.contains("DatabaseFileGuard.safeDelete") }
                    .map { (number, line) -> "$relativePath:$number: ${line.trim()}" }
                    .toList()
            }
    }

    /**
     * Every region of [source] that iterates the shared artifact family: the body of the lambda or loop
     * whose receiver came from [DatabaseArtifacts.filesFor] or [DatabaseArtifacts.sidecarFilesFor] —
     * either directly, or through a name bound from it.
     *
     * This is what makes the guard match the real defect rather than every delete in the file. The known
     * violators are exactly this shape — iterate the family, then delete each member — so the shape is
     * where the rule is enforced.
     *
     * THE BINDING CASE IS NOT OPTIONAL: matching only a lambda taken straight off the call reads the WRONG
     * lambda as soon as the chain carries one in front of the iteration. Measured on the desktop's
     * forgot-passphrase reset, which read `val present = DatabaseArtifacts.filesFor(f).filter { ... }` and
     * then `present.forEach { Files.deleteIfExists(it) }`: this check took the `filter` lambda, which
     * deletes nothing, and reported the file clean while it removed a user's whole artifact family outside
     * the guard. The gap was still real; the scan had merely stopped being able to see it, and the
     * known-gap assertion below is what surfaced it.
     */
    private fun artifactFamilyRegions(source: String, relativePath: String): List<Region> {
        val regions = linkedMapOf<Int, Region>()
        for (match in FAMILY_ITERATION.findAll(source)) {
            val body = bracedBodyAt(source, match.range.last) ?: continue
            val startLine = lineNumberAt(source, match.range.last)
            regions.putIfAbsent(startLine, Region(relativePath, startLine, body))
        }
        for (binding in FAMILY_BINDING.findAll(source)) {
            val name = binding.groupValues[1]
            for (use in familyIterationOver(name).findAll(source)) {
                val body = bracedBodyAt(source, use.range.last) ?: continue
                val startLine = lineNumberAt(source, use.range.last)
                regions.putIfAbsent(startLine, Region(relativePath, startLine, body))
            }
        }
        return regions.values.toList()
    }

    /** The 1-based line [offset] falls on in [source]. */
    private fun lineNumberAt(source: String, offset: Int): Int = source.substring(0, offset).count { it == '\n' } + 1

    /**
     * The text inside the braces starting at or after [braceStart], or null when they do not balance.
     *
     * Null rather than a best guess: an unbalanced result means the shape was not what this check
     * understands, and reporting it as "no violation" would be the one failure mode a guard must not
     * have. Such a region is skipped by the caller only because [theScan_findsTheArtifactFamilyRegions_itExistsToCheck]
     * independently proves the regions are still being found.
     */
    private fun bracedBodyAt(source: String, braceStart: Int): String? {
        val open = source.indexOf('{', braceStart)
        if (open < 0) return null
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        return null
    }

    /**
     * The body of the named top-level function in [path], by brace matching from its signature.
     *
     * Used only where the declaration is unambiguous — a private function named once in its file — so
     * that this test's own assertions read like the rule rather than like text matching.
     */
    private fun functionBody(path: String, functionName: String): String {
        val source = stripComments(File(path).readText())
        val declaration = Regex("""fun\s+$functionName\s*\(""").find(source)
            ?: error("Could not find $functionName in $path")
        return bracedBodyAt(source, declaration.range.last).orEmpty()
    }

    /**
     * Every Android production Kotlin source in a module that DEPENDS ON `:core:database`, which is what
     * makes it able to call [DatabaseFileGuard.safeDelete] at all.
     *
     * Scoped to those modules because that is the set the rule can be satisfied within — a module with
     * no edge to the guard cannot be asked to use it. The list is explicit so a new module is a deliberate
     * decision rather than an accident of directory layout, and so the walk below cannot drift into the
     * desktop or `build` copies (spotless keeps a rewritten copy of every source, and scanning a copy
     * could let a green result come from a stale file rather than the source). `core/database-jvm` is
     * absent on purpose: it opens and reads databases rather than deleting them.
     */
    private val guardedModuleRoots =
        listOf("app", "core/database", "core/database-model")

    private fun productionSources(): List<File> {
        val found = mutableListOf<File>()
        for (moduleRoot in guardedModuleRoots) {
            val sourceDir = File(repositoryRoot, moduleRoot + File.separator + "src" + File.separator + "main")
            if (!sourceDir.isDirectory) continue
            sourceDir.walkTopDown()
                .onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" }
                .forEach { found.add(it) }
        }
        return found
    }

    /**
     * The repository root: the nearest ancestor of the working directory holding `settings.gradle.kts`.
     *
     * Discovered rather than hardcoded, because a scan pointed at the wrong directory passes vacuously:
     * it finds no sources and reports nothing. A check that can be green by looking in the wrong place
     * is not a check.
     */
    private val repositoryRoot: File by lazy {
        var candidate = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error(
                "Could not find settings.gradle.kts above ${System.getProperty("user.dir")}",
            )
        }
        candidate
    }

    /**
     * Replaces comment text with spaces, keeping every line's length and numbering.
     *
     * Whitespace rather than removal, so a violation is still reported at the line the reader would find
     * it on. Block comments are handled first, and the line-comment rule stops at end-of-line so a `//`
     * inside a string literal cannot truncate real code after it.
     */
    private fun stripComments(source: String): String =
        source
            .replace(BLOCK_COMMENT) { match -> match.value.blankOutKeepingNewlines() }
            .replace(LINE_COMMENT) { match -> match.value.blankOutKeepingNewlines() }

    /**
     * Replaces [this] with spaces, keeping every newline so line numbers do not move.
     *
     * The newline preservation is the whole reason this is a function and not `replace(regex, " ")`: a
     * block comment replaced by a single space collapses the lines it spanned, so every violation
     * after it would be reported at a line number the reader cannot find it on.
     */
    private fun String.blankOutKeepingNewlines(): String =
        map { if (it == '\n') '\n' else ' ' }.joinToString("")

    /**
     * The ways this codebase deletes a file. [DatabaseFileGuard.safeDelete] does NOT match: the guard's
     * own name ends in `safeDelete(`, and these patterns require a literal dot before the delete word.
     */
    private val DELETES = Regex("""\.delete\s*\(|\.deleteRecursively\s*\(|Files\.deleteIfExists\s*\(""")

    /** An iteration over the artifact family: the family builder, then a lambda or loop over it. */
    private val FAMILY_ITERATION =
        Regex("""DatabaseArtifacts\s*\.\s*(?:sidecar)?[Ff]ilesFor\s*\(""")

    /**
     * A `val`/`var` the artifact family is bound to: `val present = DatabaseArtifacts.filesFor(f)`.
     *
     * The binding is how the family escapes the expression that built it, and a delete over that name is
     * still a delete of the live database. Same-line only: every family binding in this codebase is
     * single-line today, and a multi-line one would silently reopen the blind spot this exists to close,
     * so the limit is stated here rather than left implied.
     */
    private val FAMILY_BINDING =
        Regex(
            """\b(?:val|var)\s+(\w+)\s*(?::[^=\n]*)?=\s*[^\n]*DatabaseArtifacts\s*\.\s*(?:sidecar)?[Ff]ilesFor""",
        )

    /**
     * A lambda over a name that holds the family, e.g. `present.forEach { file -> ... }`.
     *
     * The iterating calls are named rather than inferred, so an unrelated `.size` or `.first()` on the same
     * name cannot manufacture a region whose body has nothing to scan.
     */
    private fun familyIterationOver(name: String): Regex =
        Regex(
            """\b${Regex.escape(name)}\s*\.\s*(?:forEach|forEachIndexed|onEach|map|filter|filterNot|any|all|none)\s*\{""",
        )

    /** One region of a file that iterates the artifact family. */
    private data class Region(
        val path: String,
        val startLine: Int,
        val body: String,
    )

    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("""//[^\n]*""")

    private companion object {
        /** The adapter that deleted a user's whole artifact family without the guard. */
        const val RESET_SOURCE =
            "core/database/src/main/kotlin/io/payanam/database/security/AndroidForgottenPassphraseReset.kt"

        /** The migration step that removed the sidecar family without the guard. */
        const val MIGRATION_SOURCE =
            "core/database/src/main/kotlin/io/payanam/database/security/DatabaseEncryptionMigrationSupport.kt"

        const val RESET_FUNCTION = "deleteAllDatabaseFiles"
        const val MIGRATION_FUNCTION = "deleteCompanionFiles"

        /** The gateway that swapped the live database in with a raw, unchecked delete. */
        const val TRANSFER_GATEWAY_SOURCE =
            "core/database/src/main/kotlin/io/payanam/database/transfer/AndroidDatabaseTransferGateway.kt"

        const val IMPORT_FUNCTION = "importFrom"

        /** The guard itself, excluded from the caller scan because it declares every intent. */
        const val GUARD_SOURCE = "core/shared/src/main/kotlin/io/payanam/database/security/DatabaseFileGuard.kt"
    }
}
