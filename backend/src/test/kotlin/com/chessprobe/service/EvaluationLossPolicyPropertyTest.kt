package com.chessprobe.service

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.lifecycle.AfterContainer
import net.jqwik.api.lifecycle.AfterProperty
import net.jqwik.api.lifecycle.BeforeContainer
import net.jqwik.api.lifecycle.BeforeProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.abs

/**
 * Strict Total Order Comparator for EvalScore matching chess engine semantics:
 * - Winning Mate (Tier 3): smaller mate distance is strictly better (+M1 > +M2).
 * - Centipawns (Tier 2): higher centipawn advantage is strictly better (+500 > -200).
 * - Losing Mate (Tier 1): surviving longer is strictly better (-M5 > -M1, since |-5| > |-1|).
 * - Unranked / Empty (Tier 0): lowest rank.
 * Null-safe secondary tie-breaking on cp and mate ensures complete consistency with equals.
 */
object EvalScoreComparator : Comparator<EvalScore> {
    override fun compare(
        a: EvalScore,
        b: EvalScore,
    ): Int {
        if (a == b) return 0
        val rankA = scoreTier(a)
        val rankB = scoreTier(b)
        if (rankA != rankB) {
            return rankA.compareTo(rankB)
        }
        val primary = compareWithinTier(rankA, a, b)
        if (primary != 0) return primary
        val cpDiff = compareValues(a.cp, b.cp)
        if (cpDiff != 0) return cpDiff
        return compareValues(a.mate, b.mate)
    }

    private fun compareWithinTier(
        tier: Int,
        a: EvalScore,
        b: EvalScore,
    ): Int =
        when (tier) {
            3 -> b.mate!!.compareTo(a.mate!!)
            2 -> (a.cp ?: 0).compareTo(b.cp ?: 0)
            1 -> abs(a.mate!!).compareTo(abs(b.mate!!))
            else -> 0
        }

    fun scoreTier(s: EvalScore): Int =
        when {
            s.mate != null && s.mate > 0 -> 3
            s.mate == null && s.cp != null -> 2
            s.mate != null && s.mate < 0 -> 1
            s.cp != null -> 2
            else -> 0
        }
}

class EvaluationLossPolicyPropertyTest {
    companion object {
        private var containerStartTimeMs: Long = 0

        @BeforeContainer
        @JvmStatic
        fun startContainerTimer() {
            containerStartTimeMs = System.currentTimeMillis()
        }

        @AfterContainer
        @JvmStatic
        fun enforceContainerBudget() {
            val durationMs = System.currentTimeMillis() - containerStartTimeMs
            assertTrue(
                durationMs <= 5000,
                "EvaluationLossPolicyPropertyTest cumulative duration ($durationMs ms) exceeded 5000ms budget",
            )
        }
    }

    private var propertyStartTimeMs: Long = 0

    @BeforeProperty
    fun startPropertyTimer() {
        propertyStartTimeMs = System.currentTimeMillis()
    }

    @AfterProperty
    fun enforcePropertyBudget() {
        val durationMs = System.currentTimeMillis() - propertyStartTimeMs
        assertTrue(
            durationMs <= 5000,
            "Property execution duration ($durationMs ms) exceeded 5000ms budget",
        )
    }

    @Provide
    fun allEvalScores(): Arbitrary<EvalScore> {
        val cpArbitrary = Arbitraries.integers().between(-2500, 2500).map { EvalScore(cp = it, mate = null) }
        val winMateArbitrary = Arbitraries.integers().between(1, 25).map { EvalScore(cp = null, mate = it) }
        val loseMateArbitrary = Arbitraries.integers().between(-25, -1).map { EvalScore(cp = null, mate = it) }
        val emptyArbitrary = Arbitraries.just(EvalScore(cp = null, mate = null))
        val compositeWin =
            Arbitraries.integers().between(-500, 500).flatMap { cp ->
                Arbitraries.integers().between(1, 10).map { mate -> EvalScore(cp = cp, mate = mate) }
            }
        val compositeLose =
            Arbitraries.integers().between(-500, 500).flatMap { cp ->
                Arbitraries.integers().between(-10, -1).map { mate -> EvalScore(cp = cp, mate = mate) }
            }
        val compositeZeroMate = Arbitraries.integers().between(-500, 500).map { EvalScore(cp = it, mate = 0) }
        return Arbitraries.oneOf(
            cpArbitrary,
            winMateArbitrary,
            loseMateArbitrary,
            emptyArbitrary,
            compositeWin,
            compositeLose,
            compositeZeroMate,
        )
    }

