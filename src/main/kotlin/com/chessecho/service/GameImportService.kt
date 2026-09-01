package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.AsyncJob
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.ImportedArchive
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.TimeControl
import com.chessecho.domain.UserPositionStats
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.ImportedArchiveRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

@Service
class GameImportService(
    private val asyncJobRepository: AsyncJobRepository,
    private val appUserRepository: AppUserRepository,
    private val chessAccountRepository: ChessAccountRepository,
    private val gameRepository: GameRepository,
    private val importedArchiveRepository: ImportedArchiveRepository,
    private val chessComClient: ChessComClient,
    private val gameParserService: GameParserService,
    private val userPositionStatsRepository: UserPositionStatsRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val positionRepository: PositionRepository,
    private val engineAnalysisOrchestrator: EngineAnalysisOrchestrator,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val ACTIVE_STATUSES = listOf("QUEUED", "PROCESSING")
    }

    /**
     * Creates a new QUEUED import job for the specified user and platform.
     * Throws ActiveImportJobException if an active job already exists.
     */
    fun createImportJob(request: ImportGamesRequest): AsyncJob {
        return transactionTemplate.execute {
            asyncJobRepository.findByUsernameAndStatusIn(request.username, ACTIVE_STATUSES)
                ?.let {
                    throw ActiveImportJobException(
                        "An active import job already exists for username '${request.username}' (jobId=${it.id})",
                    )
                }

            val job =
                AsyncJob(
                    username = request.username,
                    platform = request.platform.name,
                    status = "QUEUED",
                )
            asyncJobRepository.save(job)
        }!!
    }

    /**
     * Asynchronously executes a game import job by ID.
     * Loads the AsyncJob, fetches archive index, filters out completed past archives,
     * fetches unimported past months + current month, parses positions, updates UserPositionStats,
     * and triggers engine analysis orchestration for affected positions.
     *
     * Transaction boundaries are explicitly managed via TransactionTemplate around database operations,
     * ensuring no database transaction remains open during external HTTP calls to Chess.com.
     */
    @Async
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
            val account = getOrCreateAccount(request.username, request.platform)
            val archiveUrls = fetchArchiveUrls(request.username, request.fromDate, request.toDate)

            val alreadyImportedArchives =
                transactionTemplate.execute {
                    importedArchiveRepository.findByChessAccount(account)
                        .associateBy { it.archiveUrl }
                } ?: emptyMap()

            val currentYearMonth = YearMonth.now(ZoneOffset.UTC).toString()

            var imported = 0
            var skipped = 0
            var filteredOut = 0
            var processed = 0
            val allAffectedPositionIds = mutableSetOf<UUID>()

            for (archiveUrl in archiveUrls) {
                val parts = archiveUrl.split("/")
                val yearMonth =
                    if (parts.size >= 2) "${parts[parts.size - 2]}-${parts[parts.size - 1]}" else ""
                val isPastMonth = yearMonth.isNotEmpty() && yearMonth < currentYearMonth

                val importedArchive = alreadyImportedArchives[archiveUrl]
                if (isPastMonth && importedArchive != null) {
                    log.info(
                        "Archive {} ({}) already imported for user {} (contains {} games), skipping HTTP download.",
                        archiveUrl,
                        yearMonth,
                        request.username,
                        importedArchive.gameCount,
                    )
                    skipped += importedArchive.gameCount
                    processed += importedArchive.gameCount
                    transactionTemplate.executeWithoutResult {
                        persistImportProgress(job, imported, skipped, filteredOut, processed)
                    }
                    continue
                }

                val res = importMonth(account, archiveUrl, yearMonth, isPastMonth, request)
                imported += res.imported
                skipped += res.skipped
                filteredOut += res.filteredOut
                processed += res.imported + res.skipped + res.filteredOut
                allAffectedPositionIds.addAll(res.affectedPositionIds)
                transactionTemplate.executeWithoutResult {
                    persistImportProgress(job, imported, skipped, filteredOut, processed)
                }
            }

            updateUserPositionStats(account, allAffectedPositionIds)

            transactionTemplate.executeWithoutResult {
                job.gamesImported = imported
                job.gamesSkipped = skipped
                job.gamesFilteredOut = filteredOut
                job.gamesProcessed = processed
                job.analysisStatus = "ANALYZING"
                updateJobStatus(job, "COMPLETED")
            }
            log.info(
                "Import job {} completed: {} imported, {} already imported / skipped",
                job.id,
                imported,
                skipped,
            )

            try {
                engineAnalysisOrchestrator.analyzeAffectedPositions(allAffectedPositionIds)
                transactionTemplate.executeWithoutResult {
                    job.analysisStatus = "COMPLETED"
                    job.updatedAt = Instant.now()
                    asyncJobRepository.save(job)
                }
            } catch (analysisEx: Exception) {
                log.error("Engine analysis failed for job ${job.id}", analysisEx)
                transactionTemplate.executeWithoutResult {
                    job.analysisStatus = "FAILED"
                    job.updatedAt = Instant.now()
                    asyncJobRepository.save(job)
                }
            }
        } catch (ex: Exception) {
            log.error("Import job ${job.id} failed", ex)
            transactionTemplate.executeWithoutResult {
                job.errorMessage = ex.message
                updateJobStatus(job, "FAILED")
            }
        }
    }

    private fun persistImportProgress(
        job: AsyncJob,
        imported: Int,
        skipped: Int,
        filteredOut: Int,
        processed: Int,
    ) {
        job.gamesImported = imported
        job.gamesSkipped = skipped
        job.gamesFilteredOut = filteredOut
        job.gamesProcessed = processed
        job.updatedAt = Instant.now()
        asyncJobRepository.save(job)
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
        platform: Platform,
    ): ChessAccount {
        return transactionTemplate.execute {
            chessAccountRepository.findByPlatformAndUsernameIgnoreCase(platform.name, username)?.let { return@execute it }

            val dummyEmail = "$username@placeholder.chessecho.com"
            val user = appUserRepository.findByEmail(dummyEmail) ?: appUserRepository.save(AppUser(email = dummyEmail))

            chessAccountRepository.save(
                ChessAccount(
                    user = user,
                    platform = platform.name,
                    username = username,
                ),
            )
        }!!
    }

    private data class ImportMonthResult(
        val imported: Int,
        val skipped: Int,
        val filteredOut: Int,
        val affectedPositionIds: Set<UUID>,
    )

    private fun importMonth(
        account: ChessAccount,
        archiveUrl: String,
        yearMonth: String,
        isPastMonth: Boolean,
        request: ImportGamesRequest,
    ): ImportMonthResult {
        val username = request.username
        log.debug("Fetching games from $archiveUrl")

        // External HTTP request executed outside any DB transaction
        val games = chessComClient.fetchMonthlyGames(archiveUrl) ?: return ImportMonthResult(0, 0, 0, emptySet())

        val allUrls = games.mapNotNull { it["url"] as? String }
        val existingUrls =
            transactionTemplate.execute {
                gameRepository.findPlatformGameIdsByChessAccountAndPlatformGameIdIn(account, allUrls).toSet()
            } ?: emptySet()

        val seenBatchUrls = mutableSetOf<String>()
        val gamesToSave = mutableListOf<Game>()

        var skipped = 0
        var filteredOut = 0

        for (game in games) {
            val rules = game["rules"] as? String
            if (rules != null && !rules.equals("chess", ignoreCase = true)) {
                filteredOut++
                continue
            }

            val timeClass = game["time_class"] as? String
            val domainTimeControl = TimeControl.fromExternal(timeClass)
            if (domainTimeControl == null || !request.timeControls.contains(domainTimeControl)) {
                filteredOut++
                continue
            }

            val white = (game["white"] as? Map<*, *>)?.get("username") as? String
            val black = (game["black"] as? Map<*, *>)?.get("username") as? String
            if (white == null || black == null) {
                filteredOut++
                continue
            }

            val isPlayerWhite = white.equals(username, ignoreCase = true)
            val isPlayerBlack = black.equals(username, ignoreCase = true)
            val colorMatch =
                when (request.playerColor) {
                    PlayerColor.WHITE -> isPlayerWhite
                    PlayerColor.BLACK -> isPlayerBlack
                    PlayerColor.BOTH -> isPlayerWhite || isPlayerBlack
                }
            if (!colorMatch) {
                filteredOut++
                continue
            }

            val pgn = game["pgn"] as? String
            val url = game["url"] as? String
            if (pgn == null || url == null) {
                filteredOut++
                continue
            }

            val playedAtUnix = (game["end_time"] as? Number)?.toLong()
            val whiteResult = (game["white"] as? Map<*, *>)?.get("result") as? String

            if (existingUrls.contains(url) || !seenBatchUrls.add(url)) {
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

        val affectedPositionIds =
            if (gamesToSave.isNotEmpty()) {
                val ids =
                    transactionTemplate.execute {
                        val savedGames = gameRepository.saveAll(gamesToSave)
                        gameParserService.parseAndSavePositions(savedGames)
                    } ?: emptySet()
                if (ids.isNotEmpty()) {
                    updateUserPositionStats(account, ids)
                }
                ids
            } else {
                emptySet()
            }

        // Only mark past months as permanently imported after games, positions, and position stats are successfully saved
        if (isPastMonth) {
            transactionTemplate.executeWithoutResult {
                if (!importedArchiveRepository.existsByChessAccountAndArchiveUrl(account, archiveUrl)) {
                    importedArchiveRepository.save(
                        ImportedArchive(
                            chessAccount = account,
                            archiveUrl = archiveUrl,
                            yearMonth = yearMonth,
                            gameCount = gamesToSave.size,
                        ),
                    )
                }
            }
        }

        return ImportMonthResult(
            gamesToSave.size,
            skipped,
            filteredOut,
            affectedPositionIds,
        )
    }

    private fun updateUserPositionStats(
        account: ChessAccount,
        affectedPositionIds: Set<UUID>,
    ) {
        if (affectedPositionIds.isEmpty()) return

        val batchSize = 1000
        val positionIdBatches = affectedPositionIds.chunked(batchSize)

        for (batch in positionIdBatches) {
            transactionTemplate.executeWithoutResult {
                val batchSet = batch.toSet()

                val occurrenceCounts =
                    positionOccurrenceRepository.countOccurrencesByAccountAndPositions(
                        account.id,
                        batchSet,
                    )

                val existingStats =
                    userPositionStatsRepository.findByChessAccountIdAndPositionIdIn(
                        account.id,
                        batchSet,
                    )
                val existingStatsMap = existingStats.associateBy { "${it.chessAccount.id}-${it.position.id}-${it.playerColor}" }

                val positions =
                    positionRepository.findAllById(batchSet).associateBy { it.id }

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

                if (statsUpdates.isNotEmpty()) {
                    userPositionStatsRepository.saveAll(statsUpdates)
                }
            }
        }
    }

    private fun fetchArchiveUrls(
        username: String,
        fromDate: String?,
        toDate: String?,
    ): List<String> {
        val allArchives = chessComClient.fetchArchiveUrls(username)

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
