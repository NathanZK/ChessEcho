package com.chessecho.research

import java.util.UUID
import kotlin.math.abs

data class FalseFamiliarityCandidate(
    val posU: UUID,
    val posV: UUID,
    val fenU: String,
    val fenV: String,
    val staticSimilarity: Double,
    val sideToMove: String,
    val materialDiff: Double,
    val evalU: Int,
    val evalV: Int,
    val evalDiffCp: Int,
    val top2GapU: Int,
    val top2GapV: Int,
    val top2GapDiffCp: Int,
    val bestMoveU: String,
    val bestMoveV: String,
    val divergenceType: String,
)

data class FalseFamiliarityCaseStudy(
    val caseNumber: Int,
    val title: String,
    val fenU: String,
    val fenV: String,
    val staticSimilarity: Double,
    val evalU: Int,
    val evalV: Int,
    val bestMoveU: String,
    val bestMoveV: String,
    val strategicContext: String,
    val failureMechanism: String,
)

class FalseFamiliarityDetector {

    /**
     * Identifies false-familiarity candidate pairs according to the approved methodological protocol.
     */
    fun findCandidates(
        positions: List<PositionSummary>,
        evaluations: Map<UUID, EngineEvaluation>,
        staticSimilarities: Map<Pair<UUID, UUID>, Double>,
        minStaticSim: Double = 0.70,
        maxMaterialDiff: Double = 1.0,
        minEvalDivergenceCp: Int = 200,
        minTop2DivergenceCp: Int = 150,
    ): List<FalseFamiliarityCandidate> {
        val candidates = mutableListOf<FalseFamiliarityCandidate>()
        val posById = positions.associateBy { it.positionId }

        staticSimilarities.forEach { (pair, sim) ->
            if (sim >= minStaticSim) {
                val (uId, vId) = pair
                val docU = posById[uId]
                val docV = posById[vId]
                val evalU = evaluations[uId]
                val evalV = evaluations[vId]

                if (docU != null && docV != null && evalU != null && evalV != null) {
                    if (docU.sideToMove.equals(docV.sideToMove, ignoreCase = true)) {
                        val matU = calculateMaterial(docU.canonicalFen)
                        val matV = calculateMaterial(docV.canonicalFen)
                        val matDiff = abs(matU - matV)

                        if (matDiff <= maxMaterialDiff) {
                            val evalDiff = abs(evalU.scoreCpSideToMove - evalV.scoreCpSideToMove)
                            val top2Diff = abs(evalU.top2GapCp - evalV.top2GapCp)

                            val hasEvalDivergence = evalDiff >= minEvalDivergenceCp
                            val hasCriticalityDivergence = top2Diff >= minTop2DivergenceCp

                            if (hasEvalDivergence || hasCriticalityDivergence) {
                                val divType = when {
                                    hasEvalDivergence && hasCriticalityDivergence -> "EVAL_AND_CRITICALITY"
                                    hasEvalDivergence -> "EVAL_DIVERGENCE"
                                    else -> "CRITICALITY_DIVERGENCE"
                                }

                                candidates.add(
                                    FalseFamiliarityCandidate(
                                        posU = uId,
                                        posV = vId,
                                        fenU = docU.canonicalFen,
                                        fenV = docV.canonicalFen,
                                        staticSimilarity = sim,
                                        sideToMove = docU.sideToMove,
                                        materialDiff = matDiff,
                                        evalU = evalU.scoreCpSideToMove,
                                        evalV = evalV.scoreCpSideToMove,
                                        evalDiffCp = evalDiff,
                                        top2GapU = evalU.top2GapCp,
                                        top2GapV = evalV.top2GapCp,
                                        top2GapDiffCp = top2Diff,
                                        bestMoveU = evalU.bestMove,
                                        bestMoveV = evalV.bestMove,
                                        divergenceType = divType,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        return candidates.sortedByDescending { it.evalDiffCp.toDouble() * it.staticSimilarity }
    }

    fun calculateMaterial(fen: String): Double {
        val placement = fen.split(" ")[0]
        var score = 0.0
        placement.forEach { c ->
            score += when (c) {
                'Q', 'q' -> 9.0
                'R', 'r' -> 5.0
                'B', 'b' -> 3.0
                'N', 'n' -> 3.0
                'P', 'p' -> 1.0
                else -> 0.0
            }
        }
        return score
    }
}

data class PositionSummary(
    val positionId: UUID,
    val canonicalFen: String,
    val sideToMove: String,
)
