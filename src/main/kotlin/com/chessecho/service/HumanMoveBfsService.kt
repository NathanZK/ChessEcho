package com.chessecho.service

import com.chessecho.domain.HumanMoveDistribution
import com.chessecho.domain.Position
import com.chessecho.domain.RatingBand
import com.chessecho.domain.TimeControl
import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.dto.HumanMoveBfsResponse
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
                "minObservations=${request.minObservations}, " +
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

        var cumulativeUniquePositions = 0
        var cumulativeTotalObservations = 0
        var batchNumber = 0

        var batchObservations = mutableMapOf<Pair<String, String>, Int>()
        var batchFenByHash = mutableMapOf<String, String>()
        var batchQualifyingGames = 0

        var stopReason = ""

        /**
         * Flush the current batch by applying the observation threshold,
         * persisting the observations, and resetting the batch aggregation.
         */
        fun flushBatch() {
            if (batchObservations.isEmpty()) return

            batchNumber++

            val batchUniquePositions =
                batchObservations.keys
                    .map { it.first }
                    .toSet()
                    .size

            val batchTotalObservations = batchObservations.values.sum()

            val obsByPosition = mutableMapOf<String, Int>()
            for ((key, count) in batchObservations) {
                val hash = key.first
                obsByPosition[hash] = (obsByPosition[hash] ?: 0) + count
            }

            val positionsMeetingThreshold =
                obsByPosition.values.count { it >= request.minObservations }

            log.info("--- Batch $batchNumber complete ---")
            log.info("  Qualifying games         : $batchQualifyingGames")
            log.info("  Unique positions         : $batchUniquePositions")
            log.info("  Total observations       : $batchTotalObservations")
            log.info("  Positions meeting minObs : $positionsMeetingThreshold")

            persistObservations(
                targetBand = targetBand,
                observations = batchObservations,
                fenByHash = batchFenByHash,
                minObservations = request.minObservations,
            )

            cumulativeUniquePositions += batchUniquePositions
            cumulativeTotalObservations += batchTotalObservations

            log.info("  Cumulative games         : $totalQualifyingGames")

            batchObservations = mutableMapOf()
            batchFenByHash = mutableMapOf()
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

                // Process archives from newest to oldest.
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

                    // Process games from newest to oldest in the archive.
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
                            continue
                        }

                        val whiteData = game["white"] as? Map<*, *> ?: continue
                        val blackData = game["black"] as? Map<*, *> ?: continue

                        val whiteUsername =
                            (whiteData["username"] as? String)?.lowercase() ?: continue
                        val blackUsername =
                            (blackData["username"] as? String)?.lowercase() ?: continue

                        val whiteRating =
                            (whiteData["rating"] as? Number)?.toInt() ?: 0
                        val blackRating =
                            (blackData["rating"] as? Number)?.toInt() ?: 0

                        val isWhiteInBand = isRatingInBand(whiteRating, targetBand)
                        val isBlackInBand = isRatingInBand(blackRating, targetBand)

                        // Determine the opponent and add them to the next frontier
                        // regardless of rating.
                        val opponent =
                            if (whiteUsername == player) {
                                blackUsername
                            } else {
                                whiteUsername
                            }

                        if (!visitedPlayers.contains(opponent) &&
                            !queuedPlayers.contains(opponent)
                        ) {
                            if (nextFrontier.add(opponent)) {
                                queuedPlayers.add(opponent)
                                newOpponentsDiscovered++
                            }
                        }

                        // If neither player is in the target band, this is not
                        // a qualifying game.
                        if (!isWhiteInBand && !isBlackInBand) {
                            continue
                        }

                        val pgn = game["pgn"] as? String ?: continue

                        playerQualifyingGames++
                        totalQualifyingGames++
                        batchQualifyingGames++

                        processGamePgn(
                            pgn = pgn,
                            isWhiteInBand = isWhiteInBand,
                            isBlackInBand = isBlackInBand,
                            observations = batchObservations,
                            fenByHash = batchFenByHash,
                        )

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

            if (depth == request.maxDepth) {
                stopReason = "MAX_DEPTH"
                break
            }

            currentFrontier = nextFrontier.toList()
            depth++
        }

        if (stopReason.isEmpty() && currentFrontier.isEmpty()) {
            stopReason = "EMPTY_FRONTIER"
        }

        // Flush any remaining partial batch.
        if (batchObservations.isNotEmpty()) {
            log.info("Flushing final partial batch (batch ${batchNumber + 1})")
            flushBatch()
        }

        log.info("--- BFS SUMMARY ---")
        log.info("  Total batches          : $batchNumber")
        log.info("  Qualifying games total : $totalQualifyingGames")
        log.info("  Games inspected        : $totalGamesInspected")
        log.info("  Rapid games            : $totalRapidGames")
        log.info("  Unique games processed : ${seenGameUrls.size}")
        log.info("  Unique positions       : $cumulativeUniquePositions")
        log.info("  Total observations     : $cumulativeTotalObservations")
        log.info("  Stop reason            : $stopReason")

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
        }

        if (band.value.endsWith("+")) {
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

                if (
                    initialFen != null &&
                    initialFen.isNotBlank() &&
                    !GameParserService.isStandardStartFen(initialFen)
                ) {
                    return
                }

                chesslibGame.loadMoveText()

                val moves = chesslibGame.halfMoves
                val board = Board()

                for ((index, move) in moves.withIndex()) {
                    val isWhiteTurn = index % 2 == 0

                    val isQualifyingTurn =
                        (isWhiteTurn && isWhiteInBand) ||
                            (!isWhiteTurn && isBlackInBand)

                    if (isQualifyingTurn) {
                        val rawFen = board.fen
                        val hash = GameParserService.generateHash(rawFen)
                        val moveSan = move.san

                        fenByHash[hash] = rawFen

                        val key = Pair(hash, moveSan)
                        observations[key] =
                            observations.getOrDefault(key, 0) + 1

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
     * Persists a single batch of observations.
     */
    @Transactional
    fun persistObservations(
        targetBand: RatingBand,
        observations: Map<Pair<String, String>, Int>,
        fenByHash: Map<String, String>,
        minObservations: Int = 1,
    ) {
        val allHashes = fenByHash.keys.toList()

        // Find existing positions.
        val existingPositionsMap = mutableMapOf<String, Position>()
        val hashBatches = allHashes.chunked(1000)

        for (batch in hashBatches) {
            positionRepository.findByHashIn(batch).forEach { pos ->
                existingPositionsMap[pos.hash] = pos
            }
        }

        // Create missing positions.
        val missingHashes =
            allHashes.filter { !existingPositionsMap.containsKey(it) }

        if (missingHashes.isNotEmpty()) {
            val missingBatches = missingHashes.chunked(1000)

            for (batch in missingBatches) {
                val newPositions =
                    batch.map { hash ->
                        Position(
                            hash = hash,
                            fen = fenByHash[hash]!!,
                        )
                    }

                val savedPositions = positionRepository.saveAll(newPositions)

                savedPositions.forEach { pos ->
                    existingPositionsMap[pos.hash] = pos
                }
            }
        }

        val allPositionIds =
            existingPositionsMap.values.map { it.id }.toSet()

        val existingDistributions =
            mutableMapOf<Pair<UUID, String>, HumanMoveDistribution>()

        // Load existing distributions to avoid duplicates and accumulate
        // observations across separate runs.
        val posIdBatches = allPositionIds.chunked(1000)

        for (batch in posIdBatches) {
            batch.forEach { posId ->
                val dists =
                    humanMoveDistributionRepository
                        .findByPositionIdAndRatingBand(
                            posId,
                            targetBand.value,
                        )

                dists.forEach { dist ->
                    existingDistributions[Pair(posId, dist.movePlayed)] = dist
                }
            }
        }

        // Calculate total observation count per position from existing
        // database observations plus observations in this batch.
        val totalObsPerPosition = mutableMapOf<UUID, Int>()

        for ((distKey, existing) in existingDistributions) {
            val (posId, _) = distKey
            totalObsPerPosition[posId] =
                (totalObsPerPosition[posId] ?: 0) + existing.observationCount
        }

        for ((key, count) in observations) {
            val (hash, _) = key
            val position = existingPositionsMap[hash] ?: continue

            totalObsPerPosition[position.id] =
                (totalObsPerPosition[position.id] ?: 0) + count
        }

        // Build the rows to save, filtering by the minimum observation count.
        val distributionsToSave =
            mutableListOf<HumanMoveDistribution>()

        for ((key, count) in observations) {
            val (hash, move) = key
            val position = existingPositionsMap[hash] ?: continue

            val totalObs = totalObsPerPosition[position.id] ?: 0

            if (totalObs < minObservations) {
                continue
            }

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
    }

}
