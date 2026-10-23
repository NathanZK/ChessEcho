package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.AsyncJob
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.ImportedArchive
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.TimeControl
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AppUserRepository
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
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
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
    private val appUserRepository: AppUserRepository,
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
    private val derivedGameBatchSize: Int = DerivedProcessingCoordinator.DEFAULT_DERIVED_GAME_BATCH_SIZE,
    userPositionStatsBatchService: UserPositionStatsBatchService? = null,
    puzzleSchedulingEventDispatcher: PuzzleSchedulingEventDispatcher? = null,
    derivedProcessingCoordinator: DerivedProcessingCoordinator? = null,
    monthImportCoordinator: MonthImportCoordinator? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val userPositionStatsBatchService =
        userPositionStatsBatchService ?: UserPositionStatsBatchService(
            positionOccurrenceRepository = positionOccurrenceRepository,
            userPositionStatsRepository = userPositionStatsRepository,
            positionRepository = positionRepository,
            transactionTemplate = transactionTemplate,
        )

    private val puzzleSchedulingEventDispatcher =
        puzzleSchedulingEventDispatcher ?: PuzzleSchedulingEventDispatcher(
            positionOccurrenceRepository = positionOccurrenceRepository,
            userPositionWeaknessRepository = userPositionWeaknessRepository,
            engineAnalysisRepository = engineAnalysisRepository,
            puzzleSchedulingEventRepository = puzzleSchedulingEventRepository,
        )

    private val derivedProcessingCoordinator =
        derivedProcessingCoordinator ?: DerivedProcessingCoordinator(
            archiveDerivedProcessingRepository = archiveDerivedProcessingRepository,
            gameRepository = gameRepository,
            gameParserService = gameParserService,
            puzzleSchedulingEventDispatcher = this.puzzleSchedulingEventDispatcher,
            userPositionStatsBatchService = this.userPositionStatsBatchService,
            transactionTemplate = transactionTemplate,
            dataSource = dataSource,
            derivedGameBatchSize = derivedGameBatchSize,
        )

    private val monthImportCoordinator =
        monthImportCoordinator ?: MonthImportCoordinator(
            chessComClient = chessComClient,
            gameRepository = gameRepository,
            importedArchiveRepository = importedArchiveRepository,
            derivedProcessingCoordinator = this.derivedProcessingCoordinator,
            transactionTemplate = transactionTemplate,
        )

    companion object {
        private val ACTIVE_STATUSES = listOf("QUEUED", "PROCESSING")
        private val ASYNC_JOB_LEASE = Duration.ofMinutes(30)
        internal const val DEFAULT_DERIVED_GAME_BATCH_SIZE = DerivedProcessingCoordinator.DEFAULT_DERIVED_GAME_BATCH_SIZE
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
        return transactionTemplate.execute {
            val account = accountOwnershipService.resolveImportAccount(request, principal)
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
                    appUser = principal?.let { appUserRepository.getReferenceById(it.appUserId) },
                    username = lockedAccount.username,
                    platform = lockedAccount.platform,
                    status = "QUEUED",
                    fromDate = request.normalizedFromDate(),
                    toDate = request.normalizedToDate(),
                    timeControlsCsv = request.canonicalTimeControls(),
                    playerColor = request.playerColor!!.name,
                    analysisMultiPv = request.multiPv,
                    maxEligibleGames = request.maxEligibleGames,
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
            val affectedPositionIds = importJobArchives(claim, job, account, request)
            finalizeEngineAnalysis(claim, job, request, affectedPositionIds)
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

    private fun importJobArchives(
        claim: AsyncJobClaim,
        job: AsyncJob,
        account: ChessAccount,
        request: ImportGamesRequest,
    ): Set<UUID> {
        val archiveUrls = fetchArchiveUrls(account.username, job.fromDate, job.toDate)
        val alreadyImportedArchives = fetchImportedArchives(account)
        val currentYearMonth = YearMonth.now(ZoneOffset.UTC).toString()

        var imported = 0
        var skipped = 0
        var processed = 0
        var eligibleSelected = job.eligibleGamesSelected
        val maxEligible = job.maxEligibleGames
        val allAffectedPositionIds = mutableSetOf<UUID>()

        for (archiveUrl in archiveUrls) {
            if (maxEligible != null && eligibleSelected >= maxEligible) break

            val yearMonth = parseArchiveYearMonth(archiveUrl)
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
                allAffectedPositionIds.addAll(derivedProcessingCoordinator.processDerivedArchive(account, job.appUser, importedArchive))
                transactionTemplate.executeWithoutResult {
                    persistImportProgress(claim, imported, skipped, processed, eligibleSelected)
                }
                continue
            }

            val remainingCap = maxEligible?.let { (it - eligibleSelected).coerceAtLeast(0) }
            val res = monthImportCoordinator.importMonth(account, job.appUser, archiveUrl, yearMonth, isPastMonth, request, remainingCap)
            imported += res.imported
            skipped += res.skipped
            processed += res.processed
            eligibleSelected += res.imported
            allAffectedPositionIds.addAll(res.affectedPositionIds)
            transactionTemplate.executeWithoutResult {
                persistImportProgress(claim, imported, skipped, processed, eligibleSelected)
            }
        }

        completeJobImport(claim, imported, skipped, processed, eligibleSelected)
        log.info("Import job {} completed: {} imported, {} already imported / skipped", job.id, imported, skipped)
        return allAffectedPositionIds
    }

    private fun parseArchiveYearMonth(archiveUrl: String): String {
        val parts = archiveUrl.split("/")
        return if (parts.size >= 2) "${parts[parts.size - 2]}-${parts[parts.size - 1]}" else ""
    }

    private fun fetchImportedArchives(account: ChessAccount): Map<String, ImportedArchive> =
        transactionTemplate.execute {
            importedArchiveRepository.findByChessAccount(account).associateBy { it.archiveUrl }
        } ?: emptyMap()

    private fun completeJobImport(
        claim: AsyncJobClaim,
        imported: Int,
        skipped: Int,
        processed: Int,
        eligibleSelected: Int,
    ) {
        transactionTemplate.executeWithoutResult {
            val current = claimLockedJob(claim) ?: return@executeWithoutResult
            current.gamesImported = imported
            current.gamesSkipped = skipped
            current.gamesProcessed = processed
            current.eligibleGamesSelected = eligibleSelected
            current.analysisStatus = "ANALYZING"
            updateJobStatus(current, "COMPLETED")
        }
    }

    private fun finalizeEngineAnalysis(
        claim: AsyncJobClaim,
        job: AsyncJob,
        request: ImportGamesRequest,
        affectedPositionIds: Set<UUID>,
    ) {
        try {
            val outcome =
                if (request.multiPv == null) {
                    engineAnalysisOrchestrator.analyzeAffectedPositions(affectedPositionIds)
                } else {
                    engineAnalysisOrchestrator.analyzeAffectedPositions(affectedPositionIds, request.multiPv)
                }
            transactionTemplate.executeWithoutResult {
                val current = claimLockedJob(claim) ?: return@executeWithoutResult
                current.failedPositionIds = outcome.failedPositionIds
                current.analysisStatus = if (outcome.failedPositionIds.isEmpty()) "COMPLETED" else "FAILED"
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
    }

    /**
     * Crash recovery for import jobs.
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
            maxEligibleGames = job.maxEligibleGames,
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

    private fun persistImportProgress(
        claim: AsyncJobClaim,
        imported: Int,
        skipped: Int,
        processed: Int,
        eligibleSelected: Int,
    ) {
        val job = claimLockedJob(claim) ?: return
        job.gamesImported = imported
        job.gamesSkipped = skipped
        job.gamesProcessed = processed
        job.eligibleGamesSelected = eligibleSelected
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

    private fun importMonth(
        account: ChessAccount,
        initiatingUser: AppUser?,
        archiveUrl: String,
        yearMonth: String,
        isPastMonth: Boolean,
        request: ImportGamesRequest,
        remainingCap: Int? = null,
    ): ImportMonthResult =
        monthImportCoordinator.importMonth(
            account = account,
            initiatingUser = initiatingUser,
            archiveUrl = archiveUrl,
            yearMonth = yearMonth,
            isPastMonth = isPastMonth,
            request = request,
            remainingCap = remainingCap,
        )

    private fun processDerivedArchive(
        account: ChessAccount,
        initiatingUser: AppUser?,
        archive: ImportedArchive,
    ): Set<UUID> = derivedProcessingCoordinator.processDerivedArchive(account, initiatingUser, archive)

    private fun processDerivedGames(
        account: ChessAccount,
        initiatingUser: AppUser?,
        savedGames: List<Game>,
        archive: ImportedArchive?,
    ): Set<UUID> = derivedProcessingCoordinator.processDerivedGames(account, initiatingUser, savedGames, archive)

    private fun ensureDerivedProcessingRecord(archive: ImportedArchive) {
        derivedProcessingCoordinator.ensureDerivedProcessingRecord(archive)
    }

    private fun emitSchedulingEvents(
        savedGames: List<Game>,
        initiatingUser: AppUser?,
        affectedPositionIds: Set<UUID>,
    ) {
        puzzleSchedulingEventDispatcher.emitSchedulingEvents(savedGames, initiatingUser, affectedPositionIds)
    }

    private fun updateUserPositionStats(
        account: ChessAccount,
        affectedPositionIds: Set<UUID>,
    ) {
        userPositionStatsBatchService.updateUserPositionStats(account, affectedPositionIds)
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
