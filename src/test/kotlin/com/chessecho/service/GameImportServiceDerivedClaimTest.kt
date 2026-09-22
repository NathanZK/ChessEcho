package com.chessecho.service

import com.chessecho.domain.ArchiveDerivedProcessing
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.ImportedArchive
import com.chessecho.repository.ArchiveDerivedProcessingRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationTargetException
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic coverage of the first derived-processing claim on datasources
 * that cannot run `INSERT ... ON CONFLICT DO NOTHING`.
 *
 * The PostgreSQL branch of the same race is proven against a real database in
 * `DerivedProcessingPostgresConcurrencyTest`. Here the loser's exact interleaving
 * is forced rather than raced: the existence check sees nothing, the insert loses
 * to a winner that committed in between, and the claim must converge on the
 * winner's row instead of failing or retrying the same doomed insert.
 */
class GameImportServiceDerivedClaimTest {
    private val archiveDerivedProcessingRepository = mock<ArchiveDerivedProcessingRepository>()
    private val account = ChessAccount(platform = "CHESS_COM", username = "claimer")
    private val archive =
        ImportedArchive(
            chessAccount = account,
            archiveUrl = "https://api.chess.com/pub/player/claimer/games/2024/01",
            yearMonth = "2024-01",
        )

    @Test
    fun `losing the first claim converges on the winner's record`() {
        val winner = ArchiveDerivedProcessing(importedArchive = archive)
        whenever(archiveDerivedProcessingRepository.findByImportedArchive(archive))
            .thenReturn(null, winner)
        whenever(archiveDerivedProcessingRepository.saveAndFlush(any<ArchiveDerivedProcessing>()))
            .thenThrow(DataIntegrityViolationException("uk_archive_derived_processing_archive"))

        ensureDerivedProcessingRecord()

        verify(archiveDerivedProcessingRepository, times(1)).saveAndFlush(any<ArchiveDerivedProcessing>())
        verify(archiveDerivedProcessingRepository, times(2)).findByImportedArchive(archive)
    }

    @Test
    fun `a rolled back first claim also converges on the winner's record`() {
        val winner = ArchiveDerivedProcessing(importedArchive = archive)
        whenever(archiveDerivedProcessingRepository.findByImportedArchive(archive))
            .thenReturn(null, winner)

        ensureDerivedProcessingRecord(commitFailure = UnexpectedRollbackException("marked rollback-only"))

        verify(archiveDerivedProcessingRepository, times(1)).saveAndFlush(any<ArchiveDerivedProcessing>())
    }

    @Test
    fun `a vanished record is surfaced instead of silently skipping derived processing`() {
        whenever(archiveDerivedProcessingRepository.findByImportedArchive(archive)).thenReturn(null, null)
        whenever(archiveDerivedProcessingRepository.saveAndFlush(any<ArchiveDerivedProcessing>()))
            .thenThrow(DataIntegrityViolationException("uk_archive_derived_processing_archive"))

        val failure = runCatching { ensureDerivedProcessingRecord() }.exceptionOrNull()

        val cause = (failure as? InvocationTargetException)?.targetException ?: failure
        assertTrue(cause is IllegalStateException, "expected the missing record to be reported, got $cause")
        assertEquals("Derived processing record missing for archive ${archive.id}", cause.message)
    }

    private fun ensureDerivedProcessingRecord(commitFailure: RuntimeException? = null) {
        val service = service(commitFailure)
        val method =
            GameImportService::class.java
                .getDeclaredMethod("ensureDerivedProcessingRecord", ImportedArchive::class.java)
                .apply { isAccessible = true }
        method.invoke(service, archive)
    }

    private fun service(commitFailure: RuntimeException? = null): GameImportService {
        val transactionManager = mock<PlatformTransactionManager>()
        whenever(transactionManager.getTransaction(any())).thenReturn(SimpleTransactionStatus())
        commitFailure?.let { whenever(transactionManager.commit(any())).thenThrow(it) }
        val metadata = mock<java.sql.DatabaseMetaData>()
        whenever(metadata.databaseProductName).thenReturn("H2")
        val connection = mock<java.sql.Connection>()
        whenever(connection.metaData).thenReturn(metadata)
        val dataSource = mock<DataSource>()
        whenever(dataSource.connection).thenReturn(connection)

        return GameImportService(
            asyncJobRepository = mock(),
            chessAccountRepository = mock(),
            gameRepository = mock(),
            importedArchiveRepository = mock(),
            archiveDerivedProcessingRepository = archiveDerivedProcessingRepository,
            chessComClient = mock(),
            gameParserService = mock(),
            userPositionStatsRepository = mock(),
            positionOccurrenceRepository = mock(),
            positionRepository = mock(),
            engineAnalysisOrchestrator = mock(),
            transactionTemplate = TransactionTemplate(transactionManager),
            accountOwnershipService = mock(),
            engineAnalysisRepository = mock(),
            userPositionWeaknessRepository = mock(),
            puzzleSchedulingEventRepository = mock(),
            dataSource = dataSource,
        )
    }
}
