package com.chessecho.service

import com.chessecho.domain.RatingBand
import com.chessecho.domain.TimeControl
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import org.slf4j.Logger
import java.io.File

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
        val targetBand = bounds.targetBand
        val visitedPlayers = mutableSetOf<String>()
        val queuedPlayers = mutableSetOf<String>()
        val excludedPlayers = bounds.excludedPlayers.map { it.lowercase() }.toSet()

        var currentFrontier =
            bounds.seedPlayers
                .map { it.lowercase() }
                .filterNot { it in excludedPlayers }
                .distinct()
        queuedPlayers.addAll(currentFrontier)

        var depth = 0

        var totalPlayersVisited = 0
        var totalGamesInspected = 0
        var totalRapidGames = 0
        var totalQualifyingGames = 0
        val seenGameUrls = mutableSetOf<String>()

        var stopReason = ""

        while (currentFrontier.isNotEmpty() && (bounds.maxDepth == null || depth <= bounds.maxDepth)) {
            log.info("--- BFS Depth: $depth, Frontier Size: ${currentFrontier.size} ---")
            val nextFrontier = mutableSetOf<String>()

            for (player in currentFrontier) {
                if (bounds.maxPlayers != null && totalPlayersVisited >= bounds.maxPlayers) {
                    stopReason = "MAX_PLAYERS"
                    break
                }
                if (bounds.maxQualifyingGames != null && totalQualifyingGames >= bounds.maxQualifyingGames) {
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
                        sink.onArchiveListFailure(player, e)
                        continue
                    }

                var playerGamesInspected = 0
                // Counts only NEW (not previously seen/claimed) rapid games found
                // for this player in this run. maxGamesPerPlayer bounds this
                // per-run new-game budget, not the number of games merely
                // inspected, so previously ingested games must not consume it.
                var playerNewRapidGamesInspected = 0
                var playerQualifyingGames = 0
                var newOpponentsDiscovered = 0

                // Process archives from newest to oldest
                for (archiveUrl in archiveUrls.reversed()) {
                    if (bounds.maxQualifyingGames != null && totalQualifyingGames >= bounds.maxQualifyingGames) break
                    if (playerNewRapidGamesInspected >= bounds.maxGamesPerPlayer) break

                    val games =
                        try {
                            chessComClient.fetchMonthlyGames(archiveUrl) ?: emptyList()
                        } catch (e: Exception) {
                            log.warn("Failed to fetch games from $archiveUrl: ${e.message}")
                            sink.onMonthlyGamesFailure(archiveUrl, e)
                            continue
                        }

                    // One round-trip per archive: skip any game the sink reports as
                    // already contributed, so we do not waste PGN parsing on games
                    // that will be filtered at the persistent-claim step anyway.
                    val archiveUrlsInBatch = games.mapNotNull { it["url"] as? String }
                    val alreadyClaimedUrls: Set<String> =
                        if (archiveUrlsInBatch.isEmpty()) {
                            emptySet()
                        } else {
                            sink.alreadyContributed(archiveUrlsInBatch)
                        }

                    // Process games from newest to oldest in the archive
                    for (game in games.reversed()) {
                        if (bounds.maxQualifyingGames != null && totalQualifyingGames >= bounds.maxQualifyingGames) break
                        if (playerNewRapidGamesInspected >= bounds.maxGamesPerPlayer) break

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

                        // Only games that are new (not previously claimed) count
                        // toward this run's per-player new-game budget.
                        playerNewRapidGamesInspected++

                        val whiteData = game["white"] as? Map<*, *> ?: continue
                        val blackData = game["black"] as? Map<*, *> ?: continue

                        val whiteUsername = (whiteData["username"] as? String)?.lowercase() ?: continue
                        val blackUsername = (blackData["username"] as? String)?.lowercase() ?: continue

                        val whiteRating = (whiteData["rating"] as? Number)?.toInt() ?: 0
                        val blackRating = (blackData["rating"] as? Number)?.toInt() ?: 0

                        // Identify which side the traversed player occupies and derive the opponent.
                        val isPlayerWhite = (whiteUsername == player)
                        val opponent = if (isPlayerWhite) blackUsername else whiteUsername
                        val opponentRating = if (isPlayerWhite) blackRating else whiteRating

                        if (opponent in excludedPlayers) {
                            // Excluded opponents must not affect traversal, attribution,
                            // or the persistent seen-game claim.
                            seenGameUrls.remove(url)
                            continue
                        }

                        // Add opponent to frontier regardless of rating
                        if (!visitedPlayers.contains(opponent) && !queuedPlayers.contains(opponent)) {
                            if (nextFrontier.add(opponent)) {
                                queuedPlayers.add(opponent)
                                newOpponentsDiscovered++
                            }
                        }

                        // A game only qualifies when the opponent's game-time rating is within the
                        // target band.  Only the opponent's moves are attributed to the distribution;
                        // the traversed player's own moves are never recorded.
                        if (!isRatingInBand(opponentRating, targetBand)) continue

                        val pgn = game["pgn"] as? String ?: continue

                        val counted =
                            sink.accept(
                                HumanMoveBfsQualifyingGame(
                                    url = url,
                                    traversedPlayer = player,
                                    opponent = opponent,
                                    isPlayerWhite = isPlayerWhite,
                                    opponentRating = opponentRating,
                                    rules = rules,
                                    timeClass = timeClass,
                                    depth = depth,
                                    pgn = pgn,
                                ),
                            )
                        if (counted) {
                            playerQualifyingGames++
                            totalQualifyingGames++
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

            if (bounds.maxDepth != null && depth == bounds.maxDepth && stopReason.isEmpty()) {
                stopReason = "MAX_DEPTH"
                break
            }

            currentFrontier = nextFrontier.toList()
            depth++
        }

        if (stopReason.isEmpty() && currentFrontier.isEmpty()) {
            stopReason = "EMPTY_FRONTIER"
        }

        return HumanMoveBfsTraversalResult(
            playersVisited = totalPlayersVisited,
            depthReached = depth,
            gamesInspected = totalGamesInspected,
            rapidGames = totalRapidGames,
            qualifyingGames = totalQualifyingGames,
            uniqueGamesProcessed = seenGameUrls.size,
            stopReason = stopReason,
        )
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
    ): HumanMoveBfsPgnOutcome {
        val file = File.createTempFile("bfs_game", ".pgn")
        try {
            file.writeText(pgn)
            val pgnHolder = PgnHolder(file.absolutePath)
            pgnHolder.loadPgn()

            if (pgnHolder.game.isNotEmpty()) {
                val chesslibGame = pgnHolder.game.first()
                val initialFen = chesslibGame.fen
                if (initialFen != null && initialFen.isNotBlank() && !GameParserService.isStandardStartFen(initialFen)) {
                    return HumanMoveBfsPgnOutcome.NON_STANDARD_START
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
            return HumanMoveBfsPgnOutcome.PARSED
        } catch (e: Exception) {
            log.debug("Failed to parse game: ${e.message}")
            return HumanMoveBfsPgnOutcome.PARSE_FAILED
        } finally {
            file.delete()
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
