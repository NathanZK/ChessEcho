package com.chessecho.service

import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.UserPositionStats
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.time.Instant

@Service
class GameParserService(
    private val positionRepository: PositionRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val userPositionStatsRepository: UserPositionStatsRepository,
) {
    /**
     * Parses the PGN of a list of games, generates standardized positions (ignoring move counters)
     * by traversing the move tree, and persists both unique [Position]s and their specific [PositionOccurrence]s.
     *
     * @param games The list of [Game] entities whose PGNs should be parsed and processed.
     */
    @Transactional
    fun parseAndSavePositions(games: List<Game>) {
        if (games.isEmpty()) return

        val allPositions = mutableMapOf<String, Position>()
        val occurrences = mutableListOf<PositionOccurrence>()

        for (dbGame in games) {
            val file = File.createTempFile("game_parse", ".pgn")
            try {
                file.writeText(dbGame.pgn)
                val pgnHolder = PgnHolder(file.absolutePath)
                pgnHolder.loadPgn()

                if (pgnHolder.game.isNotEmpty()) {
                    val chesslibGame = pgnHolder.game.first()
                    chesslibGame.loadMoveText()
                    val moves = chesslibGame.halfMoves

                    val board = Board()
                    for ((index, move) in moves.withIndex()) {
                        // Capture the position BEFORE the move is made
                        val rawFen = board.fen
                        val hash = generateHash(rawFen)

                        // Add to our batch
                        val position =
                            allPositions.getOrPut(hash) {
                                Position(hash = hash, fen = rawFen)
                            }

                        val playerColor = if (index % 2 == 0) "WHITE" else "BLACK"

                        occurrences.add(
                            PositionOccurrence(
                                game = dbGame,
                                position = position,
                                chessAccount = dbGame.chessAccount,
                                plyNumber = index + 1,
                                movePlayed = move.san,
                                playerColor = playerColor,
                            ),
                        )

                        // Now make the move for the next iteration
                        board.doMove(move)
                    }
                }
            } catch (e: Exception) {
                // Log and skip if a game fails to parse
                println("Failed to parse game ${dbGame.id}: ${e.message}")
            } finally {
                file.delete()
            }
        }

        // Fetch existing positions from DB to avoid constraint violations
        val existingPositions =
            positionRepository.findByHashIn(allPositions.keys.toList())
                .associateBy { it.hash }
                .toMutableMap()

        // Save new positions
        val newPositions = allPositions.values.filter { !existingPositions.containsKey(it.hash) }
        if (newPositions.isNotEmpty()) {
            val savedPositions = positionRepository.saveAll(newPositions)
            savedPositions.forEach { existingPositions[it.hash] = it }
        }

        // Now map occurrences to the real persisted positions
        val mappedOccurrences =
            occurrences.map { occurrence ->
                val persistedPosition = existingPositions[occurrence.position.hash]!!
                PositionOccurrence(
                    game = occurrence.game,
                    position = persistedPosition,
                    chessAccount = occurrence.chessAccount,
                    plyNumber = occurrence.plyNumber,
                    movePlayed = occurrence.movePlayed,
                    playerColor = occurrence.playerColor,
                )
            }

        positionOccurrenceRepository.saveAll(mappedOccurrences)

        // Update user_position_stats for each unique (chess_account_id, position_id, player_color) combination
        val statsUpdates = mutableMapOf<String, UserPositionStats>()
        for (occurrence in mappedOccurrences) {
            val key = "${occurrence.chessAccount.id}-${occurrence.position.id}-${occurrence.playerColor}"
            val existingStats = statsUpdates[key]
            if (existingStats != null) {
                existingStats.timesReached++
                existingStats.updatedAt = Instant.now()
            } else {
                val dbStats =
                    userPositionStatsRepository.findByChessAccountIdAndPositionIdAndPlayerColor(
                        occurrence.chessAccount.id,
                        occurrence.position.id,
                        occurrence.playerColor,
                    )
                if (dbStats != null) {
                    dbStats.timesReached++
                    dbStats.updatedAt = Instant.now()
                    statsUpdates[key] = dbStats
                } else {
                    val newStats =
                        UserPositionStats(
                            chessAccount = occurrence.chessAccount,
                            position = occurrence.position,
                            playerColor = occurrence.playerColor,
                            timesReached = 1,
                            // Explicitly set to 1 for first occurrence
                            updatedAt = Instant.now(),
                        )
                    statsUpdates[key] = newStats
                }
            }
        }

        userPositionStatsRepository.saveAll(statsUpdates.values)
    }

    /**
     * Extracts the core structural components of a FEN string (piece placement, active color, castling, and en passant)
     * and computes a SHA-256 hash. This cleanly deduplicates transpositions while retaining strict chess rules.
     * Note: Time controls and half-move/full-move clocks are explicitly ignored.
     *
     * @param fen The raw standard FEN string to hash.
     * @return A 64-character hex string representing the SHA-256 hash.
     */
    private fun generateHash(fen: String): String {
        // A FEN string is space-separated:
        // [piece placement] [active color] [castling] [en passant] [halfmove] [fullmove]
        // The first 4 parts uniquely identify the legal board state.
        val parts = fen.split(" ")
        val cleanedFen =
            if (parts.size >= 4) {
                "${parts[0]} ${parts[1]} ${parts[2]} ${parts[3]}"
            } else {
                fen
            }

        // Apply SHA-256 hashing to ensure a fixed-length, indexed identifier
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(cleanedFen.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
