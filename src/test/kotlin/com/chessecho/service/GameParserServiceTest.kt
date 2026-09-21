package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.AdditionalAnswers
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GameParserServiceTest {
    private lateinit var positionRepository: PositionRepository
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository
    private lateinit var gameParserService: GameParserService

    @BeforeEach
    fun setup() {
        positionRepository = mock()
        positionOccurrenceRepository = mock()
        gameParserService = GameParserService(positionRepository, positionOccurrenceRepository)
    }

    @Test
    fun `parseAndSavePositions extracts positions correctly`() {
        // Mock DB dependencies
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer {
            val list = it.getArgument<List<Position>>(0)
            list
        }

        val appUser = AppUser(email = "test@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "tester")
        val game =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-1",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn =
                    """
                    [Event "FIDE World Cup 2017"]
                    [White "tester"]
                    [Black "opponent"]

                    1. e4 e5 2. Nf3 Nc6 3. Bb5 1/2-1/2
                    """.trimIndent(),
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game))

        // 3 decision plies for White: e4, Nf3, Bb5
        val positionsCaptor = argumentCaptor<List<Position>>()
        verify(positionRepository, times(1)).saveAll(positionsCaptor.capture())

        val savedPositions = positionsCaptor.firstValue
        assertEquals(3, savedPositions.size)

        // Before e4 (startpos)
        val hashStartFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val expectedHashStart = digest.digest(hashStartFen.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        assertTrue(savedPositions.any { it.hash == expectedHashStart }, "Expected start position before e4 not found")

        val occurrencesCaptor = argumentCaptor<List<com.chessecho.domain.PositionOccurrence>>()
        verify(positionOccurrenceRepository, times(1)).saveAll(occurrencesCaptor.capture())

        val savedOccurrences = occurrencesCaptor.firstValue
        assertEquals(3, savedOccurrences.size)

        val ply1 = savedOccurrences.first { it.plyNumber == 1 }
        assertEquals("e4", ply1.movePlayed)
        assertEquals("WHITE", ply1.playerColor)

        val ply5 = savedOccurrences.first { it.plyNumber == 5 }
        assertEquals("Bb5", ply5.movePlayed)
        assertEquals("WHITE", ply5.playerColor)
    }

    @Test
    fun `transpositions generate the same hash`() {
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer { it.getArgument<List<Position>>(0) }

        val appUser = AppUser(email = "test@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "tester")

        // Game 1: 1. d3 d6 2. e3 e6 (2 White positions)
        val game1 =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-1",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn = "1. d3 d6 2. e3 e6 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )
        // Game 2: 1. e3 e6 2. d3 d6 (2 White positions)
        val game2 =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-2",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn = "1. e3 e6 2. d3 d6 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game1, game2))

        val positionsCaptor = argumentCaptor<List<Position>>()
        verify(positionRepository, times(1)).saveAll(positionsCaptor.capture())

        val savedPositions = positionsCaptor.firstValue

        // Game 1 has 2 White positions (before d3, before e3), Game 2 has 2 White positions (before e3, before d3).
        // The start position is shared in both games.
        // Total unique White positions across G1 and G2 = 3.
        assertEquals(3, savedPositions.size, "Transpositions must generate the exact same hash and deduplicate")
    }

    @Test
    fun `different castling rights generate different hashes`() {
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer { it.getArgument<List<Position>>(0) }

        val appUser = AppUser(email = "test@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "tester")

        // Game 1: King moves and returns, losing castling rights (3 White positions: before e4, before Ke2, before Ke1)
        val game1 =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-1",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn = "1. e4 e5 2. Ke2 d6 3. Ke1 d5 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )
        // Game 2: Knight moves and returns, retaining castling rights (3 White positions: before e4, before Nf3, before Ng1)
        val game2 =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-2",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn = "1. e4 e5 2. Nf3 d6 3. Ng1 d5 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game1, game2))

        val positionsCaptor = argumentCaptor<List<Position>>()
        verify(positionRepository, times(1)).saveAll(positionsCaptor.capture())

        val savedPositions = positionsCaptor.firstValue

        // Plies 1 (startpos) and 3 (after 1... e5) are identical across both games.
        // Ply 5 (before 3. Ke1 vs before 3. Ng1) differ due to piece placement & castling rights.
        // Total unique White positions = 2 shared + 1 unique G1 + 1 unique G2 = 4.
        assertEquals(4, savedPositions.size, "Different castling rights must generate different hashes")
    }

    @Test
    fun `conflict safe position inserts use deterministic hash order`() {
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.findByHash(any())).thenReturn(null)
        whenever(positionRepository.insertIfAbsent(any(), any(), any(), any())).thenReturn(1)

        val appUser = AppUser(email = "test@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "tester")
        val game =
            Game(
                chessAccount = chessAccount,
                platformGameId = "ordered-inserts",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn = "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game))

        val hashCaptor = argumentCaptor<String>()
        verify(positionRepository, times(3)).insertIfAbsent(any(), hashCaptor.capture(), any(), any())
        assertEquals(hashCaptor.allValues.sorted(), hashCaptor.allValues)
    }
}

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class GameParserServiceConcurrencyTest {
    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
        }
    }

    @AfterEach
    fun tearDown() {
        positionOccurrenceRepository.deleteAll()
        positionRepository.deleteAll()
        gameRepository.deleteAll()
        chessAccountRepository.deleteAll()
    }

    @Test
    fun `concurrent parsers share one canonical position`() {
        val accountOne = chessAccountRepository.saveAndFlush(ChessAccount(platform = "CHESS_COM", username = "player-one"))
        val accountTwo = chessAccountRepository.saveAndFlush(ChessAccount(platform = "CHESS_COM", username = "player-two"))
        val gameOne = gameRepository.saveAndFlush(game(accountOne, "game-one", "player-one"))
        val gameTwo = gameRepository.saveAndFlush(game(accountTwo, "game-two", "player-two"))
        val parserAccountOne = ChessAccount(id = accountOne.id, platform = accountOne.platform, username = accountOne.username)
        val parserAccountTwo = ChessAccount(id = accountTwo.id, platform = accountTwo.platform, username = accountTwo.username)

        val startBarrier = CountDownLatch(2)
        val repositorySpy =
            mock<PositionRepository>(
                defaultAnswer = AdditionalAnswers.delegatesTo(positionRepository),
            )
        val capturedOccurrences = ConcurrentLinkedQueue<List<PositionOccurrence>>()
        val occurrenceRepositorySpy = mock<PositionOccurrenceRepository>()
        doAnswer {
            positionRepository.findByHashIn(it.getArgument<List<String>>(0))
        }.whenever(repositorySpy).findByHashIn(any<List<String>>())
        doAnswer {
            positionRepository.saveAll(it.getArgument<List<Position>>(0))
        }.whenever(repositorySpy).saveAll(any<List<Position>>())
        whenever(occurrenceRepositorySpy.saveAll(any<List<PositionOccurrence>>())).thenAnswer {
            val occurrences = it.getArgument<List<PositionOccurrence>>(0)
            capturedOccurrences.add(occurrences)
            occurrences
        }

        val parser = GameParserService(repositorySpy, occurrenceRepositorySpy)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf(gameOne, gameTwo).map { game ->
                    executor.submit<Set<java.util.UUID>> {
                        startBarrier.countDown()
                        assertTrue(startBarrier.await(10, TimeUnit.SECONDS), "Both parser transactions must start together")
                        transactionTemplate.execute {
                            val parserGame =
                                Game(
                                    id = game.id,
                                    chessAccount = if (game.id == gameOne.id) parserAccountOne else parserAccountTwo,
                                    platformGameId = game.platformGameId,
                                    pgn = game.pgn,
                                    timeControl = game.timeControl,
                                    playedAt = game.playedAt,
                                    result = game.result,
                                    whiteUsername = game.whiteUsername,
                                    blackUsername = game.blackUsername,
                                )
                            parser.parseAndSavePositions(listOf(parserGame))
                        } ?: error("Parser transaction returned no result")
                    }
                }

            val affectedIds = futures.flatMap { it.get(15, TimeUnit.SECONDS) }.toSet()
            assertEquals(1, affectedIds.size)

            val positions = positionRepository.findAll()
            assertEquals(1, positions.size)

            val occurrences = capturedOccurrences.flatten()
            assertEquals(2, occurrences.size)
            assertEquals(setOf(positions.single().id), occurrences.map { it.position.id }.toSet())
            assertEquals(1, occurrences.count { it.game.id == gameOne.id })
            assertEquals(1, occurrences.count { it.game.id == gameTwo.id })
            positionOccurrenceRepository.saveAll(occurrences)
            assertNotNull(positions.single().id)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent parsers resolve opposite overlapping position order`() {
        val accountOne = chessAccountRepository.saveAndFlush(ChessAccount(platform = "CHESS_COM", username = "order-one"))
        val accountTwo = chessAccountRepository.saveAndFlush(ChessAccount(platform = "CHESS_COM", username = "order-two"))
        val gameOne =
            gameRepository.saveAndFlush(
                transposedBlackGame(accountOne, "order-one-game", "order-one", "1. Nf3 Nf6 1/2-1/2"),
            )
        val gameTwo =
            gameRepository.saveAndFlush(
                transposedBlackGame(accountTwo, "order-two-game", "order-two", "1. Nc3 Nc6 1/2-1/2"),
            )
        val insertBarrier = CountDownLatch(2)
        val insertCounts = ThreadLocal.withInitial { 0 }
        val repositorySpy =
            mock<PositionRepository>(
                defaultAnswer = AdditionalAnswers.delegatesTo(positionRepository),
            )
        doAnswer {
            val count = insertCounts.get() + 1
            insertCounts.set(count)
            if (count == 1) {
                insertBarrier.countDown()
                assertTrue(insertBarrier.await(10, TimeUnit.SECONDS), "Both transactions must reach their first black position")
            }
            positionRepository.insertIfAbsent(
                it.getArgument(0),
                it.getArgument(1),
                it.getArgument(2),
                it.getArgument(3),
            )
        }.whenever(repositorySpy).insertIfAbsent(any(), any(), any(), any())

        val parser = GameParserService(repositorySpy, positionOccurrenceRepository)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf(gameOne to listOf(gameOne, gameTwo), gameTwo to listOf(gameTwo, gameOne)).map { (game, games) ->
                    executor.submit {
                        transactionTemplate.execute {
                            val account = chessAccountRepository.findById(game.chessAccount.id).orElseThrow()
                            parser.parseAndSavePositions(
                                games.map {
                                    Game(
                                        id = it.id,
                                        chessAccount = account,
                                        platformGameId = it.platformGameId,
                                        pgn = it.pgn,
                                        timeControl = it.timeControl,
                                        playedAt = it.playedAt,
                                        result = it.result,
                                        whiteUsername = it.whiteUsername,
                                        blackUsername = account.username,
                                    )
                                },
                            )
                        }
                    }
                }

            val failures =
                futures.map { future ->
                    runCatching { future.get(20, TimeUnit.SECONDS) }.exceptionOrNull()
                }
            assertTrue(failures.all { it == null }, "Unexpected PostgreSQL contention failure: ${failures.joinToString()}")
            assertEquals(2, positionRepository.count())
            assertEquals(4, positionOccurrenceRepository.count())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun game(
        account: ChessAccount,
        id: String,
        username: String,
    ): Game =
        Game(
            chessAccount = account,
            platformGameId = id,
            whiteUsername = username,
            blackUsername = "opponent",
            pgn =
                """
                [Event "Concurrent import"]
                [White "$username"]
                [Black "opponent"]

                1. e4 e5 1/2-1/2
                """.trimIndent(),
            timeControl = "600",
            playedAt = Instant.now(),
        )

    private fun transposedGame(
        account: ChessAccount,
        id: String,
        username: String,
        pgnMoves: String,
    ): Game =
        Game(
            chessAccount = account,
            platformGameId = id,
            whiteUsername = username,
            blackUsername = "opponent",
            pgn =
                """
                [Event "Concurrent ordering import"]
                [White "$username"]
                [Black "opponent"]

                $pgnMoves
                """.trimIndent(),
            timeControl = "600",
            playedAt = Instant.now(),
        )

    private fun transposedBlackGame(
        account: ChessAccount,
        id: String,
        username: String,
        pgnMoves: String,
    ): Game =
        Game(
            chessAccount = account,
            platformGameId = id,
            whiteUsername = "opponent",
            blackUsername = username,
            pgn =
                """
                [Event "Concurrent ordering import"]
                [White "opponent"]
                [Black "$username"]

                $pgnMoves
                """.trimIndent(),
            timeControl = "600",
            playedAt = Instant.now(),
        )
}
