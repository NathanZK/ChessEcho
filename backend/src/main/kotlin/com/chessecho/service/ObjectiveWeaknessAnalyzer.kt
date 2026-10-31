package com.chessecho.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.Platform
import com.chessecho.domain.PositionOccurrence
import com.chessecho.dto.AcceptableMove
import com.chessecho.dto.MoveBreakdown
import com.chessecho.dto.OpeningContext
import com.chessecho.dto.PhaseStats
import com.chessecho.dto.TimeControlStats
import com.chessecho.repository.WeaknessAggregation
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.max

internal data class ObjectiveWeaknessDraft(
    val positionId: UUID,
    val fen: String,
    val playerColor: String,
    val timesReached: Int,
    val mistakeCount: Int,
    val mistakeRate: Double,
    val averageLoss: Double,
    val priority: Double,
    val bestMove: String?,
    val acceptableMoves: List<AcceptableMove>,
    val movesPlayed: List<MoveBreakdown>,
    val gameUrls: List<String>,
    val openingContext: OpeningContext?,
    val evalCp: Int?,
    val lastSeenAt: Instant?,
    val timeControlStats: Map<String, TimeControlStats>? = null,
    val phaseStats: Map<String, PhaseStats>? = null,
) {
    fun positionScope(chessAccountId: UUID): PracticalEvidenceScope =
        PracticalEvidenceScope(
            chessAccountId = chessAccountId,
            positionId = positionId,
            playerColor = playerColor,
        )

    fun decisionScope(
        chessAccountId: UUID,
        san: String,
    ): PracticalEvidenceScope =
        PracticalEvidenceScope(
            chessAccountId = chessAccountId,
            positionId = positionId,
            playerColor = playerColor,
            decisionSan = san,
        )
}

