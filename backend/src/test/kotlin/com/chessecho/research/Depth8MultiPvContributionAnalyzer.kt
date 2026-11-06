package com.chessecho.research

/**
 * Analytical evaluator for Rank-1 consistency and MultiPV token contributions
 * according to SPEC-RESEARCH-DYNAMIC-SIMILARITY-DEPTH8-MULTIPV (§4.2, §8).
 */
data class Rank1ConsistencyReport(
    val totalPositions: Int,
    val move1IdentityRate1vs3: Double,
    val move1IdentityRate1vs5: Double,
    val meanPvJaccard1vs3: Double,
    val meanPvJaccard1vs5: Double,
    val meanScoreDiff1vs3: Double,
    val meanScoreDiff1vs5: Double,
    val meanTokenJaccard1vs3: Double,
    val meanTokenJaccard1vs5: Double,
)

data class MultiPvContributionReport(
    val totalPositions: Int,
    val vocabularySizes: Map<String, Int>,
    val meanDocumentLengths: Map<String, Double>,
    val meanTokenNovelty: Map<String, Double>,
    val motifCategoryBreakdownMpv3: Map<String, Int>,
    val motifCategoryBreakdownMpv5: Map<String, Int>,
)

class Depth8MultiPvContributionAnalyzer {

    fun analyzeRank1Consistency(
        cache1: Map<String, Depth8CachedEngineRecord>,
        cache3: Map<String, Depth8CachedEngineRecord>,
        cache5: Map<String, Depth8CachedEngineRecord>,
    ): Rank1ConsistencyReport {
        // Map by canonicalFen
        val byFen1 = cache1.values.associateBy { it.canonicalFen }
        val byFen3 = cache3.values.associateBy { it.canonicalFen }
        val byFen5 = cache5.values.associateBy { it.canonicalFen }

        val commonFens = byFen1.keys.filter { byFen3.containsKey(it) && byFen5.containsKey(it) }
        val n = commonFens.size.coerceAtLeast(1)

        var move1Identical1vs3 = 0
        var move1Identical1vs5 = 0
        var sumPvJaccard1vs3 = 0.0
        var sumPvJaccard1vs5 = 0.0
        var sumScoreDiff1vs3 = 0.0
        var sumScoreDiff1vs5 = 0.0
        var sumTokenJaccard1vs3 = 0.0
        var sumTokenJaccard1vs5 = 0.0

        for (fen in commonFens) {
            val r1 = byFen1[fen]!!
            val r3 = byFen3[fen]!!
            val r5 = byFen5[fen]!!

            val pv1 = r1.principalVariations.firstOrNull { it.rank == 1 }?.moves.orEmpty()
            val pv3 = r3.principalVariations.firstOrNull { it.rank == 1 }?.moves.orEmpty()
            val pv5 = r5.principalVariations.firstOrNull { it.rank == 1 }?.moves.orEmpty()

            val best1 = r1.bestMove
            val best3 = r3.bestMove
            val best5 = r5.bestMove

            if (best1 == best3) move1Identical1vs3++
            if (best1 == best5) move1Identical1vs5++

            sumPvJaccard1vs3 += jaccard(pv1.toSet(), pv3.toSet())
            sumPvJaccard1vs5 += jaccard(pv1.toSet(), pv5.toSet())

            sumScoreDiff1vs3 += Math.abs(r1.scoreCp - r3.scoreCp)
            sumScoreDiff1vs5 += Math.abs(r1.scoreCp - r5.scoreCp)

            val tok1 = r1.tokensByRank["1"]?.keys.orEmpty()
            val tok3 = r3.tokensByRank["1"]?.keys.orEmpty()
            val tok5 = r5.tokensByRank["1"]?.keys.orEmpty()

            sumTokenJaccard1vs3 += jaccard(tok1, tok3)
            sumTokenJaccard1vs5 += jaccard(tok1, tok5)
        }

        return Rank1ConsistencyReport(
            totalPositions = n,
            move1IdentityRate1vs3 = Math.round((move1Identical1vs3.toDouble() / n) * 1000.0) / 10.0,
            move1IdentityRate1vs5 = Math.round((move1Identical1vs5.toDouble() / n) * 1000.0) / 10.0,
            meanPvJaccard1vs3 = Math.round((sumPvJaccard1vs3 / n) * 1000.0) / 1000.0,
            meanPvJaccard1vs5 = Math.round((sumPvJaccard1vs5 / n) * 1000.0) / 1000.0,
            meanScoreDiff1vs3 = Math.round((sumScoreDiff1vs3 / n) * 10.0) / 10.0,
            meanScoreDiff1vs5 = Math.round((sumScoreDiff1vs5 / n) * 10.0) / 10.0,
            meanTokenJaccard1vs3 = Math.round((sumTokenJaccard1vs3 / n) * 1000.0) / 1000.0,
            meanTokenJaccard1vs5 = Math.round((sumTokenJaccard1vs5 / n) * 1000.0) / 1000.0,
        )
    }

