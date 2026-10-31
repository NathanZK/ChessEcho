package com.chessecho.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceCoverageAnalysisServiceTest {
    private val service = ReferenceCoverageAnalysisService()

    @Test
    fun `calculates inclusive threshold coverage per player from distinct positions`() {
        val inReference = setOf("p1", "p2")
        val rows =
            listOf(
                row("alice", "p1", 0.30, 1, "g1"),
                row("alice", "p1", 0.80, 3, "g2"),
                row("alice", "p3", 0.50, 5, "g3"),
                row("bob", "p2", 0.50, 1, "g4"),
                row("bob", "p4", 0.80, 3, "g5"),
            )

        val result = service.analyze(ReferenceCoverageAnalysisInput(inReference, players("alice", "bob"), rows, "e6-v1"))

        val alice = result.byPlayer.getValue("alice")
        val bob = result.byPlayer.getValue("bob")
        assertEquals(PositionCoverage(2, 1, 0.5), alice.at(0.30))
        assertEquals(PositionCoverage(2, 1, 0.5), alice.at(0.50))
        assertEquals(PositionCoverage(1, 1, 1.0), alice.at(0.80))
        assertEquals(PositionCoverage(2, 1, 0.5), bob.at(0.30))
        assertEquals(PositionCoverage(2, 1, 0.5), bob.at(0.50))
        assertEquals(PositionCoverage(1, 0, 0.0), bob.at(0.80))
        assertEquals(setOf("p1", "p3"), alice.weaknessPositions.at(0.50))
        assertEquals(setOf("p1"), alice.sharedPositions.at(0.50))
    }

    @Test
    fun `uses summed pooled ratios and excludes undefined players from summaries`() {
        val rows =
            listOf(
                row("defined", "p1", 0.50, 1, "g1"),
                row("defined", "p2", 0.50, 3, "g2"),
                row("other", "p1", 0.50, 1, "g3"),
                row("undefined", "p3", 0.10, 1, "g4"),
            )

        val result =
            service.analyze(
                ReferenceCoverageAnalysisInput(
                    setOf("p1"),
                    players("defined", "other", "undefined", "zero-row"),
                    rows,
                    "e6-v1",
                ),
            )

        assertEquals(PositionCoverage(3, 2, 2.0 / 3.0), result.pooled.at(0.50))
        assertEquals(0.75, result.summaries.at(0.50).mean)
        assertEquals(0.75, result.summaries.at(0.50).median)
        assertEquals(0.5, result.summaries.at(0.50).minimum)
        assertEquals(1.0, result.summaries.at(0.50).maximum)
        assertNull(
            result.byPlayer
                .getValue("undefined")
                .at(0.50)
                .coverage,
        )
        assertEquals(PositionCoverage(0, 0, null), result.byPlayer.getValue("zero-row").at(0.50))
    }

    @Test
    fun `reports all qualified occurrences and separates repeated locations from positions and games`() {
        val rows =
            listOf(
                row("alice", "outside-r", 0.80, 5, "g1", "Nf3"),
                row("alice", "inside-r", 0.80, 8, "g1", "Nf3"),
                row("alice", "inside-r", 0.80, 2, "g2", "Nf3"),
            )

        val result = service.analyze(ReferenceCoverageAnalysisInput(setOf("inside-r"), players("alice"), rows, "e6-v1"))
        val locations = result.locations.getValue("alice").at(0.80)

        assertEquals(3, locations.occurrenceCount)
        assertEquals(2, locations.distinctPositionCount)
        assertEquals(2, locations.uniqueGameCount)
        assertEquals(setOf(2, 5, 8), locations.groupsByPreMovePly.keys)
        assertEquals("all-threshold-qualified-occurrences", locations.population)
        assertEquals("canonical-source-occurrence", locations.unit)
        assertEquals(3, locations.denominator)
        assertEquals("preMovePly", locations.groupingKey)
        assertEquals(1, locations.groupsByPreMovePly.getValue(5).numerator)
        assertEquals(PositionCoverage(2, 1, 0.5), result.byPlayer.getValue("alice").at(0.80))
    }

    @Test
    fun `counts repeated operational links once per canonical location after thresholding`() {
        val first = row("alice", "p1", 0.55, 5, "g1")
        val second = first.copy(gameId = UUID.randomUUID(), loss = 0.35)

        val result =
            service.analyze(
                ReferenceCoverageAnalysisInput(
                    setOf("p1"),
                    players("alice"),
                    listOf(first, second),
                    "e6-v1",
                ),
            )

        val lowThreshold = result.locations.getValue("alice").at(0.30)
        assertEquals(1, lowThreshold.occurrenceCount)
        assertEquals(1, lowThreshold.denominator)
        assertEquals(1, lowThreshold.uniqueGameCount)
        assertEquals(LocationGroupCount(1, 1), lowThreshold.groupsByPreMovePly.getValue(5))
        assertEquals(PositionCoverage(1, 1, 1.0), result.byPlayer.getValue("alice").at(0.30))

        val higherThreshold = result.locations.getValue("alice").at(0.50)
        assertEquals(1, higherThreshold.occurrenceCount)
        assertEquals(1, higherThreshold.denominator)
        assertEquals(1, higherThreshold.groupsByPreMovePly.getValue(5).numerator)
        assertEquals(
            0,
            result.locations
                .getValue("alice")
                .at(0.80)
                .occurrenceCount,
        )
        assertEquals(PositionCoverage(0, 0, null), result.byPlayer.getValue("alice").at(0.80))
    }

    @Test
    fun `keeps a declared player with no rows and reports undefined pooled coverage`() {
        val result = service.analyze(ReferenceCoverageAnalysisInput(setOf("p1"), players("zero-row"), emptyList(), "e6-v1"))

        EvaluationEvidenceSnapshotService.APPROVED_THRESHOLDS.forEach { threshold ->
            assertEquals(PositionCoverage(0, 0, null), result.byPlayer.getValue("zero-row").at(threshold))
            assertEquals(PositionCoverage(0, 0, null), result.pooled.at(threshold))
            assertNull(result.summaries.at(threshold).mean)
            assertNull(result.summaries.at(threshold).median)
            assertNull(result.summaries.at(threshold).minimum)
            assertNull(result.summaries.at(threshold).maximum)
            assertEquals(
                0,
                result.locations
                    .getValue("zero-row")
                    .at(threshold)
                    .occurrenceCount,
            )
        }
    }

    @Test
    fun `rejects unverified rows and preserves frozen analysis version`() {
        val row = row("alice", "p1", 0.50, 1, "g1").copy(verifiedOccurrence = false)

        try {
            service.analyze(ReferenceCoverageAnalysisInput(setOf("p1"), players("alice"), listOf(row), "e6-v1"))
            error("unverified occurrence should fail closed")
        } catch (error: ReferenceCoverageAnalysisIntegrityException) {
            assertTrue(error.message?.contains("verified occurrence") == true)
        }
    }

    @Test
    fun `accepts valid zero loss engine moves without reporting them as weakness at positive thresholds`() {
        val rows = listOf(row("alice", "pos_zero_loss", 0.0, 1, "g1"))
        val input =
            ReferenceCoverageAnalysisInput(
                referencePositions = setOf("pos_zero_loss"),
                players = players("alice"),
                rows = rows,
                analysisVersion = "e6-v1",
            )

        val result = assertDoesNotThrow { service.analyze(input) }

        val playerAnalysis = result.byPlayer.getValue("alice")
        assertFalse(playerAnalysis.weaknessPositions.at(0.30).contains("pos_zero_loss"))
        assertEquals(PositionCoverage(0, 0, null), playerAnalysis.at(0.30))
    }

    @Test
    fun `includes exact threshold boundary occurrences in shared positions`() {
        val rows = listOf(row("alice", "pos_exact", 0.50, 1, "g1"))
        val input =
            ReferenceCoverageAnalysisInput(
                referencePositions = setOf("pos_exact"),
                players = players("alice"),
                rows = rows,
                analysisVersion = "e6-v1",
            )

        val result = service.analyze(input)
        val playerAnalysis = result.byPlayer.getValue("alice")

        assertTrue(playerAnalysis.sharedPositions.at(0.50).contains("pos_exact"))
        assertFalse(playerAnalysis.sharedPositions.at(0.80).contains("pos_exact"))
        assertEquals(1, playerAnalysis.coverageByThreshold.getValue(0.50).sharedPositionCount)
    }

    @Test
    fun `rejects player map when map key does not match player declared identity`() {
        val mismatched = mapOf("alice" to EvaluationEvidencePlayer(UUID.randomUUID(), "bob"))
        val input =
            ReferenceCoverageAnalysisInput(
                referencePositions = setOf("pos1"),
                players = mismatched,
                rows = emptyList(),
                analysisVersion = "e6-v1",
            )

        val exception =
            assertThrows<ReferenceCoverageAnalysisIntegrityException> {
                service.analyze(input)
            }
        assertTrue(exception.message?.contains("declared players are invalid") == true)
    }

    @Test
    fun `rejects player map when player identity is blank`() {
        val blankPlayer = mapOf("" to EvaluationEvidencePlayer(UUID.randomUUID(), ""))
        val input =
            ReferenceCoverageAnalysisInput(
                referencePositions = setOf("pos1"),
                players = blankPlayer,
                rows = emptyList(),
                analysisVersion = "e6-v1",
            )

        val exception =
            assertThrows<ReferenceCoverageAnalysisIntegrityException> {
                service.analyze(input)
            }
        assertTrue(exception.message?.contains("declared players are invalid") == true)
    }

    private fun players(vararg names: String) =
        names.associateWith { EvaluationEvidencePlayer(UUID.nameUUIDFromBytes(it.toByteArray()), it) }

    private fun row(
        player: String,
        position: String,
        loss: Double,
        ply: Int,
        game: String,
        san: String = "e4",
    ) = ReferenceCoverageAnalysisRow(
        playerId = players(player).getValue(player).id,
        gameId = UUID.nameUUIDFromBytes(game.toByteArray()),
        occurrenceId = UUID.randomUUID(),
        positionIdentity = position,
        loss = loss,
        verifiedOccurrence = true,
        canonicalLocation =
            CanonicalOccurrenceLocation(
                UUID.nameUUIDFromBytes("run-a".toByteArray()),
                game.removePrefix("g").toInt(),
                ply,
                san,
            ),
    )
}
