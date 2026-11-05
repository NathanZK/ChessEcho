package com.chessecho.research

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.PositionInvertedIndex
import com.chessecho.similarity.RelationalPawnTokenExtractor
import com.chessecho.similarity.clustering.ClusterEvaluationResult
import com.chessecho.similarity.clustering.PositionClusterCentroidEvaluator
import com.chessecho.similarity.clustering.PositionClusterSimilarityScorer
import com.chessecho.similarity.clustering.PositionClusterVector
import com.chessecho.similarity.clustering.PositionGraph
import com.chessecho.similarity.clustering.PositionLeidenCommunityDetector
import com.chessecho.similarity.clustering.RelaxedMnnGraphBuilder
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID
import kotlin.math.ln

data class GridCellResult(
    val alpha: Double,
    val gammaRb: Double,
    val totalClusters: Int,
    val assignedCount: Int,
    val assignedPct: Double,
    val isolateCount: Int,
    val outlierCount: Int,
    val meanSize: Double,
    val medianSize: Double,
    val p90Size: Double,
    val maxSize: Int,
    val modularityQ: Double,
    val amiVsBaseline: Double,
    val ariVsBaseline: Double,
    val jaccardAssignedVsBaseline: Double,
    val viVsBaseline: Double,
)

data class PositionMigrationProfile(
    val canonicalFen: String,
    val eco: String,
    val sideToMove: String,
    val clusterIdByAlpha: Map<Double, Int?>,
    val isAnchor: Boolean,
    val isMigrator: Boolean,
    val notes: String,
)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SensitivityGridExperimentTest {
    private val extractor = RelationalPawnTokenExtractor()
    private val scorer = PositionClusterSimilarityScorer()
    private val graphBuilder = RelaxedMnnGraphBuilder(scorer)
    private val detector = PositionLeidenCommunityDetector()
    private val centroidEvaluator = PositionClusterCentroidEvaluator()
    private val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val outputFile = File("src/test/resources/research/sensitivity_grid_report.json")

    private lateinit var corpusDocs: List<PositionDocument>
    private lateinit var idfMap: Map<String, Double>
    private lateinit var invertedIndex: PositionInvertedIndex

    private val alphaValues = listOf(0.00, 0.25, 0.50, 0.60, 0.75, 1.00)
    private val gammaValues = listOf(0.50, 0.80, 1.25, 2.00, 3.50, 5.00)

    private val gridResults = mutableListOf<GridCellResult>()
    private val partitionAssignments = mutableMapOf<Pair<Double, Double>, Map<UUID, Int?>>()

    @BeforeAll
    fun setUp() {
        assertTrue(corpusFile.exists(), "Corpus A must exist")
        val builder = CorpusBuilder()
        val rawPositions = builder.loadCorpusFromJson(corpusFile)

        println("Loading and indexing Corpus A for 6x6 sensitivity grid sweep...")
        corpusDocs = rawPositions.mapIndexed { _, pos ->
            val id = DeterministicIdGenerator.generateDeterministicId(pos.canonicalFen, pos.samplePly)
            extractor.extractDocument(id, pos.canonicalFen, includeReachability = true)
        }

        // IDF computation
        val dfMap = mutableMapOf<String, Int>()
        corpusDocs.forEach { p ->
            p.terms.keys.forEach { t -> dfMap.merge(t, 1, Int::plus) }
        }
        val totalDocs = corpusDocs.size
        idfMap = dfMap.mapValues { (_, df) ->
            ln(1.0 + ((totalDocs - df + 0.5) / (df + 0.5)))
        }

        invertedIndex = PositionInvertedIndex().apply { index(corpusDocs) }
        println("Corpus A indexing complete.")
    }

    @Test
    fun `execute complete 6x6 alpha and resolution sensitivity sweep`() {
        // First identify baseline (alpha=0.60, gamma=1.25)
        println("Executing 6x6 Sensitivity Grid Sweep (${alphaValues.size} alphas x ${gammaValues.size} gammas)...")

        // 1. Precompute MNN graph for each alpha
        val graphsByAlpha = mutableMapOf<Double, Pair<List<PositionClusterVector>, PositionGraph>>()
        val candidateMapsByAlpha = mutableMapOf<Double, Map<UUID, Collection<UUID>>>()

        alphaValues.forEach { alpha ->
            println("Building vectors and MNN graph for alpha=$alpha...")
            val vectors = corpusDocs.parallelStream().map { doc ->
                doc.positionId to scorer.buildVector(
                    positionId = doc.positionId,
                    canonicalFen = doc.canonicalFen,
                    terms = doc.terms,
                    idfs = idfMap,
                    alpha = alpha,
                )
            }.toList().toMap()

            val cMap = corpusDocs.parallelStream().map { doc ->
                val cands = invertedIndex.generateCandidates(
                    queryTerms = doc.terms.keys,
                    activeTurn = doc.sideToMove,
                    maxCandidates = 200,
                    alpha = alpha,
                ).filter { it.positionId != doc.positionId }
                doc.positionId to cands.map { it.positionId }
            }.toList().toMap()
            candidateMapsByAlpha[alpha] = cMap

            val vList = vectors.values.toList()
            val graph = graphBuilder.buildGraph(
                vectors = vList,
                candidateMap = cMap,
                topK = 30,
                floorThreshold = 0.40,
                top1Threshold = 0.65,
            )
            graphsByAlpha[alpha] = Pair(vList, graph)
        }

        // 2. Evaluate all 36 (alpha, gamma) cells
        val evaluatedCells = mutableMapOf<Pair<Double, Double>, Pair<Double, ClusterEvaluationResult>>()

        alphaValues.forEach { alpha ->
            val (vList, graph) = graphsByAlpha.getValue(alpha)

            gammaValues.forEach { gamma ->
                val leiden = detector.detectCommunities(graph, rbResolution = gamma, randomSeed = 42L)
                val evaluated = centroidEvaluator.evaluateClusters(
                    vectors = vList,
                    communityAssignments = leiden.assignments,
                    centroidThreshold = 0.38,
                )

                evaluatedCells[Pair(alpha, gamma)] = Pair(leiden.quality, evaluated)
                val assignments = evaluated.assignments.associate { it.positionId to it.clusterIndex }
                partitionAssignments[Pair(alpha, gamma)] = assignments
            }
        }

        // 3. Compute partition metrics against baseline (alpha=0.60, gamma=1.25)
        val baselineAssignments = partitionAssignments.getValue(Pair(0.60, 1.25))

        alphaValues.forEach { alpha ->
            gammaValues.forEach { gamma ->
                val (q, evaluated) = evaluatedCells.getValue(Pair(alpha, gamma))
                val assignments = partitionAssignments.getValue(Pair(alpha, gamma))
                val dist = ResearchMetrics.computeDistributionStats(evaluated.clusters.map { it.size })

                val assignedCount = evaluated.assignments.count { it.clusterIndex != null }
                val isolateCount = evaluated.assignments.count { it.status == com.chessecho.domain.clustering.PositionClusterAssignmentStatus.ISOLATE }
                val outlierCount = evaluated.assignments.count { it.status == com.chessecho.domain.clustering.PositionClusterAssignmentStatus.OUTLIER_EVICTED }

                val partMetrics = PartitionDistanceMetrics.evaluate(baselineAssignments, assignments)

                val cell = GridCellResult(
                    alpha = alpha,
                    gammaRb = gamma,
                    totalClusters = evaluated.clusters.size,
                    assignedCount = assignedCount,
                    assignedPct = (assignedCount.toDouble() / corpusDocs.size) * 100.0,
                    isolateCount = isolateCount,
                    outlierCount = outlierCount,
                    meanSize = dist.mean,
                    medianSize = dist.median,
                    p90Size = dist.p90,
                    maxSize = dist.max,
                    modularityQ = q,
                    amiVsBaseline = partMetrics.ami,
                    ariVsBaseline = partMetrics.ari,
                    jaccardAssignedVsBaseline = partMetrics.jaccardAssigned,
                    viVsBaseline = partMetrics.variationOfInformation,
                )
                gridResults.add(cell)
            }
        }

        println("=== 6x6 SENSITIVITY GRID SWEEP COMPLETED (36 cells) ===")
        println("Alpha | Gamma | Clusters | Assigned% | MeanSize | MaxSize | ModularityQ | AMI vs Baseline | Jaccard")
        println("------+-------+----------+-----------+----------+---------+-------------+-----------------+--------")
        gridResults.forEach { c ->
            println("%5.2f | %5.2f | %8d | %8.1f%% | %8.1f | %7d | %11.4f | %15.4f | %7.4f"
                .format(c.alpha, c.gammaRb, c.totalClusters, c.assignedPct, c.meanSize, c.maxSize, c.modularityQ, c.amiVsBaseline, c.jaccardAssignedVsBaseline))
        }

        // 4. Structural anchor vs migrator profiling across alpha
        val migrationProfiles = analyzeAnchorVsMigratingPositions(corpusDocs, partitionAssignments)
        println("Identified ${migrationProfiles.count { it.isAnchor }} structural anchors and ${migrationProfiles.count { it.isMigrator }} migrators.")

        // 5. Serialize full report
        mapper.writeValue(outputFile, mapOf(
            "gridSweep" to gridResults,
            "anchorCount" to migrationProfiles.count { it.isAnchor },
            "migratorCount" to migrationProfiles.count { it.isMigrator },
            "sampleProfiles" to migrationProfiles.take(20),
        ))

        assertTrue(gridResults.size == 36, "Must compute all 36 grid cells")
        val baselineCell = gridResults.first { it.alpha == 0.60 && it.gammaRb == 1.25 }
        assertTrue(baselineCell.amiVsBaseline > 0.99, "Baseline AMI vs itself must be ~1.0")
    }

    private fun analyzeAnchorVsMigratingPositions(
        docs: List<PositionDocument>,
        partitions: Map<Pair<Double, Double>, Map<UUID, Int?>>,
    ): List<PositionMigrationProfile> {
        val gammaRef = 1.25
        val sampleDocs = docs.take(100)
        val profiles = mutableListOf<PositionMigrationProfile>()

        for (doc in sampleDocs) {
            val clustersByAlpha = mutableMapOf<Double, Int?>()
            alphaValues.forEach { a ->
                val p = partitions[Pair(a, gammaRef)]
                clustersByAlpha[a] = p?.get(doc.positionId)
            }

            val assignedValues = clustersByAlpha.values.filterNotNull()
            val isAnchor = assignedValues.size == alphaValues.size && assignedValues.distinct().size == 1
            val isMigrator = assignedValues.distinct().size >= 3

            val notes = when {
                isAnchor -> "Robust structural anchor across pawn-piece balance"
                isMigrator -> "Migrating position: shifts cluster as piece weighting dominates"
                else -> "Intermediate stability"
            }

            profiles.add(
                PositionMigrationProfile(
                    canonicalFen = doc.canonicalFen,
                    eco = doc.canonicalFen.take(3), // heuristic
                    sideToMove = doc.sideToMove,
                    clusterIdByAlpha = clustersByAlpha,
                    isAnchor = isAnchor,
                    isMigrator = isMigrator,
                    notes = notes,
                ),
            )
        }
        return profiles
    }
}
