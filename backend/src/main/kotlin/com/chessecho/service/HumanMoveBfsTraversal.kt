package com.chessecho.service

import com.chessecho.domain.RatingBand
import com.chessecho.domain.TimeControl
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.MoveList
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import org.slf4j.Logger

/** Traversal bounds shared by the legacy `/bfs` path and the Issue #423 corpus path. */
internal data class HumanMoveBfsBounds(
    val targetBand: RatingBand,
    val seedPlayers: List<String>,
    val excludedPlayers: List<String>,
    val maxQualifyingGames: Int?,
    val maxGamesPerPlayer: Int,
    val maxPlayers: Int?,
    val maxDepth: Int?,
)

/** A RAPID standard-chess game whose opponent is in band and which carries a PGN. */
internal data class HumanMoveBfsQualifyingGame(
    val url: String,
    val traversedPlayer: String,
    val opponent: String,
    val isPlayerWhite: Boolean,
    val opponentRating: Int,
    val rules: String?,
    val timeClass: String?,
    val depth: Int,
    val pgn: String,
)

internal enum class HumanMoveBfsPgnOutcome { PARSED, PARSE_FAILED, NON_STANDARD_START }

/**
 * Receives traversal events. The legacy sink batches into the global
 * distribution; the corpus sink commits run-scoped per-game contributions.
 */
internal interface HumanMoveBfsSink {
    /** Archive URLs already contributed outside this traversal; skipped before the per-player budget. */
    fun alreadyContributed(archiveGameUrls: List<String>): Set<String>

    /** Returns true when the game counts toward `maxQualifyingGames`. */
    fun accept(game: HumanMoveBfsQualifyingGame): Boolean

    fun onArchiveListFailure(
        player: String,
        error: Exception,
    ) {}

    fun onMonthlyGamesFailure(
        archiveUrl: String,
        error: Exception,
    ) {}
}

internal data class HumanMoveBfsTraversalResult(
    val playersVisited: Int,
    val depthReached: Int,
    val gamesInspected: Int,
    val rapidGames: Int,
    val qualifyingGames: Int,
    val uniqueGamesProcessed: Int,
    val stopReason: String,
)

private data class TraversalState(
    val visitedPlayers: MutableSet<String> = mutableSetOf(),
    val queuedPlayers: MutableSet<String> = mutableSetOf(),
    val excludedPlayers: Set<String>,
    val seenGameUrls: MutableSet<String> = mutableSetOf(),
    var currentFrontier: List<String>,
    var depth: Int = 0,
    var totalPlayersVisited: Int = 0,
    var totalGamesInspected: Int = 0,
    var totalRapidGames: Int = 0,
    var totalQualifyingGames: Int = 0,
    var stopReason: String = "",
)

private data class PlayerTraversalStats(
    var gamesInspected: Int = 0,
    var newRapidGamesInspected: Int = 0,
    var qualifyingGames: Int = 0,
    var newOpponentsDiscovered: Int = 0,
)

private data class ExtractedOpponent(
    val username: String,
    val rating: Int,
    val isPlayerWhite: Boolean,
)

/**
 * The BFS loop previously inlined in [HumanMoveBfsService.runBfs], moved
 * without semantic change so both paths share one traversal and qualification
 * definition.
 */
