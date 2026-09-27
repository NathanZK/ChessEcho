package com.chessecho.service

import com.chessecho.domain.HumanMoveDistribution
import com.chessecho.domain.Position
import com.chessecho.domain.RatingBand
import com.chessecho.domain.TimeControl
import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.dto.HumanMoveBfsResponse
import com.chessecho.dto.HumanMovePopulationDiscoveryRequest
import com.chessecho.dto.HumanMovePopulationDiscoveryResponse
import com.chessecho.repository.HumanMoveBfsSeenGameClaimer
import com.chessecho.repository.HumanMoveBfsSeenGameRepository
import com.chessecho.repository.HumanMoveDistributionRepository
import com.chessecho.repository.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class HumanMoveBfsService(
    private val chessComClient: ChessComClient,
    private val positionRepository: PositionRepository,
    private val humanMoveDistributionRepository: HumanMoveDistributionRepository,
    private val humanMoveBfsSeenGameRepository: HumanMoveBfsSeenGameRepository,
    private val humanMoveBfsSeenGameClaimer: HumanMoveBfsSeenGameClaimer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runBfs(request: HumanMoveBfsRequest): HumanMoveBfsResponse {
        require(request.maxPlayers != null || request.maxDepth != null || request.maxQualifyingGames != null) {
            "At least one BFS bound must be supplied"
        }
        val targetBand =
            RatingBand.fromValue(request.ratingBand)
                ?: throw IllegalArgumentException("Invalid rating band: ${request.ratingBand}")

        log.info("Starting human move distribution BFS for band ${targetBand.value}")
        log.info("Seeds: ${request.seedPlayers}")
        log.info(
            "Config: maxQualifyingGames=${request.maxQualifyingGames}, " +
                "batchSize=${request.batchSize}, " +
                "maxDepth=${request.maxDepth}",
        )

        var totalQualifyingGames = 0

        // Cumulative totals across all flushed batches
        var cumulativeUniquePositions = 0
        var cumulativeTotalObservations = 0
        var cumulativeDistributionRowsPersisted = 0
        var batchNumber = 0

        // Current batch aggregation — replaced with a fresh instance on each flush
        var batchObservations = mutableMapOf<Pair<String, String>, Int>()
        var batchFenByHash = mutableMapOf<String, String>()
        // URLs of games whose observations are aggregated into the current batch.
        // Persisted atomically inside persistObservations()'s transaction via a
        // plain INSERT — the game_url PRIMARY KEY uniqueness constraint on
        // human_move_bfs_seen_game provides the atomic-claim semantics. Because
        // the claim and the human_move_distribution writes share one
        // @Transactional boundary, they commit or roll back together.
        var batchGameUrls = mutableSetOf<String>()
        var batchQualifyingGames = 0

        /**
         * Flush the current batch: persist every observed (position, move) pair
         * with accumulate-on-conflict semantics against the DB, log, then discard
         * the aggregation maps so the old objects become eligible for GC.
         *
         * No minObservations filter is applied here — thresholding is deferred to
         * an explicit finalization operation so that observations arriving in
         * later batches (or in later BFS invocations) can contribute to the same
         * position's global total.
         */
        fun flushBatch() {
            if (batchObservations.isEmpty()) return

            batchNumber++

            val batchUniquePositions = batchObservations.keys.map { it.first }.toSet().size
            val batchTotalObservations = batchObservations.values.sum()

            // Observation-count distribution within the batch (log-only diagnostics)
            val obsByPosition = mutableMapOf<String, Int>()
            for ((key, count) in batchObservations) {
                val hash = key.first
                obsByPosition[hash] = (obsByPosition[hash] ?: 0) + count
            }
            val posCount1 = obsByPosition.values.count { it == 1 }
            val posCount2to4 = obsByPosition.values.count { it in 2..4 }
            val posCount5plus = obsByPosition.values.count { it >= 5 }

            log.info("--- Batch $batchNumber complete ---")
            log.info("  Qualifying games in batch  : $batchQualifyingGames")
            log.info("  Unique positions in batch  : $batchUniquePositions")
            log.info("  Total observations in batch: $batchTotalObservations")
            log.info("  Positions with 1 obs       : $posCount1")
            log.info("  Positions with 2–4 obs     : $posCount2to4")
            log.info("  Positions with 5+ obs      : $posCount5plus")

            val rowsPersisted = persistObservations(targetBand, batchObservations, batchFenByHash, batchGameUrls)

            cumulativeUniquePositions += batchUniquePositions
            cumulativeTotalObservations += batchTotalObservations
            cumulativeDistributionRowsPersisted += rowsPersisted

            log.info("  Distribution rows persisted: $rowsPersisted")
            log.info("--- Cumulative after batch $batchNumber ---")
            log.info("  Qualifying games total     : $totalQualifyingGames")
            log.info("  Unique positions (sum)     : $cumulativeUniquePositions")
            log.info("  Observations (sum)         : $cumulativeTotalObservations")
            log.info("  Rows persisted (sum)       : $cumulativeDistributionRowsPersisted")

            // Replace with fresh maps — old maps and their contents fall out of scope
            batchObservations = mutableMapOf()
            batchFenByHash = mutableMapOf()
            batchGameUrls = mutableSetOf()
            batchQualifyingGames = 0
        }

        val traversal = HumanMoveBfsTraversal(chessComClient, log)
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
                object : HumanMoveBfsSink {
                    // Skip any game whose URL has already been claimed by a previous
                    // batch / invocation / day.
                    override fun alreadyContributed(archiveGameUrls: List<String>): Set<String> =
                        humanMoveBfsSeenGameRepository
                            .findExistingGameUrls(archiveGameUrls)
                            .toSet()

                    override fun accept(game: HumanMoveBfsQualifyingGame): Boolean {
                        totalQualifyingGames++
                        batchQualifyingGames++
                        batchGameUrls.add(game.url)

                        traversal.processGamePgn(
                            pgn = game.pgn,
                            // opponent is White when the traversed player is Black
                            isWhiteInBand = !game.isPlayerWhite,
                            // opponent is Black when the traversed player is White
                            isBlackInBand = game.isPlayerWhite,
                            observations = batchObservations,
                            fenByHash = batchFenByHash,
                        )

                        // Flush completed batch
                        if (batchQualifyingGames >= request.batchSize) {
                            log.info("Starting batch ${batchNumber + 1}")
                            flushBatch()
                        }
                        return true
                    }
                },
            )

        // Flush any remaining partial batch
        if (batchObservations.isNotEmpty()) {
            log.info("Flushing final partial batch (batch ${batchNumber + 1})")
            flushBatch()
        }

        log.info("--- BFS SUMMARY ---")
        log.info("Target band: ${targetBand.value}")
        log.info("Seed players: ${request.seedPlayers.size}")
        log.info("Players visited: ${result.playersVisited}")
        log.info("Depth reached: ${result.depthReached}")
        log.info("Games inspected: ${result.gamesInspected}")
        log.info("Rapid games: ${result.rapidGames}")
        log.info("Qualifying games: ${result.qualifyingGames}")
        log.info("Unique games processed: ${result.uniqueGamesProcessed}")
        log.info("Batches flushed: $batchNumber")
        log.info("Cumulative unique positions (sum across batches): $cumulativeUniquePositions")
        log.info("Cumulative total observations: $cumulativeTotalObservations")
        log.info("Cumulative distribution rows persisted: $cumulativeDistributionRowsPersisted")
        log.info("Stop reason: ${result.stopReason}")
        log.info("--------------------------")

        return HumanMoveBfsResponse(
            ratingBand = targetBand.value,
            seedPlayers = request.seedPlayers.size,
            playersVisited = result.playersVisited,
            maxDepthReached = result.depthReached,
            maxGamesPerPlayer = request.maxGamesPerPlayer,
            gamesInspected = result.gamesInspected,
            rapidGames = result.rapidGames,
            qualifyingGames = result.qualifyingGames,
            uniqueGamesProcessed = result.uniqueGamesProcessed,
            uniquePositions = cumulativeUniquePositions,
            totalObservations = cumulativeTotalObservations,
            stopReason = result.stopReason,
        )
    }

    fun discoverPopulation(request: HumanMovePopulationDiscoveryRequest): HumanMovePopulationDiscoveryResponse {
        val targetBand =
            RatingBand.fromValue(request.ratingBand)
                ?: throw IllegalArgumentException("Invalid rating band: ${request.ratingBand}")
        require(request.targetQualifyingPlayers > 0) { "targetQualifyingPlayers must be positive" }

        val excluded = request.excludedPlayers.map(String::lowercase).toSet()
        val visited = mutableSetOf<String>()
        val queued = mutableSetOf<String>()
        var frontier = request.seedPlayers.map(String::lowercase).filterNot(excluded::contains).distinct()
        queued.addAll(frontier)
        val qualifying = linkedSetOf<String>()
        val seenUrls = mutableSetOf<String>()
        var depth = 0
        var gamesInspected = 0
        var rapidGames = 0
        var stopReason = ""

        while (frontier.isNotEmpty() && depth <= request.maxDepth && stopReason.isEmpty()) {
            val next = linkedSetOf<String>()
            for (player in frontier) {
                if (visited.size >= request.maxPlayers) {
                    stopReason = "MAX_PLAYERS"
                    break
                }
                if (!visited.add(player)) continue
                val archives =
                    try {
                        chessComClient.fetchArchiveUrls(player)
                    } catch (e: Exception) {
                        log.warn("Failed to fetch archives for $player: ${e.message}")
                        continue
                    }
                if (qualifying.size >= request.targetQualifyingPlayers) {
                    stopReason = "TARGET_QUALIFYING_PLAYERS"
                    break
                }
                var newRapidGames = 0
                for (archive in archives.reversed()) {
                    if (qualifying.size >= request.targetQualifyingPlayers ||
                        newRapidGames >= request.maxGamesPerPlayer
                    ) {
                        break
                    }
                    val games =
                        try {
                            chessComClient.fetchMonthlyGames(archive) ?: emptyList()
                        } catch (e: Exception) {
                            log.warn("Failed to fetch games from $archive: ${e.message}")
                            continue
                        }
                    for (game in games.reversed()) {
                        if (qualifying.size >= request.targetQualifyingPlayers ||
                            newRapidGames >= request.maxGamesPerPlayer
                        ) {
                            break
                        }
                        gamesInspected++
                        if ((game["rules"] as? String)?.equals("chess", true) == false) continue
                        if (TimeControl.fromExternal(game["time_class"] as? String) != TimeControl.RAPID) continue
                        rapidGames++
                        val url = game["url"] as? String ?: continue
                        if (!seenUrls.add(url)) continue
                        newRapidGames++
                        val white = game["white"] as? Map<*, *> ?: continue
                        val black = game["black"] as? Map<*, *> ?: continue
                        val whiteName = (white["username"] as? String)?.lowercase() ?: continue
                        val blackName = (black["username"] as? String)?.lowercase() ?: continue
                        val playerIsWhite = whiteName == player
                        val opponent = if (playerIsWhite) blackName else whiteName
                        val opponentRating =
                            ((if (playerIsWhite) black["rating"] else white["rating"]) as? Number)?.toInt() ?: 0
                        if (opponent in excluded) continue
                        if (!visited.contains(opponent) && queued.add(opponent)) next.add(opponent)
                        if (HumanMoveBfsTraversal.isRatingInBand(opponentRating, targetBand)) qualifying.add(opponent)
                    }
                }
            }
            if (stopReason.isNotEmpty()) break
            if (depth == request.maxDepth) {
                stopReason = "MAX_DEPTH"
                break
            }
            frontier = next.toList()
            depth++
        }
        if (stopReason.isEmpty()) stopReason = "EMPTY_FRONTIER"
        return HumanMovePopulationDiscoveryResponse(
            ratingBand = targetBand.value,
            seedPlayers = request.seedPlayers,
            excludedPlayers = request.excludedPlayers,
            targetQualifyingPlayers = request.targetQualifyingPlayers,
            qualifyingPlayers = qualifying.toList(),
            qualifyingPlayerCount = qualifying.size,
            maxPlayers = request.maxPlayers,
            maxGamesPerPlayer = request.maxGamesPerPlayer,
            maxDepth = request.maxDepth,
            playersVisited = visited.size,
            gamesInspected = gamesInspected,
            rapidGames = rapidGames,
            uniqueGamesProcessed = seenUrls.size,
            maxDepthReached = depth,
            stopReason = stopReason,
        )
    }

    /**
     * Persists a single batch of observations. Returns the number of distribution
     * rows written or updated. Called once per batch from [flushBatch].
     *
     * Every observed (position, move) pair is persisted — no minObservations
     * filtering is applied at this layer. Thresholding is the responsibility of
     * the explicit finalization operation, so that observations from later
     * batches (and later BFS invocations) can accumulate against the same
     * position before the global threshold is evaluated.
     *
     * Game-level exactly-once dedup: the batch's game URLs are atomically
     * claimed with a plain INSERT as the first DB operation inside this
     * transaction. Atomicity is provided by the `game_url` PRIMARY KEY
     * uniqueness constraint on `human_move_bfs_seen_game` — any pre-existing
     * URL surfaces as [org.springframework.dao.DataIntegrityViolationException],
     * which the claimer rewraps as [com.chessecho.repository.HumanMoveBfsClaimConflictException].
     * Because the claim and the distribution writes share one @Transactional
     * boundary, they commit or roll back together: a URL can never be marked as
     * consumed unless its observations are durably persisted, and observations
     * for an already-claimed URL will fail the claim step and abort the whole
     * batch.
     */
    @Transactional
    fun persistObservations(
        targetBand: RatingBand,
        observations: Map<Pair<String, String>, Int>,
        fenByHash: Map<String, String>,
        batchGameUrls: Set<String>,
    ): Int {
        // Step 1: atomically claim this batch's game URLs. The primary-key
        // constraint on human_move_bfs_seen_game.game_url is the atomic primitive.
        // Any pre-existing URL surfaces as HumanMoveBfsClaimConflictException,
        // which propagates out of this @Transactional method and rolls back both
        // the (partial) URL claims and any observations otherwise written below.
        // Under the single-writer assumption combined with the per-archive
        // pre-check, this never triggers in practice.
        humanMoveBfsSeenGameClaimer.claimGameUrls(batchGameUrls)

        val allHashes = fenByHash.keys.toList()

        // Find existing positions
        val existingPositionsMap = mutableMapOf<String, Position>()
        val hashBatches = allHashes.chunked(1000)
        for (batch in hashBatches) {
            positionRepository.findByHashIn(batch).forEach { pos ->
                existingPositionsMap[pos.hash] = pos
            }
        }

        // Create missing positions
        val missingHashes = allHashes.filter { !existingPositionsMap.containsKey(it) }
        if (missingHashes.isNotEmpty()) {
            val missingBatches = missingHashes.chunked(1000)
            for (batch in missingBatches) {
                val newPositions = batch.map { hash -> Position(hash = hash, fen = fenByHash[hash]!!) }
                val savedPositions = positionRepository.saveAll(newPositions)
                savedPositions.forEach { pos -> existingPositionsMap[pos.hash] = pos }
            }
        }

        val allPositionIds = existingPositionsMap.values.map { it.id }.toSet()
        val existingDistributions = mutableMapOf<Pair<UUID, String>, HumanMoveDistribution>()

        // Load existing distributions so we can accumulate onto them
        // (idempotent per unique key (position_id, rating_band, move_played))
        val posIdBatches = allPositionIds.chunked(1000)
        for (batch in posIdBatches) {
            batch.forEach { posId ->
                val dists = humanMoveDistributionRepository.findByPositionIdAndRatingBand(posId, targetBand.value)
                dists.forEach { dist ->
                    existingDistributions[Pair(posId, dist.movePlayed)] = dist
                }
            }
        }

        // Build the row set: UPDATE if the row already exists for
        // (position_id, rating_band, move_played), else INSERT.
        val distributionsToSave = mutableListOf<HumanMoveDistribution>()

        for ((key, count) in observations) {
            val (hash, move) = key
            val position = existingPositionsMap[hash] ?: continue

            val distKey = Pair(position.id, move)
            val existing = existingDistributions[distKey]

            if (existing != null) {
                existing.observationCount += count
                distributionsToSave.add(existing)
            } else {
                distributionsToSave.add(
                    HumanMoveDistribution(
                        positionId = position.id,
                        ratingBand = targetBand.value,
                        movePlayed = move,
                        observationCount = count,
                    ),
                )
            }
        }

        val distributionBatches = distributionsToSave.chunked(1000)
        for (batch in distributionBatches) {
            humanMoveDistributionRepository.saveAll(batch)
        }

        return distributionsToSave.size
    }
}
