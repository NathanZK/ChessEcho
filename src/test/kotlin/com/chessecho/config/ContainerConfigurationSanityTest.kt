package com.chessecho.config

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ContainerConfigurationSanityTest {
    @Test
    fun `docker-compose configures local spring profile for plain HTTP execution`() {
        val composeFile = File("docker-compose.yml")
        assertTrue(composeFile.exists(), "docker-compose.yml must exist")
        val content = composeFile.readText()
        assertTrue(
            content.contains("SPRING_PROFILES_ACTIVE"),
            "docker-compose.yml must explicitly configure SPRING_PROFILES_ACTIVE",
        )
    }

    @Test
    fun `env example documents production secure cookie default`() {
        val envExampleFile = File(".env.example")
        assertTrue(envExampleFile.exists(), ".env.example must exist")
        val content = envExampleFile.readText()
        assertTrue(
            content.contains("CHESSECHO_AUTH_COOKIE_SECURE=true"),
            ".env.example must document CHESSECHO_AUTH_COOKIE_SECURE=true",
        )
    }
}
