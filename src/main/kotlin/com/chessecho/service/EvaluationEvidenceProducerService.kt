package com.chessecho.service

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class EvaluationEvidenceProducerService(
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val selectedPopulationVerificationService: SelectedPopulationVerificationService,
    private val evaluationEvidenceSnapshotService: EvaluationEvidenceSnapshotService,
    private val gameOutcomeNormalizer: GameOutcomeNormalizer,
    private val engineAnalysisService: EngineAnalysisService,
) {
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun produce(input: EvaluationEvidenceProducerInput): UUID {
        validateInput(input)
        val selectedOccurrences = loadSelectedOccurrences(input.operationalOccurrenceIds)
        val analyses = loadAnalyses(selectedOccurrences)
        val matchingOccurrences = findMatchingReferenceOccurrences(input, selectedOccurrences)
        val rows = buildRows(input, selectedOccurrences, analyses, matchingOccurrences)
        val snapshot =
            EvaluationEvidenceSnapshot(
                id = UUID.randomUUID(),
                referencePopulation = input.referencePopulation,
                occurrenceEvidenceId = null,
                players = input.roster,
                configuration = input.configuration,
                sourceRevision = null,
                engineIdentity = null,
                parserIdentity = null,
                rows = rows,
            )
        evaluationEvidenceSnapshotService.validate(snapshot)

        val intendedOccurrenceIds = matchingOccurrences.values.flatten().toSet()
        val verified =
            selectedPopulationVerificationService.verify(
                input.referencePopulation,
                intendedOccurrenceIds,
            )
        validateVerifiedOccurrences(input, rows, intendedOccurrenceIds, verified)
        return evaluationEvidenceSnapshotService.persist(snapshot, input.referencePopulation)
    }

    private fun validateInput(input: EvaluationEvidenceProducerInput) {
        val configuration = input.configuration
        if (configuration.thresholds != EvaluationEvidenceSnapshotService.APPROVED_THRESHOLDS ||
            configuration.platform != "CHESS_COM" ||
            configuration.minMistakeCount < 0 ||
            configuration.minTimesReached < 0 ||
            configuration.color !in setOf("WHITE", "BLACK", "BOTH") ||
            configuration.observationWindowDays != null
        ) {
            throw EvaluationEvidenceIntegrityException("Invalid frozen evaluation-evidence configuration")
        }
        if (input.roster.map { it.id }.toSet().size != input.roster.size ||
            input.roster.map { it.identity }.toSet().size != input.roster.size ||
            input.roster.any { it.identity.isBlank() }
        ) {
            throw EvaluationEvidenceIntegrityException("Evaluation-player roster is invalid")
        }
    }

    private fun loadSelectedOccurrences(ids: Set<UUID>): List<PositionOccurrence> {
        if (ids.isEmpty()) return emptyList()
        val occurrences = positionOccurrenceRepository.findSelectedWithOperationalFacts(ids)
        if (occurrences.map { it.id }.toSet() != ids) {
            throw EvaluationEvidenceIntegrityException("A selected operational occurrence is missing")
        }
        return occurrences.sortedBy { it.id }
    }

    private fun loadAnalyses(occurrences: List<PositionOccurrence>): Map<UUID, EngineAnalysis> {
        val positionIds = occurrences.map { it.position.id }.toSet()
        if (positionIds.isEmpty()) return emptyMap()
        val analyses = engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(positionIds)
        val indexed = analyses.associateBy { it.position.id }
        if (indexed.size != analyses.size || indexed.keys != positionIds) {
            throw EvaluationEvidenceIntegrityException("A selected operational position has missing or ambiguous engine analysis")
        }
        return indexed
    }

    private fun findMatchingReferenceOccurrences(
        input: EvaluationEvidenceProducerInput,
        operationalOccurrences: List<PositionOccurrence>,
    ): Map<PositionPly, List<UUID>> {
        val positionPlys =
            operationalOccurrences
                .map { PositionPly(it.position.hash, it.plyNumber) }
                .distinct()
                .sortedWith(compareBy({ it.positionHash }, { it.preMovePly }))
        if (positionPlys.isEmpty()) return emptyMap()

        val selectedValues = positionPlys.joinToString(", ") { "(?, ?)" }
        val parameters = mutableListOf<Any>()
        positionPlys.forEach {
            parameters += it.positionHash
            parameters += it.preMovePly
        }
        parameters += input.referencePopulation.sourceRunId
        parameters += input.referencePopulation.contentDigest
        parameters += input.referencePopulation.coveredPrefix
        parameters += input.referencePopulation.prefixN
        val matches =
            jdbcTemplate.query(
                """
                WITH selected_positions(position_hash, pre_move_ply) AS (VALUES $selectedValues)
                SELECT o.id, o.position_hash, o.pre_move_ply
                FROM human_move_corpus_occurrence o
                JOIN selected_positions p
                  ON p.position_hash = o.position_hash
                 AND p.pre_move_ply = o.pre_move_ply
                WHERE o.source_run_id = ?
                  AND o.content_digest = ?
                  AND o.covered_prefix = ?
                  AND o.qualifying_ordinal <= ?
                ORDER BY o.position_hash, o.pre_move_ply, o.qualifying_ordinal, o.id
                """.trimIndent(),
                { rs, _ ->
                    val key = PositionPly(rs.getString("position_hash"), rs.getInt("pre_move_ply"))
                    key to rs.getObject("id", UUID::class.java)
                },
                *parameters.toTypedArray(),
            )
        val indexed = matches.groupBy({ it.first }, { it.second })
        if (positionPlys.any { indexed[it].isNullOrEmpty() }) {
            throw EvaluationEvidenceIntegrityException("A selected operational decision has no matching reference occurrence")
        }
        return indexed.mapValues { (_, occurrenceIds) -> occurrenceIds.sorted() }
    }

    private fun buildRows(
        input: EvaluationEvidenceProducerInput,
        operationalOccurrences: List<PositionOccurrence>,
        analyses: Map<UUID, EngineAnalysis>,
        matchingOccurrences: Map<PositionPly, List<UUID>>,
    ): List<EvaluationEvidenceRow> {
        val declaredPlayers = input.roster.associateBy { it.id }
        return operationalOccurrences.flatMap { operational ->
            val playerId = operational.chessAccount.id
            if (playerId !in declaredPlayers) {
                throw EvaluationEvidenceIntegrityException(
                    "A selected operational occurrence belongs to an undeclared player",
                )
            }
            if (operational.game.chessAccount.id != playerId ||
                operational.chessAccount.platform != input.configuration.platform
            ) {
                throw EvaluationEvidenceIntegrityException("Selected operational game/account facts are inconsistent")
            }
            val analysis =
                analyses[operational.position.id]
                    ?: throw EvaluationEvidenceIntegrityException("A selected operational position has no engine analysis")
            val matchingMoves = analysis.moveEvaluations.filter { it.move == operational.movePlayed }
            if (matchingMoves.size != 1) {
                throw EvaluationEvidenceIntegrityException("A selected operational move has missing or ambiguous evaluation")
            }
            val evaluation = matchingMoves.single()
            val loss = validLoss(analysis, evaluation.evalCp, evaluation.evalLossFromBest)
            val outcome =
                gameOutcomeNormalizer.normalize(
                    operational.game,
                    operational.playerColor,
                    operational.chessAccount.username,
                ).outcome
                    ?: throw EvaluationEvidenceIntegrityException("A selected operational game has no normalized outcome")
            val observedOutcome =
                when (outcome) {
                    PracticalOutcome.WIN -> ObservedGameOutcome.WIN
                    PracticalOutcome.DRAW -> ObservedGameOutcome.DRAW
                    PracticalOutcome.LOSS -> ObservedGameOutcome.LOSS
                }
            val positionPly = PositionPly(operational.position.hash, operational.plyNumber)
            val referenceMatches =
                matchingOccurrences[positionPly]
                    ?: throw EvaluationEvidenceIntegrityException("A selected operational decision has no matching reference occurrence")
            referenceMatches.map { occurrenceId ->
                EvaluationEvidenceRow(
                    playerId = playerId,
                    gameId = operational.game.id,
                    occurrenceId = occurrenceId,
                    positionIdentity = operational.position.hash,
                    preMovePly = operational.plyNumber,
                    move = operational.movePlayed,
                    playerColor = operational.playerColor,
                    loss = loss,
                    engineDepth = analysis.depth,
                    observedOutcome = observedOutcome,
                    objectiveOutcome = null,
                    practicalCandidate = null,
                    practicalEligible = null,
                    practicalWins = null,
                    practicalDraws = null,
                    practicalLosses = null,
                )
            }
        }.sortedWith(compareBy({ it.playerId }, { it.preMovePly }, { it.occurrenceId }, { it.gameId }))
    }

    private fun validLoss(
        analysis: EngineAnalysis,
        moveEvalCp: Int?,
        persistedLoss: Double?,
    ): Double {
        val bestMoveEvalCp = analysis.bestMoveEvalCp
        if (bestMoveEvalCp == null || moveEvalCp == null) {
            throw EvaluationEvidenceIntegrityException("A selected operational move is missing required centipawn evidence")
        }
        val loss =
            persistedLoss ?: engineAnalysisService.calculateEvalLoss(bestMoveEvalCp, moveEvalCp)
                ?: throw EvaluationEvidenceIntegrityException("A selected operational move has no valid evaluation loss")
        if (!loss.isFinite() || loss < 0.0) {
            throw EvaluationEvidenceIntegrityException("A selected operational move has an invalid evaluation loss")
        }
        return loss
    }

    private fun validateVerifiedOccurrences(
        input: EvaluationEvidenceProducerInput,
        rows: List<EvaluationEvidenceRow>,
        intendedOccurrenceIds: Set<UUID>,
        verified: VerifiedSelectedPopulationContext,
    ) {
        if (verified.population != input.referencePopulation || verified.occurrences.keys != intendedOccurrenceIds) {
            throw EvaluationEvidenceIntegrityException("Verified occurrences do not match the intended selected population")
        }
        rows.forEach { row ->
            val occurrence =
                verified.occurrences[row.occurrenceId]
                    ?: throw EvaluationEvidenceIntegrityException("A proposed row has no verified reference occurrence")
            if (occurrence.sourceRunId != input.referencePopulation.sourceRunId ||
                occurrence.contentDigest != input.referencePopulation.contentDigest ||
                occurrence.coveredPrefix != input.referencePopulation.coveredPrefix ||
                occurrence.qualifyingOrdinal !in 1..input.referencePopulation.prefixN ||
                occurrence.positionHash != row.positionIdentity ||
                occurrence.preMovePly != row.preMovePly
            ) {
                throw EvaluationEvidenceIntegrityException("A verified reference occurrence does not match its proposed row")
            }
        }
    }

    private data class PositionPly(
        val positionHash: String,
        val preMovePly: Int,
    )
}