internal class ObjectiveWeaknessAnalyzer(
    private val pgnHeaderTagReader: PgnHeaderTagReader = PgnHeaderTagReader(),
) {
    fun buildDraft(
        aggregation: WeaknessAggregation,
        scopedOccurrences: List<PositionOccurrence>,
        analysis: EngineAnalysis,
        minEvalLoss: Double,
        minMistakeCount: Int,
        platform: Platform,
        account: ChessAccount,
    ): ObjectiveWeaknessDraft? {
        val sortedOccurrences =
            scopedOccurrences.sortedByDescending {
                it.game.playedAt ?: it.createdAt
            }
        val lastSeenAt =
            sortedOccurrences.firstOrNull()?.let {
                it.game.playedAt ?: it.createdAt
            }

        val accumulation =
            accumulateOccurrences(
                sortedOccurrences = sortedOccurrences,
                analysis = analysis,
                aggregation = aggregation,
                minEvalLoss = minEvalLoss,
                account = account,
            )

        if (accumulation.mistakeCount < minMistakeCount) {
            return null
        }

        val rawRate = accumulation.mistakeCount.toDouble() / aggregation.timesReached
        val objectivePriority = accumulation.priorityScore * rawRate
        if (!(objectivePriority > 0.0)) {
            return null
        }

        val acceptableMoves = buildAcceptableMoves(analysis, minEvalLoss)
        val movesPlayed = buildMovesPlayed(accumulation.moveStats, minEvalLoss)
        val phaseStats = buildPhaseStats(accumulation.phaseTimesReached, accumulation.phaseMistakeCount, accumulation.phaseMistakeLoss)
        val timeControlStats = buildTimeControlStats(accumulation.tcTimesReached, accumulation.tcMistakeCount, accumulation.tcMistakeLoss)

        return ObjectiveWeaknessDraft(
            positionId = aggregation.positionId,
            fen = aggregation.fen,
            playerColor = aggregation.playerColor,
            timesReached = aggregation.timesReached,
            mistakeCount = accumulation.mistakeCount,
            mistakeRate = kotlin.math.round(rawRate * 10000.0) / 100.0,
            averageLoss =
                if (accumulation.mistakeCount > 0) {
                    accumulation.unweightedTotalLoss / accumulation.mistakeCount
                } else {
                    aggregation.averageLoss ?: 0.0
                },
            priority = objectivePriority,
            bestMove = aggregation.bestMove,
            acceptableMoves = acceptableMoves,
            movesPlayed = movesPlayed,
            gameUrls = accumulation.mistakeUrls.distinct().take(10),
            openingContext = openingContext(platform, accumulation.mistakeGames),
            evalCp = aggregation.baselineEvalCp,
            lastSeenAt = lastSeenAt,
            timeControlStats = timeControlStats,
            phaseStats = phaseStats,
        )
    }

    private fun accumulateOccurrences(
        sortedOccurrences: List<PositionOccurrence>,
        analysis: EngineAnalysis,
        aggregation: WeaknessAggregation,
        minEvalLoss: Double,
        account: ChessAccount,
    ): OccurrenceAccumulation {
        val acc = OccurrenceAccumulation()
        val bestMoveEvalCp = analysis.bestMoveEvalCp
        val bestMoveEvalMate = analysis.bestMoveEvalMate

        for (occurrence in sortedOccurrences) {
            val moveEvaluation =
                analysis.moveEvaluations.find {
                    it.move == occurrence.movePlayed
                } ?: continue
            val evalLoss =
                moveEvaluation.weaknessEvaluationLoss(bestMoveEvalCp, bestMoveEvalMate)
                    ?: continue

            val (previousLoss, previousCount) =
                acc.moveStats.getOrDefault(occurrence.movePlayed, 0.0 to 0)
            acc.moveStats[occurrence.movePlayed] =
                previousLoss + evalLoss to previousCount + 1

            val tc = occurrence.game.timeControl?.uppercase()
            val isSupportedTc = tc != null && tc in SUPPORTED_TIME_CONTROLS
            if (isSupportedTc) {
                acc.tcTimesReached[tc!!] = (acc.tcTimesReached[tc] ?: 0) + 1
            }

            val phase = classifyPhase(aggregation.fen, occurrence.plyNumber)
            acc.phaseTimesReached[phase] = (acc.phaseTimesReached[phase] ?: 0) + 1

            if (evalLoss >= minEvalLoss) {
                recordMistake(acc, occurrence, evalLoss, isSupportedTc, tc, phase, account.platform)
            }
        }
        return acc
    }

    private fun recordMistake(
        acc: OccurrenceAccumulation,
        occurrence: PositionOccurrence,
        evalLoss: Double,
        isSupportedTc: Boolean,
        tc: String?,
        phase: String,
        platform: String,
    ) {
        acc.unweightedTotalLoss += evalLoss
        val occurrenceDate = occurrence.game.playedAt ?: occurrence.createdAt
        val daysOld = ChronoUnit.DAYS.between(occurrenceDate, Instant.now())
        val weight = max(0.1, 1.0 - (daysOld / 365.0))
        acc.priorityScore += evalLoss * weight
        acc.mistakeCount++
        acc.mistakeUrls.add(gameUrl(platform, occurrence.game.platformGameId))
        acc.mistakeGames.add(occurrence.game)
        if (isSupportedTc && tc != null) {
            acc.tcMistakeCount[tc] = (acc.tcMistakeCount[tc] ?: 0) + 1
            acc.tcMistakeLoss[tc] = (acc.tcMistakeLoss[tc] ?: 0.0) + evalLoss
        }
        acc.phaseMistakeCount[phase] = (acc.phaseMistakeCount[phase] ?: 0) + 1
        acc.phaseMistakeLoss[phase] = (acc.phaseMistakeLoss[phase] ?: 0.0) + evalLoss
    }

    private fun buildAcceptableMoves(
        analysis: EngineAnalysis,
        minEvalLoss: Double,
    ): List<AcceptableMove> {
        val bestMoveEvalCp = analysis.bestMoveEvalCp
        val bestMoveEvalMate = analysis.bestMoveEvalMate
        return analysis.moveEvaluations
            .mapNotNull { moveEvaluation ->
                val loss =
                    moveEvaluation.weaknessEvaluationLoss(bestMoveEvalCp, bestMoveEvalMate)
                        ?: return@mapNotNull null
                AcceptableMove(move = moveEvaluation.move, evalLoss = loss)
            }.filter { it.evalLoss < minEvalLoss }
            .sortedBy { it.evalLoss }
    }

    private fun buildMovesPlayed(
        moveStats: Map<String, Pair<Double, Int>>,
        minEvalLoss: Double,
    ): List<MoveBreakdown> =
        moveStats.entries
            .map { (move, stats) ->
                MoveBreakdown(
                    move = move,
                    timesPlayed = stats.second,
                    averageLoss = stats.first / stats.second,
                )
            }.filter { it.averageLoss >= minEvalLoss }
            .sortedWith(
                compareByDescending<MoveBreakdown> { it.timesPlayed }
                    .thenByDescending { it.averageLoss },
            ).take(3)

    private fun buildPhaseStats(
        phaseTimesReached: Map<String, Int>,
        phaseMistakeCount: Map<String, Int>,
        phaseMistakeLoss: Map<String, Double>,
    ): Map<String, PhaseStats>? =
        phaseTimesReached
            .mapValues { (phase, reached) ->
                val mCount = phaseMistakeCount[phase] ?: 0
                val totalLoss = phaseMistakeLoss[phase] ?: 0.0
                PhaseStats(
                    timesReached = reached,
                    mistakeCount = mCount,
                    mistakeRate = if (reached > 0) mCount.toDouble() / reached else 0.0,
                    averageLoss = if (mCount > 0) totalLoss / mCount else 0.0,
                )
            }.takeIf { it.isNotEmpty() }

    private fun buildTimeControlStats(
        tcTimesReached: Map<String, Int>,
        tcMistakeCount: Map<String, Int>,
        tcMistakeLoss: Map<String, Double>,
    ): Map<String, TimeControlStats>? =
        tcTimesReached
            .mapValues { (tc, reached) ->
                val mCount = tcMistakeCount[tc] ?: 0
                val totalLoss = tcMistakeLoss[tc] ?: 0.0
                TimeControlStats(
                    timesReached = reached,
                    mistakeCount = mCount,
                    mistakeRate = if (reached > 0) mCount.toDouble() / reached else 0.0,
                    averageLoss = if (mCount > 0) totalLoss / mCount else 0.0,
                )
            }.takeIf { it.isNotEmpty() }

    fun openingContext(
        platform: Platform,
        mistakeGames: List<Game>,
    ): OpeningContext? {
        if (platform != Platform.CHESS_COM) return null

        val metadata =
            mistakeGames
                .distinctBy { it.id }
                .map { pgnHeaderTagReader.read(it.pgn) }
                .mapNotNull { tags ->
                    val eco = tags.eco?.trim()
                    val ecoUrl = tags.ecoUrl?.trim()
                    if (tags.status == PgnHeaderStatus.OK && !eco.isNullOrEmpty() && !ecoUrl.isNullOrEmpty()) {
                        eco to ecoUrl
                    } else {
                        null
                    }
                }
        if (metadata.size != mistakeGames.distinctBy { it.id }.size) return null
        val first = metadata.firstOrNull() ?: return null
        return if (metadata.all { it == first }) {
            OpeningContext(eco = first.first, ecoUrl = first.second)
        } else {
            null
        }
    }

    fun gameUrl(
        platform: String,
        platformGameId: String,
    ): String =
        when {
            platformGameId.startsWith("http") -> platformGameId
            platform == "CHESS_COM" -> "https://www.chess.com/game/live/$platformGameId"
            platform == "LICHESS" -> "https://lichess.org/$platformGameId"
            else -> platformGameId
        }

    fun classifyPhase(
        fen: String,
        plyNumber: Int,
    ): String {
        val board = fen.substringBefore(" ")
        val mmCount = board.count { it in "qQrRbBnN" }
        val moveNumber = (plyNumber + 1) / 2

        val ranks = board.split("/")
        if (ranks.size < 8) return "OPENING" // fallback
        val whiteFirstRank = ranks.last()
        val blackEighthRank = ranks.first()

        val backrankSparse = whiteFirstRank.count { it.isLetter() } < 4 || blackEighthRank.count { it.isLetter() } < 4

        if (mmCount <= 6) return "ENDGAME"
        if (mmCount <= 10 || backrankSparse || moveNumber > 14) return "MIDDLEGAME"
        return "OPENING"
    }

    private class OccurrenceAccumulation {
        var unweightedTotalLoss = 0.0
        var priorityScore = 0.0
        var mistakeCount = 0
        val mistakeUrls = mutableListOf<String>()
        val mistakeGames = mutableListOf<Game>()
        val moveStats = mutableMapOf<String, Pair<Double, Int>>()
        val tcTimesReached = mutableMapOf<String, Int>()
        val tcMistakeCount = mutableMapOf<String, Int>()
        val tcMistakeLoss = mutableMapOf<String, Double>()
        val phaseTimesReached = mutableMapOf<String, Int>()
        val phaseMistakeCount = mutableMapOf<String, Int>()
        val phaseMistakeLoss = mutableMapOf<String, Double>()
    }

    companion object {
        private val SUPPORTED_TIME_CONTROLS = setOf("BULLET", "BLITZ", "RAPID", "CLASSICAL")
    }
}
