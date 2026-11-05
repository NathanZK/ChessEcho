package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.RelationalPawnTokenExtractor
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.random.Random

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamicReferenceRankingEvaluatorTest {
    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val cacheFile = File("src/test/resources/research/engine_evaluation_cache_d16_mpv1.json")

    private lateinit var corpusDocs: List<PositionDocument>
    private lateinit var cacheRecords: Map<String, CachedEngineRecord>
    private lateinit var evaluator: DynamicReferenceRankingEvaluator
    private lateinit var probeQueries: List<PositionDocument>

    @BeforeAll
    fun setUp() {
        assertTrue(corpusFile.exists(), "Corpus A file must exist")
        assertTrue(cacheFile.exists(), "Stockfish Depth 16 cache file must exist")

        val corpusBuilder = CorpusBuilder()
        val rawPositions = corpusBuilder.loadCorpusFromJson(corpusFile)
        val extractor = RelationalPawnTokenExtractor()

        corpusDocs = rawPositions.map { pos ->
            val id = DeterministicIdGenerator.generateDeterministicId(pos.canonicalFen, pos.samplePly)
            extractor.extractDocument(id, pos.canonicalFen, includeReachability = true)
        }

        val mapper = jacksonObjectMapper()
        cacheRecords = mapper.readValue(cacheFile)

        evaluator = DynamicReferenceRankingEvaluator(corpusDocs, cacheRecords)

        val rng = Random(42L)
        probeQueries = corpusDocs.shuffled(rng).take(250)
        assertEquals(250, probeQueries.size)
    }

    @Test
    fun `evaluator computes deterministic same-side reference rankings with zero top10 and top20 contamination`() {
        val references = probeQueries.map { q ->
            evaluator.computeReferenceForQuery(q)
        }
        assertEquals(250, references.size)

        val audit = evaluator.buildAuditReport(references)

        assertEquals(250, audit.totalProbeQueries)
        assertEquals(0, audit.bm25Audit.zeroScoreInTop10Count, "BM25 Top-10 must have zero zero-score entries")
        assertEquals(0, audit.bm25Audit.zeroScoreInTop20Count, "BM25 Top-20 must have zero zero-score entries")
        assertEquals(0, audit.tfidfAudit.zeroScoreInTop10Count, "TF-IDF Top-10 must have zero zero-score entries")
        assertEquals(0, audit.tfidfAudit.zeroScoreInTop20Count, "TF-IDF Top-20 must have zero zero-score entries")

        assertTrue(audit.bm25Audit.minTop10Score > 0.0, "BM25 min rank-10 score must be > 0.0")
        assertTrue(audit.tfidfAudit.minTop10Score > 0.0, "TF-IDF min rank-10 score must be > 0.0")

        println("Audit validation successful:")
        println("BM25: mean positive=${audit.bm25Audit.meanPositiveScoreCandidates}, min top10=${audit.bm25Audit.minTop10Score}")
        println("TF-IDF: mean positive=${audit.tfidfAudit.meanPositiveScoreCandidates}, min top10=${audit.tfidfAudit.minTop10Score}")
    }
}
