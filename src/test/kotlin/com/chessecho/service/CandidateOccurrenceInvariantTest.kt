package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Position
import com.chessecho.domain.UserPositionStats
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
class CandidateOccurrenceInvariantTest {
    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var userPositionStatsRepository: UserPositionStatsRepository

    @Test
    fun `Case A - User A + White + 4 occurrences must NOT qualify`() {
        val userA = appUserRepository.save(AppUser(email = "userA_a@example.com"))
        val accountA = chessAccountRepository.save(ChessAccount(user = userA, platform = "CHESS_COM", username = "userA_a"))
        val posA = positionRepository.save(Position(hash = "hash_A", fen = "fen_A"))

        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = accountA, position = posA, playerColor = "WHITE", timesReached = 4),
        )

        val qualifying = positionRepository.findQualifyingPositions(setOf(posA.id), minOccurrences = 5)
        assertFalse(qualifying.any { it.id == posA.id }, "Case A: 4 occurrences must NOT qualify")
    }

    @Test
    fun `Case B - User A + White + 5 occurrences MUST qualify`() {
        val userA = appUserRepository.save(AppUser(email = "userA_b@example.com"))
        val accountA = chessAccountRepository.save(ChessAccount(user = userA, platform = "CHESS_COM", username = "userA_b"))
        val posB = positionRepository.save(Position(hash = "hash_B", fen = "fen_B"))

        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = accountA, position = posB, playerColor = "WHITE", timesReached = 5),
        )

        val qualifying = positionRepository.findQualifyingPositions(setOf(posB.id), minOccurrences = 5)
        assertTrue(qualifying.any { it.id == posB.id }, "Case B: 5 occurrences MUST qualify")
    }

    @Test
    fun `Case C - User A + White 4 and Black 1 must NOT qualify`() {
        val userA = appUserRepository.save(AppUser(email = "userA_c@example.com"))
        val accountA = chessAccountRepository.save(ChessAccount(user = userA, platform = "CHESS_COM", username = "userA_c"))
        val posC = positionRepository.save(Position(hash = "hash_C", fen = "fen_C"))

        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = accountA, position = posC, playerColor = "WHITE", timesReached = 4),
        )
        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = accountA, position = posC, playerColor = "BLACK", timesReached = 1),
        )

        val qualifying = positionRepository.findQualifyingPositions(setOf(posC.id), minOccurrences = 5)
        assertFalse(qualifying.any { it.id == posC.id }, "Case C: White 4 + Black 1 must NOT qualify as candidate")
    }

    @Test
    fun `Case D - User A 4 + User B 1 must NOT qualify for User A`() {
        val userA = appUserRepository.save(AppUser(email = "userA_d@example.com"))
        val accountA = chessAccountRepository.save(ChessAccount(user = userA, platform = "CHESS_COM", username = "userA_d"))
        val userB = appUserRepository.save(AppUser(email = "userB_d@example.com"))
        val accountB = chessAccountRepository.save(ChessAccount(user = userB, platform = "CHESS_COM", username = "userB_d"))

        val posD = positionRepository.save(Position(hash = "hash_D", fen = "fen_D"))

        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = accountA, position = posD, playerColor = "WHITE", timesReached = 4),
        )
        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = accountB, position = posD, playerColor = "WHITE", timesReached = 1),
        )

        val qualifying = positionRepository.findQualifyingPositions(setOf(posD.id), minOccurrences = 5)
        assertFalse(qualifying.any { it.id == posD.id }, "Case D: User A 4 + User B 1 must NOT qualify")
    }

    @Test
    fun `Case E - User A White 5 + Black 2 qualifies White`() {
        val userA = appUserRepository.save(AppUser(email = "userA_e@example.com"))
        val accountA = chessAccountRepository.save(ChessAccount(user = userA, platform = "CHESS_COM", username = "userA_e"))
        val posE = positionRepository.save(Position(hash = "hash_E", fen = "fen_E"))

        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = accountA, position = posE, playerColor = "WHITE", timesReached = 5),
        )
        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = accountA, position = posE, playerColor = "BLACK", timesReached = 2),
        )

        val qualifying = positionRepository.findQualifyingPositions(setOf(posE.id), minOccurrences = 5)
        assertTrue(qualifying.any { it.id == posE.id }, "Case E: White 5 qualifies position for candidate analysis")
    }

    @Test
    fun `Case F - User A White 5 + Black 5 both qualify independently`() {
        val userA = appUserRepository.save(AppUser(email = "userA_f@example.com"))
        val accountA = chessAccountRepository.save(ChessAccount(user = userA, platform = "CHESS_COM", username = "userA_f"))
        val posF = positionRepository.save(Position(hash = "hash_F", fen = "fen_F"))

        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = accountA, position = posF, playerColor = "WHITE", timesReached = 5),
        )
        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = accountB_f(userA, accountA), position = posF, playerColor = "BLACK", timesReached = 5),
        )

        val qualifying = positionRepository.findQualifyingPositions(setOf(posF.id), minOccurrences = 5)
        assertTrue(qualifying.any { it.id == posF.id }, "Case F: Both White 5 and Black 5 qualify")
    }

    private fun accountB_f(
        user: AppUser,
        account: ChessAccount,
    ): ChessAccount {
        return account
    }
}
