package com.chessecho.integration.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Platform
import com.chessecho.domain.clustering.PositionClusterSnapshotStatus
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.clustering.PositionClusterAssignmentRepository
import com.chessecho.repository.clustering.PositionClusterRepository
import com.chessecho.repository.clustering.PositionClusterSnapshotRepository
import com.chessecho.service.clustering.PositionClusteringService
import com.chessecho.service.similarity.PersonalPositionCorpusProvider
import com.chessecho.similarity.PositionDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
    }

    class StubPersonalPositionCorpusProvider : PersonalPositionCorpusProvider {
        val accountPositions = ConcurrentHashMap<UUID, List<PositionDocument>>()

        override fun loadPersonalPositions(account: ChessAccount): List<PositionDocument> = accountPositions[account.id] ?: emptyList()
    }

    @Autowired
    private lateinit var clusteringService: PositionClusteringService

    @Autowired
    private lateinit var corpusProvider: PersonalPositionCorpusProvider

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
}
