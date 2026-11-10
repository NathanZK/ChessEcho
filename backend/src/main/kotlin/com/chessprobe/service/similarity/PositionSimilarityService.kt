package com.chessprobe.service.similarity

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.EngineAnalysis
import com.chessprobe.domain.PositionOccurrence
import com.chessprobe.domain.SimilarityRetrievalMode
import com.chessprobe.repository.EngineAnalysisRepository
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.service.ContinuationResult
import com.chessprobe.service.DynamicAnalysisOptions
import com.chessprobe.service.GameOutcomeNormalizer
import com.chessprobe.service.PrincipalVariation
import com.chessprobe.service.SearchStatus
import com.chessprobe.service.StockfishService
import com.chessprobe.similarity.CandidateContinuation
import com.chessprobe.similarity.DynamicInvertedIndex
import com.chessprobe.similarity.DynamicTacticalScorer
import com.chessprobe.similarity.PositionInvertedIndex
import com.chessprobe.similarity.PositionSimilarityScorer
import com.chessprobe.similarity.PositionTokenExtractor
import com.chessprobe.similarity.RankedDynamicMatch
import com.chessprobe.similarity.RankedPositionMatch
import com.chessprobe.similarity.ScoredCandidate
import org.springframework.beans.factory.annotation.Value
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
    val mode: String = "STRUCTURAL",
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

private data class DynamicQueryResolution(
    val tokens: Map<String, Double> = emptyMap(),
    val principalVariations: List<PrincipalVariation> = emptyList(),
    val failure: DynamicQueryFailure? = null,
) {
    companion object {
        fun success(
            tokens: Map<String, Double>,
            pvs: List<PrincipalVariation>,
        ) = DynamicQueryResolution(tokens = tokens, principalVariations = pvs)

        fun error(
            reason: String,
            status: SearchStatus,
            depth: Int,
        ) = DynamicQueryResolution(failure = DynamicQueryFailure(reason, status, depth))
    }
}

