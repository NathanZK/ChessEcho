package com.chessecho.service.clustering

import com.chessecho.config.PositionClusteringProperties
import com.chessecho.domain.clustering.ClusterType
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.clustering.PositionClusterAssignmentRepository
import com.chessecho.repository.clustering.PositionClusterRepository
import com.chessecho.repository.clustering.PositionClusterSnapshotRepository
import com.chessecho.service.similarity.PersonalDynamicCorpusProvider
import com.chessecho.service.similarity.PersonalPositionCorpusProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.transaction.PlatformTransactionManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PositionClusteringServiceLockTest {
    private lateinit var service: PositionClusteringService

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
}
