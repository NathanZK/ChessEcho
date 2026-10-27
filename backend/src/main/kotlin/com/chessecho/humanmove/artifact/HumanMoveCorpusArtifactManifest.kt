package com.chessecho.humanmove.artifact

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

data class HumanMoveCorpusArtifactManifest(
    val formatVersion: Int,
    val snapshotKind: String,
    val sourceRunId: UUID,
    val sourceStatus: String,
    val sourceFrontier: Int,
    val coveredPrefix: Int,
    val ratingBand: String,
    val sourceRunMetadata: JsonNode,
    val gameCount: Int,
    val observationCount: Int,
    val gameBytes: Long,
    val observationBytes: Long,
    val gamesSha256: String,
    val observationsSha256: String,
    val e6Eligible: Boolean,
)
