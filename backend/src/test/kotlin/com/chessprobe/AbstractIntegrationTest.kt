package com.chessprobe

import com.chessprobe.domain.AccountConnection
import com.chessprobe.domain.AppUser
import com.chessprobe.domain.ChessAccount
import com.chessprobe.repository.AccountConnectionRepository
import com.chessprobe.repository.AppUserRepository
import com.chessprobe.repository.AsyncJobRepository
import com.chessprobe.repository.ChessAccountRepository
import com.chessprobe.service.weakness.RecurringWeaknessDiscoveryService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {
    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    protected lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    protected lateinit var appUserRepository: AppUserRepository

    @Autowired
    protected lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    protected lateinit var accountConnectionRepository: AccountConnectionRepository

    @Autowired
    protected lateinit var asyncJobRepository: AsyncJobRepository

    @Autowired(required = false)
    protected var recurringWeaknessDiscoveryService: RecurringWeaknessDiscoveryService? = null

    companion object {
        @JvmField
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                start()
            }

        init {
            com.chessprobe.isolation.TestNetworkGuard
                .install()
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    postgres.stop()
                },
            )
        }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }

    // ── Test Tenant Creation Helpers ──────────────────────────────────────────

    fun createTestUser(email: String = "user-${UUID.randomUUID()}@example.com"): AppUser = appUserRepository.save(AppUser(email = email))

    fun createTestAccount(
        user: AppUser? = null,
        platform: String = "CHESS_COM",
        username: String = "account-${UUID.randomUUID().toString().replace("-", "").take(16)}",
    ): ChessAccount {
        val account =
            chessAccountRepository.save(
                ChessAccount(
                    platform = platform,
                    username = username,
                ),
            )
        if (user != null) {
            accountConnectionRepository.save(
                AccountConnection(
                    appUser = user,
                    chessAccount = account,
                ),
            )
        }
        return account
    }

    // ── Worker Draining ───────────────────────────────────────────────────────

    fun drainAsyncWorkerQuiescence(
        userId: UUID? = null,
        accountId: UUID? = null,
        jobIds: Collection<UUID> = emptyList(),
        timeoutMs: Long = 5000,
    ) {
        val identities = deriveTenantIdentities(userId, accountId)
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val dbActive = hasActiveDatabaseJobs(identities.userIds, identities.accountIds, jobIds)
            val discoveryActive = hasActiveDiscoveryJobs(identities.accountIds)

            if (!dbActive && !discoveryActive) {
                return
            }

            try {
                TimeUnit.MILLISECONDS.sleep(50)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Quiescence drain interrupted", ie)
            }
        }

        throw IllegalStateException(
            "Worker quiescence timed out after ${timeoutMs}ms for scoped tenant: " +
                "users=${identities.userIds}, accounts=${identities.accountIds}, jobs=$jobIds",
        )
    }

    private fun hasActiveDatabaseJobs(
        userIds: Set<UUID>,
        accountIds: Set<UUID>,
        jobIds: Collection<UUID>,
    ): Boolean {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (userIds.isNotEmpty()) {
            conditions.add("app_user_id IN (${userIds.joinToString(",") { "?" }})")
            params.addAll(userIds)
        }
        if (accountIds.isNotEmpty()) {
            conditions.add("chess_account_id IN (${accountIds.joinToString(",") { "?" }})")
            params.addAll(accountIds)
        }
        if (jobIds.isNotEmpty()) {
            conditions.add("id IN (${jobIds.joinToString(",") { "?" }})")
            params.addAll(jobIds)
        }
        if (conditions.isEmpty()) {
            return false
        }

        val sql = "SELECT count(*) FROM async_job WHERE (${conditions.joinToString(" OR ")}) AND status IN ('QUEUED', 'PROCESSING')"
        val count = jdbcTemplate.queryForObject(sql, Long::class.java, *params.toTypedArray()) ?: 0L
        return count > 0L
    }

    private fun hasActiveDiscoveryJobs(accountIds: Set<UUID>): Boolean {
        val discoveryService = recurringWeaknessDiscoveryService ?: return false
        if (accountIds.isEmpty()) return false

        val activeMap =
            runCatching {
                val field = discoveryService.javaClass.getDeclaredField("activeAccountJobs")
                field.isAccessible = true
                val raw = field.get(discoveryService) ?: return false
                @Suppress("UNCHECKED_CAST")
                raw as? Map<UUID, UUID> ?: return false
            }.getOrNull() ?: return false

        return accountIds.any { activeMap.containsKey(it) }
    }

    // ── 18-Table Tenant Cleanup ───────────────────────────────────────────────

    fun cleanTenantData(
        userId: UUID? = null,
        accountId: UUID? = null,
    ): TenantCleanupContext {
        require(userId != null || accountId != null) {
            "At least one of userId or accountId must be provided for tenant cleanup"
        }

        return transactionTemplate.execute {
            val identities = deriveTenantIdentities(userId, accountId)
            val snapshotIds = fetchChildSnapshotIds(identities.accountIds)
            val archiveIds = fetchChildArchiveIds(identities.accountIds)

            deleteAttemptAndEventTables(identities)
            deleteClusterHierarchy(snapshotIds, identities.accountIds)
            deleteAccountCoreTables(identities.accountIds, archiveIds)
            deleteAuthAndUserTables(identities)

            TenantCleanupContext.create(
                userIds = identities.userIds,
                accountIds = identities.accountIds,
                snapshotInventory = ChildInventory.Captured(snapshotIds),
                archiveInventory = ChildInventory.Captured(archiveIds),
            )
        } ?: throw IllegalStateException("Tenant cleanup transaction returned null context")
    }

    private fun deleteAttemptAndEventTables(identities: TenantIdentities) {
        deleteWhereUserOrAccount("training_attempt", identities.userIds, identities.accountIds)
        deleteWhereUserOrAccount("puzzle_scheduling_event", identities.userIds, identities.accountIds)
    }

    private fun deleteClusterHierarchy(
        snapshotIds: List<UUID>,
        accountIds: Set<UUID>,
    ) {
        if (snapshotIds.isNotEmpty()) {
            val qMarks = snapshotIds.joinToString(",") { "?" }
            jdbcTemplate.update("DELETE FROM position_cluster_assignment WHERE snapshot_id IN ($qMarks)", *snapshotIds.toTypedArray())
            jdbcTemplate.update("DELETE FROM position_cluster WHERE snapshot_id IN ($qMarks)", *snapshotIds.toTypedArray())
        }
        if (accountIds.isNotEmpty()) {
            val qMarks = accountIds.joinToString(",") { "?" }
            jdbcTemplate.update("DELETE FROM position_cluster_snapshot WHERE account_id IN ($qMarks)", *accountIds.toTypedArray())
        }
    }

    private fun deleteAccountCoreTables(
        accountIds: Set<UUID>,
        archiveIds: List<UUID>,
    ) {
        deleteWhereAccount("user_position_weakness", accountIds)
        deleteWhereAccount("user_position_stats", accountIds)
        deleteWhereAccount("position_occurrence", accountIds)
        deleteWhereAccount("game", accountIds)

        if (archiveIds.isNotEmpty()) {
            val qMarks = archiveIds.joinToString(",") { "?" }
            jdbcTemplate.update("DELETE FROM archive_derived_processing WHERE imported_archive_id IN ($qMarks)", *archiveIds.toTypedArray())
        }
        deleteWhereAccount("imported_archive", accountIds)
    }

    private fun deleteAuthAndUserTables(identities: TenantIdentities) {
        // async_job and account_connection must precede parent account/user deletion
        deleteWhereUserOrAccount("async_job", identities.userIds, identities.accountIds)
        deleteWhereUserOrAccount("account_connection", identities.userIds, identities.accountIds)

        deleteWhereUser("auth_session", identities.userIds)
        deleteWhereUser("auth_identity", identities.userIds)
        deleteWhereUser("local_credential", identities.userIds)

        if (identities.accountIds.isNotEmpty()) {
            val qMarks = identities.accountIds.joinToString(",") { "?" }
            jdbcTemplate.update("DELETE FROM chess_account WHERE id IN ($qMarks)", *identities.accountIds.toTypedArray())
        }
        if (identities.userIds.isNotEmpty()) {
            val qMarks = identities.userIds.joinToString(",") { "?" }
            jdbcTemplate.update("DELETE FROM app_user WHERE id IN ($qMarks)", *identities.userIds.toTypedArray())
        }
    }

    private fun deriveTenantIdentities(
        userId: UUID?,
        accountId: UUID?,
    ): TenantIdentities {
        val userIds = mutableSetOf<UUID>()
        val accountIds = mutableSetOf<UUID>()

        if (userId != null) {
            userIds.add(userId)
            val linked =
                jdbcTemplate.queryForList(
                    "SELECT chess_account_id FROM account_connection WHERE app_user_id = ?",
                    UUID::class.java,
                    userId,
                )
            accountIds.addAll(linked)
        }

        if (accountId != null) {
            accountIds.add(accountId)
            val linked =
                jdbcTemplate.queryForList(
                    "SELECT app_user_id FROM account_connection WHERE chess_account_id = ?",
                    UUID::class.java,
                    accountId,
                )
            userIds.addAll(linked)
        }

        return TenantIdentities(userIds = userIds, accountIds = accountIds)
    }

    private fun fetchChildSnapshotIds(accountIds: Set<UUID>): List<UUID> {
        if (accountIds.isEmpty()) return emptyList()
        val qMarks = accountIds.joinToString(",") { "?" }
        return jdbcTemplate.queryForList(
            "SELECT id FROM position_cluster_snapshot WHERE account_id IN ($qMarks)",
            UUID::class.java,
            *accountIds.toTypedArray(),
        )
    }

    private fun fetchChildArchiveIds(accountIds: Set<UUID>): List<UUID> {
        if (accountIds.isEmpty()) return emptyList()
        val qMarks = accountIds.joinToString(",") { "?" }
        return jdbcTemplate.queryForList(
            "SELECT id FROM imported_archive WHERE chess_account_id IN ($qMarks)",
            UUID::class.java,
            *accountIds.toTypedArray(),
        )
    }

    private fun deleteWhereUserOrAccount(
        table: String,
        userIds: Set<UUID>,
        accountIds: Set<UUID>,
    ) {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (userIds.isNotEmpty()) {
            conditions.add("app_user_id IN (${userIds.joinToString(",") { "?" }})")
            params.addAll(userIds)
        }
        if (accountIds.isNotEmpty()) {
            conditions.add("chess_account_id IN (${accountIds.joinToString(",") { "?" }})")
            params.addAll(accountIds)
        }
        if (conditions.isNotEmpty()) {
            val sql = "DELETE FROM $table WHERE ${conditions.joinToString(" OR ")}"
            jdbcTemplate.update(sql, *params.toTypedArray())
        }
    }

    private fun deleteWhereAccount(
        table: String,
        accountIds: Set<UUID>,
    ) {
        if (accountIds.isNotEmpty()) {
            val qMarks = accountIds.joinToString(",") { "?" }
            jdbcTemplate.update("DELETE FROM $table WHERE chess_account_id IN ($qMarks)", *accountIds.toTypedArray())
        }
    }

    private fun deleteWhereUser(
        table: String,
        userIds: Set<UUID>,
    ) {
        if (userIds.isNotEmpty()) {
            val qMarks = userIds.joinToString(",") { "?" }
            jdbcTemplate.update("DELETE FROM $table WHERE app_user_id IN ($qMarks)", *userIds.toTypedArray())
        }
    }

    // ── Post-Cleanup Quarantine Assertion ─────────────────────────────────────

    fun assertTenantQuarantined(context: TenantCleanupContext) {
        require(context.userIds.isNotEmpty() || context.accountIds.isNotEmpty()) {
            "Tenant quarantine verification requires a non-empty cleanup context"
        }
        if (context.accountIds.isNotEmpty()) {
            require(context.snapshotInventory is ChildInventory.Captured) {
                "Tenant quarantine verification requires captured snapshot inventory when account IDs are present"
            }
            require(context.archiveInventory is ChildInventory.Captured) {
                "Tenant quarantine verification requires captured archive inventory when account IDs are present"
            }
        }
        checkZeroWhereUserOrAccount("training_attempt", context.userIds, context.accountIds)
        checkZeroWhereUserOrAccount("puzzle_scheduling_event", context.userIds, context.accountIds)
        assertClusterQuarantine(context.snapshotIds, context.accountIds)
        assertAccountCoreQuarantine(context.accountIds, context.archiveIds)
        assertUserCoreQuarantine(context.userIds, context.accountIds)
    }

    private fun assertClusterQuarantine(
        snapshotIds: List<UUID>,
        accountIds: Set<UUID>,
    ) {
        if (snapshotIds.isNotEmpty()) {
            val qMarks = snapshotIds.joinToString(",") { "?" }
            val assignCount =
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM position_cluster_assignment WHERE snapshot_id IN ($qMarks)",
                    Long::class.java,
                    *snapshotIds.toTypedArray(),
                ) ?: 0L
            if (assignCount > 0L) failQuarantine("position_cluster_assignment", assignCount)

            val clusterCount =
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM position_cluster WHERE snapshot_id IN ($qMarks)",
                    Long::class.java,
                    *snapshotIds.toTypedArray(),
                ) ?: 0L
            if (clusterCount > 0L) failQuarantine("position_cluster", clusterCount)
        }

        if (accountIds.isNotEmpty()) {
            val qMarks = accountIds.joinToString(",") { "?" }
            val snapshotCount =
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM position_cluster_snapshot WHERE account_id IN ($qMarks)",
                    Long::class.java,
                    *accountIds.toTypedArray(),
                ) ?: 0L
            if (snapshotCount > 0L) failQuarantine("position_cluster_snapshot", snapshotCount)
        }
    }

    private fun assertAccountCoreQuarantine(
        accountIds: Set<UUID>,
        archiveIds: List<UUID>,
    ) {
        checkZeroWhereAccount("user_position_weakness", accountIds)
        checkZeroWhereAccount("user_position_stats", accountIds)
        checkZeroWhereAccount("position_occurrence", accountIds)
        checkZeroWhereAccount("game", accountIds)

        if (archiveIds.isNotEmpty()) {
            val qMarks = archiveIds.joinToString(",") { "?" }
            val count =
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM archive_derived_processing WHERE imported_archive_id IN ($qMarks)",
                    Long::class.java,
                    *archiveIds.toTypedArray(),
                ) ?: 0L
            if (count > 0L) failQuarantine("archive_derived_processing", count)
        }
        checkZeroWhereAccount("imported_archive", accountIds)
    }

    private fun assertUserCoreQuarantine(
        userIds: Set<UUID>,
        accountIds: Set<UUID>,
    ) {
        checkZeroWhereUserOrAccount("async_job", userIds, accountIds)
        checkZeroWhereUserOrAccount("account_connection", userIds, accountIds)
        checkZeroWhereUser("auth_session", userIds)
        checkZeroWhereUser("auth_identity", userIds)
        checkZeroWhereUser("local_credential", userIds)

        if (accountIds.isNotEmpty()) {
            val qMarks = accountIds.joinToString(",") { "?" }
            val accountCount =
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM chess_account WHERE id IN ($qMarks)",
                    Long::class.java,
                    *accountIds.toTypedArray(),
                ) ?: 0L
            if (accountCount > 0L) failQuarantine("chess_account", accountCount)
        }

        if (userIds.isNotEmpty()) {
            val qMarks = userIds.joinToString(",") { "?" }
            val userCount =
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM app_user WHERE id IN ($qMarks)",
                    Long::class.java,
                    *userIds.toTypedArray(),
                ) ?: 0L
            if (userCount > 0L) failQuarantine("app_user", userCount)
        }
    }

    private fun checkZeroWhereUserOrAccount(
        table: String,
        userIds: Set<UUID>,
        accountIds: Set<UUID>,
    ) {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (userIds.isNotEmpty()) {
            conditions.add("app_user_id IN (${userIds.joinToString(",") { "?" }})")
            params.addAll(userIds)
        }
        if (accountIds.isNotEmpty()) {
            conditions.add("chess_account_id IN (${accountIds.joinToString(",") { "?" }})")
            params.addAll(accountIds)
        }
        if (conditions.isNotEmpty()) {
            val sql = "SELECT count(*) FROM $table WHERE ${conditions.joinToString(" OR ")}"
            val count = jdbcTemplate.queryForObject(sql, Long::class.java, *params.toTypedArray()) ?: 0L
            if (count > 0L) failQuarantine(table, count)
        }
    }

    private fun checkZeroWhereAccount(
        table: String,
        accountIds: Set<UUID>,
    ) {
        if (accountIds.isNotEmpty()) {
            val qMarks = accountIds.joinToString(",") { "?" }
            val count =
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM $table WHERE chess_account_id IN ($qMarks)",
                    Long::class.java,
                    *accountIds.toTypedArray(),
                ) ?: 0L
            if (count > 0L) failQuarantine(table, count)
        }
    }

    private fun checkZeroWhereUser(
        table: String,
        userIds: Set<UUID>,
    ) {
        if (userIds.isNotEmpty()) {
            val qMarks = userIds.joinToString(",") { "?" }
            val count =
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM $table WHERE app_user_id IN ($qMarks)",
                    Long::class.java,
                    *userIds.toTypedArray(),
                ) ?: 0L
            if (count > 0L) failQuarantine(table, count)
        }
    }

    private fun failQuarantine(
        table: String,
        count: Long,
    ): Nothing = throw AssertionError("Tenant quarantine assertion failed: residual rows detected in table $table (count=$count)")

    data class TenantIdentities(
        val userIds: Set<UUID>,
        val accountIds: Set<UUID>,
    )

    sealed interface ChildInventory {
        val ids: List<UUID>

        data class Captured(
            override val ids: List<UUID>,
        ) : ChildInventory

        data object Uncaptured : ChildInventory {
            override val ids: List<UUID> get() = emptyList()
        }
    }

    class TenantCleanupContext private constructor(
        val userIds: Set<UUID>,
        val accountIds: Set<UUID>,
        val snapshotInventory: ChildInventory,
        val archiveInventory: ChildInventory,
    ) {
        val snapshotIds: List<UUID> get() = snapshotInventory.ids
        val archiveIds: List<UUID> get() = archiveInventory.ids

        init {
            require(userIds.isNotEmpty() || accountIds.isNotEmpty()) {
                "TenantCleanupContext must contain at least one non-empty user ID or account ID"
            }
            if (accountIds.isNotEmpty()) {
                require(snapshotInventory is ChildInventory.Captured) {
                    "TenantCleanupContext requires captured snapshot inventory when account IDs are present"
                }
                require(archiveInventory is ChildInventory.Captured) {
                    "TenantCleanupContext requires captured archive inventory when account IDs are present"
                }
            }
        }

        companion object {
            internal fun create(
                userIds: Set<UUID>,
                accountIds: Set<UUID>,
                snapshotInventory: ChildInventory,
                archiveInventory: ChildInventory,
            ): TenantCleanupContext =
                TenantCleanupContext(
                    userIds = userIds,
                    accountIds = accountIds,
                    snapshotInventory = snapshotInventory,
                    archiveInventory = archiveInventory,
                )
        }
    }
}
