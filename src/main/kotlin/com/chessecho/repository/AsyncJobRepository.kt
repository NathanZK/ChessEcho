package com.chessecho.repository

import com.chessecho.domain.AsyncJob
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AsyncJobRepository : JpaRepository<AsyncJob, UUID> {
    fun findByUsernameAndStatusIn(
        username: String,
        statuses: List<String>,
    ): AsyncJob?
}
