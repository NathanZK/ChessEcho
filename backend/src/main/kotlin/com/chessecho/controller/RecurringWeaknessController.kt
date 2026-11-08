package com.chessecho.controller

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Platform
import com.chessecho.dto.RecurringDiscoveryJobResponse
import com.chessecho.dto.RecurringDiscoveryRequest
import com.chessecho.dto.RecurringWeaknessResponse
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.AccountSelectionRequiredException
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.service.weakness.RecurringWeaknessDiscoveryService
import com.chessecho.web.OptionalAuthenticatedPrincipal
import com.chessecho.web.SessionAuthenticationFilter
import com.chessecho.web.UnauthenticatedException
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
