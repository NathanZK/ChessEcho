package com.chessprobe.service.clustering

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.CommunityPatternClassification
import com.chessprobe.domain.DecisionSeverity
import com.chessprobe.domain.Game
import com.chessprobe.domain.MistakeSeverity
import com.chessprobe.domain.Position
import com.chessprobe.domain.PositionOccurrence
import com.chessprobe.domain.clustering.PositionCluster
import com.chessprobe.service.DecisionTimingPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CommunityWeaknessServiceTest {
    private val decisionTimingPolicy = DecisionTimingPolicy()
    private val communityWeaknessService = CommunityWeaknessService(decisionTimingPolicy, minCommunitySize = 10)

    @Test
    fun `profileCommunity returns null when cluster size is below minimum threshold`() {
        val cluster = createCluster(size = 9)
        val game = createGame(timeControl = "RAPID")
        val pos = createPosition()
        val occurrences = listOf(createOccurrence(game, pos))

        val result = communityWeaknessService.profileCommunity(cluster, occurrences, emptyMap())
        assertNull(result)
    }

    @Test
    fun `profileCommunity returns null when member occurrences list is empty`() {
        val cluster = createCluster(size = 10)

        val result = communityWeaknessService.profileCommunity(cluster, emptyList(), emptyMap())
        assertNull(result)
    }

    @Test
    fun `profileCommunity classifies as SINGLE_GAME_PATTERN when all occurrences are from one game`() {
        val cluster = createCluster(size = 10)
        val game = createGame(timeControl = "BLITZ")
        val occurrences = (1..10).map { ply -> createOccurrence(game, createPosition(), plyNumber = ply) }

        val result = communityWeaknessService.profileCommunity(cluster, occurrences, emptyMap())
        assertNotNull(result)
        assertEquals(CommunityPatternClassification.SINGLE_GAME_PATTERN, result!!.classification)
        assertEquals(1, result.distinctGamesCount)
    }

    @Test
    fun `profileCommunity classifies as RECURRING_WEAKNESS when occurrences span two or more games`() {
        val cluster = createCluster(size = 12)
        val game1 = createGame(timeControl = "BLITZ")
        val game2 = createGame(timeControl = "BLITZ")
        val occ1 = createOccurrence(game1, createPosition(), plyNumber = 1)
        val occ2 = createOccurrence(game2, createPosition(), plyNumber = 3)
        val occurrences = listOf(occ1, occ2)

        val result = communityWeaknessService.profileCommunity(cluster, occurrences, emptyMap())
        assertNotNull(result)
        assertEquals(CommunityPatternClassification.RECURRING_WEAKNESS, result!!.classification)
        assertEquals(2, result.distinctGamesCount)
    }

    @Test
    fun `profileCommunity computes four orthogonal dimensions accurately`() {
        val cluster = createCluster(size = 15)
        val gameBlitz1 = createGame(timeControl = "BLITZ")
        val gameBlitz2 = createGame(timeControl = "BLITZ")
        val gameRapid = createGame(timeControl = "RAPID")
        val gameUnknown = createGame(timeControl = null)

        val pos = createPosition()

        // 1. Critical blunder in Blitz with Critical decision time (e.g. 65,000ms in blitz >= 60s)
        val occ1 = createOccurrence(gameBlitz1, pos, plyNumber = 1, decisionTimeMs = 65_000L)
        // 2. Significant mistake in Blitz with Minor decision time (e.g. 20,000ms in blitz: 15s..30s)
        val occ2 = createOccurrence(gameBlitz1, pos, plyNumber = 3, decisionTimeMs = 20_000L)
        // 3. Minor inaccuracy in Rapid with Significant decision time (e.g. 80,000ms in rapid: 60s..120s)
        val occ3 = createOccurrence(gameRapid, pos, plyNumber = 5, decisionTimeMs = 80_000L)
        // 4. No concern in Blitz without decision time (null)
        val occ4 = createOccurrence(gameBlitz2, pos, plyNumber = 7, decisionTimeMs = null)
        // 5. No concern with unknown game format (null timeControl)
        val occ5 = createOccurrence(gameUnknown, pos, plyNumber = 9, decisionTimeMs = 1_000L)

        val occurrences = listOf(occ1, occ2, occ3, occ4, occ5)
        val evaluations =
            mapOf(
                occ1.id to 3.50,
                occ2.id to 1.80,
                occ3.id to 0.90,
                occ4.id to 0.40,
                occ5.id to null,
            )

        val profile = communityWeaknessService.profileCommunity(cluster, occurrences, evaluations)
        assertNotNull(profile)
        val p = profile!!

        assertEquals(15, p.memberPositionCount)
        assertEquals(5, p.totalOccurrences)
        assertEquals(4, p.distinctGamesCount)
        assertEquals(CommunityPatternClassification.RECURRING_WEAKNESS, p.classification)

        // Dimension 1: Loss distribution and percentages across MistakeSeverity
        assertEquals(1, p.evalLossDistribution[MistakeSeverity.SEVERE])
        assertEquals(1, p.evalLossDistribution[MistakeSeverity.SIGNIFICANT])
        assertEquals(1, p.evalLossDistribution[MistakeSeverity.MODERATE])
        assertEquals(1, p.evalLossDistribution[MistakeSeverity.MINOR])
        assertEquals(0, p.evalLossDistribution[MistakeSeverity.NO_CONCERN])
        assertEquals(1, p.evalLossDistribution[MistakeSeverity.UNEVALUATED])

        assertEquals(20.0, p.evalLossPercentages[MistakeSeverity.SEVERE])
        assertEquals(20.0, p.evalLossPercentages[MistakeSeverity.SIGNIFICANT])
        assertEquals(20.0, p.evalLossPercentages[MistakeSeverity.MODERATE])
        assertEquals(20.0, p.evalLossPercentages[MistakeSeverity.MINOR])
        assertEquals(0.0, p.evalLossPercentages[MistakeSeverity.NO_CONCERN])
        assertEquals(20.0, p.evalLossPercentages[MistakeSeverity.UNEVALUATED])

        // Dimension 2: Evaluated vs unevaluated counts and undiluted frequencies
        assertEquals(4, p.evaluatedOccurrences)
        assertEquals(1, p.unevaluatedOccurrences)
        assertEquals(4, p.mistakeCount)
        assertEquals(100.0, p.mistakeFrequency) // 4 mistakes / 4 evaluated occurrences = 100.0%
        assertEquals(25.0, p.blunderRate) // 1 severe / 4 evaluated occurrences = 25.0%

        // Dimension 3: Timing profile (across DecisionSeverity)
        assertEquals(1, p.timingProfile[DecisionSeverity.CRITICAL]) // 65s Blitz -> CRITICAL
        assertEquals(1, p.timingProfile[DecisionSeverity.SIGNIFICANT]) // 45s Rapid -> SIGNIFICANT
        assertEquals(1, p.timingProfile[DecisionSeverity.MINOR]) // 20s Blitz -> MINOR
        assertEquals(2, p.timingProfile[DecisionSeverity.UNKNOWN]) // occ4 (null time) + occ5 (null timeControl) -> UNKNOWN
        assertEquals(5, p.timingProfile.values.sum())

        // Dimension 4: Format breakdown
        assertEquals(3, p.formatBreakdown["BLITZ"])
        assertEquals(1, p.formatBreakdown["RAPID"])
        assertEquals(1, p.formatBreakdown["UNKNOWN"])
    }

    @Test
    fun `profileCommunity guards against zero evaluated occurrences when all moves are unevaluated`() {
        val cluster = createCluster(size = 10)
        val game1 = createGame(timeControl = "BLITZ")
        val game2 = createGame(timeControl = "BLITZ")
        val occ1 = createOccurrence(game1, createPosition(), plyNumber = 1)
        val occ2 = createOccurrence(game2, createPosition(), plyNumber = 3)
        val occurrences = listOf(occ1, occ2)

        // All evaluations null
        val evaluations = mapOf<UUID, Double?>(occ1.id to null, occ2.id to null)

        val profile = communityWeaknessService.profileCommunity(cluster, occurrences, evaluations)
        assertNotNull(profile)
        val p = profile!!

        assertEquals(0, p.evaluatedOccurrences)
        assertEquals(2, p.unevaluatedOccurrences)
        assertEquals(0, p.mistakeCount)
        assertEquals(0.0, p.mistakeFrequency)
        assertEquals(0.0, p.blunderRate)
        assertEquals(2, p.evalLossDistribution[MistakeSeverity.UNEVALUATED])
        assertEquals(100.0, p.evalLossPercentages[MistakeSeverity.UNEVALUATED])
        assertEquals(0.0, p.evalLossPercentages[MistakeSeverity.NO_CONCERN])
        assertEquals(0.0, p.evalLossPercentages[MistakeSeverity.SEVERE])
    }

    private fun createCluster(size: Int): PositionCluster =
        PositionCluster(
            id = UUID.randomUUID(),
            snapshotId = UUID.randomUUID(),
            clusterIndex = 0,
            size = size,
            centroidVector = "{}",
            createdAt = Instant.now(),
        )

    private fun createGame(timeControl: String?): Game =
        Game(
            id = UUID.randomUUID(),
            chessAccount = ChessAccount(platform = "CHESS_COM", username = "player"),
            platformGameId = UUID.randomUUID().toString(),
            pgn = "1. e4 e5",
            timeControl = timeControl,
            playedAt = Instant.now(),
        )

    private fun createPosition(): Position =
        Position(
            id = UUID.randomUUID(),
            hash = UUID.randomUUID().toString(),
            fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            createdAt = Instant.now(),
        )

    private fun createOccurrence(
        game: Game,
        position: Position,
        plyNumber: Int = 1,
        decisionTimeMs: Long? = null,
    ): PositionOccurrence =
        PositionOccurrence(
            id = UUID.randomUUID(),
            game = game,
            position = position,
            chessAccount = game.chessAccount,
            plyNumber = plyNumber,
            movePlayed = "e4",
            playerColor = "WHITE",
            decisionTimeMs = decisionTimeMs,
            remainingClockMs = null,
            createdAt = Instant.now(),
        )
}
