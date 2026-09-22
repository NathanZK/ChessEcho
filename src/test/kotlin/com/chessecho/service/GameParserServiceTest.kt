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
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
        whenever(positionOccurrenceRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1)
        val nativeParser = GameParserService(positionRepository, positionOccurrenceRepository, postgresqlMetadataDataSource())

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

        nativeParser.parseAndSavePositions(listOf(game))

        val hashCaptor = argumentCaptor<String>()
        verify(positionRepository, times(3)).insertIfAbsent(any(), hashCaptor.capture(), any(), any())
        assertEquals(hashCaptor.allValues.sorted(), hashCaptor.allValues)
    }

    @Test
    fun `the native occurrence insert is reserved for PostgreSQL datasources`() {
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer { it.getArgument<List<Position>>(0) }
        whenever(positionOccurrenceRepository.findByGameIdIn(any())).thenReturn(emptyList())
        val h2Parser = GameParserService(positionRepository, positionOccurrenceRepository, metadataDataSource("H2"))

        h2Parser.parseAndSavePositions(listOf(sampleGame("h2-game")))

        verify(positionOccurrenceRepository, never())
            .insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any())
        verify(positionRepository, never()).insertIfAbsent(any(), any(), any(), any())
        verify(positionOccurrenceRepository).saveAll(any<List<PositionOccurrence>>())
    }

    @Test
    fun `a PostgreSQL datasource uses the conflict-safe occurrence insert`() {
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.findByHash(any())).thenReturn(null)
        whenever(positionRepository.insertIfAbsent(any(), any(), any(), any())).thenReturn(1)
        whenever(positionOccurrenceRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(1)
        val postgresParser =
            GameParserService(positionRepository, positionOccurrenceRepository, metadataDataSource("PostgreSQL"))

        postgresParser.parseAndSavePositions(listOf(sampleGame("pg-game")))

        verify(positionOccurrenceRepository, atLeastOnce())
            .insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any())
        verify(positionOccurrenceRepository, never()).saveAll(any<List<PositionOccurrence>>())
    }

    private fun sampleGame(id: String): Game =
        Game(
            chessAccount = ChessAccount(user = AppUser(email = "branch@example.com"), platform = "CHESS_COM", username = "tester"),
            platformGameId = id,
            whiteUsername = "tester",
            blackUsername = "opponent",
            pgn = "1. e4 e5 2. Nf3 Nc6 1/2-1/2",
            timeControl = "600",
            playedAt = Instant.now(),
        )

    private fun metadataDataSource(productName: String): DataSource {
        val dataSource = mock<DataSource>()
        val connection = mock<java.sql.Connection>()
        val metadata = mock<java.sql.DatabaseMetaData>()
        whenever(dataSource.connection).thenReturn(connection)
        whenever(connection.metaData).thenReturn(metadata)
        whenever(metadata.databaseProductName).thenReturn(productName)
        return dataSource
    }

    @Test
    fun `occurrence candidate key records game position ply and color semantics`() {
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer { it.getArgument<List<Position>>(0) }

        val account = ChessAccount(user = AppUser(email = "identity@example.com"), platform = "CHESS_COM", username = "tester")
        val firstGame =
            Game(
                chessAccount = account,
                platformGameId = "identity-game-1",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn = "1. e4 e5 2. Nf3 Nc6 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )
        val secondGame =
            Game(
                chessAccount = account,
                platformGameId = "identity-game-2",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn = "1. e4 e5 2. Nf3 Nc6 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(firstGame, secondGame))

        val occurrences = argumentCaptor<List<PositionOccurrence>>()
        verify(positionOccurrenceRepository).saveAll(occurrences.capture())
        val samePlyOccurrences = occurrences.firstValue.filter { it.plyNumber == 1 && it.playerColor == "WHITE" }
        assertEquals(2, samePlyOccurrences.size)

        // Candidate key: (game_id, position_id, ply_number, player_color).
        // move_played is descriptive; account_id is derivable from game_id.
        val candidateKeys =
            samePlyOccurrences.map {
                listOf(
                    it.game.id,
                    it.position.id,
                    it.plyNumber,
                    it.playerColor,
                )
            }
        assertEquals(2, candidateKeys.distinct().size)
        assertEquals(
            setOf("identity-game-1", "identity-game-2"),
            samePlyOccurrences.map { it.game.platformGameId }.toSet(),
            "the candidate key carries the game identity, not only canonical position, ply, and color",
        )
        assertTrue(
            samePlyOccurrences.map { it.game.platformGameId }.distinct().size == samePlyOccurrences.size,
            "distinct semantic games have distinct platform identities, which map to distinct game_id values",
        )
        assertNotEquals(
            samePlyOccurrences[0].game.id,
            samePlyOccurrences[1].game.id,
            "distinct platform game identities must resolve to distinct database game_id values",
        )
        assertTrue(
            samePlyOccurrences.map { it.position.hash }.distinct().size == 1,
            "the fixture proves the games share the same canonical position while remaining distinct occurrences",
        )
    }

    @Test
    fun `reprocessing the same game is expected to reconcile rather than duplicate occurrences`() {
        val persistedPositions = mutableListOf<Position>()
        whenever(positionRepository.findByHashIn(any())).thenAnswer {
            val hashes = it.getArgument<List<String>>(0)
            persistedPositions.filter { position -> position.hash in hashes }
        }
        whenever(positionRepository.findByHash(any())).thenAnswer {
            val hash = it.getArgument<String>(0)
            persistedPositions.singleOrNull { position -> position.hash == hash }
        }
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer {
            it.getArgument<List<Position>>(0).also(persistedPositions::addAll)
        }
        val persistedOccurrences = mutableListOf<PositionOccurrence>()
        whenever(positionOccurrenceRepository.findByGameIdIn(any())).thenAnswer {
            val gameIds = it.getArgument<Collection<UUID>>(0)
            persistedOccurrences.filter { occurrence -> occurrence.game.id in gameIds }
        }
        whenever(positionOccurrenceRepository.saveAll(any<List<PositionOccurrence>>())).thenAnswer {
            it.getArgument<List<PositionOccurrence>>(0).also(persistedOccurrences::addAll)
        }

        val account = ChessAccount(user = AppUser(email = "retry@example.com"), platform = "CHESS_COM", username = "tester")
        val game =
            Game(
                chessAccount = account,
                platformGameId = "retry-game",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn = "1. e4 e5 2. Nf3 Nc6 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game))
        gameParserService.parseAndSavePositions(listOf(game))

        verify(positionOccurrenceRepository, times(1)).saveAll(any<List<PositionOccurrence>>())
    }

    private fun postgresqlMetadataDataSource(): DataSource {
        val dataSource = mock<DataSource>()
        val connection = mock<java.sql.Connection>()
        val metadata = mock<java.sql.DatabaseMetaData>()
        whenever(dataSource.connection).thenReturn(connection)
        whenever(connection.metaData).thenReturn(metadata)
        whenever(metadata.databaseProductName).thenReturn("PostgreSQL")
        return dataSource
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

    @Autowired
    private lateinit var postgresBackedParser: GameParserService

    @Autowired
    private lateinit var dataSource: DataSource

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
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf(gameOne, gameTwo).map { game ->
                    executor.submit<Set<java.util.UUID>> {
                        startBarrier.countDown()
                        assertTrue(startBarrier.await(10, TimeUnit.SECONDS), "Both parser transactions must start together")
                        val account = if (game.id == gameOne.id) parserAccountOne else parserAccountTwo
                        postgresBackedParser.parseAndSavePositions(
                            listOf(
                                Game(
                                    id = game.id,
                                    chessAccount = account,
                                    platformGameId = game.platformGameId,
                                    pgn = game.pgn,
                                    timeControl = game.timeControl,
                                    playedAt = game.playedAt,
                                    result = game.result,
                                    whiteUsername = game.whiteUsername,
                                    blackUsername = game.blackUsername,
                                ),
                            ),
                        )
                    }
                }

            val affectedIds = futures.flatMap { it.get(15, TimeUnit.SECONDS) }.toSet()
            assertEquals(1, affectedIds.size)

            val positions = positionRepository.findAll()
            assertEquals(1, positions.size)

            val occurrences = positionOccurrenceRepository.findAll()
            assertEquals(2, occurrences.size)
            assertEquals(setOf(positions.single().id), occurrences.map { it.position.id }.toSet())
            assertEquals(1, occurrences.count { it.game.id == gameOne.id })
            assertEquals(1, occurrences.count { it.game.id == gameTwo.id })
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

        val parser = GameParserService(repositorySpy, positionOccurrenceRepository, dataSource)
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
            assertEquals(2, positionOccurrenceRepository.count(), "concurrent replay must reconcile semantic occurrences")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent PostgreSQL parsers insert one semantic occurrence for the same game position`() {
        val account = chessAccountRepository.saveAndFlush(ChessAccount(platform = "CHESS_COM", username = "same-game"))
        val persistedGame = gameRepository.saveAndFlush(game(account, "same-game-id", "same-game"))
        val startBarrier = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map {
                    executor.submit {
                        startBarrier.countDown()
                        assertTrue(startBarrier.await(10, TimeUnit.SECONDS), "Both parsers must start together")
                        val reloaded = gameRepository.findById(persistedGame.id).orElseThrow()
                        postgresBackedParser.parseAndSavePositions(listOf(reloaded))
                    }
                }

            val failures = futures.map { runCatching { it.get(20, TimeUnit.SECONDS) }.exceptionOrNull() }
            assertTrue(failures.all { it == null }, "Unexpected PostgreSQL occurrence conflict failure: $failures")
            assertEquals(1, positionRepository.count())
            assertEquals(
                1,
                positionOccurrenceRepository.count(),
                "the database identity (game, position, ply, color) must collapse concurrent replays",
            )
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