private data class DynamicQueryFailure(
    val fallbackReason: String,
    val status: SearchStatus,
    val achievedDepth: Int,
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
    private val dynamicCache: DynamicSimilarityCache,
    private val personalDynamicCorpusProvider: PersonalDynamicCorpusProvider,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    @Value("\${chess.similarity.dynamic.candidate-budget:100}")
    private val candidateBudget: Int = MAX_ENGINE_SHORTLIST,
) {
    @Transactional(readOnly = true)
    fun findSimilarPositions(
        queryFen: String,
        account: ChessAccount,
        minScore: Double = 0.0,
        limit: Int = DEFAULT_LIMIT,
        depth: Int = DEFAULT_DEPTH,
        multiPv: Int = DEFAULT_MULTI_PV,
        mode: SimilarityRetrievalMode = SimilarityRetrievalMode.STRUCTURAL,
    ): SimilarPositionsResult {
        val validFen = validator.validateOrthodoxFen(queryFen)
        val resolvedLimit = limit.coerceIn(1, MAX_LIMIT)
        return when (mode) {
            SimilarityRetrievalMode.STRUCTURAL ->
                findStructuralSimilarPositions(validFen, account, minScore, resolvedLimit)
            SimilarityRetrievalMode.DYNAMIC ->
                findDynamicSimilarPositions(validFen, account, resolvedLimit, depth, multiPv)
        }
    }

    private fun findStructuralSimilarPositions(
        validFen: String,
        account: ChessAccount,
        minScore: Double,
        limit: Int,
    ): SimilarPositionsResult {
        val queryDoc = tokenExtractor.extractDocument(UUID.randomUUID(), validFen, includeReachability = false)

        val activeTurn = queryDoc.sideToMove
        require(activeTurn == "w" || activeTurn == "b") { "Invalid active turn: $activeTurn" }
        val expectedColor = if (activeTurn == "w") "WHITE" else "BLACK"

        val index = getOrBuildIndex(account) ?: return emptyResult(validFen, mode = "STRUCTURAL")

        val candidateDocs = index.queryCandidates(queryDoc.terms.keys, activeTurn = activeTurn, maxCandidates = MAX_CANDIDATES)
        if (candidateDocs.isEmpty()) {
            return emptyResult(validFen, mode = "STRUCTURAL")
        }

        val scoredMatches = scorer.score(queryDoc, candidateDocs)
        val filtered = scoredMatches.filter { it.score >= minScore }
        if (filtered.isEmpty()) {
            return emptyResult(validFen, mode = "STRUCTURAL")
        }

        val qualifyingMatches = qualifyCandidates(account, filtered, activeTurn, expectedColor)
        if (qualifyingMatches.isEmpty()) {
            return emptyResult(validFen, mode = "STRUCTURAL")
        }

        val shortlistedMatches = qualifyingMatches.take(limit)
        val results = mapStructuralMatches(shortlistedMatches)

        return SimilarPositionsResult(
            queryFen = validFen,
            candidatesEvaluated = candidateDocs.size,
            shortlistedCandidatesCount = shortlistedMatches.size,
            engineCompletedCount = 0,
            dynamicSimilarityApplied = false,
            fallbackReason = null,
            queryStatus = null,
            queryAchievedDepth = 0,
            queryPrincipalVariations = emptyList(),
            results = results,
            mode = "STRUCTURAL",
        )
    }

    private fun findDynamicSimilarPositions(
        validFen: String,
        account: ChessAccount,
        limit: Int,
        depth: Int,
        multiPv: Int,
    ): SimilarPositionsResult {
        val queryDoc = tokenExtractor.extractDocument(UUID.randomUUID(), validFen, includeReachability = false)
        val activeTurn = queryDoc.sideToMove
        require(activeTurn == "w" || activeTurn == "b") { "Invalid active turn: $activeTurn" }
        val expectedColor = if (activeTurn == "w") "WHITE" else "BLACK"

        return concurrencyManager.withPermitOrFallback(
            onPermitAcquired = {
                executeDynamicSearch(validFen, account, activeTurn, expectedColor, limit, depth, multiPv)
            },
            onCapacityExceeded = {
                emptyDynamicResult(
                    queryFen = validFen,
                    fallbackReason = "CAPACITY_EXCEEDED",
                )
            },
        )
    }

    private fun executeDynamicSearch(
        validFen: String,
        account: ChessAccount,
        activeTurn: String,
        expectedColor: String,
        limit: Int,
        depth: Int,
        multiPv: Int,
    ): SimilarPositionsResult {
        val targetDepth = maxOf(depth, DYNAMIC_TARGET_DEPTH)
        val queryFeatures = resolveDynamicQueryFeatures(validFen, targetDepth)
        if (queryFeatures.failure != null) {
            return emptyDynamicResult(
                queryFen = validFen,
                fallbackReason = queryFeatures.failure.fallbackReason,
                queryStatus = queryFeatures.failure.status,
                queryAchievedDepth = queryFeatures.failure.achievedDepth,
            )
        }

        val dynamicIndex =
            dynamicCache.getOrBuild(account.id) {
                val docs = personalDynamicCorpusProvider.loadPersonalDynamicPositions(account)
                DynamicInvertedIndex(docs)
            }

        val scoredCandidates =
            dynamicIndex.retrieveCandidates(
                queryTerms = queryFeatures.tokens.keys,
                activeTurn = activeTurn,
                maxCandidates = candidateBudget,
            )

        val qualifyingMatches = qualifyDynamicCandidates(account.id, scoredCandidates, expectedColor)
        if (qualifyingMatches.isEmpty()) {
            return emptyDynamicResult(
                queryFen = validFen,
                queryStatus = SearchStatus.COMPLETED,
                queryAchievedDepth = targetDepth,
                queryPrincipalVariations = queryFeatures.principalVariations,
            )
        }

        val queryContinuation =
            ContinuationResult(
                status = SearchStatus.COMPLETED,
                achievedDepth = targetDepth,
                principalVariations = queryFeatures.principalVariations,
            )
        val resolution = resolveCandidateContinuations(validFen, qualifyingMatches, targetDepth, queryContinuation)
        if (resolution.fallbackReason != null) {
            return buildDynamicFallbackResult(
                validFen = validFen,
                totalScored = scoredCandidates.size,
                qualifyingMatches = qualifyingMatches,
                resolution = resolution,
                queryPvs = queryFeatures.principalVariations,
                limit = limit,
            )
        }

        return buildRerankedDynamicResult(
            validFen = validFen,
            totalScored = scoredCandidates.size,
            qualifyingMatches = qualifyingMatches,
            resolution = resolution,
            queryFeatures = queryFeatures,
            targetDepth = targetDepth,
            limit = limit,
        )
    }

    private fun buildDynamicFallbackResult(
        validFen: String,
        totalScored: Int,
        qualifyingMatches: List<Pair<ScoredCandidate, List<PositionOccurrence>>>,
        resolution: CandidateContinuationsResolution,
        queryPvs: List<PrincipalVariation>,
        limit: Int,
    ): SimilarPositionsResult {
        val fallbackMatches = qualifyingMatches.take(limit)
        val fallbackResults =
            mapDynamicMatches(
                shortlisted = fallbackMatches,
                engineDepth = 10,
                searchStatus = resolution.queryStatus ?: SearchStatus.INTERRUPTED,
            )
        return SimilarPositionsResult(
            queryFen = validFen,
            candidatesEvaluated = totalScored,
            shortlistedCandidatesCount = fallbackMatches.size,
            engineCompletedCount = 1 + resolution.engineCompletedCount,
            dynamicSimilarityApplied = true,
            fallbackReason = resolution.fallbackReason,
            queryStatus = resolution.queryStatus ?: SearchStatus.INTERRUPTED,
            queryAchievedDepth = 10,
            queryPrincipalVariations = queryPvs,
            results = fallbackResults,
            mode = "DYNAMIC",
        )
    }

    private fun buildRerankedDynamicResult(
        validFen: String,
        totalScored: Int,
        qualifyingMatches: List<Pair<ScoredCandidate, List<PositionOccurrence>>>,
        resolution: CandidateContinuationsResolution,
        queryFeatures: DynamicQueryResolution,
        targetDepth: Int,
        limit: Int,
    ): SimilarPositionsResult {
        val queryMoves =
            queryFeatures
                .principalVariations
                .firstOrNull()
                ?.moves
                .orEmpty()
        val candidateContinuations =
            qualifyingMatches.map { (cand, _) ->
                CandidateContinuation(
                    positionId = cand.positionId,
                    fen = cand.canonicalFen,
                    canonicalFen = cand.canonicalFen,
                    staticRank = 0,
                    staticScore = 0.0,
                    continuationResult =
                        resolution.continuations[cand.positionId]
                            ?: ContinuationResult(SearchStatus.FAILED, 0, emptyList()),
                )
            }

        val rankedMatches = dynamicScorer.rank(validFen, queryMoves, candidateContinuations)
        val rankedMap = rankedMatches.associateBy { it.positionId }

        val sortedQualifying =
            qualifyingMatches.sortedWith(
                compareBy<Pair<ScoredCandidate, List<PositionOccurrence>>> { (cand, _) ->
                    rankedMap[cand.positionId]?.rank ?: Int.MAX_VALUE
                }.thenBy { it.first.canonicalFen }
                    .thenBy { it.first.positionId },
            )

        val shortlisted = sortedQualifying.take(limit)
        val results = mapRerankedMatches(shortlisted, rankedMap, resolution, targetDepth)

        return SimilarPositionsResult(
            queryFen = validFen,
            candidatesEvaluated = totalScored,
            shortlistedCandidatesCount = shortlisted.size,
            engineCompletedCount = 1 + resolution.engineCompletedCount,
            dynamicSimilarityApplied = true,
            fallbackReason = null,
            queryStatus = SearchStatus.COMPLETED,
            queryAchievedDepth = targetDepth,
            queryPrincipalVariations = queryFeatures.principalVariations,
            results = results,
            mode = "DYNAMIC",
        )
    }

    private fun mapRerankedMatches(
        shortlisted: List<Pair<ScoredCandidate, List<PositionOccurrence>>>,
        rankedMap: Map<UUID, RankedDynamicMatch>,
        resolution: CandidateContinuationsResolution,
        targetDepth: Int,
    ): List<SimilarPositionMatch> =
        shortlisted.mapIndexed { index, (candidate, occs) ->
            val rankedMatch = rankedMap[candidate.positionId]
            val candContinuation = resolution.continuations[candidate.positionId]
            SimilarPositionMatch(
                rank = index + 1,
                score = rankedMatch?.dynamicScore ?: 0.0,
                staticScore = 0.0,
                dynamicScore = rankedMatch?.dynamicScore ?: 0.0,
                positionId = candidate.positionId,
                fen = candidate.canonicalFen,
                canonicalFen = candidate.canonicalFen,
                searchStatus = candContinuation?.status ?: SearchStatus.COMPLETED,
                achievedDepth = candContinuation?.achievedDepth ?: targetDepth,
                principalVariations = candContinuation?.principalVariations.orEmpty(),
                occurrences = occs.map { mapOccurrence(it) },
            )
        }

    private data class CandidateContinuationsResolution(
        val continuations: Map<UUID, ContinuationResult>,
        val fallbackReason: String? = null,
        val queryStatus: SearchStatus? = null,
        val engineCompletedCount: Int = 0,
    )

    private fun fetchExistingAnalyses(positionIds: Set<UUID>): Map<UUID, EngineAnalysis> =
        try {
            engineAnalysisRepository
                .findByPositionIdInWithMoveEvaluations(positionIds)
                .associateBy { it.position.id }
        } catch (_: Exception) {
            emptyMap()
        }

    private fun partitionCandidateAnalyses(
        candidates: List<Pair<ScoredCandidate, List<PositionOccurrence>>>,
        existingAnalyses: Map<UUID, EngineAnalysis>,
        targetDepth: Int,
    ): Pair<MutableMap<UUID, ContinuationResult>, List<Pair<UUID, String>>> {
        val cachedResults = mutableMapOf<UUID, ContinuationResult>()
        val uncachedPairs = mutableListOf<Pair<UUID, String>>()
        for ((candidate, _) in candidates) {
            val analysis = existingAnalyses[candidate.positionId]
            if (analysis != null && analysis.depth >= targetDepth && !analysis.bestMove.isNullOrBlank()) {
                cachedResults[candidate.positionId] =
                    ContinuationResult(
                        status = SearchStatus.COMPLETED,
                        achievedDepth = analysis.depth,
                        principalVariations = listOf(PrincipalVariation(1, listOf(analysis.bestMove!!), analysis.depth)),
                    )
            } else {
                uncachedPairs.add(candidate.positionId to candidate.canonicalFen)
            }
        }
        return cachedResults to uncachedPairs
    }

    private fun resolveCandidateContinuations(
        validFen: String,
        candidates: List<Pair<ScoredCandidate, List<PositionOccurrence>>>,
        targetDepth: Int,
        queryContinuation: ContinuationResult? = null,
    ): CandidateContinuationsResolution {
        val candidatePositionIds = candidates.map { it.first.positionId }.toSet()
        val existingAnalyses = fetchExistingAnalyses(candidatePositionIds)
        val (cachedResults, uncachedPairs) = partitionCandidateAnalyses(candidates, existingAnalyses, targetDepth)

        if (uncachedPairs.isEmpty()) {
            return CandidateContinuationsResolution(
                continuations = cachedResults,
                engineCompletedCount = 0,
            )
        }

        val batchResult =
            try {
                stockfishService.batchResolveContinuations(
                    queryFen = validFen,
                    candidates = uncachedPairs,
                    options =
                        DynamicAnalysisOptions(
                            depth = targetDepth,
                            multiPv = 1,
                            watchdogTimeoutMs = BATCH_WATCHDOG_TIMEOUT_MS,
                        ),
                    knownQueryResult = queryContinuation,
                )
            } catch (_: Exception) {
                return CandidateContinuationsResolution(
                    continuations = cachedResults,
                    fallbackReason = "ENGINE_FAILED",
                    queryStatus = SearchStatus.FAILED,
                )
            }

        if (batchResult.fallbackReason != null) {
            return CandidateContinuationsResolution(
                continuations = cachedResults,
                fallbackReason = batchResult.fallbackReason,
                queryStatus = batchResult.queryResult.status,
                engineCompletedCount = batchResult.engineCompletedCount,
            )
        }

        val mergedContinuations = cachedResults.toMutableMap()
        mergedContinuations.putAll(batchResult.candidateResults)

        return CandidateContinuationsResolution(
            continuations = mergedContinuations,
            engineCompletedCount = batchResult.engineCompletedCount,
        )
    }

    private fun resolveDynamicQueryFeatures(
        validFen: String,
        engineDepth: Int,
    ): DynamicQueryResolution {
        val candidates =
            try {
                stockfishService.analyzeMultiPv(validFen, engineDepth, 1)
            } catch (_: Exception) {
                return DynamicQueryResolution.error("ENGINE_FAILED", SearchStatus.FAILED, 0)
            }

        val moves = candidates.map { it.move }
        if (moves.isEmpty()) {
            return DynamicQueryResolution.error("NO_QUERY_PV", SearchStatus.FAILED, engineDepth)
        }

        val tokens = dynamicScorer.extractDynamicTokens(validFen, moves)
        if (tokens.isEmpty()) {
            return DynamicQueryResolution.error("NO_DYNAMIC_TOKENS", SearchStatus.COMPLETED, engineDepth)
        }

        val pvs = candidates.mapIndexed { i, c -> PrincipalVariation(i + 1, listOf(c.move), engineDepth) }
        return DynamicQueryResolution.success(tokens, pvs)
    }

    private fun qualifyDynamicCandidates(
        accountId: UUID,
        scoredCandidates: List<ScoredCandidate>,
        expectedColor: String,
    ): List<Pair<ScoredCandidate, List<PositionOccurrence>>> {
        if (scoredCandidates.isEmpty()) return emptyList()
        val candidatePositionIds = scoredCandidates.map { it.positionId }
        val occurrencesByPos = loadOccurrencesForPositions(accountId, candidatePositionIds)

        return scoredCandidates.mapNotNull { candidate ->
            val occs = occurrencesByPos[candidate.positionId].orEmpty()
            val qualifyingOccs =
                occs.filter { occ ->
                    occ.playerColor.equals(expectedColor, ignoreCase = true) && occ.movePlayed.isNotBlank()
                }
            if (qualifyingOccs.isEmpty()) null else candidate to qualifyingOccs
        }
    }

    private fun mapDynamicMatches(
        shortlisted: List<Pair<ScoredCandidate, List<PositionOccurrence>>>,
        engineDepth: Int,
        searchStatus: SearchStatus = SearchStatus.COMPLETED,
    ): List<SimilarPositionMatch> =
        shortlisted.mapIndexed { index, (candidate, occs) ->
            SimilarPositionMatch(
                rank = index + 1,
                score = candidate.score,
                staticScore = 0.0,
                dynamicScore = candidate.score,
                positionId = candidate.positionId,
                fen = candidate.canonicalFen,
                canonicalFen = candidate.canonicalFen,
                searchStatus = searchStatus,
                achievedDepth = engineDepth,
                principalVariations = emptyList(),
                occurrences = occs.map { mapOccurrence(it) },
            )
        }

    private fun emptyDynamicResult(
        queryFen: String,
        fallbackReason: String? = null,
        queryStatus: SearchStatus? = null,
        queryAchievedDepth: Int = 0,
        queryPrincipalVariations: List<PrincipalVariation> = emptyList(),
    ): SimilarPositionsResult =
        SimilarPositionsResult(
            queryFen = queryFen,
            candidatesEvaluated = 0,
            shortlistedCandidatesCount = 0,
            engineCompletedCount = 0,
            dynamicSimilarityApplied = true,
            fallbackReason = fallbackReason,
            queryStatus = queryStatus,
            queryAchievedDepth = queryAchievedDepth,
            queryPrincipalVariations = queryPrincipalVariations,
            results = emptyList(),
            mode = "DYNAMIC",
        )

    private fun emptyResult(
        queryFen: String,
        mode: String = "STRUCTURAL",
    ): SimilarPositionsResult =
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
            mode = mode,
        )

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

    private fun mapStructuralMatches(
        shortlistedMatches: List<Pair<RankedPositionMatch, List<PositionOccurrence>>>,
    ): List<SimilarPositionMatch> =
        shortlistedMatches.mapIndexed { index, (match, occs) ->
            SimilarPositionMatch(
                rank = index + 1,
                score = match.score,
                staticScore = match.score,
                dynamicScore = 0.0,
                positionId = match.document.positionId,
                fen = match.document.fen,
                canonicalFen = match.document.canonicalFen,
                searchStatus = null,
                achievedDepth = 0,
                principalVariations = emptyList(),
                occurrences = occs.map { mapOccurrence(it) },
            )
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
        const val DYNAMIC_TARGET_DEPTH = 16
        const val BATCH_WATCHDOG_TIMEOUT_MS = 5000L
    }
}
