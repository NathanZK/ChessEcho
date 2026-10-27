package com.chessecho.service

import com.chessecho.domain.HumanMoveDistribution
import com.chessecho.domain.Position
import com.chessecho.domain.RatingBand
import com.chessecho.dto.HumanMoveFinalizeRequest
import com.chessecho.repository.HumanMoveDistributionRepository
import com.chessecho.repository.PositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@DataJpaTest
@ActiveProfiles("test")
@Import(HumanMoveDistributionFinalizationService::class)
class HumanMoveDistributionFinalizationServiceTest {
    @Autowired
    private lateinit var finalizationService: HumanMoveDistributionFinalizationService

    @Autowired
    private lateinit var humanMoveDistributionRepository: HumanMoveDistributionRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    private val band = RatingBand.BAND_1000_1200.value
    private val otherBand = RatingBand.BAND_1200_1400.value

    @BeforeEach
    fun clean() {
        humanMoveDistributionRepository.deleteAll()
        positionRepository.deleteAll()
    }

    private fun newPosition(tag: String): Position =
        positionRepository.save(Position(hash = "hash-$tag-${UUID.randomUUID()}", fen = "fen-$tag"))

    private fun row(
        position: Position,
        move: String,
        count: Int,
        ratingBand: String = band,
    ): HumanMoveDistribution =
        humanMoveDistributionRepository.save(
            HumanMoveDistribution(
                positionId = position.id,
                ratingBand = ratingBand,
                movePlayed = move,
                observationCount = count,
            ),
        )

    @Test
    fun `finalize removes positions whose global sum is below the threshold`() {
        val below = newPosition("below")
        val above = newPosition("above")

        // below: 2 + 2 = 4 across two move rows, below threshold=5
        row(below, "e4", 2)
        row(below, "d4", 2)
        // above: 3 + 3 = 6 across two move rows, above threshold=5
        row(above, "e4", 3)
        row(above, "d4", 3)

        val response =
            finalizationService.finalize(
                HumanMoveFinalizeRequest(ratingBand = band, minObservations = 5),
            )

        assertEquals(band, response.ratingBand)
        assertEquals(5, response.minObservations)
        assertEquals(2, response.positionsEvaluated)
        assertEquals(1, response.positionsRemoved)
        assertEquals(2, response.rowsRemoved)
        assertEquals(1, response.positionsRetained)

        val remaining = humanMoveDistributionRepository.findAll()
        assertTrue(remaining.all { it.positionId == above.id }, "only the above-threshold position must survive")
        assertEquals(2, remaining.size, "all move rows of the retained position must be preserved")
    }

    @Test
    fun `finalize retains a position that meets the threshold from many small rows`() {
        // 5 different moves each with 1 observation => global sum = 5, meets threshold=5
        val pos = newPosition("many-small")
        listOf("a3", "b3", "c3", "d3", "e3").forEach { row(pos, it, 1) }

        val response =
            finalizationService.finalize(
                HumanMoveFinalizeRequest(ratingBand = band, minObservations = 5),
            )

        assertEquals(1, response.positionsEvaluated)
        assertEquals(0, response.positionsRemoved)
        assertEquals(0, response.rowsRemoved)
        assertEquals(1, response.positionsRetained)
        assertEquals(5, humanMoveDistributionRepository.findAll().size)
    }

    @Test
    fun `finalize is idempotent`() {
        val below = newPosition("below")
        val above = newPosition("above")
        row(below, "e4", 2)
        row(above, "e4", 10)

        val first =
            finalizationService.finalize(HumanMoveFinalizeRequest(band, minObservations = 5))
        val second =
            finalizationService.finalize(HumanMoveFinalizeRequest(band, minObservations = 5))

        assertEquals(1, first.positionsRemoved)
        assertEquals(1, first.rowsRemoved)
        assertEquals(0, second.positionsRemoved, "second finalize must remove nothing")
        assertEquals(0, second.rowsRemoved)
        assertEquals(1, second.positionsRetained)
    }

    @Test
    fun `finalize is scoped to the requested rating band`() {
        val pos = newPosition("shared")
        // 2 obs in target band (below threshold), 10 obs in other band (above)
        row(pos, "e4", 2, ratingBand = band)
        row(pos, "e4", 10, ratingBand = otherBand)

        finalizationService.finalize(HumanMoveFinalizeRequest(band, minObservations = 5))

        val remaining = humanMoveDistributionRepository.findAll()
        assertEquals(1, remaining.size, "only the target-band row must be deleted")
        assertEquals(otherBand, remaining.first().ratingBand)
        assertEquals(10, remaining.first().observationCount)
    }

    @Test
    fun `finalize preserves all move rows for a retained position`() {
        val pos = newPosition("multi-move")
        // 1 + 1 + 3 = 5, meets threshold=5; all three move rows must survive
        row(pos, "a3", 1)
        row(pos, "b3", 1)
        row(pos, "e4", 3)

        finalizationService.finalize(HumanMoveFinalizeRequest(band, minObservations = 5))

        val remaining = humanMoveDistributionRepository.findAll().sortedBy { it.movePlayed }
        assertEquals(3, remaining.size)
        assertEquals(listOf("a3", "b3", "e4"), remaining.map { it.movePlayed })
        assertEquals(listOf(1, 1, 3), remaining.map { it.observationCount })
    }

    @Test
    fun `finalize rejects an invalid rating band`() {
        assertThrows(IllegalArgumentException::class.java) {
            finalizationService.finalize(HumanMoveFinalizeRequest("not-a-band", minObservations = 5))
        }
    }

    @Test
    fun `finalize rejects a non-positive minObservations`() {
        assertThrows(IllegalArgumentException::class.java) {
            finalizationService.finalize(HumanMoveFinalizeRequest(band, minObservations = 0))
        }
    }

    @Test
    fun `finalize on an empty band is a no-op`() {
        val response =
            finalizationService.finalize(HumanMoveFinalizeRequest(band, minObservations = 5))
        assertEquals(0, response.positionsEvaluated)
        assertEquals(0, response.positionsRemoved)
        assertEquals(0, response.rowsRemoved)
        assertEquals(0, response.positionsRetained)
    }
}
