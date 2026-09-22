package com.chessecho.service

import com.chessecho.domain.ArchiveDerivedProcessing
import com.chessecho.domain.ArchiveDerivedStatus
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
import com.chessecho.repository.ArchiveDerivedProcessingRepository
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
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

@Service
class GameImportService(
    private val asyncJobRepository: AsyncJobRepository,
    private val chessAccountRepository: ChessAccountRepository,
    private val gameRepository: GameRepository,
    private val importedArchiveRepository: ImportedArchiveRepository,
    private val archiveDerivedProcessingRepository: ArchiveDerivedProcessingRepository,
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
    private val dataSource: DataSource,
    @Value("\${chessecho.import.derived-game-batch-size:1000}")
    private val derivedGameBatchSize: Int = DEFAULT_DERIVED_GAME_BATCH_SIZE,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val ACTIVE_STATUSES = listOf("QUEUED", "PROCESSING")
        private val ASYNC_JOB_LEASE = Duration.ofMinutes(30)

        /**
         * Default derived-processing batch size, overridable with
         * `chessecho.import.derived-game-batch-size`.
         *
         * Repository evidence for the 1,000 element default, verified in this
         * repository rather than assumed:
         *  - `HumanMoveBfsService` chunks hash lookups, missing-hash lookups,
         *    position-id lookups, and distribution writes with `chunked(1000)`.
         *  - `EngineAnalysisOrchestrator` batches affected position ids with
         *    `batchSize = 1000`.
         *  - `updateUserPositionStats` in this class batches stats recomputation
         *    with `batchSize = 1000`.
         *
         * What that evidence does and does not establish: 1,000 is this
         * repository's established element-count ceiling for database-facing
         * batch loops. It is *not* a measured parser throughput figure, and a
         * game is a much heavier unit than an id, so a full 1,000-game batch can
         * still hold a large parser transaction for unusually dense archives.
         *
         * The property that is actually asserted by
         * `DerivedBatchBoundaryIntegrationTest` is the boundary, not the
         * constant: the parser commits once per batch, so a failure in a later
         * batch leaves earlier batches durably committed, and derived lock
         * lifetime is bounded by one batch instead of the whole archive. This
         * reduces lock lifetime relative to the archive-wide transaction; it does
         * not by itself eliminate deadlocks.
         */
        internal const val DEFAULT_DERIVED_GAME_BATCH_SIZE = 1000
        private val DERIVED_PROCESSING_LEASE = Duration.ofMinutes(30)
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
                    if (it.status == "PROCESSING" && isAsyncJobLeaseStale(it, Instant.now())) {
                        it.status = "FAILED"
                        it.errorMessage = "STALE_PROCESSING_JOB_RECLAIMED"
                        it.workerToken = null
                        it.leaseExpiresAt = null
                        it.updatedAt = Instant.now()
                        asyncJobRepository.saveAndFlush(it)
                    } else {
                        throw ActiveImportJobException(
                            "An active import job already exists for account '${lockedAccount.id}' (jobId=${it.id})",
                        )
                    }
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
        val claim = claimForExecution(jobId) ?: return
        val job = claim.job

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
                    allAffectedPositionIds.addAll(processDerivedArchive(account, importedArchive))
                    transactionTemplate.executeWithoutResult {
                        persistImportProgress(claim, imported, skipped, processed)
                    }
                    continue
                }

                val res = importMonth(account, archiveUrl, yearMonth, isPastMonth, request)
                imported += res.imported
                skipped += res.skipped
                processed += res.processed
                allAffectedPositionIds.addAll(res.affectedPositionIds)
                transactionTemplate.executeWithoutResult {
                    persistImportProgress(claim, imported, skipped, processed)
                }
            }

            transactionTemplate.executeWithoutResult {
                val current = claimLockedJob(claim) ?: return@executeWithoutResult
                current.gamesImported = imported
                current.gamesSkipped = skipped
                current.gamesProcessed = processed
                current.analysisStatus = "ANALYZING"
                updateJobStatus(current, "COMPLETED")
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
                    val current = claimLockedJob(claim) ?: return@executeWithoutResult
                    current.analysisStatus = "COMPLETED"
                    current.workerToken = null
                    current.leaseExpiresAt = null
                    current.updatedAt = Instant.now()
                    asyncJobRepository.save(current)
                }
            } catch (analysisEx: Exception) {
                log.error("Engine analysis failed for job ${job.id}", analysisEx)
                transactionTemplate.executeWithoutResult {
                    val current = claimLockedJob(claim) ?: return@executeWithoutResult
                    current.analysisStatus = "FAILED"
                    current.workerToken = null
                    current.leaseExpiresAt = null
                    current.updatedAt = Instant.now()
                    asyncJobRepository.save(current)
                }
            }
        } catch (ex: Exception) {
            log.error("Import job ${job.id} failed", ex)
            transactionTemplate.executeWithoutResult {
                val current = claimLockedJob(claim) ?: return@executeWithoutResult
                current.errorMessage = ex.message
                current.workerToken = null
                current.leaseExpiresAt = null
                updateJobStatus(current, "FAILED")
            }
        }
    }

    /**
     * Crash recovery for import jobs.
     *
     * `claimForExecution` can already adopt a PROCESSING job whose worker lease
     * has lapsed, but nothing reached that path in the normal lifecycle: the
     * creation flow refuses to queue a second job while one is active, so a job
     * abandoned by a crashed worker previously stayed PROCESSING until a user
     * happened to request a new import. This sweep gives that reclaim an actual
     * caller. It follows the existing scheduled-maintenance convention in
     * `IdentitySessionService`.
     *
     * Duplicate live workers are impossible because every candidate is re-locked
     * with `SELECT ... FOR UPDATE` inside `claimForExecution`, which adopts the
     * job only while it is still PROCESSING with a lapsed lease and immediately
     * installs a fresh worker token and lease. A job whose live worker renewed
     * its lease in the meantime is simply skipped, and every subsequent progress
     * or finalization write is gated on that worker token.
     *
     * Recovery runs on the sweep thread so a resumed job is observable to callers
     * without depending on async dispatch.
     *
     * @return the ids of jobs that were resumed by this sweep.
     */
    @Scheduled(
        fixedDelayString = "\${chessecho.import.stale-job-recovery-interval-ms:300000}",
        initialDelayString = "\${chessecho.import.stale-job-recovery-initial-delay-ms:300000}",
    )
    fun sweepStaleImportJobs() {
        recoverStaleImportJobs(Instant.now())
    }

    fun recoverStaleImportJobs(): List<UUID> = recoverStaleImportJobs(Instant.now())

    fun recoverStaleImportJobs(now: Instant): List<UUID> {
        val staleJobIds =
            transactionTemplate.execute {
                asyncJobRepository.findStaleProcessingJobIds(now)
            } ?: emptyList()
        if (staleJobIds.isEmpty()) return emptyList()

        log.info("Recovering {} import job(s) abandoned by a lapsed worker lease", staleJobIds.size)
        return staleJobIds.filter { jobId ->
            runCatching { executeImportJob(jobId) }
                .onFailure { log.error("Stale import job {} could not be recovered", jobId, it) }
                .isSuccess
        }
    }

    private data class AsyncJobClaim(
        val job: AsyncJob,
        val workerToken: UUID,
    )

    private fun claimForExecution(jobId: UUID): AsyncJobClaim? =
        transactionTemplate.execute {
            val now = Instant.now()
            val job =
                asyncJobRepository.findByIdForUpdate(jobId)
                    ?: asyncJobRepository.findAnyByIdForUpdate(jobId)
                    ?: throw IllegalStateException("AsyncJob not found: $jobId")

            val reclaimingStale = job.status == "PROCESSING" && isAsyncJobLeaseStale(job, now)
            if (job.status != "QUEUED" && !reclaimingStale) {
                return@execute null
            }
            if (!isExecutable(job)) {
                job.status = "FAILED"
                job.errorMessage = "INVALID_READY_JOB_CONFIGURATION"
                job.workerToken = null
                job.leaseExpiresAt = null
                job.updatedAt = Instant.now()
                asyncJobRepository.saveAndFlush(job)
                return@execute null
            }

            val workerToken = UUID.randomUUID()
            job.status = "PROCESSING"
            job.workerToken = workerToken
            job.startedAt = now
            job.leaseExpiresAt = now.plus(ASYNC_JOB_LEASE)
            job.updatedAt = now
            asyncJobRepository.saveAndFlush(job)
            AsyncJobClaim(job, workerToken)
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
        claim: AsyncJobClaim,
        imported: Int,
        skipped: Int,
        processed: Int,
    ) {
        val job = claimLockedJob(claim) ?: return
        job.gamesImported = imported
        job.gamesSkipped = skipped
        job.gamesProcessed = processed
        renewJobLease(job)
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

    /**
     * Re-reads the job under a row lock and proves this worker still owns it
     * before any progress or finalization write. Ownership is the worker token
     * installed at claim time: if recovery handed the job to another worker the
     * token no longer matches and this worker's write is dropped instead of
     * overwriting the live worker's state. Still owning the row also means the
     * lease can be safely extended, which keeps recovery from adopting a job
     * that is demonstrably still progressing.
     */
    private fun claimLockedJob(claim: AsyncJobClaim): AsyncJob? {
        val job = asyncJobRepository.findAnyByIdForUpdate(claim.job.id) ?: return null
        if (job.workerToken != claim.workerToken || job.status !in setOf("PROCESSING", "COMPLETED")) {
            log.warn("Skipping stale async job write for job {}", claim.job.id)
            return null
        }
        if (job.status == "PROCESSING") {
            renewJobLease(job)
        }
        return job
    }

    private fun renewJobLease(job: AsyncJob) {
        val now = Instant.now()
        job.leaseExpiresAt = now.plus(ASYNC_JOB_LEASE)
        job.updatedAt = now
    }

    private fun isAsyncJobLeaseStale(
        job: AsyncJob,
        now: Instant,
    ): Boolean = job.leaseExpiresAt?.isAfter(now) != true

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

        // This is deliberately the only archive-sized transaction: raw games and
        // the raw commitment are durable before any parser or downstream work.
        val savedGamesAndArchive =
            transactionTemplate.execute {
                val archive =
                    if (isPastMonth) {
                        importedArchiveRepository.findByChessAccountAndArchiveUrl(account, archiveUrl)
                            ?: importedArchiveRepository.save(
                                ImportedArchive(
                                    chessAccount = account,
                                    archiveUrl = archiveUrl,
                                    yearMonth = yearMonth,
                                    gameCount = gamesToSave.size,
                                ),
                            )
                    } else {
                        null
                    }
                gamesToSave.forEach { it.importedArchive = archive }
                val savedGames = gameRepository.saveAll(gamesToSave)
                Pair(savedGames, archive)
            } ?: Pair(emptyList(), null)

        val affectedPositionIds =
            processDerivedGames(account, savedGamesAndArchive.first, savedGamesAndArchive.second)

        return ImportMonthResult(
            gamesToSave.size,
            skipped,
            games.size,
            affectedPositionIds,
        )
    }

    private fun processDerivedArchive(
        account: ChessAccount,
        archive: ImportedArchive,
    ): Set<UUID> {
        return processDerivedGames(account, emptyList(), archive)
    }

    private fun processDerivedGames(
        account: ChessAccount,
        savedGames: List<Game>,
        archive: ImportedArchive?,
    ): Set<UUID> {
        val claim = archive?.let(::claimDerivedProcessing) ?: if (archive == null) null else return emptySet()

        val games =
            if (archive != null) {
                gameRepository.findAllByImportedArchiveIdOrderByPlayedAtAsc(archive.id)
            } else if (savedGames.isNotEmpty()) {
                savedGames
            } else {
                emptyList()
            }
        val affected = mutableSetOf<UUID>()
        try {
            // One parser transaction per batch: see DEFAULT_DERIVED_GAME_BATCH_SIZE
            // for the batch-size evidence and for what this boundary does and
            // does not guarantee.
            games.chunked(derivedGameBatchSize.coerceAtLeast(1)).forEach { batch ->
                val ids = gameParserService.parseAndSavePositions(batch)
                emitSchedulingEvents(batch, ids)
                updateUserPositionStats(account, ids)
                affected.addAll(ids)
                claim?.let(::renewDerivedProcessingLease)
            }
            claim?.let(::completeDerivedProcessing)
            return affected
        } catch (ex: Exception) {
            claim?.let { failDerivedProcessing(it, ex) }
            throw ex
        }
    }

    private data class DerivedProcessingClaim(
        val processingId: UUID,
        val workerToken: UUID,
    )

    private fun claimDerivedProcessing(archive: ImportedArchive): DerivedProcessingClaim? {
        ensureDerivedProcessingRecord(archive)
        return transactionTemplate.execute {
            val now = Instant.now()
            val record =
                archiveDerivedProcessingRepository.findByImportedArchiveIdForUpdate(archive.id)
                    ?: throw IllegalStateException(
                        "Derived processing record missing for archive ${archive.id}",
                    )

            when (record.status) {
                ArchiveDerivedStatus.COMPLETED -> return@execute null
                ArchiveDerivedStatus.PROCESSING ->
                    if (record.leaseExpiresAt?.isAfter(now) == true) {
                        return@execute null
                    }
                ArchiveDerivedStatus.PENDING, ArchiveDerivedStatus.FAILED -> Unit
            }

            val workerToken = UUID.randomUUID()
            record.status = ArchiveDerivedStatus.PROCESSING
            record.workerToken = workerToken
            record.attemptCount += 1
            record.lastError = null
            record.startedAt = now
            record.leaseExpiresAt = now.plus(DERIVED_PROCESSING_LEASE)
            record.completedAt = null
            record.updatedAt = now
            archiveDerivedProcessingRepository.saveAndFlush(record)
            DerivedProcessingClaim(record.id, workerToken)
        }
    }

    /**
     * Makes the very first claim for an archive race-safe.
     *
     * The uniqueness of `archive_derived_processing.imported_archive_id` means two
     * workers reaching an archive for the first time necessarily contend. Retrying
     * the same blind insert would simply reproduce the same unique violation, so
     * the loser must converge instead of failing:
     *  - on PostgreSQL the row is created with `INSERT ... ON CONFLICT DO NOTHING`,
     *    so the loser's statement is a no-op rather than an error;
     *  - elsewhere the violation is caught and the committed winner is reloaded.
     *
     * Either way this runs in its own short transaction, so a violation never
     * poisons the claiming transaction that immediately follows, and the caller
     * can then take the row under `SELECT ... FOR UPDATE`.
     */
    private fun ensureDerivedProcessingRecord(archive: ImportedArchive) {
        if (archiveDerivedProcessingRepository.findByImportedArchive(archive) != null) return
        if (supportsConflictSafeInsert()) {
            transactionTemplate.executeWithoutResult {
                archiveDerivedProcessingRepository.insertPendingIfAbsent(
                    UUID.randomUUID(),
                    archive.id,
                    Instant.now(),
                )
            }
        } else {
            try {
                // The violation is deliberately allowed to leave the transaction
                // before it is caught: a persistence context that failed to flush
                // is already marked rollback-only, so swallowing the failure inside
                // the transaction would only turn it into a rollback error here.
                transactionTemplate.executeWithoutResult {
                    archiveDerivedProcessingRepository.saveAndFlush(
                        ArchiveDerivedProcessing(importedArchive = archive),
                    )
                }
            } catch (ex: DataIntegrityViolationException) {
                log.debug("Lost first derived-processing claim for archive {}", archive.id, ex)
            } catch (ex: UnexpectedRollbackException) {
                log.debug("First derived-processing claim for archive {} was rolled back", archive.id, ex)
            }
        }
        if (archiveDerivedProcessingRepository.findByImportedArchive(archive) == null) {
            // The winner's row must be visible once its transaction committed; if it
            // is not, surface it instead of silently skipping derived processing.
            throw IllegalStateException("Derived processing record missing for archive ${archive.id}")
        }
    }

    private fun supportsConflictSafeInsert(): Boolean {
        val connection = DataSourceUtils.getConnection(dataSource)
        val supported =
            try {
                connection.metaData.databaseProductName == "PostgreSQL"
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource)
            }
        return supported
    }

    private fun renewDerivedProcessingLease(claim: DerivedProcessingClaim) {
        transactionTemplate.executeWithoutResult {
            val record = archiveDerivedProcessingRepository.findByIdForUpdate(claim.processingId) ?: return@executeWithoutResult
            if (record.status == ArchiveDerivedStatus.PROCESSING && record.workerToken == claim.workerToken) {
                val now = Instant.now()
                record.leaseExpiresAt = now.plus(DERIVED_PROCESSING_LEASE)
                record.updatedAt = now
                archiveDerivedProcessingRepository.save(record)
            }
        }
    }

    private fun completeDerivedProcessing(claim: DerivedProcessingClaim) {
        transactionTemplate.executeWithoutResult {
            val record = archiveDerivedProcessingRepository.findByIdForUpdate(claim.processingId) ?: return@executeWithoutResult
            if (record.status == ArchiveDerivedStatus.PROCESSING && record.workerToken == claim.workerToken) {
                val now = Instant.now()
                record.status = ArchiveDerivedStatus.COMPLETED
                record.completedAt = now
                record.leaseExpiresAt = null
                record.updatedAt = now
                archiveDerivedProcessingRepository.save(record)
            }
        }
    }

    private fun failDerivedProcessing(
        claim: DerivedProcessingClaim,
        ex: Exception,
    ) {
        transactionTemplate.executeWithoutResult {
            val record = archiveDerivedProcessingRepository.findByIdForUpdate(claim.processingId) ?: return@executeWithoutResult
            if (record.status == ArchiveDerivedStatus.PROCESSING && record.workerToken == claim.workerToken) {
                record.status = ArchiveDerivedStatus.FAILED
                record.lastError = ex.message ?: ex.javaClass.simpleName
                record.leaseExpiresAt = null
                record.updatedAt = Instant.now()
                archiveDerivedProcessingRepository.save(record)
            }
        }
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
