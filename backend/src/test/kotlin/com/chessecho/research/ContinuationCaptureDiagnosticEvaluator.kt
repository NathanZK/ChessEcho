package com.chessecho.research

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import java.util.Locale
import kotlin.math.max

class ContinuationCaptureDiagnosticEvaluator {

    private val extractor = ConnectivityClosureExtractor()

    private val captureFamilyPairs = mapOf(
        (PieceType.KNIGHT to PieceType.QUEEN) to "N_x_Q",
        (PieceType.BISHOP to PieceType.QUEEN) to "B_x_Q",
        (PieceType.ROOK to PieceType.QUEEN) to "R_x_Q",
        (PieceType.KNIGHT to PieceType.ROOK) to "N_x_R",
        (PieceType.BISHOP to PieceType.ROOK) to "B_x_R",
        (PieceType.PAWN to PieceType.QUEEN) to "P_x_Q",
    )

    fun evaluateSinglePosition(fen: String, pvMoves: List<String>): CaptureDiagnosticReport {
        val breakdown = evaluatePositions(listOf(fen to pvMoves))
        return CaptureDiagnosticReport(
            populationSize = 1,
            emptyPvCount = if (pvMoves.isEmpty()) 1 else 0,
            nxqDiagnostic = breakdown["N_x_Q"]!!,
            captureFamilyBreakdown = breakdown,
        )
    }

    fun evaluateCorpus(positionsWithPvs: List<Pair<String, List<String>>>): CaptureDiagnosticReport {
        val emptyCount = positionsWithPvs.count { it.second.isEmpty() }
        val breakdown = evaluatePositions(positionsWithPvs)
        return CaptureDiagnosticReport(
            populationSize = positionsWithPvs.size,
            emptyPvCount = emptyCount,
            nxqDiagnostic = breakdown["N_x_Q"]!!,
            captureFamilyBreakdown = breakdown,
        )
    }

    private data class IntermediateCaptureEvent(
        val ply: Int,
        val isRootPlayer: Boolean,
        val isMatchedInRoot: Boolean,
    )

