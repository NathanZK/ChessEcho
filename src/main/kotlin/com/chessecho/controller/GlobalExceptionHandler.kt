package com.chessecho.controller

import com.chessecho.dto.ErrorResponse
import com.chessecho.service.ActiveImportJobException
import com.chessecho.web.CsrfException
import com.chessecho.web.UnauthenticatedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {
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
