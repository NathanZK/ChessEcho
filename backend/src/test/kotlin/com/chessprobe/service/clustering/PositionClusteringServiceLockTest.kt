package com.chessprobe.service.clustering

import com.chessprobe.config.PositionClusteringProperties
import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.clustering.ClusterType
import com.chessprobe.domain.clustering.PositionClusterSnapshot
import com.chessprobe.domain.clustering.PositionClusterSnapshotStatus
import com.chessprobe.repository.ChessAccountRepository
import com.chessprobe.repository.clustering.PositionClusterAssignmentRepository
import com.chessprobe.repository.clustering.PositionClusterRepository
import com.chessprobe.repository.clustering.PositionClusterSnapshotRepository
import com.chessprobe.service.similarity.PersonalDynamicCorpusProvider
import com.chessprobe.service.similarity.PersonalPositionCorpusProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PositionClusteringServiceLockTest {
    private lateinit var service: PositionClusteringService
    private lateinit var snapshotRepository: PositionClusterSnapshotRepository
    private lateinit var clusterRepository: PositionClusterRepository
    private lateinit var assignmentRepository: PositionClusterAssignmentRepository
    private lateinit var accountRepository: ChessAccountRepository
    private lateinit var corpusProvider: PersonalPositionCorpusProvider
    private lateinit var dynamicCorpusProvider: PersonalDynamicCorpusProvider
    private lateinit var transactionManager: PlatformTransactionManager

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
    fun `withAccountLock permits concurrent execution for different cluster types on same account`() {
        val accountId = UUID.randomUUID()
        val structuralStarted = CountDownLatch(1)
        val dynamicStarted = CountDownLatch(1)
        val releaseStructural = CountDownLatch(1)
        val releaseDynamic = CountDownLatch(1)
        val dynamicCompleted = AtomicBoolean(false)

        val threadStructural =
            Thread {
                service.withAccountLock(accountId, ClusterType.STRUCTURAL) {
                    structuralStarted.countDown()
                    releaseStructural.await(5, TimeUnit.SECONDS)
                }
            }

        val threadDynamic =
            Thread {
                structuralStarted.await(5, TimeUnit.SECONDS)
                service.withAccountLock(accountId, ClusterType.DYNAMIC) {
                    dynamicStarted.countDown()
                    releaseDynamic.await(5, TimeUnit.SECONDS)
                    dynamicCompleted.set(true)
                }
            }

        threadStructural.start()
        threadDynamic.start()

        assertTrue(dynamicStarted.await(5, TimeUnit.SECONDS))
        releaseStructural.countDown()
        releaseDynamic.countDown()
        threadStructural.join(5000)
        threadDynamic.join(5000)

        assertTrue(dynamicCompleted.get())
    }

    @Test
    fun `withAccountLock rejects concurrent execution for same cluster type on same account`() {
        val accountId = UUID.randomUUID()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        val thread =
            Thread {
                service.withAccountLock(accountId, ClusterType.STRUCTURAL) {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        thread.start()
        started.await(5, TimeUnit.SECONDS)

        val ex =
            assertThrows(IllegalStateException::class.java) {
                service.withAccountLock(accountId, ClusterType.STRUCTURAL) {
                    // should not reach
                }
            }
        assertTrue(ex.message!!.contains("Clustering generation already in progress"))

        release.countDown()
        thread.join(5000)

        // After completion, acquiring the lock again succeeds
        val ran = service.withAccountLock(accountId, ClusterType.STRUCTURAL) { "ok" }
        assertEquals("ok", ran)
    }

    @Test
    fun `getOrGenerateActiveSnapshot returns existing active snapshot without generating`() {
        val accountId = UUID.randomUUID()
        val existing =
            PositionClusterSnapshot(
                id = UUID.randomUUID(),
                accountId = accountId,
                snapshotVersion = 1,
                clusterType = ClusterType.STRUCTURAL,
                status = PositionClusterSnapshotStatus.ACTIVE,
            )
        whenever(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                accountId,
                ClusterType.STRUCTURAL,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(existing)

        val result = service.getOrGenerateActiveSnapshot(accountId, ClusterType.STRUCTURAL)
        assertEquals(existing.id, result.id)
    }

    @Test
    fun `getOrGenerateActiveSnapshot generates new active snapshot when absent`() {
        val accountId = UUID.randomUUID()
        val account = ChessAccount(id = accountId, platform = "CHESS_COM", username = "testuser")
        whenever(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                accountId,
                ClusterType.STRUCTURAL,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(null)
        whenever(accountRepository.findById(accountId)).thenReturn(Optional.of(account))
        whenever(transactionManager.getTransaction(any())).thenReturn(SimpleTransactionStatus())
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(emptyList())

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
        whenever(snapshotRepository.findMaxVersionByAccountIdAndClusterType(eq(accountId), eq(ClusterType.STRUCTURAL))).thenReturn(0)

        val result = service.getOrGenerateActiveSnapshot(accountId, ClusterType.STRUCTURAL)
        assertEquals(PositionClusterSnapshotStatus.ACTIVE, result.status)
        assertEquals(ClusterType.STRUCTURAL, result.clusterType)
    }
}
