package com.bess.salestrainer.core.model.fsrs

import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.Rating
import java.time.Duration
import java.time.Instant
import kotlin.math.E
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure Kotlin port of the FSRS v6 scheduler (py-fsrs 6.3.1).
 *
 * Source (MIT License): https://github.com/open-spaced-repetition/py-fsrs
 * Copyright (c) open-spaced-repetition. Ported to Kotlin for offline on-device
 * scheduling. All formulas, parameter ordering, and state transitions match
 * scheduler.py exactly so the golden vectors in `fsrs_golden.json` pass.
 *
 * Determinism: pass `enableFuzzing = false` (the default) in tests and for any
 * reproducible schedule. When enabled, the same fuzz algorithm as py-fsrs is
 * applied to Review-state intervals >= 2.5 days.
 */
class FsrsScheduler(
    val parameters: DoubleArray = DEFAULT_PARAMETERS.copyOf(),
    val desiredRetention: Double = 0.9,
    /** Learning-state steps, e.g. 1 min, 10 min. */
    val learningSteps: List<Duration> = listOf(Duration.ofMinutes(1), Duration.ofMinutes(10)),
    /** Relearning-state steps, e.g. 10 min. */
    val relearningSteps: List<Duration> = listOf(Duration.ofMinutes(10)),
    val maximumIntervalDays: Int = 36500,
    val enableFuzzing: Boolean = false,
    /** Injectable fuzz RNG in [0.0, 1.0); only used when [enableFuzzing] is true. */
    private val fuzzRandom: () -> Double = { java.util.Random().nextDouble() },
) {
    init {
        require(parameters.size == PARAMETER_COUNT) {
            "Expected $PARAMETER_COUNT parameters, got ${parameters.size}"
        }
        parameters.forEachIndexed { i, p ->
            require(p in LOWER_BOUNDS[i]..UPPER_BOUNDS[i]) {
                "parameters[$i] = $p is out of bounds: (${LOWER_BOUNDS[i]}, ${UPPER_BOUNDS[i]})"
            }
        }
        require(desiredRetention in 0.0..1.0) { "desiredRetention must be in [0, 1]" }
    }

    private val decay: Double = -parameters[20]
    private val factor: Double = 0.9.pow(1.0 / decay) - 1.0

    /** Predicted probability of recall at [current]. 0 when never reviewed. */
    fun retrievability(card: FsrsCard, current: Instant): Double {
        val stability = card.stability ?: return 0.0
        val lastReview = card.lastReview ?: return 0.0
        val elapsedDays = max(0, Duration.between(lastReview, current).toDays()).toDouble()
        return (1.0 + factor * elapsedDays / stability).pow(decay)
    }

    /** Result of a single review: the updated card plus its new due instant. */
    data class ReviewOutcome(val card: FsrsCard)

    /**
     * Review [card] with [rating] at [reviewTime] (UTC).
     * Returns a new card; the input is not mutated.
     */
    fun reviewCard(card: FsrsCard, rating: Rating, reviewTime: Instant): ReviewOutcome {
        var c = card
        val daysSinceLastReview = c.lastReview?.let { Duration.between(it, reviewTime).toDays() }

        when (c.state) {
            FsrsState.LEARNING, FsrsState.RELEARNING -> {
                val isRelearning = c.state == FsrsState.RELEARNING
                val steps = if (isRelearning) relearningSteps else learningSteps
                val step = c.step ?: 0

                // ---- update stability & difficulty ----
                if (c.stability == null || c.difficulty == null) {
                    c = c.copy(
                        stability = clampStability(initialStability(rating)),
                        difficulty = clampDifficulty(initialDifficulty(rating, clamp = true)),
                    )
                } else if (daysSinceLastReview != null && daysSinceLastReview < 1) {
                    c = c.copy(
                        stability = shortTermStability(c.stability!!, rating),
                        difficulty = nextDifficulty(c.difficulty!!, rating),
                    )
                } else {
                    val r = retrievability(c, reviewTime)
                    c = c.copy(
                        stability = nextStability(c.difficulty!!, c.stability!!, r, rating),
                        difficulty = nextDifficulty(c.difficulty!!, rating),
                    )
                }

                // ---- next interval & state transition ----
                // Edge case: step beyond current steps and rating graduates the card.
                val nextInterval: Duration
                if (steps.isEmpty() ||
                    (step >= steps.size && rating != Rating.AGAIN)
                ) {
                    c = c.copy(state = FsrsState.REVIEW, step = null)
                    nextInterval = Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                } else {
                    when (rating) {
                        Rating.AGAIN -> {
                            c = c.copy(step = 0)
                            nextInterval = steps[0]
                        }
                        Rating.HARD -> {
                            nextInterval = when {
                                step == 0 && steps.size == 1 -> steps[0].multipliedBy(3).dividedBy(2)
                                step == 0 && steps.size >= 2 -> steps[0].plus(steps[1]).dividedBy(2)
                                else -> steps[step]
                            }
                            // step stays the same
                        }
                        Rating.GOOD -> {
                            if (step + 1 == steps.size) {
                                c = c.copy(state = FsrsState.REVIEW, step = null)
                                nextInterval = Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                            } else {
                                c = c.copy(step = step + 1)
                                nextInterval = steps[step + 1]
                            }
                        }
                        Rating.EASY -> {
                            c = c.copy(state = FsrsState.REVIEW, step = null)
                            nextInterval = Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                        }
                    }
                }
                c = c.copy(due = reviewTime.plus(nextInterval), lastReview = reviewTime)
            }

            FsrsState.REVIEW -> {
                val stability = requireNotNull(c.stability) { "Review state requires stability" }
                val difficulty = requireNotNull(c.difficulty) { "Review state requires difficulty" }

                // ---- update stability & difficulty ----
                val newStability = if (daysSinceLastReview != null && daysSinceLastReview < 1) {
                    shortTermStability(stability, rating)
                } else {
                    val r = retrievability(c, reviewTime)
                    nextStability(difficulty, stability, r, rating)
                }
                c = c.copy(
                    stability = newStability,
                    difficulty = nextDifficulty(difficulty, rating),
                )

                // ---- next interval & state transition ----
                val nextInterval: Duration = when (rating) {
                    Rating.AGAIN -> {
                        if (relearningSteps.isEmpty()) {
                            Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                        } else {
                            c = c.copy(state = FsrsState.RELEARNING, step = 0)
                            relearningSteps[0]
                        }
                    }
                    Rating.HARD, Rating.GOOD, Rating.EASY ->
                        Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                }
                c = c.copy(due = reviewTime.plus(nextInterval), lastReview = reviewTime)
            }

            FsrsState.NEW -> error("NEW is not a schedulable FSRS state; treat as LEARNING step 0")
        }

        // Fuzzing (Review state only, intervals >= 2.5 days). Disabled by default.
        if (enableFuzzing && c.state == FsrsState.REVIEW) {
            val interval = Duration.between(reviewTime, c.due)
            val fuzzed = fuzzedInterval(interval)
            c = c.copy(due = reviewTime.plus(fuzzed))
        }

        return ReviewOutcome(c)
    }

    // ------------------------------------------------------------------
    // Core formulas (ported verbatim from py-fsrs 6.3.1 scheduler.py)
    // ------------------------------------------------------------------

    private fun clampDifficulty(d: Double): Double = min(max(d, MIN_DIFFICULTY), MAX_DIFFICULTY)
    private fun clampStability(s: Double): Double = max(s, STABILITY_MIN)

    /** S0(rating) = parameters[rating - 1]. Rating ordinal: AGAIN=1..EASY=4. */
    private fun initialStability(rating: Rating): Double =
        clampStability(parameters[rating.fsrsIndex])

    /** D0(rating) = p4 - e^(p5*(rating-1)) + 1. */
    private fun initialDifficulty(rating: Rating, clamp: Boolean): Double {
        val d = parameters[4] - E.pow(parameters[5] * rating.fsrsIndex) + 1.0
        return if (clamp) clampDifficulty(d) else d
    }

    /**
     * Next interval in whole days:
     *   round( (S / FACTOR) * (retention^(1/DECAY) - 1) ), clamped to [1, max].
     */
    private fun nextIntervalDays(stability: Double): Int {
        val raw = (stability / factor) * (desiredRetention.pow(1.0 / decay) - 1.0)
        val rounded = raw.roundToInt()
        return min(max(rounded, 1), maximumIntervalDays)
    }

    /** Short-term stability update (same-day reviews). */
    private fun shortTermStability(stability: Double, rating: Rating): Double {
        var increase = E.pow(parameters[17] * (rating.fsrsIndex - 2.0 + parameters[18])) *
            stability.pow(-parameters[19])
        if (rating == Rating.GOOD || rating == Rating.EASY) {
            increase = max(increase, 1.0)
        }
        return clampStability(stability * increase)
    }

    /** Linear damping + mean reversion difficulty update. */
    private fun nextDifficulty(difficulty: Double, rating: Rating): Double {
        fun linearDamping(delta: Double, d: Double): Double = (10.0 - d) * delta / 9.0
        fun meanReversion(a1: Double, a2: Double): Double =
            parameters[7] * a1 + (1.0 - parameters[7]) * a2

        val arg1 = initialDifficulty(Rating.EASY, clamp = false)
        val delta = -(parameters[6] * (rating.fsrsIndex - 2.0))
        val arg2 = difficulty + linearDamping(delta, difficulty)
        return clampDifficulty(meanReversion(arg1, arg2))
    }

    private fun nextStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: Rating,
    ): Double {
        val s = when (rating) {
            Rating.AGAIN -> nextForgetStability(difficulty, stability, retrievability)
            Rating.HARD, Rating.GOOD, Rating.EASY ->
                nextRecallStability(difficulty, stability, retrievability, rating)
        }
        return clampStability(s)
    }

    private fun nextForgetStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
    ): Double {
        val longTerm = parameters[11] *
            difficulty.pow(-parameters[12]) *
            ((stability + 1.0).pow(parameters[13]) - 1.0) *
            E.pow((1.0 - retrievability) * parameters[14])
        val shortTerm = stability / E.pow(parameters[17] * parameters[18])
        return min(longTerm, shortTerm)
    }

    private fun nextRecallStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: Rating,
    ): Double {
        val hardPenalty = if (rating == Rating.HARD) parameters[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) parameters[16] else 1.0
        return stability * (
            1.0 +
                E.pow(parameters[8]) *
                (11.0 - difficulty) *
                stability.pow(-parameters[9]) *
                (E.pow((1.0 - retrievability) * parameters[10]) - 1.0) *
                hardPenalty *
                easyBonus
            )
    }

    // ------------------------------------------------------------------
    // Fuzzing (ported; disabled by default)
    // ------------------------------------------------------------------

    private fun fuzzedInterval(interval: Duration): Duration {
        val days = interval.toDays()
        if (days < 2.5) return interval
        val (minIvl, maxIvl) = fuzzRange(days)
        var fuzzed = fuzzRandom() * (maxIvl - minIvl + 1) + minIvl
        fuzzed = min(fuzzed.roundToInt(), maximumIntervalDays).toDouble()
        return Duration.ofDays(fuzzed.toLong())
    }

    private fun fuzzRange(intervalDays: Long): Pair<Int, Int> {
        var delta = 1.0
        for (range in FUZZ_RANGES) {
            delta += range.factor * max(
                min(intervalDays.toDouble(), range.end) - range.start,
                0.0,
            )
        }
        var minIvl = (intervalDays - delta).roundToInt()
        var maxIvl = (intervalDays + delta).roundToInt()
        minIvl = max(2, minIvl)
        maxIvl = min(maxIvl, maximumIntervalDays)
        minIvl = min(minIvl, maxIvl)
        return minIvl to maxIvl
    }

    private data class FuzzRange(val start: Double, val end: Double, val factor: Double)

    companion object {
        const val PARAMETER_COUNT = 21
        const val STABILITY_MIN = 0.001
        const val MIN_DIFFICULTY = 1.0
        const val MAX_DIFFICULTY = 10.0
        const val FSRS_DEFAULT_DECAY = 0.1542

        /** py-fsrs 6.3.1 DEFAULT_PARAMETERS. */
        val DEFAULT_PARAMETERS = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
            1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
            1.8729, 0.5425, 0.0912, 0.0658, FSRS_DEFAULT_DECAY,
        )

        private val LOWER_BOUNDS = doubleArrayOf(
            STABILITY_MIN, STABILITY_MIN, STABILITY_MIN, STABILITY_MIN,
            1.0, 0.001, 0.001, 0.001, 0.0, 0.0, 0.001, 0.001, 0.001, 0.001,
            0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1,
        )

        private val UPPER_BOUNDS = doubleArrayOf(
            100.0, 100.0, 100.0, 100.0,
            10.0, 4.0, 4.0, 0.75, 4.5, 0.8, 3.5, 5.0, 0.25, 0.9, 4.0,
            1.0, 6.0, 2.0, 2.0, 0.8, 0.8,
        )

        private val FUZZ_RANGES = listOf(
            FuzzRange(2.5, 7.0, 0.15),
            FuzzRange(7.0, 20.0, 0.1),
            FuzzRange(20.0, Double.POSITIVE_INFINITY, 0.05),
        )

        /** FSRS rating index: AGAIN=1, HARD=2, GOOD=3, EASY=4 (py-fsrs `rating - 1`). */
        private val Rating.fsrsIndex: Int
            get() = when (this) {
                Rating.AGAIN -> 0
                Rating.HARD -> 1
                Rating.GOOD -> 2
                Rating.EASY -> 3
            }
    }
}
