package com.chessecho.integration.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Platform
import com.chessecho.domain.clustering.ClusterType
import com.chessecho.domain.clustering.PositionClusterAssignmentStatus
import com.chessecho.domain.clustering.PositionClusterSnapshotStatus
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.clustering.PositionClusterAssignmentRepository
import com.chessecho.repository.clustering.PositionClusterRepository
import com.chessecho.repository.clustering.PositionClusterSnapshotRepository
import com.chessecho.service.clustering.PositionClusteringService
import com.chessecho.service.similarity.PersonalDynamicCorpusProvider
import com.chessecho.service.similarity.PersonalPositionCorpusProvider
import com.chessecho.similarity.PositionDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertFailsWith

@SpringBootTest
@ActiveProfiles("test")
class PositionClusteringServiceIntegrationTest {
    @TestConfiguration
    class TestCorpusConfig {
        @Bean
        @Primary
        fun testCorpusProvider(): PersonalPositionCorpusProvider = StubPersonalPositionCorpusProvider()

        @Bean
        @Primary
        fun testDynamicCorpusProvider(): PersonalDynamicCorpusProvider = StubPersonalDynamicCorpusProvider()
    }

    class StubPersonalPositionCorpusProvider : PersonalPositionCorpusProvider {
        val accountPositions = ConcurrentHashMap<UUID, List<PositionDocument>>()

        override fun loadPersonalPositions(account: ChessAccount): List<PositionDocument> = accountPositions[account.id] ?: emptyList()
    }

    class StubPersonalDynamicCorpusProvider : PersonalDynamicCorpusProvider {
        val accountDynamicPositions = ConcurrentHashMap<UUID, List<PositionDocument>>()

        override fun loadPersonalDynamicPositions(account: ChessAccount): List<PositionDocument> =
            accountDynamicDynamicPositions(account.id)

        private fun accountDynamicDynamicPositions(accountId: UUID): List<PositionDocument> =
            accountDynamicPositions[accountId] ?: emptyList()
    }

    @Autowired
    private lateinit var clusteringService: PositionClusteringService

    @Autowired
    private lateinit var corpusProvider: PersonalPositionCorpusProvider

    @Autowired
    private lateinit var dynamicCorpusProvider: PersonalDynamicCorpusProvider

    @Autowired
    private lateinit var accountRepository: ChessAccountRepository

    @Autowired
    private lateinit var snapshotRepository: PositionClusterSnapshotRepository

    @Autowired
    private lateinit var clusterRepository: PositionClusterRepository

    @Autowired
    private lateinit var assignmentRepository: PositionClusterAssignmentRepository

    private val stubCorpusProvider: StubPersonalPositionCorpusProvider
        get() = corpusProvider as StubPersonalPositionCorpusProvider

    private val stubDynamicCorpusProvider: StubPersonalDynamicCorpusProvider
        get() = dynamicCorpusProvider as StubPersonalDynamicCorpusProvider

    private fun createAccount(): ChessAccount =
        accountRepository.save(
            ChessAccount(
                platform = Platform.CHESS_COM.name,
                username = "test_user_${UUID.randomUUID().toString().take(8)}",
            ),
        )

    private fun createPositionDoc(
        fen: String,
        pawnTokens: Set<String>,
        pieceTokens: Set<String>,
    ): PositionDocument {
        val terms = (pawnTokens + pieceTokens).associateWith { 1.0 }
        return PositionDocument(
            positionId = UUID.randomUUID(),
            fen = "$fen w - - 0 1",
            canonicalFen = "$fen w - - 0 1",
            terms = terms,
        )
    }

