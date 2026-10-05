package com.chessecho.service

import com.chessecho.domain.AccountConnection
import com.chessecho.domain.AsyncJob
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Platform
import com.chessecho.dto.AccountAssociationRequest
import com.chessecho.dto.ChessAccountResponse
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AccountConnectionRepository
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.web.UnauthenticatedException
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.Locale
import java.util.UUID

/**
 * The single authorization boundary for account-backed data. A provider
 * username is useful for guest lookup, but it is never an ownership credential.
 */
@Service
class AccountOwnershipService(
    private val chessAccountRepository: ChessAccountRepository,
    private val appUserRepository: AppUserRepository,
    private val connectionRepository: AccountConnectionRepository,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val operationTransaction = TransactionTemplate(transactionManager)

    @Transactional(readOnly = true)
    fun listOwnedAccounts(principal: AuthenticatedPrincipal): List<ChessAccountResponse> =
        chessAccountRepository
            .findAllByUserIdOrderByCreatedAtAsc(principal.appUserId)
            .map { it.toResponse() }

    fun associate(
        principal: AuthenticatedPrincipal,
        request: AccountAssociationRequest,
    ): AssociationResult {
        val platform = normalizePlatform(request.platform)
        val username = normalizeUsername(request.username)
        // Return expected conflicts outside the transaction callback, so a caller can
        // handle the limit without marking its surrounding transaction rollback-only.
        val result =
            operationTransaction.execute {
                val user =
                    appUserRepository.findByIdForUpdate(principal.appUserId)
                        ?: throw AccountNotFoundException("Account user not found")
                val connection = connectionRepository.findByAppUserId(user.id)
                if (connection != null) {
                    val account = connection.chessAccount
                    if (account.platform.equals(platform, ignoreCase = true) &&
                        account.username.equals(username, ignoreCase = true)
                    ) {
                        return@execute AssociationResult(account.toResponse(), created = false)
                    }
                    return@execute null
                }
                val (account, created) = findOrCreateShared(platform, username)
                connectionRepository.saveAndFlush(AccountConnection(appUser = user, chessAccount = account))
                AssociationResult(account.toResponse(), created)
            }
        return result ?: throw AccountConnectionLimitReachedException()
    }

    @Transactional
    fun disconnect(
        accountId: UUID,
        principal: AuthenticatedPrincipal,
    ) {
        appUserRepository.findByIdForUpdate(principal.appUserId)
            ?: throw AccountNotFoundException("Account user not found")
        val connection = connectionRepository.findByAppUserId(principal.appUserId)
        if (connection == null || connection.chessAccount.id != accountId) {
            throw AccountNotFoundException("Account connection not found: $accountId")
        }
        connectionRepository.delete(connection)
        connectionRepository.flush()
    }

    /**
     * Resolves and authorizes an import request before any job is inserted.
     */
    @Transactional
    fun resolveImportAccount(
        request: ImportGamesRequest,
        principal: AuthenticatedPrincipal?,
    ): ChessAccount {
        if (request.isAuthenticatedForm()) {
            val accountId = request.accountId!!
            val account =
                chessAccountRepository.findById(accountId)
                    .orElseThrow { AccountNotFoundException("Account not found: $accountId") }
            verifySnapshots(request, account)
            val resolvedPrincipal = principal ?: throw UnauthenticatedException()
            appUserRepository.findByIdForUpdate(resolvedPrincipal.appUserId)
                ?: throw AccountNotFoundException("Account user not found")
            requireOwner(resolvedPrincipal, account)
            return account
        }

        if (principal != null) {
            throw AccountSelectionRequiredException()
        }
        val platform = normalizePlatform(request.platform?.name)
        val username = normalizeUsername(request.username)
        return findOrCreateShared(platform, username).first
    }

    @Transactional(readOnly = true)
    fun resolvePrivateRead(
        platform: Platform,
        username: String,
        principal: AuthenticatedPrincipal?,
    ): ChessAccount {
        if (principal != null) throw AccountSelectionRequiredException()

        val account =
            chessAccountRepository.findByPlatformAndUsernameIgnoreCase(
                normalizePlatform(platform.name),
                normalizeUsername(username),
            ) ?: throw AccountNotFoundException("Chess account not found")

        return account
    }

    @Transactional(readOnly = true)
    fun resolvePrivateRead(
        accountId: UUID,
        principal: AuthenticatedPrincipal?,
    ): ChessAccount {
        val account =
            chessAccountRepository.findById(accountId)
                .orElseThrow { AccountNotFoundException("Account not found: $accountId") }
        if (principal == null) throw UnauthenticatedException()
        requireOwner(principal, account)
        return account
    }

    /**
     * Existence-only resolution for **shared** account-derived reads (#457, T5).
     *
     * Shared data — games, occurrences, position stats, engine analysis — is owned by the
     * `ChessAccount` row itself, so any authenticated principal may read it for any account that
     * exists. Current connections deliberately play no part here.
     *
     * Personal state is *not* covered by this resolver: callers must still scope
     * `PuzzleSchedulingEvent` reads by `app_user_id`.
     *
     * Unauthenticated callers are still rejected, and the lookup deliberately precedes that check so
     * the guest-visible outcome matches [resolvePrivateRead] exactly.
     */
    @Transactional(readOnly = true)
    fun resolveSharedAccount(
        accountId: UUID,
        principal: AuthenticatedPrincipal?,
    ): ChessAccount {
        val account =
            chessAccountRepository.findById(accountId)
                .orElseThrow { AccountNotFoundException("Account not found: $accountId") }
        if (principal == null) throw UnauthenticatedException()
        return account
    }

    @Transactional(readOnly = true)
    fun requireOwnedAccount(
        accountId: UUID,
        principal: AuthenticatedPrincipal,
    ): ChessAccount {
        val account =
            chessAccountRepository.findById(accountId)
                .orElseThrow { AccountNotFoundException("Account not found: $accountId") }
        requireOwner(principal, account)
        return account
    }

    @Transactional(readOnly = true)
    fun requireConnectedAccountForProgress(
        accountId: UUID,
        principal: AuthenticatedPrincipal,
    ): ChessAccount {
        val account =
            chessAccountRepository.findById(accountId)
                .orElseThrow { AccountNotFoundException("Account not found: $accountId") }
        if (!connectionRepository.existsByAppUserIdAndChessAccountId(principal.appUserId, account.id)) {
            throw AccountNotFoundException("Account connection not found: $accountId")
        }
        return account
    }

    @Transactional(readOnly = true)
    fun authorizeJob(
        job: AsyncJob,
        principal: AuthenticatedPrincipal?,
    ) {
        val account = job.chessAccount ?: throw AccountNotFoundException("Import account is unresolved")
        if (principal == null) {
            if (job.appUser != null) throw UnauthenticatedException()
        } else if (job.appUser?.id != principal.appUserId) {
            throw ForbiddenAccountException()
        }
    }

    fun normalizePlatform(raw: String?): String {
        val normalized = raw?.trim()?.uppercase(Locale.ROOT)
        if (normalized.isNullOrBlank()) throw IllegalArgumentException("platform must not be blank")
        return try {
            Platform.valueOf(normalized).name
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Unsupported platform: $raw")
        }
    }

    fun normalizeUsername(raw: String): String {
        val normalized = raw.trim()
        if (normalized.isBlank()) throw IllegalArgumentException("username must not be blank")
        if (!USERNAME_PATTERN.matches(normalized)) {
            throw IllegalArgumentException("username contains invalid characters")
        }
        return normalized
    }

    private fun findOrCreateShared(
        platform: String,
        username: String,
    ): Pair<ChessAccount, Boolean> {
        val existing = chessAccountRepository.findByPlatformAndUsernameIgnoreCase(platform, username)
        if (existing != null) return existing to false
        val inserted =
            jdbc.execute(
                ConnectionCallback { connection ->
                    // H2's PostgreSQL mode supports DO NOTHING, but not expression-index inference.
                    val conflictTarget =
                        if (connection.metaData.databaseProductName == "PostgreSQL") {
                            " (lower(platform), lower(username))"
                        } else {
                            ""
                        }
                    connection.prepareStatement(
                        "INSERT INTO chess_account (id, platform, username, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP) " +
                            "ON CONFLICT$conflictTarget DO NOTHING",
                    ).use { statement ->
                        statement.setObject(1, UUID.randomUUID())
                        statement.setString(2, platform)
                        statement.setString(3, username)
                        statement.executeUpdate()
                    }
                },
            ) ?: throw IllegalStateException("Shared account insert returned no result")
        // A separate statement sees a concurrent winner after ON CONFLICT waits
        // for its commit under PostgreSQL's READ COMMITTED isolation.
        val account =
            chessAccountRepository.findByPlatformAndUsernameIgnoreCase(platform, username)
                ?: throw IllegalStateException("Shared account insert returned no account")
        return account to (inserted == 1)
    }

    private fun verifySnapshots(
        request: ImportGamesRequest,
        account: ChessAccount,
    ) {
        val suppliedPlatform = request.platform?.name?.let(::normalizePlatform)
        val suppliedUsername = request.normalizedUsername()
        if (suppliedPlatform != null && suppliedPlatform != account.platform) {
            throw AccountSelectionMismatchException()
        }
        if (suppliedUsername != null && !suppliedUsername.equals(account.username, ignoreCase = true)) {
            throw AccountSelectionMismatchException()
        }
    }

    private fun requireOwner(
        principal: AuthenticatedPrincipal,
        account: ChessAccount,
    ) {
        if (!connectionRepository.existsByAppUserIdAndChessAccountId(principal.appUserId, account.id)) {
            throw ForbiddenAccountException()
        }
    }

    private fun ChessAccount.toResponse(): ChessAccountResponse =
        ChessAccountResponse(
            id = id,
            platform = platform,
            username = username,
        )

    companion object {
        private val USERNAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}")
    }
}

data class AssociationResult(
    val account: ChessAccountResponse,
    val created: Boolean,
)

class AccountNotFoundException(message: String = "Account not found") : RuntimeException(message)

class ForbiddenAccountException(message: String = "The authenticated principal does not own this account") :
    RuntimeException(message)

class AccountConnectionLimitReachedException(message: String = "Disconnect the current account before connecting another account") :
    RuntimeException(message)

class AccountSelectionRequiredException(message: String = "An accountId is required for authenticated private data") :
    RuntimeException(message)

class AccountSelectionMismatchException(message: String = "The supplied account metadata does not match accountId") :
    RuntimeException(message)
