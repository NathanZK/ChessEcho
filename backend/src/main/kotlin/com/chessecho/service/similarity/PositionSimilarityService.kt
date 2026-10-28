package com.chessecho.service.similarity

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.similarity.PositionInvertedIndex
import com.chessecho.similarity.PositionSimilarityScorer
import com.chessecho.similarity.PositionTokenExtractor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class SimilarPositionsResult(
    val queryFen: String,
    val candidatesEvaluated: Int,
    val results: List<SimilarPositionMatch>,
)

data class SimilarPositionMatch(
    val rank: Int,
    val score: Double,
    val positionId: UUID,
    val fen: String,
    val canonicalFen: String,
    val occurrences: List<SimilarOccurrenceDto>,
)

data class SimilarOccurrenceDto(
    val id: UUID,
    val platformGameId: String,
    val playedMove: String,
    val plyNumber: Int,
    val playerColor: String,
    val playedAt: Instant?,
    val opponentUsername: String?,
    val timeControl: String?,
    val gameResult: String?,
    val chessComUrl: String?,
)

@Service
class PositionSimilarityService(
    private val corpusProvider: PersonalPositionCorpusProvider,
    private val occurrenceRepository: PositionOccurrenceRepository,
    private val tokenExtractor: PositionTokenExtractor,
    private val scorer: PositionSimilarityScorer,
    private val urlBuilder: ChessComUrlBuilder,
    private val validator: FenPositionValidator,
    private val cache: PositionSimilarityCache,
) {
    @Transactional(readOnly = true)
    fun findSimilarPositions(
        queryFen: String,
        account: ChessAccount,
        minScore: Double = 0.0,
        limit: Int = DEFAULT_LIMIT,
    ): SimilarPositionsResult {
        val validFen = validator.validateOrthodoxFen(queryFen)
        val queryDoc = tokenExtractor.extractDocument(UUID.randomUUID(), validFen, includeReachability = false)

        val activeTurn = queryDoc.sideToMove
        require(activeTurn == "w" || activeTurn == "b") { "Invalid active turn: $activeTurn" }
        val expectedColor = if (activeTurn == "w") "WHITE" else "BLACK"

        val index = getOrBuildIndex(account) ?: return SimilarPositionsResult(validFen, 0, emptyList())

        val candidateDocs = index.queryCandidates(queryDoc.terms.keys, activeTurn = activeTurn, maxCandidates = MAX_CANDIDATES)
        if (candidateDocs.isEmpty()) {
            return SimilarPositionsResult(validFen, 0, emptyList())
        }

        val scoredMatches = scorer.score(queryDoc, candidateDocs)
        val filtered = scoredMatches.filter { it.score >= minScore }
        if (filtered.isEmpty()) {
            return SimilarPositionsResult(validFen, candidateDocs.size, emptyList())
        }

        val occurrencesByPos = loadOccurrencesForPositions(account.id, filtered.map { it.document.positionId })

        val qualifyingMatches =
            filtered.mapNotNull { match ->
                val occs = occurrencesByPos[match.document.positionId].orEmpty()
                val qualifyingOccs =
                    occs.filter { occ ->
                        occ.playerColor.equals(expectedColor, ignoreCase = true) && occ.movePlayed.isNotBlank()
                    }
                if (qualifyingOccs.isEmpty()) {
                    null
                } else {
                    match to qualifyingOccs
                }
            }

        val topMatches = qualifyingMatches.take(limit.coerceIn(1, MAX_LIMIT))

        val results =
            topMatches.mapIndexed { index, (match, occs) ->
                SimilarPositionMatch(
                    rank = index + 1,
                    score = match.score,
                    positionId = match.document.positionId,
                    fen = match.document.fen,
                    canonicalFen = match.document.canonicalFen,
                    occurrences = occs.map { mapOccurrence(it) },
                )
            }

        return SimilarPositionsResult(
            queryFen = validFen,
            candidatesEvaluated = candidateDocs.size,
            results = results,
        )
    }

    private fun getOrBuildIndex(account: ChessAccount): PositionInvertedIndex? {
        val docs = corpusProvider.loadPersonalPositions(account)
        if (docs.isEmpty()) return null

        return cache.getOrBuild(account.id) {
            val newIndex = PositionInvertedIndex()
            newIndex.index(docs)
            newIndex
        }
    }

    private fun loadOccurrencesForPositions(
        accountId: UUID,
        positionIds: List<UUID>,
    ): Map<UUID, List<PositionOccurrence>> {
        val occurrences = occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(accountId, positionIds)
        return occurrences.groupBy { it.position.id }
    }

    private fun mapOccurrence(occ: PositionOccurrence): SimilarOccurrenceDto {
        val game = occ.game
        val opponent =
            when (occ.playerColor.uppercase()) {
                "WHITE" -> game.blackUsername
                "BLACK" -> game.whiteUsername
                else -> null
            }

        val url = urlBuilder.buildExactPlyUrl(game.platformGameId, occ.plyNumber, game.timeControl)

        return SimilarOccurrenceDto(
            id = occ.id,
            platformGameId = game.platformGameId,
            playedMove = occ.movePlayed,
            plyNumber = occ.plyNumber,
            playerColor = occ.playerColor,
            playedAt = game.playedAt,
            opponentUsername = opponent,
            timeControl = game.timeControl,
            gameResult = game.result,
            chessComUrl = url,
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val MAX_CANDIDATES = 200
    }
}
