package com.chessecho.integration.corpus

import com.chessecho.domain.HumanMoveCorpusProjection
import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.dto.HumanMoveCorpusRunRequest
import com.chessecho.humanmove.artifact.HumanMoveCorpusArtifactService
import com.chessecho.humanmove.artifact.HumanMoveCorpusExportRequest
import com.chessecho.repository.HumanMoveCorpusProjectionRepository
import com.chessecho.service.EvaluationReferencePopulation
import com.chessecho.service.GameParserService
import com.chessecho.service.HumanMoveCorpusCandidate
import com.chessecho.service.HumanMoveCorpusGameWriter
import com.chessecho.service.HumanMoveCorpusImportService
import com.chessecho.service.HumanMoveCorpusIntegrityException
import com.chessecho.service.HumanMoveCorpusMaterializationService
import com.chessecho.service.HumanMoveCorpusMaterializeRequest
import com.chessecho.service.HumanMoveCorpusObservedMove
import com.chessecho.service.HumanMoveCorpusProjectionFinalizationService
import com.chessecho.service.HumanMoveCorpusRunOutcome
import com.chessecho.service.HumanMoveCorpusSide
import com.chessecho.service.RetainedEvaluationEvidenceIntegrityException
import com.chessecho.service.SelectedPopulationVerificationService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest
@Testcontainers
class SelectedPopulationVerificationPostgresIntegrationTest {
    @Autowired
    private lateinit var gameWriter: HumanMoveCorpusGameWriter

    @Autowired
    private lateinit var artifactService: HumanMoveCorpusArtifactService

    @Autowired
    private lateinit var importService: HumanMoveCorpusImportService

    @Autowired
    private lateinit var materializationService: HumanMoveCorpusMaterializationService

    @Autowired
    private lateinit var projectionFinalizationService: HumanMoveCorpusProjectionFinalizationService

    @Autowired
    private lateinit var projectionRepository: HumanMoveCorpusProjectionRepository

    @Autowired
    private lateinit var verificationService: SelectedPopulationVerificationService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun resetDatabase() {
        jdbcTemplate.execute("TRUNCATE human_move_corpus_import CASCADE")
        jdbcTemplate.execute(
            "TRUNCATE human_move_corpus_observation, human_move_corpus_game, human_move_corpus_run, " +
                "human_move_distribution, human_move_bfs_seen_game, position CASCADE",
        )
    }

    @Test
    fun `resolves requested occurrences inside the selected projection prefix without changing corpus state`() {
        val fixture = finalizedFixture()
        val before = persistedCorpusState(fixture)

        val verified = verificationService.verify(fixture.population, setOf(fixture.occurrences.getValue(1)))

        assertEquals(fixture.projection.id, verified.projection.id)
        assertEquals(1, verified.projection.prefixN)
        assertEquals(fixture.population, verified.population)
        assertEquals(fixture.occurrences.getValue(1), verified.occurrences.keys.single())
        assertEquals(1, verified.occurrences.values.single().qualifyingOrdinal)
        assertEquals(fixture.runId, verified.binding.sourceRunId)
        assertEquals(2, verified.binding.coveredPrefix)
        assertEquals(before, persistedCorpusState(fixture))
    }

