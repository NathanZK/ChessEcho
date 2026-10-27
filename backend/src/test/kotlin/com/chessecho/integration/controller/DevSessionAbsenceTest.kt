package com.chessecho.integration.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

/**
 * Issue #113 (AC9/AC13, #79 D7) — under the default (production) profile with no
 * {dev, local} allowlisted profile active, the @Profile-gated dev bean is absent,
 * so POST /api/dev/session resolves to 404 rather than establishing a session.
 *
 * Written test-first: it boots the full application context (which also runs the
 * always-registered DevModeStartupGuard under the default profile with dev mode
 * disabled, permitting boot) and is expected to be red until production exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DevSessionAbsenceTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `dev session endpoint is absent under the default profile`() {
        val response =
            restTemplate.postForEntity(
                "http://localhost:$port/api/dev/session",
                null,
                String::class.java,
            )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }
}