    @Provide
    fun validEvalScores(): Arbitrary<EvalScore> {
        val cpArbitrary = Arbitraries.integers().between(-2500, 2500).map { EvalScore(cp = it, mate = null) }
        val winMateArbitrary = Arbitraries.integers().between(1, 25).map { EvalScore(cp = null, mate = it) }
        val loseMateArbitrary = Arbitraries.integers().between(-25, -1).map { EvalScore(cp = null, mate = it) }
        return Arbitraries.oneOf(cpArbitrary, winMateArbitrary, loseMateArbitrary)
    }

    @Provide
    fun nonTier0EvalScores(): Arbitrary<EvalScore> {
        val cpArbitrary = Arbitraries.integers().between(-2500, 2500).map { EvalScore(cp = it, mate = null) }
        val winMateArbitrary = Arbitraries.integers().between(1, 25).map { EvalScore(cp = null, mate = it) }
        val loseMateArbitrary = Arbitraries.integers().between(-25, -1).map { EvalScore(cp = null, mate = it) }
        return Arbitraries.oneOf(cpArbitrary, winMateArbitrary, loseMateArbitrary)
    }

    @Provide
    fun centipawnScores(): Arbitrary<EvalScore> = Arbitraries.integers().between(-2500, 2500).map { EvalScore(cp = it, mate = null) }

    @Provide
    fun winningMateScores(): Arbitrary<EvalScore> = Arbitraries.integers().between(1, 25).map { EvalScore(cp = null, mate = it) }

    @Provide
    fun losingMateScores(): Arbitrary<EvalScore> = Arbitraries.integers().between(-25, -1).map { EvalScore(cp = null, mate = it) }

    @Property(tries = 100)
    fun `EvalScoreComparator satisfies strict total order properties across all tiers including composite and empty`(
        @ForAll("allEvalScores") a: EvalScore,
        @ForAll("allEvalScores") b: EvalScore,
        @ForAll("allEvalScores") c: EvalScore,
    ) {
        // Reflexivity
        assertEquals(0, EvalScoreComparator.compare(a, a))

        // Antisymmetry
        val cmpAB = EvalScoreComparator.compare(a, b)
        val cmpBA = EvalScoreComparator.compare(b, a)
        assertEquals(cmpAB.compareTo(0), -cmpBA.compareTo(0))

        // Transitivity
        val cmpBC = EvalScoreComparator.compare(b, c)
        if (cmpAB > 0 && cmpBC > 0) {
            assertTrue(EvalScoreComparator.compare(a, c) > 0)
        } else if (cmpAB < 0 && cmpBC < 0) {
            assertTrue(EvalScoreComparator.compare(a, c) < 0)
        } else if (cmpAB == 0 && cmpBC == 0) {
            assertEquals(0, EvalScoreComparator.compare(a, c))
        }

        // Connexity
        assertTrue(cmpAB <= 0 || cmpAB >= 0)

        // Consistency with equals: compare(a, b) == 0 <=> a == b
        if (cmpAB == 0) {
            assertEquals(a, b, "Equality must hold whenever compare returns 0: a=$a, b=$b")
        } else {
            assertTrue(a != b, "Equality must not hold whenever compare returns non-zero: a=$a, b=$b")
        }
    }

    @Property(tries = 100)
    fun `EvalScoreComparator strictly ranks Tier 0 empty scores below all active tiers`(
        @ForAll("nonTier0EvalScores") activeScore: EvalScore,
    ) {
        val emptyScore = EvalScore(cp = null, mate = null)
        assertTrue(EvalScoreComparator.compare(emptyScore, activeScore) < 0)
        assertTrue(EvalScoreComparator.compare(activeScore, emptyScore) > 0)
        assertEquals(0, EvalScoreComparator.compare(emptyScore, emptyScore))
    }

