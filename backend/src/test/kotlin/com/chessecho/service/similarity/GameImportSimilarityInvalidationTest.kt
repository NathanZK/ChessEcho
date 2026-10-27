package com.chessecho.service.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class GameImportSimilarityInvalidationTest {
    @Test
    fun `cache invalidation advances generation counter for imported account`() {
        val cache = PositionSimilarityCache()
        val accountId = UUID.randomUUID()

        var buildCount = 0
        cache.getOrBuild(accountId) {
            buildCount++
            com.chessecho.similarity.PositionInvertedIndex()
        }
        assertEquals(1, buildCount)

        cache.invalidate(accountId)

        cache.getOrBuild(accountId) {
            buildCount++
            com.chessecho.similarity.PositionInvertedIndex()
        }
        assertEquals(2, buildCount, "Generation must advance and trigger rebuild after invalidation")
    }
}
