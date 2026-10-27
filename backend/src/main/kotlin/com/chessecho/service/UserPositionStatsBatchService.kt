package com.chessecho.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Position
import com.chessecho.domain.UserPositionStats
import com.chessecho.repository.PositionOccurrenceCount
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Component
class UserPositionStatsBatchService(
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val userPositionStatsRepository: UserPositionStatsRepository,
    private val positionRepository: PositionRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    fun updateUserPositionStats(
        account: ChessAccount,
        affectedPositionIds: Set<UUID>,
    ) {
        if (affectedPositionIds.isEmpty()) return

        val positionIdBatches = affectedPositionIds.chunked(DEFAULT_STATS_BATCH_SIZE)
        for (batch in positionIdBatches) {
            updateStatsBatch(account, batch.toSet())
        }
    }

    private fun updateStatsBatch(
        account: ChessAccount,
        batchSet: Set<UUID>,
    ) {
        transactionTemplate.executeWithoutResult {
            val occurrenceCounts =
                positionOccurrenceRepository.countOccurrencesByAccountAndPositions(
                    account.id,
                    batchSet,
                )

            val existingStats =
                userPositionStatsRepository.findByChessAccountIdAndPositionIdIn(
                    account.id,
                    batchSet,
                )
            val existingStatsMap = existingStats.associateBy { "${it.chessAccount.id}-${it.position.id}-${it.playerColor}" }
            val positions = positionRepository.findAllById(batchSet).associateBy { it.id }

            val statsUpdates =
                occurrenceCounts.map { count ->
                    buildOrUpdateStat(account, count, existingStatsMap, positions)
                }

            if (statsUpdates.isNotEmpty()) {
                userPositionStatsRepository.saveAll(statsUpdates)
            }
        }
    }

    private fun buildOrUpdateStat(
        account: ChessAccount,
        count: PositionOccurrenceCount,
        existingStatsMap: Map<String, UserPositionStats>,
        positions: Map<UUID, Position>,
    ): UserPositionStats {
        val key = "${account.id}-${count.positionId}-${count.playerColor}"
        val existing = existingStatsMap[key]

        return if (existing != null) {
            existing.timesReached = count.timesReached.toInt()
            existing.updatedAt = Instant.now()
            existing
        } else {
            val position =
                positions[count.positionId]
                    ?: throw IllegalStateException("Position not found: ${count.positionId}")
            UserPositionStats(
                chessAccount = account,
                position = position,
                playerColor = count.playerColor,
                timesReached = count.timesReached.toInt(),
                updatedAt = Instant.now(),
            )
        }
    }

    companion object {
        private const val DEFAULT_STATS_BATCH_SIZE = 1000
    }
}