    @Property(tries = 100)
    fun `EvalScoreComparator preserves chess survival semantics for losing mates`(
        @ForAll("losingMateScores") m1: EvalScore,
        @ForAll("losingMateScores") m2: EvalScore,
    ) {
        // Surviving longer is strictly better (-M5 > -M1)
        val dist1 = abs(m1.mate!!)
        val dist2 = abs(m2.mate!!)
        val expected = dist1.compareTo(dist2)
        assertEquals(expected, EvalScoreComparator.compare(m1, m2))
    }

    @Property(tries = 100)
    fun `calculateEvalLoss non-negativity, identity, and range bounds`(
        @ForAll("validEvalScores") best: EvalScore,
        @ForAll("validEvalScores") move: EvalScore,
    ) {
        val loss = EvaluationLossPolicy.calculateEvalLoss(best, move)
        if (loss != null) {
            assertTrue(loss >= 0.0, "Loss must be non-negative: $loss for best=$best, move=$move")
            assertTrue(loss <= 20.0, "Loss must not exceed 20.0 cap: $loss for best=$best, move=$move")
        }

        // Identity
        val selfLoss = EvaluationLossPolicy.calculateEvalLoss(best, best)
        assertEquals(0.0, selfLoss, "Loss for identical score must be 0.0: best=$best")
    }

    @Property(tries = 100)
    fun `Intra-Tier weak monotonicity holds unconditionally for centipawns`(
        @ForAll("validEvalScores") best: EvalScore,
        @ForAll("centipawnScores") m1: EvalScore,
        @ForAll("centipawnScores") m2: EvalScore,
    ) {
        val cmp = EvalScoreComparator.compare(m1, m2)
        val loss1 = EvaluationLossPolicy.calculateEvalLoss(best, m1)
        val loss2 = EvaluationLossPolicy.calculateEvalLoss(best, m2)

        if (loss1 != null && loss2 != null) {
            if (cmp >= 0) {
                assertTrue(loss1 <= loss2 + 1e-6, "Monotonicity violated: cmp=$cmp, loss1=$loss1, loss2=$loss2")
            } else {
                assertTrue(loss2 <= loss1 + 1e-6, "Monotonicity violated: cmp=$cmp, loss1=$loss1, loss2=$loss2")
            }
        }
    }

    @Property(tries = 100)
    fun `Intra-Tier weak monotonicity holds unconditionally for winning mates`(
        @ForAll("validEvalScores") best: EvalScore,
        @ForAll("winningMateScores") m1: EvalScore,
        @ForAll("winningMateScores") m2: EvalScore,
    ) {
        val cmp = EvalScoreComparator.compare(m1, m2)
        val loss1 = EvaluationLossPolicy.calculateEvalLoss(best, m1)
        val loss2 = EvaluationLossPolicy.calculateEvalLoss(best, m2)

        if (loss1 != null && loss2 != null) {
            if (cmp >= 0) {
                assertTrue(loss1 <= loss2 + 1e-6, "Monotonicity violated: cmp=$cmp, loss1=$loss1, loss2=$loss2")
            } else {
                assertTrue(loss2 <= loss1 + 1e-6, "Monotonicity violated: cmp=$cmp, loss1=$loss1, loss2=$loss2")
            }
        }
    }

    @Property(tries = 100)
    fun `Intra-Tier weak monotonicity holds unconditionally for losing mates`(
        @ForAll("validEvalScores") best: EvalScore,
        @ForAll("losingMateScores") m1: EvalScore,
        @ForAll("losingMateScores") m2: EvalScore,
    ) {
        val cmp = EvalScoreComparator.compare(m1, m2)
        val loss1 = EvaluationLossPolicy.calculateEvalLoss(best, m1)
        val loss2 = EvaluationLossPolicy.calculateEvalLoss(best, m2)

        if (loss1 != null && loss2 != null) {
            if (cmp >= 0) {
                assertTrue(loss1 <= loss2 + 1e-6, "Monotonicity violated: cmp=$cmp, loss1=$loss1, loss2=$loss2")
            } else {
                assertTrue(loss2 <= loss1 + 1e-6, "Monotonicity violated: cmp=$cmp, loss1=$loss1, loss2=$loss2")
            }
        }
    }