    private fun evaluatePositions(
        positionsWithPvs: List<Pair<String, List<String>>>,
    ): Map<String, SingleCaptureDiagnostic> {
        val allFamilies = captureFamilyPairs.values.distinct()

        // Per family: list of positions, each having immediate events and preparatory events
        val immediateEventsPerFamily = allFamilies.associateWith { mutableListOf<List<IntermediateCaptureEvent>>() }
        val preparatoryEventsPerFamily = allFamilies.associateWith { mutableListOf<List<IntermediateCaptureEvent>>() }

        for ((fen, pvMoves) in positionsWithPvs) {
            if (pvMoves.isEmpty()) continue

            val rootAttackTerms = extractor.extractAttackTerms(fen).keys
            val board = Board()
            try {
                board.loadFromFen(fen)
            } catch (e: Exception) {
                continue
            }

            val posImmediateEvents = allFamilies.associateWith { mutableListOf<IntermediateCaptureEvent>() }
            val posPreparatoryEvents = allFamilies.associateWith { mutableListOf<IntermediateCaptureEvent>() }

            for ((ply, uci) in pvMoves.withIndex()) {
                if (uci.length < 4) break
                val fromStr = uci.substring(0, 2).uppercase(Locale.US)
                val toStr = uci.substring(2, 4).uppercase(Locale.US)

                val from = try { Square.fromValue(fromStr) } catch (_: Exception) { null }
                val to = try { Square.fromValue(toStr) } catch (_: Exception) { null }
                if (from == null || to == null || from == Square.NONE || to == Square.NONE) break

                val movingPiece = board.getPiece(from)
                val targetPiece = board.getPiece(to)

                // Check capture
                val isCapture = targetPiece != Piece.NONE && targetPiece.pieceSide != movingPiece.pieceSide
                if (isCapture) {
                    val familyKey = captureFamilyPairs[movingPiece.pieceType to targetPiece.pieceType]
                    if (familyKey != null) {
                        val sourcePieceName = movingPiece.pieceType.value().lowercase(Locale.US)
                        val targetPieceName = targetPiece.pieceType.value().lowercase(Locale.US)
                        val targetSquareName = to.value().lowercase(Locale.US)
                        val expectedToken = "attack|$sourcePieceName|$targetPieceName|$targetSquareName"

                        val matched = rootAttackTerms.contains(expectedToken)
                        val isRootPlayer = (ply % 2 == 0)

                        val event = IntermediateCaptureEvent(
                            ply = ply,
                            isRootPlayer = isRootPlayer,
                            isMatchedInRoot = matched,
                        )

                        if (ply == 0) {
                            posImmediateEvents[familyKey]!!.add(event)
                        } else {
                            posPreparatoryEvents[familyKey]!!.add(event)
                        }
                    }
                }

                // Execute move to advance board state
                try {
                    val promotion = if (uci.length >= 5) {
                        when (uci[4].lowercaseChar()) {
                            'q' -> if (board.sideToMove.name == "WHITE") Piece.WHITE_QUEEN else Piece.BLACK_QUEEN
                            'r' -> if (board.sideToMove.name == "WHITE") Piece.WHITE_ROOK else Piece.BLACK_ROOK
                            'b' -> if (board.sideToMove.name == "WHITE") Piece.WHITE_BISHOP else Piece.BLACK_BISHOP
                            'n' -> if (board.sideToMove.name == "WHITE") Piece.WHITE_KNIGHT else Piece.BLACK_KNIGHT
                            else -> Piece.NONE
                        }
                    } else Piece.NONE

                    val move = Move(from, to, promotion)
                    board.doMove(move)
                } catch (e: Exception) {
                    break
                }
            }

            for (family in allFamilies) {
                val imm = posImmediateEvents[family]!!
                if (imm.isNotEmpty()) {
                    immediateEventsPerFamily[family]!!.add(imm)
                }
                val prep = posPreparatoryEvents[family]!!
                if (prep.isNotEmpty()) {
                    preparatoryEventsPerFamily[family]!!.add(prep)
                }
            }
        }

        return allFamilies.associateWith { family ->
            val immPositions = immediateEventsPerFamily[family]!!
            val immAllEvents = immPositions.flatten()
            val immTotalEvents = immAllEvents.size
            val immMatchedEvents = immAllEvents.count { it.isMatchedInRoot }
            val immUniquePositions = immPositions.size
            val immMatchedPositions = immPositions.count { posEvents -> posEvents.any { it.isMatchedInRoot } }

            val immediateStats = ImmediateCaptureStats(
                totalEvents = immTotalEvents,
                matchedEvents = immMatchedEvents,
                eventMatchRate = if (immTotalEvents > 0) immMatchedEvents.toDouble() / immTotalEvents else 0.0,
                uniquePositions = immUniquePositions,
                matchedPositions = immMatchedPositions,
                positionMatchRate = if (immUniquePositions > 0) immMatchedPositions.toDouble() / immUniquePositions else 0.0,
            )

            val prepPositions = preparatoryEventsPerFamily[family]!!
            val prepAllEvents = prepPositions.flatten()
            val prepTotalEvents = prepAllEvents.size
            val prepMatchedEvents = prepAllEvents.count { it.isMatchedInRoot }
            val prepUniquePositions = prepPositions.size
            val prepMatchedPositions = prepPositions.count { posEvents -> posEvents.any { it.isMatchedInRoot } }
            val prepRootPlayerEvents = prepAllEvents.count { it.isRootPlayer }
            val prepMatchedRootPlayerEvents = prepAllEvents.count { it.isRootPlayer && it.isMatchedInRoot }
            val prepMeanPly = if (prepTotalEvents > 0) prepAllEvents.sumOf { it.ply }.toDouble() / prepTotalEvents else 0.0

            val preparatoryStats = PreparatoryCaptureStats(
                totalEvents = prepTotalEvents,
                matchedEvents = prepMatchedEvents,
                eventMatchRate = if (prepTotalEvents > 0) prepMatchedEvents.toDouble() / prepTotalEvents else 0.0,
                uniquePositions = prepUniquePositions,
                matchedPositions = prepMatchedPositions,
                positionMatchRate = if (prepUniquePositions > 0) prepMatchedPositions.toDouble() / prepUniquePositions else 0.0,
                rootPlayerEvents = prepRootPlayerEvents,
                matchedRootPlayerEvents = prepMatchedRootPlayerEvents,
                rootPlayerEventMatchRate = if (prepRootPlayerEvents > 0) prepMatchedRootPlayerEvents.toDouble() / prepRootPlayerEvents else 0.0,
                meanPlyOfCapture = prepMeanPly,
            )

            SingleCaptureDiagnostic(
                captureType = family,
                immediatePly0 = immediateStats,
                preparatoryPlyK = preparatoryStats,
            )
        }
    }
}
