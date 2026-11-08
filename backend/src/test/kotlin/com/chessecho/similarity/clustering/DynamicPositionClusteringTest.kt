package com.chessecho.similarity.clustering

import com.chessecho.config.PositionClusteringProperties
import com.chessecho.domain.clustering.ClusterType
import com.chessecho.domain.clustering.PositionClusterAssignmentStatus
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.clustering.PositionClusterAssignmentRepository
import com.chessecho.repository.clustering.PositionClusterRepository
import com.chessecho.repository.clustering.PositionClusterSnapshotRepository
import com.chessecho.service.clustering.PositionClusteringService
import com.chessecho.service.similarity.PersonalDynamicCorpusProvider
import com.chessecho.service.similarity.PersonalPositionCorpusProvider
import com.chessecho.similarity.PositionDocument
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.transaction.PlatformTransactionManager
import java.util.UUID

class DynamicPositionClusteringTest {
    private lateinit var service: PositionClusteringService
    private val scorer = PositionClusterSimilarityScorer()

    @BeforeEach
    fun setUp() {
        val snapshotRepository = mock(PositionClusterSnapshotRepository::class.java)
        val clusterRepository = mock(PositionClusterRepository::class.java)
        val assignmentRepository = mock(PositionClusterAssignmentRepository::class.java)
        val accountRepository = mock(ChessAccountRepository::class.java)
        val corpusProvider = mock(PersonalPositionCorpusProvider::class.java)
        val dynamicCorpusProvider = mock(PersonalDynamicCorpusProvider::class.java)
        val properties = PositionClusteringProperties()
        val objectMapper = ObjectMapper()
        val transactionManager = mock(PlatformTransactionManager::class.java)

        service =
            PositionClusteringService(
                snapshotRepository = snapshotRepository,
                clusterRepository = clusterRepository,
                assignmentRepository = assignmentRepository,
                accountRepository = accountRepository,
                corpusProvider = corpusProvider,
                dynamicCorpusProvider = dynamicCorpusProvider,
                properties = properties,
                objectMapper = objectMapper,
                transactionManager = transactionManager,
            )
    }

    @Test
    fun `dynamic vector construction produces unit normalized vectors and accurate cosine similarity`() {
        val doc1 =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                canonicalFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3",
                terms = mapOf("M:e7e5" to 1.0, "A:N->Q" to 1.0),
            )
        val doc2 =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
                canonicalFen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6",
                terms = mapOf("M:e7e5" to 1.0, "A:N->Q" to 1.0),
            )
        val emptyDoc =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "8/8/8/8/8/8/8/8 w - - 0 1",
                canonicalFen = "8/8/8/8/8/8/8/8 w - -",
                terms = emptyMap(),
            )

        val vectors = service.buildDynamicVectors(listOf(doc1, doc2, emptyDoc))
        assertEquals(3, vectors.size)

        val v1 = vectors[0]
        val v2 = vectors[1]
        val vEmpty = vectors[2]

        assertEquals(1.0, v1.totalNorm, 1e-6)
        assertEquals(1.0, v2.totalNorm, 1e-6)
        assertEquals(0.0, vEmpty.totalNorm, 1e-6)

        // Exact match should have similarity 1.0
        val sim = scorer.cosineSimilarity(v1, v2)
        assertEquals(1.0, sim, 1e-6)

        // Similarity with empty document should be 0.0
        val simEmpty = scorer.cosineSimilarity(v1, vEmpty)
        assertEquals(0.0, simEmpty, 1e-6)
    }

    @Test
    fun `computeDynamicClustering groups matching dynamic positions and marks un-evaluated positions as isolates`() {
        val p1 = UUID.randomUUID()
        val p2 = UUID.randomUUID()
        val p3 = UUID.randomUUID()
        val pUnEval = UUID.randomUUID()

        // p1 and p2 share motif "FORK_KNIGHT" and "CHECK"
        val doc1 =
            PositionDocument(
                positionId = p1,
                fen = "8/8/8/3n4/8/8/8/8 w - - 0 1",
                canonicalFen = "8/8/8/3n4/8/8/8/8 w - -",
                terms = mapOf("T:fork_knight" to 2.0, "T:check" to 1.0, "M:d5c7" to 1.0),
            )
        val doc2 =
            PositionDocument(
                positionId = p2,
                fen = "8/8/8/4n3/8/8/8/8 w - - 0 1",
                canonicalFen = "8/8/8/4n3/8/8/8/8 w - -",
                terms = mapOf("T:fork_knight" to 2.0, "T:check" to 1.0, "M:e5f7" to 1.0),
            )
        // p3 shares fork motif
        val doc3 =
            PositionDocument(
                positionId = p3,
                fen = "8/8/8/5n2/8/8/8/8 w - - 0 1",
                canonicalFen = "8/8/8/5n2/8/8/8/8 w - -",
                terms = mapOf("T:fork_knight" to 2.0, "T:check" to 1.0),
            )
        // un-evaluated position
        val docUnEval =
            PositionDocument(
                positionId = pUnEval,
                fen = "8/8/8/8/8/8/8/8 w - - 0 1",
                canonicalFen = "8/8/8/8/8/8/8/8 w - -",
                terms = emptyMap(),
            )

        val result = service.computeClustering(listOf(doc1, doc2, doc3, docUnEval), ClusterType.DYNAMIC)

        // Every position must receive an assignment
        assertEquals(4, result.assignments.size)

        val unEvalAssignment = result.assignments.first { it.positionId == pUnEval }
        assertEquals(PositionClusterAssignmentStatus.ISOLATE, unEvalAssignment.status)
        assertNull(unEvalAssignment.clusterIndex)
        assertNull(unEvalAssignment.centroidSimilarity)
    }
}
