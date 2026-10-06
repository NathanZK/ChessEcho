package com.chessecho.similarity

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import kotlin.math.abs
import kotlin.math.ln

data class RankedFenPosition(
    val canonicalFen: String,
    val score: Double,
)

/**
 * Ganguly-style relational BM25 for a bounded collection of orthodox positions.
 *
 * This is a pure core component: it does not retain an index or depend on application services.
 */
class FenPositionSimilarity {
    fun rank(
        queryFen: String,
        candidateFens: Collection<String>,
    ): List<RankedFenPosition> {
        val query = parsePosition(queryFen, "query")
        val candidates =
            candidateFens.mapIndexed { index, fen ->
                parsePosition(fen, "candidate[$index]")
            }.distinctBy { it.canonicalFen }
                .filterNot { it.canonicalFen == query.canonicalFen }
                .sortedBy { it.canonicalFen }

        if (candidates.isEmpty()) return emptyList()

        val queryTerms = extractTerms(query.board, includeReachability = false).keys
        val documentTerms =
            candidates.associate { candidate ->
                candidate.canonicalFen to extractTerms(candidate.board, includeReachability = true)
            }
        val documentLengths = documentTerms.mapValues { (_, terms) -> terms.values.sum() }
        val averageDocumentLength = documentLengths.values.average()

        val documentFrequencies = mutableMapOf<String, Int>()
        documentTerms.values.forEach { terms ->
            terms.filterValues { it > 0.0 }.keys.forEach { term ->
                documentFrequencies.merge(term, 1, Int::plus)
            }
        }

        return candidates.map { candidate ->
            val terms = documentTerms.getValue(candidate.canonicalFen)
            val length = documentLengths.getValue(candidate.canonicalFen)
            RankedFenPosition(
                canonicalFen = candidate.canonicalFen,
                score =
                    bm25Score(
                        queryTerms = queryTerms,
                        documentTerms = terms,
                        documentFrequencies = documentFrequencies,
                        documentCount = candidates.size,
                        documentLength = length,
                        averageDocumentLength = averageDocumentLength,
                    ),
            )
        }.sortedWith(compareByDescending<RankedFenPosition> { it.score }.thenBy { it.canonicalFen })
    }

    internal fun extractTerms(
        fen: String,
        includeReachability: Boolean,
    ): Map<String, Double> {
        val position = parsePosition(fen, "position")
        return extractTerms(position.board, includeReachability)
    }

    private fun extractTerms(
        board: Board,
        includeReachability: Boolean,
    ): Map<String, Double> {
        val terms = mutableMapOf<String, Double>()
        val occupiedSquares = Square.values().filter { board.getPiece(it) != Piece.NONE }

        occupiedSquares.forEach { square ->
            val piece = board.getPiece(square)
            addTerm(terms, pieceTerm(piece, square))
        }

        if (includeReachability) {
            addReachabilityTerms(board, terms)
        }

        occupiedSquares.forEach { source ->
            val sourcePiece = board.getPiece(source)
            occupiedSquares.asSequence()
                .filterNot { it == source }
                .filter { target -> isAttackedBy(board, target, sourcePiece.getPieceSide(), source) }
                .forEach { target ->
                    val targetPiece = board.getPiece(target)
                    val family =
                        if (targetPiece.getPieceSide() == sourcePiece.getPieceSide()) {
                            "defense"
                        } else {
                            "attack"
                        }
                    addTerm(
                        terms,
                        relationTerm(family, sourcePiece, targetPiece, target),
                    )
                }
        }

        addUnobstructedRayTerms(board, occupiedSquares, terms)
        return terms.toSortedMap()
    }

