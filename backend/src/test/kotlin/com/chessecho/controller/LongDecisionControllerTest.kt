package com.chessecho.controller

import com.chessecho.domain.DecisionSeverity
import com.chessecho.domain.TimeControl
import com.chessecho.dto.LongDecisionPageResponse
import com.chessecho.service.LongDecisionService
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.core.MethodParameter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.UUID

class LongDecisionControllerTest {
    private val longDecisionService: LongDecisionService = mock()
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

    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(LongDecisionController(longDecisionService))
            .setCustomArgumentResolvers(principalResolver)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()

    @Test
    fun `rejects request when minDurationSeconds, thresholdSeconds, and severity are all absent`() {
        val accountId = UUID.randomUUID()
        mockMvc
            .perform(
                get("/api/positions/long-decisions")
                    .param("accountId", accountId.toString())
                    .param("timeControl", "RAPID"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details[0]").value("At least one of severity, minDurationSeconds, or thresholdSeconds is required"))

        verifyNoInteractions(longDecisionService)
    }

    @Test
    fun `delegates to longDecisionService when severity is provided without numeric thresholds`() {
        val accountId = UUID.randomUUID()
        val emptyPage =
            LongDecisionPageResponse(
                content = emptyList(),
                page = 0,
                size = 20,
                totalElements = 0,
                totalPages = 0,
                hasNext = false,
            )
        whenever(
            longDecisionService.findLongDecisions(
                accountId = eq(accountId),
                timeControl = eq(TimeControl.RAPID),
                minDurationSeconds = eq(null),
                thresholdSeconds = eq(null),
                severity = eq(DecisionSeverity.CRITICAL),
                page = eq(0),
                size = eq(20),
                principal = any(),
            ),
        ).thenReturn(emptyPage)

        mockMvc
            .perform(
                get("/api/positions/long-decisions")
                    .param("accountId", accountId.toString())
                    .param("timeControl", "RAPID")
                    .param("severity", "CRITICAL"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.totalElements").value(0))

        verify(longDecisionService).findLongDecisions(
            accountId = eq(accountId),
            timeControl = eq(TimeControl.RAPID),
            minDurationSeconds = eq(null),
            thresholdSeconds = eq(null),
            severity = eq(DecisionSeverity.CRITICAL),
            page = eq(0),
            size = eq(20),
            principal = any(),
        )
    }
}
