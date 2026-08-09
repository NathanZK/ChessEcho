package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.AsyncJob
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.UserPositionStats
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.UUID

@Service
class GameImportService(
    private val asyncJobRepository: AsyncJobRepository,
    private val appUserRepository: AppUserRepository,
    private val chessAccountRepository: ChessAccountRepository,
    private val gameRepository: GameRepository,
    private val restClient: RestClient,
    private val gameParserService: GameParserService,
    private val userPositionStatsRepository: UserPositionStatsRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val positionRepository: PositionRepository,
    private val engineAnalysisOrchestrator: EngineAnalysisOrchestrator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val ACTIVE_STATUSES = listOf("QUEUED", "PROCESSING")
    }

    /**
     * Creates a new QUEUED import job for the specified user and platform.
     * Throws ActiveImportJobException if an active job already exists.
     */
    @Transactional
    fun createImportJob(request: ImportGamesRequest): AsyncJob {
        asyncJobRepository.findByUsernameAndStatusIn(request.username, ACTIVE_STATUSES)
            ?.let {
                throw ActiveImportJobException(
                    "An active import job already exists for username '${request.username}' (jobId=${it.id})",
                )
            }

        val job =
            AsyncJob(
                username = request.username,
                platform = request.platform,
                status = "QUEUED",
            )
        return asyncJobRepository.save(job)
    }

    /**
     * Asynchronously executes a game import job by ID. Loads the AsyncJob within the transaction,
     * fetches games from the external platform, parses positions, updates UserPositionStats,
     * and triggers engine analysis orchestration for affected positions.
     */
    @Async
    @Transactional
    fun executeImportJob(
        jobId: UUID,
        request: ImportGamesRequest,
    ) {
        val job =
            asyncJobRepository.findById(jobId)
                .orElseThrow { IllegalStateException("AsyncJob not found: $jobId") }

        log.info("Starting import job ${job.id} for user ${request.username}")
        updateJobStatus(job, "PROCESSING")

        try {
            // Ensure we have a user account to attach these games to
            val account = getOrCreateAccount(request.username, request.platform)

            val archiveUrls = fetchArchiveUrls(request.username, request.fromDate, request.toDate)
            var imported = 0
            var skipped = 0
            val allAffectedPositionIds = mutableSetOf<UUID>()

            for (archiveUrl in archiveUrls) {
                val (monthImported, monthSkipped, affectedPositionIds) = importMonth(account, archiveUrl, request)
                imported += monthImported
                skipped += monthSkipped
                allAffectedPositionIds.addAll(affectedPositionIds)
            }

            // Update UserPositionStats after all games are imported
            updateUserPositionStats(account, allAffectedPositionIds)

            // Trigger engine analysis orchestrator for affected positions
            engineAnalysisOrchestrator.analyzeAffectedPositions(allAffectedPositionIds)

            job.gamesImported = imported
            job.gamesSkipped = skipped
            updateJobStatus(job, "COMPLETED")
            log.info("Import job ${job.id} completed: $imported imported, $skipped skipped")
        } catch (ex: Exception) {
            log.error("Import job ${job.id} failed", ex)
            job.errorMessage = ex.message
            updateJobStatus(job, "FAILED")
        }
    }

    private fun updateJobStatus(
        job: AsyncJob,
        status: String,
    ) {
        job.status = status
        job.updatedAt = Instant.now()
        asyncJobRepository.save(job)
    }

    private fun getOrCreateAccount(
        username: String,
        platform: String,
    ): ChessAccount {
        chessAccountRepository.findByPlatformAndUsernameIgnoreCase(platform, username)?.let { return it }

        // We don't have authentication yet, so we generate a dummy AppUser based on the username
        // to satisfy the foreign key constraint.
        val dummyEmail = "$username@placeholder.chessecho.com"
        val user = appUserRepository.findByEmail(dummyEmail) ?: appUserRepository.save(AppUser(email = dummyEmail))

        return chessAccountRepository.save(
            ChessAccount(
                user = user,
                platform = platform,
                username = username,
            ),
        )
    }

    private fun importMonth(
        account: ChessAccount,
        archiveUrl: String,
        request: ImportGamesRequest,
    ): Triple<Int, Int, Set<java.util.UUID>> {
        val username = request.username
        log.debug("Fetching games from $archiveUrl")

        @Suppress("UNCHECKED_CAST")
        val response =
            restClient.get()
                .uri(archiveUrl)
                .retrieve()
                .body(Map::class.java) as? Map<String, Any> ?: return Triple(0, 0, emptySet())

        @Suppress("UNCHECKED_CAST")
        val games = response["games"] as? List<Map<String, Any>> ?: return Triple(0, 0, emptySet())

        val allUrls = games.mapNotNull { it["url"] as? String }
        val existingUrls = gameRepository.findPlatformGameIdsByChessAccountAndPlatformGameIdIn(account, allUrls).toSet()
        val gamesToSave = mutableListOf<Game>()

        var skipped = 0

        for (game in games) {
            val timeClass = game["time_class"] as? String ?: continue
            if (!request.timeControls.contains(timeClass)) continue

            val white = (game["white"] as? Map<*, *>)?.get("username") as? String ?: continue
            val black = (game["black"] as? Map<*, *>)?.get("username") as? String ?: continue

            val isPlayerWhite = white.equals(username, ignoreCase = true)
            val isPlayerBlack = black.equals(username, ignoreCase = true)
            val colorMatch =
                when (request.playerColor.lowercase()) {
                    "white" -> isPlayerWhite
                    "black" -> isPlayerBlack
                    "both" -> isPlayerWhite || isPlayerBlack
                    else -> false
                }
            if (!colorMatch) continue

            // Parse game details
            val pgn = game["pgn"] as? String ?: continue
            val url = game["url"] as? String ?: continue // Use URL as unique game ID
            val playedAtUnix = (game["end_time"] as? Number)?.toLong()
            val whiteResult = (game["white"] as? Map<*, *>)?.get("result") as? String

            if (existingUrls.contains(url)) {
                skipped++
                continue
            }

            val gameEntity =
                Game(
                    chessAccount = account,
                    platformGameId = url,
                    pgn = pgn,
                    timeControl = timeClass,
                    playedAt = playedAtUnix?.let { Instant.ofEpochSecond(it) },
                    result = whiteResult,
                    whiteUsername = white,
                    blackUsername = black,
                )

            gamesToSave.add(gameEntity)
        }

        if (gamesToSave.isNotEmpty()) {
            val savedGames = gameRepository.saveAll(gamesToSave)
            val affectedPositionIds = gameParserService.parseAndSavePositions(savedGames)
            return Triple(gamesToSave.size, skipped, affectedPositionIds)
        }

        return Triple(0, skipped, emptySet())
    }

    private fun updateUserPositionStats(
        account: ChessAccount,
        affectedPositionIds: Set<java.util.UUID>,
    ) {
        if (affectedPositionIds.isEmpty()) return

        // Use bulk aggregation query to count occurrences for affected positions
        val occurrenceCounts =
            positionOccurrenceRepository.countOccurrencesByAccountAndPositions(
                account.id,
                affectedPositionIds,
            )

        // Fetch existing UserPositionStats for affected positions
        val existingStats =
            userPositionStatsRepository.findByChessAccountIdAndPositionIdIn(
                account.id,
                affectedPositionIds,
            )
        val existingStatsMap = existingStats.associateBy { "${it.chessAccount.id}-${it.position.id}-${it.playerColor}" }

        // Fetch all needed Position entities in one query
        val positions =
            positionRepository.findAllById(affectedPositionIds).associateBy { it.id }

        val statsUpdates = mutableListOf<UserPositionStats>()
        for (count in occurrenceCounts) {
            val key = "${account.id}-${count.positionId}-${count.playerColor}"
            val existing = existingStatsMap[key]

            if (existing != null) {
                existing.timesReached = count.timesReached.toInt()
                existing.updatedAt = Instant.now()
                statsUpdates.add(existing)
            } else {
                val position =
                    positions[count.positionId]
                        ?: throw IllegalStateException("Position not found: ${count.positionId}")
                val newStats =
                    UserPositionStats(
                        chessAccount = account,
                        position = position,
                        playerColor = count.playerColor,
                        timesReached = count.timesReached.toInt(),
                        updatedAt = Instant.now(),
                    )
                statsUpdates.add(newStats)
            }
        }

        userPositionStatsRepository.saveAll(statsUpdates)
    }

    private fun fetchArchiveUrls(
        username: String,
        fromDate: String?,
        toDate: String?,
    ): List<String> {
        val url = "https://api.chess.com/pub/player/${username.lowercase()}/games/archives"

        @Suppress("UNCHECKED_CAST")
        val response =
            restClient.get()
                .uri(url)
                .retrieve()
                .body(Map::class.java) as? Map<String, Any> ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val allArchives = response["archives"] as? List<String> ?: return emptyList()

        return allArchives.filter { archiveUrl ->
            val parts = archiveUrl.split("/")
            if (parts.size >= 2) {
                val year = parts[parts.size - 2]
                val month = parts[parts.size - 1]
                val archiveMonth = "$year-$month"

                val afterFrom = fromDate == null || archiveMonth >= fromDate
                val beforeTo = toDate == null || archiveMonth <= toDate
                afterFrom && beforeTo
            } else {
                false
            }
        }
    }
}

class ActiveImportJobException(message: String) : RuntimeException(message)
