package com.chessecho.controller

import com.chessecho.dto.AccountAssociationRequest
import com.chessecho.dto.ChessAccountResponse
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.web.SessionAuthenticationFilter
import com.chessecho.web.UnauthenticatedException
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/accounts")
class AccountController(
    private val accountOwnershipService: AccountOwnershipService,
) {
    @GetMapping
    fun listAccounts(
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
    ): List<ChessAccountResponse> {
        return accountOwnershipService.listOwnedAccounts(principal ?: throw UnauthenticatedException())
    }

    @PostMapping
    fun associate(
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
        @Valid @RequestBody request: AccountAssociationRequest,
    ): ResponseEntity<ChessAccountResponse> {
        val result = accountOwnershipService.associate(principal ?: throw UnauthenticatedException(), request)
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(result.account)
    }
}
