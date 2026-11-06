package com.chessecho.research

import com.chessecho.similarity.RelationalPawnTokenExtractor
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Random

class ProbeQueryIdentityVerificationTest {

    private val mapper = jacksonObjectMapper()
    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val stageAnalysisFile = File("src/test/resources/research/stage_by_stage_retention_analysis.json")

    @Test
    fun `verify exact Q250 query identities against Issue 722 artifacts`() {
        val corpusBuilder = CorpusBuilder()
        val rawPositions = corpusBuilder.loadCorpusFromJson(corpusFile)
        val extractor = RelationalPawnTokenExtractor()

        val corpusDocs = rawPositions.map { pos ->
            val id = DeterministicIdGenerator.generateDeterministicId(pos.canonicalFen, pos.samplePly)
            extractor.extractDocument(id, pos.canonicalFen, includeReachability = true)
        }
        assertEquals(10_000, corpusDocs.size)

        // Try java.util.Random(42L)
        val javaRng = java.util.Random(42L)
        val javaProbeQueries = corpusDocs.shuffled(javaRng).take(250)

        // Try kotlin.random.Random(42L)
        val kotlinRng = kotlin.random.Random(42L)
        val kotlinProbeQueries = corpusDocs.shuffled(kotlinRng).take(250)

        val javaProbeIds = javaProbeQueries.map { it.positionId.toString() }.toSet()
        val kotlinProbeIds = kotlinProbeQueries.map { it.positionId.toString() }.toSet()

        println("Q250 Probe Query Verification:")
        println("  Java Random Q250: ${javaProbeQueries.size} queries")
        println("  Kotlin Random Q250: ${kotlinProbeQueries.size} queries")

        // Cross-reference against stage_by_stage_retention_analysis.json examples
        if (stageAnalysisFile.exists()) {
            val root: Map<String, Any> = mapper.readValue(stageAnalysisFile)
            @Suppress("UNCHECKED_CAST")
            val examples = root["examples"] as? List<Map<String, Any>> ?: emptyList()
            println("  Stage analysis examples count: ${examples.size}")

            val javaMatches = examples.count { javaProbeIds.contains(it["queryId"] as String) }
            val kotlinMatches = examples.count { kotlinProbeIds.contains(it["queryId"] as String) }

            println("  Java Random matches: $javaMatches / ${examples.size}")
            println("  Kotlin Random matches: $kotlinMatches / ${examples.size}")
        }

        fun analyzePool(name: String, pool: List<com.chessecho.similarity.PositionDocument>) {
            val white = pool.count { it.fen.split("\\s+".toRegex())[1].lowercase() == "w" }
            val black = pool.count { it.fen.split("\\s+".toRegex())[1].lowercase() == "b" }
            val universes = pool.map { q ->
                val side = q.fen.split("\\s+".toRegex())[1].lowercase()
                corpusDocs.count { it.canonicalFen != q.canonicalFen && it.fen.split("\\s+".toRegex())[1].lowercase() == side }
            }
            println("  Pool $name: white=$white, black=$black, meanUniverse=${universes.average()}")
        }

        analyzePool("Java Random", javaProbeQueries)
        analyzePool("Kotlin Random", kotlinProbeQueries)
    }
}
