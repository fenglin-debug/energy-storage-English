package com.bess.salestrainer.core.model.fsrs

import com.bess.salestrainer.core.model.FsrsState
import java.time.Instant

/**
 * A scheduling card for FSRS v6. Pure Kotlin port of py-fsrs 6.3.1 `Card`.
 *
 * Source (MIT License): https://github.com/open-spaced-repetition/py-fsrs
 * The port is step-based: `step` is non-null only in Learning / Relearning states.
 */
data class FsrsCard(
    val state: FsrsState = FsrsState.LEARNING,
    /** Learning / Relearning step index; null in Review state. */
    val step: Int? = if (
        state == FsrsState.LEARNING || state == FsrsState.RELEARNING
    ) 0 else null,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Instant,
    val lastReview: Instant? = null,
) {
    init {
        require(
            (state != FsrsState.LEARNING && state != FsrsState.RELEARNING) || step != null,
        ) {
            "Learning and relearning states require a non-null step"
        }
    }
}
