package com.chessecho.controller

import com.chessecho.dto.ErrorResponse
import com.chessecho.service.AccountClaimConflictException
import com.chessecho.service.AccountNotFoundException
import com.chessecho.service.AccountSelectionMismatchException
import com.chessecho.service.AccountSelectionRequiredException
import com.chessecho.service.ActiveImportJobException
import com.chessecho.service.ForbiddenAccountException
import com.chessecho.web.CsrfException
import com.chessecho.web.DuplicateRegistrationException
import com.chessecho.web.InvalidCredentialsException
import com.chessecho.web.UnauthenticatedException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(error = "INVALID_CREDENTIALS", details = listOf("Invalid email or password")))

    @ExceptionHandler(DuplicateRegistrationException::class)
    fun handleDuplicateRegistration(): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(error = "REGISTRATION_CONFLICT", details = listOf("Email is already registered")))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(error = "REGISTRATION_CONFLICT", details = listOf("Email is already registered")))

    @ExceptionHandler(UnauthenticatedException::class)
    fun handleUnauthenticated(ex: UnauthenticatedException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(error = "UNAUTHENTICATED", details = listOf(ex.message ?: "Authentication required")))

    @ExceptionHandler(CsrfException::class)
    fun handleCsrf(ex: CsrfException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse(error = "CSRF_FAILED", details = listOf(ex.message ?: "CSRF validation failed")))

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(error = "NOT_FOUND", details = listOf(ex.message ?: "Resource not found")))

    @ExceptionHandler(AccountNotFoundException::class)
    fun handleAccountNotFound(ex: AccountNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(error = "ACCOUNT_NOT_FOUND", details = listOf(ex.message ?: "Account not found")))

    @ExceptionHandler(ForbiddenAccountException::class)
    fun handleForbidden(ex: ForbiddenAccountException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse(error = "FORBIDDEN", details = listOf(ex.message ?: "Forbidden")))

    @ExceptionHandler(AccountClaimConflictException::class)
    fun handleAccountClaimConflict(ex: AccountClaimConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(error = "ACCOUNT_CLAIM_CONFLICT", details = listOf(ex.message ?: "Account claim conflict")))

    @ExceptionHandler(AccountSelectionRequiredException::class)
    fun handleAccountSelectionRequired(ex: AccountSelectionRequiredException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .badRequest()
            .body(ErrorResponse(error = "ACCOUNT_SELECTION_REQUIRED", details = listOf(ex.message ?: "Account selection required")))

    @ExceptionHandler(AccountSelectionMismatchException::class)
    fun handleAccountSelectionMismatch(ex: AccountSelectionMismatchException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .badRequest()
            .body(ErrorResponse(error = "ACCOUNT_SELECTION_MISMATCH", details = listOf(ex.message ?: "Account selection mismatch")))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(error = "VALIDATION_ERROR", details = listOf(ex.message ?: "Invalid request parameter")))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationErrors(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = ex.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity
            .badRequest()
            .body(ErrorResponse(error = "VALIDATION_ERROR", details = details))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> {
        val detail = ex.cause?.message ?: ex.message ?: "Invalid parameter value '${ex.value}'"
        return ResponseEntity
            .badRequest()
            .body(ErrorResponse(error = "VALIDATION_ERROR", details = listOf(detail)))
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(
        ex: org.springframework.http.converter.HttpMessageNotReadableException,
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .badRequest()
            .body(ErrorResponse(error = "VALIDATION_ERROR", details = listOf(ex.message ?: "Invalid request body")))

    @ExceptionHandler(ActiveImportJobException::class)
    fun handleActiveJobConflict(ex: ActiveImportJobException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(error = "CONFLICT", details = listOf(ex.message ?: "Active job exists")))
}
