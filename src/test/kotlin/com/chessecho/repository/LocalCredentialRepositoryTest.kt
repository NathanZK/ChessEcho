package com.chessecho.repository

import com.chessecho.domain.AppUser
import com.chessecho.domain.LocalCredential
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

/**
 * Issue #276 — local credentials are an authentication factor attached to the
 * authoritative AppUser identity, never a second identity record.
 */
@DataJpaTest
@ActiveProfiles("test")
class LocalCredentialRepositoryTest {
    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var localCredentialRepository: LocalCredentialRepository

    @Test
    fun `credential belongs to an existing AppUser and has no authoritative email field`() {
        val user = appUserRepository.saveAndFlush(AppUser(email = "canonical@example.com"))
        val credential =
            localCredentialRepository.saveAndFlush(
                LocalCredential(user = user, passwordHash = "{bcrypt}adaptive-hash"),
            )

        val reloaded = localCredentialRepository.findByUserId(user.id)

        assertNotNull(reloaded)
        assertEquals(credential.id, reloaded!!.id)
        assertEquals(user.id, reloaded.user.id)
        assertFalse(
            LocalCredential::class.java.declaredFields.any { it.name.contains("email", ignoreCase = true) },
            "LocalCredential must not duplicate AppUser's authoritative email",
        )
    }

    @Test
    fun `database rejects a second credential for the same AppUser`() {
        val user = appUserRepository.saveAndFlush(AppUser(email = "one-credential@example.com"))
        localCredentialRepository.saveAndFlush(LocalCredential(user = user, passwordHash = "{bcrypt}first"))

        assertThrows(DataIntegrityViolationException::class.java) {
            localCredentialRepository.saveAndFlush(LocalCredential(user = user, passwordHash = "{bcrypt}second"))
        }
    }
}
