package com.chessprobe.repository

import com.chessprobe.domain.LocalCredential
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LocalCredentialRepository : JpaRepository<LocalCredential, UUID> {
    fun findByUserId(userId: UUID): LocalCredential?
}
