package com.chessprobe

import com.chessprobe.domain.AsyncJob
import com.chessprobe.domain.Platform
import com.chessprobe.domain.PlayerColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AbstractIntegrationTestLifecycleTest : AbstractIntegrationTest() {
    @Test
    fun `database connection is active and flyway baseline schema is valid`() {
        val result = jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
        assertEquals(1, result ?: 0)

        val tableCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'",
                Int::class.java,
            )
        assertNotNull(tableCount)
        assertTrue((tableCount ?: 0) > 0, "Public schema should contain tables from Flyway migrations")
    }

    // ── Positive Tests for All 5 Identity Configurations ──────────────────────

    @Test
    fun `cleanTenantData and assertTenantQuarantined handle user-only tenant`() {
        val user = createTestUser("user-only-${UUID.randomUUID()}@example.com")
        val userId = user.id!!

        jdbcTemplate.update(
            "INSERT INTO local_credential (app_user_id, password_hash) VALUES (?, ?)",
            userId,
            "hash123",
        )
        jdbcTemplate.update(
            """
            INSERT INTO auth_session (token_hash, app_user_id, idle_expires_at, absolute_expires_at)
            VALUES (?, ?, CURRENT_TIMESTAMP + interval '1 hour', CURRENT_TIMESTAMP + interval '2 hour')
            """,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            userId,
        )

        val context = cleanTenantData(userId = userId)
        assertEquals(setOf(userId), context.userIds)
        assertTrue(context.accountIds.isEmpty())
        assertTenantQuarantined(context)
    }

    @Test
    fun `cleanTenantData and assertTenantQuarantined handle account-only tenant`() {
        val account = createTestAccount(user = null, username = "acct-only-${UUID.randomUUID()}")
        val accountId = account.id!!

        val gameId = UUID.randomUUID()
        val posId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO position (id, hash, fen) VALUES (?, ?, ?)",
            posId,
            "hash-${UUID.randomUUID()}",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        )
        jdbcTemplate.update(
            "INSERT INTO game (id, chess_account_id, platform_game_id, pgn) VALUES (?, ?, ?, ?)",
            gameId,
            accountId,
            "pgn-${UUID.randomUUID()}",
            "1. e4",
        )
        jdbcTemplate.update(
            """
            INSERT INTO position_occurrence (id, game_id, position_id, chess_account_id, ply_number, move_played, player_color)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            UUID.randomUUID(),
            gameId,
            posId,
            accountId,
            1,
            "e4",
            "WHITE",
        )

        val context = cleanTenantData(accountId = accountId)
        assertTrue(context.userIds.isEmpty())
        assertEquals(setOf(accountId), context.accountIds)
        assertTrue(context.snapshotInventory is ChildInventory.Captured)
        assertTrue(context.archiveInventory is ChildInventory.Captured)
        assertTenantQuarantined(context)
    }

    @Test
    fun `cleanTenantData and assertTenantQuarantined handle linked user and account tenant`() {
        val user = createTestUser("linked-${UUID.randomUUID()}@example.com")
        val account = createTestAccount(user = user, username = "linked-${UUID.randomUUID()}")
        val userId = user.id!!
        val accountId = account.id!!

        val gameId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO game (id, chess_account_id, platform_game_id, pgn) VALUES (?, ?, ?, ?)",
            gameId,
            accountId,
            "pgn-${UUID.randomUUID()}",
            "1. e4",
        )
        jdbcTemplate.update(
            """
            INSERT INTO async_job (app_user_id, chess_account_id, username, platform, status)
            VALUES (?, ?, ?, 'CHESS_COM', 'COMPLETED')
            """,
            userId,
            accountId,
            account.username,
        )

        val context = cleanTenantData(userId = userId)
        assertEquals(setOf(userId), context.userIds)
        assertEquals(setOf(accountId), context.accountIds)
        assertTenantQuarantined(context)
    }

    @Test
    fun `cleanTenantData and assertTenantQuarantined handle unlinked user and account`() {
        val user = createTestUser("unlinked-${UUID.randomUUID()}@example.com")
        val account = createTestAccount(user = null, username = "unlinked-${UUID.randomUUID()}")
        val userId = user.id!!
        val accountId = account.id!!

        val context = cleanTenantData(userId = userId, accountId = accountId)
        assertEquals(setOf(userId), context.userIds)
        assertEquals(setOf(accountId), context.accountIds)
        assertTenantQuarantined(context)
    }

    @Test
    fun `cleanTenantData and assertTenantQuarantined handle guest account jobs`() {
        val account = createTestAccount(user = null, username = "guest-${UUID.randomUUID()}")
        val accountId = account.id!!

        jdbcTemplate.update(
            """
            INSERT INTO async_job (app_user_id, chess_account_id, username, platform, status)
            VALUES (NULL, ?, ?, 'CHESS_COM', 'COMPLETED')
            """,
            accountId,
            account.username,
        )

        val context = cleanTenantData(accountId = accountId)
        assertTrue(context.userIds.isEmpty())
        assertEquals(setOf(accountId), context.accountIds)
        assertTenantQuarantined(context)
    }

    // ── Negative Tests: Missing and Incomplete Context ────────────────────────

    @Test
    fun `cleanTenantData throws IllegalArgumentException when both userId and accountId are null`() {
        assertThrows(IllegalArgumentException::class.java) {
            cleanTenantData(userId = null, accountId = null)
        }
    }

    @Test
    fun `TenantCleanupContext throws IllegalArgumentException when both userIds and accountIds are empty`() {
        assertThrows(IllegalArgumentException::class.java) {
            TenantCleanupContext.create(
                userIds = emptySet(),
                accountIds = emptySet(),
                snapshotInventory = ChildInventory.Captured(emptyList()),
                archiveInventory = ChildInventory.Captured(emptyList()),
            )
        }
    }

    @Test
    fun `TenantCleanupContext throws IllegalArgumentException when account IDs present with uncaptured snapshot inventory`() {
        val accountId = UUID.randomUUID()
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                TenantCleanupContext.create(
                    userIds = emptySet(),
                    accountIds = setOf(accountId),
                    snapshotInventory = ChildInventory.Uncaptured,
                    archiveInventory = ChildInventory.Captured(emptyList()),
                )
            }
        assertTrue(ex.message?.contains("requires captured snapshot inventory") == true)
    }

    @Test
    fun `TenantCleanupContext throws IllegalArgumentException when account IDs present with uncaptured archive inventory`() {
        val accountId = UUID.randomUUID()
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                TenantCleanupContext.create(
                    userIds = emptySet(),
                    accountIds = setOf(accountId),
                    snapshotInventory = ChildInventory.Captured(emptyList()),
                    archiveInventory = ChildInventory.Uncaptured,
                )
            }
        assertTrue(ex.message?.contains("requires captured archive inventory") == true)
    }

    @Test
    fun `cleanTenantData produces a valid context with legitimately captured empty inventory for accounts with no child data`() {
        val account = createTestAccount(user = null, username = "empty-inv-${UUID.randomUUID().toString().take(8)}")
        val accountId = account.id!!

        val context = cleanTenantData(accountId = accountId)
        assertTrue(context.snapshotInventory is ChildInventory.Captured)
        assertTrue(context.archiveInventory is ChildInventory.Captured)
        assertTrue(context.snapshotIds.isEmpty())
        assertTrue(context.archiveIds.isEmpty())
        assertTenantQuarantined(context)
    }

    // ── Negative Tests: Residual Row Detection Across Tables ──────────────────

    @Test
    fun `assertTenantQuarantined detects residual row in position_cluster_assignment`() {
        val account = createTestAccount(user = null, username = "res-assign-${UUID.randomUUID().toString().take(8)}")
        val accountId = account.id!!
        val posId = UUID.randomUUID()
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()

        insertPositionAndClusterTree(posId, accountId, snapshotId, clusterId)

        val context =
            TenantCleanupContext.create(
                userIds = emptySet(),
                accountIds = setOf(accountId),
                snapshotInventory = ChildInventory.Captured(listOf(snapshotId)),
                archiveInventory = ChildInventory.Captured(emptyList()),
            )

        try {
            val ex =
                assertThrows(AssertionError::class.java) {
                    assertTenantQuarantined(context)
                }
            assertTrue(ex.message?.contains("position_cluster_assignment") == true)
        } finally {
            cleanClusterTree(snapshotId, accountId, posId)
        }
    }

    @Test
    fun `assertTenantQuarantined detects residual row in position_cluster`() {
        val account = createTestAccount(user = null, username = "res-cluster-${UUID.randomUUID().toString().take(8)}")
        val accountId = account.id!!
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()

        insertSnapshotAndClusterOnly(accountId, snapshotId, clusterId)

        val context =
            TenantCleanupContext.create(
                userIds = emptySet(),
                accountIds = setOf(accountId),
                snapshotInventory = ChildInventory.Captured(listOf(snapshotId)),
                archiveInventory = ChildInventory.Captured(emptyList()),
            )

        try {
            val ex =
                assertThrows(AssertionError::class.java) {
                    assertTenantQuarantined(context)
                }
            assertTrue(ex.message?.contains("position_cluster") == true)
        } finally {
            cleanSnapshotAndClusterOnly(snapshotId, accountId)
        }
    }

    @Test
    fun `assertTenantQuarantined detects residual row in archive_derived_processing`() {
        val account = createTestAccount(user = null, username = "res-arch-${UUID.randomUUID().toString().take(8)}")
        val accountId = account.id!!
        val archiveId = UUID.randomUUID()
        val procId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO imported_archive (id, chess_account_id, archive_url, year_month) VALUES (?, ?, ?, '2024-01')",
            archiveId,
            accountId,
            "https://example.com/archive/$archiveId",
        )
        jdbcTemplate.update(
            "INSERT INTO archive_derived_processing (id, imported_archive_id, status) VALUES (?, ?, 'PENDING')",
            procId,
            archiveId,
        )

        val context =
            TenantCleanupContext.create(
                userIds = emptySet(),
                accountIds = setOf(accountId),
                snapshotInventory = ChildInventory.Captured(emptyList()),
                archiveInventory = ChildInventory.Captured(listOf(archiveId)),
            )

        try {
            val ex =
                assertThrows(AssertionError::class.java) {
                    assertTenantQuarantined(context)
                }
            assertTrue(ex.message?.contains("archive_derived_processing") == true)
        } finally {
            jdbcTemplate.update("DELETE FROM archive_derived_processing WHERE id = ?", procId)
            jdbcTemplate.update("DELETE FROM imported_archive WHERE id = ?", archiveId)
            cleanTenantData(accountId = accountId)
        }
    }

    @Test
    fun `assertTenantQuarantined detects residual row in training_attempt`() {
        val user = createTestUser("attempt-res-${UUID.randomUUID()}@example.com")
        val account = createTestAccount(user = user, username = "attempt-res-${UUID.randomUUID().toString().take(8)}")
        val attemptId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO training_attempt (id, puzzle_id, mode, elapsed_ms, outcome, app_user_id, chess_account_id)
            VALUES (?, 'p-1', 'STOPWATCH', 1000, 'SUBMITTED', ?, ?)
            """,
            attemptId,
            user.id!!,
            account.id!!,
        )

        val context =
            TenantCleanupContext.create(
                userIds = setOf(user.id!!),
                accountIds = setOf(account.id!!),
                snapshotInventory = ChildInventory.Captured(emptyList()),
                archiveInventory = ChildInventory.Captured(emptyList()),
            )

        try {
            val ex =
                assertThrows(AssertionError::class.java) {
                    assertTenantQuarantined(context)
                }
            assertTrue(ex.message?.contains("training_attempt") == true)
        } finally {
            jdbcTemplate.update("DELETE FROM training_attempt WHERE id = ?", attemptId)
            cleanTenantData(userId = user.id!!, accountId = account.id!!)
        }
    }

    @Test
    fun `assertTenantQuarantined detects residual row in async_job`() {
        val account = createTestAccount(user = null, username = "job-res-${UUID.randomUUID().toString().take(8)}")
        val accountId = account.id!!
        val jobId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO async_job (id, app_user_id, chess_account_id, username, platform, status)
            VALUES (?, NULL, ?, ?, 'CHESS_COM', 'COMPLETED')
            """,
            jobId,
            accountId,
            account.username,
        )

        val context =
            TenantCleanupContext.create(
                userIds = emptySet(),
                accountIds = setOf(accountId),
                snapshotInventory = ChildInventory.Captured(emptyList()),
                archiveInventory = ChildInventory.Captured(emptyList()),
            )

        try {
            val ex =
                assertThrows(AssertionError::class.java) {
                    assertTenantQuarantined(context)
                }
            assertTrue(ex.message?.contains("async_job") == true)
        } finally {
            jdbcTemplate.update("DELETE FROM async_job WHERE id = ?", jobId)
            cleanTenantData(accountId = accountId)
        }
    }

    // ── Helper Methods for Residual Fixture Setup ─────────────────────────────

    private fun insertPositionAndClusterTree(
        posId: UUID,
        accountId: UUID,
        snapshotId: UUID,
        clusterId: UUID,
    ) {
        jdbcTemplate.update(
            "INSERT INTO position (id, hash, fen) VALUES (?, ?, ?)",
            posId,
            "hash-${UUID.randomUUID()}",
            "8/8/8/8/8/8/8/8 w - - 0 1",
        )
        insertSnapshotAndClusterOnly(accountId, snapshotId, clusterId)
        jdbcTemplate.update(
            """
            INSERT INTO position_cluster_assignment (snapshot_id, position_id, cluster_id, assignment_status)
            VALUES (?, ?, ?, 'ASSIGNED')
            """,
            snapshotId,
            posId,
            clusterId,
        )
    }

    private fun insertSnapshotAndClusterOnly(
        accountId: UUID,
        snapshotId: UUID,
        clusterId: UUID,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO position_cluster_snapshot (
                id, account_id, snapshot_version, cluster_type, status,
                alpha, top_k, floor_threshold, top1_threshold, resolution, centroid_threshold
            ) VALUES (?, ?, 1, 'STRUCTURAL', 'ACTIVE', 0.6, 10, 0.5, 0.8, 1.0, 0.5)
            """,
            snapshotId,
            accountId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO position_cluster (id, snapshot_id, cluster_index, size, centroid_vector)
            VALUES (?, ?, 0, 1, '{}'::jsonb)
            """,
            clusterId,
            snapshotId,
        )
    }

    private fun cleanClusterTree(
        snapshotId: UUID,
        accountId: UUID,
        posId: UUID,
    ) {
        jdbcTemplate.update("DELETE FROM position_cluster_assignment WHERE snapshot_id = ?", snapshotId)
        cleanSnapshotAndClusterOnly(snapshotId, accountId)
        jdbcTemplate.update("DELETE FROM position WHERE id = ?", posId)
    }

    private fun cleanSnapshotAndClusterOnly(
        snapshotId: UUID,
        accountId: UUID,
    ) {
        jdbcTemplate.update("DELETE FROM position_cluster WHERE snapshot_id = ?", snapshotId)
        jdbcTemplate.update("DELETE FROM position_cluster_snapshot WHERE id = ?", snapshotId)
        cleanTenantData(accountId = accountId)
    }

    // ── Worker Quiescence Tests ───────────────────────────────────────────────

    @Test
    fun `drainAsyncWorkerQuiescence succeeds when scoped jobs complete`() {
        val account = createTestAccount(user = null, username = "drain-success-${UUID.randomUUID().toString().replace("-", "").take(16)}")
        val accountId = account.id!!

        val job =
            asyncJobRepository.save(
                AsyncJob(
                    chessAccount = account,
                    username = account.username,
                    platform = Platform.CHESS_COM.name,
                    status = "COMPLETED",
                    playerColor = PlayerColor.WHITE.name,
                    timeControlsCsv = "RAPID",
                ),
            )

        try {
            drainAsyncWorkerQuiescence(accountId = accountId, jobIds = listOf(job.id!!), timeoutMs = 1000)
        } finally {
            cleanTenantData(accountId = accountId)
        }
    }

    @Test
    fun `drainAsyncWorkerQuiescence times out when scoped job remains non-terminal`() {
        val account = createTestAccount(user = null, username = "drain-timeout-${UUID.randomUUID().toString().replace("-", "").take(16)}")
        val accountId = account.id!!

        val job =
            asyncJobRepository.save(
                AsyncJob(
                    chessAccount = account,
                    username = account.username,
                    platform = Platform.CHESS_COM.name,
                    status = "PROCESSING",
                    playerColor = PlayerColor.WHITE.name,
                    timeControlsCsv = "RAPID",
                ),
            )

        try {
            assertThrows(IllegalStateException::class.java) {
                drainAsyncWorkerQuiescence(accountId = accountId, jobIds = listOf(job.id!!), timeoutMs = 200)
            }
        } finally {
            job.status = "FAILED"
            asyncJobRepository.save(job)
            cleanTenantData(accountId = accountId)
        }
    }
}
