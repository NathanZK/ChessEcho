package com.chessecho.service

import com.chessecho.repository.HumanMoveCorpusProjectionRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertFailsWith

class SelectedPopulationVerificationServiceTest {
    private val projectionRepository = mock<HumanMoveCorpusProjectionRepository>()
    private val finalizationService = mock<HumanMoveCorpusProjectionFinalizationService>()
    private val occurrenceService = mock<HumanMoveCorpusOccurrenceService>()
    private val jdbcTemplate = mock<JdbcTemplate>()
    private val service =
        SelectedPopulationVerificationService(
            projectionRepository,
            finalizationService,
            occurrenceService,
            jdbcTemplate,
        )

    @Test
    fun `rejects a nonpositive selected prefix before reading persisted state`() {
        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            service.verify(population(prefixN = 0), emptySet())
        }

        verifyNoInteractions(projectionRepository, finalizationService, occurrenceService, jdbcTemplate)
    }

    @Test
    fun `rejects a selected prefix beyond the artifact prefix before reading persisted state`() {
        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            service.verify(population(coveredPrefix = 9, prefixN = 10), emptySet())
        }

        verifyNoInteractions(projectionRepository, finalizationService, occurrenceService, jdbcTemplate)
    }

    @Test
    fun `rejects a missing distribution digest before reading persisted state`() {
        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            service.verify(population(distributionSha256 = null), emptySet())
        }

        verifyNoInteractions(projectionRepository, finalizationService, occurrenceService, jdbcTemplate)
    }

    private fun population(
        coveredPrefix: Int = 10,
        prefixN: Int = 10,
        distributionSha256: String? = "a".repeat(64),
    ) = EvaluationReferencePopulation(
        contentDigest = "b".repeat(64),
        sourceRunId = UUID.nameUUIDFromBytes("selected-run".toByteArray()),
        coveredPrefix = coveredPrefix,
        prefixN = prefixN,
        ratingBand = "1000-1200",
        minObservations = 5,
        calculationVersion = "human-move-corpus-checkpoint-v1",
        distributionSha256 = distributionSha256,
    )
}
