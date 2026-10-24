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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private class BfsBatchAccumulator(
    private val targetBand: RatingBand,
    private val persistAction: (RatingBand, Map<Pair<String, String>, Int>, Map<String, String>, Set<String>) -> Int,
    private val log: Logger,
) {
    var totalQualifyingGames: Int = 0
    var cumulativeUniquePositions: Int = 0
    var cumulativeTotalObservations: Int = 0
    var cumulativeDistributionRowsPersisted: Int = 0
    var batchNumber: Int = 0

    var batchObservations = mutableMapOf<Pair<String, String>, Int>()
    var batchFenByHash = mutableMapOf<String, String>()
    var batchGameUrls = mutableSetOf<String>()
    var batchQualifyingGames: Int = 0

    fun flushBatch() {
        if (batchObservations.isEmpty()) return
        batchNumber++

        val batchUniquePositions = batchObservations.keys.map { it.first }.toSet().size
        val batchTotalObservations = batchObservations.values.sum()

        logBatchDiagnostics(batchUniquePositions, batchTotalObservations)

        val rowsPersisted = persistAction(targetBand, batchObservations, batchFenByHash, batchGameUrls)

        cumulativeUniquePositions += batchUniquePositions
        cumulativeTotalObservations += batchTotalObservations
        cumulativeDistributionRowsPersisted += rowsPersisted

        logBatchSummary(rowsPersisted)

        batchObservations = mutableMapOf()
        batchFenByHash = mutableMapOf()
        batchGameUrls = mutableSetOf()
        batchQualifyingGames = 0
    }

    private fun logBatchDiagnostics(
        batchUniquePositions: Int,
        batchTotalObservations: Int,
    ) {
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
    }

    private fun logBatchSummary(rowsPersisted: Int) {
        log.info("  Distribution rows persisted: $rowsPersisted")
        log.info("--- Cumulative after batch $batchNumber ---")
        log.info("  Qualifying games total     : $totalQualifyingGames")
        log.info("  Unique positions (sum)     : $cumulativeUniquePositions")
        log.info("  Observations (sum)         : $cumulativeTotalObservations")
        log.info("  Rows persisted (sum)       : $cumulativeDistributionRowsPersisted")
    }
}

private data class PopulationDiscoveryState(
    val excluded: Set<String>,
    val visited: MutableSet<String> = mutableSetOf(),
    val queued: MutableSet<String> = mutableSetOf(),
    val qualifying: MutableSet<String> = linkedSetOf(),
    val seenUrls: MutableSet<String> = mutableSetOf(),
    var frontier: List<String>,
    var depth: Int = 0,
    var gamesInspected: Int = 0,
    var rapidGames: Int = 0,
    var stopReason: String = "",
)

