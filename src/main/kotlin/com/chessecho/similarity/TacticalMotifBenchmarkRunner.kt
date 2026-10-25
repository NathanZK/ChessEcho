package com.chessecho.similarity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

data class BenchmarkRunResult(
    val corpusSha256: String,
    val executedAt: String,
    val totalQueries: Int,
    val candidatesPerQuery: Int,
    val configurations: Map<String, ConfigurationMetricResult>,
)

data class ConfigurationMetricResult(
    val configuration: String,
    val macroHitAt1: Double,
    val macroHitAt5: Double,
    val macroHitAt10: Double,
    val macroMrr: Double,
    val partitionedByMotif: Map<String, PartitionMetricResult>,
    val perQueryResults: List<QueryEvaluationRecord>,
)

data class PartitionMetricResult(
    val queryCount: Int,
    val macroHitAt1: Double,
    val macroHitAt5: Double,
    val macroHitAt10: Double,
    val macroMrr: Double,
)

data class QueryEvaluationRecord(
    val queryId: String,
    val puzzleId: String,
    val motif: String,
    val firstPositiveRank: Int?,
    val hitAt1: Double,
    val hitAt5: Double,
    val hitAt10: Double,
    val mrr: Double,
    val top10Rankings: List<RankedItemSummary>,
)

