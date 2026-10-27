package com.chessecho.similarity

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class RelationalPawnTokenExtractor : PositionTokenExtractor {
    override fun extractTerms(
        fen: String,
        includeReachability: Boolean,
    ): Map<String, Double> {
        val board = Board()
        board.loadFromFen(fen)
        val terms = mutableMapOf<String, Double>()

        extractPiecePlacements(board, terms)
        if (includeReachability) {
            extractReachability(board, terms)
        }
        extractAttacksAndDefenses(board, terms)
        extractUnobstructedRays(board, terms)
        extractPawnStructures(board, terms)

        return terms
    }

    override fun extractDocument(
        positionId: UUID,
        fen: String,
        includeReachability: Boolean,
    ): PositionDocument {
        val terms = extractTerms(fen, includeReachability)
        val canonicalFen = canonicalizeFen(fen)
        return PositionDocument(
            positionId = positionId,
            fen = fen,
            canonicalFen = canonicalFen,
            terms = terms,
        )
    }

    private fun canonicalizeFen(fen: String): String {
        val fields = fen.trim().split("\\s+".toRegex())
        return if (fields.size >= 4) {
            "${fields[0]} ${fields[1]} ${fields[2]} ${fields[3]}"
        } else {
            fen.trim()
        }
    }

    private fun extractPiecePlacements(
        board: Board,
        terms: MutableMap<String, Double>,
    ) {
        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            if (piece != Piece.NONE) {
                addTerm(terms, "piece|${colorName(piece)}|${pieceTypeName(piece)}|${squareName(square)}")
            }
        }
    }

    private fun extractReachability(
        board: Board,
        terms: MutableMap<String, Double>,
    ) {
        val originalSide = board.sideToMove
        for (side in Side.values()) {
            board.sideToMove = side
            for (move in board.pseudoLegalMoves()) {
                val target = move.to
                if (board.getPiece(target) == Piece.NONE) {
                    val source = move.from
                    val piece = board.getPiece(source)
                    val distance = chebyshevDistance(source, target)
                    val weight = 1.0 - (REACH_WEIGHT_FACTOR * distance / REACH_MAX_DISTANCE)
                    addTerm(terms, "reach|${colorName(piece)}|${pieceTypeName(piece)}|${squareName(target)}", weight)
                }
            }
        }
        board.sideToMove = originalSide
    }

    private fun extractAttacksAndDefenses(
        board: Board,
        terms: MutableMap<String, Double>,
    ) {
        val occupied = Square.values().filter { it != Square.NONE && board.getPiece(it) != Piece.NONE }
        for (source in occupied) {
            recordInteractionsFromSource(source, occupied, board, terms)
        }
    }

    private fun recordInteractionsFromSource(
        source: Square,
        occupied: List<Square>,
        board: Board,
        terms: MutableMap<String, Double>,
    ) {
        val sourcePiece = board.getPiece(source)
        for (target in occupied) {
            if (source == target) continue
            if (isAttackedBy(board, target, sourcePiece.pieceSide, source)) {
                val targetPiece = board.getPiece(target)
                val relation = if (targetPiece.pieceSide == sourcePiece.pieceSide) "defense" else "attack"
                addTerm(
                    terms,
                    "$relation|${colorName(sourcePiece)}|${pieceTypeName(sourcePiece)}|" +
                        "${colorName(targetPiece)}|${pieceTypeName(targetPiece)}|${squareName(target)}",
                )
            }
        }
    }

    private fun extractUnobstructedRays(
        board: Board,
        terms: MutableMap<String, Double>,
    ) {
        val occupied = Square.values().filter { it != Square.NONE && board.getPiece(it) != Piece.NONE }
        for (source in occupied) {
            val piece = board.getPiece(source)
            val type = piece.pieceType
            if (!type.isSlider()) continue

            for ((fileDelta, rankDelta) in rayDirections(type)) {
                traceRay(board, source, piece, fileDelta, rankDelta, terms)
            }
        }
    }

    private fun traceRay(
        board: Board,
        source: Square,
        sourcePiece: Piece,
        fileDelta: Int,
        rankDelta: Int,
        terms: MutableMap<String, Double>,
    ) {
        var file = fileIndex(source) + fileDelta
        var rank = rankIndex(source) + rankDelta
        while (file in 0..7 && rank in 0..7) {
            val square = squareAt(file, rank)
            val hitPiece = board.getPiece(square)
            if (hitPiece != Piece.NONE) {
                addTerm(
                    terms,
                    "ray|${colorName(sourcePiece)}|${pieceTypeName(sourcePiece)}|" +
                        "${colorName(hitPiece)}|${pieceTypeName(hitPiece)}|${squareName(square)}",
                )
                break
            }
            file += fileDelta
            rank += rankDelta
        }
    }

    private fun extractPawnStructures(
        board: Board,
        terms: MutableMap<String, Double>,
    ) {
        val whitePawns = Square.values().filter { board.getPiece(it) == Piece.WHITE_PAWN }
        val blackPawns = Square.values().filter { board.getPiece(it) == Piece.BLACK_PAWN }

        extractIsolatedPawns(whitePawns, terms)
        extractIsolatedPawns(blackPawns, terms)
        extractPassedPawns(whitePawns, blackPawns, isWhite = true, terms)
        extractPassedPawns(blackPawns, whitePawns, isWhite = false, terms)
        extractBackwardPawns(whitePawns, blackPawns, isWhite = true, terms)
        extractBackwardPawns(blackPawns, whitePawns, isWhite = false, terms)
        extractDoubledPawns(whitePawns, terms)
        extractDoubledPawns(blackPawns, terms)
        extractChains(whitePawns, isWhite = true, terms)
        extractChains(blackPawns, isWhite = false, terms)

        addTerm(terms, "P(${countPawnIslands(whitePawns)})")
        addTerm(terms, "p(${countPawnIslands(blackPawns)})")
    }

    private fun extractIsolatedPawns(
        pawns: List<Square>,
        terms: MutableMap<String, Double>,
    ) {
        pawns.forEach { sq ->
            val file = fileIndex(sq)
            val adjacentHasPawn = pawns.any { abs(fileIndex(it) - file) == 1 }
            if (!adjacentHasPawn) addTerm(terms, "I${squareName(sq)}")
        }
    }

    private fun extractPassedPawns(
        friendlyPawns: List<Square>,
        opposingPawns: List<Square>,
        isWhite: Boolean,
        terms: MutableMap<String, Double>,
    ) {
        friendlyPawns.forEach { sq ->
            val isPassed = isPassedSquare(sq, opposingPawns, isWhite)
            if (isPassed) {
                addTerm(terms, "F${squareName(sq)}")
                if (isProtectedPawn(sq, friendlyPawns, isWhite)) {
                    addTerm(terms, "'F${squareName(sq)}")
                }
            }
        }
    }

    private fun isPassedSquare(
        sq: Square,
        opposingPawns: List<Square>,
        isWhite: Boolean,
    ): Boolean {
        val file = fileIndex(sq)
        val rank = rankIndex(sq)
        return opposingPawns.none {
            abs(fileIndex(it) - file) <= 1 && if (isWhite) rankIndex(it) > rank else rankIndex(it) < rank
        }
    }

    private fun isProtectedPawn(
        sq: Square,
        friendlyPawns: List<Square>,
        isWhite: Boolean,
    ): Boolean {
        val rank = rankIndex(sq)
        val file = fileIndex(sq)
        val protectedRank = if (isWhite) rank - 1 else rank + 1
        return friendlyPawns.any { rankIndex(it) == protectedRank && abs(fileIndex(it) - file) == 1 }
    }

    private fun extractBackwardPawns(
        friendlyPawns: List<Square>,
        opposingPawns: List<Square>,
        isWhite: Boolean,
        terms: MutableMap<String, Double>,
    ) {
        friendlyPawns.forEach { sq ->
            if (isBackwardPawn(sq, friendlyPawns, opposingPawns, isWhite)) {
                addTerm(terms, "l${squareName(sq)}")
            }
        }
    }

    private fun isBackwardPawn(
        sq: Square,
        friendlyPawns: List<Square>,
        opposingPawns: List<Square>,
        isWhite: Boolean,
    ): Boolean {
        val file = fileIndex(sq)
        val rank = rankIndex(sq)
        val hasNeighborBehind =
            friendlyPawns.any {
                abs(fileIndex(it) - file) == 1 && if (isWhite) rankIndex(it) <= rank else rankIndex(it) >= rank
            }
        if (hasNeighborBehind) return false
        val isBoundaryRank = if (isWhite) rank >= 7 else rank <= 0
        if (isBoundaryRank) return false
        val advanceOpposingRank = if (isWhite) rank + 2 else rank - 2
        val advanceAttacked = opposingPawns.any { rankIndex(it) == advanceOpposingRank && abs(fileIndex(it) - file) == 1 }
        val advanceDefended = friendlyPawns.any { rankIndex(it) == rank && abs(fileIndex(it) - file) == 1 }
        return advanceAttacked && !advanceDefended
    }

    private fun extractDoubledPawns(
        pawns: List<Square>,
        terms: MutableMap<String, Double>,
    ) {
        for (f in 0..7) {
            val fileChar = ('a' + f).toString()
            if (pawns.count { fileIndex(it) == f } >= 2) {
                addTerm(terms, "S$fileChar")
            }
        }
    }

    private fun extractChains(
        pawns: List<Square>,
        isWhite: Boolean,
        terms: MutableMap<String, Double>,
    ) {
        val sorted = pawns.sortedBy { rankIndex(it) }
        val chains = mutableListOf<List<Square>>()
        for (base in sorted) {
            val chain = buildChainFromBase(base, pawns, isWhite)
            if (chain.size >= 2) {
                val sortedChain = chain.sortedBy { rankIndex(it) }
                if (chains.none { it == sortedChain }) {
                    chains.add(sortedChain)
                }
            }
        }
        chains.forEach { chain ->
            addTerm(terms, "w[${chain.joinToString("/") { squareName(it) }}]")
        }
    }

    private fun buildChainFromBase(
        base: Square,
        pawns: List<Square>,
        isWhite: Boolean,
    ): List<Square> {
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
        return currentChain
    }

    private fun countPawnIslands(pawns: List<Square>): Int {
        if (pawns.isEmpty()) return 0
        val filesWithPawns = pawns.map { fileIndex(it) }.distinct().sorted()
        var islands = 0
        var prevFile = -2
        for (file in filesWithPawns) {
            if (file != prevFile + 1) {
                islands++
            }
            prevFile = file
        }
        return islands
    }

    private fun isAttackedBy(
        board: Board,
        target: Square,
        attackerSide: Side,
        sourceSquare: Square,
    ): Boolean {
        val sourcePiece = board.getPiece(sourceSquare)
        if (sourcePiece == Piece.NONE || sourcePiece.pieceSide != attackerSide) return false
        val pieceType = sourcePiece.pieceType

        val deltaFile = abs(fileIndex(target) - fileIndex(sourceSquare))
        val deltaRank = abs(rankIndex(target) - rankIndex(sourceSquare))

        return when (pieceType) {
            PieceType.PAWN -> isPawnAttacking(attackerSide, deltaFile, rankIndex(target) - rankIndex(sourceSquare))
            PieceType.KNIGHT -> isKnightAttacking(deltaFile, deltaRank)
            PieceType.KING -> isKingAttacking(deltaFile, deltaRank)
            PieceType.BISHOP -> isBishopAttacking(board, sourceSquare, target, deltaFile, deltaRank)
            PieceType.ROOK -> isRookAttacking(board, sourceSquare, target, deltaFile, deltaRank)
            PieceType.QUEEN -> isQueenAttacking(board, sourceSquare, target, deltaFile, deltaRank)
            PieceType.NONE -> false
        }
    }

    private fun isPawnAttacking(
        attackerSide: Side,
        deltaFile: Int,
        rankDiff: Int,
    ): Boolean {
        val forward = if (attackerSide == Side.WHITE) 1 else -1
        return deltaFile == 1 && rankDiff == forward
    }

    private fun isKnightAttacking(
        deltaFile: Int,
        deltaRank: Int,
    ): Boolean = (deltaFile == 1 && deltaRank == 2) || (deltaFile == 2 && deltaRank == 1)

    private fun isKingAttacking(
        deltaFile: Int,
        deltaRank: Int,
    ): Boolean = deltaFile <= 1 && deltaRank <= 1 && (deltaFile + deltaRank > 0)

    private fun isBishopAttacking(
        board: Board,
        from: Square,
        to: Square,
        deltaFile: Int,
        deltaRank: Int,
    ): Boolean = deltaFile == deltaRank && deltaFile > 0 && isPathClear(board, from, to)

    private fun isRookAttacking(
        board: Board,
        from: Square,
        to: Square,
        deltaFile: Int,
        deltaRank: Int,
    ): Boolean = (deltaFile == 0 || deltaRank == 0) && (deltaFile + deltaRank > 0) && isPathClear(board, from, to)

    private fun isQueenAttacking(
        board: Board,
        from: Square,
        to: Square,
        deltaFile: Int,
        deltaRank: Int,
    ): Boolean =
        (deltaFile == deltaRank || deltaFile == 0 || deltaRank == 0) &&
            (deltaFile + deltaRank > 0) &&
            isPathClear(board, from, to)

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

    private fun colorName(piece: Piece): String = if (piece.pieceSide == Side.WHITE) "white" else "black"

    private fun pieceTypeName(piece: Piece): String = piece.pieceType.value().lowercase(Locale.US)

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

    private fun rayDirections(pieceType: PieceType): List<Pair<Int, Int>> =
        when (pieceType) {
            PieceType.ROOK -> ROOK_DIRECTIONS
            PieceType.BISHOP -> BISHOP_DIRECTIONS
            PieceType.QUEEN -> QUEEN_DIRECTIONS
            PieceType.PAWN, PieceType.KNIGHT, PieceType.KING, PieceType.NONE -> emptyList()
        }

    private fun PieceType.isSlider(): Boolean = this == PieceType.ROOK || this == PieceType.BISHOP || this == PieceType.QUEEN

    companion object {
        private const val REACH_WEIGHT_FACTOR = 7.0
        private const val REACH_MAX_DISTANCE = 64.0
        private val ROOK_DIRECTIONS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val BISHOP_DIRECTIONS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        private val QUEEN_DIRECTIONS = ROOK_DIRECTIONS + BISHOP_DIRECTIONS
    }
}
