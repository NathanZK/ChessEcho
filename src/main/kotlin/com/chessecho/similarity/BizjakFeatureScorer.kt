package com.chessecho.similarity

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

enum class ScoringVariant {
    STATIC_ONLY,
    DYNAMIC_ONLY,
    COMBINED,
}

data class TacticalPuzzleDocument(
    val puzzleId: String,
    val canonicalFen: String,
    val fen: String,
    val moves: List<String>,
    val isQuery: Boolean = false,
)

data class ScoredTacticalMatch(
    val puzzleId: String,
    val canonicalFen: String,
    val score: Double,
)

/**
 * Bizjak-faithful feature encoder and BM25 scorer supporting static positions,
 * static pawn structures, dynamic general flags, and dynamic sequence tokens.
 */
class BizjakFeatureScorer(
    val variant: ScoringVariant,
) {
    fun rank(
        query: TacticalPuzzleDocument,
        candidates: Collection<TacticalPuzzleDocument>,
    ): List<ScoredTacticalMatch> {
        val filteredCandidates =
            candidates.distinctBy { it.canonicalFen }
                .filterNot { it.canonicalFen == query.canonicalFen }
                .sortedBy { it.canonicalFen }

        if (filteredCandidates.isEmpty()) return emptyList()

        val queryTerms = extractTerms(query.copy(isQuery = true)).filterValues { it > 0.0 }.keys
        val documentTerms =
            filteredCandidates.associate { candidate ->
                candidate.canonicalFen to extractTerms(candidate.copy(isQuery = false))
            }
        val documentLengths = documentTerms.mapValues { (_, terms) -> terms.values.sum() }
        val averageDocumentLength = documentLengths.values.average()

        val documentFrequencies = mutableMapOf<String, Int>()
        documentTerms.values.forEach { terms ->
            terms.filterValues { it > 0.0 }.keys.forEach { term ->
                documentFrequencies.merge(term, 1, Int::plus)
            }
        }

        return filteredCandidates.map { candidate ->
            val terms = documentTerms.getValue(candidate.canonicalFen)
            val length = documentLengths.getValue(candidate.canonicalFen)
            ScoredTacticalMatch(
                puzzleId = candidate.puzzleId,
                canonicalFen = candidate.canonicalFen,
                score =
                    bm25Score(
                        queryTerms = queryTerms,
                        documentTerms = terms,
                        documentFrequencies = documentFrequencies,
                        documentCount = filteredCandidates.size,
                        documentLength = length,
                        averageDocumentLength = averageDocumentLength,
                    ),
            )
        }.sortedWith(compareByDescending<ScoredTacticalMatch> { it.score }.thenBy { it.canonicalFen })
    }

    fun extractTerms(puzzle: TacticalPuzzleDocument): Map<String, Double> {
        val board = parseBoard(puzzle.fen)
        val terms = mutableMapOf<String, Double>()

        when (variant) {
            ScoringVariant.STATIC_ONLY -> {
                // Static positions (without reachability for query, with reachability for candidate)
                terms.putAll(extractStaticPositions(board, includeReachability = !puzzle.isQuery))
                terms.putAll(extractStaticPawns(board))
            }
            ScoringVariant.DYNAMIC_ONLY -> {
                terms.putAll(extractDynamicTokens(puzzle.fen, puzzle.moves))
            }
            ScoringVariant.COMBINED -> {
                terms.putAll(extractStaticPositions(board, includeReachability = !puzzle.isQuery))
                terms.putAll(extractStaticPawns(board))
                terms.putAll(extractDynamicTokens(puzzle.fen, puzzle.moves))
            }
        }
        return terms.toSortedMap()
    }

    private fun parseBoard(fen: String): Board {
        val tokens = fen.trim().split(Regex("\\s+"))
        require(tokens.size == 6) { "FEN must contain 6 fields: $fen" }
        val board = Board()
        try {
            board.loadFromFen(fen.trim())
            return board
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed FEN: $fen", e)
        }
    }

    internal fun extractStaticPositions(
        board: Board,
        includeReachability: Boolean,
    ): Map<String, Double> {
        val terms = mutableMapOf<String, Double>()
        val occupiedSquares = Square.values().filter { it != Square.NONE && board.getPiece(it) != Piece.NONE }

        occupiedSquares.forEach { square ->
            val piece = board.getPiece(square)
            addTerm(terms, "piece|${colorName(piece)}|${pieceTypeName(piece)}|${squareName(square)}")
        }

        if (includeReachability) {
            Side.values().forEach { side ->
                board.sideToMove = side
                board.pseudoLegalMoves().forEach { move ->
                    val target = move.to
                    if (board.getPiece(target) == Piece.NONE) {
                        val source = move.from
                        val piece = board.getPiece(source)
                        val d = chebyshevDistance(source, target)
                        val weight = 1.0 - 7.0 * d / 64.0
                        addTerm(terms, "reach|${colorName(piece)}|${pieceTypeName(piece)}|${squareName(target)}", weight)
                    }
                }
            }
        }

        occupiedSquares.forEach { source ->
            val sourcePiece = board.getPiece(source)
            occupiedSquares.asSequence()
                .filterNot { it == source }
                .filter { target -> isAttackedBy(board, target, sourcePiece.getPieceSide(), source) }
                .forEach { target ->
                    val targetPiece = board.getPiece(target)
                    val family = if (targetPiece.getPieceSide() == sourcePiece.getPieceSide()) "defense" else "attack"
                    addTerm(
                        terms,
                        "$family|${colorName(sourcePiece)}|${pieceTypeName(sourcePiece)}|" +
                            "${colorName(targetPiece)}|${pieceTypeName(targetPiece)}|${squareName(target)}",
                    )
                }
        }

        addUnobstructedRayTerms(board, occupiedSquares, terms)
        return terms
    }

    internal fun extractStaticPawns(board: Board): Map<String, Double> {
        val terms = mutableMapOf<String, Double>()
        val whitePawns = Square.values().filter { board.getPiece(it) == Piece.WHITE_PAWN }
        val blackPawns = Square.values().filter { board.getPiece(it) == Piece.BLACK_PAWN }

        // 1. Isolated pawns: I{square}
        whitePawns.forEach { sq ->
            val file = fileIndex(sq)
            val adjacentHasPawn = whitePawns.any { abs(fileIndex(it) - file) == 1 }
            if (!adjacentHasPawn) addTerm(terms, "I${squareName(sq)}")
        }
        blackPawns.forEach { sq ->
            val file = fileIndex(sq)
            val adjacentHasPawn = blackPawns.any { abs(fileIndex(it) - file) == 1 }
            if (!adjacentHasPawn) addTerm(terms, "I${squareName(sq)}")
        }

        // 2. Passed pawns & Protected passed pawns
        whitePawns.forEach { sq ->
            val file = fileIndex(sq)
            val rank = rankIndex(sq)
            val opposingAhead = blackPawns.any { abs(fileIndex(it) - file) <= 1 && rankIndex(it) > rank }
            if (!opposingAhead) {
                addTerm(terms, "F${squareName(sq)}")
                // Check if protected by friendly pawn
                val protectedByPawn =
                    whitePawns.any {
                        rankIndex(it) == rank - 1 && abs(fileIndex(it) - file) == 1
                    }
                if (protectedByPawn) {
                    addTerm(terms, "'F${squareName(sq)}")
                }
            }
        }
        blackPawns.forEach { sq ->
            val file = fileIndex(sq)
            val rank = rankIndex(sq)
            val opposingAhead = whitePawns.any { abs(fileIndex(it) - file) <= 1 && rankIndex(it) < rank }
            if (!opposingAhead) {
                addTerm(terms, "F${squareName(sq)}")
                val protectedByPawn =
                    blackPawns.any {
                        rankIndex(it) == rank + 1 && abs(fileIndex(it) - file) == 1
                    }
                if (protectedByPawn) {
                    addTerm(terms, "'F${squareName(sq)}")
                }
            }
        }

        // 3. Backward pawns: l{square}
        whitePawns.forEach { sq ->
            val file = fileIndex(sq)
            val rank = rankIndex(sq)
            val friendlyAdjBehindOrBeside = whitePawns.any { abs(fileIndex(it) - file) == 1 && rankIndex(it) <= rank }
            if (!friendlyAdjBehindOrBeside && rank < 7) {
                val advanceSquare = squareAt(file, rank + 1)
                val advanceSquareAttackedByBlackPawn =
                    blackPawns.any {
                        rankIndex(it) == rank + 2 && abs(fileIndex(it) - file) == 1
                    }
                val advanceSquareDefendedByWhitePawn =
                    whitePawns.any {
                        rankIndex(it) == rank && abs(fileIndex(it) - file) == 1
                    }
                if (advanceSquareAttackedByBlackPawn && !advanceSquareDefendedByWhitePawn) {
                    addTerm(terms, "l${squareName(sq)}")
                }
            }
        }
        blackPawns.forEach { sq ->
            val file = fileIndex(sq)
            val rank = rankIndex(sq)
            val friendlyAdjBehindOrBeside = blackPawns.any { abs(fileIndex(it) - file) == 1 && rankIndex(it) >= rank }
            if (!friendlyAdjBehindOrBeside && rank > 0) {
                val advanceSquareAttackedByWhitePawn =
                    whitePawns.any {
                        rankIndex(it) == rank - 2 && abs(fileIndex(it) - file) == 1
                    }
                val advanceSquareDefendedByBlackPawn =
                    blackPawns.any {
                        rankIndex(it) == rank && abs(fileIndex(it) - file) == 1
                    }
                if (advanceSquareAttackedByWhitePawn && !advanceSquareDefendedByBlackPawn) {
                    addTerm(terms, "l${squareName(sq)}")
                }
            }
        }

        // 4. Doubled pawns: S{file}
        for (f in 0..7) {
            val fileChar = ('a' + f).toString()
            if (whitePawns.count { fileIndex(it) == f } >= 2) addTerm(terms, "S$fileChar")
            if (blackPawns.count { fileIndex(it) == f } >= 2) addTerm(terms, "S$fileChar")
        }

        // 5. Pawn chains: w[sq1/sq2/...]
        extractChains(whitePawns, isWhite = true).forEach { chain ->
            addTerm(terms, "w[${chain.joinToString("/") { squareName(it) }}]")
        }
        extractChains(blackPawns, isWhite = false).forEach { chain ->
            addTerm(terms, "w[${chain.joinToString("/") { squareName(it) }}]")
        }

        // 6. Pawn islands: P(n) for White, p(n) for Black
        val whiteIslands = countPawnIslands(whitePawns)
        val blackIslands = countPawnIslands(blackPawns)
        addTerm(terms, "P($whiteIslands)")
        addTerm(terms, "p($blackIslands)")

        return terms
    }

    private fun extractChains(
        pawns: List<Square>,
        isWhite: Boolean,
    ): List<List<Square>> {
        val chains = mutableListOf<List<Square>>()
        val sorted = pawns.sortedBy { rankIndex(it) }
        sorted.forEach { base ->
            val currentChain = mutableListOf(base)
            var current = base
            while (true) {
                val targetRank = if (isWhite) rankIndex(current) + 1 else rankIndex(current) - 1
                val targetFile = fileIndex(current)
                val next =
                    pawns.firstOrNull {
                        rankIndex(it) == targetRank && abs(fileIndex(it) - targetFile) == 1
                    }
                if (next != null && next !in currentChain) {
                    currentChain.add(next)
                    current = next
                } else {
                    break
                }
            }
            if (currentChain.size >= 2) {
                val sortedChain = currentChain.sortedBy { rankIndex(it) }
                if (chains.none { it == sortedChain }) {
                    chains.add(sortedChain)
                }
            }
        }
        return chains
    }

    private fun countPawnIslands(pawns: List<Square>): Int {
        if (pawns.isEmpty()) return 0
        val filesWithPawns = pawns.map { fileIndex(it) }.distinct().sorted()
        var islands = 0
        var prevFile = -2
        for (f in filesWithPawns) {
            if (f != prevFile + 1) {
                islands++
            }
            prevFile = f
        }
        return islands
    }

    internal fun extractDynamicTokens(
        fen: String,
        moves: List<String>,
    ): Map<String, Double> {
        val terms = mutableMapOf<String, Double>()
        if (moves.size < 2) return terms

        val board = parseBoard(fen)

        // Moves[0] is setup move
        val setupMove = parseUciMove(board, moves[0])
        board.doMove(setupMove)

        val playerColor = board.sideToMove
        val solutionMoves = moves.drop(1)
        if (solutionMoves.isEmpty()) return terms

        val moveHistory = mutableListOf<MoveRecord>()

        for (i in solutionMoves.indices) {
            val uci = solutionMoves[i]
            val move = parseUciMove(board, uci)
            val movedPiece = board.getPiece(move.from)
            val isPlayerMove = (board.sideToMove == playerColor)
            val capturedPiece = board.getPiece(move.to)
            val wasCapture = capturedPiece != Piece.NONE

            board.doMove(move)

            val givesCheck = board.isKingAttacked
            val isCheckmate = givesCheck && board.legalMoves().isEmpty()
            val isPromotion = move.promotion != Piece.NONE

            val record =
                MoveRecord(
                    uci = uci,
                    move = move,
                    movedPiece = movedPiece,
                    capturedPiece = capturedPiece,
                    wasCapture = wasCapture,
                    isPlayerMove = isPlayerMove,
                    givesCheck = givesCheck,
                    isCheckmate = isCheckmate,
                    isPromotion = isPromotion,
                )
            moveHistory.add(record)

            // Dynamic sequence tokens for move i
            val pieceChar = pieceTypeSymbol(movedPiece.getPieceType())
            addTerm(terms, "!-$pieceChar")

            if (wasCapture) {
                val capChar = pieceTypeSymbol(capturedPiece.getPieceType())
                addTerm(terms, "!x$capChar")
            }

            // Attacks created by move i
            val destSquare = move.to
            val oppSide = if (movedPiece.getPieceSide() == Side.WHITE) Side.BLACK else Side.WHITE
            val createdAttacks = mutableListOf<String>()
            Square.values().filter { it != Square.NONE && board.getPiece(it) != Piece.NONE }.forEach { targetSq ->
                val targetPiece = board.getPiece(targetSq)
                if (targetPiece.getPieceSide() == oppSide) {
                    if (isAttackedBy(board, targetSq, movedPiece.getPieceSide(), destSquare)) {
                        val oppChar = pieceTypeSymbol(targetPiece.getPieceType())
                        createdAttacks.add("!$pieceChar>$oppChar")
                    }
                }
            }
            createdAttacks.sorted().forEach { addTerm(terms, it) }

            // Checkmate deliverer
            if (isCheckmate) {
                addTerm(terms, "!#$pieceChar")
            }
        }

        // Consecutive tokens
        for (i in 0 until moveHistory.size - 1) {
            val r1 = moveHistory[i]
            val r2 = moveHistory[i + 1]
            val p1 = pieceTypeSymbol(r1.movedPiece.getPieceType())
            val p2 = pieceTypeSymbol(r2.movedPiece.getPieceType())
            addTerm(terms, "!-$p1$p2")

            if (r1.wasCapture && r2.wasCapture) {
                val c1 = pieceTypeSymbol(r1.capturedPiece.getPieceType())
                val c2 = pieceTypeSymbol(r2.capturedPiece.getPieceType())
                addTerm(terms, "!x$c1$c2")
            }
        }

        // Checkmate pair (last 2 plies if final ply is checkmate)
        val lastRecord = moveHistory.last()
        if (lastRecord.isCheckmate && moveHistory.size >= 2) {
            val prevRecord = moveHistory[moveHistory.size - 2]
            val p1 = pieceTypeSymbol(prevRecord.movedPiece.getPieceType())
            val p2 = pieceTypeSymbol(lastRecord.movedPiece.getPieceType())
            addTerm(terms, "!#$p1$p2")
        }

        // General binary flags
        if (moveHistory.any { it.isPlayerMove && it.wasCapture }) addTerm(terms, "?px")
        if (moveHistory.any { !it.isPlayerMove && it.wasCapture }) addTerm(terms, "?ox")
        if (moveHistory.any { it.givesCheck }) addTerm(terms, "?+")
        if (moveHistory.any { it.isPromotion }) addTerm(terms, "?=")
        if (lastRecord.isCheckmate) addTerm(terms, "?#")

        // Sacrifice detection: player piece moved, then captured next ply by opponent where value(moved) > value(captured)
        for (i in 0 until moveHistory.size - 1) {
            val rPlayer = moveHistory[i]
            val rOpp = moveHistory[i + 1]
            if (rPlayer.isPlayerMove && !rOpp.isPlayerMove && rOpp.wasCapture) {
                val movedVal = pieceValue(rPlayer.movedPiece.getPieceType())
                val capturedVal = if (rPlayer.wasCapture) pieceValue(rPlayer.capturedPiece.getPieceType()) else 0
                if (movedVal > capturedVal && rOpp.move.to == rPlayer.move.to) {
                    addTerm(terms, "?S")
                    addTerm(terms, "!S${pieceTypeSymbol(rPlayer.movedPiece.getPieceType())}")
                }
            }
        }

        return terms
    }

    private fun parseUciMove(
        board: Board,
        uci: String,
    ): Move {
        val legalMove = board.legalMoves().firstOrNull { it.toString().lowercase() == uci.lowercase() }
        if (legalMove != null) return legalMove
        throw IllegalArgumentException("Illegal or unparseable move: $uci in ${board.fen}")
    }

    private data class MoveRecord(
        val uci: String,
        val move: Move,
        val movedPiece: Piece,
        val capturedPiece: Piece,
        val wasCapture: Boolean,
        val isPlayerMove: Boolean,
        val givesCheck: Boolean,
        val isCheckmate: Boolean,
        val isPromotion: Boolean,
    )

    private fun pieceValue(type: PieceType): Int =
        when (type) {
            PieceType.QUEEN -> 9
            PieceType.ROOK -> 5
            PieceType.BISHOP, PieceType.KNIGHT -> 3
            PieceType.PAWN -> 1
            else -> 0
        }

    private fun pieceTypeSymbol(type: PieceType): String =
        when (type) {
            PieceType.KING -> "K"
            PieceType.QUEEN -> "Q"
            PieceType.ROOK -> "R"
            PieceType.BISHOP -> "B"
            PieceType.KNIGHT -> "N"
            PieceType.PAWN -> "P"
            else -> "P"
        }

    private fun addUnobstructedRayTerms(
        board: Board,
        occupiedSquares: List<Square>,
        terms: MutableMap<String, Double>,
    ) {
        val whiteKing = occupiedSquares.filter { board.getPiece(it) == Piece.WHITE_KING }.toSet()
        val blackKing = occupiedSquares.filter { board.getPiece(it) == Piece.BLACK_KING }.toSet()
        if (whiteKing.size != 1 || blackKing.size != 1) return

        occupiedSquares.forEach { source ->
            val sourcePiece = board.getPiece(source)
            val pieceType = sourcePiece.getPieceType()
            if (pieceType.isSlider()) {
                rayDirections(pieceType).forEach { (fileDelta, rankDelta) ->
                    var currentFile = fileIndex(source) + fileDelta
                    var currentRank = rankIndex(source) + rankDelta
                    while (currentFile in 0..7 && currentRank in 0..7) {
                        val currentSquare = squareAt(currentFile, currentRank)
                        val pieceOnSquare = board.getPiece(currentSquare)
                        if (pieceOnSquare != Piece.NONE) {
                            addTerm(
                                terms,
                                "ray|${colorName(sourcePiece)}|${pieceTypeName(sourcePiece)}|" +
                                    "${colorName(pieceOnSquare)}|${pieceTypeName(pieceOnSquare)}|${squareName(currentSquare)}",
                            )
                            break
                        }
                        currentFile += fileDelta
                        currentRank += rankDelta
                    }
                }
            }
        }
    }

    private fun isAttackedBy(
        board: Board,
        target: Square,
        attackerSide: Side,
        sourceSquare: Square,
    ): Boolean {
        val sourcePiece = board.getPiece(sourceSquare)
        if (sourcePiece == Piece.NONE || sourcePiece.getPieceSide() != attackerSide) return false
        val pieceType = sourcePiece.getPieceType()

        val deltaFile = abs(fileIndex(target) - fileIndex(sourceSquare))
        val deltaRank = abs(rankIndex(target) - rankIndex(sourceSquare))

        return when (pieceType) {
            PieceType.PAWN -> {
                val forward = if (attackerSide == Side.WHITE) 1 else -1
                deltaFile == 1 && (rankIndex(target) - rankIndex(sourceSquare)) == forward
            }
            PieceType.KNIGHT -> (deltaFile == 1 && deltaRank == 2) || (deltaFile == 2 && deltaRank == 1)
            PieceType.KING -> deltaFile <= 1 && deltaRank <= 1 && (deltaFile + deltaRank > 0)
            PieceType.BISHOP -> deltaFile == deltaRank && deltaFile > 0 && isPathClear(board, sourceSquare, target)
            PieceType.ROOK -> (deltaFile == 0 || deltaRank == 0) && (deltaFile + deltaRank > 0) && isPathClear(board, sourceSquare, target)
            PieceType.QUEEN -> {
                (deltaFile == deltaRank || deltaFile == 0 || deltaRank == 0) &&
                    (deltaFile + deltaRank > 0) &&
                    isPathClear(board, sourceSquare, target)
            }
            else -> false
        }
    }

    private fun isPathClear(
        board: Board,
        from: Square,
        to: Square,
    ): Boolean {
        val stepFile = (fileIndex(to) - fileIndex(from)).coerceIn(-1, 1)
        val stepRank = (rankIndex(to) - rankIndex(from)).coerceIn(-1, 1)

        var currFile = fileIndex(from) + stepFile
        var currRank = rankIndex(from) + stepRank

        while (currFile != fileIndex(to) || currRank != rankIndex(to)) {
            val currSquare = squareAt(currFile, currRank)
            if (board.getPiece(currSquare) != Piece.NONE) return false
            currFile += stepFile
            currRank += stepRank
        }
        return true
    }

    private fun addTerm(
        terms: MutableMap<String, Double>,
        term: String,
        frequency: Double = 1.0,
    ) {
        terms.merge(term, frequency, Double::plus)
    }

    private fun colorName(piece: Piece): String = if (piece.getPieceSide() == Side.WHITE) "white" else "black"

    private fun pieceTypeName(piece: Piece): String = piece.getPieceType().value().lowercase(Locale.US)

    private fun squareName(square: Square): String = square.value().lowercase(Locale.US)

    private fun chebyshevDistance(
        first: Square,
        second: Square,
    ): Int = maxOf(abs(fileIndex(first) - fileIndex(second)), abs(rankIndex(first) - rankIndex(second)))

    private fun fileIndex(square: Square): Int = square.value()[0].uppercaseChar() - 'A'

    private fun rankIndex(square: Square): Int = square.value()[1].digitToInt() - 1

    private fun squareAt(
        file: Int,
        rank: Int,
    ): Square = Square.valueOf("${('A' + file)}${rank + 1}")

    private fun rayDirections(pieceType: PieceType): List<Pair<Int, Int>> {
        val rookDirections = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val bishopDirections = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        return when (pieceType) {
            PieceType.ROOK -> rookDirections
            PieceType.BISHOP -> bishopDirections
            PieceType.QUEEN -> rookDirections + bishopDirections
            else -> emptyList()
        }
    }

    private fun PieceType.isSlider(): Boolean = this == PieceType.ROOK || this == PieceType.BISHOP || this == PieceType.QUEEN

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75

        internal fun bm25Score(
            queryTerms: Set<String>,
            documentTerms: Map<String, Double>,
            documentFrequencies: Map<String, Int>,
            documentCount: Int,
            documentLength: Double,
            averageDocumentLength: Double,
        ): Double {
            if (averageDocumentLength == 0.0 || documentCount == 0) return 0.0
            return queryTerms.sorted().sumOf { term ->
                val termFrequency = documentTerms[term] ?: 0.0
                if (termFrequency <= 0.0) {
                    0.0
                } else {
                    val documentFrequency = documentFrequencies[term] ?: 0
                    val inverseDocumentFrequency =
                        ln(1.0 + (documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5))
                    val normalization = K1 * (1.0 - B + B * documentLength / averageDocumentLength)
                    val termScore =
                        inverseDocumentFrequency *
                            (termFrequency * (K1 + 1.0)) /
                            (termFrequency + normalization)
                    termScore
                }
            }
        }
    }
}
