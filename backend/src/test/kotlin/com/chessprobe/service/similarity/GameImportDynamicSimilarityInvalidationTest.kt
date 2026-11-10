package com.chessprobe.service.similarity

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.Game
import com.chessprobe.domain.ImportedArchive
import com.chessprobe.service.DerivedProcessingCoordinator
import com.chessprobe.service.GameImportService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.lang.reflect.Method
import java.util.UUID

class GameImportDynamicSimilarityInvalidationTest {
    @Test
    fun `processDerivedGames invalidates dynamic similarity cache when positions are affected`() {
        val dynamicCache = DynamicSimilarityCache()
        val account = ChessAccount(platform = "CHESS_COM", username = "test_player")

        var buildCount = 0
        dynamicCache.getOrBuild(account.id) {
            buildCount++
            com.chessprobe.similarity.DynamicInvertedIndex()
        }
        assertEquals(1, buildCount)

        val derivedProcessingCoordinator: DerivedProcessingCoordinator = mock()
        whenever(derivedProcessingCoordinator.processDerivedGames(any(), anyOrNull(), any(), anyOrNull()))
            .thenReturn(setOf(UUID.randomUUID()))

        val service =
            GameImportService(
                asyncJobRepository = mock(),
                appUserRepository = mock(),
                chessAccountRepository = mock(),
                gameRepository = mock(),
                importedArchiveRepository = mock(),
                archiveDerivedProcessingRepository = mock(),
                chessComClient = mock(),
                gameParserService = mock(),
                userPositionStatsRepository = mock(),
                positionOccurrenceRepository = mock(),
                positionRepository = mock(),
                engineAnalysisOrchestrator = mock(),
                transactionTemplate = mock(),
                accountOwnershipService = mock(),
                engineAnalysisRepository = mock(),
                userPositionWeaknessRepository = mock(),
                puzzleSchedulingEventRepository = mock(),
                dataSource = mock(),
                positionSimilarityCache = null,
                dynamicSimilarityCache = dynamicCache,
                derivedGameBatchSize = 1000,
                userPositionStatsBatchService = mock(),
                puzzleSchedulingEventDispatcher = mock(),
                derivedProcessingCoordinator = derivedProcessingCoordinator,
            )

        val method: Method =
            GameImportService::class.java.getDeclaredMethod(
                "processDerivedGames",
                ChessAccount::class.java,
                com.chessprobe.domain.AppUser::class.java,
                List::class.java,
                ImportedArchive::class.java,
            )
        method.isAccessible = true
        method.invoke(service, account, null, emptyList<Game>(), null)

        assertEquals(0, dynamicCache.activeCachedAccountCount())
        dynamicCache.getOrBuild(account.id) {
            buildCount++
            com.chessprobe.similarity.DynamicInvertedIndex()
        }
        assertEquals(2, buildCount, "Dynamic cache must be invalidated and rebuilt when positions are affected")
    }

    @Test
    fun `cache invalidation advances generation counter for imported account in dynamic similarity cache`() {
        val cache = DynamicSimilarityCache()
        val accountId = UUID.randomUUID()

        var buildCount = 0
        cache.getOrBuild(accountId) {
            buildCount++
            com.chessprobe.similarity.DynamicInvertedIndex()
        }
        assertEquals(1, buildCount)

        cache.invalidate(accountId)

        cache.getOrBuild(accountId) {
            buildCount++
            com.chessprobe.similarity.DynamicInvertedIndex()
        }
        assertEquals(2, buildCount, "Generation must advance and trigger rebuild after dynamic cache invalidation")
    }
}
