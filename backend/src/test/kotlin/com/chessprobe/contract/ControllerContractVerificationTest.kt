package com.chessprobe.contract

import com.chessprobe.config.ComparatorMethod
import com.chessprobe.config.ConfidenceMethod
import com.chessprobe.controller.GlobalExceptionHandler
import com.chessprobe.controller.LongDecisionController
import com.chessprobe.controller.PositionSimilarityController
import com.chessprobe.controller.RecurringWeaknessController
import com.chessprobe.controller.WeaknessController
import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.DecisionSeverity
import com.chessprobe.domain.PlayerColor
import com.chessprobe.domain.SimilarityRetrievalMode
import com.chessprobe.domain.TimeControl
import com.chessprobe.dto.ClusterMemberPositionDto
import com.chessprobe.dto.ClusterMemberPositionsResponse
import com.chessprobe.dto.LongDecisionOccurrenceResponse
import com.chessprobe.dto.LongDecisionPageResponse
import com.chessprobe.dto.PracticalEvidenceCohort
import com.chessprobe.dto.PracticalEvidenceResponse
import com.chessprobe.dto.PracticalEvidenceScopeType
import com.chessprobe.dto.WeaknessResponse
import com.chessprobe.service.AccountOwnershipService
import com.chessprobe.service.LongDecisionService
import com.chessprobe.service.ObjectiveEvidenceState
import com.chessprobe.service.PracticalConfidenceState
import com.chessprobe.service.PracticalConfigurationState
import com.chessprobe.service.PrincipalVariation
import com.chessprobe.service.SearchStatus
import com.chessprobe.service.WeaknessCalculationService
import com.chessprobe.service.auth.AuthenticatedPrincipal
import com.chessprobe.service.similarity.FenPositionValidator
import com.chessprobe.service.similarity.PositionSimilarityService
import com.chessprobe.service.similarity.SimilarOccurrenceDto
import com.chessprobe.service.similarity.SimilarPositionMatch
import com.chessprobe.service.similarity.SimilarPositionsResult
import com.chessprobe.service.weakness.RecurringWeaknessDiscoveryService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.core.MethodParameter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.time.Instant
import java.util.UUID

class ControllerContractVerificationTest {
    private val positionSimilarityService: PositionSimilarityService = mock()
    private val accountOwnershipService: AccountOwnershipService = mock()
    private val weaknessCalculationService: WeaknessCalculationService = mock()
    private val discoveryService: RecurringWeaknessDiscoveryService = mock()
    private val longDecisionService: LongDecisionService = mock()

    private val fenPositionValidator = FenPositionValidator()
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val testPrincipal = AuthenticatedPrincipal(UUID.randomUUID(), devPrincipal = false)

    private val principalResolver =
        object : HandlerMethodArgumentResolver {
            override fun supportsParameter(parameter: MethodParameter): Boolean =
                parameter.parameterType == AuthenticatedPrincipal::class.java

            override fun resolveArgument(
                parameter: MethodParameter,
                mavContainer: ModelAndViewContainer?,
                webRequest: NativeWebRequest,
                binderFactory: WebDataBinderFactory?,
            ): Any = testPrincipal
        }

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        val similarityController =
            PositionSimilarityController(
                positionSimilarityService = positionSimilarityService,
                accountOwnershipService = accountOwnershipService,
                fenPositionValidator = fenPositionValidator,
            )
        val weaknessController =
            WeaknessController(
                weaknessCalculationService = weaknessCalculationService,
            )
        val recurringWeaknessController =
            RecurringWeaknessController(
                discoveryService = discoveryService,
                accountOwnershipService = accountOwnershipService,
            )
        val longDecisionController =
            LongDecisionController(
                longDecisionService = longDecisionService,
            )

