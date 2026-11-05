package com.chessecho.research

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import java.io.File
import kotlin.random.Random

enum class GamePhase {
    OPENING,
    MIDDLEGAME,
    ENDGAME,
}

data class PositionProvenanceRecord(
    val canonicalFen: String,
    val occurrenceCount: Int,
    val sampleGameId: String,
    val samplePlayerId: String,
    val whitePlayer: String = "unknown",
    val blackPlayer: String = "unknown",
    val samplePly: Int,
    val sampleTimeControl: String,
    val eco: String,
    val phase: GamePhase,
    val sideToMove: String,
)

data class RawGameRecord(
    val url: String?,
    val pgn: String?,
    val timeControl: String?,
    val eco: String,
    val whiteUsername: String?,
    val blackUsername: String?,
)

data class CorpusAuditReport(
    val corpusName: String,
    val totalPositions: Int,
    val uniqueFens: Int,
    val whiteCount: Int,
    val blackCount: Int,
    val whitePct: Double,
    val blackPct: Double,
    val openingCount: Int,
    val middlegameCount: Int,
    val endgameCount: Int,
    val openingPct: Double,
    val middlegamePct: Double,
    val endgamePct: Double,
    val ecoDistribution: Map<String, Int>,
    val maxFamilyPct: Double,
    val maxFamilyName: String,
    val uncapturableEpCount: Int,
    val allCriteriaPassed: Boolean,
)

class CorpusBuilder {
    private val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val ecoRegex = Regex("""\[ECO\s+"([^"]+)"]""")

    fun normalizeEnPassant(fen: String): String {
        val trimmed = fen.trim()
        val fields = trimmed.split("\\s+".toRegex())
        if (fields.size < 4) return trimmed
        val epField = fields[3]
        if (epField == "-") return "${fields[0]} ${fields[1]} ${fields[2]} -"

        val epSquareName = epField.uppercase()
        return try {
            val board = Board()
            board.loadFromFen(trimmed)
            val epSquare = Square.valueOf(epSquareName)
            val hasLegalEp = board.legalMoves().any { move ->
                move.to == epSquare &&
                    board.getPiece(move.from).pieceType == PieceType.PAWN &&
                    move.from.file != move.to.file
            }
            if (hasLegalEp) {
                "${fields[0]} ${fields[1]} ${fields[2]} ${fields[3].lowercase()}"
            } else {
                "${fields[0]} ${fields[1]} ${fields[2]} -"
            }
        } catch (_: Exception) {
            "${fields[0]} ${fields[1]} ${fields[2]} -"
        }
    }

    fun classifyPhase(board: Board, ply: Int): GamePhase {
        if (ply <= 20) return GamePhase.OPENING

        var whiteNonPawn = 0
        var blackNonPawn = 0
        var totalPieces = 0

        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            if (piece != Piece.NONE) {
                totalPieces++
                val value = when (piece.pieceType) {
                    PieceType.QUEEN -> 9
                    PieceType.ROOK -> 5
                    PieceType.BISHOP, PieceType.KNIGHT -> 3
                    else -> 0
                }
                if (piece.pieceSide == com.github.bhlangonijr.chesslib.Side.WHITE) {
                    whiteNonPawn += value
                } else {
                    blackNonPawn += value
                }
            }
        }

