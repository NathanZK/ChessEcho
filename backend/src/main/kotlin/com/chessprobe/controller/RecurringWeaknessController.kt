package com.chessprobe.controller

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.Platform
import com.chessprobe.dto.ClusterMemberPositionsResponse
import com.chessprobe.dto.RecurringDiscoveryJobResponse
import com.chessprobe.dto.RecurringDiscoveryRequest
import com.chessprobe.dto.RecurringWeaknessResponse
import com.chessprobe.service.AccountOwnershipService
import com.chessprobe.service.AccountSelectionRequiredException
import com.chessprobe.service.auth.AuthenticatedPrincipal
import com.chessprobe.service.weakness.RecurringWeaknessDiscoveryService
import com.chessprobe.web.OptionalAuthenticatedPrincipal
import com.chessprobe.web.SessionAuthenticationFilter
import com.chessprobe.web.UnauthenticatedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/weaknesses/recurring")
class RecurringWeaknessController(
    private val discoveryService: RecurringWeaknessDiscoveryService,
    private val accountOwnershipService: AccountOwnershipService,
) {
    @PostMapping("/discover")
    fun triggerDiscovery(
        @RequestBody request: RecurringDiscoveryRequest,
        @OptionalAuthenticatedPrincipal
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
    ): ResponseEntity<RecurringDiscoveryJobResponse> {
        val account = resolveAccount(request.accountId, request.username, principal)
        val job = discoveryService.triggerDiscovery(account, request)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job)
    }

    @GetMapping("/jobs/{jobId}")
    fun getJobStatus(
        @PathVariable jobId: UUID,
        @OptionalAuthenticatedPrincipal
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
    ): ResponseEntity<RecurringDiscoveryJobResponse> {
        val job = discoveryService.getJob(jobId) ?: throw NoSuchElementException("Job $jobId not found")
        if (principal != null) {
            accountOwnershipService.resolvePrivateRead(job.accountId, principal)
        }
        return ResponseEntity.ok(job)
    }

    @PostMapping("/jobs/{jobId}/cancel")
    fun cancelJob(
        @PathVariable jobId: UUID,
        @RequestParam(required = false) accountId: UUID?,
        @RequestParam(required = false) username: String?,
        @OptionalAuthenticatedPrincipal
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
    ): ResponseEntity<RecurringDiscoveryJobResponse> {
        val job = discoveryService.getJob(jobId) ?: throw NoSuchElementException("Job $jobId not found")
        val targetAccountId = accountId ?: if (principal != null) job.accountId else null
        val account = resolveAccount(targetAccountId, username, principal)
        val cancelled = discoveryService.cancelJob(jobId, account)
        return ResponseEntity.ok(cancelled)
    }

    @GetMapping
    fun getRecurringWeaknesses(
        @RequestParam(required = false) accountId: UUID?,
        @RequestParam(required = false) username: String?,
        @OptionalAuthenticatedPrincipal
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
    ): ResponseEntity<List<RecurringWeaknessResponse>> {
        val account = resolveAccount(accountId, username, principal)
        val weaknesses = discoveryService.getLatestRecurringWeaknesses(account)
        return ResponseEntity.ok(weaknesses)
    }

    @GetMapping("/{clusterId}/positions")
    fun getClusterPositions(
        @PathVariable clusterId: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "false") playableOnly: Boolean,
        @RequestParam(required = false) accountId: UUID?,
        @OptionalAuthenticatedPrincipal
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
    ): ResponseEntity<ClusterMemberPositionsResponse> {
        if (principal == null) {
            throw UnauthenticatedException("Authentication required to access cluster member positions")
        }
        if (page < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be non-negative: $page")
        }
        if (size < 1 || size > 100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and 100: $size")
        }

        val targetAccountId =
            accountId ?: discoveryService.getClusterAccountId(clusterId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Cluster $clusterId not found")

        val account =
            try {
                accountOwnershipService.resolvePrivateRead(targetAccountId, principal)
            } catch (_: Exception) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Cluster $clusterId not found")
            }

        val response =
            discoveryService.getClusterPositions(
                clusterId = clusterId,
                account = account,
                page = page,
                size = size,
                playableOnly = playableOnly,
            )
        return ResponseEntity.ok(response)
    }

    private fun resolveAccount(
        accountId: UUID?,
        username: String?,
        principal: AuthenticatedPrincipal?,
    ): ChessAccount =
        if (principal != null) {
            if (!username.isNullOrBlank()) {
                throw AccountSelectionRequiredException("An accountId is required for authenticated private data")
            }
            if (accountId == null) {
                throw IllegalArgumentException("Either accountId or username must be provided")
            }
            accountOwnershipService.resolvePrivateRead(accountId, principal)
        } else {
            if (accountId != null) {
                throw UnauthenticatedException("Authentication required to access account by accountId")
            }
            if (username.isNullOrBlank()) {
                throw IllegalArgumentException("Either accountId or username must be provided")
            }
            accountOwnershipService.resolvePrivateRead(Platform.CHESS_COM, username, null)
        }
}
