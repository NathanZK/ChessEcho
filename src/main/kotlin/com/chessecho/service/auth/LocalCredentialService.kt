package com.chessecho.service.auth

import com.chessecho.domain.AppUser
import com.chessecho.domain.AuthIdentity
import com.chessecho.domain.LocalCredential
import com.chessecho.dto.LoginRequest
import com.chessecho.dto.RegistrationRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.AuthIdentityRepository
import com.chessecho.repository.LocalCredentialRepository
import com.chessecho.web.DuplicateRegistrationException
import com.chessecho.web.InvalidCredentialsException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * First-party email/password authentication. The local credential is attached
 * to the existing AppUser, while the normal opaque session service remains the
 * only session issuer.
 */
@Service
class LocalCredentialService(
    private val appUserRepository: AppUserRepository,
    private val localCredentialRepository: LocalCredentialRepository,
    private val authIdentityRepository: AuthIdentityRepository,
    private val identitySessionService: IdentitySessionService,
) {
    @Transactional
    fun register(request: RegistrationRequest): LocalAuthentication {
        val email = CanonicalEmail.normalize(request.email)
        validateEmail(email)
        validatePassword(request.password)
        if (appUserRepository.findByEmail(email) != null) {
            throw DuplicateRegistrationException()
        }

        val user =
            try {
                appUserRepository.saveAndFlush(AppUser(email = email))
            } catch (_: DataIntegrityViolationException) {
                // The unique canonical-email constraint is the race-safe
                // authority when two registrations arrive concurrently.
                throw DuplicateRegistrationException()
            }
        val credential =
            try {
                localCredentialRepository.saveAndFlush(
                    LocalCredential(user = user, passwordHash = PasswordHasher.hash(request.password)),
                )
            } catch (_: DataIntegrityViolationException) {
                throw DuplicateRegistrationException()
            }
        ensureLocalIdentity(user)
        return authenticate(user, credential, request.password)
    }

    @Transactional
    fun login(request: LoginRequest): LocalAuthentication {
        val email = CanonicalEmail.normalize(request.email)
        validateEmail(email)
        val user = appUserRepository.findByEmail(email)
        val credential = user?.let { localCredentialRepository.findByUserId(it.id) }
        if (credential == null) {
            PasswordHasher.matches(request.password, PasswordHasher.DUMMY_HASH)
            throw InvalidCredentialsException()
        }
        return authenticate(requireNotNull(user), credential, request.password)
    }

    private fun authenticate(
        user: AppUser,
        credential: LocalCredential,
        password: String,
    ): LocalAuthentication {
        if (!PasswordHasher.matches(password, credential.passwordHash)) {
            throw InvalidCredentialsException()
        }
        ensureLocalIdentity(user)
        val session =
            identitySessionService.establishSession(
                VerifiedIdentityClaims(
                    issuer = LOCAL_ISSUER,
                    subject = user.id.toString(),
                    emailSnapshot = user.email,
                    emailVerified = true,
                ),
                devPrincipal = false,
            )
        return LocalAuthentication(user = user, session = session)
    }

    private fun ensureLocalIdentity(user: AppUser) {
        if (authIdentityRepository.findByIssuerAndSubject(LOCAL_ISSUER, user.id.toString()) == null) {
            authIdentityRepository.saveAndFlush(
                AuthIdentity(
                    user = user,
                    issuer = LOCAL_ISSUER,
                    subject = user.id.toString(),
                    emailSnapshot = user.email,
                    emailVerified = true,
                ),
            )
        }
    }

    private fun validatePassword(password: String) {
        require(password.length >= MIN_PASSWORD_LENGTH) { "password must be at least $MIN_PASSWORD_LENGTH characters" }
        require(password.length <= MAX_PASSWORD_LENGTH) { "password must not exceed $MAX_PASSWORD_LENGTH characters" }
    }

    private fun validateEmail(email: String) {
        require(EMAIL_PATTERN.matches(email)) { "email must be valid" }
    }

    companion object {
        const val LOCAL_ISSUER = "local"
        private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private const val MIN_PASSWORD_LENGTH = 8
        private const val MAX_PASSWORD_LENGTH = 200
    }
}

data class LocalAuthentication(
    val user: AppUser,
    val session: EstablishedSession,
)

/**
 * One canonical representation for local email identity: Unicode surrounding
 * whitespace is removed, then Locale.ROOT lowercasing is applied. Provider
 * specific rewrites (such as Gmail dot/plus handling) are intentionally absent.
 */
object CanonicalEmail {
    fun normalize(value: String): String = value.trim { it.isWhitespace() }.lowercase(Locale.ROOT)
}

private object PasswordHasher {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 310_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    // A fixed verifier ensures unknown-email login still performs the same
    // password derivation without revealing whether an account exists.
    val DUMMY_HASH = hash("dummy-password-for-unknown-user")

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        return format(salt, derive(password, salt, ITERATIONS))
    }

    fun matches(
        password: String,
        encoded: String,
    ): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        return try {
            val salt = decoder.decode(parts[2])
            val expected = decoder.decode(parts[3])
            val actual = derive(password, salt, iterations)
            MessageDigest.isEqual(expected, actual)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun format(
        salt: ByteArray,
        hash: ByteArray,
    ): String = "pbkdf2-sha256\$$ITERATIONS\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"

    private fun derive(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
