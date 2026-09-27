package com.chessecho.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

data class EvaluationReferencePopulation(
    val contentDigest: String,
    val sourceRunId: UUID,
    val coveredPrefix: Int,
    val prefixN: Int,
    val ratingBand: String,
    val minObservations: Int,
    val calculationVersion: String,
    val distributionSha256: String?,
)

data class EvaluationEvidenceConfiguration(
    val thresholds: Set<Double>,
    val minMistakeCount: Int,
    val minTimesReached: Int,
    val color: String,
    val platform: String,
    val observationWindowDays: Int?,
)

data class EvaluationEvidencePlayer(
    val id: UUID,
    val identity: String,
)

enum class ObjectiveOutcome {
    WEAK,
    SOUND,
}

enum class ObservedGameOutcome {
    WIN,
    DRAW,
    LOSS,
}

data class EvaluationEvidenceRow(
    val playerId: UUID,
    val gameId: UUID,
    val occurrenceId: UUID,
    val positionIdentity: String,
    val preMovePly: Int,
    val move: String,
    val playerColor: String,
    val loss: Double,
    val engineDepth: Int,
    val observedOutcome: ObservedGameOutcome,
    val objectiveOutcome: ObjectiveOutcome,
    val practicalCandidate: Boolean,
    val practicalEligible: Boolean,
    val practicalWins: Int,
    val practicalDraws: Int,
    val practicalLosses: Int,
)

data class EvaluationEvidenceSnapshot(
    val id: UUID,
    val referencePopulation: EvaluationReferencePopulation,
    val occurrenceEvidenceId: UUID?,
    val players: Set<EvaluationEvidencePlayer>,
    val configuration: EvaluationEvidenceConfiguration,
    val sourceRevision: String,
    val engineIdentity: String,
    val parserIdentity: String,
    val rows: List<EvaluationEvidenceRow>,
)

data class EvaluationEvidenceReconstruction(
    val objectiveWeakness: Map<UUID, Map<Double, Int>>,
    val practicalEvidence: Map<UUID, PracticalEvidenceCounts>,
    val observedOutcomes: Map<UUID, Map<ObservedGameOutcome, Int>>,
)

data class PracticalEvidenceCounts(
    val candidateGames: Int,
    val eligibleGames: Int,
    val ineligibleGames: Int,
    val excludedGames: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
)

class EvaluationEvidenceIntegrityException(message: String) : RuntimeException(message)

