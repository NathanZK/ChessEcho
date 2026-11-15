package com.chessprobe.isolation

import com.chessprobe.controller.AccountController
import com.chessprobe.controller.GameImportController
import com.chessprobe.controller.GlobalExceptionHandler
import com.chessprobe.controller.PositionSimilarityController
import com.chessprobe.repository.AsyncJobRepository
import com.chessprobe.service.AccountOwnershipService
import com.chessprobe.service.ForbiddenAccountException
import com.chessprobe.service.GameImportService
import com.chessprobe.service.auth.AuthenticatedPrincipal
import com.chessprobe.service.similarity.DynamicSimilarityCache
import com.chessprobe.service.similarity.FenPositionValidator
import com.chessprobe.service.similarity.PositionSimilarityService
import com.chessprobe.similarity.DynamicInvertedIndex
import com.chessprobe.similarity.PositionDocument
import com.chessprobe.web.SessionAuthenticationFilter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.UUID

class DualTenantCacheAndSecurityIsolationTest {
    private val positionSimilarityService: PositionSimilarityService = mock()
    private val accountOwnershipService: AccountOwnershipService = mock()
    private val gameImportService: GameImportService = mock()
    private val asyncJobRepository: AsyncJobRepository = mock()
    private val fenPositionValidator = FenPositionValidator()
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    private val tenantAUser = UUID.randomUUID()
    private val tenantBAccount = UUID.randomUUID()

    private val principalA = AuthenticatedPrincipal(appUserId = tenantAUser, devPrincipal = false)

    private val principalResolver =
        object : HandlerMethodArgumentResolver {
            override fun supportsParameter(parameter: MethodParameter): Boolean =
                parameter.parameterType == AuthenticatedPrincipal::class.java

            override fun resolveArgument(
                parameter: MethodParameter,
                mavContainer: ModelAndViewContainer?,
                webRequest: NativeWebRequest,
                binderFactory: WebDataBinderFactory?,
            ): Any = principalA
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
        val importController =
            GameImportController(
                gameImportService = gameImportService,
                asyncJobRepository = asyncJobRepository,
                accountOwnershipService = accountOwnershipService,
            )
        val accountController =
            AccountController(
                accountOwnershipService = accountOwnershipService,
            )

        mockMvc =
            MockMvcBuilders
                .standaloneSetup(similarityController, importController, accountController)
                .setControllerAdvice(GlobalExceptionHandler())
                .setCustomArgumentResolvers(principalResolver)
                .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
                .build()
    }

    @Test
    fun `dynamic similarity cache partitions indexes strictly by account id`() {
        val cache = DynamicSimilarityCache(maxCachedAccounts = 10)
        val tenantA = UUID.randomUUID()
        val tenantB = UUID.randomUUID()

        val docA =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "8/8/8/8/8/8/8/8 w - - 0 1",
                canonicalFen = "8/8/8/8/8/8/8/8 w - - 0 1",
                terms = mapOf("pawn_e4" to 1.0, "pawn_c5" to 1.0),
            )
        val docB =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "8/8/8/8/8/8/8/7K w - - 0 1",
                canonicalFen = "8/8/8/8/8/8/8/7K w - - 0 1",
                terms = mapOf("pawn_d4" to 1.0, "pawn_d5" to 1.0),
            )

        val indexA = cache.getOrBuild(tenantA) { DynamicInvertedIndex(listOf(docA)) }
        assertNull(cache.get(tenantB))

        val indexB = cache.getOrBuild(tenantB) { DynamicInvertedIndex(listOf(docB)) }

        assertEquals(1, indexA.size)
        assertEquals(1, indexB.size)
        assertEquals(1, cache.get(tenantA)?.size)
        assertEquals(1, cache.get(tenantB)?.size)

        // Querying index A with tenant B terms returns no matches
        val queryBTerms = setOf("pawn_d4", "pawn_d5")
        val matchesInA = indexA.retrieveCandidates(queryBTerms, activeTurn = "w", maxCandidates = 10)
        assertEquals(0, matchesInA.size)

        // Invalidating tenant A does not purge tenant B
        cache.invalidate(tenantA)
        assertNull(cache.get(tenantA))
        assertNotNull(cache.get(tenantB))
        assertEquals(1, cache.get(tenantB)?.size)
    }

    @Test
    fun `similar positions endpoint returns 403 Forbidden when requesting another tenant account`() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        whenever(
            accountOwnershipService.resolvePrivateRead(
                eq(tenantBAccount),
                eq(principalA),
            ),
        ).thenThrow(ForbiddenAccountException("Tenant A is not authorized to access Tenant B"))

        mockMvc
            .perform(
                get("/api/positions/similar")
                    .param("fen", fen)
                    .param("accountId", tenantBAccount.toString())
                    .requestAttr(SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, principalA),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `game import endpoint returns 403 Forbidden when initiating import for another tenant account`() {
        val payload =
            mapOf(
                "accountId" to tenantBAccount.toString(),
                "platform" to "CHESS_COM",
                "username" to "rival_player",
                "timeControls" to listOf("RAPID"),
            )

        whenever(
            gameImportService.createImportJob(
                any(),
                eq(principalA),
            ),
        ).thenThrow(ForbiddenAccountException("Tenant A cannot import games for Tenant B"))

        mockMvc
            .perform(
                post("/api/games/import")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(payload))
                    .requestAttr(SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, principalA),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `disconnect endpoint rejects disconnecting another tenant account`() {
        whenever(
            accountOwnershipService.disconnect(
                eq(tenantBAccount),
                eq(principalA),
            ),
        ).thenThrow(ForbiddenAccountException("Tenant A cannot disconnect Tenant B"))

        mockMvc
            .perform(
                delete("/api/accounts/{accountId}/connection", tenantBAccount)
                    .requestAttr(SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, principalA),
            ).andExpect(status().isForbidden)
    }
}
