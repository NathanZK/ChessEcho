package com.chessecho.service

import com.chessecho.domain.AsyncJob
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.ImportedArchive
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.domain.TimeControl
import com.chessecho.domain.UserPositionStats
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.ImportedArchiveRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.UserPositionStatsRepository
import com.chessecho.repository.UserPositionWeaknessRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
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
    private val accountOwnershipService: AccountOwnershipService,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val userPositionWeaknessRepository: UserPositionWeaknessRepository,
    private val puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val ACTIVE_STATUSES = listOf("QUEUED", "PROCESSING")
    }

    /**
     * Creates a new QUEUED import job for the specified user and platform.
     * Throws ActiveImportJobException if an active job already exists.
     */
    fun createImportJob(request: ImportGamesRequest): AsyncJob = createImportJob(request, null)

    fun createImportJob(
        request: ImportGamesRequest,
        principal: AuthenticatedPrincipal?,
    ): AsyncJob {
        val account = accountOwnershipService.resolveImportAccount(request, principal)
        return transactionTemplate.execute {
            val lockedAccount =
                chessAccountRepository.findByIdForUpdate(account.id)
                    ?: throw AccountNotFoundException("Import account not found: ${account.id}")
            asyncJobRepository.findByChessAccountIdAndStatusIn(lockedAccount.id, ACTIVE_STATUSES)
                ?.let {
                    throw ActiveImportJobException(
                        "An active import job already exists for account '${lockedAccount.id}' (jobId=${it.id})",
                    )
                }

            val job =
                AsyncJob(
                    chessAccount = lockedAccount,
                    username = lockedAccount.username,
                    platform = lockedAccount.platform,
                    status = "QUEUED",
                    fromDate = request.normalizedFromDate(),
                    toDate = request.normalizedToDate(),
                    timeControlsCsv = request.canonicalTimeControls(),
                    playerColor = request.playerColor!!.name,
                    analysisMultiPv = request.multiPv,
                    configurationState = AsyncJob.CONFIGURATION_READY,
                )
            try {
                asyncJobRepository.saveAndFlush(job)
            } catch (_: org.springframework.dao.DataIntegrityViolationException) {
                throw ActiveImportJobException("An active import job already exists for account '${lockedAccount.id}'")
            }
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
    fun executeImportJob(jobId: UUID) {
        val job = claimForExecution(jobId) ?: return

        val account = job.chessAccount!!
        val request = requestFromJob(job)
        log.info("Starting import job ${job.id} for account ${account.id}")

        try {
            val archiveUrls = fetchArchiveUrls(account.username, job.fromDate, job.toDate)

            val alreadyImportedArchives =
                transactionTemplate.execute {
                    importedArchiveRepository.findByChessAccount(account)
                        .associateBy { it.archiveUrl }
                } ?: emptyMap()

            val currentYearMonth = YearMonth.now(ZoneOffset.UTC).toString()

            var imported = 0
            var skipped = 0
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
                        account.username,
                        importedArchive.gameCount,
                    )
                    skipped += importedArchive.gameCount
                    processed += importedArchive.gameCount
                    transactionTemplate.executeWithoutResult {
                        persistImportProgress(job, imported, skipped, processed)
                    }
                    continue
                }

                val res = importMonth(account, archiveUrl, yearMonth, isPastMonth, request)
                imported += res.imported
                skipped += res.skipped
                processed += res.processed
                allAffectedPositionIds.addAll(res.affectedPositionIds)
                transactionTemplate.executeWithoutResult {
                    persistImportProgress(job, imported, skipped, processed)
                }
            }

            updateUserPositionStats(account, allAffectedPositionIds)

            transactionTemplate.executeWithoutResult {
                job.gamesImported = imported
                job.gamesSkipped = skipped
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
                if (request.multiPv == null) {
                    engineAnalysisOrchestrator.analyzeAffectedPositions(allAffectedPositionIds)
                } else {
                    engineAnalysisOrchestrator.analyzeAffectedPositions(allAffectedPositionIds, request.multiPv)
                }
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

    private fun claimForExecution(jobId: UUID): AsyncJob? =
        transactionTemplate.execute {
            val job =
                asyncJobRepository.findByIdForUpdate(jobId)
                    ?: asyncJobRepository.findAnyByIdForUpdate(jobId)
                    ?: throw IllegalStateException("AsyncJob not found: $jobId")

            if (job.status != "QUEUED") {
                return@execute null
            }
            if (!isExecutable(job)) {
                job.status = "FAILED"
                job.errorMessage = "INVALID_READY_JOB_CONFIGURATION"
                job.updatedAt = Instant.now()
                asyncJobRepository.saveAndFlush(job)
                return@execute null
            }

            job.status = "PROCESSING"
            job.updatedAt = Instant.now()
            asyncJobRepository.saveAndFlush(job)
        }

    /**
     * Compatibility entry point for callers that still pass the original
     * request. The request is ignored after insertion; workers always reload
     * the immutable persisted configuration.
     */
    @Async
    fun executeImportJob(
        jobId: UUID,
        @Suppress("UNUSED_PARAMETER") request: ImportGamesRequest,
    ) {
        executeImportJob(jobId)
    }

    private fun requestFromJob(job: AsyncJob): ImportGamesRequest =
        ImportGamesRequest(
            accountId = job.chessAccount?.id,
            platform = runCatching { Platform.valueOf(job.platform) }.getOrNull(),
            username = job.username,
            timeControls =
                job.timeControlsCsv
                    ?.split(',')
                    ?.mapNotNull { runCatching { TimeControl.valueOf(it) }.getOrNull() }
                    ?: emptyList(),
            playerColor = runCatching { PlayerColor.valueOf(job.playerColor ?: "") }.getOrNull(),
            fromDate = job.fromDate,
            toDate = job.toDate,
            multiPv = job.analysisMultiPv,
        )

    private fun isExecutable(job: AsyncJob): Boolean =
        runCatching {
            job.validateConfiguration()
            val account = job.chessAccount ?: return@runCatching false
            job.configurationState == AsyncJob.CONFIGURATION_READY &&
                account.platform == job.platform &&
                account.username.equals(job.username, ignoreCase = true) &&
                job.playerColor in AsyncJob.ALLOWED_PLAYER_COLORS &&
                !job.timeControlsCsv.isNullOrBlank()
        }.getOrDefault(false)

    private fun persistImportProgress(
        job: AsyncJob,
        imported: Int,
        skipped: Int,
        processed: Int,
    ) {
        job.gamesImported = imported
        job.gamesSkipped = skipped
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

    private data class ImportMonthResult(
        val imported: Int,
        val skipped: Int,
        val processed: Int,
        val affectedPositionIds: Set<UUID>,
    )

    private fun importMonth(
        account: ChessAccount,
        archiveUrl: String,
        yearMonth: String,
        isPastMonth: Boolean,
        request: ImportGamesRequest,
    ): ImportMonthResult {
        val username = request.username.ifBlank { account.username }
        val requestedTimeControls = request.timeControls.toSet()
        val requestedPlayerColor = request.playerColor ?: PlayerColor.BOTH
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

        for (game in games) {
            val rules = game["rules"] as? String
            if (rules != null && !rules.equals("chess", ignoreCase = true)) {
                continue
            }

            val timeClass = game["time_class"] as? String
            val domainTimeControl = TimeControl.fromExternal(timeClass)
            if (domainTimeControl == null || !requestedTimeControls.contains(domainTimeControl)) {
                continue
            }

            val white = (game["white"] as? Map<*, *>)?.get("username") as? String
            val black = (game["black"] as? Map<*, *>)?.get("username") as? String
            if (white == null || black == null) {
                continue
            }

            val isPlayerWhite = white.equals(username, ignoreCase = true)
            val isPlayerBlack = black.equals(username, ignoreCase = true)
            val colorMatch =
                when (requestedPlayerColor) {
                    PlayerColor.WHITE -> isPlayerWhite
                    PlayerColor.BLACK -> isPlayerBlack
                    PlayerColor.BOTH -> isPlayerWhite || isPlayerBlack
                }
            if (!colorMatch) {
                continue
            }

            val pgn = game["pgn"] as? String
            val url = game["url"] as? String
            if (pgn == null || url == null) {
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
                        val result = gameParserService.parseAndSavePositions(savedGames)
                        emitSchedulingEvents(savedGames, result)
                        result
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
            games.size,
            affectedPositionIds,
        )
    }

    private fun emitSchedulingEvents(
        savedGames: List<Game>,
        affectedPositionIds: Set<UUID>,
    ) {
        if (savedGames.isEmpty() || affectedPositionIds.isEmpty()) return
        val savedGameIds = savedGames.map { it.id }.toSet()
        val occurrences =
            positionOccurrenceRepository
                .findByChessAccountIdAndPlayerColorAndPositionIdIn(
                    savedGames.first().chessAccount.id,
                    "WHITE",
                    affectedPositionIds,
                ) +
                positionOccurrenceRepository
                    .findByChessAccountIdAndPlayerColorAndPositionIdIn(
                        savedGames.first().chessAccount.id,
                        "BLACK",
                        affectedPositionIds,
                    )
        occurrences
            .filter { it.game.id in savedGameIds }
            .forEach { occurrence ->
                if (userPositionWeaknessRepository.findByChessAccountIdAndPositionIdAndPlayerColor(
                        occurrence.chessAccount.id,
                        occurrence.position.id,
                        occurrence.playerColor,
                    ) == null
                ) {
                    return@forEach
                }
                val analysis = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(occurrence.position.id)
                val moveEvaluation = analysis?.moveEvaluations?.firstOrNull { it.move == occurrence.movePlayed }
                val loss =
                    moveEvaluation?.evalLossFromBest
                        ?: analysis?.let {
                            val best = it.bestMoveEvalCp
                            val move = moveEvaluation?.evalCp
                            if (best != null && move != null) ((best - move).coerceAtLeast(0) / 100.0) else 0.0
                        }
                        ?: 0.0
                val occurredAt = occurrence.game.playedAt ?: occurrence.game.createdAt
                claimSchedulingEvent(
                    PuzzleSchedulingEvent(
                        chessAccount = occurrence.chessAccount,
                        position = occurrence.position,
                        playerColor = occurrence.playerColor,
                        eventType = SchedulingEventType.GAME_REENCOUNTERED,
                        sourceOccurrence = occurrence,
                        occurredAt = occurredAt,
                    ),
                )
                claimSchedulingEvent(
                    PuzzleSchedulingEvent(
                        chessAccount = occurrence.chessAccount,
                        position = occurrence.position,
                        playerColor = occurrence.playerColor,
                        eventType =
                            if (loss >= WeaknessCalculationService.DEFAULT_MIN_EVAL_LOSS) {
                                SchedulingEventType.GAME_MISTAKE
                            } else {
                                SchedulingEventType.GAME_HANDLED_SUCCESSFULLY
                            },
                        sourceOccurrence = occurrence,
                        occurredAt = occurredAt,
                    ),
                )
            }
    }

    private fun claimSchedulingEvent(event: PuzzleSchedulingEvent) {
        try {
            puzzleSchedulingEventRepository.saveAndFlush(event)
        } catch (ex: DataIntegrityViolationException) {
            val sourceOccurrence = requireNotNull(event.sourceOccurrence)
            puzzleSchedulingEventRepository.findExistingClaim(sourceOccurrence.id, event.eventType) ?: throw ex
        }
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