    private fun addReachabilityTerms(
        board: Board,
        terms: MutableMap<String, Double>,
    ) {
        // #522 indexes empty one-move destinations: w=1-7*d/64, with Chebyshev d and no source-square term.
        // Pseudo-legal ordinary moves for each color are a structural reachability adaptation, not move legality.
        Side.values().forEach { side ->
            board.sideToMove = side
            board.pseudoLegalMoves()
                .filterNot { move ->
                    board.getPiece(move.from).getPieceType() == PieceType.KING &&
                        abs(fileIndex(move.from) - fileIndex(move.to)) == 2
                }.distinctBy { it.from to it.to }
                .forEach moveLoop@{ move ->
                    if (board.getPiece(move.to) != Piece.NONE) return@moveLoop

                    val piece = board.getPiece(move.from)
                    if (piece == Piece.NONE) return@moveLoop

                    val distance = chebyshevDistance(move.from, move.to)
                    val weight = 1.0 - (7.0 * distance / 64.0)
                    addTerm(
                        terms,
                        "reach|${colorName(piece)}|${pieceTypeName(piece)}|${squareName(move.to)}",
                        weight,
                    )
                }
        }
    }

    private fun addUnobstructedRayTerms(
        board: Board,
        occupiedSquares: List<Square>,
        terms: MutableMap<String, Double>,
    ) {
        // #522's selected V1 contract uses clear rays, not Ganguly's X-rays through intervening pieces.
        occupiedSquares.forEach { source ->
            val slider = board.getPiece(source)
            if (!slider.getPieceType().isSlider()) return@forEach

            rayDirections(slider.getPieceType()).forEach { (fileStep, rankStep) ->
                var file = fileIndex(source) + fileStep
                var rank = rankIndex(source) + rankStep
                while (file in 0..7 && rank in 0..7) {
                    val target = squareAt(file, rank)
                    val targetPiece = board.getPiece(target)
                    if (targetPiece != Piece.NONE) {
                        if (targetPiece.getPieceSide() != slider.getPieceSide()) {
                            addTerm(terms, relationTerm("ray", slider, targetPiece, target))
                        }
                        break
                    }
                    file += fileStep
                    rank += rankStep
                }
            }
        }
    }

    private fun isAttackedBy(
        board: Board,
        target: Square,
        attackerSide: Side,
        attackerSquare: Square,
    ): Boolean = (board.squareAttackedBy(target, attackerSide) and attackerSquare.bitboard) != 0L

    private fun parsePosition(
        fen: String,
        inputName: String,
    ): ParsedPosition {
        fun invalid(
            reason: String,
            cause: Throwable? = null,
        ): Nothing {
            throw IllegalArgumentException("Invalid $inputName FEN: $reason", cause)
        }

        val fields = fen.trim().split(WHITESPACE)
        if (fields.size != 6) invalid("expected exactly six fields")
        validateFenFields(fields, ::invalid)

        val board = Board()
        try {
            board.loadFromFen(fields.joinToString(" "))
        } catch (exception: RuntimeException) {
            invalid("chesslib could not parse the position", exception)
        }

        validatePosition(board, fields, ::invalid)
        val canonicalFen = board.fen.split(' ').take(4).joinToString(" ")
        return ParsedPosition(board, canonicalFen)
    }

    private fun validateFenFields(
        fields: List<String>,
        invalid: (String, Throwable?) -> Nothing,
    ) {
        val ranks = fields[0].split('/')
        if (ranks.size != 8) invalid("piece placement must contain eight ranks", null)
        val allowedPieces = "pnbrqkPNBRQK"
        ranks.forEach { rank ->
            var width = 0
            rank.forEach { symbol ->
                when {
                    symbol in '1'..'8' -> width += symbol.digitToInt()
                    symbol in allowedPieces -> width += 1
                    else -> invalid("piece placement contains an invalid symbol", null)
                }
            }
            if (width != 8) invalid("each piece-placement rank must describe eight squares", null)
        }
        if (fields[1] !in setOf("w", "b")) invalid("active color must be 'w' or 'b'", null)
        if (fields[2] != "-" &&
            (fields[2].any { it !in "KQkq" } || fields[2].toSet().size != fields[2].length)
        ) {
            invalid("castling rights must be '-', K, Q, k, q, or a non-duplicated combination", null)
        }
        if (fields[3] != "-" && !EN_PASSANT.matches(fields[3])) {
            invalid("en-passant target must be '-' or a lowercase square on rank 3 or 6", null)
        }
        if (!COUNTER.matches(fields[4]) || fields[4].toIntOrNull() == null) {
            invalid("halfmove clock must be a nonnegative integer", null)
        }
        if (!COUNTER.matches(fields[5]) || fields[5].toIntOrNull()?.let { it > 0 } != true) {
            invalid("fullmove number must be a positive integer", null)
        }
    }

