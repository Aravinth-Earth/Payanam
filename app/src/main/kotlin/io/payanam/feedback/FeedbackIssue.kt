//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feedback

data class FeedbackIssue(
    val number: Int,
    val title: String,
    val state: String,
    val createdAt: String,
    val htmlUrl: String,
    val body: String? = null,
    val updatedAt: String? = null,
    val closedAt: String? = null,
)