internal class HumanMoveBfsTraversal(
    private val chessComClient: ChessComClient,
    private val log: Logger,
) {
    fun traverse(
        bounds: HumanMoveBfsBounds,
        sink: HumanMoveBfsSink,
    ): HumanMoveBfsTraversalResult {
        val state = initTraversalState(bounds)

        while (isFrontierActive(bounds, state)) {
            log.info("--- BFS Depth: ${state.depth}, Frontier Size: ${state.currentFrontier.size} ---")
            val nextFrontier = mutableSetOf<String>()

            for (player in state.currentFrontier) {
                if (checkPlayerStoppingCondition(bounds, state)) break
                traversePlayer(player, bounds, sink, state, nextFrontier)
            }

            if (checkDepthOrStop(bounds, state)) break

            state.currentFrontier = nextFrontier.toList()
            state.depth++
        }

        finalizeStopReason(state)
        return buildResult(state)
    }

    private fun isFrontierActive(
        bounds: HumanMoveBfsBounds,
        state: TraversalState,
    ): Boolean = state.currentFrontier.isNotEmpty() && (bounds.maxDepth == null || state.depth <= bounds.maxDepth)

    private fun checkDepthOrStop(
        bounds: HumanMoveBfsBounds,
        state: TraversalState,
    ): Boolean {
        if (state.stopReason.isNotEmpty()) return true
        if (bounds.maxDepth != null && state.depth == bounds.maxDepth) {
            state.stopReason = "MAX_DEPTH"
            return true
        }
        return false
    }

    private fun finalizeStopReason(state: TraversalState) {
        if (state.stopReason.isEmpty() && state.currentFrontier.isEmpty()) {
            state.stopReason = "EMPTY_FRONTIER"
        }
    }

    private fun buildResult(state: TraversalState): HumanMoveBfsTraversalResult =
        HumanMoveBfsTraversalResult(
            playersVisited = state.totalPlayersVisited,
            depthReached = state.depth,
            gamesInspected = state.totalGamesInspected,
            rapidGames = state.totalRapidGames,
            qualifyingGames = state.totalQualifyingGames,
            uniqueGamesProcessed = state.seenGameUrls.size,
            stopReason = state.stopReason,
        )

    private fun initTraversalState(bounds: HumanMoveBfsBounds): TraversalState {
        val excludedPlayers = bounds.excludedPlayers.map { it.lowercase() }.toSet()
        val currentFrontier =
            bounds.seedPlayers
                .map { it.lowercase() }
                .filterNot { it in excludedPlayers }
                .distinct()
        val state =
            TraversalState(
                excludedPlayers = excludedPlayers,
                currentFrontier = currentFrontier,
            )
        state.queuedPlayers.addAll(currentFrontier)
        return state
    }

    private fun checkPlayerStoppingCondition(
        bounds: HumanMoveBfsBounds,
        state: TraversalState,
    ): Boolean {
        if (bounds.maxPlayers != null && state.totalPlayersVisited >= bounds.maxPlayers) {
            state.stopReason = "MAX_PLAYERS"
            return true
        }
        if (bounds.maxQualifyingGames != null && state.totalQualifyingGames >= bounds.maxQualifyingGames) {
            state.stopReason = "MAX_QUALIFYING_GAMES"
            return true
        }
        return false
    }

    private fun isPlayerBudgetReached(
        bounds: HumanMoveBfsBounds,
        state: TraversalState,
        playerStats: PlayerTraversalStats,
    ): Boolean =
        (bounds.maxQualifyingGames != null && state.totalQualifyingGames >= bounds.maxQualifyingGames) ||
            (playerStats.newRapidGamesInspected >= bounds.maxGamesPerPlayer)

    private fun traversePlayer(
        player: String,
        bounds: HumanMoveBfsBounds,
        sink: HumanMoveBfsSink,
        state: TraversalState,
        nextFrontier: MutableSet<String>,
    ) {
        if (!state.visitedPlayers.add(player)) return

        state.totalPlayersVisited++
        log.info("Processing player: $player")

        val archiveUrls =
            try {
                chessComClient.fetchArchiveUrls(player)
            } catch (e: Exception) {
                log.warn("Failed to fetch archives for $player: ${e.message}")
                sink.onArchiveListFailure(player, e)
                return
            }

        val playerStats = PlayerTraversalStats()
        for (archiveUrl in archiveUrls.reversed()) {
            if (isPlayerBudgetReached(bounds, state, playerStats)) break
            inspectPlayerArchive(archiveUrl, player, bounds, sink, state, nextFrontier, playerStats)
        }

        log.info(
            "Player $player: ${playerStats.gamesInspected} games inspected, " +
                "${playerStats.qualifyingGames} qualifying. " +
                "Added ${playerStats.newOpponentsDiscovered} opponents.",
        )
    }

    private fun inspectPlayerArchive(
        archiveUrl: String,
        player: String,
        bounds: HumanMoveBfsBounds,
        sink: HumanMoveBfsSink,
        state: TraversalState,
        nextFrontier: MutableSet<String>,
        playerStats: PlayerTraversalStats,
    ) {
        val games =
            try {
                chessComClient.fetchMonthlyGames(archiveUrl) ?: emptyList()
            } catch (e: Exception) {
                log.warn("Failed to fetch games from $archiveUrl: ${e.message}")
                sink.onMonthlyGamesFailure(archiveUrl, e)
                return
            }

        val archiveUrlsInBatch = games.mapNotNull { it["url"] as? String }
        val alreadyClaimedUrls =
            if (archiveUrlsInBatch.isEmpty()) {
                emptySet()
            } else {
                sink.alreadyContributed(archiveUrlsInBatch)
            }

        for (game in games.reversed()) {
            if (isPlayerBudgetReached(bounds, state, playerStats)) break
            processCandidateGame(game, player, bounds, sink, state, nextFrontier, playerStats, alreadyClaimedUrls)
        }
    }

    private fun isRapidGame(game: Map<*, *>): Boolean {
        val rules = game["rules"] as? String
        if (rules != null && !rules.equals("chess", ignoreCase = true)) return false
        val timeClass = game["time_class"] as? String
        return TimeControl.fromExternal(timeClass) == TimeControl.RAPID
    }

    private fun tryQueueOpponent(
        opponentUsername: String,
        state: TraversalState,
        nextFrontier: MutableSet<String>,
        playerStats: PlayerTraversalStats,
    ) {
        if (!state.visitedPlayers.contains(opponentUsername) && !state.queuedPlayers.contains(opponentUsername)) {
            if (nextFrontier.add(opponentUsername)) {
                state.queuedPlayers.add(opponentUsername)
                playerStats.newOpponentsDiscovered++
            }
        }
    }

    private fun processCandidateGame(
        game: Map<*, *>,
        player: String,
        bounds: HumanMoveBfsBounds,
        sink: HumanMoveBfsSink,
        state: TraversalState,
        nextFrontier: MutableSet<String>,
        playerStats: PlayerTraversalStats,
        alreadyClaimedUrls: Set<String>,
    ) {
        state.totalGamesInspected++
        playerStats.gamesInspected++

        if (!isRapidGame(game)) return
        state.totalRapidGames++

        val url = game["url"] as? String ?: return
        if (!state.seenGameUrls.add(url) || url in alreadyClaimedUrls) return

        playerStats.newRapidGamesInspected++

        val opponent = extractOpponent(game, player) ?: return
        if (opponent.username in state.excludedPlayers) {
            state.seenGameUrls.remove(url)
            return
        }

        tryQueueOpponent(opponent.username, state, nextFrontier, playerStats)

        if (!isRatingInBand(opponent.rating, bounds.targetBand)) return
        val pgn = game["pgn"] as? String ?: return

        dispatchQualifyingGame(url, player, opponent, game, pgn, sink, state, playerStats)
    }

    private fun dispatchQualifyingGame(
        url: String,
        player: String,
        opponent: ExtractedOpponent,
        game: Map<*, *>,
        pgn: String,
        sink: HumanMoveBfsSink,
        state: TraversalState,
        playerStats: PlayerTraversalStats,
    ) {
        val qualifyingGame =
            HumanMoveBfsQualifyingGame(
                url = url,
                traversedPlayer = player,
                opponent = opponent.username,
                isPlayerWhite = opponent.isPlayerWhite,
                opponentRating = opponent.rating,
                rules = game["rules"] as? String,
                timeClass = game["time_class"] as? String,
                depth = state.depth,
                pgn = pgn,
            )

        if (sink.accept(qualifyingGame)) {
            playerStats.qualifyingGames++
            state.totalQualifyingGames++
        }
    }

    private fun extractOpponent(
        game: Map<*, *>,
        player: String,
    ): ExtractedOpponent? {
        val whiteData = game["white"] as? Map<*, *> ?: return null
        val blackData = game["black"] as? Map<*, *> ?: return null
        val whiteUsername = (whiteData["username"] as? String)?.lowercase() ?: return null
        val blackUsername = (blackData["username"] as? String)?.lowercase() ?: return null
        val isPlayerWhite = (whiteUsername == player)
        val opponentUsername = if (isPlayerWhite) blackUsername else whiteUsername
        val opponentRating = ((if (isPlayerWhite) blackData["rating"] else whiteData["rating"]) as? Number)?.toInt() ?: 0
        return ExtractedOpponent(opponentUsername, opponentRating, isPlayerWhite)
    }

    /**
     * Adds the in-band side's (position hash, SAN) observations of [pgn] to
     * [observations]. Parse failures and non-standard starts add nothing.
     */
    fun processGamePgn(
        pgn: String,
        isWhiteInBand: Boolean,
        isBlackInBand: Boolean,
        observations: MutableMap<Pair<String, String>, Int>,
        fenByHash: MutableMap<String, String>,
        occurrences: MutableList<HumanMoveCorpusOccurrence>? = null,
    ): HumanMoveBfsPgnOutcome {
        try {
            val pgnHolder = PgnHolder().apply { loadPgn(pgn) }

            if (pgnHolder.games.isEmpty()) return HumanMoveBfsPgnOutcome.PARSED

            val chesslibGame = pgnHolder.games.first()
            val initialFen = chesslibGame.fen
            if (initialFen != null && initialFen.isNotBlank() && !GameParserService.isStandardStartFen(initialFen)) {
                return HumanMoveBfsPgnOutcome.NON_STANDARD_START
            }

            chesslibGame.loadMoveText()
            recordMoveObservations(chesslibGame.halfMoves, isWhiteInBand, isBlackInBand, observations, fenByHash, occurrences)
            return HumanMoveBfsPgnOutcome.PARSED
        } catch (e: Exception) {
            log.debug("Failed to parse game: ${e.message}")
            return HumanMoveBfsPgnOutcome.PARSE_FAILED
        }
    }

    private fun recordMoveObservations(
        moves: MoveList,
        isWhiteInBand: Boolean,
        isBlackInBand: Boolean,
        observations: MutableMap<Pair<String, String>, Int>,
        fenByHash: MutableMap<String, String>,
        occurrences: MutableList<HumanMoveCorpusOccurrence>?,
    ) {
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
                occurrences?.add(
                    HumanMoveCorpusOccurrence(
                        preMovePly = index + 1,
                        positionHash = hash,
                        movePlayed = moveSan,
                    ),
                )
            }

            board.doMove(move)
        }
    }

    companion object {
        fun isRatingInBand(
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
    }
}
