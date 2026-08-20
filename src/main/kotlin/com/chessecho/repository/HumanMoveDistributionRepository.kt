package com.chessecho.repository

import com.chessecho.domain.HumanMoveDistribution
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface HumanMoveDistributionRepository : JpaRepository<HumanMoveDistribution, UUID> {
    fun findByPositionIdAndRatingBand(
        positionId: UUID,
        ratingBand: String,
    ): List<HumanMoveDistribution>
}
