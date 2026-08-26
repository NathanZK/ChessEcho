package com.chessecho.repository

import com.chessecho.domain.HumanMoveDistribution
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface HumanMoveDistributionRepository : JpaRepository<HumanMoveDistribution, UUID> {
    fun findByPositionIdAndRatingBand(
        positionId: UUID,
        ratingBand: String,
    ): List<HumanMoveDistribution>

    /**
     * Number of distinct positions currently represented in the accumulated
     * distribution for a given rating band. Used to report finalization results.
     */
    @Query(
        "SELECT COUNT(DISTINCT d.positionId) FROM HumanMoveDistribution d " +
            "WHERE d.ratingBand = :ratingBand",
    )
    fun countDistinctPositionsForBand(
        @Param("ratingBand") ratingBand: String,
    ): Long

    /**
     * Number of distinct positions whose global SUM(observation_count) for the
     * given rating band is strictly below [minObservations]. Computed prior to
     * the finalization DELETE so we can report positions removed accurately.
     */
    @Query(
        "SELECT COUNT(d.positionId) FROM HumanMoveDistribution d " +
            "WHERE d.ratingBand = :ratingBand " +
            "GROUP BY d.positionId " +
            "HAVING SUM(d.observationCount) < :minObservations",
    )
    fun subThresholdPositionCountsForBand(
        @Param("ratingBand") ratingBand: String,
        @Param("minObservations") minObservations: Int,
    ): List<Long>

    /**
     * Set-based finalization delete: removes every distribution row belonging to
     * a position whose global observation total for the band is below
     * [minObservations]. Positions meeting the threshold retain all of their
     * move rows and their observation counts. Returns the number of rows
     * deleted.
     */
    @Modifying
    @Query(
        "DELETE FROM HumanMoveDistribution d " +
            "WHERE d.ratingBand = :ratingBand " +
            "AND d.positionId IN (" +
            "SELECT d2.positionId FROM HumanMoveDistribution d2 " +
            "WHERE d2.ratingBand = :ratingBand " +
            "GROUP BY d2.positionId " +
            "HAVING SUM(d2.observationCount) < :minObservations" +
            ")",
    )
    fun deleteSubThresholdRowsForBand(
        @Param("ratingBand") ratingBand: String,
        @Param("minObservations") minObservations: Int,
    ): Int
}
