package com.chessecho.repository

import com.chessecho.domain.AppUser
import com.chessecho.domain.AuthIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

/**
 * Issue #113 (AC1/AC2, #79 D1) — external identities are persisted and uniquely
 * resolved by (issuer, subject); equal subjects across different issuers stay
 * distinct; an email-snapshot change never creates or merges an identity.
 *
 * Written test-first: the AuthIdentity entity and AuthIdentityRepository do not
 * exist yet, so this class is expected to fail to compile/run until the planned
 * production symbols are implemented (red phase).
 */
@DataJpaTest
@ActiveProfiles("test")
class AuthIdentityRepositoryTest {
    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var authIdentityRepository: AuthIdentityRepository

    private fun newUser(email: String? = null): AppUser = appUserRepository.save(AppUser(email = email))

    @Test
    fun `findByIssuerAndSubject resolves the same identity and owner for repeat lookups`() {
        val user = newUser("owner@example.com")
        authIdentityRepository.save(
            AuthIdentity(user = user, issuer = "issuer-a", subject = "subject-1", emailSnapshot = "owner@example.com"),
        )

        val first = authIdentityRepository.findByIssuerAndSubject("issuer-a", "subject-1")
        val second = authIdentityRepository.findByIssuerAndSubject("issuer-a", "subject-1")

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first!!.id, second!!.id)
        assertEquals(user.id, first.user.id)
    }

    @Test
    fun `equal subjects under different issuers remain distinct identities and owners`() {
        val userA = newUser("a@example.com")
        val userB = newUser("b@example.com")
        authIdentityRepository.save(AuthIdentity(user = userA, issuer = "google", subject = "shared-subject"))
        authIdentityRepository.save(AuthIdentity(user = userB, issuer = "github", subject = "shared-subject"))

        val fromGoogle = authIdentityRepository.findByIssuerAndSubject("google", "shared-subject")
        val fromGithub = authIdentityRepository.findByIssuerAndSubject("github", "shared-subject")

        assertNotNull(fromGoogle)
        assertNotNull(fromGithub)
        assertNotEquals(fromGoogle!!.id, fromGithub!!.id)
        assertNotEquals(fromGoogle.user.id, fromGithub.user.id)
    }

    @Test
    fun `duplicate issuer and subject violates the uniqueness constraint`() {
        val userA = newUser("dup-a@example.com")
        val userB = newUser("dup-b@example.com")
        authIdentityRepository.saveAndFlush(AuthIdentity(user = userA, issuer = "issuer-x", subject = "subject-x"))

        assertThrows(DataIntegrityViolationException::class.java) {
            authIdentityRepository.saveAndFlush(
                AuthIdentity(user = userB, issuer = "issuer-x", subject = "subject-x"),
            )
        }
    }

    @Test
    fun `changing the email snapshot neither creates nor merges an identity`() {
        val user = newUser("original@example.com")
        val identity =
            authIdentityRepository.saveAndFlush(
                AuthIdentity(user = user, issuer = "issuer-e", subject = "subject-e", emailSnapshot = "original@example.com"),
            )

        identity.emailSnapshot = "changed@example.com"
        authIdentityRepository.saveAndFlush(identity)

        val resolved = authIdentityRepository.findByIssuerAndSubject("issuer-e", "subject-e")
        assertNotNull(resolved)
        assertEquals(identity.id, resolved!!.id)
        assertEquals(user.id, resolved.user.id)
        assertEquals(1, authIdentityRepository.count())
        assertEquals(1, appUserRepository.count())
    }
}
