package com.chessecho.service

import com.chessecho.domain.HumanMoveDistribution
import com.chessecho.domain.Position
import com.chessecho.repository.HumanMoveBfsClaimConflictException
import com.chessecho.repository.HumanMoveBfsSeenGameClaimer
import com.chessecho.repository.HumanMoveBfsSeenGameRepository
import com.chessecho.repository.HumanMoveDistributionRepository
import com.chessecho.repository.PositionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.util.UUID

@ExtendWith(MockitoExtension::class)
abstract class HumanMoveBfsBaseTest {
    @Mock
    protected lateinit var chessComClient: ChessComClient

    @Mock
    protected lateinit var positionRepository: PositionRepository

    @Mock
    protected lateinit var humanMoveDistributionRepository: HumanMoveDistributionRepository

    @Mock
    protected lateinit var humanMoveBfsSeenGameRepository: HumanMoveBfsSeenGameRepository

    @Mock
    protected lateinit var humanMoveBfsSeenGameClaimer: HumanMoveBfsSeenGameClaimer

    protected lateinit var service: HumanMoveBfsService

    @BeforeEach
    fun setUp() {
        service =
            HumanMoveBfsService(
                chessComClient,
                positionRepository,
                humanMoveDistributionRepository,
                humanMoveBfsSeenGameRepository,
                humanMoveBfsSeenGameClaimer,
            )
        lenient().whenever(positionRepository.saveAll(any<Iterable<Position>>())).thenAnswer {
            it.getArgument<Iterable<Position>>(0).toList()
        }
        lenient().whenever(humanMoveBfsSeenGameRepository.findExistingGameUrls(any())).thenReturn(emptyList())
    }

    protected fun rapidGame(
        url: String,
        whiteUser: String,
        whiteRating: Int,
        blackUser: String,
        blackRating: Int,
        pgn: String = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
    ): Map<String, Any> =
        mapOf(
            "url" to url,
            "rules" to "chess",
            "time_class" to "rapid",
            "white" to mapOf("username" to whiteUser, "rating" to whiteRating),
            "black" to mapOf("username" to blackUser, "rating" to blackRating),
            "pgn" to pgn,
        )

    protected fun blitzGame(
        url: String,
        whiteUser: String,
        whiteRating: Int,
        blackUser: String,
        blackRating: Int,
    ): Map<String, Any> =
        mapOf(
            "url" to url,
            "rules" to "chess",
            "time_class" to "blitz",
            "white" to mapOf("username" to whiteUser, "rating" to whiteRating),
            "black" to mapOf("username" to blackUser, "rating" to blackRating),
        )

    protected data class StatefulStores(
        val distributions: MutableList<HumanMoveDistribution>,
        val seenGameUrls: MutableSet<String>,
    )

    protected fun makeRepositoryStateful(): StatefulStores {
        val positionStore = mutableListOf<Position>()
        val distStore = mutableListOf<HumanMoveDistribution>()
        val seenGameStore = mutableSetOf<String>()

        bindStatefulPositionRepository(positionStore)
        bindStatefulDistributionRepository(distStore)
        bindStatefulSeenGameRepository(seenGameStore)

        return StatefulStores(distStore, seenGameStore)
    }

    private fun bindStatefulPositionRepository(positionStore: MutableList<Position>) {
        lenient().whenever(positionRepository.saveAll(any<Iterable<Position>>())).thenAnswer { inv ->
            val incoming = inv.getArgument<Iterable<Position>>(0).toList()
            for (pos in incoming) {
                if (positionStore.none { it.hash == pos.hash }) {
                    positionStore.add(pos)
                }
            }
            incoming
        }
        lenient().whenever(positionRepository.findByHashIn(any())).thenAnswer { inv ->
            val hashes = inv.getArgument<List<String>>(0)
            positionStore.filter { it.hash in hashes }
        }
    }

    private fun bindStatefulDistributionRepository(distStore: MutableList<HumanMoveDistribution>) {
        lenient().whenever(humanMoveDistributionRepository.saveAll(any<Iterable<HumanMoveDistribution>>())).thenAnswer { inv ->
            val incoming = inv.getArgument<Iterable<HumanMoveDistribution>>(0).toList()
            for (row in incoming) {
                val existing = distStore.find { it.id == row.id }
                if (existing != null) {
                    distStore.remove(existing)
                }
                distStore.add(row)
            }
            incoming
        }
        lenient()
            .whenever(
                humanMoveDistributionRepository.findByPositionIdAndRatingBand(any<UUID>(), anyString()),
            ).thenAnswer { inv ->
                val posId = inv.getArgument<UUID>(0)
                val band = inv.getArgument<String>(1)
                distStore.filter { it.positionId == posId && it.ratingBand == band }
            }
    }

    private fun bindStatefulSeenGameRepository(seenGameStore: MutableSet<String>) {
        lenient().whenever(humanMoveBfsSeenGameRepository.findExistingGameUrls(any())).thenAnswer { inv ->
            val urls = inv.getArgument<Collection<String>>(0)
            urls.filter { it in seenGameStore }
        }
        lenient().whenever(humanMoveBfsSeenGameClaimer.claimGameUrls(any<Collection<String>>())).thenAnswer { inv ->
            val urls = inv.getArgument<Collection<String>>(0)
            for (url in urls) {
                if (!seenGameStore.add(url)) {
                    throw HumanMoveBfsClaimConflictException(
                        attempted = urls.size,
                        cause = IllegalStateException("URL already claimed: $url"),
                    )
                }
            }
            null
        }
    }
}