        mockMvc =
            MockMvcBuilders
                .standaloneSetup(
                    similarityController,
                    weaknessController,
                    recurringWeaknessController,
                    longDecisionController,
                ).setControllerAdvice(GlobalExceptionHandler())
                .setCustomArgumentResolvers(principalResolver)
                .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
                .build()
    }

    // ── 1. PositionSimilarityController Contract Tests ────────────────────────

    @Test
    fun `similar positions endpoint rejects negative minScore with HTTP 400`() {
        mockMvc
            .perform(
                get("/api/positions/similar")
                    .param("fen", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
                    .param("minScore", "-0.1"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `similar positions endpoint rejects invalid mode with HTTP 400`() {
        mockMvc
            .perform(
                get("/api/positions/similar")
                    .param("fen", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
                    .param("mode", "INVALID_MODE"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `similar positions endpoint rejects malformed fen with HTTP 400`() {
        mockMvc
            .perform(
                get("/api/positions/similar")
                    .param("fen", "not-a-valid-fen-string"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `similar positions endpoint serializes SimilarPositionsResponse schema accurately`() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val positionId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val account = ChessAccount(id = accountId, platform = "CHESS_COM", username = "hikaru")

        whenever(accountOwnershipService.resolvePrivateRead(eq(accountId), eq(testPrincipal))).thenReturn(account)
        whenever(
            positionSimilarityService.findSimilarPositions(
                queryFen = eq(fen),
                account = eq(account),
                minScore = eq(0.0),
                limit = eq(20),
                depth = eq(8),
                multiPv = eq(1),
                mode = eq(SimilarityRetrievalMode.STRUCTURAL),
                alpha = eq(0.60),
                maxPositionsPerGame = eq(1),
                excludeGameId = isNull(),
            ),
        ).thenReturn(createSampleSimilarPositionsResult(fen, positionId))

        mockMvc
            .perform(
                get("/api/positions/similar")
                    .param("fen", fen)
                    .param("accountId", accountId.toString())
                    .requestAttr(com.chessprobe.web.SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, testPrincipal),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.queryFen").value(fen))
            .andExpect(jsonPath("$.candidatesEvaluated").value(42))
            .andExpect(jsonPath("$.results", hasSize<Any>(1)))
            .andExpect(jsonPath("$.results[0].rank").value(1))
            .andExpect(jsonPath("$.results[0].score").value(0.95))
            .andExpect(jsonPath("$.results[0].positionId").value(positionId.toString()))
            .andExpect(jsonPath("$.results[0].fen").value(fen))
            .andExpect(jsonPath("$.results[0].occurrences", hasSize<Any>(1)))
            .andExpect(jsonPath("$.results[0].occurrences[0].playedMove").value("e4"))
            .andExpect(jsonPath("$.results[0].principalVariations[0].rank").value(1))
    }

    private fun createSampleSimilarPositionsResult(
        fen: String,
        positionId: UUID,
    ): SimilarPositionsResult =
        SimilarPositionsResult(
            queryFen = fen,
            candidatesEvaluated = 42,
            shortlistedCandidatesCount = 10,
            engineCompletedCount = 10,
            dynamicSimilarityApplied = false,
            fallbackReason = null,
            queryStatus = SearchStatus.COMPLETED,
            queryAchievedDepth = 8,
            queryPrincipalVariations = emptyList(),
            results =
                listOf(
                    SimilarPositionMatch(
                        rank = 1,
                        score = 0.95,
                        staticScore = 0.95,
                        dynamicScore = 0.0,
                        positionId = positionId,
                        fen = fen,
                        canonicalFen = fen,
                        searchStatus = SearchStatus.COMPLETED,
                        achievedDepth = 8,
                        principalVariations =
                            listOf(
                                PrincipalVariation(
                                    rank = 1,
                                    moves = listOf("e4", "e5"),
                                    achievedDepth = 8,
                                ),
                            ),
                        occurrences =
                            listOf(
                                SimilarOccurrenceDto(
                                    id = UUID.randomUUID(),
                                    platformGameId = "10001",
                                    playedMove = "e4",
                                    plyNumber = 1,
                                    playerColor = "WHITE",
                                    playedAt = Instant.parse("2024-01-01T00:00:00Z"),
                                    opponentUsername = "magnus",
                                    timeControl = "blitz",
                                    gameResult = "1-0",
                                    chessComUrl = "https://www.chess.com/game/live/10001",
                                ),
                            ),
                    ),
                ),
        )

    // ── 2. WeaknessController Contract Tests ──────────────────────────────────

    @Test
    fun `weakness endpoint rejects missing playerColor with HTTP 400`() {
        mockMvc
            .perform(
                get("/api/positions/weaknesses"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `weakness endpoint rejects negative minEvalLoss with HTTP 400`() {
        mockMvc
            .perform(
                get("/api/positions/weaknesses")
                    .param("playerColor", "WHITE")
                    .param("minEvalLoss", "-0.5"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `weakness endpoint serializes WeaknessResponse schema accurately`() {
        val positionId = UUID.randomUUID()
        whenever(
            weaknessCalculationService.getWeaknesses(
                platform = any(),
                username = any(),
                playerColor = eq(PlayerColor.WHITE),
                minEvalLoss = any(),
                minMistakeCount = any(),
                minTimesReached = any(),
                principal = anyOrNull(),
                accountId = anyOrNull(),
            ),
        ).thenReturn(
            listOf(
                createWeaknessResponse(
                    positionId = positionId,
                    playerColor = "WHITE",
                    mistakeCount = 5,
                    averageLoss = 0.85,
                    mistakeRate = 0.5,
                    priority = 4.25,
                    timesReached = 10,
                ),
            ),
        )

        mockMvc
            .perform(
                get("/api/positions/weaknesses")
                    .param("playerColor", "WHITE"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].positionId").value(positionId.toString()))
            .andExpect(jsonPath("$[0].playerColor").value("WHITE"))
            .andExpect(jsonPath("$[0].mistakeCount").value(5))
            .andExpect(jsonPath("$[0].averageLoss").value(0.85))
            .andExpect(jsonPath("$[0].mistakeRate").value(0.5))
            .andExpect(jsonPath("$[0].priority").value(4.25))
            .andExpect(jsonPath("$[0].timesReached").value(10))
    }

    // ── 3. RecurringWeaknessController Contract Tests ─────────────────────────

    @Test
    fun `recurring weaknesses endpoint rejects missing account selection with HTTP 400`() {
        mockMvc
            .perform(
                get("/api/weaknesses/recurring"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `cluster positions endpoint rejects negative page with HTTP 400`() {
        val clusterId = UUID.randomUUID()
        mockMvc
            .perform(
                get("/api/weaknesses/recurring/$clusterId/positions")
                    .param("page", "-1")
                    .requestAttr(com.chessprobe.web.SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, testPrincipal),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `cluster positions endpoint rejects size exceeding 100 with HTTP 400`() {
        val clusterId = UUID.randomUUID()
        mockMvc
            .perform(
                get("/api/weaknesses/recurring/$clusterId/positions")
                    .param("size", "101")
                    .requestAttr(com.chessprobe.web.SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, testPrincipal),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `cluster positions endpoint serializes ClusterMemberPositionsResponse schema accurately`() {
        val clusterId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val account = ChessAccount(id = accountId, platform = "CHESS_COM", username = "player1")

        whenever(discoveryService.getClusterAccountId(clusterId)).thenReturn(accountId)
        whenever(accountOwnershipService.resolvePrivateRead(eq(accountId), any())).thenReturn(account)
        whenever(
            discoveryService.getClusterPositions(
                clusterId = eq(clusterId),
                account = eq(account),
                page = eq(0),
                size = eq(20),
                playableOnly = eq(false),
            ),
        ).thenReturn(
            ClusterMemberPositionsResponse(
                page = 0,
                size = 20,
                totalElements = 1,
                totalPages = 1,
                hasNext = false,
                positions =
                    listOf(
                        ClusterMemberPositionDto(
                            positionId = UUID.randomUUID(),
                            fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                            playerColor = "WHITE",
                            movePlayed = "e4",
                            gameId = UUID.randomUUID(),
                            platformGameId = "10001",
                            plyNumber = 1,
                        ),
                    ),
            ),
        )

        mockMvc
            .perform(
                get("/api/weaknesses/recurring/$clusterId/positions")
                    .requestAttr(com.chessprobe.web.SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, testPrincipal),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andExpect(jsonPath("$.positions", hasSize<Any>(1)))
            .andExpect(jsonPath("$.positions[0].movePlayed").value("e4"))
    }

    // ── 4. LongDecisionController Contract Tests ──────────────────────────────

    @Test
    fun `long decisions endpoint rejects request with no threshold or severity with HTTP 400`() {
        mockMvc
            .perform(
                get("/api/positions/long-decisions")
                    .param("accountId", UUID.randomUUID().toString())
                    .param("timeControl", "BLITZ"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `long decisions endpoint rejects invalid timeControl with HTTP 400`() {
        mockMvc
            .perform(
                get("/api/positions/long-decisions")
                    .param("accountId", UUID.randomUUID().toString())
                    .param("timeControl", "NOT_A_TIME_CONTROL")
                    .param("thresholdSeconds", "30"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `long decisions endpoint serializes LongDecisionPageResponse schema accurately`() {
        val accountId = UUID.randomUUID()
        val positionId = UUID.randomUUID()
        whenever(
            longDecisionService.findLongDecisions(
                accountId = eq(accountId),
                timeControl = eq(TimeControl.BLITZ),
                minDurationSeconds = anyOrNull(),
                thresholdSeconds = eq(30),
                severity = anyOrNull(),
                page = eq(0),
                size = eq(20),
                principal = eq(testPrincipal),
            ),
        ).thenReturn(
            LongDecisionPageResponse(
                content =
                    listOf(
                        LongDecisionOccurrenceResponse(
                            id = UUID.randomUUID(),
                            positionId = positionId,
                            fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                            playerColor = "WHITE",
                            plyNumber = 10,
                            movePlayed = "Nf3",
                            decisionTimeMs = 35000,
                            timeControl = "BLITZ",
                            platformGameId = "10001",
                            playedAt = Instant.parse("2024-01-01T00:00:00Z"),
                            opponentUsername = "magnus",
                            chessComUrl = "https://www.chess.com/game/live/10001",
                            severity = DecisionSeverity.MINOR,
                        ),
                    ),
                page = 0,
                size = 20,
                totalElements = 1,
                totalPages = 1,
                hasNext = false,
            ),
        )

        mockMvc
            .perform(
                get("/api/positions/long-decisions")
                    .param("accountId", accountId.toString())
                    .param("timeControl", "BLITZ")
                    .param("thresholdSeconds", "30"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(1)))
            .andExpect(jsonPath("$.content[0].positionId").value(positionId.toString()))
            .andExpect(jsonPath("$.content[0].decisionTimeMs").value(35000))
            .andExpect(jsonPath("$.content[0].timeControl").value("BLITZ"))
            .andExpect(jsonPath("$.content[0].severity").value("MINOR"))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.hasNext").value(false))
    }

    private fun createWeaknessResponse(
        positionId: UUID = UUID.randomUUID(),
        playerColor: String = "WHITE",
        mistakeCount: Int = 5,
        averageLoss: Double = 0.85,
        mistakeRate: Double = 0.5,
        priority: Double = 4.25,
        timesReached: Int = 10,
    ): WeaknessResponse =
        WeaknessResponse(
            positionId = positionId,
            fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            timesReached = timesReached,
            mistakeCount = mistakeCount,
            mistakeRate = mistakeRate,
            averageLoss = averageLoss,
            priority = priority,
            bestMove = "e4",
            acceptableMoves = emptyList(),
            movesPlayed = emptyList(),
            playerColor = playerColor,
            recommendationPriority = priority,
            objectiveEvidenceState = ObjectiveEvidenceState.INACCURATE,
            evidenceCombination = null,
            practicalEvidence =
                PracticalEvidenceResponse(
                    scope = PracticalEvidenceScopeType.POSITION,
                    decisionSan = null,
                    candidateGames = 10,
                    eligibleGames = 10,
                    ineligibleGames = 0,
                    excludedGames = 0,
                    wins = 5,
                    draws = 0,
                    losses = 5,
                    sideCorroborationConflictGames = 0,
                    scoreRate = 0.5,
                    comparatorMethod = ComparatorMethod.FIXED_SCORE_RATE,
                    comparatorScoreRate = 0.5,
                    confidenceMethod = ConfidenceMethod.BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE,
                    confidenceLowerBound = 0.3,
                    confidenceUpperBound = 0.7,
                    confidenceState = PracticalConfidenceState.RANKING_ELIGIBLE,
                    practicalAssessment = null,
                    sampleFloor = 5,
                    meaningfulDifference = 0.1,
                    observationWindowDays = null,
                    cohort = PracticalEvidenceCohort.STANDARD_ALL_IMPORTED_TIME_CONTROLS,
                    policyVersion = "practical-v1",
                    configurationState = PracticalConfigurationState.CALIBRATED,
                    rankingApplied = false,
                ),
        )
}
