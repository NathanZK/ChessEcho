package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ArchiveDerivedProcessing
import com.chessecho.domain.ArchiveDerivedStatus
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.ImportedArchive
import com.chessecho.repository.ArchiveDerivedProcessingRepository
import com.chessecho.repository.GameRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.stereotype.Component
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Component
class DerivedProcessingCoordinator(
    private val archiveDerivedProcessingRepository: ArchiveDerivedProcessingRepository,
    private val gameRepository: GameRepository,
    private val gameParserService: GameParserService,
    private val puzzleSchedulingEventDispatcher: PuzzleSchedulingEventDispatcher,
    private val userPositionStatsBatchService: UserPositionStatsBatchService,
    private val transactionTemplate: TransactionTemplate,
    private val dataSource: DataSource,
    @Value("\${chessecho.import.derived-game-batch-size:1000}")
    private val derivedGameBatchSize: Int = DEFAULT_DERIVED_GAME_BATCH_SIZE,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun processDerivedArchive(
        account: ChessAccount,
        initiatingUser: AppUser?,
        archive: ImportedArchive,
    ): Set<UUID> = processDerivedGames(account, initiatingUser, emptyList(), archive)

    fun processDerivedGames(
        account: ChessAccount,
        initiatingUser: AppUser?,
        savedGames: List<Game>,
        archive: ImportedArchive?,
    ): Set<UUID> {
        val claim = archive?.let(::claimDerivedProcessing) ?: if (archive == null) null else return emptySet()

        val games =
            if (archive != null) {
                gameRepository.findAllByImportedArchiveIdOrderByPlayedAtAsc(archive.id)
            } else if (savedGames.isNotEmpty()) {
                savedGames
            } else {
                emptyList()
            }
        val affected = mutableSetOf<UUID>()
        try {
            games.chunked(derivedGameBatchSize.coerceAtLeast(1)).forEach { batch ->
                val ids = gameParserService.parseAndSavePositions(batch)
                puzzleSchedulingEventDispatcher.emitSchedulingEvents(batch, initiatingUser, ids)
                userPositionStatsBatchService.updateUserPositionStats(account, ids)
                affected.addAll(ids)
                claim?.let(::renewDerivedProcessingLease)
            }
            claim?.let(::completeDerivedProcessing)
            return affected
        } catch (ex: Exception) {
            claim?.let { failDerivedProcessing(it, ex) }
            throw ex
        }
    }

    data class DerivedProcessingClaim(
        val processingId: UUID,
        val workerToken: UUID,
    )

    fun claimDerivedProcessing(archive: ImportedArchive): DerivedProcessingClaim? {
        ensureDerivedProcessingRecord(archive)
        return transactionTemplate.execute {
            val now = Instant.now()
            val record =
                archiveDerivedProcessingRepository.findByImportedArchiveIdForUpdate(archive.id)
                    ?: throw IllegalStateException(
                        "Derived processing record missing for archive ${archive.id}",
                    )

            when (record.status) {
                ArchiveDerivedStatus.COMPLETED -> return@execute null
                ArchiveDerivedStatus.PROCESSING ->
                    if (record.leaseExpiresAt?.isAfter(now) == true) {
                        return@execute null
                    }
                ArchiveDerivedStatus.PENDING, ArchiveDerivedStatus.FAILED -> Unit
            }

            val workerToken = UUID.randomUUID()
            record.status = ArchiveDerivedStatus.PROCESSING
            record.workerToken = workerToken
            record.attemptCount += 1
            record.lastError = null
            record.startedAt = now
            record.leaseExpiresAt = now.plus(DERIVED_PROCESSING_LEASE)
            record.completedAt = null
            record.updatedAt = now
            archiveDerivedProcessingRepository.saveAndFlush(record)
            DerivedProcessingClaim(record.id, workerToken)
        }
    }

    fun ensureDerivedProcessingRecord(archive: ImportedArchive) {
        if (archiveDerivedProcessingRepository.findByImportedArchive(archive) != null) return
        if (supportsConflictSafeInsert()) {
            transactionTemplate.executeWithoutResult {
                archiveDerivedProcessingRepository.insertPendingIfAbsent(
                    UUID.randomUUID(),
                    archive.id,
                    Instant.now(),
                )
            }
        } else {
            try {
                transactionTemplate.executeWithoutResult {
                    archiveDerivedProcessingRepository.saveAndFlush(
                        ArchiveDerivedProcessing(importedArchive = archive),
                    )
                }
            } catch (ex: DataIntegrityViolationException) {
                log.debug("Lost first derived-processing claim for archive {}", archive.id, ex)
            } catch (ex: UnexpectedRollbackException) {
                log.debug("First derived-processing claim for archive {} was rolled back", archive.id, ex)
            }
        }
        if (archiveDerivedProcessingRepository.findByImportedArchive(archive) == null) {
            throw IllegalStateException("Derived processing record missing for archive ${archive.id}")
        }
    }

    fun renewDerivedProcessingLease(claim: DerivedProcessingClaim) {
        transactionTemplate.executeWithoutResult {
            val record = archiveDerivedProcessingRepository.findByIdForUpdate(claim.processingId) ?: return@executeWithoutResult
            if (record.status == ArchiveDerivedStatus.PROCESSING && record.workerToken == claim.workerToken) {
                val now = Instant.now()
                record.leaseExpiresAt = now.plus(DERIVED_PROCESSING_LEASE)
                record.updatedAt = now
                archiveDerivedProcessingRepository.save(record)
            }
        }
    }

    fun completeDerivedProcessing(claim: DerivedProcessingClaim) {
        transactionTemplate.executeWithoutResult {
            val record = archiveDerivedProcessingRepository.findByIdForUpdate(claim.processingId) ?: return@executeWithoutResult
            if (record.status == ArchiveDerivedStatus.PROCESSING && record.workerToken == claim.workerToken) {
                val now = Instant.now()
                record.status = ArchiveDerivedStatus.COMPLETED
                record.completedAt = now
                record.leaseExpiresAt = null
                record.updatedAt = now
                archiveDerivedProcessingRepository.save(record)
            }
        }
    }

    fun failDerivedProcessing(
        claim: DerivedProcessingClaim,
        ex: Exception,
    ) {
        transactionTemplate.executeWithoutResult {
            val record = archiveDerivedProcessingRepository.findByIdForUpdate(claim.processingId) ?: return@executeWithoutResult
            if (record.status == ArchiveDerivedStatus.PROCESSING && record.workerToken == claim.workerToken) {
                record.status = ArchiveDerivedStatus.FAILED
                record.lastError = ex.message ?: ex.javaClass.simpleName
                record.leaseExpiresAt = null
                record.updatedAt = Instant.now()
                archiveDerivedProcessingRepository.save(record)
            }
        }
    }

    private fun supportsConflictSafeInsert(): Boolean {
        val connection = DataSourceUtils.getConnection(dataSource)
        val supported =
            try {
                connection.metaData.databaseProductName == "PostgreSQL"
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource)
            }
        return supported
    }

    companion object {
        const val DEFAULT_DERIVED_GAME_BATCH_SIZE = 1000
        private val DERIVED_PROCESSING_LEASE = Duration.ofMinutes(30)
    }
}
