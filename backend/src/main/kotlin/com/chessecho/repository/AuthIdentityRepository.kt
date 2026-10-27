package com.chessecho.repository

import com.chessecho.domain.AuthIdentity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AuthIdentityRepository : JpaRepository<AuthIdentity, UUID> {
    fun findByIssuerAndSubject(
        issuer: String,
        subject: String,
    ): AuthIdentity?
}