@Service
class EvaluationEvidenceSnapshotService(
    private val jdbcTemplate: JdbcTemplate? = null,
) {
    fun validate(snapshot: EvaluationEvidenceSnapshot) {
        require(snapshot.players.isNotEmpty()) { "evaluation-player set must not be empty" }
        require(snapshot.configuration.thresholds == APPROVED_THRESHOLDS) {
            "snapshot thresholds do not match the approved E6 configuration"
        }
        require(snapshot.configuration.minMistakeCount >= 0)
        require(snapshot.configuration.minTimesReached >= 0)
        require(snapshot.configuration.color in setOf("WHITE", "BLACK", "BOTH"))
        require(snapshot.configuration.platform == "CHESS_COM")
        require(snapshot.sourceRevision.isNotBlank())
        require(snapshot.engineIdentity.isNotBlank())
        require(snapshot.parserIdentity.isNotBlank())
        require(snapshot.referencePopulation.coveredPrefix >= snapshot.referencePopulation.prefixN)
        require(snapshot.referencePopulation.prefixN > 0)
        require(snapshot.referencePopulation.minObservations >= 0)
        requireDigest(snapshot.referencePopulation.contentDigest)
        snapshot.referencePopulation.distributionSha256?.let(::requireDigest)

        val playerIds = snapshot.players.map { it.id }.toSet()
        require(playerIds.size == snapshot.players.size) { "duplicate evaluation player identity" }
        require(snapshot.players.all { it.identity.isNotBlank() }) {
            "evaluation-player identity must not be blank"
        }
        snapshot.rows.forEach { row ->
            require(row.playerId in playerIds) { "evidence row references an unknown player" }
            require(row.preMovePly >= 1) { "pre-move ply must be one-based" }
            require(row.positionIdentity.isNotBlank())
            require(row.move.isNotBlank())
            require(row.playerColor in setOf("WHITE", "BLACK"))
            require(row.loss.isFinite() && row.loss >= 0.0)
            require(row.engineDepth > 0)
            require(row.practicalWins >= 0 && row.practicalDraws >= 0 && row.practicalLosses >= 0)
            require(!row.practicalEligible || row.practicalCandidate) {
                "eligible practical evidence must be a candidate"
            }
        }
    }

    fun bind(
        snapshot: EvaluationEvidenceSnapshot,
        selectedReferencePopulation: EvaluationReferencePopulation,
    ): EvaluationEvidenceSnapshot {
        validate(snapshot)
        require(snapshot.referencePopulation == selectedReferencePopulation) {
            "evaluation evidence is bound to a different finalized reference population"
        }
        return snapshot
    }

    fun reconstruct(snapshot: EvaluationEvidenceSnapshot): EvaluationEvidenceReconstruction {
        validate(snapshot)
        val canonicalRows = canonicalize(snapshot.rows)
        return aggregate(snapshot.players, snapshot.configuration.thresholds, canonicalRows)
    }

    /**
     * Verifies occurrence linkage against the finalized #431 occurrence/binding tables,
     * canonicalizes duplicate/conflicting rows, and idempotently retains the minimal
     * evidence in the immutable #430 tables. Returns the persisted (or already-persisted,
     * on an identical retry) snapshot id.
     */
    @Transactional
    fun persist(
        snapshot: EvaluationEvidenceSnapshot,
        selectedReferencePopulation: EvaluationReferencePopulation,
    ): UUID {
        val db = requireJdbcTemplate()
        bind(snapshot, selectedReferencePopulation)
        val canonicalRows = canonicalize(snapshot.rows)
        verifyOccurrenceLinkage(db, snapshot.referencePopulation, canonicalRows)
        val digest = computeEvidenceDigest(snapshot.referencePopulation, canonicalRows)

        val existing =
            db.query(
                "SELECT id, evidence_digest FROM evaluation_evidence_snapshot WHERE source_run_id = ?",
                { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("evidence_digest") },
                snapshot.referencePopulation.sourceRunId,
            ).singleOrNull()
        if (existing != null) {
            val (existingId, existingDigest) = existing
            if (existingDigest == digest) return existingId
            throw EvaluationEvidenceIntegrityException(
                "Conflicting evaluation evidence replacement for source run ${snapshot.referencePopulation.sourceRunId}",
            )
        }

        val id = UUID.randomUUID()
        insertSnapshot(db, id, snapshot, digest)
        insertRows(db, id, canonicalRows)
        return id
    }

    /** Reconstructs threshold/practical/outcome facts using only retained #430 evidence rows. */
    @Transactional(readOnly = true)
    fun reconstructPersisted(sourceRunId: UUID): EvaluationEvidenceReconstruction {
        val db = requireJdbcTemplate()
        val (snapshotId, thresholds) =
            db.query(
                "SELECT id, thresholds FROM evaluation_evidence_snapshot WHERE source_run_id = ?",
                { rs, _ ->
                    val thresholdValues =
                        (rs.getArray("thresholds").array as Array<*>).map { (it as Number).toDouble() }.toSet()
                    rs.getObject("id", UUID::class.java) to thresholdValues
                },
                sourceRunId,
            ).singleOrNull()
                ?: throw EvaluationEvidenceIntegrityException(
                    "No retained evaluation evidence for source run $sourceRunId",
                )

        val rows =
            db.query(
                "SELECT player_id, game_id, occurrence_id, position_identity, pre_move_ply, move_played, " +
                    "player_color, loss, engine_depth, observed_outcome, objective_outcome, practical_candidate, " +
                    "practical_eligible, practical_wins, practical_draws, practical_losses " +
                    "FROM evaluation_evidence_row WHERE snapshot_id = ?",
                { rs, _ ->
                    EvaluationEvidenceRow(
                        playerId = rs.getObject("player_id", UUID::class.java),
                        gameId = rs.getObject("game_id", UUID::class.java),
                        occurrenceId = rs.getObject("occurrence_id", UUID::class.java),
                        positionIdentity = rs.getString("position_identity"),
                        preMovePly = rs.getInt("pre_move_ply"),
                        move = rs.getString("move_played"),
                        playerColor = rs.getString("player_color"),
                        loss = rs.getDouble("loss"),
                        engineDepth = rs.getInt("engine_depth"),
                        observedOutcome = ObservedGameOutcome.valueOf(rs.getString("observed_outcome")),
                        objectiveOutcome = ObjectiveOutcome.valueOf(rs.getString("objective_outcome")),
                        practicalCandidate = rs.getBoolean("practical_candidate"),
                        practicalEligible = rs.getBoolean("practical_eligible"),
                        practicalWins = rs.getInt("practical_wins"),
                        practicalDraws = rs.getInt("practical_draws"),
                        practicalLosses = rs.getInt("practical_losses"),
                    )
                },
                snapshotId,
            )

        val players = rows.map { it.playerId }.distinct().map { EvaluationEvidencePlayer(it, it.toString()) }.toSet()
        return aggregate(players, thresholds, rows)
    }

    private fun aggregate(
        players: Set<EvaluationEvidencePlayer>,
        thresholds: Set<Double>,
        rows: List<EvaluationEvidenceRow>,
    ): EvaluationEvidenceReconstruction {
        val rowsByPlayer = rows.groupBy { it.playerId }
        val objective =
            players.associate { player ->
                player.id to
                    thresholds.associateWith { threshold ->
                        rowsByPlayer[player.id].orEmpty().count { it.loss >= threshold }
                    }
            }
        val practical =
            players.associate { player ->
                val candidateRows = rowsByPlayer[player.id].orEmpty().filter { it.practicalCandidate }
                val byGame =
                    candidateRows.groupBy { it.gameId }.mapValues { (_, gameRows) -> gameRows.first() }
                val eligible = byGame.values.filter { it.practicalEligible }
                val excluded = eligible.count { it.practicalWins + it.practicalDraws + it.practicalLosses == 0 }
                player.id to
                    PracticalEvidenceCounts(
                        candidateGames = byGame.size,
                        eligibleGames = eligible.size,
                        ineligibleGames = byGame.values.count { !it.practicalEligible },
                        excludedGames = excluded,
                        wins = eligible.sumOf { it.practicalWins },
                        draws = eligible.sumOf { it.practicalDraws },
                        losses = eligible.sumOf { it.practicalLosses },
                    )
            }
        val outcomes =
            players.associate { player ->
                player.id to
                    rowsByPlayer[player.id].orEmpty()
                        .distinctBy { it.gameId }
                        .groupBy { it.observedOutcome }
                        .mapValues { it.value.size }
            }
        return EvaluationEvidenceReconstruction(objective, practical, outcomes)
    }

    /**
     * Deterministically collapses identical duplicate rows and rejects conflicting duplicates.
     * Duplicates are resolved by evidence identity, never by list/iteration order:
     *  1. Rows sharing (playerId, occurrenceId) must be fully identical; identical duplicates collapse.
     *  2. Rows sharing (playerId, gameId) must agree on the game-level observed outcome and
     *     practical-evidence contribution.
     *  3. No two distinct occurrences may claim the same (playerId, gameId, preMovePly) location.
     */
    private fun canonicalize(rows: List<EvaluationEvidenceRow>): List<EvaluationEvidenceRow> {
        val canonicalByOccurrence =
            rows.groupBy { it.playerId to it.occurrenceId }.map { (key, group) ->
                val distinct = group.distinct()
                if (distinct.size > 1) {
                    throw EvaluationEvidenceIntegrityException(
                        "Conflicting evidence rows for player ${key.first} occurrence ${key.second}",
                    )
                }
                distinct.single()
            }

        canonicalByOccurrence.groupBy { it.playerId to it.gameId }.forEach { (key, group) ->
            if (group.map { it.observedOutcome }.toSet().size > 1) {
                throw EvaluationEvidenceIntegrityException(
                    "Conflicting observed outcomes for player ${key.first} game ${key.second}",
                )
            }
            val practicalFacts =
                group.map {
                    listOf(it.practicalCandidate, it.practicalEligible, it.practicalWins, it.practicalDraws, it.practicalLosses)
                }.toSet()
            if (practicalFacts.size > 1) {
                throw EvaluationEvidenceIntegrityException(
                    "Conflicting practical evidence for player ${key.first} game ${key.second}",
                )
            }
        }

        canonicalByOccurrence.groupBy { Triple(it.playerId, it.gameId, it.preMovePly) }.forEach { (key, group) ->
            if (group.size > 1) {
                throw EvaluationEvidenceIntegrityException(
                    "Conflicting evidence rows for player ${key.first} game ${key.second} pre-move ply ${key.third}",
                )
            }
        }

        return canonicalByOccurrence
    }

    /** Verifies every row's occurrence exists, is finalized, matches the reference population, and agrees on facts. */
    private fun verifyOccurrenceLinkage(
        db: JdbcTemplate,
        referencePopulation: EvaluationReferencePopulation,
        rows: List<EvaluationEvidenceRow>,
    ) {
        val occurrenceIds = rows.map { it.occurrenceId }.distinct()
        if (occurrenceIds.isEmpty()) return
        val placeholders = occurrenceIds.joinToString(",") { "?" }
        val occurrences =
            db.query(
                "SELECT id, source_run_id, position_hash, pre_move_ply, content_digest, covered_prefix " +
                    "FROM human_move_corpus_occurrence WHERE id IN ($placeholders)",
                { rs, _ ->
                    rs.getObject("id", UUID::class.java) to
                        OccurrenceRecord(
                            sourceRunId = rs.getObject("source_run_id", UUID::class.java),
                            positionHash = rs.getString("position_hash"),
                            preMovePly = rs.getInt("pre_move_ply"),
                            contentDigest = rs.getString("content_digest"),
                            coveredPrefix = rs.getObject("covered_prefix") as Int?,
                        )
                },
                *occurrenceIds.toTypedArray(),
            ).toMap()

        rows.forEach { row ->
            val occurrence =
                occurrences[row.occurrenceId]
                    ?: throw EvaluationEvidenceIntegrityException(
                        "Evidence row references an unknown occurrence ${row.occurrenceId}",
                    )
            if (occurrence.contentDigest == null || occurrence.coveredPrefix == null) {
                throw EvaluationEvidenceIntegrityException(
                    "Occurrence ${row.occurrenceId} is not yet bound to a finalized #426 population",
                )
            }
            if (occurrence.sourceRunId != referencePopulation.sourceRunId ||
                occurrence.contentDigest != referencePopulation.contentDigest ||
                occurrence.coveredPrefix != referencePopulation.coveredPrefix
            ) {
                throw EvaluationEvidenceIntegrityException(
                    "Occurrence ${row.occurrenceId} is bound to a different finalized #426 population",
                )
            }
            if (occurrence.positionHash != row.positionIdentity || occurrence.preMovePly != row.preMovePly) {
                throw EvaluationEvidenceIntegrityException(
                    "Occurrence ${row.occurrenceId} position/ply facts disagree with the evidence row",
                )
            }
        }
    }

    private fun computeEvidenceDigest(
        referencePopulation: EvaluationReferencePopulation,
        rows: List<EvaluationEvidenceRow>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            referencePopulation.contentDigest,
            referencePopulation.sourceRunId.toString(),
            referencePopulation.coveredPrefix.toString(),
            referencePopulation.prefixN.toString(),
            referencePopulation.ratingBand,
            referencePopulation.minObservations.toString(),
            referencePopulation.calculationVersion,
            referencePopulation.distributionSha256 ?: "",
        ).forEach { updateDigest(digest, it) }

        rows.sortedWith(compareBy({ it.playerId }, { it.occurrenceId })).forEach { row ->
            listOf(
                row.playerId.toString(),
                row.gameId.toString(),
                row.occurrenceId.toString(),
                row.positionIdentity,
                row.preMovePly.toString(),
                row.move,
                row.playerColor,
                row.loss.toString(),
                row.engineDepth.toString(),
                row.observedOutcome.name,
                row.objectiveOutcome.name,
                row.practicalCandidate.toString(),
                row.practicalEligible.toString(),
                row.practicalWins.toString(),
                row.practicalDraws.toString(),
                row.practicalLosses.toString(),
            ).forEach { updateDigest(digest, it) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateDigest(
        digest: MessageDigest,
        value: String,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun insertSnapshot(
        db: JdbcTemplate,
        id: UUID,
        snapshot: EvaluationEvidenceSnapshot,
        digest: String,
    ) {
        db.execute(
            "INSERT INTO evaluation_evidence_snapshot (" +
                "id, source_run_id, content_digest, covered_prefix, prefix_n, rating_band, min_observations, " +
                "calculation_version, distribution_sha256, occurrence_evidence_id, thresholds, min_mistake_count, " +
                "min_times_reached, color, platform, observation_window_days, source_revision, engine_identity, " +
                "parser_identity, evidence_digest" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ) { ps: java.sql.PreparedStatement ->
            val population = snapshot.referencePopulation
            val configuration = snapshot.configuration
            val thresholdsArray = ps.connection.createArrayOf("float8", configuration.thresholds.sorted().toTypedArray())
            var index = 1
            ps.setObject(index++, id)
            ps.setObject(index++, population.sourceRunId)
            ps.setString(index++, population.contentDigest)
            ps.setInt(index++, population.coveredPrefix)
            ps.setInt(index++, population.prefixN)
            ps.setString(index++, population.ratingBand)
            ps.setInt(index++, population.minObservations)
            ps.setString(index++, population.calculationVersion)
            ps.setString(index++, population.distributionSha256)
            if (snapshot.occurrenceEvidenceId != null) {
                ps.setObject(index++, snapshot.occurrenceEvidenceId)
            } else {
                ps.setNull(index++, java.sql.Types.OTHER)
            }
            ps.setArray(index++, thresholdsArray)
            ps.setInt(index++, configuration.minMistakeCount)
            ps.setInt(index++, configuration.minTimesReached)
            ps.setString(index++, configuration.color)
            ps.setString(index++, configuration.platform)
            if (configuration.observationWindowDays != null) {
                ps.setInt(index++, configuration.observationWindowDays)
            } else {
                ps.setNull(index++, java.sql.Types.INTEGER)
            }
            ps.setString(index++, snapshot.sourceRevision)
            ps.setString(index++, snapshot.engineIdentity)
            ps.setString(index++, snapshot.parserIdentity)
            ps.setString(index, digest)
            ps.executeUpdate()
        }
    }

    private fun insertRows(
        db: JdbcTemplate,
        snapshotId: UUID,
        rows: List<EvaluationEvidenceRow>,
    ) {
        if (rows.isEmpty()) return
        db.batchUpdate(
            "INSERT INTO evaluation_evidence_row (" +
                "id, snapshot_id, player_id, game_id, occurrence_id, position_identity, pre_move_ply, move_played, " +
                "player_color, loss, engine_depth, observed_outcome, objective_outcome, practical_candidate, " +
                "practical_eligible, practical_wins, practical_draws, practical_losses" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            rows.map { row ->
                arrayOf<Any>(
                    UUID.randomUUID(),
                    snapshotId,
                    row.playerId,
                    row.gameId,
                    row.occurrenceId,
                    row.positionIdentity,
                    row.preMovePly,
                    row.move,
                    row.playerColor,
                    row.loss,
                    row.engineDepth,
                    row.observedOutcome.name,
                    row.objectiveOutcome.name,
                    row.practicalCandidate,
                    row.practicalEligible,
                    row.practicalWins,
                    row.practicalDraws,
                    row.practicalLosses,
                )
            },
        )
    }

    private fun requireJdbcTemplate(): JdbcTemplate =
        jdbcTemplate ?: throw IllegalStateException(
            "This operation requires a JdbcTemplate-backed EvaluationEvidenceSnapshotService",
        )

    private fun requireDigest(value: String) {
        require(value.matches(HEX_DIGEST)) { "digest must be a 64-character hexadecimal value" }
    }

    private data class OccurrenceRecord(
        val sourceRunId: UUID,
        val positionHash: String,
        val preMovePly: Int,
        val contentDigest: String?,
        val coveredPrefix: Int?,
    )

    companion object {
        val APPROVED_THRESHOLDS = setOf(0.30, 0.50, 0.80)
        private val HEX_DIGEST = Regex("[0-9a-fA-F]{64}")
    }
}
