package com.chessecho.repository

import com.chessecho.domain.HumanMoveCorpusProjection
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HumanMoveCorpusProjectionRepository : JpaRepository<HumanMoveCorpusProjection, UUID> {
    fun findByContentDigestAndSourceRunIdAndPrefixNAndRatingBandAndMinObservationsAndCalculationVersion(
        contentDigest: String,
        sourceRunId: UUID,
        prefixN: Int,
        ratingBand: String,
        minObservations: Int,
        calculationVersion: String,
    ): HumanMoveCorpusProjection?
}
