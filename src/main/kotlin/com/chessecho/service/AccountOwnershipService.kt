package com.chessecho.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Platform
import com.chessecho.dto.AccountAssociationRequest
import com.chessecho.dto.ChessAccountResponse
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.web.UnauthenticatedException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The single authorization boundary for account-backed data. A provider
 * username is useful for guest lookup, but it is never an ownership credential.
 */
@Service
class AccountOwnershipService(
    private val chessAccountRepository: ChessAccountRepository,
    private val appUserRepository: AppUserRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val operationTransaction = TransactionTemplate(transactionManager)
    private val insertTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionTemplate.PROPAGATION_REQUIRES_NEW
        }
    private val keyLocks = ConcurrentHashMap<String, Any>()

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
        val key = "${platform.lowercase(Locale.ROOT)}\u0000${username.lowercase(Locale.ROOT)}"
        val lock = keyLocks.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            operationTransaction.execute {
                val owner =
                    appUserRepository.findById(principal.appUserId)
                        .orElseThrow { AccountNotFoundException("Account owner not found") }
                val existing = chessAccountRepository.findByPlatformAndUsernameForUpdate(platform, username)
                if (existing != null) {
                    val existingOwner = existing.user
                    return@execute when {
                        existingOwner == null -> {
                            existing.user = owner
                            AssociationResult(existing.toResponse(), created = false)
                        }
                        existingOwner.id == owner.id -> AssociationResult(existing.toResponse(), created = false)
                        else -> throw AccountClaimConflictException()
                    }
                }

                try {
                    val created =
                        insertTransaction.execute {
                            chessAccountRepository.saveAndFlush(
                                ChessAccount(
                                    user = owner,
                                    platform = platform,
                                    username = username,
                                ),
                            )
                        } ?: throw IllegalStateException("Account creation transaction returned no account")
                    AssociationResult(created.toResponse(), created = true)
                } catch (_: DataIntegrityViolationException) {
                    val raced =
                        chessAccountRepository.findByPlatformAndUsernameForUpdate(platform, username)
                            ?: throw AccountClaimConflictException()
                    if (raced.user?.id == owner.id) {
                        AssociationResult(raced.toResponse(), created = false)
                    } else {
                        throw AccountClaimConflictException()
                    }
                }
            } ?: throw IllegalStateException("Account association transaction returned no result")
        }
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
            requireOwner(resolvedPrincipal, account)
            return account
        }

        if (principal != null) {
            throw AccountSelectionRequiredException()
        }
        val platform = normalizePlatform(request.platform?.name)
        val username = normalizeUsername(request.username)
        return findOrCreateUnclaimed(platform, username)
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

        if (account.user != null) throw UnauthenticatedException()
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
    fun authorizeJob(
        account: ChessAccount?,
        principal: AuthenticatedPrincipal?,
    ) {
        if (account == null) throw AccountNotFoundException("Import account is unresolved")
        if (principal == null) {
            if (account.user != null) throw UnauthenticatedException()
        } else {
            requireOwner(principal, account)
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

    private fun findOrCreateUnclaimed(
        platform: String,
        username: String,
    ): ChessAccount {
        val existing = chessAccountRepository.findByPlatformAndUsernameForUpdate(platform, username)
        if (existing != null) {
            if (existing.user != null) throw AccountClaimConflictException()
            return existing
        }

        return try {
            chessAccountRepository.saveAndFlush(
                ChessAccount(
                    user = null,
                    platform = platform,
                    username = username,
                ),
            )
        } catch (_: DataIntegrityViolationException) {
            throw AccountClaimConflictException()
        }
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
        if (account.user?.id != principal.appUserId) throw ForbiddenAccountException()
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

class AccountClaimConflictException(message: String = "The account is already owned by another principal") :
    RuntimeException(message)

class AccountSelectionRequiredException(message: String = "An accountId is required for authenticated private data") :
    RuntimeException(message)

class AccountSelectionMismatchException(message: String = "The supplied account metadata does not match accountId") :
    RuntimeException(message)