        return if (ply >= 45 || (whiteNonPawn <= 13 && blackNonPawn <= 13) || totalPieces <= 10) {
            GamePhase.ENDGAME
        } else {
            GamePhase.MIDDLEGAME
        }
    }

    fun extractEcoFromPgn(pgn: String?): String {
        if (pgn.isNullOrBlank()) return "A00"
        val match = ecoRegex.find(pgn)
        val raw = match?.groupValues?.get(1)?.trim()?.uppercase()
        return if (!raw.isNullOrBlank() && raw.length >= 3 && raw[0] in 'A'..'E') {
            raw
        } else {
            "A00"
        }
    }

    fun parseGamesFromJsonArchive(file: File): List<RawGameRecord> {
        val rootNode = mapper.readTree(file)
        val gamesNode = rootNode.get("games") ?: return emptyList()
        val result = mutableListOf<RawGameRecord>()

        for (g in gamesNode) {
            val pgn = g.get("pgn")?.asText()
            if (pgn.isNullOrBlank()) continue
            val url = g.get("url")?.asText() ?: "unknown-game"
            val timeControl = g.get("time_control")?.asText() ?: "unknown"
            val eco = extractEcoFromPgn(pgn)
            val white = g.get("white")?.get("username")?.asText() ?: "unknown"
            val black = g.get("black")?.get("username")?.asText() ?: "unknown"

            result.add(
                RawGameRecord(
                    url = url,
                    pgn = pgn,
                    timeControl = timeControl,
                    eco = eco,
                    whiteUsername = white,
                    blackUsername = black,
                ),
            )
        }
        return result
    }

    fun extractSampledPositionsFromGame(
        rawGame: RawGameRecord,
        minPlySpacing: Int = 9,
        gameIndex: Int = 0,
    ): List<PositionProvenanceRecord> {
        val pgn = rawGame.pgn ?: return emptyList()
        val pgnHolder = PgnHolder().apply {
            try {
                loadPgn(pgn)
            } catch (_: Exception) {
                return emptyList()
            }
        }
        if (pgnHolder.games.isEmpty()) return emptyList()
        val game = pgnHolder.games.first()
        try {
            game.loadMoveText()
        } catch (_: Exception) {
            return emptyList()
        }

        val moves = game.halfMoves
        if (moves.isEmpty()) return emptyList()

        val board = Board()
        val sampled = mutableListOf<PositionProvenanceRecord>()
        val startOffset = if (gameIndex % 2 == 0) 0 else 1
        var lastSampledPly = -(minPlySpacing + 1)

        val eco = rawGame.eco

        for ((index, move) in moves.withIndex()) {
            val ply = index + 1
            if (ply >= startOffset + 1 && ply - lastSampledPly >= minPlySpacing) {
                val rawFen = board.fen
                val canonical = normalizeEnPassant(rawFen)
                val phase = classifyPhase(board, ply)
                val turn = if (board.sideToMove == com.github.bhlangonijr.chesslib.Side.WHITE) "w" else "b"

                sampled.add(
                    PositionProvenanceRecord(
                        canonicalFen = canonical,
                        occurrenceCount = 1,
                        sampleGameId = rawGame.url ?: "game-$ply",
                        samplePlayerId = if (turn == "w") rawGame.whiteUsername ?: "White" else rawGame.blackUsername ?: "Black",
                        whitePlayer = rawGame.whiteUsername ?: "White",
                        blackPlayer = rawGame.blackUsername ?: "Black",
                        samplePly = ply,
                        sampleTimeControl = rawGame.timeControl ?: "unknown",
                        eco = eco,
                        phase = phase,
                        sideToMove = turn,
                    ),
                )
                lastSampledPly = ply
            }
            try {
                board.doMove(move)
            } catch (_: Exception) {
                break
            }
        }
        return sampled
    }

    fun assembleCorpusA(
        rawFiles: List<File>,
        targetSize: Int = 10_000,
        randomSeed: Long = 42L,
    ): List<PositionProvenanceRecord> {
        val allPositions = mutableListOf<PositionProvenanceRecord>()
        val seenFens = mutableMapOf<String, PositionProvenanceRecord>()

        var gameIdx = 0
        for (file in rawFiles) {
            val games = parseGamesFromJsonArchive(file)
            for (game in games) {
                val sampled = extractSampledPositionsFromGame(game, minPlySpacing = 9, gameIndex = gameIdx++)
                for (pos in sampled) {
                    val existing = seenFens[pos.canonicalFen]
                    if (existing != null) {
                        seenFens[pos.canonicalFen] = existing.copy(occurrenceCount = existing.occurrenceCount + 1)
                    } else {
                        seenFens[pos.canonicalFen] = pos
                        allPositions.add(pos)
                    }
                }
            }
        }

        val targetOpening = (targetSize * 0.225).toInt() // 2,250 (22.5%)
        val targetEndgame = (targetSize * 0.185).toInt() // 1,850 (18.5%)
        val targetMiddlegame = targetSize - targetOpening - targetEndgame // 5,900 (59.0%)
        val targetWhite = targetSize / 2 // 5,000
        val targetBlack = targetSize / 2 // 5,000
        val maxFamilyCount = (targetSize * 0.14).toInt() // 1,400 (<15%)

        val familyCounts = mutableMapOf<String, Int>()
        val selected = mutableListOf<PositionProvenanceRecord>()
        val selectedFens = mutableSetOf<String>()

        var curOpening = 0
        var curMiddlegame = 0
        var curEndgame = 0
        var curWhite = 0
        var curBlack = 0

        val rng = Random(randomSeed)
        val shuffled = allPositions.shuffled(rng)

        for (pos in shuffled) {
            if (selected.size >= targetSize) break
            if (selectedFens.contains(pos.canonicalFen)) continue

            val family = pos.eco.take(2).uppercase()
            val famCount = familyCounts.getOrDefault(family, 0)
            if (famCount >= maxFamilyCount) continue

            val isWhite = pos.sideToMove == "w"
            if (isWhite && curWhite >= targetWhite + 25) continue
            if (!isWhite && curBlack >= targetBlack + 25) continue

            val phaseFits = when (pos.phase) {
                GamePhase.OPENING -> curOpening < targetOpening + 100
                GamePhase.MIDDLEGAME -> curMiddlegame < targetMiddlegame + 150
                GamePhase.ENDGAME -> curEndgame < targetEndgame + 100
            }
            if (!phaseFits) continue

            selected.add(pos)
            selectedFens.add(pos.canonicalFen)
            familyCounts[family] = famCount + 1

            if (isWhite) curWhite++ else curBlack++
            when (pos.phase) {
                GamePhase.OPENING -> curOpening++
                GamePhase.MIDDLEGAME -> curMiddlegame++
                GamePhase.ENDGAME -> curEndgame++
            }
        }

        // Second pass if needed to reach exact targetSize
        if (selected.size < targetSize) {
            for (pos in shuffled) {
                if (selected.size >= targetSize) break
                if (selectedFens.contains(pos.canonicalFen)) continue

                val family = pos.eco.take(2).uppercase()
                val famCount = familyCounts.getOrDefault(family, 0)
                if (famCount >= (targetSize * 0.15).toInt()) continue

                val isWhite = pos.sideToMove == "w"
                if (isWhite && curWhite >= targetWhite + 50) continue
                if (!isWhite && curBlack >= targetBlack + 50) continue

                selected.add(pos)
                selectedFens.add(pos.canonicalFen)
                familyCounts[family] = famCount + 1

                if (isWhite) curWhite++ else curBlack++
                when (pos.phase) {
                    GamePhase.OPENING -> curOpening++
                    GamePhase.MIDDLEGAME -> curMiddlegame++
                    GamePhase.ENDGAME -> curEndgame++
                }
            }
        }

        return selected
    }

    fun assembleCorpusB1(
        rawFiles: List<File>,
        targetSize: Int = 3_000,
        randomSeed: Long = 42L,
    ): List<PositionProvenanceRecord> {
        val seenFens = mutableMapOf<String, PositionProvenanceRecord>()
        var gameIdx = 0
        for (file in rawFiles) {
            val games = parseGamesFromJsonArchive(file)
            for (game in games) {
                val sampled = extractSampledPositionsFromGame(game, minPlySpacing = 9, gameIndex = gameIdx++)
                for (pos in sampled) {
                    val existing = seenFens[pos.canonicalFen]
                    if (existing != null) {
                        seenFens[pos.canonicalFen] = existing.copy(occurrenceCount = existing.occurrenceCount + 1)
                    } else {
                        seenFens[pos.canonicalFen] = pos
                    }
                }
            }
        }

        val rng = Random(randomSeed)
        return seenFens.values.shuffled(rng).take(targetSize)
    }

    fun assembleCorpusB2(
        rawFiles: List<File>,
        targetSize: Int = 10_000,
        randomSeed: Long = 42L,
    ): List<PositionProvenanceRecord> {
        val seenFens = mutableMapOf<String, PositionProvenanceRecord>()
        var gameIdx = 0
        for (file in rawFiles) {
            val games = parseGamesFromJsonArchive(file)
            for (game in games) {
                val sampled = extractSampledPositionsFromGame(game, minPlySpacing = 9, gameIndex = gameIdx++)
                for (pos in sampled) {
                    val existing = seenFens[pos.canonicalFen]
                    if (existing != null) {
                        seenFens[pos.canonicalFen] = existing.copy(occurrenceCount = existing.occurrenceCount + 1)
                    } else {
                        seenFens[pos.canonicalFen] = pos
                    }
                }
            }
        }

        val rng = Random(randomSeed)
        return seenFens.values.shuffled(rng).take(targetSize)
    }

    fun auditCorpus(name: String, positions: List<PositionProvenanceRecord>): CorpusAuditReport {
        val total = positions.size
        val uniqueFens = positions.map { it.canonicalFen }.distinct().size

        var white = 0
        var black = 0
        var opening = 0
        var middlegame = 0
        var endgame = 0
        val ecoDist = mutableMapOf<String, Int>()
        val familyDist = mutableMapOf<String, Int>()
        var uncapturableEp = 0

        for (p in positions) {
            if (p.sideToMove == "w") white++ else black++
            when (p.phase) {
                GamePhase.OPENING -> opening++
                GamePhase.MIDDLEGAME -> middlegame++
                GamePhase.ENDGAME -> endgame++
            }
            val ecoCat = p.eco.take(1).uppercase()
            ecoDist[ecoCat] = ecoDist.getOrDefault(ecoCat, 0) + 1
            val family = p.eco.take(2).uppercase()
            familyDist[family] = familyDist.getOrDefault(family, 0) + 1

            val fields = p.canonicalFen.split(" ")
            if (fields.size >= 4 && fields[3] != "-") {
                val normalized = normalizeEnPassant(p.canonicalFen)
                if (normalized.endsWith("-")) {
                    uncapturableEp++
                }
            }
        }

        val maxFamilyEntry = familyDist.maxByOrNull { it.value }
        val maxFamilyPct = if (total > 0 && maxFamilyEntry != null) (maxFamilyEntry.value.toDouble() / total) * 100.0 else 0.0

        val whitePct = if (total > 0) (white.toDouble() / total) * 100.0 else 0.0
        val blackPct = if (total > 0) (black.toDouble() / total) * 100.0 else 0.0
        val openingPct = if (total > 0) (opening.toDouble() / total) * 100.0 else 0.0
        val middlegamePct = if (total > 0) (middlegame.toDouble() / total) * 100.0 else 0.0
        val endgamePct = if (total > 0) (endgame.toDouble() / total) * 100.0 else 0.0

        val passed = uniqueFens == total &&
            uncapturableEp == 0 &&
            whitePct in 49.0..51.0 &&
            openingPct in 20.0..25.5 &&
            middlegamePct in 54.0..61.0 &&
            endgamePct in 14.5..21.0 &&
            maxFamilyPct <= 15.0

        return CorpusAuditReport(
            corpusName = name,
            totalPositions = total,
            uniqueFens = uniqueFens,
            whiteCount = white,
            blackCount = black,
            whitePct = whitePct,
            blackPct = blackPct,
            openingCount = opening,
            middlegameCount = middlegame,
            endgameCount = endgame,
            openingPct = openingPct,
            middlegamePct = middlegamePct,
            endgamePct = endgamePct,
            ecoDistribution = ecoDist,
            maxFamilyPct = maxFamilyPct,
            maxFamilyName = maxFamilyEntry?.key ?: "none",
            uncapturableEpCount = uncapturableEp,
            allCriteriaPassed = passed,
        )
    }

    fun saveCorpusToJson(file: File, positions: List<PositionProvenanceRecord>) {
        file.parentFile.mkdirs()
        mapper.writeValue(file, positions)
    }

    fun loadCorpusFromJson(file: File): List<PositionProvenanceRecord> {
        return mapper.readValue(file)
    }
}
