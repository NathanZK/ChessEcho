package com.chessecho.humanmove.artifact

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path
import java.time.Instant

data class HumanMoveCorpusArtifactRecord(
    val ordinal: Int,
    val value: JsonNode,
)

/** One verified `games.ndjson` line, decoded to its portable fields. */
data class HumanMoveCorpusGameRecord(
    val qualifyingOrdinal: Int,
    val providerGameId: String,
    val traversedPlayer: String,
    val opponent: String,
    val opponentSide: String,
    val opponentRating: Int,
    val rules: String?,
    val timeClass: String,
    val bfsDepth: Int,
    val pgn: String,
    val pgnSha256: String,
    val observationTotal: Int,
    val distinctMoveCount: Int,
    val committedAt: Instant,
)

/** One verified `observations.ndjson` line, decoded to its portable fields. */
data class HumanMoveCorpusObservationRecord(
    val qualifyingOrdinal: Int,
    val positionHash: String,
    val positionFen: String,
    val movePlayed: String,
    val observationCount: Int,
)

/**
 * Verified artifact identity and metadata. Use the artifact service's record
 * callbacks to reread evidence without collecting corpus-sized lists.
 */
data class VerifiedCorpusArtifact(
    val digest: String,
    val manifest: HumanMoveCorpusArtifactManifest,
    val manifestJson: String,
    val archivePath: Path? = null,
)

/** A supplied expected digest, uploaded bytes, or manifest do not match trusted or recomputed evidence. */
class CorpusArtifactConflict(message: String) : RuntimeException(message)

/** The archive fails structural, size, or cross-record verification before any database mutation. */
class CorpusArtifactInvalidException(message: String) : RuntimeException(message)
