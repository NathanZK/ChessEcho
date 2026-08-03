package com.chessecho.repository

import com.chessecho.domain.AppUser
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AppUserRepository : JpaRepository<AppUser, UUID> {
    fun findByEmail(email: String): AppUser?
}
