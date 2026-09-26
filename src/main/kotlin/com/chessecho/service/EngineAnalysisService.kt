package com.chessecho.service

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

@Service
class EngineAnalysisService(
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val stockfishService: StockfishService,
    private val transactionTemplate: TransactionTemplate,
    @Value("\${engine.analysis.multi-pv:5}")
    private val multiPv: Int = DEFAULT_MULTI_PV,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_MULTI_PV = 5
        private const val UNIQUE_VIOLATION_SQL_STATE = "23505"

        // Must match `uk_engine_analysis_position` in db/migration/V1__baseline.sql. Distinguishing this
        // specific constraint - not merely SQLState 23505 - matters because the parent-plus-children
        // saveAndFlush below could, in principle, also raise 23505 from `uk_engine_move_evaluation_analysis_move`
        // (e.g. a future bug breaking the `mergedCandidates.distinct()` invariant). Treating *any* 23505 as
        // the expected position_id race would silently mask that as a resolved race instead of surfacing it.
        private const val ENGINE_ANALYSIS_POSITION_UNIQUE_CONSTRAINT = "uk_engine_analysis_position"
    }

    /**
     * Executes Stockfish engine analysis for a single position.
     *
     * Architectural Invariants & Key Design Rationale:
     * 1. Query & Transaction Optimization:
     *    Receives an already-managed [Position] entity from the orchestrator, avoiding a per-position `findById` DB lookup.
     *    Calls `findByPositionIdWithMoveEvaluations()` to load the existing analysis and its associated [MoveEvaluation]
     *    collection in a single `LEFT JOIN FETCH` query, allowing already-evaluated moves to be determined in memory.
     *
     * 2. Baseline vs Candidate Moves (Historical + MultiPV Engine Moves):
     *    The original position is analyzed independently to establish the engine's objective baseline evaluation,
     *    best move, and best-move score. Historical moves obtained from [PositionOccurrence] and top-N engine candidates
     *    obtained via Stockfish MultiPV search are merged and deduplicated. Every candidate is evaluated relative to that objective baseline.
     *
     * 3. Candidate Persistence & Weakness Tracking:
     *    Every evaluated candidate move MUST be persisted in [MoveEvaluation]. Severe blunders MUST NOT be
     *    discarded based on evaluation loss because historical blunders are essential evidence for ChessEcho's
     *    weakness detection algorithm. `evalLossFromBest` is an objective numeric measurement, not a binary
     *    acceptable/unacceptable classification.
     *
     * 4. Incremental Analysis:
     *    Engine analysis is global per position, while historical moves accumulate over time as more games are imported.
     *    For existing [EngineAnalysis] entities, only unanalyzed candidates (`mergedCandidates - evaluatedMoves`)
     *    are sent to Stockfish. The existing baseline evaluation is reused and is NOT recomputed.
     *
     * 5. Concurrency & Transaction Boundaries:
     *    Stockfish computation is deliberately performed with no database transaction open around it: the method
     *    only takes short, independent transactions (via [transactionTemplate]) immediately before and after the
     *    engine call, so expensive engine work never holds a database connection or lock. Persistence uses
     *    conflict-safe recovery instead of a single all-encompassing lock:
     *      - Creating the first [EngineAnalysis] for a position is attempted as one atomic parent+children write;
     *        a concurrent creator losing that race is detected via the `position_id` unique constraint and
     *        recovers by re-reading the authoritative persisted analysis instead of continuing from stale state.
     *      - Filling in still-missing [MoveEvaluation] rows against an authoritative (whether pre-existing or
     *        just-adopted) analysis never reassigns/cascades a stale fetched `moveEvaluations` collection (which
     *        would risk deleting a sibling worker's concurrently-inserted row via cascade `orphanRemoval`).
     *        Instead each required row is inserted individually via a conflict-safe, idempotent insert, letting
     *        unrelated moves and unrelated positions proceed fully concurrently.
     */
    fun analyzePosition(position: Position) {
        analyzePosition(position, null)
    }

    fun analyzePosition(
        position: Position,
        analysisMultiPv: Int?,
    ) {
        val totalStart = System.currentTimeMillis()
        val positionId = position.id
        val snapshot = readSnapshot(positionId) ?: return

        val depth = 16
        val effectiveMultiPv = analysisMultiPv ?: multiPv

        // 1. BEFORE MultiPV analysis
        log.info(
            "BEFORE MultiPV analysis: positionId={} multiPv={} depth={}",
            positionId,
            effectiveMultiPv,
            depth,
        )
        log.debug("MultiPV analysis position FEN: positionId={} fen='{}'", positionId, position.fen)

        val multiPvStart = System.currentTimeMillis()
        val engineCandidates = stockfishService.analyzeMultiPv(position.fen, depth, effectiveMultiPv)
        val multiPvDurationMs = System.currentTimeMillis() - multiPvStart

        val engineCandidateMoves = engineCandidates.map { it.move }
        val multiPvScoreMap = engineCandidates.associate { it.move to it.score }

        // AFTER MultiPV analysis
        log.info(
            "AFTER MultiPV analysis: positionId={} count={} durationMs={}",
            positionId,
            engineCandidates.size,
            multiPvDurationMs,
        )

        val historicalMoves = snapshot.historicalMoves
        val existingAnalysis = snapshot.existingAnalysis
        val historicalSet = historicalMoves.toSet()
        val engineSet = engineCandidateMoves.toSet()

        val mergedCandidates = (historicalMoves + engineCandidateMoves).distinct()
        val remainingHistoricalMoves = historicalMoves.filter { it !in multiPvScoreMap }

        // Candidate merge summary logging (INFO for aggregate counts, DEBUG for full move lists)
        log.info(
            "Candidate merge summary: positionId={} historicalCount={} engineCount={} mergedCount={} secondaryAnalysisCount={}",
            positionId,
            historicalMoves.size,
            engineCandidateMoves.size,
            mergedCandidates.size,
            remainingHistoricalMoves.size,
        )
        log.debug(
            "Candidate merge details: positionId={} engineCandidates={} historicalCandidates={} " +
                "reusedFromMultiPv={} requiringIndividualAnalysis={}",
            positionId,
            engineCandidateMoves,
            historicalMoves,
            engineCandidateMoves,
            remainingHistoricalMoves,
        )

        mergedCandidates.forEach { move ->
            val origin =
                when {
                    move in historicalSet && move in engineSet -> "both"
                    move in historicalSet -> "historical"
                    else -> "engine"
                }
            val evalSource = if (move in multiPvScoreMap) "MULTIPV" else "HISTORICAL_SINGLE"
            log.debug("Merged candidate origin: positionId={} move={} origin={} evalSource={}", positionId, move, origin, evalSource)
        }

        if (existingAnalysis == null) {
            log.info("Performing full engine analysis for new position $positionId")

            val rank1Candidate = engineCandidates.firstOrNull()

            val evaluatedMap = mutableMapOf<String, EvalScore>()
            var bestMove: String? = null
            var bestMoveEvalCp: Int? = null
            var candidateEvalDurationMs = 0L

            if (rank1Candidate != null) {
                // Primary path: MultiPV search returned engine candidates (rank 1 = best move)
                bestMove = rank1Candidate.move
                bestMoveEvalCp = rank1Candidate.score.cp
                engineCandidates.forEach { candidate ->
                    evaluatedMap[candidate.move] = candidate.score
                }

                log.info(
                    "BEFORE baseline Stockfish analysis: positionId={} bestMove={} baselineEvalCp={} depth={}",
                    positionId,
                    bestMove,
                    bestMoveEvalCp,
                    depth,
                )

                if (remainingHistoricalMoves.isNotEmpty()) {
                    log.info(
                        "BEFORE candidate evaluation: positionId={} candidateCount={}",
                        positionId,
                        remainingHistoricalMoves.size,
                    )
                    log.debug("Candidates requiring secondary analysis: positionId={} moves={}", positionId, remainingHistoricalMoves)
                    val evalStart = System.currentTimeMillis()
                    val remainingResults = stockfishService.analyze(position.fen, depth, remainingHistoricalMoves)
                    candidateEvalDurationMs = System.currentTimeMillis() - evalStart
                    remainingHistoricalMoves.forEach { move ->
                        val result = remainingResults[move]
                        if (result != null) {
                            evaluatedMap[move] = result.score
                        }
                    }
                } else {
                    log.info(
                        "Skipping secondary Stockfish search: all historical candidates captured in MultiPV top-N for positionId={}",
                        positionId,
                    )
                }
            } else {
                // Fallback path: MultiPV returned empty list, run full stockfishService.analyze
                log.info("BEFORE baseline Stockfish analysis: positionId={} depth={}", positionId, depth)
                log.info(
                    "BEFORE candidate evaluation: positionId={} candidateCount={}",
                    positionId,
                    mergedCandidates.size,
                )
                log.debug("Fallback candidate moves: positionId={} moves={}", positionId, mergedCandidates)
                val evalStart = System.currentTimeMillis()
                val analysisResults = stockfishService.analyze(position.fen, depth, mergedCandidates)
                candidateEvalDurationMs = System.currentTimeMillis() - evalStart

                val baselineResult =
                    analysisResults["baseline"]
                        ?: throw IllegalStateException("Baseline analysis missing for position $positionId")
                bestMove = baselineResult.bestMove
                bestMoveEvalCp = analysisResults[bestMove]?.score?.cp ?: baselineResult.score.cp

                mergedCandidates.forEach { move ->
                    val result = analysisResults[move]
                    if (result != null) {
                        evaluatedMap[move] = result.score
                    }
                }
            }

            // AFTER baseline Stockfish analysis
            log.info(
                "Baseline analysis completed: positionId={} bestMove={} baselineEvalCp={} depth={} durationMs={}",
                positionId,
                bestMove,
                bestMoveEvalCp,
                depth,
                candidateEvalDurationMs,
            )

            val candidateAnalysis =
                EngineAnalysis(
                    position = position,
                    depth = depth,
                    baselineEvalCp = bestMoveEvalCp,
                    bestMove = bestMove ?: "",
                    bestMoveEvalCp = bestMoveEvalCp,
                    analyzedAt = Instant.now(),
                )

            mergedCandidates.forEach { move ->
                val score = evaluatedMap[move]
                if (score != null) {
                    val evalLoss = calculateEvalLoss(bestMoveEvalCp, score.cp) ?: 0.0
                    val evalStr = score.cp?.let { "${it}cp" } ?: score.mate?.let { "mate $it" } ?: "N/A"
                    val origin =
                        when {
                            move in historicalSet && move in engineSet -> "both"
                            move in historicalSet -> "historical"
                            else -> "engine"
                        }
                    val evalSource = if (move in multiPvScoreMap) "MULTIPV" else "HISTORICAL_SINGLE"
                    log.debug(
                        "Evaluated candidate: positionId={} move={} origin={} evalSource={} eval={} evalLossFromBest={}",
                        positionId,
                        move,
                        origin,
                        evalSource,
                        evalStr,
                        evalLoss,
                    )
                    candidateAnalysis.moveEvaluations.add(
                        MoveEvaluation(
                            engineAnalysis = candidateAnalysis,
                            move = move,
                            evalCp = score.cp,
                            evalLossFromBest = evalLoss,
                        ),
                    )
                }
            }

            log.info(
                "Candidate evaluation completed: positionId={} count={} durationMs={}",
                positionId,
                candidateAnalysis.moveEvaluations.size,
                candidateEvalDurationMs,
            )

            // BEFORE/AFTER MoveEvaluation persistence
            log.info(
                "BEFORE MoveEvaluation persistence: positionId={} count={}",
                positionId,
                candidateAnalysis.moveEvaluations.size,
            )
            log.debug("Moves to persist: positionId={} moves={}", positionId, candidateAnalysis.moveEvaluations.map { it.move })

            val outcome = createOrAdoptAnalysis(candidateAnalysis, positionId)
            if (!outcome.wonCreationRace) {
                // Another worker already committed the authoritative parent (and, atomically, its own
                // children) before this attempt reached the database. Adopt its persisted identity and
                // baseline rather than continuing from this worker's own pre-race computation, and make
                // sure any candidate this worker evaluated - but the winner did not persist - still lands.
                completeMissingMoveEvaluations(outcome.analysis, mergedCandidates, evaluatedMap)
            }

            log.info(
                "AFTER MoveEvaluation persistence: positionId={} analysisId={} wonCreationRace={}",
                positionId,
                outcome.analysis.id,
                outcome.wonCreationRace,
            )
        } else {
            // Determine evaluated moves in memory from pre-fetched collection
            val evaluatedMoves = existingAnalysis.moveEvaluations.map { it.move }.toSet()
            val missingMoves = mergedCandidates.filter { it !in evaluatedMoves }

            if (missingMoves.isEmpty()) {
                log.debug("Position $positionId has no missing moves to analyze")
                val totalDurationMs = System.currentTimeMillis() - totalStart
                log.info("Total position analysis completed: positionId={} durationMs={}", positionId, totalDurationMs)
                return
            }

            log.info("Analyzing ${missingMoves.size} missing moves for position $positionId")

            val evaluatedMap = mutableMapOf<String, EvalScore>()

            missingMoves.forEach { move ->
                if (move in multiPvScoreMap) {
                    evaluatedMap[move] = multiPvScoreMap[move]!!
                }
            }

            val missingFromMultiPv = missingMoves.filter { it !in multiPvScoreMap }
            var candidateEvalDurationMs = 0L
            if (missingFromMultiPv.isNotEmpty()) {
                // BEFORE candidate evaluation (incremental)
                log.info(
                    "BEFORE candidate evaluation (incremental): positionId={} candidateCount={}",
                    positionId,
                    missingFromMultiPv.size,
                )
                log.debug("Missing candidate moves requiring secondary analysis: positionId={} moves={}", positionId, missingFromMultiPv)

                val evalStart = System.currentTimeMillis()
                val analysisResults = stockfishService.analyze(position.fen, depth, missingFromMultiPv)
                candidateEvalDurationMs = System.currentTimeMillis() - evalStart

                missingFromMultiPv.forEach { move ->
                    val result = analysisResults[move]
                    if (result != null) {
                        evaluatedMap[move] = result.score
                    }
                }
            }

            // AFTER candidate analysis (log per evaluated candidate at DEBUG level)
            missingMoves.forEach { move ->
                val score = evaluatedMap[move]
                if (score != null) {
                    val evalLoss = calculateEvalLoss(existingAnalysis.bestMoveEvalCp, score.cp) ?: 0.0
                    val evalStr = score.cp?.let { "${it}cp" } ?: score.mate?.let { "mate $it" } ?: "N/A"
                    val origin =
                        when {
                            move in historicalSet && move in engineSet -> "both"
                            move in historicalSet -> "historical"
                            else -> "engine"
                        }
                    val evalSource = if (move in multiPvScoreMap) "MULTIPV" else "HISTORICAL_SINGLE"
                    log.debug(
                        "Evaluated candidate (incremental): positionId={} move={} origin={} evalSource={} eval={} evalLossFromBest={}",
                        positionId,
                        move,
                        origin,
                        evalSource,
                        evalStr,
                        evalLoss,
                    )
                }
            }

            log.info(
                "Candidate evaluation completed: positionId={} count={} durationMs={}",
                positionId,
                evaluatedMap.size,
                candidateEvalDurationMs,
            )

            // BEFORE/AFTER MoveEvaluation persistence
            log.info(
                "BEFORE MoveEvaluation persistence: positionId={} count={}",
                positionId,
                missingMoves.size,
            )
            log.debug("Missing moves to persist: positionId={} moves={}", positionId, missingMoves)

            completeMissingMoveEvaluations(existingAnalysis, missingMoves, evaluatedMap)

            log.info(
                "AFTER MoveEvaluation persistence: positionId={} analysisId={}",
                positionId,
                existingAnalysis.id,
            )
        }

        val totalDurationMs = System.currentTimeMillis() - totalStart
        val avoidedSearches = mergedCandidates.size - remainingHistoricalMoves.size
        log.info(
            "Optimization summary: positionId={} totalCandidates={} reusedFromMultiPv={} " +
                "requiringIndividualAnalysis={} avoidedStockfishSearches={} totalDurationMs={}",
            positionId,
            mergedCandidates.size,
            engineCandidateMoves.size,
            remainingHistoricalMoves.size,
            avoidedSearches,
            totalDurationMs,
        )
    }

    /**
     * Short, independent read-only snapshot of the state needed to decide what Stockfish work is required.
     * Deliberately its own transaction so it does not remain open across the (potentially slow) Stockfish
     * calls that follow.
     */
    private fun readSnapshot(positionId: UUID): AnalysisSnapshot? =
        transactionTemplate.execute {
            val historicalMoves = positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)
            if (historicalMoves.isEmpty()) {
                null
            } else {
                AnalysisSnapshot(historicalMoves, engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId))
            }
        }

    /**
     * Attempts to atomically create the first [EngineAnalysis] (with all of this worker's evaluated
     * candidates as children) for a position that had no analysis at the start of this call.
     *
     * A concurrent creator can win the same race: the `position_id` unique constraint turns that into a
     * [DataIntegrityViolationException] here rather than two persisted parents. The loser recovers in a
     * fresh, healthy transaction by re-reading the authoritative persisted analysis - it never continues
     * using its own pre-race candidate as if it had been persisted.
     */
    private fun createOrAdoptAnalysis(
        candidateAnalysis: EngineAnalysis,
        positionId: UUID,
    ): CreationOutcome =
        try {
            transactionTemplate.executeWithoutResult { engineAnalysisRepository.saveAndFlush(candidateAnalysis) }
            CreationOutcome(candidateAnalysis, wonCreationRace = true)
        } catch (ex: DataIntegrityViolationException) {
            if (!isUniqueConstraintViolation(ex)) {
                // A genuine persistence failure (e.g. a real constraint/trigger failure unrelated to the
                // expected position_id race) must remain observable, not be treated as a resolved race.
                throw ex
            }
            log.info(
                "Lost EngineAnalysis creation race for position {}; adopting the authoritative persisted analysis",
                positionId,
            )
            val authoritative =
                transactionTemplate.execute { engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId) }
                    ?: throw IllegalStateException(
                        "Expected an authoritative EngineAnalysis for position $positionId after a creation-race conflict",
                    )
            CreationOutcome(authoritative, wonCreationRace = false)
        }

    /**
     * Inserts any [MoveEvaluation] rows still required by `requiredMoves`/`evaluatedMap` against the given
     * (authoritative) [EngineAnalysis], using its persisted baseline as the reference for evaluation loss.
     *
     * Deliberately never reassigns or saves `analysis.moveEvaluations`: doing so through JPA cascade would
     * treat this worker's possibly-stale fetched collection as authoritative and, because of
     * `orphanRemoval`, could delete a sibling worker's concurrently-inserted row. Each required row is
     * instead inserted individually through a conflict-safe, idempotent insert, so unrelated moves - and a
     * competing worker targeting the very same move - can complete independently without serializing on
     * this or unrelated positions/analyses.
     */
    private fun completeMissingMoveEvaluations(
        analysis: EngineAnalysis,
        requiredMoves: List<String>,
        evaluatedMap: Map<String, EvalScore>,
    ) {
        val alreadyPersisted = analysis.moveEvaluations.map { it.move }.toSet()
        val requiredMovesWithScores = requiredMoves.filter { evaluatedMap.containsKey(it) }
        val stillMissing = requiredMovesWithScores.filter { it !in alreadyPersisted }
        if (stillMissing.isEmpty()) return

        transactionTemplate.executeWithoutResult {
            stillMissing.forEach { move ->
                val score = evaluatedMap.getValue(move)
                val evalLoss = calculateEvalLoss(analysis.bestMoveEvalCp, score.cp) ?: 0.0
                engineAnalysisRepository.insertMoveEvaluationIfAbsent(
                    UUID.randomUUID(),
                    analysis.id,
                    move,
                    score.cp,
                    evalLoss,
                )
            }

            // Never trust ON CONFLICT DO NOTHING's per-call outcome alone: each insert's return value only
            // tells this worker whether *it* won that individual row, not whether the row now exists (a
            // concurrent writer may have won it instead). `clearAutomatically = true` on the insert clears
            // this transaction's persistence context after every call, so the read below is a fresh,
            // authoritative hit against the database within this same healthy transaction - proving every
            // move this worker actually produced an EvalScore for is now persisted, whoever wrote it.
            val persisted =
                engineAnalysisRepository.findByIdWithMoveEvaluations(analysis.id)
                    ?: throw IllegalStateException(
                        "EngineAnalysis ${analysis.id} vanished while completing move evaluations",
                    )
            val persistedMoves = persisted.moveEvaluations.map { it.move }.toSet()
            val stillAbsent = requiredMovesWithScores.filterNot { it in persistedMoves }
            check(stillAbsent.isEmpty()) {
                "Move evaluations still missing for EngineAnalysis ${analysis.id} after conflict-safe " +
                    "insert and authoritative re-read: $stillAbsent"
            }
        }
    }

    /**
     * Walks the exception's cause chain looking for the PostgreSQL "unique_violation" SQL state
     * (`23505`) specifically raised against the `uk_engine_analysis_position` constraint, so the
     * expected `position_id` race can be distinguished both from any other genuine persistence failure
     * (e.g. a foreign-key violation) *and* from a 23505 on a different constraint (e.g.
     * `uk_engine_move_evaluation_analysis_move`) that should never be treated as a resolved race.
     *
     * The constraint name is read from the underlying [SQLException]'s message, not compared via a
     * driver-specific type: this module only has a `runtimeOnly` dependency on the PostgreSQL driver, so
     * a compile-time reference to `org.postgresql.util.PSQLException`/`ServerErrorMessage` is unavailable
     * here. PostgreSQL's JDBC driver renders the server's error message verbatim, e.g.
     * `ERROR: duplicate key value violates unique constraint "uk_engine_analysis_position"`, so matching
     * that quoted constraint name is a reliable, dependency-free way to identify the specific constraint.
     */
    private fun isUniqueConstraintViolation(ex: Throwable): Boolean {
        var cause: Throwable? = ex
        while (cause != null) {
            if (cause is SQLException &&
                cause.sqlState == UNIQUE_VIOLATION_SQL_STATE &&
                cause.message?.contains(ENGINE_ANALYSIS_POSITION_UNIQUE_CONSTRAINT) == true
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * Calculates evaluation loss in pawns relative to Stockfish's best move.
     *
     * Scores are pre-normalized by [StockfishService] to the perspective of the player to move in the baseline position
     * (positive scores indicate advantage for the player to move). Therefore, `bestMoveEvalCp - moveEvalCp` is valid
     * for both White and Black positions.
     */
    private fun calculateEvalLoss(
        bestMoveEvalCp: Int?,
        moveEvalCp: Int?,
    ): Double? {
        if (bestMoveEvalCp == null || moveEvalCp == null) return null
        return maxOf(0.0, (bestMoveEvalCp - moveEvalCp) / 100.0)
    }

    private data class AnalysisSnapshot(
        val historicalMoves: List<String>,
        val existingAnalysis: EngineAnalysis?,
    )

    private data class CreationOutcome(
        val analysis: EngineAnalysis,
        val wonCreationRace: Boolean,
    )
}