    @Test
    fun `rejects a requested occurrence beyond prefixN even when it is within coveredPrefix`() {
        val fixture = finalizedFixture()
        val before = persistedCorpusState(fixture)

        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            verificationService.verify(
                fixture.population,
                setOf(fixture.occurrences.getValue(1), fixture.occurrences.getValue(2)),
            )
        }
        assertEquals(before, persistedCorpusState(fixture))

        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            verificationService.verify(fixture.population, setOf(UUID.randomUUID()))
        }
        assertEquals(before, persistedCorpusState(fixture))
    }

    @Test
    fun `rejects missing projection and mismatched artifact binding without changing corpus state`() {
        val fixture = finalizedFixture()
        val before = persistedCorpusState(fixture)

        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            verificationService.verify(fixture.population.copy(contentDigest = "c".repeat(64)), emptySet())
        }
        assertEquals(before, persistedCorpusState(fixture))

        assertFailsWith<HumanMoveCorpusIntegrityException> {
            verificationService.verify(fixture.population.copy(coveredPrefix = 1), emptySet())
        }
        assertEquals(before, persistedCorpusState(fixture))
    }

    @Test
    fun `requires finalized and verified projection state without changing it on rejection`() {
        val fixture = finalizedFixture()

        jdbcTemplate.update("UPDATE human_move_corpus_projection SET finalized = false WHERE id = ?", fixture.projection.id)
        var before = persistedCorpusState(fixture)
        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            verificationService.verify(fixture.population, emptySet())
        }
        assertEquals(before, persistedCorpusState(fixture))

        jdbcTemplate.update(
            "UPDATE human_move_corpus_projection SET finalized = true, verified = false WHERE id = ?",
            fixture.projection.id,
        )
        before = persistedCorpusState(fixture)
        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            verificationService.verify(fixture.population, emptySet())
        }
        assertEquals(before, persistedCorpusState(fixture))
    }

    @Test
    fun `checks caller stored and recomputed digests without changing corpus state on rejection`() {
        val fixture = finalizedFixture()
        val before = persistedCorpusState(fixture)

        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            verificationService.verify(fixture.population.copy(distributionSha256 = "c".repeat(64)), emptySet())
        }
        assertEquals(before, persistedCorpusState(fixture))

        jdbcTemplate.update("UPDATE human_move_corpus_projection SET distribution_sha256 = NULL WHERE id = ?", fixture.projection.id)
        val missingStoredDigestState = persistedCorpusState(fixture)
        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            verificationService.verify(fixture.population, emptySet())
        }
        assertEquals(missingStoredDigestState, persistedCorpusState(fixture))

        val forgedDigest = "d".repeat(64)
        jdbcTemplate.update(
            "UPDATE human_move_corpus_projection SET distribution_sha256 = ? WHERE id = ?",
            forgedDigest,
            fixture.projection.id,
        )
        val forgedPopulation = fixture.population.copy(distributionSha256 = forgedDigest)
        val forgedState = persistedCorpusState(fixture)
        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            verificationService.verify(forgedPopulation, emptySet())
        }
        assertEquals(forgedState, persistedCorpusState(fixture))
    }

    @Test
    fun `verifies selection and binding for an empty occurrence request without changing corpus state`() {
        val fixture = finalizedFixture()
        val before = persistedCorpusState(fixture)

        val verified = verificationService.verify(fixture.population, emptySet())

        assertEquals(2, verified.binding.coveredPrefix)
        assertEquals(emptyMap(), verified.occurrences)
        assertEquals(before, persistedCorpusState(fixture))
    }

    private fun finalizedFixture(): Fixture {
        val runId =
            gameWriter.createRun(
                HumanMoveCorpusRunRequest(
                    ratingBand = "1000-1200",
                    seedPlayers = listOf("seed"),
                    maxQualifyingGames = 2,
                    sourceRevision = "selected-population-verification-test",
                ),
            )
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val hash = GameParserService.generateHash(fen)
        (1..2).forEach { ordinal ->
            gameWriter.commitGame(
                runId,
                HumanMoveCorpusCandidate(
                    providerGameId = "https://example.invalid/selected-population/$ordinal",
                    traversedPlayer = "seed",
                    opponent = "opponent",
                    opponentSide = HumanMoveCorpusSide.BLACK,
                    opponentRating = 1100,
                    rules = "chess",
                    timeClass = "rapid",
                    bfsDepth = 0,
                    pgn = "[Event \"Selected population $ordinal\"]\n\n1. e4 *",
                    observations = listOf(HumanMoveCorpusObservedMove(hash, fen, "e4", 1)),
                ),
            )
        }
        gameWriter.finishRun(
            runId,
            HumanMoveCorpusRunOutcome(
                status = HumanMoveCorpusRunStatus.COMPLETED,
                stopReason = "MAX_QUALIFYING_GAMES",
                rejectedGameCount = 0,
                archiveFetchFailureCount = 0,
                failureDetails = null,
            ),
        )
        val exported = artifactService.export(HumanMoveCorpusExportRequest(runId, 2))
        val bytes = Files.readAllBytes(artifactService.archivePath(exported.contentDigest))
        importService.import(bytes, exported.contentDigest)
        val materialized =
            materializationService.materialize(
                HumanMoveCorpusMaterializeRequest(
                    contentDigest = exported.contentDigest,
                    prefixN = 1,
                    minObservations = 1,
                ),
            )
        projectionFinalizationService.finalize(materialized.projectionId)
        val projection = projectionRepository.findById(materialized.projectionId).orElseThrow()
        val occurrences =
            jdbcTemplate.queryForList(
                "SELECT id, qualifying_ordinal FROM human_move_corpus_occurrence " +
                    "WHERE source_run_id = ? ORDER BY qualifying_ordinal",
                runId,
            ).associate { (it["qualifying_ordinal"] as Number).toInt() to (it["id"] as UUID) }
        return Fixture(
            runId = runId,
            projection = projection,
            population =
                EvaluationReferencePopulation(
                    contentDigest = exported.contentDigest,
                    sourceRunId = runId,
                    coveredPrefix = 2,
                    prefixN = 1,
                    ratingBand = "1000-1200",
                    minObservations = 1,
                    calculationVersion = HumanMoveCorpusMaterializationService.CALCULATION_VERSION,
                    distributionSha256 = projection.distributionSha256,
                ),
            occurrences = occurrences,
        )
    }

    private fun persistedCorpusState(fixture: Fixture): List<List<String>> =
        listOf(
            tableState(
                "SELECT to_jsonb(p)::text FROM human_move_corpus_projection p WHERE p.id = ? ORDER BY p.id",
                fixture.projection.id,
            ),
            tableState(
                "SELECT to_jsonb(r)::text FROM human_move_corpus_projection_row r WHERE r.projection_id = ? ORDER BY r.id",
                fixture.projection.id,
            ),
            tableState(
                "SELECT to_jsonb(o)::text FROM human_move_corpus_occurrence o WHERE o.source_run_id = ? ORDER BY o.id",
                fixture.runId,
            ),
            tableState(
                "SELECT to_jsonb(b)::text FROM human_move_corpus_occurrence_binding b WHERE b.source_run_id = ? ORDER BY b.id",
                fixture.runId,
            ),
            tableState(
                "SELECT to_jsonb(a)::text FROM human_move_corpus_artifact_snapshot a WHERE a.source_run_id = ? ORDER BY a.content_digest",
                fixture.runId,
            ),
            tableState(
                "SELECT to_jsonb(r)::text FROM human_move_corpus_run r WHERE r.id = ? ORDER BY r.id",
                fixture.runId,
            ),
            tableState(
                "SELECT to_jsonb(g)::text FROM human_move_corpus_game g WHERE g.run_id = ? ORDER BY g.id",
                fixture.runId,
            ),
            tableState(
                "SELECT to_jsonb(o)::text FROM human_move_corpus_observation o " +
                    "JOIN human_move_corpus_game g ON g.id = o.game_id WHERE g.run_id = ? ORDER BY o.id",
                fixture.runId,
            ),
            tableState(
                "SELECT to_jsonb(i)::text FROM human_move_corpus_import i WHERE i.source_run_id = ? ORDER BY i.source_run_id",
                fixture.runId,
            ),
            tableState(
                "SELECT to_jsonb(g)::text FROM human_move_corpus_imported_game g WHERE g.source_run_id = ? ORDER BY g.id",
                fixture.runId,
            ),
            tableState(
                "SELECT to_jsonb(o)::text FROM human_move_corpus_imported_observation o " +
                    "JOIN human_move_corpus_imported_game g ON g.id = o.game_id " +
                    "WHERE g.source_run_id = ? ORDER BY o.id",
                fixture.runId,
            ),
        )

    private fun tableState(
        query: String,
        vararg parameters: Any,
    ): List<String> = jdbcTemplate.query(query, { rs, _ -> rs.getString(1) }, *parameters)

    private data class Fixture(
        val runId: UUID,
        val projection: HumanMoveCorpusProjection,
        val population: EvaluationReferencePopulation,
        val occurrences: Map<Int, UUID>,
    )

    companion object {
        private val archiveRoot = Files.createTempDirectory("chessecho-selected-population-")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("chessecho.corpus.archive-root", archiveRoot::toString)
            registry.add("chessecho.corpus.max-archive-bytes", { "100000" })
        }
    }
}
