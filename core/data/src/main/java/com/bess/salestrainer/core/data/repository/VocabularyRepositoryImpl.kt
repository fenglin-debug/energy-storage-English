package com.bess.salestrainer.core.data.repository

import com.bess.salestrainer.core.data.mapper.toModel
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import com.bess.salestrainer.core.database.tx.ReviewTxRunner
import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.MasteryFilter
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.RecordWordReview
import com.bess.salestrainer.core.model.ReviewResult
import com.bess.salestrainer.core.model.Vocabulary
import com.bess.salestrainer.core.model.VocabularyFilter
import com.bess.salestrainer.core.model.VocabularyQueue
import com.bess.salestrainer.core.model.contract.VocabularyRepository
import com.bess.salestrainer.core.model.fsrs.FsrsCard
import com.bess.salestrainer.core.model.fsrs.FsrsScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Room-backed [VocabularyRepository]. Owns the FSRS scheduling loop:
 * read memory state -> review via [FsrsScheduler] -> persist atomically.
 *
 * The UI never touches [FsrsScheduler] directly; it only sees [ReviewResult].
 */
class VocabularyRepositoryImpl(
    private val db: BessDatabase,
    private val scheduler: FsrsScheduler = FsrsScheduler(enableFuzzing = true),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val newWordLimit: Int = 15,
    private val reviewLimit: Int = 60,
) : VocabularyRepository {

    private val dao get() = db.vocabularyDao()

    override fun observeWord(wordId: String): Flow<Vocabulary> =
        combine(
            dao.observeById(wordId),
            dao.observeMemoryState(wordId),
        ) { entry, memory ->
            requireNotNull(entry) { "Vocabulary $wordId not found" }
            entry.toModel(memory)
        }

    override fun observeTodayQueue(): Flow<VocabularyQueue> {
        val now = System.currentTimeMillis()
        return combine(
            dao.observeNewWords(newWordLimit),
            dao.observeDueReviews(now, reviewLimit),
            dao.observeDueCount(now),
            dao.observeAllMemoryStates(),
        ) { newEntries, dueEntries, dueCount, memoryStates ->
            val memById = memoryStates.associateBy { it.wordId }
            VocabularyQueue(
                newWords = newEntries.map { it.toModel(null) },
                dueReviews = dueEntries.map { it.toModel(memById[it.id]) },
                overflowDeferredCount = (dueCount - dueEntries.size).coerceAtLeast(0),
            )
        }
    }

    override fun observeVocabulary(filter: VocabularyFilter): Flow<List<Vocabulary>> =
        combine(
            dao.observeAllActive(),
            dao.observeAllMemoryStates(),
        ) { entries, memoryStates ->
            val memById = memoryStates.associateBy { it.wordId }
            val now = System.currentTimeMillis()
            entries.asSequence()
                .map { it.toModel(memById[it.id]) }
                .filter { v -> filter.topic == null || v.topic == filter.topic }
                .filter { v -> filter.cefrLevel == null || v.cefrLevel == filter.cefrLevel }
                .filter { v -> !filter.favoritesOnly || v.memoryState?.isFavorite == true }
                .filter { v ->
                    !filter.dueOnly || (v.memoryState?.dueAt?.toEpochMilli() ?: Long.MAX_VALUE) <= now
                }
                .filter { v ->
                    when (filter.mastery) {
                        MasteryFilter.ALL -> true
                        MasteryFilter.NOT_STARTED -> v.memoryState == null
                        MasteryFilter.LEARNING ->
                            v.memoryState?.let { !it.masteredUi } == true
                        MasteryFilter.MASTERED -> v.memoryState?.masteredUi == true
                    }
                }
                .filter { v ->
                    val q = filter.query
                    q.isNullOrBlank() ||
                        v.term.contains(q, ignoreCase = true) ||
                        v.chineseGloss.contains(q, ignoreCase = true)
                }
                .toList()
        }

    override suspend fun recordReview(command: RecordWordReview): ReviewResult {
        val now = command.reviewedAt
        val existing = dao.getMemoryState(command.wordId)
        val isNewWord = existing == null

        // Rebuild the FSRS card from persisted state (or a fresh card).
        val card = if (existing == null) {
            FsrsCard(due = now)
        } else {
            FsrsCard(
                state = FsrsState.valueOf(existing.fsrsState),
                step = null, // step is not persisted; recompute from state below
                stability = existing.stability,
                difficulty = existing.difficulty,
                due = Instant.ofEpochMilli(existing.dueAtEpochMs),
                lastReview = existing.lastReviewAtEpochMs?.let(Instant::ofEpochMilli),
            ).let { c ->
                // Learning/Relearning require a step; default to 0 when unknown.
                if (c.state == FsrsState.LEARNING || c.state == FsrsState.RELEARNING) c.copy(step = 0) else c
            }
        }

        val stateBefore = card.state
        val outcome = scheduler.reviewCard(card, command.rating, now)
        val newCard = outcome.card

        val reps = (existing?.reps ?: 0) + 1
        val lapses = (existing?.lapses ?: 0) + if (command.rating == Rating.AGAIN) 1 else 0
        val scheduledDays = existing?.let {
            java.time.Duration.between(
                Instant.ofEpochMilli(it.dueAtEpochMs), newCard.due,
            ).toDays().coerceAtLeast(0)
        } ?: 0L
        val elapsedDays = existing?.lastReviewAtEpochMs?.let {
            java.time.Duration.between(Instant.ofEpochMilli(it), now).toDays().coerceAtLeast(0)
        } ?: 0L

        val memoryEntity = WordMemoryStateEntity(
            wordId = command.wordId,
            fsrsState = newCard.state.name,
            difficulty = newCard.difficulty ?: 0.0,
            stability = newCard.stability ?: 0.0,
            dueAtEpochMs = newCard.due.toEpochMilli(),
            lastReviewAtEpochMs = now.toEpochMilli(),
            reps = reps,
            lapses = lapses,
            masteredUi = existing?.masteredUi ?: false,
            lastQuestionMode = command.questionMode.name,
            isFavorite = existing?.isFavorite ?: false,
            updatedAtEpochMs = now.toEpochMilli(),
        )

        val logEntity = ReviewLogEntity(
            id = UUID.randomUUID().toString(),
            wordId = command.wordId,
            rating = command.rating.name,
            questionMode = command.questionMode.name,
            usedHint = command.usedHint,
            revealedAnswer = command.revealedAnswer,
            reviewedAtEpochMs = now.toEpochMilli(),
            responseTimeMs = command.responseTimeMs,
            scheduledDays = scheduledDays,
            elapsedDays = elapsedDays,
            stateBefore = stateBefore.name,
            stateAfter = newCard.state.name,
        )

        ReviewTxRunner(db).recordReviewAtomic(
            ReviewTxRunner.ReviewTx(
                reviewLog = logEntity,
                memoryState = memoryEntity,
                taskDateEpochDay = LocalDate.ofInstant(now, zoneId).toEpochDay(),
                isNewWord = isNewWord,
            ),
        )

        return ReviewResult(
            wordId = command.wordId,
            newState = newCard.state,
            nextDueAt = newCard.due,
            reps = reps,
            lapses = lapses,
        )
    }
}