    @Test
    fun `generates active cluster snapshot for account with positions and supersedes existing active snapshot`() {
        val account = createAccount()

        // Create 6 positions: 3 Italian-like, 3 French-like
        val p1 = createPositionDoc("fen_it_1", setOf("P(e4)", "P(c3)"), setOf("N(f3)", "B(c4)"))
        val p2 = createPositionDoc("fen_it_2", setOf("P(e4)", "P(c3)"), setOf("N(f3)", "B(c4)"))
        val p3 = createPositionDoc("fen_it_3", setOf("P(e4)", "P(c3)"), setOf("N(f3)", "B(b5)"))

        val p4 = createPositionDoc("fen_fr_1", setOf("P(e6)", "P(d5)"), setOf("N(d2)", "B(d3)"))
        val p5 = createPositionDoc("fen_fr_2", setOf("P(e6)", "P(d5)"), setOf("N(d2)", "B(d3)"))
        val p6 = createPositionDoc("fen_fr_3", setOf("P(e6)", "P(d5)"), setOf("N(f3)", "B(d3)"))

        stubCorpusProvider.accountPositions[account.id] = listOf(p1, p2, p3, p4, p5, p6)

        // 1. First generation: promoteToActive = true
        val snapshot1 = clusteringService.generateClusterSnapshot(account.id, promoteToActive = true).join()
        assertEquals(PositionClusterSnapshotStatus.ACTIVE, snapshot1.status)
        assertEquals(1, snapshot1.snapshotVersion)
        assertEquals(6, snapshot1.totalPositions)
        assertTrue(snapshot1.clusterCount >= 1)
        assertNotNull(snapshot1.completedAt)

        val activeSnapshot1 = clusteringService.getActiveSnapshot(account.id)
        assertNotNull(activeSnapshot1)
        assertEquals(snapshot1.id, activeSnapshot1!!.id)

        val clusters1 = clusteringService.getClustersForSnapshot(snapshot1.id)
        assertEquals(snapshot1.clusterCount, clusters1.size)

        // 2. Second generation: promoteToActive = true (supersedes snapshot1)
        val snapshot2 = clusteringService.generateClusterSnapshot(account.id, promoteToActive = true).join()
        assertEquals(PositionClusterSnapshotStatus.ACTIVE, snapshot2.status)
        assertEquals(2, snapshot2.snapshotVersion)

        // Verify snapshot1 was superseded
        val updatedSnapshot1 = snapshotRepository.findById(snapshot1.id).orElseThrow()
        assertEquals(PositionClusterSnapshotStatus.SUPERSEDED, updatedSnapshot1.status)

        // Verify active snapshot is now snapshot2
        val activeSnapshot2 = clusteringService.getActiveSnapshot(account.id)
        assertNotNull(activeSnapshot2)
        assertEquals(snapshot2.id, activeSnapshot2!!.id)
    }

    @Test
    fun `generates experimental snapshot without superseding active snapshot`() {
        val account = createAccount()
        val p1 = createPositionDoc("fen_1", setOf("P(e4)"), setOf("N(f3)"))
        val p2 = createPositionDoc("fen_2", setOf("P(e4)"), setOf("N(f3)"))
        stubCorpusProvider.accountPositions[account.id] = listOf(p1, p2)

        // 1. Establish ACTIVE baseline
        val activeSnapshot = clusteringService.generateClusterSnapshot(account.id, promoteToActive = true).join()
        assertEquals(PositionClusterSnapshotStatus.ACTIVE, activeSnapshot.status)

        // 2. Run EXPERIMENTAL run
        val expSnapshot = clusteringService.generateClusterSnapshot(account.id, promoteToActive = false).join()
        assertEquals(PositionClusterSnapshotStatus.EXPERIMENTAL, expSnapshot.status)

        // 3. Active snapshot remains untouched
        val activeAfter = clusteringService.getActiveSnapshot(account.id)
        assertNotNull(activeAfter)
        assertEquals(activeSnapshot.id, activeAfter!!.id)
        assertEquals(PositionClusterSnapshotStatus.ACTIVE, activeAfter.status)

        // 4. Downstream query still works on experimental snapshot
        val expClusters = clusteringService.getClustersForSnapshot(expSnapshot.id)
        assertEquals(expSnapshot.clusterCount, expClusters.size)
    }

    @Test
    fun `empty account completes immediately with zero counts`() {
        val account = createAccount()
        stubCorpusProvider.accountPositions[account.id] = emptyList()

        val snapshot = clusteringService.generateClusterSnapshot(account.id, promoteToActive = true).join()
        assertEquals(PositionClusterSnapshotStatus.ACTIVE, snapshot.status)
        assertEquals(0, snapshot.totalPositions)
        assertEquals(0, snapshot.clusterCount)
        assertEquals(0, snapshot.unassignedCount)
        assertNotNull(snapshot.completedAt)

        val clusters = clusteringService.getClustersForSnapshot(snapshot.id)
        assertTrue(clusters.isEmpty())
    }

    @Test
    fun `duplicate concurrent clustering on same account is rejected`() {
        val account = createAccount()
        stubCorpusProvider.accountPositions[account.id] = emptyList()

        // Test the concurrency guard directly
        assertFailsWith<IllegalStateException> {
            clusteringService.withAccountLock(account.id) {
                // Inside lock, nested attempt must fail
                clusteringService.withAccountLock(account.id) {}
            }
        }
    }

