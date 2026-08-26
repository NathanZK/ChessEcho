package com.chessecho.service

import com.chessecho.domain.HumanMoveBfsSeenGame
import com.chessecho.domain.Position
import com.chessecho.domain.RatingBand
import com.chessecho.repository.HumanMoveBfsClaimConflictException
import com.chessecho.repository.HumanMoveBfsSeenGameClaimer
import com.chessecho.repository.HumanMoveBfsSeenGameRepository
import com.chessecho.repository.HumanMoveDistributionRepository
import com.chessecho.repository.PositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

/**
 * Verifies the transactional boundary of [HumanMoveBfsService.persistObservations]:
 * the atomic game-URL claim and the human_move_distribution writes must commit
 * or roll back together. Uses a real DataSource + transaction manager (H2 in
 * PostgreSQL compatibility mode) so the claim's plain `INSERT` — protected by
 * the `game_url` primary-key uniqueness constraint on
 * `human_move_bfs_seen_game` — runs against a real database inside a real
 * transaction.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(
    HumanMoveBfsService::class,
    HumanMoveBfsSeenGameClaimer::class,
    HumanMoveBfsServiceTransactionalTest.JdbcConfig::class,
)
class HumanMoveBfsServiceTransactionalTest {
    @TestConfiguration
    class JdbcConfig {
        @Bean
        fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)
    }

    @MockBean
    private lateinit var chessComClient: ChessComClient

    @Autowired
    private lateinit var service: HumanMoveBfsService

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var humanMoveDistributionRepository: HumanMoveDistributionRepository

    @Autowired
    private lateinit var seenGameRepository: HumanMoveBfsSeenGameRepository

    @BeforeEach
    fun clean() {
        humanMoveDistributionRepository.deleteAll()
        positionRepository.deleteAll()
        seenGameRepository.deleteAll()
    }

    @Test
    fun `successful batch commits URL claims and distribution rows atomically`() {
        val position = positionRepository.save(Position(hash = "hash-e4-start", fen = "startpos"))
        val observations = mapOf(Pair(position.hash, "e4") to 3)
        val fenByHash = mapOf(position.hash to position.fen)
        val batchGameUrls = setOf("http://game1", "http://game2", "http://game3")

        val rowsPersisted =
            service.persistObservations(
                targetBand = RatingBand.BAND_1000_1200,
                observations = observations,
                fenByHash = fenByHash,
                batchGameUrls = batchGameUrls,
            )

        assertEquals(1, rowsPersisted, "one distribution row for e4 at the starting position")
        assertEquals(3, seenGameRepository.count(), "all three URLs must be claimed")
        assertEquals(1, humanMoveDistributionRepository.count(), "one distribution row persisted")
    }

    @Test
    fun `claim conflict throws HumanMoveBfsClaimConflictException and aborts the batch`() {
        // Seed one URL as already claimed. The batch attempts to insert both the
        // seeded URL and a new URL; the PK constraint on human_move_bfs_seen_game
        // aborts the plain INSERT and the claimer rethrows the failure as
        // HumanMoveBfsClaimConflictException. The @Transactional boundary on
        // persistObservations marks the enclosing transaction for rollback so no
        // distribution rows are handed to human_move_distribution.
        seenGameRepository.saveAndFlush(HumanMoveBfsSeenGame(gameUrl = "http://already-claimed"))

        val position = positionRepository.save(Position(hash = "hash-e4-start", fen = "startpos"))
        val observations = mapOf(Pair(position.hash, "e4") to 2)
        val fenByHash = mapOf(position.hash to position.fen)
        val batchGameUrls = setOf("http://already-claimed", "http://new-url")

        assertThrows(HumanMoveBfsClaimConflictException::class.java) {
            service.persistObservations(
                targetBand = RatingBand.BAND_1000_1200,
                observations = observations,
                fenByHash = fenByHash,
                batchGameUrls = batchGameUrls,
            )
        }

        // No distribution rows were persisted: the claim step ran first and
        // threw before persistObservations reached its saveAll of distributions.
        // This is what makes the URL claim and the observation writes atomic —
        // an aborted claim step guarantees no orphaned observations.
        assertEquals(
            0,
            humanMoveDistributionRepository.count(),
            "no distribution rows may be persisted when the batch aborts on claim conflict",
        )
    }

    @Test
    fun `empty batch is a no-op and does not fail`() {
        val rowsPersisted =
            service.persistObservations(
                targetBand = RatingBand.BAND_1000_1200,
                observations = emptyMap(),
                fenByHash = emptyMap(),
                batchGameUrls = emptySet(),
            )
        assertEquals(0, rowsPersisted)
        assertEquals(0, seenGameRepository.count())
        assertEquals(0, humanMoveDistributionRepository.count())
    }
}
