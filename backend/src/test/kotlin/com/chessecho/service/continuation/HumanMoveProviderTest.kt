package com.chessecho.service.continuation

import com.chessecho.domain.HumanMoveDistribution
import com.chessecho.domain.Position
import com.chessecho.repository.HumanMoveDistributionRepository
import com.chessecho.repository.PositionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class HumanMoveProviderTest {
    @Mock
    private lateinit var positionRepository: PositionRepository

    @Mock
    private lateinit var humanMoveDistributionRepository: HumanMoveDistributionRepository

    private lateinit var humanMoveProvider: HumanMoveProvider

    private val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    private val expectedHash = "7c53dea8af10e728bbf57edf7ebc8275f0a29735bb0c9c852ee96533064271dc"
    private val positionId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        humanMoveProvider =
            HumanMoveProvider(
                positionRepository = positionRepository,
                humanMoveDistributionRepository = humanMoveDistributionRepository,
                minObservations = 10,
            )
    }

    private fun mockPosition() {
        val position = Position(id = positionId, hash = expectedHash, fen = fen)
        `when`(positionRepository.findByHash(eq(expectedHash))).thenReturn(position)
    }

    @Test
    fun `exact band with sufficient observations returns candidates ordered by frequency`() {
        mockPosition()

        val dist =
            listOf(
                HumanMoveDistribution(positionId = positionId, ratingBand = "1000-1200", movePlayed = "e5", observationCount = 50),
                HumanMoveDistribution(positionId = positionId, ratingBand = "1000-1200", movePlayed = "c5", observationCount = 100),
                HumanMoveDistribution(positionId = positionId, ratingBand = "1000-1200", movePlayed = "e6", observationCount = 10),
            )

        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "1000-1200")).thenReturn(dist)

        val candidates = humanMoveProvider.getContinuationCandidates(fen, "1000-1200")

        assertThat(candidates).hasSize(3)
        assertThat(candidates[0].move).isEqualTo("c5")
        assertThat(candidates[0].timesPlayed).isEqualTo(100)
        assertThat(candidates[0].providerType).isEqualTo("HUMAN")
        assertThat(candidates[0].resultingFen)
            .isEqualTo("rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2")

        assertThat(candidates[1].move).isEqualTo("e5")
        assertThat(candidates[1].timesPlayed).isEqualTo(50)

        assertThat(candidates[2].move).isEqualTo("e6")
        assertThat(candidates[2].timesPlayed).isEqualTo(10)
    }

    @Test
    fun `exact band with exact minimum observations boundary is eligible and returns candidate`() {
        mockPosition()

        val dist =
            listOf(
                HumanMoveDistribution(
                    positionId = positionId,
                    ratingBand = "1000-1200",
                    movePlayed = "c5",
                    observationCount = 10,
                ),
            )

        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "1000-1200")).thenReturn(dist)

        val candidates = humanMoveProvider.getContinuationCandidates(fen, "1000-1200")

        assertThat(candidates).hasSize(1)
        assertThat(candidates[0].timesPlayed).isEqualTo(10)
    }

    @Test
    fun `exact band with insufficient observations returns empty list`() {
        mockPosition()

        val dist =
            listOf(
                HumanMoveDistribution(positionId = positionId, ratingBand = "1000-1200", movePlayed = "e5", observationCount = 5),
                HumanMoveDistribution(positionId = positionId, ratingBand = "1000-1200", movePlayed = "c5", observationCount = 4),
            )

        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "1000-1200")).thenReturn(dist)
        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "800-1000")).thenReturn(emptyList())
        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "1200-1400")).thenReturn(emptyList())

        val candidates = humanMoveProvider.getContinuationCandidates(fen, "1000-1200")

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `exact insufficient but adjacent sufficient returns adjacent candidates`() {
        mockPosition()

        val exactDist =
            listOf(
                HumanMoveDistribution(positionId = positionId, ratingBand = "1000-1200", movePlayed = "e5", observationCount = 2),
            )
        val adjacentDist1 = emptyList<HumanMoveDistribution>()
        val adjacentDist2 =
            listOf(
                HumanMoveDistribution(positionId = positionId, ratingBand = "1200-1400", movePlayed = "c5", observationCount = 20),
            )

        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "1000-1200")).thenReturn(exactDist)
        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "800-1000")).thenReturn(adjacentDist1)
        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "1200-1400")).thenReturn(adjacentDist2)

        val candidates = humanMoveProvider.getContinuationCandidates(fen, "1000-1200")

        assertThat(candidates).hasSize(1)
        assertThat(candidates[0].move).isEqualTo("c5")
        assertThat(candidates[0].timesPlayed).isEqualTo(20)
    }

    @Test
    fun `insufficient exact and adjacent returns empty list`() {
        mockPosition()

        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "1000-1200")).thenReturn(emptyList())
        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "800-1000")).thenReturn(emptyList())
        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "1200-1400")).thenReturn(emptyList())

        val candidates = humanMoveProvider.getContinuationCandidates(fen, "1000-1200")

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `unknown position hash returns empty list and does not query distributions`() {
        `when`(positionRepository.findByHash(eq(expectedHash))).thenReturn(null)

        val candidates = humanMoveProvider.getContinuationCandidates(fen, "1000-1200")

        assertThat(candidates).isEmpty()
        verifyNoInteractions(humanMoveDistributionRepository)
    }

    @Test
    fun `invalid rating band returns empty list without querying repositories`() {
        val candidates = humanMoveProvider.getContinuationCandidates(fen, "invalid-band")

        assertThat(candidates).isEmpty()
        verifyNoInteractions(positionRepository)
        verifyNoInteractions(humanMoveDistributionRepository)
    }

    @Test
    fun `null rating band returns empty list without querying repositories`() {
        val candidates = humanMoveProvider.getContinuationCandidates(fen, null)

        assertThat(candidates).isEmpty()
        verifyNoInteractions(positionRepository)
        verifyNoInteractions(humanMoveDistributionRepository)
    }

    @Test
    fun `four-part FEN computes matching normalized hash and queries position repository`() {
        val fourPartFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3"
        val position = Position(id = positionId, hash = expectedHash, fen = fourPartFen)
        `when`(positionRepository.findByHash(eq(expectedHash))).thenReturn(position)
        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "1000-1200")).thenReturn(emptyList())
        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "800-1000")).thenReturn(emptyList())
        `when`(humanMoveDistributionRepository.findByPositionIdAndRatingBand(positionId, "1200-1400")).thenReturn(emptyList())

        val candidates = humanMoveProvider.getContinuationCandidates(fourPartFen, "1000-1200")

        assertThat(candidates).isEmpty()
        verify(positionRepository).findByHash(expectedHash)
    }
}
