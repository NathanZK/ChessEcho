package com.chessecho.service.weakness

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.CommunityPatternClassification
import com.chessecho.domain.DecisionSeverity
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MistakeSeverity
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.clustering.ClusterType
import com.chessecho.domain.clustering.PositionCluster
import com.chessecho.domain.clustering.PositionClusterAssignment
import com.chessecho.domain.clustering.PositionClusterAssignmentId
import com.chessecho.domain.clustering.PositionClusterAssignmentStatus
import com.chessecho.domain.clustering.PositionClusterSnapshot
import com.chessecho.domain.clustering.PositionClusterSnapshotStatus
import com.chessecho.dto.RecurringDiscoveryRequest
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.clustering.PositionClusterAssignmentRepository
import com.chessecho.repository.clustering.PositionClusterRepository
import com.chessecho.repository.clustering.PositionClusterSnapshotRepository
import com.chessecho.service.clustering.CommunityWeaknessProfile
import com.chessecho.service.clustering.CommunityWeaknessService
import com.chessecho.service.clustering.PositionClusteringService
import com.chessecho.service.similarity.SimilarityConcurrencyManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID

class RecurringWeaknessDiscoveryFilteringAndRankingTest {
    private val occurrenceRepository = mock(PositionOccurrenceRepository::class.java)
    private val engineAnalysisRepository = mock(EngineAnalysisRepository::class.java)
    private val snapshotRepository = mock(PositionClusterSnapshotRepository::class.java)
    private val clusterRepository = mock(PositionClusterRepository::class.java)
    private val assignmentRepository = mock(PositionClusterAssignmentRepository::class.java)
    private val communityWeaknessService = mock(CommunityWeaknessService::class.java)
    private val concurrencyManager = mock(SimilarityConcurrencyManager::class.java)
    private val clusteringService = mock(PositionClusteringService::class.java)

    private lateinit var service: RecurringWeaknessDiscoveryService
    private val testAccount =
        ChessAccount(
            id = UUID.randomUUID(),
            platform = "CHESS_COM",
            username = "grandmaster",
        )

    @BeforeEach
    fun setup() {
        service =
            RecurringWeaknessDiscoveryService(
                occurrenceRepository = occurrenceRepository,
                engineAnalysisRepository = engineAnalysisRepository,
                snapshotRepository = snapshotRepository,
                clusterRepository = clusterRepository,
                assignmentRepository = assignmentRepository,
                communityWeaknessService = communityWeaknessService,
                concurrencyManager = concurrencyManager,
                clusteringService = clusteringService,
            )
        `when`(
            concurrencyManager.withPermitOrFallback<Boolean>(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
            ),
        ).thenAnswer {
            val onPermitAcquired = it.getArgument<() -> Boolean>(0)
            onPermitAcquired()
        }
    }

