package com.chessecho.similarity

import com.chessecho.service.ContinuationResult
import com.chessecho.service.SearchStatus
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ln

data class CandidateContinuation(
    val positionId: UUID,
    val fen: String,
    val canonicalFen: String,
    val staticRank: Int,
    val staticScore: Double,
    val continuationResult: ContinuationResult,
)

data class RankedDynamicMatch(
    val positionId: UUID,
    val fen: String,
    val canonicalFen: String,
    val dynamicScore: Double,
    val staticRank: Int,
    val staticScore: Double,
    val rank: Int,
    val continuationResult: ContinuationResult,
)

/**
 * Dynamic tactical feature extractor and BM25 scorer
 * evaluating continuations starting at ply 0.
 *
 * Grounded in the Bizjak tactical motif feature extraction methodology (exp/tactical-motif-v1).
 */
@Component
class DynamicTacticalScorer {
    fun rank(
        queryFen: String,
        queryMoves: List<String>,
        candidates: List<CandidateContinuation>,
    ): List<RankedDynamicMatch> {
        if (candidates.isEmpty()) return emptyList()

        val queryTerms = extractQueryTerms(queryFen, queryMoves)
        val candidateTokens = collectCandidateTokens(candidates)
        val completed = candidates.filter { it.continuationResult.status == SearchStatus.COMPLETED }
        val docLengths = candidateTokens.mapValues { (_, terms) -> terms.values.sum() }
        val poolSize = completed.size
        val avgDocLen = if (poolSize > 0) completed.sumOf { docLengths[it.positionId] ?: 0.0 } / poolSize else 0.0
        val docFreqs = computeDocFrequencies(completed, candidateTokens)

        val scored =
            candidates.map { cand ->
                val score =
                    calculateCandidateScore(
                        cand = cand,
                        queryTerms = queryTerms,
                        candTerms = candidateTokens[cand.positionId].orEmpty(),
                        docLen = docLengths[cand.positionId] ?: 0.0,
                        poolSize = poolSize,
                        avgDocLen = avgDocLen,
                        docFreqs = docFreqs,
                    )
                RankedDynamicMatch(
                    positionId = cand.positionId,
                    fen = cand.fen,
                    canonicalFen = cand.canonicalFen,
                    dynamicScore = score,
                    staticRank = cand.staticRank,
                    staticScore = cand.staticScore,
                    rank = 0,
                    continuationResult = cand.continuationResult,
                )
            }

        return scored
            .sortedWith(
                compareByDescending<RankedDynamicMatch> { it.dynamicScore }
                    .thenBy { it.staticRank }
                    .thenBy { it.canonicalFen },
            ).mapIndexed { index, match -> match.copy(rank = index + 1) }
    }

    private fun extractQueryTerms(
        queryFen: String,
        queryMoves: List<String>,
    ): Set<String> =
        if (queryMoves.isNotEmpty()) {
            extractDynamicTokens(queryFen, queryMoves).filterValues { it > 0.0 }.keys
        } else {
            emptySet()
        }

    private fun collectCandidateTokens(candidates: List<CandidateContinuation>): Map<UUID, Map<String, Double>> =
        candidates.associate { candidate ->
            val res = candidate.continuationResult
            val rank1Moves =
                if (res.status == SearchStatus.COMPLETED) {
                    res.principalVariations
                        .firstOrNull { it.rank == 1 }
                        ?.moves
                        .orEmpty()
                } else {
                    emptyList()
                }
            val tokens = if (rank1Moves.isNotEmpty()) extractDynamicTokens(candidate.fen, rank1Moves) else emptyMap()
            candidate.positionId to tokens
        }

    private fun computeDocFrequencies(
        completed: List<CandidateContinuation>,
        tokens: Map<UUID, Map<String, Double>>,
    ): Map<String, Int> {
        val freqs = mutableMapOf<String, Int>()
        completed.forEach { cand ->
            tokens[cand.positionId].orEmpty().filterValues { it > 0.0 }.keys.forEach { term ->
                freqs.merge(term, 1, Int::plus)
            }
        }
        return freqs
    }

    private fun calculateCandidateScore(
        cand: CandidateContinuation,
        queryTerms: Set<String>,
        candTerms: Map<String, Double>,
        docLen: Double,
        poolSize: Int,
        avgDocLen: Double,
        docFreqs: Map<String, Int>,
    ): Double =
        if (cand.continuationResult.status == SearchStatus.COMPLETED && queryTerms.isNotEmpty()) {
            bm25Score(
                queryTerms = queryTerms,
                documentTerms = candTerms,
                documentFrequencies = docFreqs,
                documentCount = poolSize,
                documentLength = docLen,
                averageDocumentLength = avgDocLen,
            )
        } else {
            0.0
        }

