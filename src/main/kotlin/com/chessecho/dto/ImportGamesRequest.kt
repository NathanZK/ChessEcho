package com.chessecho.dto

import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.TimeControl
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.YearMonth
import java.util.UUID

/**
 * The import request deliberately supports two disjoint selector forms:
 *
 *  * an authenticated request selects an already-associated account by UUID;
 *  * a guest request selects an unclaimed account by the normalized provider
 *    platform and username pair.
 *
 * Platform and username are nullable because they are optional snapshots in the
 * account form. They are validated as a pair only when [accountId] is absent.
 */
data class ImportGamesRequest(
    val accountId: UUID? = null,
    val platform: Platform? = null,
    val username: String = "",
    @field:NotEmpty(message = "at least one timeControl is required")
    val timeControls: List<TimeControl> = emptyList(),
    @field:NotNull(message = "playerColor must not be null")
    val playerColor: PlayerColor? = PlayerColor.BOTH,
    @field:Pattern(
        regexp = "\\d{4}-(0[1-9]|1[0-2])",
        message = "fromDate must be in YYYY-MM format",
    )
    val fromDate: String? = null,
    @field:Pattern(
        regexp = "\\d{4}-(0[1-9]|1[0-2])",
        message = "toDate must be in YYYY-MM format",
    )
    val toDate: String? = null,
) {
    /**
     * Bean validation runs before the controller can inspect the request. This
     * check enforces selector cardinality without treating optional account
     * snapshots as a second selector.
     */
    @AssertTrue(message = "an accountId or both platform and username are required")
    fun hasSelector(): Boolean =
        if (accountId != null) {
            true
        } else {
            platform != null && !username.isNullOrBlank()
        }

    @AssertTrue(message = "fromDate must not be after toDate")
    fun hasOrderedDateRange(): Boolean {
        if (fromDate == null || toDate == null) return true
        return runCatching { YearMonth.parse(fromDate) <= YearMonth.parse(toDate) }.getOrDefault(false)
    }

    fun normalizedUsername(): String? = username.trim().takeIf { it.isNotEmpty() }

    fun normalizedPlatform(): String? = platform?.name?.trim()?.uppercase()

    fun canonicalTimeControls(): String =
        timeControls
            .distinct()
            .sortedBy { it.name }
            .joinToString(",") { it.name }

    fun normalizedFromDate(): String? = fromDate?.trim()

    fun normalizedToDate(): String? = toDate?.trim()

    fun isAuthenticatedForm(): Boolean = accountId != null
}