    @Test
    fun `account can concurrently maintain both ACTIVE structural and ACTIVE dynamic snapshots without collision`() {
        val account = createAccount()

        val p1 = createPositionDoc("fen_s1", setOf("P(e4)"), setOf("N(f3)"))
        val p2 = createPositionDoc("fen_s2", setOf("P(e4)"), setOf("N(f3)"))
        stubCorpusProvider.accountPositions[account.id] = listOf(p1, p2)

        val d1 =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "8/8/8/3n4/8/8/8/8 w - - 0 1",
                canonicalFen = "8/8/8/3n4/8/8/8/8 w - -",
                terms = mapOf("T:fork" to 2.0, "M:d5c7" to 1.0),
            )
        val d2 =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "8/8/8/4n3/8/8/8/8 w - - 0 1",
                canonicalFen = "8/8/8/4n3/8/8/8/8 w - -",
                terms = mapOf("T:fork" to 2.0, "M:e5f7" to 1.0),
            )
        stubDynamicCorpusProvider.accountDynamicPositions[account.id] = listOf(d1, d2)

        // 1. Generate active STRUCTURAL snapshot
        val structSnap1 =
            clusteringService
                .generateClusterSnapshot(account.id, clusterType = ClusterType.STRUCTURAL, promoteToActive = true)
                .join()
        assertEquals(PositionClusterSnapshotStatus.ACTIVE, structSnap1.status)
        assertEquals(ClusterType.STRUCTURAL, structSnap1.clusterType)

        // 2. Generate active DYNAMIC snapshot
        val dynSnap1 =
            clusteringService
                .generateClusterSnapshot(account.id, clusterType = ClusterType.DYNAMIC, promoteToActive = true)
                .join()
        assertEquals(PositionClusterSnapshotStatus.ACTIVE, dynSnap1.status)
        assertEquals(ClusterType.DYNAMIC, dynSnap1.clusterType)

        // 3. Both remain ACTIVE concurrently in DB
        val activeStruct = clusteringService.getActiveSnapshot(account.id, ClusterType.STRUCTURAL)
        val activeDyn = clusteringService.getActiveSnapshot(account.id, ClusterType.DYNAMIC)
        assertNotNull(activeStruct)
        assertNotNull(activeDyn)
        assertEquals(structSnap1.id, activeStruct!!.id)
        assertEquals(dynSnap1.id, activeDyn!!.id)

        // 4. Generating a second dynamic snapshot supersedes dynamic snapshot 1, but leaves structural snapshot ACTIVE
        val dynSnap2 =
            clusteringService
                .generateClusterSnapshot(account.id, clusterType = ClusterType.DYNAMIC, promoteToActive = true)
                .join()
        assertEquals(PositionClusterSnapshotStatus.ACTIVE, dynSnap2.status)
        assertEquals(2, dynSnap2.snapshotVersion)

        val supersededDyn1 = snapshotRepository.findById(dynSnap1.id).orElseThrow()
        assertEquals(PositionClusterSnapshotStatus.SUPERSEDED, supersededDyn1.status)

        val structStillActive = clusteringService.getActiveSnapshot(account.id, ClusterType.STRUCTURAL)
        assertNotNull(structStillActive)
        assertEquals(structSnap1.id, structStillActive!!.id)
        assertEquals(PositionClusterSnapshotStatus.ACTIVE, structStillActive.status)

        // 5. Verify snapshot histories are strictly isolated and ordered by version descending per cluster type
        val dynHistory = snapshotRepository.findByAccountIdAndClusterTypeOrderBySnapshotVersionDesc(account.id, ClusterType.DYNAMIC)
        assertEquals(2, dynHistory.size)
        assertEquals(2, dynHistory[0].snapshotVersion)
        assertEquals(1, dynHistory[1].snapshotVersion)

        val structHistory = snapshotRepository.findByAccountIdAndClusterTypeOrderBySnapshotVersionDesc(account.id, ClusterType.STRUCTURAL)
        assertEquals(1, structHistory.size)
        assertEquals(1, structHistory[0].snapshotVersion)
    }

    @Test
    fun `dynamic clustering persists un-evaluated positions as ISOLATE assignments and maintains total position count`() {
        val account = createAccount()

        val evaluated1 =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "8/8/8/3n4/8/8/8/8 w - - 0 1",
                canonicalFen = "8/8/8/3n4/8/8/8/8 w - -",
                terms = mapOf("T:pin" to 1.0, "M:e1e4" to 1.0),
            )
        val evaluated2 =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "8/8/8/4n3/8/8/8/8 w - - 0 1",
                canonicalFen = "8/8/8/4n3/8/8/8/8 w - -",
                terms = mapOf("T:pin" to 1.0, "M:e1e5" to 1.0),
            )
        val unEvaluated =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "8/8/8/8/8/8/8/8 w - - 0 1",
                canonicalFen = "8/8/8/8/8/8/8/8 w - -",
                terms = emptyMap(),
            )

        stubDynamicCorpusProvider.accountDynamicPositions[account.id] = listOf(evaluated1, evaluated2, unEvaluated)

        val snapshot =
            clusteringService
                .generateClusterSnapshot(account.id, clusterType = ClusterType.DYNAMIC, promoteToActive = true)
                .join()

        assertEquals(PositionClusterSnapshotStatus.ACTIVE, snapshot.status)
        assertEquals(3, snapshot.totalPositions)

        val assignments = assignmentRepository.findByIdSnapshotId(snapshot.id)
        assertEquals(3, assignments.size)

        val unEvalAssignment = assignments.first { it.id.positionId == unEvaluated.positionId }
        assertEquals(PositionClusterAssignmentStatus.ISOLATE, unEvalAssignment.assignmentStatus)
        assertNull(unEvalAssignment.clusterId)
        assertNull(unEvalAssignment.centroidSimilarity)
    }
}
