package com.chessecho.research

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import java.util.Locale
import kotlin.math.abs

class ConnectivityClosureExtractor {

    fun extractAttackTerms(fen: String): Map<String, Int> =
        extractClosureTerms(fen, includeAttack = true, includeDefense = false)

    fun extractDefenseTerms(fen: String): Map<String, Int> =
        extractClosureTerms(fen, includeAttack = false, includeDefense = true)

    fun extractCombinedTerms(fen: String): Map<String, Int> =
        extractClosureTerms(fen, includeAttack = true, includeDefense = true)

    fun extractTerms(fen: String): Map<String, Int> = extractCombinedTerms(fen)

    private fun extractClosureTerms(
        fen: String,
        includeAttack: Boolean,
        includeDefense: Boolean,
    ): Map<String, Int> {
        val board = Board()
        board.loadFromFen(fen)
        val terms = mutableMapOf<String, Int>()

        val occupied = Square.values().filter { it != Square.NONE && board.getPiece(it) != Piece.NONE }
        for (source in occupied) {
            val sourcePiece = board.getPiece(source)
            for (target in occupied) {
                if (source == target) continue
                if (isAttackingSquare(board, source, target, sourcePiece)) {
                    val targetPiece = board.getPiece(target)
                    val isFriendly = (targetPiece.pieceSide == sourcePiece.pieceSide)
                    if (isFriendly && includeDefense) {
                        val token = "defense|${pieceName(sourcePiece)}|${pieceName(targetPiece)}|${squareName(target)}"
                        terms[token] = 1
                    } else if (!isFriendly && includeAttack) {
                        val token = "attack|${pieceName(sourcePiece)}|${pieceName(targetPiece)}|${squareName(target)}"
                        terms[token] = 1
                    }
                }
            }
        }
        return terms
    }

    private fun isAttackingSquare(
        board: Board,
        source: Square,
        target: Square,
        sourcePiece: Piece,
    ): Boolean {
        val deltaFile = abs(fileIndex(target) - fileIndex(source))
        val deltaRank = abs(rankIndex(target) - rankIndex(source))

        return when (sourcePiece.pieceType) {
            PieceType.PAWN -> {
                val forward = if (sourcePiece.pieceSide == Side.WHITE) 1 else -1
                val rankDiff = rankIndex(target) - rankIndex(source)
                deltaFile == 1 && rankDiff == forward
            }
            PieceType.KNIGHT -> (deltaFile == 1 && deltaRank == 2) || (deltaFile == 2 && deltaRank == 1)
            PieceType.KING -> deltaFile <= 1 && deltaRank <= 1 && (deltaFile + deltaRank > 0)
            PieceType.BISHOP -> deltaFile == deltaRank && deltaFile > 0 && isPathClear(board, source, target)
            PieceType.ROOK -> (deltaFile == 0 || deltaRank == 0) && (deltaFile + deltaRank > 0) && isPathClear(board, source, target)
            PieceType.QUEEN -> (deltaFile == deltaRank || deltaFile == 0 || deltaRank == 0) &&
                (deltaFile + deltaRank > 0) && isPathClear(board, source, target)
            PieceType.NONE -> false
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

    private fun pieceName(piece: Piece): String = piece.pieceType.value().lowercase(Locale.US)

    private fun squareName(square: Square): String = square.value().lowercase(Locale.US)

    private fun fileIndex(square: Square): Int = square.value()[0].uppercaseChar() - 'A'

    private fun rankIndex(square: Square): Int = square.value()[1].digitToInt() - 1

    private fun squareAt(file: Int, rank: Int): Square =
        Square.valueOf("${('A' + file)}${rank + 1}")
}
