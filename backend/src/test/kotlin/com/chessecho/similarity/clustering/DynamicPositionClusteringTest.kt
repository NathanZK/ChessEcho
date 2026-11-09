package com.chessecho.similarity.clustering

import com.chessecho.config.PositionClusteringProperties
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.clustering.ClusterType
import com.chessecho.domain.clustering.PositionCluster
import com.chessecho.domain.clustering.PositionClusterAssignmentStatus
import com.chessecho.domain.clustering.PositionClusterSnapshot
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import java.util.Optional
import java.util.UUID

class DynamicPositionClusteringTest {
    private lateinit var service: PositionClusteringService
    private lateinit var snapshotRepository: PositionClusterSnapshotRepository
    private lateinit var clusterRepository: PositionClusterRepository
    private lateinit var assignmentRepository: PositionClusterAssignmentRepository
    private lateinit var accountRepository: ChessAccountRepository
    private lateinit var corpusProvider: PersonalPositionCorpusProvider
    private lateinit var dynamicCorpusProvider: PersonalDynamicCorpusProvider
    private lateinit var transactionManager: PlatformTransactionManager
    private val scorer = PositionClusterSimilarityScorer()

    @BeforeEach
    fun setUp() {
        snapshotRepository = mock()
        clusterRepository = mock()
        assignmentRepository = mock()
        accountRepository = mock()
        corpusProvider = mock()
        dynamicCorpusProvider = mock()
        val properties = PositionClusteringProperties()
        val objectMapper = ObjectMapper()
        transactionManager = mock()

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

    private fun createDynamicDoc(
        id: UUID,
        square: String,
    ): PositionDocument =
        PositionDocument(
            positionId = id,
            fen = "8/8/8/$square/8/8/8/8 w - - 0 1",
            canonicalFen = "8/8/8/$square/8/8/8/8 w - -",
            terms = mapOf("M:e7e5" to 1.0, "A:N->Q" to 1.0),
        )

    @Test
    fun `generateClusterSnapshot with DYNAMIC clusterType dispatches computeDynamicClustering and detects clusters`() {
        val accountId = UUID.randomUUID()
        val account = ChessAccount(id = accountId, platform = "CHESS_COM", username = "testuser")
        whenever(accountRepository.findById(accountId)).thenReturn(Optional.of(account))
        whenever(transactionManager.getTransaction(any())).thenReturn(SimpleTransactionStatus())

        val docs = listOf("3n4", "4n3", "5n2").map { createDynamicDoc(UUID.randomUUID(), it) }
        whenever(dynamicCorpusProvider.loadPersonalDynamicPositions(account)).thenReturn(docs)

        val snapshotMap = mutableMapOf<UUID, PositionClusterSnapshot>()
        whenever(snapshotRepository.save(any())).thenAnswer {
            val s = it.getArgument<PositionClusterSnapshot>(0)
            snapshotMap[s.id] = s
            s
        }
        whenever(snapshotRepository.findById(any())).thenAnswer {
            val id = it.getArgument<UUID>(0)
            Optional.ofNullable(snapshotMap[id])
        }
        whenever(snapshotRepository.findMaxVersionByAccountIdAndClusterType(eq(accountId), eq(ClusterType.DYNAMIC))).thenReturn(0)
        whenever(clusterRepository.save(any())).thenAnswer {
            val c = it.getArgument<PositionCluster>(0)
            PositionCluster(
                id = UUID.randomUUID(),
                snapshotId = c.snapshotId,
                clusterIndex = c.clusterIndex,
                size = c.size,
                centroidVector = c.centroidVector,
            )
        }

        val snapshotFuture = service.generateClusterSnapshot(accountId, ClusterType.DYNAMIC, promoteToActive = true)
        val resultSnapshot = snapshotFuture.join()

        assertEquals(ClusterType.DYNAMIC, resultSnapshot.clusterType)
        assertEquals(3, resultSnapshot.totalPositions)
        assertTrue(resultSnapshot.clusterCount >= 1, "Dynamic clustering must produce at least 1 cluster from matching terms")
    }
}