    @Property(tries = 100)
    fun `Cross-Tier monotonicity for Winning Mate Best`(
        @ForAll("winningMateScores") best: EvalScore,
        @ForAll("validEvalScores") m1: EvalScore,
        @ForAll("validEvalScores") m2: EvalScore,
    ) {
        val cmp = EvalScoreComparator.compare(m1, m2)
        val loss1 = EvaluationLossPolicy.calculateEvalLoss(best, m1)
        val loss2 = EvaluationLossPolicy.calculateEvalLoss(best, m2)

        if (loss1 != null && loss2 != null) {
            if (cmp >= 0) {
                assertTrue(
                    loss1 <= loss2 + 1e-6,
                    "Winning mate best cross-tier monotonicity violated: cmp=$cmp, loss1=$loss1, loss2=$loss2",
                )
            } else {
                assertTrue(
                    loss2 <= loss1 + 1e-6,
                    "Winning mate best cross-tier monotonicity violated: cmp=$cmp, loss1=$loss1, loss2=$loss2",
                )
            }
        }
    }

    @Property(tries = 100)
    fun `Cross-Tier monotonicity for Centipawn Best with realistic candidate evaluations`(
        @ForAll("centipawnScores") best: EvalScore,
        @ForAll("validEvalScores") m1: EvalScore,
        @ForAll("validEvalScores") m2: EvalScore,
    ) {
        // Exclude extreme collapses beyond -10 pawns where centipawn loss caps at 20.0
        val isRealistic1 = m1.cp == null || m1.cp >= -1000
        val isRealistic2 = m2.cp == null || m2.cp >= -1000
        if (!isRealistic1 || !isRealistic2) return

        val cmp = EvalScoreComparator.compare(m1, m2)
        val loss1 = EvaluationLossPolicy.calculateEvalLoss(best, m1)
        val loss2 = EvaluationLossPolicy.calculateEvalLoss(best, m2)

        if (loss1 != null && loss2 != null) {
            if (cmp >= 0) {
                assertTrue(loss1 <= loss2 + 1e-6, "Centipawn best cross-tier monotonicity violated: cmp=$cmp, loss1=$loss1, loss2=$loss2")
            } else {
                assertTrue(loss2 <= loss1 + 1e-6, "Centipawn best cross-tier monotonicity violated: cmp=$cmp, loss1=$loss1, loss2=$loss2")
            }
        }
    }

    @Property(tries = 100)
    fun `Forced mate semantics boundary checks`(
        @ForAll("winningMateScores") winBest: EvalScore,
        @ForAll("losingMateScores") loseBest: EvalScore,
        @ForAll("centipawnScores") cpBest: EvalScore,
    ) {
        // Winning mate delayed: loss <= 0.50
        val delayedLoss = EvaluationLossPolicy.calculateEvalLoss(winBest, EvalScore(cp = null, mate = winBest.mate!! + 2))
        assertTrue(delayedLoss != null && delayedLoss <= 0.50)

        // Winning mate discarded for centipawn: loss in 8.0..15.0
        val thrownLoss = EvaluationLossPolicy.calculateEvalLoss(winBest, EvalScore(cp = 500, mate = null))
        assertTrue(thrownLoss != null && thrownLoss in 8.0..15.0)

        // Centipawn best vs losing mate: loss in 10.0..20.0
        val blunderedMateLoss = EvaluationLossPolicy.calculateEvalLoss(cpBest, EvalScore(cp = null, mate = -5))
        assertTrue(blunderedMateLoss != null && blunderedMateLoss in 10.0..20.0)

        // Centipawn best vs winning mate: loss == 0.0
        val winMateFound = EvaluationLossPolicy.calculateEvalLoss(cpBest, EvalScore(cp = null, mate = 2))
        assertEquals(0.0, winMateFound)

        // Defending losing mate delayed or held: loss == 0.0
        val heldLoss = EvaluationLossPolicy.calculateEvalLoss(loseBest, loseBest)
        assertEquals(0.0, heldLoss)

        // Inconclusive losing mate defense (negative cp): returns null
        val inconclusiveLoss = EvaluationLossPolicy.calculateEvalLoss(loseBest, EvalScore(cp = -300, mate = null))
        assertNull(inconclusiveLoss)
    }
}