private data class DiscoveryOpponent(val username: String, val rating: Int)

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

        val accumulator = BfsBatchAccumulator(targetBand, ::persistObservations, log)
        val traversal = HumanMoveBfsTraversal(chessComClient, log)

        val bounds =
            HumanMoveBfsBounds(
                targetBand = targetBand,
                seedPlayers = request.seedPlayers,
                excludedPlayers = request.excludedPlayers,
                maxQualifyingGames = request.maxQualifyingGames,
                maxGamesPerPlayer = request.maxGamesPerPlayer,
                maxPlayers = request.maxPlayers,
                maxDepth = request.maxDepth,
            )

        val sink = createBfsSink(accumulator, traversal, request.batchSize)
        val result = traversal.traverse(bounds, sink)

        accumulator.flushBatch()

        logBfsSummary(targetBand, request, result, accumulator)

        return buildBfsResponse(targetBand, request, result, accumulator)
    }

    private fun createBfsSink(
        accumulator: BfsBatchAccumulator,
        traversal: HumanMoveBfsTraversal,
        batchSize: Int,
    ): HumanMoveBfsSink =
        object : HumanMoveBfsSink {
            override fun alreadyContributed(archiveGameUrls: List<String>): Set<String> =
                humanMoveBfsSeenGameRepository.findExistingGameUrls(archiveGameUrls).toSet()

            override fun accept(game: HumanMoveBfsQualifyingGame): Boolean {
                accumulator.totalQualifyingGames++
                accumulator.batchQualifyingGames++
                accumulator.batchGameUrls.add(game.url)

                traversal.processGamePgn(
                    pgn = game.pgn,
                    isWhiteInBand = !game.isPlayerWhite,
                    isBlackInBand = game.isPlayerWhite,
                    observations = accumulator.batchObservations,
                    fenByHash = accumulator.batchFenByHash,
                )

                if (accumulator.batchQualifyingGames >= batchSize) {
                    log.info("Starting batch ${accumulator.batchNumber + 1}")
                    accumulator.flushBatch()
                }
                return true
            }
        }

    private fun logBfsSummary(
        targetBand: RatingBand,
        request: HumanMoveBfsRequest,
        result: HumanMoveBfsTraversalResult,
        accumulator: BfsBatchAccumulator,
    ) {
        log.info("--- BFS SUMMARY ---")
        log.info("Target band: ${targetBand.value}")
        log.info("Seed players: ${request.seedPlayers.size}")
        log.info("Players visited: ${result.playersVisited}")
        log.info("Depth reached: ${result.depthReached}")
        log.info("Games inspected: ${result.gamesInspected}")
        log.info("Rapid games: ${result.rapidGames}")
        log.info("Qualifying games: ${result.qualifyingGames}")
        log.info("Unique games processed: ${result.uniqueGamesProcessed}")
        log.info("Batches flushed: ${accumulator.batchNumber}")
        log.info("Cumulative unique positions (sum across batches): ${accumulator.cumulativeUniquePositions}")
        log.info("Cumulative total observations: ${accumulator.cumulativeTotalObservations}")
        log.info("Cumulative distribution rows persisted: ${accumulator.cumulativeDistributionRowsPersisted}")
        log.info("Stop reason: ${result.stopReason}")
        log.info("--------------------------")
    }

    private fun buildBfsResponse(
        targetBand: RatingBand,
        request: HumanMoveBfsRequest,
        result: HumanMoveBfsTraversalResult,
        accumulator: BfsBatchAccumulator,
    ): HumanMoveBfsResponse =
        HumanMoveBfsResponse(
            ratingBand = targetBand.value,
            seedPlayers = request.seedPlayers.size,
            playersVisited = result.playersVisited,
            maxDepthReached = result.depthReached,
            maxGamesPerPlayer = request.maxGamesPerPlayer,
            gamesInspected = result.gamesInspected,
            rapidGames = result.rapidGames,
            qualifyingGames = result.qualifyingGames,
            uniqueGamesProcessed = result.uniqueGamesProcessed,
            uniquePositions = accumulator.cumulativeUniquePositions,
            totalObservations = accumulator.cumulativeTotalObservations,
            stopReason = result.stopReason,
        )

    fun discoverPopulation(request: HumanMovePopulationDiscoveryRequest): HumanMovePopulationDiscoveryResponse {
        val targetBand =
            RatingBand.fromValue(request.ratingBand)
                ?: throw IllegalArgumentException("Invalid rating band: ${request.ratingBand}")
        require(request.targetQualifyingPlayers > 0) { "targetQualifyingPlayers must be positive" }

        val state = initDiscoveryState(request)

        while (isDiscoveryActive(request, state)) {
            val next = linkedSetOf<String>()
            for (player in state.frontier) {
                if (checkPlayerDiscoveryStoppingCondition(request, state)) break
                exploreDiscoveryPlayer(player, request, targetBand, state, next)
                if (state.stopReason.isNotEmpty()) break
            }

            if (checkDiscoveryDepthOrStop(request, state)) break

            state.frontier = next.toList()
            state.depth++
        }

        if (state.stopReason.isEmpty()) state.stopReason = "EMPTY_FRONTIER"
        return buildDiscoveryResponse(targetBand, request, state)
    }

    private fun initDiscoveryState(request: HumanMovePopulationDiscoveryRequest): PopulationDiscoveryState {
        val excluded = request.excludedPlayers.map(String::lowercase).toSet()
        val frontier = request.seedPlayers.map(String::lowercase).filterNot(excluded::contains).distinct()
        val state = PopulationDiscoveryState(excluded = excluded, frontier = frontier)
        state.queued.addAll(frontier)
        return state
    }

    private fun isDiscoveryActive(
        request: HumanMovePopulationDiscoveryRequest,
        state: PopulationDiscoveryState,
    ): Boolean = state.frontier.isNotEmpty() && state.depth <= request.maxDepth && state.stopReason.isEmpty()

    private fun checkPlayerDiscoveryStoppingCondition(
        request: HumanMovePopulationDiscoveryRequest,
        state: PopulationDiscoveryState,
    ): Boolean {
        if (state.visited.size >= request.maxPlayers) {
            state.stopReason = "MAX_PLAYERS"
            return true
        }
        return false
    }

    private fun checkDiscoveryDepthOrStop(
        request: HumanMovePopulationDiscoveryRequest,
        state: PopulationDiscoveryState,
    ): Boolean {
        if (state.stopReason.isNotEmpty()) return true
        if (state.depth == request.maxDepth) {
            state.stopReason = "MAX_DEPTH"
            return true
        }
        return false
    }

    private fun exploreDiscoveryPlayer(
        player: String,
        request: HumanMovePopulationDiscoveryRequest,
        targetBand: RatingBand,
        state: PopulationDiscoveryState,
        next: MutableSet<String>,
    ) {
        if (!state.visited.add(player)) return

        val archives =
            try {
                chessComClient.fetchArchiveUrls(player)
            } catch (e: Exception) {
                log.warn("Failed to fetch archives for $player: ${e.message}")
                return
            }

        if (state.qualifying.size >= request.targetQualifyingPlayers) {
            state.stopReason = "TARGET_QUALIFYING_PLAYERS"
            return
        }

        var newRapidGames = 0
        for (archive in archives.reversed()) {
            if (state.qualifying.size >= request.targetQualifyingPlayers || newRapidGames >= request.maxGamesPerPlayer) break
            newRapidGames += inspectDiscoveryArchive(archive, player, request, targetBand, state, next, newRapidGames)
        }
    }

    private fun inspectDiscoveryArchive(
        archiveUrl: String,
        player: String,
        request: HumanMovePopulationDiscoveryRequest,
        targetBand: RatingBand,
        state: PopulationDiscoveryState,
        next: MutableSet<String>,
        currentNewRapid: Int,
    ): Int {
        val games =
            try {
                chessComClient.fetchMonthlyGames(archiveUrl) ?: emptyList()
            } catch (e: Exception) {
                log.warn("Failed to fetch games from $archiveUrl: ${e.message}")
                return 0
            }

        var newRapidAdded = 0
        for (game in games.reversed()) {
            if (state.qualifying.size >= request.targetQualifyingPlayers ||
                (currentNewRapid + newRapidAdded) >= request.maxGamesPerPlayer
            ) {
                break
            }
            if (processDiscoveryGame(game, player, targetBand, state, next)) {
                newRapidAdded++
            }
        }
        return newRapidAdded
    }

    private fun processDiscoveryGame(
        game: Map<*, *>,
        player: String,
        targetBand: RatingBand,
        state: PopulationDiscoveryState,
        next: MutableSet<String>,
    ): Boolean {
        state.gamesInspected++
        if ((game["rules"] as? String)?.equals("chess", true) == false) return false
        if (TimeControl.fromExternal(game["time_class"] as? String) != TimeControl.RAPID) return false

        state.rapidGames++
        val url = game["url"] as? String ?: return false
        if (!state.seenUrls.add(url)) return false

        val opponent = extractDiscoveryOpponent(game, player) ?: return true
        if (opponent.username in state.excluded) return true

        if (!state.visited.contains(opponent.username) && state.queued.add(opponent.username)) {
            next.add(opponent.username)
        }
        if (HumanMoveBfsTraversal.isRatingInBand(opponent.rating, targetBand)) {
            state.qualifying.add(opponent.username)
        }
        return true
    }

    private fun extractDiscoveryOpponent(
        game: Map<*, *>,
        player: String,
    ): DiscoveryOpponent? {
        val white = game["white"] as? Map<*, *> ?: return null
        val black = game["black"] as? Map<*, *> ?: return null
        val whiteName = (white["username"] as? String)?.lowercase() ?: return null
        val blackName = (black["username"] as? String)?.lowercase() ?: return null
        val playerIsWhite = whiteName == player
        val opponentName = if (playerIsWhite) blackName else whiteName
        val opponentRating = ((if (playerIsWhite) black["rating"] else white["rating"]) as? Number)?.toInt() ?: 0
        return DiscoveryOpponent(opponentName, opponentRating)
    }

    private fun buildDiscoveryResponse(
        targetBand: RatingBand,
        request: HumanMovePopulationDiscoveryRequest,
        state: PopulationDiscoveryState,
    ): HumanMovePopulationDiscoveryResponse =
        HumanMovePopulationDiscoveryResponse(
            ratingBand = targetBand.value,
            seedPlayers = request.seedPlayers,
            excludedPlayers = request.excludedPlayers,
            targetQualifyingPlayers = request.targetQualifyingPlayers,
            qualifyingPlayers = state.qualifying.toList(),
            qualifyingPlayerCount = state.qualifying.size,
            maxPlayers = request.maxPlayers,
            maxGamesPerPlayer = request.maxGamesPerPlayer,
            maxDepth = request.maxDepth,
            playersVisited = state.visited.size,
            gamesInspected = state.gamesInspected,
            rapidGames = state.rapidGames,
            uniqueGamesProcessed = state.seenUrls.size,
            maxDepthReached = state.depth,
            stopReason = state.stopReason,
        )

    /**
     * Persists a single batch of observations. Returns the number of distribution
     * rows written or updated. Called once per batch from [flushBatch].
     */
    @Transactional
    fun persistObservations(
        targetBand: RatingBand,
        observations: Map<Pair<String, String>, Int>,
        fenByHash: Map<String, String>,
        batchGameUrls: Set<String>,
    ): Int {
        humanMoveBfsSeenGameClaimer.claimGameUrls(batchGameUrls)

        val existingPositionsMap = loadOrCreatePositions(fenByHash)
        val distributionsToSave = buildDistributionsToSave(targetBand, observations, existingPositionsMap)

        for (batch in distributionsToSave.chunked(1000)) {
            humanMoveDistributionRepository.saveAll(batch)
        }

        return distributionsToSave.size
    }

    private fun loadOrCreatePositions(fenByHash: Map<String, String>): MutableMap<String, Position> {
        val allHashes = fenByHash.keys.toList()
        val existingPositionsMap = mutableMapOf<String, Position>()

        for (batch in allHashes.chunked(1000)) {
            positionRepository.findByHashIn(batch).forEach { pos ->
                existingPositionsMap[pos.hash] = pos
            }
        }

        val missingHashes = allHashes.filter { !existingPositionsMap.containsKey(it) }
        if (missingHashes.isNotEmpty()) {
            for (batch in missingHashes.chunked(1000)) {
                val newPositions =
                    batch.map { hash ->
                        Position(
                            hash = hash,
                            fen = checkNotNull(fenByHash[hash]) { "Missing FEN for hash $hash" },
                        )
                    }
                val savedPositions = positionRepository.saveAll(newPositions)
                savedPositions.forEach { pos -> existingPositionsMap[pos.hash] = pos }
            }
        }

        return existingPositionsMap
    }

    private fun loadExistingDistributions(
        positionIds: Set<UUID>,
        targetBand: RatingBand,
    ): MutableMap<Pair<UUID, String>, HumanMoveDistribution> {
        val existingDistributions = mutableMapOf<Pair<UUID, String>, HumanMoveDistribution>()
        for (batch in positionIds.chunked(1000)) {
            batch.forEach { posId ->
                val dists = humanMoveDistributionRepository.findByPositionIdAndRatingBand(posId, targetBand.value)
                dists.forEach { dist ->
                    existingDistributions[Pair(posId, dist.movePlayed)] = dist
                }
            }
        }
        return existingDistributions
    }

    private fun buildDistributionsToSave(
        targetBand: RatingBand,
        observations: Map<Pair<String, String>, Int>,
        existingPositionsMap: Map<String, Position>,
    ): List<HumanMoveDistribution> {
        val allPositionIds = existingPositionsMap.values.map { it.id }.toSet()
        val existingDistributions = loadExistingDistributions(allPositionIds, targetBand)
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
        return distributionsToSave
    }
}
