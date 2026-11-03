package com.chessecho.service.similarity

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.service.BatchContinuationResult
import com.chessecho.service.ContinuationResult
import com.chessecho.service.DynamicAnalysisOptions
import com.chessecho.service.GameOutcomeNormalizer
import com.chessecho.service.PrincipalVariation
import com.chessecho.service.SearchStatus
import com.chessecho.service.StockfishService
import com.chessecho.similarity.CandidateContinuation
import com.chessecho.similarity.DynamicTacticalScorer
import com.chessecho.similarity.PositionInvertedIndex
import com.chessecho.similarity.PositionSimilarityScorer
import com.chessecho.similarity.PositionTokenExtractor
import com.chessecho.similarity.RankedPositionMatch
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class SimilarPositionsResult(
    val queryFen: String,
    val candidatesEvaluated: Int,
    val shortlistedCandidatesCount: Int,
    val engineCompletedCount: Int,
    val dynamicSimilarityApplied: Boolean,
    val fallbackReason: String?,
    val queryStatus: SearchStatus?,
    val queryAchievedDepth: Int,
    val queryPrincipalVariations: List<PrincipalVariation>,
    val results: List<SimilarPositionMatch>,
)

data class SimilarPositionMatch(
    val rank: Int,
    val score: Double,
    val staticScore: Double,
    val dynamicScore: Double,
    val positionId: UUID,
    val fen: String,
    val canonicalFen: String,
    val searchStatus: SearchStatus?,
    val achievedDepth: Int,
    val principalVariations: List<PrincipalVariation>,
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
    private val stockfishService: StockfishService,
    private val concurrencyManager: SimilarityConcurrencyManager,
    private val dynamicScorer: DynamicTacticalScorer,
    private val gameOutcomeNormalizer: GameOutcomeNormalizer,
) {
    @Transactional(readOnly = true)
    fun findSimilarPositions(
        queryFen: String,
        account: ChessAccount,
        minScore: Double = 0.0,
        limit: Int = DEFAULT_LIMIT,
        depth: Int = DEFAULT_DEPTH,
        multiPv: Int = DEFAULT_MULTI_PV,
    ): SimilarPositionsResult {
        val validFen = validator.validateOrthodoxFen(queryFen)
        val queryDoc = tokenExtractor.extractDocument(UUID.randomUUID(), validFen, includeReachability = false)

        val activeTurn = queryDoc.sideToMove
        require(activeTurn == "w" || activeTurn == "b") { "Invalid active turn: $activeTurn" }
        val expectedColor = if (activeTurn == "w") "WHITE" else "BLACK"

        val index = getOrBuildIndex(account) ?: return emptyResult(validFen)

        val candidateDocs = index.queryCandidates(queryDoc.terms.keys, activeTurn = activeTurn, maxCandidates = MAX_CANDIDATES)
        if (candidateDocs.isEmpty()) {
            return emptyResult(validFen)
        }

        val scoredMatches = scorer.score(queryDoc, candidateDocs)
        val filtered = scoredMatches.filter { it.score >= minScore }
        if (filtered.isEmpty()) {
            return emptyResult(validFen)
        }

        val qualifyingMatches = qualifyCandidates(account, filtered, activeTurn, expectedColor)
        if (qualifyingMatches.isEmpty()) {
            return emptyResult(validFen)
        }

        val shortlistedMatches = qualifyingMatches.take(MAX_ENGINE_SHORTLIST)
        val candidatePairs =
            shortlistedMatches.map { (match, _) ->
                match.document.positionId to match.document.fen
            }

        return concurrencyManager.withPermitOrFallback(
            onPermitAcquired = {
                executeEngineBatch(validFen, shortlistedMatches, candidatePairs, depth, multiPv, limit)
            },
            onCapacityExceeded = {
                buildStaticFallbackResult(
                    queryFen = validFen,
                    shortlistedMatches = shortlistedMatches,
                    fallbackReason = "CAPACITY_EXCEEDED",
                    limit = limit,
                    candidatesEvaluated = 0,
                    engineCompletedCount = 0,
                    queryResult = null,
                    candidateResults = emptyMap(),
                )
            },
        )
    }

    private fun qualifyCandidates(
        account: ChessAccount,
        filtered: List<RankedPositionMatch>,
        activeTurn: String,
        expectedColor: String,
    ): List<Pair<RankedPositionMatch, List<PositionOccurrence>>> {
        val occurrencesByPos = loadOccurrencesForPositions(account.id, filtered.map { it.document.positionId })
        return filtered.mapNotNull { match ->
            if (match.document.sideToMove != activeTurn) {
                return@mapNotNull null
            }
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
    }

    private fun executeEngineBatch(
        validFen: String,
        shortlistedMatches: List<Pair<RankedPositionMatch, List<PositionOccurrence>>>,
        candidatePairs: List<Pair<UUID, String>>,
        depth: Int,
        multiPv: Int,
        limit: Int,
    ): SimilarPositionsResult {
        val batchResult =
            stockfishService.batchResolveContinuations(
                queryFen = validFen,
                candidates = candidatePairs,
                options = DynamicAnalysisOptions(depth = depth, multiPv = multiPv),
            )

        return if (batchResult.fallbackReason != null) {
            buildStaticFallbackResult(
                queryFen = validFen,
                shortlistedMatches = shortlistedMatches,
                fallbackReason = batchResult.fallbackReason,
                limit = limit,
                candidatesEvaluated = batchResult.engineAttemptedCount,
                engineCompletedCount = batchResult.engineCompletedCount,
                queryResult = batchResult.queryResult,
                candidateResults = batchResult.candidateResults,
            )
        } else {
            buildDynamicRankedResult(validFen, shortlistedMatches, batchResult, limit)
        }
    }

    private fun buildDynamicRankedResult(
        validFen: String,
        shortlistedMatches: List<Pair<RankedPositionMatch, List<PositionOccurrence>>>,
        batchResult: BatchContinuationResult,
        limit: Int,
    ): SimilarPositionsResult {
        val queryRank1Moves =
            batchResult.queryResult.principalVariations
                .firstOrNull { it.rank == 1 }
                ?.moves
                .orEmpty()

        val candidateContinuations =
            shortlistedMatches.mapIndexed { index, (match, _) ->
                val candRes =
                    batchResult.candidateResults[match.document.positionId]
                        ?: ContinuationResult(SearchStatus.FAILED, 0, emptyList())
                CandidateContinuation(
                    positionId = match.document.positionId,
                    fen = match.document.fen,
                    canonicalFen = match.document.canonicalFen,
                    staticRank = index + 1,
                    staticScore = match.score,
                    continuationResult = candRes,
                )
            }

        val rankedMatches =
            dynamicScorer.rank(
                queryFen = validFen,
                queryMoves = queryRank1Moves,
                candidates = candidateContinuations,
            )

        val occsByPos = shortlistedMatches.associate { (m, occs) -> m.document.positionId to occs }
        val topRanked = rankedMatches.take(limit.coerceIn(1, MAX_LIMIT))

        val results =
            topRanked.map { ranked ->
                val occs = occsByPos[ranked.positionId].orEmpty()
                SimilarPositionMatch(
                    rank = ranked.rank,
                    score = ranked.dynamicScore,
                    staticScore = ranked.staticScore,
                    dynamicScore = ranked.dynamicScore,
                    positionId = ranked.positionId,
                    fen = ranked.fen,
                    canonicalFen = ranked.canonicalFen,
                    searchStatus = ranked.continuationResult.status,
                    achievedDepth = ranked.continuationResult.achievedDepth,
                    principalVariations = ranked.continuationResult.principalVariations,
                    occurrences = occs.map { mapOccurrence(it) },
                )
            }

        return SimilarPositionsResult(
            queryFen = validFen,
            candidatesEvaluated = batchResult.engineAttemptedCount,
            shortlistedCandidatesCount = shortlistedMatches.size,
            engineCompletedCount = batchResult.engineCompletedCount,
            dynamicSimilarityApplied = true,
            fallbackReason = null,
            queryStatus = batchResult.queryResult.status,
            queryAchievedDepth = batchResult.queryResult.achievedDepth,
            queryPrincipalVariations = batchResult.queryResult.principalVariations,
            results = results,
        )
    }

    private fun emptyResult(queryFen: String): SimilarPositionsResult =
        SimilarPositionsResult(
            queryFen = queryFen,
            candidatesEvaluated = 0,
            shortlistedCandidatesCount = 0,
            engineCompletedCount = 0,
            dynamicSimilarityApplied = false,
            fallbackReason = null,
            queryStatus = null,
            queryAchievedDepth = 0,
            queryPrincipalVariations = emptyList(),
            results = emptyList(),
        )

    private fun buildStaticFallbackResult(
        queryFen: String,
        shortlistedMatches: List<Pair<RankedPositionMatch, List<PositionOccurrence>>>,
        fallbackReason: String,
        limit: Int,
        candidatesEvaluated: Int,
        engineCompletedCount: Int,
        queryResult: ContinuationResult?,
        candidateResults: Map<UUID, ContinuationResult>,
    ): SimilarPositionsResult {
        val topMatches = shortlistedMatches.take(limit.coerceIn(1, MAX_LIMIT))
        val results =
            topMatches.mapIndexed { index, (match, occs) ->
                val candRes = candidateResults[match.document.positionId]
                SimilarPositionMatch(
                    rank = index + 1,
                    score = match.score,
                    staticScore = match.score,
                    dynamicScore = 0.0,
                    positionId = match.document.positionId,
                    fen = match.document.fen,
                    canonicalFen = match.document.canonicalFen,
                    searchStatus = candRes?.status,
                    achievedDepth = candRes?.achievedDepth ?: 0,
                    principalVariations = candRes?.principalVariations.orEmpty(),
                    occurrences = occs.map { mapOccurrence(it) },
                )
            }

        return SimilarPositionsResult(
            queryFen = queryFen,
            candidatesEvaluated = candidatesEvaluated,
            shortlistedCandidatesCount = shortlistedMatches.size,
            engineCompletedCount = engineCompletedCount,
            dynamicSimilarityApplied = false,
            fallbackReason = fallbackReason,
            queryStatus = queryResult?.status,
            queryAchievedDepth = queryResult?.achievedDepth ?: 0,
            queryPrincipalVariations = queryResult?.principalVariations.orEmpty(),
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
        val resolvedResult =
            gameOutcomeNormalizer.resolvePerspectiveResult(
                game = game,
                authoritativeColor = occ.playerColor,
                accountUsername = occ.chessAccount.username,
            )

        return SimilarOccurrenceDto(
            id = occ.id,
            platformGameId = game.platformGameId,
            playedMove = occ.movePlayed,
            plyNumber = occ.plyNumber,
            playerColor = occ.playerColor,
            playedAt = game.playedAt,
            opponentUsername = opponent,
            timeControl = game.timeControl,
            gameResult = resolvedResult,
            chessComUrl = url,
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val MAX_CANDIDATES = 200
        const val MAX_ENGINE_SHORTLIST = 100
        const val DEFAULT_DEPTH = 8
        const val DEFAULT_MULTI_PV = 1
    }
}
