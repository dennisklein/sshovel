// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import com.github.dennisklein.sshovel.core.mobile.Mobile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One validation finding from core/config (ARCHITECTURE §8, "Validation issues"), e.g.
 * `{"field":"routes[1]","code":"ROUTE_OVERLAP","severity":"warning","suggestion":"10.0.0.0/8"}`.
 */
@Serializable
data class ValidationIssue(
    /** JSON path in the profile, e.g. "tun.cidr" or "routes[1]". */
    val field: String,
    val code: String,
    /** [ERROR] blocks saving and connecting; [WARNING] doesn't. */
    val severity: String,
    val suggestion: String? = null,
) {
    val isError: Boolean get() = severity == ERROR

    companion object {
        const val ERROR = "error"
        const val WARNING = "warning"
    }
}

/** Saving refused: the profile has validation errors ([issues] also lists warnings). */
class ProfileInvalidException(val issues: List<ValidationIssue>) :
    IllegalArgumentException("invalid profile: " + issues.filter { it.isError }.joinToString { "${it.field}=${it.code}" })

/** Checks a profile against the rules in core/config, the single source of truth (ARCHITECTURE §3). */
fun interface ProfileValidator {
    fun validate(profile: Profile): List<ValidationIssue>
}

/** [ProfileValidator] backed by the Go core's ValidateConfig. */
class GoProfileValidator : ProfileValidator {
    override fun validate(profile: Profile): List<ValidationIssue> = parse(Mobile.validateConfig(profile.toJson()))

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** ValidateConfig returns "" for a valid profile, else a JSON list of issues. */
        fun parse(result: String): List<ValidationIssue> =
            if (result.isBlank()) emptyList() else json.decodeFromString(ListSerializer(ValidationIssue.serializer()), result)
    }
}
