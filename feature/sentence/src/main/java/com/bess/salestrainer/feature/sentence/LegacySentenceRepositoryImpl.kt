package com.bess.salestrainer.core.data.repository

import com.bess.salestrainer.core.data.mapper.toStringList
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.ExampleEntity
import com.bess.salestrainer.core.database.entity.ItemMemoryStateEntity
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.ItemType
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.ReviewAdvance
import com.bess.salestrainer.core.model.SentenceExample
import com.bess.salestrainer.core.model.SentenceQueue
import com.bess.salestrainer.core.model.SentenceSessionView
import com.bess.salestrainer.core.model.Speaker
import com.bess.salestrainer.core.model.contract.SentenceRepository
import com.bess.salestrainer.core.model.fsrs.FsrsScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.util.UUID

/**
 * Room-backed [SentenceRepository]: sentence practice (例句练习) over the
 * 常用例句 corpus table, scheduled by the same FSRS v6 loop as vocabulary
 * (itemType = EXAMPLE in item_memory_states).
 *
 * Session state is kept in memory keyed by sessionId (the queue is frozen at
 * start for the process lifetime); FSRS outcomes and review logs are always
 * persisted atomically per rating.
 */
class SentenceRepositoryImpl(
    private val db: BessDatabase,
    private val scheduler: FsrsScheduler = FsrsScheduler(enableFuzzing = true),
    private val newItemTarget: Int = 10,
) : SentenceRepository {

    private val exampleDao get() = db.exampleDao()
    private val itemMemoryDao get() = db.itemMemoryDao()

    /** In-memory session state (frozen queue + reveal flag). */
    private data class Session(
        val sessionId: String,
        val queueIds: List<String>,
        val currentIndex: Int,
        val questionMode: QuestionMode,
        val answerRevealed: Boolean,
    )

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
    private val sessionFlows = mutableMapOf<String, kotlinx.coroutines.flow.MutableStateFlow<Session>>()

    override fun observeExamples(): Flow<List<SentenceExample>> =
        exampleDao.observeAllActive().combine(
            itemMemoryDao.observeByType(ItemType.EXAMPLE.name),
        ) { entries, memories ->
            entries.map { it.toModel() }
        }

    override fun observeTodayQueue(): Flow<SentenceQueue> =
        exampleDao.observeAllActive().combine(
            itemMemoryDao.observeByType(ItemType.EXAMPLE.name),
        ) { entries, memories ->
            val memById = memories.associateBy { it.itemId }
            val now = System.currentTimeMillis()
            val due = entries
                .filter { e -> memById[e.id]?.let { it.dueAtEpochMs <= now } == true }
                .sortedWith(compareBy({ memById[it.id]!!.dueAtEpochMs }, { it.id }))
            val new = entries
                .filter { e -> memById[e.id] == null }
                .sortedBy { it.id }
                .take(newItemTarget)
            SentenceQueue(
                newItems = new.map { it.toModel() },
                dueReviews = due.map { it.toModel() },
            )
        }

    override fun observeSession(): Flow<SentenceSessionView?> =
        kotlinx.coroutines.flow.flow {
            // Emits the latest active session view; null when none started.
            val latest = sessions.values.maxByOrNull { it.sessionId }
            emit(latest?.let { buildView(it) })
        }

    override suspend fun startOrResumeSession(): String {
        sessions.values.firstOrNull()?.let { return it.sessionId }

        val now = System.currentTimeMillis()
        val due = exampleDao.getDueReviews(now)
        val new = exampleDao.getNewExamples(newItemTarget)
        val queue = (due + new).map { it.id }
        require(queue.isNotEmpty()) { "No sentence examples available for study" }

        val sessionId = "sent_${UUID.randomUUID()}"
        val firstMemory = itemMemoryDao.get(queue.first(), ItemType.EXAMPLE.name)
        sessions[sessionId] = Session(
            sessionId = sessionId,
            queueIds = queue,
            currentIndex = 0,
            questionMode = questionModeFor(firstMemory),
            answerRevealed = false,
        )
        return sessionId
    }

    override suspend fun revealAnswer(sessionId: String) {
        val s = requireNotNull(sessions[sessionId]) { "Sentence session $sessionId not found" }
        sessions[sessionId] = s.copy(answerRevealed = true)
    }

    override suspend fun recordReview(
        sessionId: String,
        expectedItemId: String,
        expectedIndex: Int,
        rating: Rating,
        reviewedAt: Instant,
    ): ReviewAdvance {
        val s = requireNotNull(sessions[sessionId]) { "Sentence session $sessionId not found" }
        require(s.answerRevealed) { "Answer must be revealed before rating" }
        require(expectedIndex == s.currentIndex) { "Review action is stale" }
        val currentId = requireNotNull(s.queueIds.getOrNull(s.currentIndex)) {
            "Session $sessionId points past the queue end"
        }
        require(expectedItemId == currentId) { "Review item is stale" }

        val now = reviewedAt
        val existing = itemMemoryDao.get(currentId, ItemType.EXAMPLE.name)
        val card = ItemFsrsSupport.toCard(existing, now)
        val newCard = scheduler.reviewCard(card, rating, now).card

        val reps = (existing?.reps ?: 0) + 1
        val lapses = (existing?.lapses ?: 0) + if (rating == Rating.AGAIN) 1 else 0

        itemMemoryDao.upsert(
            ItemFsrsSupport.toEntity(
                itemId = currentId,
                itemType = ItemType.EXAMPLE.name,
                card = newCard,
                reps = reps,
                lapses = lapses,
                learnedContentHash = exampleDao.getById(currentId)?.contentHash,
                now = now,
            ),
        )
        db.vocabularyDao().insertReviewLog(
            ReviewLogEntity(
                id = UUID.randomUUID().toString(),
                wordId = currentId, // column semantic: generic itemId (D1)
                rating = rating.name,
                questionMode = s.questionMode.name,
                usedHint = false,
                revealedAnswer = true,
                reviewedAtEpochMs = now.toEpochMilli(),
                responseTimeMs = null,
                scheduledDays = 0,
                elapsedDays = 0,
                stateBefore = card.state.name,
                stateAfter = newCard.state.name,
            ),
        )

        val nextIndex = s.currentIndex + 1
        val completed = nextIndex >= s.queueIds.size
        if (completed) {
            sessions.remove(sessionId)
            return ReviewAdvance.Completed(sessionId)
        }
        val nextMode = questionModeFor(itemMemoryDao.get(s.queueIds[nextIndex], ItemType.EXAMPLE.name))
        sessions[sessionId] = s.copy(
            currentIndex = nextIndex,
            questionMode = nextMode,
            answerRevealed = false,
        )
        return ReviewAdvance.Next(s.queueIds[nextIndex], nextMode, s.queueIds.size - nextIndex)
    }

    // ------------------------------------------------------------------

    private suspend fun buildView(s: Session): SentenceSessionView {
        val current = s.queueIds.getOrNull(s.currentIndex)?.let { exampleDao.getById(it)?.toModel() }
        return SentenceSessionView(
            sessionId = s.sessionId,
            queueIds = s.queueIds,
            currentIndex = s.currentIndex,
            currentItem = current,
            questionMode = s.questionMode,
            answerRevealed = s.answerRevealed,
            remainingCount = (s.queueIds.size - s.currentIndex).coerceAtLeast(0),
        )
    }

    private fun questionModeFor(memory: ItemMemoryStateEntity?): QuestionMode {
        if (memory == null || memory.fsrsState == FsrsState.NEW.name) return QuestionMode.INTRODUCE
        return when (memory.reps % 3) {
            0 -> QuestionMode.EN2ZH
            1 -> QuestionMode.ZH2EN
            else -> QuestionMode.LISTENING
        }
    }
}

internal fun ExampleEntity.toModel(): SentenceExample =
    SentenceExample(
        id = id,
        industry = industry,
        scene = scene,
        speaker = runCatching { Speaker.valueOf(speaker) }.getOrDefault(Speaker.CUSTOMER),
        textEn = textEn,
        textZh = textZh,
        linkedTermIds = linkedTermIdsJson.toStringList(),
        dialogueGroupId = dialogueGroupId,
        audioAssetId = audioAssetId,
        contentHash = contentHash,
    )