    private fun validatePosition(
        board: Board,
        fields: List<String>,
        invalid: (String, Throwable?) -> Nothing,
    ) {
        val pieces = Square.values().associateWith { board.getPiece(it) }
        val whiteKings = pieces.filterValues { it == Piece.WHITE_KING }.keys
        val blackKings = pieces.filterValues { it == Piece.BLACK_KING }.keys
        if (whiteKings.size != 1 || blackKings.size != 1) invalid("position must contain exactly one king per side", null)

        Side.values().forEach { side ->
            val sidePieces = pieces.values.filter { it != Piece.NONE && it.getPieceSide() == side }
            if (sidePieces.size > 16) invalid("a side cannot have more than 16 pieces", null)
            if (sidePieces.count { it.getPieceType() == PieceType.PAWN } > 8) {
                invalid("a side cannot have more than eight pawns", null)
            }
        }
        if (pieces.any { (square, piece) ->
                piece.getPieceType() == PieceType.PAWN && rankIndex(square) in setOf(0, 7)
            }
        ) {
            invalid("pawns cannot occupy the first or eighth rank", null)
        }

        val kings = listOf(whiteKings.single(), blackKings.single())
        if (chebyshevDistance(kings[0], kings[1]) <= 1) invalid("kings cannot be adjacent", null)

        val activeSide = if (fields[1] == "w") Side.WHITE else Side.BLACK
        if (isSquareAttacked(board, kingsFor(activeSide.flip(), whiteKings, blackKings), activeSide)) {
            invalid("the side that just moved cannot have left its king in check", null)
        }

        validateCastlingRights(pieces, fields[2]) { reason, cause ->
            invalid(reason, cause)
        }
        validateEnPassant(pieces, fields[1], fields[3]) { reason, cause ->
            invalid(reason, cause)
        }
    }

    private fun validateCastlingRights(
        pieces: Map<Square, Piece>,
        rights: String,
        invalid: (String, Throwable?) -> Nothing,
    ) {
        val required =
            mapOf(
                'K' to (Square.E1 to Piece.WHITE_KING),
                'Q' to (Square.E1 to Piece.WHITE_KING),
                'k' to (Square.E8 to Piece.BLACK_KING),
                'q' to (Square.E8 to Piece.BLACK_KING),
            )
        rights.filter { it != '-' }.forEach { right ->
            val (kingSquare, kingPiece) = required.getValue(right)
            val rookSquare =
                when (right) {
                    'K' -> Square.H1
                    'Q' -> Square.A1
                    'k' -> Square.H8
                    else -> Square.A8
                }
            val rookPiece = if (right.isUpperCase()) Piece.WHITE_ROOK else Piece.BLACK_ROOK
            if (pieces[kingSquare] != kingPiece || pieces[rookSquare] != rookPiece) {
                invalid("castling right '$right' does not match its king and rook", null)
            }
        }
    }