data class RankedItemSummary(
    val rank: Int,
    val puzzleId: String,
    val canonicalFen: String,
    val score: Double,
    val isTargetPositive: Boolean,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class FrozenPoolsFile(
    val description: String? = null,
    val sourceDataset: String? = null,
    val totalQueries: Int = 0,
    val candidatesPerQuery: Int = 0,
    val queries: List<FrozenQueryRecord> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class FrozenQueryRecord(
    val queryId: String,
    val puzzleId: String,
    val fen: String,
    val moves: List<String>,
    val rootFen: String,
    val canonicalFen: String,
    val gameUrl: String? = null,
    val motif: String,
    val targetPositives: List<String> = emptyList(),
    val candidates: List<FrozenCandidateRecord> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class FrozenCandidateRecord(
    val puzzleId: String,
    val fen: String,
    val moves: List<String>,
    val rootFen: String,
    val canonicalFen: String,
    val gameUrl: String? = null,
    val label: String? = null,
)

/**
 * Benchmark runner for tactical motif retrieval evaluation (Stages 1 & 2).
 *
 * Programmatically enforces SHA-256 integrity of the candidate pool, executes
 * baseline and experimental configurations, and outputs full metrics and rankings.
 */
class TacticalMotifBenchmarkRunner {
    private val objectMapper = jacksonObjectMapper()
    private val baselineScorer = FenPositionSimilarity()
    private val staticScorer = BizjakFeatureScorer(ScoringVariant.STATIC_ONLY)
    private val dynamicScorer = BizjakFeatureScorer(ScoringVariant.DYNAMIC_ONLY)
    private val combinedScorer = BizjakFeatureScorer(ScoringVariant.COMBINED)

    fun runBenchmark(
        frozenPoolsPath: Path = Path.of("src/test/resources/tactical-motif-frozen-pools.json"),
        digestPath: Path = Path.of("docs/experiments/tactical-motif-frozen-pools.sha256"),
        outputPath: Path = Path.of("docs/experiments/tactical-motif-v1-results.json"),
    ): BenchmarkRunResult {
        val poolBytes = Files.readAllBytes(frozenPoolsPath)
        val expectedDigest = Files.readString(digestPath).trim()
        val actualDigest = verifyCorpusDigest(expectedDigest, poolBytes)

        val frozenData: FrozenPoolsFile = objectMapper.readValue(poolBytes)
        val queries = frozenData.queries

        val configNames =
            listOf(
                "baseline_ganguly_static",
                "bizjak_static_only",
                "bizjak_dynamic_only",
                "bizjak_combined",
            )

        val configResults = mutableMapOf<String, ConfigurationMetricResult>()

        configNames.forEach { config ->
            val queryRecords = mutableListOf<QueryEvaluationRecord>()

            queries.forEach { q ->
                val targetPositiveSet = q.targetPositives.toSet()

                val rankedCandidates: List<RankedItemSummary> =
                    when (config) {
                        "baseline_ganguly_static" -> {
                            val candidateFens = q.candidates.map { it.rootFen }
                            val ranked = baselineScorer.rank(q.rootFen, candidateFens)
                            val fenToCandidate = q.candidates.associateBy { canonical(it.rootFen) }
                            ranked.mapIndexed { index, item ->
                                val cand =
                                    fenToCandidate[item.canonicalFen]
                                        ?: error("Missing candidate for ${item.canonicalFen}")
                                RankedItemSummary(
                                    rank = index + 1,
                                    puzzleId = cand.puzzleId,
                                    canonicalFen = item.canonicalFen,
                                    score = item.score,
                                    isTargetPositive = cand.puzzleId in targetPositiveSet,
                                )
                            }
                        }
                        "bizjak_static_only" -> scoreWithBizjak(staticScorer, q, targetPositiveSet)
                        "bizjak_dynamic_only" -> scoreWithBizjak(dynamicScorer, q, targetPositiveSet)
                        "bizjak_combined" -> scoreWithBizjak(combinedScorer, q, targetPositiveSet)
                        else -> error("Unknown config $config")
                    }

                val firstPositive = rankedCandidates.firstOrNull { it.isTargetPositive }
                val rank = firstPositive?.rank
                val hitAt1 = if (rank != null && rank <= 1) 1.0 else 0.0
                val hitAt5 = if (rank != null && rank <= 5) 1.0 else 0.0
                val hitAt10 = if (rank != null && rank <= 10) 1.0 else 0.0
                val mrr = if (rank != null) 1.0 / rank else 0.0

                queryRecords.add(
                    QueryEvaluationRecord(
                        queryId = q.queryId,
                        puzzleId = q.puzzleId,
                        motif = q.motif,
                        firstPositiveRank = rank,
                        hitAt1 = hitAt1,
                        hitAt5 = hitAt5,
                        hitAt10 = hitAt10,
                        mrr = mrr,
                        top10Rankings = rankedCandidates.take(10),
                    ),
                )
            }

            val macroHitAt1 = queryRecords.map { it.hitAt1 }.average()
            val macroHitAt5 = queryRecords.map { it.hitAt5 }.average()
            val macroHitAt10 = queryRecords.map { it.hitAt10 }.average()
            val macroMrr = queryRecords.map { it.mrr }.average()

            // Motif partitions
            val partitions =
                queryRecords.groupBy { it.motif }.mapValues { (_, records) ->
                    PartitionMetricResult(
                        queryCount = records.size,
                        macroHitAt1 = records.map { it.hitAt1 }.average(),
                        macroHitAt5 = records.map { it.hitAt5 }.average(),
                        macroHitAt10 = records.map { it.hitAt10 }.average(),
                        macroMrr = records.map { it.mrr }.average(),
                    )
                }

            configResults[config] =
                ConfigurationMetricResult(
                    configuration = config,
                    macroHitAt1 = macroHitAt1,
                    macroHitAt5 = macroHitAt5,
                    macroHitAt10 = macroHitAt10,
                    macroMrr = macroMrr,
                    partitionedByMotif = partitions,
                    perQueryResults = queryRecords,
                )
        }

        val result =
            BenchmarkRunResult(
                corpusSha256 = actualDigest,
                executedAt = Instant.now().toString(),
                totalQueries = queries.size,
                candidatesPerQuery = if (queries.isNotEmpty()) queries[0].candidates.size else 0,
                configurations = configResults,
            )

        Files.createDirectories(outputPath.parent)
        Files.writeString(outputPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))
        return result
    }

    private fun scoreWithBizjak(
        scorer: BizjakFeatureScorer,
        q: FrozenQueryRecord,
        targetPositiveSet: Set<String>,
    ): List<RankedItemSummary> {
        val queryDoc =
            TacticalPuzzleDocument(
                puzzleId = q.puzzleId,
                canonicalFen = q.canonicalFen,
                fen = q.fen,
                moves = q.moves,
                isQuery = true,
            )
        val candidateDocs =
            q.candidates.map {
                TacticalPuzzleDocument(
                    puzzleId = it.puzzleId,
                    canonicalFen = it.canonicalFen,
                    fen = it.fen,
                    moves = it.moves,
                    isQuery = false,
                )
            }

        val ranked = scorer.rank(queryDoc, candidateDocs)
        return ranked.mapIndexed { index, match ->
            RankedItemSummary(
                rank = index + 1,
                puzzleId = match.puzzleId,
                canonicalFen = match.canonicalFen,
                score = match.score,
                isTargetPositive = match.puzzleId in targetPositiveSet,
            )
        }
    }

    private fun canonical(fen: String): String = fen.trim().split(Regex("\\s+")).take(4).joinToString(" ")

    fun verifyCorpusDigest(
        expectedDigest: String,
        poolBytes: ByteArray,
    ): String {
        val actualDigest =
            MessageDigest.getInstance("SHA-256")
                .digest(poolBytes)
                .joinToString("") { "%02x".format(it) }

        if (!actualDigest.equals(expectedDigest, ignoreCase = true)) {
            throw IllegalStateException("Corpus SHA-256 mismatch! Expected $expectedDigest but computed $actualDigest")
        }
        return actualDigest
    }
}
