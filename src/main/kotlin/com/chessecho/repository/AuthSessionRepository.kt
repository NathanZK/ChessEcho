package com.chessecho.repository

import com.chessecho.domain.AuthSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import java.time.Instant
import java.util.UUID

interface AuthSessionRepository : JpaRepository<AuthSession, UUID> {
    fun findByTokenHash(tokenHash: String): AuthSession?

    @Modifying
    fun deleteByRevokedAtIsNotNullOrAbsoluteExpiresAtBefore(cutoff: Instant): Long
}
