package com.chessecho.research

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import org.junit.jupiter.api.Test
import java.io.File

class HalfmoveClockAuditTest {
    @Test
    fun `audit historical halfmove clock distribution`() {
        val corpusAFile = File("src/test/resources/research/corpus_a.json")
        val mapper = jacksonObjectMapper()
        val corpusA: List<PositionProvenanceRecord> = mapper.readValue(corpusAFile)

        println("Corpus A loaded: ${corpusA.size} positions")

        val rawGamesDir =
            File(
                "/Users/nathankebede/AntigravityProfiles/third/.gemini/antigravity/brain/9ee8df32-f921-4f62-99d5-42752729c645/scratch/raw_games",
            )
        val builder = CorpusBuilder()

        // Index games by URL
        val gameUrlToPgn = mutableMapOf<String, String>()
        val gmFiles =
            rawGamesDir.listFiles { f ->
                f.name.startsWith("hikaru") || f.name.startsWith("DanielNaroditsky") || f.name.startsWith("MagnusCarlsen")
            } ?: emptyArray()

        for (file in gmFiles) {
            val games = builder.parseGamesFromJsonArchive(file)
            for (g in games) {
                if (g.url != null && g.pgn != null) {
                    gameUrlToPgn[g.url] = g.pgn
                }
            }
        }
        println("Loaded ${gameUrlToPgn.size} GM games into index")

        var foundCount = 0
        var missingCount = 0
        val halfmoveClocks = mutableListOf<Int>()

        for (pos in corpusA) {
            val pgn = gameUrlToPgn[pos.sampleGameId]
            if (pgn == null) {
                missingCount++
                continue
            }
            foundCount++

            val pgnHolder =
                PgnHolder().apply {
                    try {
                        loadPgn(pgn)
                    } catch (_: Exception) {
                    }
                }
            if (pgnHolder.games.isEmpty()) continue
            val game = pgnHolder.games.first()
            try {
                game.loadMoveText()
            } catch (_: Exception) {
                continue
            }

            val moves = game.halfMoves
            val board = Board()
            val targetPly = pos.samplePly

            for ((idx, move) in moves.withIndex()) {
                val ply = idx + 1
                if (ply == targetPly) {
                    halfmoveClocks.add(board.halfMoveCounter)
                    break
                }
                try {
                    board.doMove(move)
                } catch (_: Exception) {
                    break
                }
            }
        }

        println("Audited positions with exact board state: ${halfmoveClocks.size} / ${corpusA.size}")
        println("Found in games: $foundCount, missing: $missingCount")

        val ge80 = halfmoveClocks.count { it >= 80 }
        val ge50 = halfmoveClocks.count { it >= 50 }
        val ge40 = halfmoveClocks.count { it >= 40 }
        val ge20 = halfmoveClocks.count { it >= 20 }
        val maxClock = halfmoveClocks.maxOrNull() ?: 0
        val meanClock = if (halfmoveClocks.isNotEmpty()) halfmoveClocks.average() else 0.0

        println("=== HISTORICAL HALFMOVE CLOCK AUDIT REPORT ===")
        println("Total positions audited: ${halfmoveClocks.size}")
        println("Positions with halfmove clock >= 80: $ge80 (${ge80.toDouble() / halfmoveClocks.size * 100.0}%)")
        println("Positions with halfmove clock >= 50: $ge50 (${ge50.toDouble() / halfmoveClocks.size * 100.0}%)")
        println("Positions with halfmove clock >= 40: $ge40 (${ge40.toDouble() / halfmoveClocks.size * 100.0}%)")
        println("Positions with halfmove clock >= 20: $ge20 (${ge20.toDouble() / halfmoveClocks.size * 100.0}%)")
        println("Max halfmove clock observed: $maxClock")
        println("Mean halfmove clock: $meanClock")
    }
}
