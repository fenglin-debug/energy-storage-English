package com.bess.salestrainer.core.data.repository

import com.bess.salestrainer.core.database.entity.ItemMemoryStateEntity
import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.fsrs.FsrsCard
import java.time.Instant

/** ItemMemoryStateEntity <-> FsrsCard conversion shared by item-level repositories. */
internal object ItemFsrsSupport {

    fun toCard(state: ItemMemoryStateEntity?, now: Instant): FsrsCard {
        if (state == null) return FsrsCard(due = now)
        return fromPersisted(
            fsrsState = state.fsrsState,
            stability = state.stability,
            difficulty = state.difficulty,
            dueAtEpochMs = state.dueAtEpochMs,
            lastReviewAtEpochMs = state.lastReviewAtEpochMs,
        )
    }

    fun fromPersisted(
        fsrsState: String,
        stability: Double?,
        difficulty: Double?,
        dueAtEpochMs: Long,
        lastReviewAtEpochMs: Long?,
    ): FsrsCard {
        val persistedState = FsrsState.valueOf(fsrsState)
        // Older favorite rows can carry NEW as a persisted memory state. FSRS
        // schedules NEW content as the first learning step, never as NEW itself.
        val state = if (persistedState == FsrsState.NEW) FsrsState.LEARNING else persistedState
        return FsrsCard(
            state = state,
            step = when (state) {
                FsrsState.LEARNING, FsrsState.RELEARNING -> 0
                else -> null
            },
            stability = stability.takeUnless { persistedState == FsrsState.NEW },
            difficulty = difficulty.takeUnless { persistedState == FsrsState.NEW },
            due = Instant.ofEpochMilli(dueAtEpochMs),
            lastReview = lastReviewAtEpochMs
                ?.takeUnless { persistedState == FsrsState.NEW }
                ?.let(Instant::ofEpochMilli),
        )
    }

    fun toEntity(
        itemId: String,
        itemType: String,
        card: FsrsCard,
        reps: Int,
        lapses: Int,
        learnedContentHash: String?,
        now: Instant,
    ): ItemMemoryStateEntity =
        ItemMemoryStateEntity(
            itemId = itemId,
            itemType = itemType,
            fsrsState = card.state.name,
            difficulty = card.difficulty ?: 0.0,
            stability = card.stability ?: 0.0,
            dueAtEpochMs = card.due.toEpochMilli(),
            lastReviewAtEpochMs = now.toEpochMilli(),
            reps = reps,
            lapses = lapses,
            learnedContentHash = learnedContentHash,
            updatedAtEpochMs = now.toEpochMilli(),
        )
}
