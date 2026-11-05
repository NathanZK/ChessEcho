package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import java.io.File

interface CandidatePreservationAblationRunner {
    fun runAblation(
        corpusDocs: List<PositionDocument>,
        probeQueries: List<PositionDocument>,
        alphas: List<Double> = listOf(0.00, 0.25, 0.50, 0.60, 0.75, 1.00),
        directBudgets: List<Int> = listOf(50, 80, 200, 500, 1000),
        graphBudgets: List<Int> = listOf(50, 80, 200),
        engineCacheFile: File = File("backend/src/test/resources/research/engine_evaluation_cache_d16_mpv1.json"),
        frozenPoolsFile: File = File("backend/src/test/resources/research/tactical-motif-frozen-pools.json"),
    ): AblationExperimentResult
}

data class AblationExperimentResult(
    val metadata: Map<String, Any>,
    val referenceAudit: DynamicReferenceAuditReport,
    val tacticalMotifBenchmarkValidation: Map<String, Any>,
    val systemResults: Map<String, SystemAblationRecord>,
)

data class DynamicReferenceAuditReport(
    val totalProbeQueries: Int,
    val bm25Audit: ScorerAuditSummary,
    val tfidfAudit: ScorerAuditSummary,
)

data class ScorerAuditSummary(
    val meanPositiveScoreCandidates: Double,
    val minPositiveScoreCandidates: Int,
    val medianPositiveScoreCandidates: Int,
    val maxPositiveScoreCandidates: Int,
    val zeroScoreInTop10Count: Int,
    val zeroScoreInTop20Count: Int,
    val minTop10Score: Double,
    val medianTop10Score: Double,
    val minTop20Score: Double,
    val medianTop20Score: Double,
)

data class SystemAblationRecord(
    val systemId: String,
    val systemType: String, // "DIRECT_INDEX", "STATIC_COSINE_CONTROL", "PRODUCTION_CLUSTERED_BASELINE", "SIDE_CONSTRAINED_GRAPH_EXPANSION", "SIDE_CONSTRAINED_CLUSTERED_PIPELINE"
    val targetBudget: Int?,
    val alphaResults: Map<String, AlphaSystemMetrics>, // Keyed by "alpha_0.60"
)

data class AlphaSystemMetrics(
    val alpha: Double,
    val meanCandidateCount: Double,
    val candidateReductionRate: Double,
    val oppositeSideCandidatesExcludedCount: Int,
    val bm25Metrics: ScorerAblationMetrics,
    val tfidfMetrics: ScorerAblationMetrics,
    val lineageBreakdown: IndependentLineageMetrics?,
)

data class ScorerAblationMetrics(
    val dynamicRecallAt10: Double, // Evaluated over same-side reference R_10
    val dynamicRecallAt20: Double, // Evaluated over same-side reference R_20
    val positiveOnlyRecallAt10: Double, // Defensive diagnostic (identical to dynamicRecallAt10 when zeroScoreCount == 0)
    val positiveOnlyRecallAt20: Double,
    val purityAt10: Double, // Evaluated with denominator |C(q)|
    val purityAt20: Double, // Evaluated with denominator |C(q)|
    val retainedAt10Distribution: MetricDistribution,
    val retainedAt20Distribution: MetricDistribution,
)

data class MetricDistribution(
    val min: Int,
    val p25: Int,
    val median: Int,
    val p75: Int,
    val p90: Int,
    val max: Int,
    val mean: Double,
    val zeroCount: Int,
    val zeroPct: Double,
)

data class IndependentLineageMetrics(
    val bm25DirectRecalledAt10: Int,
    val bm25GraphRecalledAt10: Int,
    val bm25GraphRecoveryRatioAt10: Double,
    val bm25DirectRecalledAt20: Int,
    val bm25GraphRecalledAt20: Int,
    val bm25GraphRecoveryRatioAt20: Double,
    val tfidfDirectRecalledAt10: Int,
    val tfidfGraphRecalledAt10: Int,
    val tfidfGraphRecoveryRatioAt10: Double,
    val tfidfDirectRecalledAt20: Int,
    val tfidfGraphRecalledAt20: Int,
    val tfidfGraphRecoveryRatioAt20: Double,
)
