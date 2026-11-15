package com.chessprobe.repository

import com.chessprobe.AbstractIntegrationTest
import com.chessprobe.domain.AppUser
import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.EngineAnalysis
import com.chessprobe.domain.Game
import com.chessprobe.domain.Position
import com.chessprobe.domain.PositionOccurrence
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.UUID

class RepositoryDeterministicTotalOrderingSliceTest : AbstractIntegrationTest() {
    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    private val tenantsToClean = mutableListOf<Pair<AppUser?, ChessAccount?>>()

    @AfterEach
    fun teardownTenants() {
        tenantsToClean.forEach { (user, account) ->
            val context = cleanTenantData(userId = user?.id, accountId = account?.id)
            assertTenantQuarantined(context)
        }
        tenantsToClean.clear()
    }

    @Test
    fun `PositionOccurrenceRepository findLongDecisionOccurrences breaks timestamp ties deterministically by po id ASC`() {
        val account =
            chessAccountRepository.saveAndFlush(
                ChessAccount(
                    platform = "CHESS_COM",
                    username = "tiebreaker-tester-${UUID.randomUUID().toString().take(8)}",
                ),
            )
        tenantsToClean.add(null to account)

        val fixedPlayedAt = Instant.parse("2024-06-01T12:00:00Z")
        val fixedDecisionTime = 5000L

        val occurrences =
            (1..10).map { i ->
                val position =
                    positionRepository.saveAndFlush(
                        Position(
                            hash = "hash-tie-${UUID.randomUUID()}",
                            fen = "8/8/8/8/8/8/8/$i w - - 0 1",
                        ),
                    )
                val game =
                    gameRepository.saveAndFlush(
                        Game(
                            chessAccount = account,
                            platformGameId = "tie-game-$i-${UUID.randomUUID()}",
                            pgn = "1. e4",
                            timeControl = "RAPID",
                            playedAt = fixedPlayedAt,
                        ),
                    )
                positionOccurrenceRepository.saveAndFlush(
                    PositionOccurrence(
                        game = game,
                        position = position,
                        chessAccount = account,
                        plyNumber = 1,
                        movePlayed = "e4",
                        playerColor = "WHITE",
                        decisionTimeMs = fixedDecisionTime,
                    ),
                )
            }

        val page =
            positionOccurrenceRepository.findLongDecisionOccurrences(
                chessAccountId = account.id,
                timeControl = "RAPID",
                effectiveMinMs = 1000L,
                effectiveMaxMs = null,
                pageable = PageRequest.of(0, 20),
            )

        val returnedIds = page.content.map { it.id }
        val expectedSortedIds = occurrences.map { it.id }.sortedBy { it.toString() }

        assertEquals(10, returnedIds.size)
        assertEquals(expectedSortedIds, returnedIds)
    }

    @Test
    fun `EngineAnalysisRepository findByPositionIdInWithMoveEvaluations orders batch results deterministically by e id ASC`() {
        val insertedAnalyses =
            (1..10).map { i ->
                val position =
                    positionRepository.saveAndFlush(
                        Position(
                            hash = "hash-engine-${UUID.randomUUID()}",
                            fen = "8/8/8/8/8/8/8/P$i w - - 0 1",
                        ),
                    )
                engineAnalysisRepository.saveAndFlush(
                    EngineAnalysis(
                        position = position,
                        depth = 12,
                        bestMove = "e4",
                    ),
                )
            }

        val positionIds = insertedAnalyses.map { it.position.id }.toSet()
        val results = engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(positionIds)

        val returnedIds = results.map { it.id }
        val expectedSortedIds = insertedAnalyses.map { it.id }.sortedBy { it.toString() }

        assertEquals(10, returnedIds.size)
        assertEquals(expectedSortedIds, returnedIds)
    }
}