    private fun validateEnPassant(
        pieces: Map<Square, Piece>,
        activeColor: String,
        targetName: String,
        invalid: (String, Throwable?) -> Nothing,
    ) {
        if (targetName == "-") return
        val target = Square.valueOf(targetName.uppercase())
        val expectedRank = if (activeColor == "w") 5 else 2
        if (rankIndex(target) != expectedRank || pieces[target] != Piece.NONE) {
            invalid("en-passant target is inconsistent with the active color or occupied", null)
        }
        val pawnSquare = squareAt(fileIndex(target), rankIndex(target) + if (activeColor == "w") -1 else 1)
        val pawn = if (activeColor == "w") Piece.BLACK_PAWN else Piece.WHITE_PAWN
        if (pieces[pawnSquare] != pawn) invalid("en-passant target has no pawn that just advanced", null)
        val originSquare = squareAt(fileIndex(target), rankIndex(target) + if (activeColor == "w") 1 else -1)
        if (pieces[originSquare] != Piece.NONE) invalid("en-passant pawn origin square must be empty", null)
    }

    private fun isSquareAttacked(
        board: Board,
        square: Square,
        attackerSide: Side,
    ): Boolean = board.squareAttackedBy(square, attackerSide) != 0L

    private fun kingsFor(
        side: Side,
        whiteKing: Set<Square>,
        blackKing: Set<Square>,
    ): Square = if (side == Side.WHITE) whiteKing.single() else blackKing.single()

    private fun relationTerm(
        family: String,
        source: Piece,
        target: Piece,
        targetSquare: Square,
    ): String =
        "$family|${colorName(source)}|${pieceTypeName(source)}|${colorName(target)}|" +
            "${pieceTypeName(target)}|${squareName(targetSquare)}"

    private fun pieceTerm(
        piece: Piece,
        square: Square,
    ): String = "piece|${colorName(piece)}|${pieceTypeName(piece)}|${squareName(square)}"

    private fun addTerm(
        terms: MutableMap<String, Double>,
        term: String,
        frequency: Double = 1.0,
    ) {
        terms.merge(term, frequency, Double::plus)
    }

    private fun colorName(piece: Piece): String = if (piece.getPieceSide() == Side.WHITE) "white" else "black"

    private fun pieceTypeName(piece: Piece): String = piece.getPieceType().value().lowercase()

    private fun squareName(square: Square): String = square.value().lowercase()

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

    private data class ParsedPosition(
        val board: Board,
        val canonicalFen: String,
    )

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val EN_PASSANT = Regex("[a-h][36]")
        private val COUNTER = Regex("\\d+")

        internal fun bm25Score(
            queryTerms: Set<String>,
            documentTerms: Map<String, Double>,
            documentFrequencies: Map<String, Int>,
            documentCount: Int,
            documentLength: Double,
            averageDocumentLength: Double,
        ): Double {
            require(documentCount >= 0) { "BM25 document count cannot be negative" }
            require(documentLength.isFinite() && documentLength >= 0.0) { "BM25 document length must be finite and nonnegative" }
            require(averageDocumentLength.isFinite() && averageDocumentLength >= 0.0) {
                "BM25 average document length must be finite and nonnegative"
            }
            if (averageDocumentLength == 0.0 || documentCount == 0) return 0.0
            return queryTerms.sorted().sumOf { term ->
                val termFrequency = documentTerms[term] ?: 0.0
                require(termFrequency.isFinite() && termFrequency >= 0.0) {
                    "BM25 term frequency must be finite and nonnegative"
                }
                if (termFrequency <= 0.0) {
                    0.0
                } else {
                    val documentFrequency = documentFrequencies[term] ?: 0
                    require(documentFrequency in 0..documentCount) {
                        "BM25 document frequency must be within the document count"
                    }
                    val inverseDocumentFrequency =
                        ln(1.0 + (documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5))
                    val normalization = K1 * (1.0 - B + B * documentLength / averageDocumentLength)
                    val termScore =
                        inverseDocumentFrequency *
                            (termFrequency * (K1 + 1.0)) /
                            (termFrequency + normalization)
                    require(termScore.isFinite()) { "BM25 term score must be finite" }
                    termScore
                }
            }.also { score ->
                require(score.isFinite()) { "BM25 score must be finite" }
            }
        }

        private const val K1 = 1.2
        private const val B = 0.75
    }
}
