package com.chessecho.service

import com.chessecho.domain.HumanMoveDistribution
import com.chessecho.domain.Position
import com.chessecho.domain.RatingBand
import com.chessecho.domain.TimeControl
import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.dto.HumanMoveBfsResponse
import com.chessecho.repository.HumanMoveBfsSeenGameClaimer
import com.chessecho.repository.HumanMoveBfsSeenGameRepository
import com.chessecho.repository.HumanMoveDistributionRepository
import com.chessecho.repository.PositionRepository
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File
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

        val visitedPlayers = mutableSetOf<String>()
        val queuedPlayers = mutableSetOf<String>()

        var currentFrontier = request.seedPlayers.map { it.lowercase() }.distinct()
        queuedPlayers.addAll(currentFrontier)

        var depth = 0

        var totalPlayersVisited = 0
        var totalGamesInspected = 0
        var totalRapidGames = 0
        var totalQualifyingGames = 0
        val seenGameUrls = mutableSetOf<String>()

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

        var stopReason = ""

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

        while (currentFrontier.isNotEmpty() && depth <= request.maxDepth) {
            log.info("--- BFS Depth: $depth, Frontier Size: ${currentFrontier.size} ---")
            val nextFrontier = mutableSetOf<String>()

            for (player in currentFrontier) {
                if (totalPlayersVisited >= request.maxPlayers) {
                    stopReason = "MAX_PLAYERS"
                    break
                }
                if (totalQualifyingGames >= request.maxQualifyingGames) {
                    stopReason = "MAX_QUALIFYING_GAMES"
                    break
                }

                if (!visitedPlayers.add(player)) {
                    continue
                }

                totalPlayersVisited++
                log.info("Processing player: $player")

                val archiveUrls =
                    try {
                        chessComClient.fetchArchiveUrls(player)
                    } catch (e: Exception) {
                        log.warn("Failed to fetch archives for $player: ${e.message}")
                        continue
                    }

                var playerGamesInspected = 0
                var playerQualifyingGames = 0
                var newOpponentsDiscovered = 0

                // Process archives from newest to oldest
                for (archiveUrl in archiveUrls.reversed()) {
                    if (totalQualifyingGames >= request.maxQualifyingGames) break
                    if (playerGamesInspected >= request.maxGamesPerPlayer) break

                    val games =
                        try {
                            chessComClient.fetchMonthlyGames(archiveUrl) ?: emptyList()
                        } catch (e: Exception) {
                            log.warn("Failed to fetch games from $archiveUrl: ${e.message}")
                            continue
                        }

                    // One indexed round-trip per archive: skip any game whose URL has
                    // already been claimed by a previous batch / invocation / day, so
                    // we do not waste PGN parsing on games that will be filtered at
                    // the persistent-claim step anyway.
                    val archiveUrlsInBatch = games.mapNotNull { it["url"] as? String }
                    val alreadyClaimedUrls: Set<String> =
                        if (archiveUrlsInBatch.isEmpty()) {
                            emptySet()
                        } else {
                            humanMoveBfsSeenGameRepository
                                .findExistingGameUrls(archiveUrlsInBatch)
                                .toSet()
                        }

                    // Process games from newest to oldest in the archive
                    for (game in games.reversed()) {
                        if (totalQualifyingGames >= request.maxQualifyingGames) break
                        if (playerGamesInspected >= request.maxGamesPerPlayer) break

                        totalGamesInspected++
                        playerGamesInspected++

                        val rules = game["rules"] as? String
                        if (rules != null && !rules.equals("chess", ignoreCase = true)) {
                            continue
                        }

                        val timeClass = game["time_class"] as? String
                        val timeControl = TimeControl.fromExternal(timeClass)
                        if (timeControl != TimeControl.RAPID) {
                            continue
                        }

                        totalRapidGames++

                        val url = game["url"] as? String ?: continue
                        if (!seenGameUrls.add(url)) {
                            continue // Deduplicate games within this run
                        }
                        if (url in alreadyClaimedUrls) {
                            continue // Already contributed by a prior batch / run / day
                        }

                        val whiteData = game["white"] as? Map<*, *> ?: continue
                        val blackData = game["black"] as? Map<*, *> ?: continue

                        val whiteUsername = (whiteData["username"] as? String)?.lowercase() ?: continue
                        val blackUsername = (blackData["username"] as? String)?.lowercase() ?: continue

                        val whiteRating = (whiteData["rating"] as? Number)?.toInt() ?: 0
                        val blackRating = (blackData["rating"] as? Number)?.toInt() ?: 0

                        val isWhiteInBand = isRatingInBand(whiteRating, targetBand)
                        val isBlackInBand = isRatingInBand(blackRating, targetBand)

                        // Determine opponent and add to next frontier regardless of rating
                        val opponent = if (whiteUsername == player) blackUsername else whiteUsername
                        if (!visitedPlayers.contains(opponent) && !queuedPlayers.contains(opponent)) {
                            if (nextFrontier.add(opponent)) {
                                queuedPlayers.add(opponent)
                                newOpponentsDiscovered++
                            }
                        }

                        // If neither player is in the target band, this is not a qualifying game
                        if (!isWhiteInBand && !isBlackInBand) {
                            continue
                        }

                        val pgn = game["pgn"] as? String ?: continue

                        playerQualifyingGames++
                        totalQualifyingGames++
                        batchQualifyingGames++
                        batchGameUrls.add(url)

                        processGamePgn(
                            pgn = pgn,
                            isWhiteInBand = isWhiteInBand,
                            isBlackInBand = isBlackInBand,
                            observations = batchObservations,
                            fenByHash = batchFenByHash,
                        )

                        // Flush completed batch
                        if (batchQualifyingGames >= request.batchSize) {
                            log.info("Starting batch ${batchNumber + 1}")
                            flushBatch()
                        }
                    }
                }

                log.info(
                    "Player $player: $playerGamesInspected games inspected, " +
                        "$playerQualifyingGames qualifying. " +
                        "Added $newOpponentsDiscovered opponents.",
                )
            }

            if (stopReason.isNotEmpty()) {
                break
            }

            if (depth == request.maxDepth && stopReason.isEmpty()) {
                stopReason = "MAX_DEPTH"
                break
            }

            currentFrontier = nextFrontier.toList()
            depth++
        }

        if (stopReason.isEmpty() && currentFrontier.isEmpty()) {
            stopReason = "EMPTY_FRONTIER"
        }

        // Flush any remaining partial batch
        if (batchObservations.isNotEmpty()) {
            log.info("Flushing final partial batch (batch ${batchNumber + 1})")
            flushBatch()
        }

        log.info("--- BFS SUMMARY ---")
        log.info("Target band: ${targetBand.value}")
        log.info("Seed players: ${request.seedPlayers.size}")
        log.info("Players visited: $totalPlayersVisited")
        log.info("Depth reached: $depth")
        log.info("Games inspected: $totalGamesInspected")
        log.info("Rapid games: $totalRapidGames")
        log.info("Qualifying games: $totalQualifyingGames")
        log.info("Unique games processed: ${seenGameUrls.size}")
        log.info("Batches flushed: $batchNumber")
        log.info("Cumulative unique positions (sum across batches): $cumulativeUniquePositions")
        log.info("Cumulative total observations: $cumulativeTotalObservations")
        log.info("Cumulative distribution rows persisted: $cumulativeDistributionRowsPersisted")
        log.info("Stop reason: $stopReason")
        log.info("--------------------------")

        return HumanMoveBfsResponse(
            ratingBand = targetBand.value,
            seedPlayers = request.seedPlayers.size,
            playersVisited = totalPlayersVisited,
            maxDepthReached = depth,
            maxGamesPerPlayer = request.maxGamesPerPlayer,
            gamesInspected = totalGamesInspected,
            rapidGames = totalRapidGames,
            qualifyingGames = totalQualifyingGames,
            uniqueGamesProcessed = seenGameUrls.size,
            uniquePositions = cumulativeUniquePositions,
            totalObservations = cumulativeTotalObservations,
            stopReason = stopReason,
        )
    }

    private fun isRatingInBand(
        rating: Int,
        band: RatingBand,
    ): Boolean {
        val parts = band.value.split("-")
        if (parts.size == 2) {
            val min = parts[0].toIntOrNull() ?: return false
            val max = parts[1].toIntOrNull() ?: return false
            return rating in min..max
        } else if (band.value.endsWith("+")) {
            val min = band.value.dropLast(1).toIntOrNull() ?: return false
            return rating >= min
        }
        return false
    }

    private fun processGamePgn(
        pgn: String,
        isWhiteInBand: Boolean,
        isBlackInBand: Boolean,
        observations: MutableMap<Pair<String, String>, Int>,
        fenByHash: MutableMap<String, String>,
    ) {
        val file = File.createTempFile("bfs_game", ".pgn")
        try {
            file.writeText(pgn)
            val pgnHolder = PgnHolder(file.absolutePath)
            pgnHolder.loadPgn()

            if (pgnHolder.game.isNotEmpty()) {
                val chesslibGame = pgnHolder.game.first()
                val initialFen = chesslibGame.fen
                if (initialFen != null && initialFen.isNotBlank() && !GameParserService.isStandardStartFen(initialFen)) {
                    return
                }

                chesslibGame.loadMoveText()
                val moves = chesslibGame.halfMoves
                val board = Board()

                for ((index, move) in moves.withIndex()) {
                    val isWhiteTurn = index % 2 == 0
                    val isQualifyingTurn = (isWhiteTurn && isWhiteInBand) || (!isWhiteTurn && isBlackInBand)

                    if (isQualifyingTurn) {
                        val rawFen = board.fen
                        val hash = GameParserService.generateHash(rawFen)
                        val moveSan = move.san

                        fenByHash[hash] = rawFen

                        val key = Pair(hash, moveSan)
                        observations[key] = observations.getOrDefault(key, 0) + 1
                    }

                    board.doMove(move)
                }
            }
        } catch (e: Exception) {
            log.debug("Failed to parse game: ${e.message}")
        } finally {
            file.delete()
        }
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
