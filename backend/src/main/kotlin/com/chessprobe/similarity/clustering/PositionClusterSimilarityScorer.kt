package com.chessprobe.similarity.clustering

import java.util.UUID
import kotlin.math.sqrt

class PositionClusterSimilarityScorer {
    private data class WeightedEnergies(
        val terms: Map<String, Double>,
        val pawnNorm: Double,
        val pieceNorm: Double,
    )

    fun buildVector(
        positionId: UUID,
        canonicalFen: String,
        terms: Map<String, Double>,
        idfs: Map<String, Double>,
        alpha: Double,
    ): PositionClusterVector {
        require(alpha.isFinite() && alpha in 0.0..1.0) { "alpha must be in [0.0, 1.0]" }

        val energies = computeUnnormalizedEnergies(terms, idfs)
        val normalizedTerms = computeBlockNormalizedTerms(energies, alpha)
        val totalNorm = sqrt(normalizedTerms.values.sumOf { it * it })

        return PositionClusterVector(
            positionId = positionId,
            canonicalFen = canonicalFen,
            rawTerms = terms,
            weightedTerms = normalizedTerms,
            pawnSubvectorNorm = energies.pawnNorm,
            pieceSubvectorNorm = energies.pieceNorm,
            totalNorm = totalNorm,
        )
    }

    private fun computeUnnormalizedEnergies(
        terms: Map<String, Double>,
        idfs: Map<String, Double>,
    ): WeightedEnergies {
        var pawnEnergy = 0.0
        var pieceEnergy = 0.0
        val weighted = mutableMapOf<String, Double>()

        terms.forEach { (term, tf) ->
            val idf = idfs[term] ?: 0.0
            val weight = tf * idf
            if (weight > 0.0) {
                weighted[term] = weight
                if (isPawnToken(term)) {
                    pawnEnergy += weight * weight
                } else {
                    pieceEnergy += weight * weight
                }
            }
        }

        return WeightedEnergies(
            terms = weighted,
            pawnNorm = sqrt(pawnEnergy),
            pieceNorm = sqrt(pieceEnergy),
        )
    }

    private fun computeBlockNormalizedTerms(
        energies: WeightedEnergies,
        alpha: Double,
    ): Map<String, Double> {
        val sqrtAlpha = sqrt(alpha)
        val sqrtOneMinusAlpha = sqrt(1.0 - alpha)
        val result = mutableMapOf<String, Double>()

        energies.terms.forEach { (term, weight) ->
            val blockWeighted =
                computeTermBlockWeight(
                    term = term,
                    weight = weight,
                    pawnNorm = energies.pawnNorm,
                    pieceNorm = energies.pieceNorm,
                    alpha = alpha,
                    sqrtAlpha = sqrtAlpha,
                    sqrtOneMinusAlpha = sqrtOneMinusAlpha,
                )
            if (blockWeighted > 0.0) {
                result[term] = blockWeighted
            }
        }
        return result
    }

    private fun computeTermBlockWeight(
        term: String,
        weight: Double,
        pawnNorm: Double,
        pieceNorm: Double,
        alpha: Double,
        sqrtAlpha: Double,
        sqrtOneMinusAlpha: Double,
    ): Double =
        if (isPawnToken(term)) {
            if (pawnNorm > 0.0 && alpha > 0.0) {
                sqrtAlpha * (weight / pawnNorm)
            } else {
                0.0
            }
        } else {
            if (pieceNorm > 0.0 && alpha < 1.0) {
                sqrtOneMinusAlpha * (weight / pieceNorm)
            } else {
                0.0
            }
        }

    fun cosineSimilarity(
        u: PositionClusterVector,
        v: PositionClusterVector,
    ): Double {
        if (u.totalNorm == 0.0 || v.totalNorm == 0.0) {
            return 0.0
        }

        if (u.positionId == v.positionId && u.totalNorm > 0.0) {
            return 1.0
        }

        val (smaller, larger) =
            if (u.weightedTerms.size <= v.weightedTerms.size) {
                u.weightedTerms to v.weightedTerms
            } else {
                v.weightedTerms to u.weightedTerms
            }

        var dotProduct = 0.0
        smaller.forEach { (term, uWeight) ->
            val vWeight = larger[term]
            if (vWeight != null) {
                dotProduct += uWeight * vWeight
            }
        }

        val denom = u.totalNorm * v.totalNorm
        val sim = dotProduct / denom
        return sim.coerceIn(0.0, 1.0)
    }
}