    fun analyzeMultiPvContributions(
        cache1: Map<String, Depth8CachedEngineRecord>,
        cache3: Map<String, Depth8CachedEngineRecord>,
        cache5: Map<String, Depth8CachedEngineRecord>,
    ): MultiPvContributionReport {
        val byFen1 = cache1.values.associateBy { it.canonicalFen }
        val byFen3 = cache3.values.associateBy { it.canonicalFen }
        val byFen5 = cache5.values.associateBy { it.canonicalFen }

        val commonFens = byFen1.keys.filter { byFen3.containsKey(it) && byFen5.containsKey(it) }
        val n = commonFens.size.coerceAtLeast(1)

        val vocab1 = mutableSetOf<String>()
        val vocab3 = mutableSetOf<String>()
        val vocab5 = mutableSetOf<String>()

        var totalLen1 = 0L
        var totalLen3 = 0L
        var totalLen5 = 0L

        var sumNovelty3 = 0.0
        var sumNovelty5 = 0.0

        val motifCounts3 = mutableMapOf<String, Int>()
        val motifCounts5 = mutableMapOf<String, Int>()

        for (fen in commonFens) {
            val r1 = byFen1[fen]!!
            val r3 = byFen3[fen]!!
            val r5 = byFen5[fen]!!

            val t1 = r1.dynamicTokens.keys
            val t3 = r3.dynamicTokens.keys
            val t5 = r5.dynamicTokens.keys

            vocab1.addAll(t1)
            vocab3.addAll(t3)
            vocab5.addAll(t5)

            totalLen1 += r1.dynamicTokens.values.sum().toLong()
            totalLen3 += r3.dynamicTokens.values.sum().toLong()
            totalLen5 += r5.dynamicTokens.values.sum().toLong()

            val nov3 = if (t3.isNotEmpty()) (t3 - t1).size.toDouble() / t3.size.toDouble() else 0.0
            val nov5 = if (t5.isNotEmpty()) (t5 - t1).size.toDouble() / t5.size.toDouble() else 0.0

            sumNovelty3 += nov3
            sumNovelty5 += nov5

            for (token in (t3 - t1)) {
                val cat = categorizeToken(token)
                motifCounts3[cat] = (motifCounts3[cat] ?: 0) + 1
            }
            for (token in (t5 - t1)) {
                val cat = categorizeToken(token)
                motifCounts5[cat] = (motifCounts5[cat] ?: 0) + 1
            }
        }

        return MultiPvContributionReport(
            totalPositions = n,
            vocabularySizes = mapOf("MPV1" to vocab1.size, "MPV3" to vocab3.size, "MPV5" to vocab5.size),
            meanDocumentLengths = mapOf(
                "MPV1" to Math.round((totalLen1.toDouble() / n) * 10.0) / 10.0,
                "MPV3" to Math.round((totalLen3.toDouble() / n) * 10.0) / 10.0,
                "MPV5" to Math.round((totalLen5.toDouble() / n) * 10.0) / 10.0,
            ),
            meanTokenNovelty = mapOf(
                "MPV3" to Math.round((sumNovelty3 / n) * 1000.0) / 10.0, // percentage
                "MPV5" to Math.round((sumNovelty5 / n) * 1000.0) / 10.0,
            ),
            motifCategoryBreakdownMpv3 = motifCounts3,
            motifCategoryBreakdownMpv5 = motifCounts5,
        )
    }

    fun categorizeToken(token: String): String {
        return when {
            token == "?+" -> "tactical_checks"
            token.startsWith("!x") -> "material_captures"
            token.contains(">") -> "created_attack_threats"
            token == "?S" || token.startsWith("!S") -> "piece_sacrifices"
            token == "?=" -> "pawn_promotions"
            token == "?#" || token.startsWith("!#") -> "tactical_checkmates"
            token == "?px" || token == "?ox" -> "general_capture_flags"
            token.startsWith("!-") -> "piece_move_cadence"
            else -> "other_tactical_tokens"
        }
    }

    private fun <T> jaccard(a: Set<T>, b: Set<T>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val union = a + b
        if (union.isEmpty()) return 0.0
        val intersection = a.intersect(b)
        return intersection.size.toDouble() / union.size.toDouble()
    }
}