    @Test
    fun `dual snapshot resolution merges structural and dynamic clusters when clusterType is omitted`() {
        val structCluster = mockSnapshotAndClusterForType(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ClusterType.STRUCTURAL)
        val dynCluster = mockSnapshotAndClusterForType(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ClusterType.DYNAMIC)

        mockConcurrencyAndProfile(structCluster)
        mockConcurrencyAndProfile(dynCluster)

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest(timeControls = listOf("RAPID")))
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val weaknesses = service.getLatestRecurringWeaknesses(testAccount)
        assertEquals(2, weaknesses.size)
        val types = weaknesses.map { it.clusterType }.toSet()
        assertTrue(types.contains(ClusterType.STRUCTURAL))
        assertTrue(types.contains(ClusterType.DYNAMIC))
    }

    @Test
    fun `discovery restricts resolution to specified clusterType when clusterType is provided`() {
        mockSnapshotAndClusterForType(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ClusterType.STRUCTURAL)
        val dynClusterId = UUID.randomUUID()
        val dynCluster = mockSnapshotAndClusterForType(UUID.randomUUID(), dynClusterId, UUID.randomUUID(), ClusterType.DYNAMIC)
        mockConcurrencyAndProfile(dynCluster)

        val response =
            service.triggerDiscovery(
                testAccount,
                RecurringDiscoveryRequest(clusterType = ClusterType.DYNAMIC, timeControls = listOf("RAPID")),
            )
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val weaknesses = service.getLatestRecurringWeaknesses(testAccount)
        assertEquals(1, weaknesses.size)
        assertEquals(ClusterType.DYNAMIC, weaknesses[0].clusterType)
        assertEquals(dynClusterId, weaknesses[0].clusterId)
    }

    @Test
    fun `weaknesses are ranked deterministically by priority, mistakes, positions, clusterId`() {
        val snapId = UUID.randomUUID()
        mockActiveStructuralSnapshot(snapId)

        val uuidA = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val uuidB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val uuidC = UUID.fromString("00000000-0000-0000-0000-000000000003")

        val clusterA = PositionCluster(id = uuidA, snapshotId = snapId, clusterIndex = 0, size = 10, centroidVector = "{}")
        val clusterB = PositionCluster(id = uuidB, snapshotId = snapId, clusterIndex = 1, size = 20, centroidVector = "{}")
        val clusterC = PositionCluster(id = uuidC, snapshotId = snapId, clusterIndex = 2, size = 15, centroidVector = "{}")
        `when`(clusterRepository.findBySnapshotIdOrderByClusterIndexAsc(snapId)).thenReturn(listOf(clusterA, clusterB, clusterC))

        setupClusterOccurrencesAndProfile(snapId, clusterA, mistakeCount = 3, blunderRate = 50.0)
        setupClusterOccurrencesAndProfile(snapId, clusterB, mistakeCount = 2, blunderRate = 70.0)
        setupClusterOccurrencesAndProfile(snapId, clusterC, mistakeCount = 5, blunderRate = 50.0)

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest(clusterType = ClusterType.STRUCTURAL))
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val weaknesses = service.getLatestRecurringWeaknesses(testAccount)
        assertEquals(3, weaknesses.size)
        assertEquals(uuidB, weaknesses[0].clusterId)
        assertEquals(uuidC, weaknesses[1].clusterId)
        assertEquals(uuidA, weaknesses[2].clusterId)
    }

    @Test
    fun `discovery filters candidate clusters by minMistakeSeverity`() {
        val cluster = mockSnapshotAndClusterForType(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ClusterType.STRUCTURAL)
        val profileMinor =
            createProfile(cluster.id, size = 12, mistakeCount = 2, blunderRate = 0.0)
                .copy(
                    evalLossDistribution = mapOf(MistakeSeverity.MINOR to 2),
                    evalLossPercentages = mapOf(MistakeSeverity.MINOR to 100.0),
                )
        `when`(
            communityWeaknessService.profileCommunity(org.mockito.kotlin.eq(cluster), org.mockito.kotlin.any(), org.mockito.kotlin.any()),
        ).thenReturn(profileMinor)

        val request =
            RecurringDiscoveryRequest(
                clusterType = ClusterType.STRUCTURAL,
                minMistakeSeverity = MistakeSeverity.MODERATE,
            )
        val response = service.triggerDiscovery(testAccount, request)
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        assertTrue(service.getLatestRecurringWeaknesses(testAccount).isEmpty())
    }

    @Test
    fun `discovery filters candidate clusters by minEvalLoss`() {
        val posId = UUID.randomUUID()
        val cluster = mockSnapshotAndClusterForType(UUID.randomUUID(), UUID.randomUUID(), posId, ClusterType.STRUCTURAL)
        mockOccurrencesWithLoss(posId, 0.5)

        val profile = createProfile(cluster.id, size = 12, mistakeCount = 1, blunderRate = 50.0)
        `when`(
            communityWeaknessService.profileCommunity(org.mockito.kotlin.eq(cluster), org.mockito.kotlin.any(), org.mockito.kotlin.any()),
        ).thenReturn(profile)

        val request =
            RecurringDiscoveryRequest(
                clusterType = ClusterType.STRUCTURAL,
                minEvalLoss = 0.8,
            )
        val response = service.triggerDiscovery(testAccount, request)
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        assertTrue(service.getLatestRecurringWeaknesses(testAccount).isEmpty())
    }

    @Test
    fun `discovery applies both minMistakeSeverity and minEvalLoss as logical AND`() {
        val posId = UUID.randomUUID()
        val cluster = mockSnapshotAndClusterForType(UUID.randomUUID(), UUID.randomUUID(), posId, ClusterType.STRUCTURAL)
        mockOccurrencesWithLoss(posId, 1.2)

        val profile =
            createProfile(cluster.id, size = 12, mistakeCount = 1, blunderRate = 50.0)
                .copy(evalLossDistribution = mapOf(MistakeSeverity.MODERATE to 1))
        `when`(
            communityWeaknessService.profileCommunity(org.mockito.kotlin.eq(cluster), org.mockito.kotlin.any(), org.mockito.kotlin.any()),
        ).thenReturn(profile)

        val request =
            RecurringDiscoveryRequest(
                clusterType = ClusterType.STRUCTURAL,
                minMistakeSeverity = MistakeSeverity.MODERATE,
                minEvalLoss = 0.8,
            )
        val response = service.triggerDiscovery(testAccount, request)
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        assertEquals(1, service.getLatestRecurringWeaknesses(testAccount).size)
    }

    @Test
    fun `discovery includes cluster when evalLoss exactly equals minEvalLoss boundary`() {
        val posId = UUID.randomUUID()
        val cluster = mockSnapshotAndClusterForType(UUID.randomUUID(), UUID.randomUUID(), posId, ClusterType.STRUCTURAL)
        mockOccurrencesWithLoss(posId, 0.8)

        val profile = createProfile(cluster.id, size = 12, mistakeCount = 1, blunderRate = 50.0)
        `when`(
            communityWeaknessService.profileCommunity(org.mockito.kotlin.eq(cluster), org.mockito.kotlin.any(), org.mockito.kotlin.any()),
        ).thenReturn(profile)

        val request = RecurringDiscoveryRequest(clusterType = ClusterType.STRUCTURAL, minEvalLoss = 0.8)
        val response = service.triggerDiscovery(testAccount, request)
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        assertEquals(1, service.getLatestRecurringWeaknesses(testAccount).size)
    }

    @Test
    fun `discovery filters candidate clusters by UNEVALUATED mistake severity`() {
        val cluster = mockSnapshotAndClusterForType(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ClusterType.STRUCTURAL)
        val profileUnevaluated =
            createProfile(cluster.id, size = 12, mistakeCount = 1, blunderRate = 0.0)
                .copy(evalLossDistribution = mapOf(MistakeSeverity.UNEVALUATED to 1))
        `when`(
            communityWeaknessService.profileCommunity(org.mockito.kotlin.eq(cluster), org.mockito.kotlin.any(), org.mockito.kotlin.any()),
        ).thenReturn(profileUnevaluated)

        val request = RecurringDiscoveryRequest(clusterType = ClusterType.STRUCTURAL, minMistakeSeverity = MistakeSeverity.UNEVALUATED)
        val response = service.triggerDiscovery(testAccount, request)
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        assertEquals(1, service.getLatestRecurringWeaknesses(testAccount).size)
    }

    private fun mockActiveStructuralSnapshot(snapId: UUID) {
        val snapshot =
            PositionClusterSnapshot(
                id = snapId,
                accountId = testAccount.id,
                snapshotVersion = 1,
                clusterType = ClusterType.STRUCTURAL,
                status = PositionClusterSnapshotStatus.ACTIVE,
            )
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.STRUCTURAL,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(snapshot)
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.DYNAMIC,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(null)
    }

    private fun setupClusterOccurrencesAndProfile(
        snapId: UUID,
        cluster: PositionCluster,
        mistakeCount: Int,
        blunderRate: Double,
    ) {
        val posId = UUID.randomUUID()
        val assignment =
            PositionClusterAssignment(
                id = PositionClusterAssignmentId(snapshotId = snapId, positionId = posId),
                clusterId = cluster.id,
                assignmentStatus = PositionClusterAssignmentStatus.ASSIGNED,
            )
        `when`(assignmentRepository.findByClusterId(cluster.id)).thenReturn(listOf(assignment))
        mockOccurrencesForPos(posId, "g_${cluster.id}")
        val profile = createProfile(cluster.id, size = cluster.size, mistakeCount = mistakeCount, blunderRate = blunderRate)
        `when`(
            communityWeaknessService.profileCommunity(org.mockito.kotlin.eq(cluster), org.mockito.kotlin.any(), org.mockito.kotlin.any()),
        ).thenReturn(profile)
    }

    private fun mockOccurrencesWithLoss(
        posId: UUID,
        loss: Double,
    ) {
        val game = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "gEval", pgn = "", timeControl = "RAPID")
        val position = Position(id = posId, hash = "h1", fen = "fen")
        val occ1 = createOccurrence(game, position)
        val occ2 = createOccurrence(game, position)
        `when`(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(testAccount.id, listOf(posId))).thenReturn(listOf(occ1, occ2))

        val analysis = EngineAnalysis(id = UUID.randomUUID(), position = position, depth = 18, bestMove = "Nf3")
        analysis.moveEvaluations.add(
            MoveEvaluation(id = UUID.randomUUID(), engineAnalysis = analysis, move = "Nf3", evalLossFromBest = loss),
        )
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId))).thenReturn(listOf(analysis))
    }

    private fun mockSnapshotAndClusterForType(
        snapshotId: UUID,
        clusterId: UUID,
        posId: UUID,
        clusterType: ClusterType,
    ): PositionCluster {
        val snapshot =
            PositionClusterSnapshot(
                id = snapshotId,
                accountId = testAccount.id,
                snapshotVersion = 1,
                clusterType = clusterType,
                status = PositionClusterSnapshotStatus.ACTIVE,
            )
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                clusterType,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(snapshot)

        val cluster = PositionCluster(id = clusterId, snapshotId = snapshotId, clusterIndex = 0, size = 12, centroidVector = "{}")
        `when`(clusterRepository.findBySnapshotIdOrderByClusterIndexAsc(snapshotId)).thenReturn(listOf(cluster))

        val assignment =
            PositionClusterAssignment(
                id = PositionClusterAssignmentId(snapshotId = snapshotId, positionId = posId),
                clusterId = clusterId,
                assignmentStatus = PositionClusterAssignmentStatus.ASSIGNED,
            )
        `when`(assignmentRepository.findByClusterId(clusterId)).thenReturn(listOf(assignment))
        mockOccurrencesForPos(posId, "g_$clusterId")
        return cluster
    }

    private fun mockOccurrencesForPos(
        posId: UUID,
        prefix: String,
    ): List<PositionOccurrence> {
        val g1 = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "${prefix}_1", pgn = "", timeControl = "RAPID")
        val g2 = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "${prefix}_2", pgn = "", timeControl = "RAPID")
        val pos = Position(id = posId, hash = "h_$posId", fen = "fen")
        val occs = listOf(createOccurrence(g1, pos), createOccurrence(g2, pos))
        `when`(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(testAccount.id, listOf(posId))).thenReturn(occs)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId))).thenReturn(emptyList())
        return occs
    }

    private fun mockConcurrencyAndProfile(cluster: PositionCluster) {
        val profile = createProfile(cluster.id, size = 12, mistakeCount = 2, blunderRate = 50.0)
        `when`(
            communityWeaknessService.profileCommunity(org.mockito.kotlin.eq(cluster), org.mockito.kotlin.any(), org.mockito.kotlin.any()),
        ).thenReturn(profile)
    }

    private fun createProfile(
        clusterId: UUID,
        size: Int,
        mistakeCount: Int,
        blunderRate: Double,
    ): CommunityWeaknessProfile =
        CommunityWeaknessProfile(
            clusterId = clusterId,
            memberPositionCount = size,
            totalOccurrences = 2,
            distinctGamesCount = 2,
            classification = CommunityPatternClassification.RECURRING_WEAKNESS,
            mistakeCount = mistakeCount,
            mistakeFrequency = 100.0,
            blunderRate = blunderRate,
            evalLossDistribution = mapOf(MistakeSeverity.SEVERE to 1),
            evalLossPercentages = mapOf(MistakeSeverity.SEVERE to 50.0),
            timingProfile = emptyMap(),
            formatBreakdown = mapOf("RAPID" to 2),
        )

    private fun createOccurrence(
        game: Game,
        position: Position,
    ): PositionOccurrence =
        PositionOccurrence(
            id = UUID.randomUUID(),
            game = game,
            position = position,
            chessAccount = testAccount,
            plyNumber = 5,
            movePlayed = "Nf3",
            playerColor = "WHITE",
        )

    @Test
    fun `getClusterPositions returns paginated positions with evaluations and decision timing`() {
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()

        setupClusterWithAssignments(snapshotId, clusterId, listOf(posId1, posId2))

        val g1 = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "game_1", pgn = "", timeControl = "RAPID")
        val g2 = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "game_2", pgn = "", timeControl = "BLITZ")
        val p1 = Position(id = posId1, hash = "h1", fen = "fen1")
        val p2 = Position(id = posId2, hash = "h2", fen = "fen2")

        val occ1 =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = g1,
                position = p1,
                chessAccount = testAccount,
                plyNumber = 5,
                movePlayed = "e5",
                playerColor = "BLACK",
                decisionTimeMs = 4500L,
            )
        val occ2 =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = g2,
                position = p2,
                chessAccount = testAccount,
                plyNumber = 4,
                movePlayed = "d5",
                playerColor = "WHITE",
                decisionTimeMs = null,
            )

        `when`(
            occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(testAccount.id, listOf(posId1, posId2)),
        ).thenReturn(listOf(occ1, occ2))

        val ea1 =
            EngineAnalysis(position = p1, depth = 16, bestMove = "c5").apply {
                moveEvaluations.add(MoveEvaluation(engineAnalysis = this, move = "e5", evalLossFromBest = 1.20))
                moveEvaluations.add(MoveEvaluation(engineAnalysis = this, move = "c5", evalLossFromBest = 0.0))
            }
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId1, posId2))).thenReturn(listOf(ea1))

        val response = service.getClusterPositions(clusterId, testAccount, page = 0, size = 10, playableOnly = false)

        assertEquals(0, response.page)
        assertEquals(10, response.size)
        assertEquals(2L, response.totalElements)
        assertEquals(1, response.totalPages)
        assertEquals(false, response.hasNext)
        assertEquals(2, response.positions.size)

        assertEvaluatedPosition(response.positions[0], posId1)
        assertUnevaluatedPosition(response.positions[1], posId2)
    }

    @Test
    fun `getClusterPositions with playableOnly=true filters out unevaluated positions`() {
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()

        setupClusterWithAssignments(snapshotId, clusterId, listOf(posId1, posId2))

        val g1 = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "game_1", pgn = "", timeControl = "RAPID")
        val p1 = Position(id = posId1, hash = "h1", fen = "fen1")
        val p2 = Position(id = posId2, hash = "h2", fen = "fen2")
        val occ1 =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = g1,
                position = p1,
                chessAccount = testAccount,
                plyNumber = 5,
                movePlayed = "e5",
                playerColor = "BLACK",
            )
        val occ2 =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = g1,
                position = p2,
                chessAccount = testAccount,
                plyNumber = 4,
                movePlayed = "d5",
                playerColor = "WHITE",
            )

        `when`(
            occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(testAccount.id, listOf(posId1, posId2)),
        ).thenReturn(listOf(occ1, occ2))

        val ea1 = EngineAnalysis(position = p1, depth = 16, bestMove = "c5")
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId1, posId2))).thenReturn(listOf(ea1))

        val response = service.getClusterPositions(clusterId, testAccount, page = 0, size = 10, playableOnly = true)

        assertEquals(1L, response.totalElements)
        assertEquals(1, response.positions.size)
        assertEquals(posId1, response.positions[0].positionId)
    }

    @Test
    fun `getClusterPositions throws ResponseStatusException with 404 when cluster belongs to another account`() {
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()
        val otherAccountId = UUID.randomUUID()

        setupClusterWithAssignments(snapshotId, clusterId, listOf(UUID.randomUUID()), otherAccountId)

        val ex =
            assertThrows<ResponseStatusException> {
                service.getClusterPositions(clusterId, testAccount, page = 0, size = 10, playableOnly = false)
            }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    private fun setupClusterWithAssignments(
        snapshotId: UUID,
        clusterId: UUID,
        posIds: List<UUID>,
        accountId: UUID = testAccount.id,
    ): Pair<PositionClusterSnapshot, PositionCluster> {
        val snapshot =
            PositionClusterSnapshot(
                id = snapshotId,
                accountId = accountId,
                snapshotVersion = 1,
                clusterType = ClusterType.STRUCTURAL,
                status = PositionClusterSnapshotStatus.ACTIVE,
            )
        val cluster =
            PositionCluster(
                id = clusterId,
                snapshotId = snapshotId,
                clusterIndex = 0,
                size = posIds.size,
                centroidVector = "{}",
            )
        `when`(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster))
        `when`(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot))

        val assignments =
            posIds.map { posId ->
                PositionClusterAssignment(
                    id = PositionClusterAssignmentId(snapshotId = snapshotId, positionId = posId),
                    clusterId = clusterId,
                    assignmentStatus = PositionClusterAssignmentStatus.ASSIGNED,
                )
            }
        `when`(assignmentRepository.findByClusterId(clusterId)).thenReturn(assignments)
        return snapshot to cluster
    }

    private fun assertEvaluatedPosition(
        item: com.chessecho.dto.ClusterMemberPositionDto,
        expectedPosId: UUID,
    ) {
        assertEquals(expectedPosId, item.positionId)
        assertEquals("BLACK", item.playerColor)
        assertEquals("e5", item.movePlayed)
        assertEquals("c5", item.targetMove)
        assertEquals(1.20, item.evalLoss)
        assertEquals(MistakeSeverity.MODERATE, item.mistakeSeverity)
        assertEquals(4500L, item.decisionTimeMs)
        assertEquals(DecisionSeverity.NO_CONCERN, item.decisionSeverity)
        assertEquals("RAPID", item.timeControl)
        assertEquals(3, item.moveNumber)
        assertEquals(5, item.plyNumber)
        assertEquals(1, item.acceptableMoves?.size)
        assertEquals("c5", item.acceptableMoves?.firstOrNull()?.move)
    }

    private fun assertUnevaluatedPosition(
        item: com.chessecho.dto.ClusterMemberPositionDto,
        expectedPosId: UUID,
    ) {
        assertEquals(expectedPosId, item.positionId)
        assertEquals("WHITE", item.playerColor)
        assertEquals("d5", item.movePlayed)
        assertNull(item.targetMove)
        assertNull(item.evalLoss)
        assertNull(item.mistakeSeverity)
        assertNull(item.decisionTimeMs)
        assertEquals(DecisionSeverity.UNKNOWN, item.decisionSeverity)
        assertEquals("BLITZ", item.timeControl)
        assertEquals(2, item.moveNumber)
    }
}