    fun extractDynamicTokens(
        fen: String,
        moves: List<String>,
    ): Map<String, Double> {
        val terms = mutableMapOf<String, Double>()
        if (moves.isEmpty()) return terms

        val board = parseBoard(fen)
        val playerColor = board.sideToMove
        val moveHistory = mutableListOf<MoveRecord>()

        for (uci in moves) {
            val record = applyAndRecordMove(board, uci, playerColor)
            moveHistory.add(record)
            addMoveTokens(board, record, terms)
        }

        addConsecutiveTokens(moveHistory, terms)
        addCheckmatePairTokens(moveHistory, terms)
        addGeneralFlags(moveHistory, terms)
        addSacrificeTokens(moveHistory, terms)

        return terms
    }

    private fun applyAndRecordMove(
        board: Board,
        uci: String,
        playerColor: Side,
    ): MoveRecord {
        val move = parseUciMove(board, uci)
        val movedPiece = board.getPiece(move.from)
        val isPlayerMove = (board.sideToMove == playerColor)
        val capturedPiece = board.getPiece(move.to)
        val wasCapture = capturedPiece != Piece.NONE

        board.doMove(move)

        val givesCheck = board.isKingAttacked
        val isCheckmate = givesCheck && board.legalMoves().isEmpty()
        val isPromotion = move.promotion != Piece.NONE

        return MoveRecord(
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
    }

    private fun addMoveTokens(
        board: Board,
        record: MoveRecord,
        terms: MutableMap<String, Double>,
    ) {
        val pieceChar = pieceTypeSymbol(record.movedPiece.pieceType)
        addTerm(terms, "!-$pieceChar")

        if (record.wasCapture) {
            val capChar = pieceTypeSymbol(record.capturedPiece.pieceType)
            addTerm(terms, "!x$capChar")
        }

        addCreatedAttacks(board, record.move.to, record.movedPiece, terms)

        if (record.isCheckmate) {
            addTerm(terms, "!#$pieceChar")
        }
    }

    private fun addCreatedAttacks(
        board: Board,
        destSquare: Square,
        movedPiece: Piece,
        terms: MutableMap<String, Double>,
    ) {
        val oppSide = if (movedPiece.pieceSide == Side.WHITE) Side.BLACK else Side.WHITE
        val pieceChar = pieceTypeSymbol(movedPiece.pieceType)
        val attacks = mutableListOf<String>()

        for (targetSq in Square.values()) {
            if (targetSq == Square.NONE) continue
            val targetPiece = board.getPiece(targetSq)
            if (targetPiece == Piece.NONE || targetPiece.pieceSide != oppSide) continue
            if (isAttackedBy(board, targetSq, movedPiece.pieceSide, destSquare)) {
                val oppChar = pieceTypeSymbol(targetPiece.pieceType)
                attacks.add("!$pieceChar>$oppChar")
            }
        }
        attacks.sorted().forEach { addTerm(terms, it) }
    }

    private fun addConsecutiveTokens(
        moveHistory: List<MoveRecord>,
        terms: MutableMap<String, Double>,
    ) {
        for (i in 0 until moveHistory.size - 1) {
            val r1 = moveHistory[i]
            val r2 = moveHistory[i + 1]
            val p1 = pieceTypeSymbol(r1.movedPiece.pieceType)
            val p2 = pieceTypeSymbol(r2.movedPiece.pieceType)
            addTerm(terms, "!-$p1$p2")

            if (r1.wasCapture && r2.wasCapture) {
                val c1 = pieceTypeSymbol(r1.capturedPiece.pieceType)
                val c2 = pieceTypeSymbol(r2.capturedPiece.pieceType)
                addTerm(terms, "!x$c1$c2")
            }
        }
    }

    private fun addCheckmatePairTokens(
        moveHistory: List<MoveRecord>,
        terms: MutableMap<String, Double>,
    ) {
        val lastRecord = moveHistory.lastOrNull() ?: return
        if (lastRecord.isCheckmate && moveHistory.size >= 2) {
            val prevRecord = moveHistory[moveHistory.size - 2]
            val p1 = pieceTypeSymbol(prevRecord.movedPiece.pieceType)
            val p2 = pieceTypeSymbol(lastRecord.movedPiece.pieceType)
            addTerm(terms, "!#$p1$p2")
        }
    }

    private fun addGeneralFlags(
        moveHistory: List<MoveRecord>,
        terms: MutableMap<String, Double>,
    ) {
        if (moveHistory.any { it.isPlayerMove && it.wasCapture }) addTerm(terms, "?px")
        if (moveHistory.any { !it.isPlayerMove && it.wasCapture }) addTerm(terms, "?ox")
        if (moveHistory.any { it.givesCheck }) addTerm(terms, "?+")
        if (moveHistory.any { it.isPromotion }) addTerm(terms, "?=")
        if (moveHistory.lastOrNull()?.isCheckmate == true) addTerm(terms, "?#")
    }

    private fun addSacrificeTokens(
        moveHistory: List<MoveRecord>,
        terms: MutableMap<String, Double>,
    ) {
        for (i in 0 until moveHistory.size - 1) {
            val rPlayer = moveHistory[i]
            val rOpp = moveHistory[i + 1]
            if (rPlayer.isPlayerMove && !rOpp.isPlayerMove && rOpp.wasCapture) {
                val movedVal = pieceValue(rPlayer.movedPiece.pieceType)
                val capturedVal = if (rPlayer.wasCapture) pieceValue(rPlayer.capturedPiece.pieceType) else 0
                if (movedVal > capturedVal && rOpp.move.to == rPlayer.move.to) {
                    addTerm(terms, "?S")
                    addTerm(terms, "!S${pieceTypeSymbol(rPlayer.movedPiece.pieceType)}")
                }
            }
        }
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

    private fun parseUciMove(
        board: Board,
        uci: String,
    ): Move {
        val legalMove = board.legalMoves().firstOrNull { it.toString().lowercase() == uci.lowercase() }
        if (legalMove != null) return legalMove
        throw IllegalArgumentException("Illegal or unparseable move: $uci in ${board.fen}")
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
            PieceType.PAWN -> isPawnAttack(deltaFile, deltaRank, attackerSide, target, sourceSquare)
            PieceType.KNIGHT -> isKnightAttack(deltaFile, deltaRank)
            PieceType.KING -> isKingAttack(deltaFile, deltaRank)
            PieceType.BISHOP -> isBishopAttack(deltaFile, deltaRank, board, sourceSquare, target)
            PieceType.ROOK -> isRookAttack(deltaFile, deltaRank, board, sourceSquare, target)
            PieceType.QUEEN -> isQueenAttack(deltaFile, deltaRank, board, sourceSquare, target)
            PieceType.NONE -> false
        }
    }

    private fun isKnightAttack(
        df: Int,
        dr: Int,
    ): Boolean = (df == 1 && dr == 2) || (df == 2 && dr == 1)

    private fun isKingAttack(
        df: Int,
        dr: Int,
    ): Boolean = df <= 1 && dr <= 1 && (df + dr > 0)

    private fun isBishopAttack(
        df: Int,
        dr: Int,
        board: Board,
        from: Square,
        to: Square,
    ): Boolean = df == dr && df > 0 && isPathClear(board, from, to)

    private fun isRookAttack(
        df: Int,
        dr: Int,
        board: Board,
        from: Square,
        to: Square,
    ): Boolean = (df == 0 || dr == 0) && (df + dr > 0) && isPathClear(board, from, to)

    private fun isPawnAttack(
        deltaFile: Int,
        deltaRank: Int,
        attackerSide: Side,
        target: Square,
        sourceSquare: Square,
    ): Boolean {
        val forward = if (attackerSide == Side.WHITE) 1 else -1
        return deltaFile == 1 && (rankIndex(target) - rankIndex(sourceSquare)) == forward
    }

    private fun isQueenAttack(
        deltaFile: Int,
        deltaRank: Int,
        board: Board,
        sourceSquare: Square,
        target: Square,
    ): Boolean =
        (deltaFile == deltaRank || deltaFile == 0 || deltaRank == 0) &&
            (deltaFile + deltaRank > 0) &&
            isPathClear(board, sourceSquare, target)

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

    private fun fileIndex(square: Square): Int = square.value()[0].uppercaseChar() - 'A'

    private fun rankIndex(square: Square): Int = square.value()[1].digitToInt() - 1

    private fun squareAt(
        file: Int,
        rank: Int,
    ): Square = Square.valueOf("${('A' + file)}${rank + 1}")

    private fun pieceTypeSymbol(type: PieceType): String =
        when (type) {
            PieceType.KING -> "K"
            PieceType.QUEEN -> "Q"
            PieceType.ROOK -> "R"
            PieceType.BISHOP -> "B"
            PieceType.KNIGHT -> "N"
            PieceType.PAWN -> "P"
            PieceType.NONE -> "P"
        }

    private fun pieceValue(type: PieceType): Int =
        when (type) {
            PieceType.QUEEN -> 9
            PieceType.ROOK -> 5
            PieceType.BISHOP, PieceType.KNIGHT -> 3
            PieceType.PAWN -> 1
            PieceType.KING, PieceType.NONE -> 0
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
