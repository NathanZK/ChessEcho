package com.chessecho.controller

import com.chessecho.dto.AuthResponse
import com.chessecho.dto.LoginRequest
import com.chessecho.dto.RegistrationRequest
import com.chessecho.service.auth.LocalCredentialService
import com.chessecho.web.SessionCookieWriter
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class AuthController(
    private val localCredentialService: LocalCredentialService,
    private val sessionCookieWriter: SessionCookieWriter,
) {
    @PostMapping("/register")
    fun register(
        @Valid @RequestBody request: RegistrationRequest,
        response: HttpServletResponse,
    ): ResponseEntity<AuthResponse> {
        val result = localCredentialService.register(request)
        sessionCookieWriter.writeSessionCookie(response, result.session)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(AuthResponse(result.user.id, requireNotNull(result.user.email)))
    }

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        response: HttpServletResponse,
    ): AuthResponse {
        val result = localCredentialService.login(request)
        sessionCookieWriter.writeSessionCookie(response, result.session)
        return AuthResponse(result.user.id, requireNotNull(result.user.email))
    }
}
