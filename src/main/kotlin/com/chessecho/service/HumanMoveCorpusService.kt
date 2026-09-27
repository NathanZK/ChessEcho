package com.chessecho.service

import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.domain.RatingBand
import com.chessecho.dto.HumanMoveCorpusRunRequest
import com.chessecho.dto.HumanMoveCorpusRunResponse
import com.chessecho.repository.HumanMoveCorpusRunRepository
import com.chessecho.service.HumanMoveCorpusGameWriter.Companion.toResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Issue #423 reference-corpus runs for Issue #75 E6. Traversal and
 * qualification are shared with legacy `/bfs` via [HumanMoveBfsTraversal];
 * membership is run-scoped and never touches the legacy global seen-game
 * claim or `human_move_distribution`.
 */
@Service
class HumanMoveCorpusService(
    private val chessComClient: ChessComClient,
    private val gameWriter: HumanMoveCorpusGameWriter,
    private val runRepository: HumanMoveCorpusRunRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runCorpus(request: HumanMoveCorpusRunRequest): HumanMoveCorpusRunResponse {
        require(request.maxPlayers != null || request.maxDepth != null || request.maxQualifyingGames != null) {
            "At least one BFS bound must be supplied"
        }
        val targetBand =
            RatingBand.fromValue(request.ratingBand)
                ?: throw IllegalArgumentException("Invalid rating band: ${request.ratingBand}")
        require(request.sourceRevision.isNotBlank()) { "sourceRevision must not be blank" }

        val runId = gameWriter.createRun(request)
        log.info("Started corpus run $runId for band ${targetBand.value} (sourceRevision=${request.sourceRevision})")

        val traversal = HumanMoveBfsTraversal(chessComClient, log)
        val fetchFailures = mutableListOf<String>()
        var rejectedGames = 0

        val sink =
            object : HumanMoveBfsSink {
                // Run-scoped membership: the traversal's in-run dedup is the only skip.
                override fun alreadyContributed(archiveGameUrls: List<String>): Set<String> = emptySet()

                override fun accept(game: HumanMoveBfsQualifyingGame): Boolean {
                    val observations = mutableMapOf<Pair<String, String>, Int>()
                    val fenByHash = mutableMapOf<String, String>()
                    val occurrences = mutableListOf<HumanMoveCorpusOccurrence>()
                    val parse =
                        traversal.processGamePgn(
                            pgn = game.pgn,
                            isWhiteInBand = !game.isPlayerWhite,
                            isBlackInBand = game.isPlayerWhite,
                            observations = observations,
                            fenByHash = fenByHash,
                            occurrences = occurrences,
                        )
                    if (parse != HumanMoveBfsPgnOutcome.PARSED || observations.isEmpty() || occurrences.isEmpty()) {
                        rejectedGames++
                        val reason = if (parse == HumanMoveBfsPgnOutcome.PARSED) "ZERO_OBSERVATIONS" else parse.name
                        log.info("Corpus run $runId rejected game ${game.url}: $reason")
                        return false
                    }
                    val occurrenceAggregate =
                        occurrences.groupingBy { it.positionHash to it.movePlayed }.eachCount()
                    check(occurrenceAggregate == observations) {
                        "Occurrence and aggregate contributions diverged while parsing ${game.url}"
                    }
                    val candidate =
                        HumanMoveCorpusCandidate(
                            providerGameId = game.url,
                            traversedPlayer = game.traversedPlayer,
                            opponent = game.opponent,
                            opponentSide = if (game.isPlayerWhite) HumanMoveCorpusSide.BLACK else HumanMoveCorpusSide.WHITE,
                            opponentRating = game.opponentRating,
                            rules = game.rules,
                            timeClass = game.timeClass.orEmpty(),
                            bfsDepth = game.depth,
                            pgn = game.pgn,
                            observations =
                                occurrenceAggregate
                                    .map { (key, count) ->
                                        HumanMoveCorpusObservedMove(
                                            key.first,
                                            fenByHash.getValue(key.first),
                                            key.second,
                                            count,
                                        )
                                    }.sortedWith(compareBy({ it.positionHash }, { it.movePlayed })),
                            occurrences = occurrences.sortedBy { it.preMovePly },
                        )
                    commitWithRetry(runId, candidate)
                    return true
                }

                override fun onArchiveListFailure(
                    player: String,
                    error: Exception,
                ) {
                    fetchFailures += "archives for player $player: ${error.message}"
                }

                override fun onMonthlyGamesFailure(
                    archiveUrl: String,
                    error: Exception,
                ) {
                    fetchFailures += "games from $archiveUrl: ${error.message}"
                }
            }

        val outcome =
            try {
                val result =
                    traversal.traverse(
                        HumanMoveBfsBounds(
                            targetBand = targetBand,
                            seedPlayers = request.seedPlayers,
                            excludedPlayers = request.excludedPlayers,
                            maxQualifyingGames = request.maxQualifyingGames,
                            maxGamesPerPlayer = request.maxGamesPerPlayer,
                            maxPlayers = request.maxPlayers,
                            maxDepth = request.maxDepth,
                        ),
                        sink,
                    )
                HumanMoveCorpusRunOutcome(
                    status = if (fetchFailures.isEmpty()) HumanMoveCorpusRunStatus.COMPLETED else HumanMoveCorpusRunStatus.INCOMPLETE,
                    stopReason = result.stopReason,
                    rejectedGameCount = rejectedGames,
                    archiveFetchFailureCount = fetchFailures.size,
                    failureDetails = fetchFailureDetails(fetchFailures),
                )
            } catch (e: CommitFailure) {
                log.error("Corpus run $runId failed: ${e.message}", e.cause)
                failedOutcome("COMMIT_FAILED", e.message, rejectedGames, fetchFailures)
            } catch (e: Exception) {
                log.error("Corpus run $runId failed unexpectedly", e)
                failedOutcome("UNEXPECTED_ERROR", "${e.javaClass.simpleName}: ${e.message}", rejectedGames, fetchFailures)
            }

        log.info(
            "Corpus run $runId finished: status=${outcome.status} stopReason=${outcome.stopReason} " +
                "rejected=${outcome.rejectedGameCount} fetchFailures=${outcome.archiveFetchFailureCount}",
        )
        return gameWriter.finishRun(runId, outcome)
    }

    fun listRuns(): List<HumanMoveCorpusRunResponse> = runRepository.findAllByOrderByCreatedAtDesc().map { it.toResponse() }

    fun getRun(runId: UUID): HumanMoveCorpusRunResponse =
        runRepository.findById(runId).orElseThrow { NoSuchElementException("Corpus run $runId not found") }.toResponse()

    /** Bounded same-candidate retry; each attempt is a fresh writer transaction. */
    private fun commitWithRetry(
        runId: UUID,
        candidate: HumanMoveCorpusCandidate,
    ) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val result = gameWriter.commitGame(runId, candidate)
                if (result.outcome == HumanMoveCorpusCommitOutcome.ALREADY_COMMITTED) {
                    log.info("Game ${candidate.providerGameId} already committed at ordinal ${result.qualifyingOrdinal}")
                }
                return
            } catch (e: TransientDataAccessException) {
                if (attempt >= MAX_COMMIT_ATTEMPTS) throw CommitFailure(candidate.providerGameId, attempt, e)
                log.warn("Transient failure committing ${candidate.providerGameId} (attempt $attempt): ${e.message}")
            } catch (e: Exception) {
                throw CommitFailure(candidate.providerGameId, attempt, e)
            }
        }
    }

    private class CommitFailure(
        gameUrl: String,
        attempts: Int,
        cause: Exception,
    ) : RuntimeException(
            "Commit of game $gameUrl failed after $attempts attempt(s): ${cause.javaClass.simpleName}: ${cause.message}",
            cause,
        )

    private fun failedOutcome(
        stopReason: String,
        details: String?,
        rejectedGames: Int,
        fetchFailures: List<String>,
    ) = HumanMoveCorpusRunOutcome(
        status = HumanMoveCorpusRunStatus.FAILED,
        stopReason = stopReason,
        rejectedGameCount = rejectedGames,
        archiveFetchFailureCount = fetchFailures.size,
        failureDetails = listOfNotNull(details, fetchFailureDetails(fetchFailures)).joinToString("\n"),
    )

    private fun fetchFailureDetails(failures: List<String>): String? {
        if (failures.isEmpty()) return null
        val shown = failures.take(MAX_REPORTED_FETCH_FAILURES).joinToString("; ")
        val more = failures.size - MAX_REPORTED_FETCH_FAILURES
        return "${failures.size} fetch failure(s): $shown" + if (more > 0) "; and $more more" else ""
    }

    companion object {
        const val ALGORITHM_VERSION = "human-move-corpus-v1"
        const val MAX_COMMIT_ATTEMPTS = 3
        private const val MAX_REPORTED_FETCH_FAILURES = 100
    }
}
