package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import java.io.File

interface ConnectivityClosureAblationRunner {
    fun runAblation(
        corpusDocs: List<PositionDocument>,
        probeQueries: List<PositionDocument>,
        budgets: List<Int> = listOf(50, 80, 200, 500, 1000),
        engineCacheFile: File = File("backend/src/test/resources/research/engine_evaluation_cache_d16_mpv1.json"),
        baselineResultsFile: File = File("backend/src/test/resources/research/candidate_preservation_ablation_results.json"),
    ): ConnectivityAblationResult
}

data class ConnectivityAblationResult(
    val metadata: ExperimentMetadata,
    val vocabularyStatistics: Map<String, VocabularySummary>,
    val diagnosticResults: CaptureDiagnosticReport,
    val systemResults: Map<String, ConnectivitySystemRecord>,
    val baselineComparisons: Map<String, BaselineComparisonRecord>,
)

data class ExperimentMetadata(
    val experimentId: String,
    val timestamp: String,
    val gitCommit: String,
    val corpusSize: Int,
    val queryCount: Int,
    val meanCandidateUniverseSize: Double,
    val zeroEngineSearchesGuaranteed: Boolean,
    val zeroProductionChangesGuaranteed: Boolean,
)

data class VocabularySummary(
    val vocabularySize: Int,
    val avgDocumentLength: Double,
    val medianDocumentLength: Int,
    val maxDocumentLength: Int,
    val emptyDocumentCount: Int,
    val emptyDocumentPercentage: Double,
)

data class CaptureDiagnosticReport(
    val populationSize: Int,
    val emptyPvCount: Int,
    val nxqDiagnostic: SingleCaptureDiagnostic,
    val captureFamilyBreakdown: Map<String, SingleCaptureDiagnostic>,
)

data class SingleCaptureDiagnostic(
    val captureType: String, // e.g. "N_x_Q", "B_x_Q"
    val immediatePly0: ImmediateCaptureStats,
    val preparatoryPlyK: PreparatoryCaptureStats,
)

data class ImmediateCaptureStats(
    val totalEvents: Int,
    val matchedEvents: Int,
    val eventMatchRate: Double, // matchedEvents / max(1, totalEvents); expected 1.0 (integrity check)
    val uniquePositions: Int,
    val matchedPositions: Int,
    val positionMatchRate: Double, // matchedPositions / max(1, uniquePositions); expected 1.0 (integrity check)
)

data class PreparatoryCaptureStats(
    val totalEvents: Int,
    val matchedEvents: Int,
    val eventMatchRate: Double, // matchedEvents / max(1, totalEvents); empirical correspondence
    val uniquePositions: Int,
    val matchedPositions: Int,
    val positionMatchRate: Double, // matchedPositions / max(1, uniquePositions); empirical correspondence
    val rootPlayerEvents: Int,
    val matchedRootPlayerEvents: Int,
    val rootPlayerEventMatchRate: Double, // matchedRootPlayerEvents / max(1, rootPlayerEvents); empirical correspondence
    val meanPlyOfCapture: Double,
)

data class ConnectivitySystemRecord(
    val systemId: String,
    val representationType: String, // "ATTACK_CLOSURE", "DEFENSE_CLOSURE", "COMBINED_CLOSURE"
    val budgetResults: Map<String, BudgetSystemMetrics>, // Keyed by "budget_50", "budget_80", etc.
)

data class BudgetSystemMetrics(
    val requestedBudget: Int,
    val actualMeanCandidateCount: Double,
    val positiveCandidatePoolExhaustionRate: Double,
    val actualMeanCandidateReductionRate: Double,
    val nominalCandidateReductionRate: Double,
    val bm25Metrics: ConnectivityScorerMetrics,
    val tfidfMetrics: ConnectivityScorerMetrics,
    val unweightedOverlapDiagnostic: OverlapDiagnosticStats,
    val overlapWithDefenseClosureJaccard: Double?, // Computed for Attack Closure
    val bm25IncrementalRecallGainAt10OverBestSingle: Double?, // Computed for Combined Closure
    val bm25IncrementalRecallGainAt20OverBestSingle: Double?, // Computed for Combined Closure
    val tfidfIncrementalRecallGainAt10OverBestSingle: Double?, // Computed for Combined Closure
    val tfidfIncrementalRecallGainAt20OverBestSingle: Double?, // Computed for Combined Closure
)

data class ReferenceOverlapDistribution(
    val mean: Double,
    val median: Int,
    val max: Int,
)

data class OverlapDiagnosticStats(
    val meanRetrievedOverlap: Double,
    val medianRetrievedOverlap: Int,
    val maxRetrievedOverlap: Int,
    val bm25ReferenceTop10Overlap: ReferenceOverlapDistribution,
    val bm25ReferenceTop20Overlap: ReferenceOverlapDistribution,
    val tfidfReferenceTop10Overlap: ReferenceOverlapDistribution,
    val tfidfReferenceTop20Overlap: ReferenceOverlapDistribution,
)

data class ConnectivityScorerMetrics(
    val recallAt10: ConnectivityMetricDistribution,
    val recallAt20: ConnectivityMetricDistribution,
    val purityAt10: ConnectivityMetricDistribution,
    val purityAt20: ConnectivityMetricDistribution,
    val zeroRecallCountAt10: Int,
    val zeroRecallPercentageAt10: Double,
    val zeroRecallCountAt20: Int,
    val zeroRecallPercentageAt20: Double,
)

data class ConnectivityMetricDistribution(
    val mean: Double,
    val median: Double,
    val stdDev: Double,
    val min: Double,
    val p25: Double,
    val p75: Double,
    val p90: Double,
    val max: Double,
)

data class BaselineComparisonRecord(
    val systemId: String,
    val architectureFamily: String,
    val budget: Int?,
    val meanCandidateCount: Double,
    val candidateReductionRate: Double,
    val bm25RecallAt10: Double,
    val bm25RecallAt20: Double,
    val tfidfRecallAt10: Double,
    val tfidfRecallAt20: Double,
)
