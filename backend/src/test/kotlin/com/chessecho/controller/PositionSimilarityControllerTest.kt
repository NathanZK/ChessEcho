package com.chessecho.controller

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Platform
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.PrincipalVariation
import com.chessecho.service.SearchStatus
import com.chessecho.service.similarity.FenPositionValidator
import com.chessecho.service.similarity.PositionSimilarityService
import com.chessecho.service.similarity.SimilarPositionMatch
import com.chessecho.service.similarity.SimilarPositionsResult
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class PositionSimilarityControllerTest {
    private val positionSimilarityService: PositionSimilarityService = mock()
    private val accountOwnershipService: AccountOwnershipService = mock()
    private val fenPositionValidator = FenPositionValidator()
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(
                PositionSimilarityController(
                    positionSimilarityService = positionSimilarityService,
                    accountOwnershipService = accountOwnershipService,
                    fenPositionValidator = fenPositionValidator,
                ),
            ).setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()

    private val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val testAccount =
        ChessAccount(
            id = UUID.randomUUID(),
            platform = "CHESS_COM",
            username = "testuser",
        )

    @Test
    fun `rejects depth outside 4 to 12 range`() {
        whenever(accountOwnershipService.resolvePrivateRead(eq(Platform.CHESS_COM), eq("testuser"), anyOrNull()))
            .thenReturn(testAccount)

        mockMvc
            .perform(
                get("/api/positions/similar")
                    .param("fen", queryFen)
                    .param("username", "testuser")
                    .param("depth", "3"),
            ).andExpect(status().isBadRequest)

        mockMvc
            .perform(
                get("/api/positions/similar")
                    .param("fen", queryFen)
                    .param("username", "testuser")
                    .param("depth", "13"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects multiPv outside 1 to 5 range`() {
        whenever(accountOwnershipService.resolvePrivateRead(eq(Platform.CHESS_COM), eq("testuser"), anyOrNull()))
            .thenReturn(testAccount)

        mockMvc
            .perform(
                get("/api/positions/similar")
                    .param("fen", queryFen)
                    .param("username", "testuser")
                    .param("multiPv", "0"),
            ).andExpect(status().isBadRequest)

        mockMvc
            .perform(
                get("/api/positions/similar")
                    .param("fen", queryFen)
                    .param("username", "testuser")
                    .param("multiPv", "6"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `passes depth and multiPv to service and maps response DTO fields`() {
        whenever(accountOwnershipService.resolvePrivateRead(eq(Platform.CHESS_COM), eq("testuser"), anyOrNull()))
            .thenReturn(testAccount)

        val posId = UUID.randomUUID()
        val mockResult = buildMockResult(posId)

        whenever(
            positionSimilarityService.findSimilarPositions(
                queryFen = eq(queryFen),
                account = eq(testAccount),
                minScore = eq(0.0),
                limit = eq(20),
                depth = eq(8),
                multiPv = eq(2),
            ),
        ).thenReturn(mockResult)

        mockMvc
            .perform(
                get("/api/positions/similar")
                    .param("fen", queryFen)
                    .param("username", "testuser")
                    .param("depth", "8")
                    .param("multiPv", "2"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.dynamicSimilarityApplied").value(true))
            .andExpect(jsonPath("$.queryStatus").value("COMPLETED"))
            .andExpect(jsonPath("$.queryAchievedDepth").value(8))
            .andExpect(jsonPath("$.queryPrincipalVariations[0].rank").value(1))
            .andExpect(jsonPath("$.queryPrincipalVariations[0].moves[0]").value("e2e4"))
            .andExpect(jsonPath("$.results[0].rank").value(1))
            .andExpect(jsonPath("$.results[0].score").value(14.82))
            .andExpect(jsonPath("$.results[0].staticScore").value(12.45))
            .andExpect(jsonPath("$.results[0].dynamicScore").value(14.82))
            .andExpect(jsonPath("$.results[0].searchStatus").value("COMPLETED"))
            .andExpect(jsonPath("$.results[0].achievedDepth").value(8))
            .andExpect(jsonPath("$.results[0].principalVariations[0].rank").value(1))
            .andExpect(jsonPath("$.results[0].principalVariations[0].moves[0]").value("d2d4"))

        verify(positionSimilarityService).findSimilarPositions(
            queryFen = eq(queryFen),
            account = eq(testAccount),
            minScore = eq(0.0),
            limit = eq(20),
            depth = eq(8),
            multiPv = eq(2),
        )
    }

    private fun buildMockResult(posId: UUID): SimilarPositionsResult =
        SimilarPositionsResult(
            queryFen = queryFen,
            candidatesEvaluated = 100,
            shortlistedCandidatesCount = 100,
            engineCompletedCount = 98,
            dynamicSimilarityApplied = true,
            fallbackReason = null,
            queryStatus = SearchStatus.COMPLETED,
            queryAchievedDepth = 8,
            queryPrincipalVariations = listOf(PrincipalVariation(1, listOf("e2e4"), 8)),
            results =
                listOf(
                    SimilarPositionMatch(
                        rank = 1,
                        score = 14.82,
                        staticScore = 12.45,
                        dynamicScore = 14.82,
                        positionId = posId,
                        fen = queryFen,
                        canonicalFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -",
                        searchStatus = SearchStatus.COMPLETED,
                        achievedDepth = 8,
                        principalVariations = listOf(PrincipalVariation(1, listOf("d2d4"), 8)),
                        occurrences = emptyList(),
                    ),
                ),
        )
}
