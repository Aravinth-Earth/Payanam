//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [Report.succeeded], the one predicate that decides whether a user is told their data was destroyed.
 *
 * WHY THIS IS ITS OWN FILE AND NOT A CASE INSIDE A PLATFORM SUITE: the roll-up lives in the shared
 * contract, both platforms read it, and neither platform's suite could fail if the rule were wrong in a way
 * only the OTHER platform produced. Asserting it here means a change to the predicate is caught at the
 * module that owns it, on the JVM, without standing up an Android context or a desktop file system.
 *
 * THE DEFECT THIS SHAPE EXISTS TO CATCH, because the rule was not enforced anywhere: `succeeded` was
 * derived from `credentialCleared` alone while its own KDoc said "Both halves completed". The credential
 * clears reliably on both platforms — it is an in-memory map or a JSON file the process owns — so a
 * delete that removed SOME of the artifact family and failed on one member still reported success. The
 * surviving member is the dangerous one: a `-wal` beside a wiped main file is how a database the user was
 * told is gone hands its rows back.
 *
 * WHY THE FIX NEEDS A COUNT OF ATTEMPTS rather than a flag: an implementation cannot tell "this member was
 * never there" from "this member is still here and I failed to delete it" unless it counted the family
 * before it started. Both are `artifactsDeleted == artifactsAttempted`, and both are a success — the absent
 * member is a fresh install, not a failure — which is exactly why the counts cannot be compared to a
 * constant.
 */
class ForgottenPassphraseResetReportTest {
    @Test
    fun `a complete delete with a cleared credential succeeded`() {
        val report = Report(artifactsDeleted = 4, artifactsAttempted = 4, credentialCleared = true)

        assertThat(report.succeeded).isTrue()
    }

    @Test
    fun `a fresh install that deleted nothing is a success, not a failure`() {
        // Zero of zero: nothing existed to destroy, and the credential cleared. Reading this as a failure
        // would make every first-run reset on a new machine report "reset failed" — the reason the
        // comparison is `>=` between two counts rather than a truthiness check on a non-zero delete.
        val report = Report(artifactsDeleted = 0, artifactsAttempted = 0, credentialCleared = true)

        assertThat(report.succeeded).isTrue()
    }

    @Test
    fun `a partial delete is NOT success even though the credential cleared`() {
        // The load-bearing case, and the one the old single-flag roll-up could not express: three of four
        // members are gone, the credential is clean, and one member of the user's own database is still on
        // disk. Reporting success here is what tells a user their data was destroyed when it was not.
        val report = Report(artifactsDeleted = 3, artifactsAttempted = 4, credentialCleared = true)

        assertThat(report.succeeded).isFalse()
        // The two halves are still reported separately, which is the contract's other promise: the caller
        // is owed the difference, so a refusal here is informative rather than a bare failure.
        assertThat(report.credentialCleared).isTrue()
        assertThat(report.artifactsDeleted).isEqualTo(3)
    }

    @Test
    fun `a cleared credential over a full delete is refused when nothing was deleted`() {
        // Direction two, internally consistent: the credential half is the only thing that can make this
        // true today, so the credential-true variant above is the one that proves the guard is not vacuous.
        // Here the attempt count exceeds the success count with the credential clean — a platform that
        // enumerated more than it removed, whatever the reason.
        val report = Report(artifactsDeleted = 2, artifactsAttempted = 5, credentialCleared = true)

        assertThat(report.succeeded).isFalse()
    }

    @Test
    fun `a failed credential is a failure however clean the delete was`() {
        // The other half of the promise, kept: the roll-up must not become "delete-only" either, or a user
        // holding a working credential for a database that no longer exists is told the reset worked.
        val report = Report(artifactsDeleted = 4, artifactsAttempted = 4, credentialCleared = false)

        assertThat(report.succeeded).isFalse()
    }

    @Test
    fun `a fresh install with a failed credential is still a failure`() {
        // Zero of zero AND a credential that would not clear: nothing to delete was not the problem, so this
        // is the case that separates "delete half failed" from "credential half failed" for a caller.
        val report = Report(artifactsDeleted = 0, artifactsAttempted = 0, credentialCleared = false)

        assertThat(report.succeeded).isFalse()
    }
}
