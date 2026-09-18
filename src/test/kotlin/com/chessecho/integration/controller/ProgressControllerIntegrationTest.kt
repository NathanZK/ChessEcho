package com.chessecho.integration.controller

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Issue #76: progress history is private account-owned data, unlike the
 * existing public weakness summary.
 *
 * This test is intentionally written before the progress endpoint exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProgressControllerIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `guest progress history requests require authentication`() {
        val positionId = UUID.randomUUID()

        val first =
            restTemplate.getForEntity(
                "/api/positions/$positionId/progress?playerColor=WHITE",
                String::class.java,
            )
        val repeated =
            restTemplate.getForEntity(
                "/api/positions/$positionId/progress?playerColor=WHITE",
                String::class.java,
            )

        assertEquals(HttpStatus.UNAUTHORIZED, first.statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, repeated.statusCode)
    }
}
