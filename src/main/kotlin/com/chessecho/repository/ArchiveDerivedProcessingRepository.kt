package com.chessecho.repository

import com.chessecho.domain.ArchiveDerivedProcessing
import com.chessecho.domain.ArchiveDerivedStatus
import com.chessecho.domain.ImportedArchive
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

data class ArchiveDerivedStatusView(
    val yearMonth: String,
    val status: ArchiveDerivedStatus,
)

interface ArchiveDerivedProcessingRepository : JpaRepository<ArchiveDerivedProcessing, UUID> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            INSERT INTO archive_derived_processing (id, imported_archive_id, status, attempt_count, updated_at)
            VALUES (:id, :importedArchiveId, 'PENDING', 0, :updatedAt)
            ON CONFLICT (imported_archive_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertPendingIfAbsent(
        @Param("id") id: UUID,
        @Param("importedArchiveId") importedArchiveId: UUID,
        @Param("updatedAt") updatedAt: Instant,
    ): Int

    fun findByImportedArchive(importedArchive: ImportedArchive): ArchiveDerivedProcessing?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ArchiveDerivedProcessing p WHERE p.importedArchive.id = :importedArchiveId")
    fun findByImportedArchiveIdForUpdate(
        @Param("importedArchiveId") importedArchiveId: UUID,
    ): ArchiveDerivedProcessing?

    /**
     * Derived-processing status paired with the archive month it belongs to, so
     * callers can scope the result to a specific requested month range without
     * traversing a lazy association outside a transaction.
     */
    @Query(
        """
        SELECT new com.chessecho.repository.ArchiveDerivedStatusView(a.yearMonth, p.status)
        FROM ArchiveDerivedProcessing p
        JOIN p.importedArchive a
        WHERE a.chessAccount.id = :chessAccountId
        """,
    )
    fun findDerivedStatusesByChessAccountId(
        @Param("chessAccountId") chessAccountId: UUID,
    ): List<ArchiveDerivedStatusView>

    fun findByStatusIn(statuses: Collection<ArchiveDerivedStatus>): List<ArchiveDerivedProcessing>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ArchiveDerivedProcessing p WHERE p.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: UUID,
    ): ArchiveDerivedProcessing?
}
