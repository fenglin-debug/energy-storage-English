package com.bess.salestrainer.core.data.repository

import androidx.room.withTransaction
import com.bess.salestrainer.core.data.mapper.toJsonString
import com.bess.salestrainer.core.data.mapper.toModel
import com.bess.salestrainer.core.data.mapper.toStringList
import com.bess.salestrainer.core.data.mapper.toVocabularyModel
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.ReviewActionKeyEntity
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.VocabularySessionCheckpointEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import com.bess.salestrainer.core.database.tx.ReviewTxRunner
import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.ItemType
import com.bess.salestrainer.core.model.MasteryFilter
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.RecordWordReview
import com.bess.salestrainer.core.model.ReviewAdvance
import com.bess.salestrainer.core.model.Vocabulary
import com.bess.salestrainer.core.model.VocabularyFilter
import com.bess.salestrainer.core.model.VocabularyQueue
import com.bess.salestrainer.core.model.VocabularySessionStatus
import com.bess.salestrainer.core.model.VocabularySessionView
import com.bess.salestrainer.core.model.VocabularySelfAssessment
import com.bess.salestrainer.core.model.contract.VocabularyRepository
import com.bess.salestrainer.core.model.contract.SettingsRepository
import com.bess.salestrainer.core.model.fsrs.FsrsCard
import com.bess.salestrainer.core.model.fsrs.FsrsScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Room-backed [VocabularyRepository]. Owns the FSRS scheduling loop and the
 * precise session checkpoint: the queue is frozen at session start; every
 * reveal and rating is persisted before the UI advances (TDD §5.3, §9.1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyRepositoryImpl(
    private val db: BessDatabase,
    private val scheduler: FsrsScheduler = FsrsScheduler(enableFuzzing = true),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val newWordTarget: Int = 15,
    private val reviewTarget: Int = 0, // 0 = all due reviews (TDD §1.3: no truncation cap)
    private val settingsRepository: SettingsRepository? = null,
) : VocabularyRepository {

    private val dao get() = db.vocabularyDao()
    private val phraseDao get() = db.phraseDao()
    private val itemMemoryDao get() = db.itemMemoryDao()

    private fun isPhraseId(id: String) = id.startsWith("PHR-")

    override fun observeWord(wordId: String): Flow<Vocabulary> =
        combine(
            dao.observeById(wordId),
            dao.observeMemoryState(wordId),
        ) { entry, memory ->
            requireNotNull(entry) { "Vocabulary $wordId not found" }
            entry.toModel(
                memory,
                dao.getAliases(wordId).map { it.alias },
                linkedExamples(wordId),
            )
        }

    override fun observeVocabulary(filter: VocabularyFilter): Flow<List<Vocabulary>> =
        combine(
            dao.observeAllActive(),
            dao.observeAllMemoryStates(),
        ) { entries, memoryStates ->
            val memById = memoryStates.associateBy { it.wordId }
            val aliases = dao.getAllAliases().groupBy({ it.wordId }, { it.alias })
            val examples = exampleLinks()
            val now = System.currentTimeMillis()
            entries.asSequence()
                .map {
                    it.toModel(
                        memById[it.id],
                        aliases[it.id].orEmpty(),
                        examples[it.id].orEmpty(),
                    )
                }
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
                        v.chineseGloss.contains(q, ignoreCase = true) ||
                        v.aliases.any { it.contains(q, ignoreCase = true) }
                }
                .toList()
        }

    override fun observeTodayQueue(): Flow<VocabularyQueue> = flow {
        // Home start is gated on this Flow. Repair MAX-due mastered rows first
        // so post-upgrade users see due reviews without starting a session.
        val now = System.currentTimeMillis()
        dao.repairInfiniteDueReviewedWords(now)
        itemMemoryDao.repairInfiniteDueReviewedItems(now)
        emitAll(
            combine(
                dao.observeAllActive(),
                dao.observeAllMemoryStates(),
                phraseDao.observeAllActive(),
                itemMemoryDao.observeByType(ItemType.PHRASE.name),
                settingsRepository?.observeSettings()?.map { it.dailyNewWordTarget }
                    ?: flowOf(newWordTarget),
            ) { entries, memoryStates, phrases, phraseMemories, dailyTarget ->
                val memById = memoryStates.associateBy { it.wordId }
                val phraseMemById = phraseMemories.associateBy { it.itemId }
                val examples = exampleLinks()
                val dueNow = System.currentTimeMillis()
                val due = entries
                    .filter { e ->
                        memById[e.id]?.let { it.reps > 0 && it.dueAtEpochMs <= dueNow } == true
                    }
                    .sortedWith(compareBy({ memById[it.id]!!.dueAtEpochMs }, { it.id }))
                val newWords = entries
                    .filter { e ->
                        memById[e.id]?.let { it.reps == 0 && !it.masteredUi } ?: true
                    }
                    .sortedBy { it.id }
                val duePhrases = phrases
                    .filter { p ->
                        phraseMemById[p.id]?.let { it.dueAtEpochMs <= dueNow } == true
                    }
                    .sortedWith(compareBy({ phraseMemById[it.id]!!.dueAtEpochMs }, { it.id }))
                val newPhrases = phrases
                    .filter { p -> phraseMemById[p.id] == null }
                    .sortedBy { it.id }
                val newItems = (
                    newWords.map {
                        it.id to it.toModel(null, linkedExamples = examples[it.id].orEmpty())
                    } +
                        newPhrases.map {
                            it.id to it.toVocabularyModel(null, examples[it.id].orEmpty())
                        }
                    ).sortedBy { it.first }.take(dailyTarget).map { it.second }
                VocabularyQueue(
                    newWords = newItems,
                    dueReviews = due.map {
                        it.toModel(memById[it.id], linkedExamples = examples[it.id].orEmpty())
                    } + duePhrases.map { p ->
                        p.toVocabularyModel(phraseMemById[p.id], examples[p.id].orEmpty())
                    },
                )
            },
        )
    }

    override fun observeSession(sessionId: String): Flow<VocabularySessionView> =
        dao.observeCheckpoint(sessionId).flatMapLatest { checkpoint ->
            requireNotNull(checkpoint) { "Vocabulary session $sessionId not found" }
            val queueIds = checkpoint.queueWordIdsJson.toStringList()
            val entries = dao.getByIds(queueIds).associateBy { it.id }
            val memories = dao.getMemoryStates(queueIds).associateBy { it.wordId }
            val phraseIds = queueIds.filter { isPhraseId(it) }
            val phrases = if (phraseIds.isEmpty()) {
                emptyMap()
            } else {
                phraseDao.getByIds(phraseIds).associateBy { it.id }
            }
            val phraseMemories = if (phraseIds.isEmpty()) {
                emptyMap()
            } else {
                itemMemoryDao.getForItems(phraseIds, ItemType.PHRASE.name).associateBy { it.itemId }
            }
            val aliases = dao.getAllAliases().groupBy({ it.wordId }, { it.alias })
            val currentWord = queueIds.getOrNull(checkpoint.currentIndex)?.let { id ->
                if (isPhraseId(id)) {
                    phrases[id]?.toVocabularyModel(phraseMemories[id], linkedExamples(id))
                } else {
                    entries[id]?.toModel(
                        memories[id],
                        aliases[id].orEmpty(),
                        linkedExamples(id),
                    )
                }
            }
            flowOf(
                VocabularySessionView(
                    checkpoint = checkpoint.toModel(),
                    currentWord = currentWord,
                    remainingCount = (queueIds.size - checkpoint.currentIndex).coerceAtLeast(0),
                    hasNext = checkpoint.currentIndex < queueIds.lastIndex,
                ),
            )
        }

    override suspend fun startOrResumeSession(): String {
        dao.getLatestInProgressCheckpoint()?.let { return it.sessionId }

        val now = System.currentTimeMillis()
        // Legacy mastered rows used Long.MAX_VALUE due; repair before queue build.
        dao.repairInfiniteDueReviewedWords(now)
        itemMemoryDao.repairInfiniteDueReviewedItems(now)
        val dailyTarget = settingsRepository?.observeSettings()?.first()?.dailyNewWordTarget
            ?: newWordTarget
        // Build the frozen queue: all due reviews (by due time) then today's new
        // items (stable id order) — TDD §4.1. FR-v2: phrases join the queue.
        val due = dao.getDueReviews(now)
        val new = dao.getNewWords(dailyTarget)
        val duePhrases = phraseDao.getDueReviews(now)
        val newPhrases = phraseDao.getNewPhrases(dailyTarget)
        val newIds = (new.map { it.id } + newPhrases.map { it.id })
            .sorted()
            .take(dailyTarget)
        val queue = (due.map { it.id } + duePhrases.map { it.id } + newIds)
        require(queue.isNotEmpty()) { "No vocabulary available for study" }

        val firstWordId = queue.first()
        val firstMemory = memoryForQuestionMode(firstWordId)
        val sessionId = "vocab_${UUID.randomUUID()}"
        val corpusVersion = db.corpusDao().getActive()?.contentVersion ?: "none"
        val mode = questionModeFor(firstMemory)
        dao.upsertCheckpoint(
            VocabularySessionCheckpointEntity(
                sessionId = sessionId,
                status = VocabularySessionStatus.IN_PROGRESS.name,
                corpusVersion = corpusVersion,
                queueWordIdsJson = queue.toJsonString(),
                currentIndex = 0,
                questionMode = mode.name,
                answerRevealed = mode == QuestionMode.INTRODUCE,
                hintRevealed = false,
                assessmentSubmitted = false,
                selectedAssessment = null,
                startedAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        return sessionId
    }

    override suspend fun submitAssessment(
        sessionId: String,
        itemId: String,
        assessment: VocabularySelfAssessment,
    ) {
        val now = Instant.now()
        db.withTransaction {
            val checkpoint = requireNotNull(dao.getCheckpoint(sessionId)) {
                "Vocabulary session $sessionId not found"
            }
            check(checkpoint.status == VocabularySessionStatus.IN_PROGRESS.name)
            val mode = QuestionMode.valueOf(checkpoint.questionMode)
            if (mode != QuestionMode.INTRODUCE && !checkpoint.answerRevealed) {
                error("Answer must be revealed before assessment")
            }
            val queueIds = checkpoint.queueWordIdsJson.toStringList()
            val currentId = requireNotNull(queueIds.getOrNull(checkpoint.currentIndex))
            require(currentId == itemId) { "Assessment item is stale" }
            dao.upsertCheckpoint(
                checkpoint.copy(
                    assessmentSubmitted = true,
                    selectedAssessment = assessment.name,
                    updatedAtEpochMs = now.toEpochMilli(),
                ),
            )
        }
    }

    override suspend fun advanceToNext(sessionId: String) {
        val now = Instant.now()
        db.withTransaction {
            val checkpoint = requireNotNull(dao.getCheckpoint(sessionId)) {
                "Vocabulary session $sessionId not found"
            }
            if (checkpoint.status != VocabularySessionStatus.IN_PROGRESS.name) return@withTransaction
            if (!checkpoint.assessmentSubmitted) return@withTransaction
            val queue = checkpoint.queueWordIdsJson.toStringList()
            val itemId = requireNotNull(queue.getOrNull(checkpoint.currentIndex))
            val assessment = VocabularySelfAssessment.valueOf(
                requireNotNull(checkpoint.selectedAssessment),
            )
            val actionInserted = dao.insertActionKey(
                ReviewActionKeyEntity(
                    actionKey = "$sessionId:${checkpoint.currentIndex}",
                    sessionId = sessionId,
                    currentIndex = checkpoint.currentIndex,
                    createdAtEpochMs = now.toEpochMilli(),
                ),
            ) != -1L
            if (actionInserted) {
                persistAssessmentReview(itemId, assessment, now)
            }
            val nextIndex = checkpoint.currentIndex + 1
            val completed = nextIndex >= queue.size
            val nextId = queue.getOrNull(nextIndex)
            val nextMode = if (completed || nextId == null) {
                QuestionMode.valueOf(checkpoint.questionMode)
            } else {
                questionModeFor(memoryForQuestionMode(nextId))
            }
            dao.upsertCheckpoint(
                checkpoint.copy(
                    status = if (completed) {
                        VocabularySessionStatus.COMPLETED.name
                    } else {
                        checkpoint.status
                    },
                    currentIndex = nextIndex,
                    questionMode = nextMode.name,
                    answerRevealed = nextMode == QuestionMode.INTRODUCE,
                    hintRevealed = false,
                    assessmentSubmitted = false,
                    selectedAssessment = null,
                    updatedAtEpochMs = now.toEpochMilli(),
                ),
            )
        }
    }

    override suspend fun revealVocabularyAnswer(sessionId: String) {
        val checkpoint = requireNotNull(dao.getCheckpoint(sessionId)) {
            "Vocabulary session $sessionId not found"
        }
        check(checkpoint.status == VocabularySessionStatus.IN_PROGRESS.name) {
            "Session $sessionId is not in progress"
        }
        dao.upsertCheckpoint(
            checkpoint.copy(answerRevealed = true, updatedAtEpochMs = System.currentTimeMillis()),
        )
    }

    suspend fun recordReview(sessionId: String, command: RecordWordReview): ReviewAdvance {
        val checkpoint = requireNotNull(dao.getCheckpoint(sessionId)) {
            "Vocabulary session $sessionId not found"
        }
        check(checkpoint.status == VocabularySessionStatus.IN_PROGRESS.name) {
            throw IllegalStateException("Session $sessionId is not in progress")
        }
        require(checkpoint.answerRevealed) { "Answer must be revealed before rating" }
        require(command.expectedIndex == checkpoint.currentIndex) { "Review action is stale" }

        val queueIds = checkpoint.queueWordIdsJson.toStringList()
        val currentWordId = requireNotNull(queueIds.getOrNull(checkpoint.currentIndex)) {
            "Session $sessionId checkpoint points past the queue end"
        }
        require(command.expectedWordId == currentWordId) { "Review word is stale" }

        val now = command.reviewedAt
        val isPhrase = isPhraseId(currentWordId)
        val existingItemMemory = if (isPhrase) {
            itemMemoryDao.get(currentWordId, ItemType.PHRASE.name)
        } else {
            null
        }
        val existing = if (isPhrase) null else dao.getMemoryState(currentWordId)
        val isNewWord = if (isPhrase) existingItemMemory == null else existing == null

        val card = if (isPhrase) {
            ItemFsrsSupport.toCard(existingItemMemory, now)
        } else if (existing == null) {
            FsrsCard(due = now)
        } else {
            ItemFsrsSupport.fromPersisted(
                fsrsState = existing.fsrsState,
                stability = existing.stability,
                difficulty = existing.difficulty,
                dueAtEpochMs = existing.dueAtEpochMs,
                lastReviewAtEpochMs = existing.lastReviewAtEpochMs,
            )
        }

        val stateBefore = card.state
        val newCard = scheduler.reviewCard(card, command.rating, now).card

        val priorReps = if (isPhrase) existingItemMemory?.reps ?: 0 else existing?.reps ?: 0
        val priorLapses = if (isPhrase) existingItemMemory?.lapses ?: 0 else existing?.lapses ?: 0
        val reps = priorReps + 1
        val lapses = priorLapses + if (command.rating == Rating.AGAIN) 1 else 0
        val priorDueMs = if (isPhrase) existingItemMemory?.dueAtEpochMs else existing?.dueAtEpochMs
        val priorReviewMs = if (isPhrase) {
            existingItemMemory?.lastReviewAtEpochMs
        } else {
            existing?.lastReviewAtEpochMs
        }
        val scheduledDays = priorDueMs?.let {
            Duration.between(Instant.ofEpochMilli(it), newCard.due).toDays().coerceAtLeast(0)
        } ?: 0L
        val elapsedDays = priorReviewMs?.let {
            Duration.between(Instant.ofEpochMilli(it), now).toDays().coerceAtLeast(0)
        } ?: 0L

        val nextIndex = checkpoint.currentIndex + 1
        val completed = nextIndex >= queueIds.size
        val nextId = queueIds.getOrNull(nextIndex)
        val nextMode = if (completed || nextId == null) {
            QuestionMode.valueOf(checkpoint.questionMode) // irrelevant after completion
        } else if (isPhraseId(nextId)) {
            // Cycle EN2ZH / ZH2EN / LISTENING for phrases (same as sentences).
            val nextReps = itemMemoryDao.get(nextId, ItemType.PHRASE.name)?.reps ?: 0
            when (nextReps % 3) {
                0 -> QuestionMode.EN2ZH
                1 -> QuestionMode.ZH2EN
                else -> QuestionMode.LISTENING
            }
        } else {
            questionModeFor(dao.getMemoryState(nextId))
        }
        val advancedCheckpoint = checkpoint.copy(
            status = if (completed) VocabularySessionStatus.COMPLETED.name else checkpoint.status,
            currentIndex = nextIndex,
            questionMode = nextMode.name,
            answerRevealed = false,
            hintRevealed = false,
            updatedAtEpochMs = now.toEpochMilli(),
        )

        if (isPhrase) {
            // FR-v2 phrase reviews: item_memory_states + review log + task
            // counter + checkpoint, committed in one Room transaction.
            val committed = db.withTransaction {
                val inserted = db.vocabularyDao().insertActionKey(
                    ReviewActionKeyEntity(
                        actionKey = "$sessionId:${checkpoint.currentIndex}",
                        sessionId = sessionId,
                        currentIndex = checkpoint.currentIndex,
                        createdAtEpochMs = now.toEpochMilli(),
                    ),
                )
                if (inserted == -1L) return@withTransaction false
                db.vocabularyDao().insertReviewLog(
                    ReviewLogEntity(
                        id = UUID.randomUUID().toString(),
                        wordId = currentWordId, // column semantic: generic itemId (D1)
                        rating = command.rating.name,
                        questionMode = checkpoint.questionMode,
                        usedHint = command.usedHint,
                        revealedAnswer = true,
                        reviewedAtEpochMs = now.toEpochMilli(),
                        responseTimeMs = command.responseTimeMs,
                        scheduledDays = scheduledDays,
                        elapsedDays = elapsedDays,
                        stateBefore = stateBefore.name,
                        stateAfter = newCard.state.name,
                    ),
                )
                itemMemoryDao.upsert(
                    ItemFsrsSupport.toEntity(
                        itemId = currentWordId,
                        itemType = ItemType.PHRASE.name,
                        card = newCard,
                        reps = reps,
                        lapses = lapses,
                        learnedContentHash = phraseDao.getById(currentWordId)?.contentHash,
                        now = now,
                    ),
                )
                db.vocabularyDao().upsertCheckpoint(advancedCheckpoint)
                val taskDao = db.studyTaskDao()
                val dateEpochDay = LocalDate.ofInstant(now, zoneId).toEpochDay()
                taskDao.getByDate(dateEpochDay)?.let { t ->
                    taskDao.upsert(
                        t.copy(
                            reviewDone = t.reviewDone + 1,
                            updatedAtEpochMs = now.toEpochMilli(),
                        ),
                    )
                }
                true
            }
            if (!committed) {
                return if (completed) {
                    ReviewAdvance.Completed(sessionId)
                } else {
                    ReviewAdvance.Next(queueIds[nextIndex], nextMode, queueIds.size - nextIndex)
                }
            }
        } else {
            val committed = ReviewTxRunner(db).recordReviewAtomic(
                ReviewTxRunner.ReviewTx(
                    actionKey = ReviewActionKeyEntity(
                        actionKey = "$sessionId:${checkpoint.currentIndex}",
                        sessionId = sessionId,
                        currentIndex = checkpoint.currentIndex,
                        createdAtEpochMs = now.toEpochMilli(),
                    ),
                    reviewLog = ReviewLogEntity(
                        id = UUID.randomUUID().toString(),
                        wordId = currentWordId,
                        rating = command.rating.name,
                        questionMode = checkpoint.questionMode,
                        usedHint = command.usedHint,
                        revealedAnswer = true,
                        reviewedAtEpochMs = now.toEpochMilli(),
                        responseTimeMs = command.responseTimeMs,
                        scheduledDays = scheduledDays,
                        elapsedDays = elapsedDays,
                        stateBefore = stateBefore.name,
                        stateAfter = newCard.state.name,
                    ),
                    memoryState = WordMemoryStateEntity(
                        wordId = currentWordId,
                        fsrsState = newCard.state.name,
                        difficulty = newCard.difficulty ?: 0.0,
                        stability = newCard.stability ?: 0.0,
                        dueAtEpochMs = newCard.due.toEpochMilli(),
                        lastReviewAtEpochMs = now.toEpochMilli(),
                        reps = reps,
                        lapses = lapses,
                        masteredUi = existing?.masteredUi ?: false,
                        lastQuestionMode = checkpoint.questionMode,
                        isFavorite = existing?.isFavorite ?: false,
                        learnedContentHash = dao.getById(currentWordId)?.contentHash,
                        updatedAtEpochMs = now.toEpochMilli(),
                    ),
                    taskDateEpochDay = LocalDate.ofInstant(now, zoneId).toEpochDay(),
                    isNewWord = isNewWord,
                    newWordTarget = newWordTarget,
                    reviewTarget = reviewTarget,
                    advancedCheckpoint = advancedCheckpoint,
                ),
            )
            if (!committed) {
                // Duplicate tap: the first submission already advanced; report
                // the same advance result without double-writing.
                return if (completed) {
                    ReviewAdvance.Completed(sessionId)
                } else {
                    ReviewAdvance.Next(queueIds[nextIndex], nextMode, queueIds.size - nextIndex)
                }
            }
        }

        return if (completed) {
            ReviewAdvance.Completed(sessionId)
        } else {
            ReviewAdvance.Next(queueIds[nextIndex], nextMode, queueIds.size - nextIndex)
        }
    }

    override suspend fun setFavorite(wordId: String, favorite: Boolean) {
        val now = System.currentTimeMillis()
        // A favorite mark on an unlearned word creates the memory-state row.
        dao.insertMemoryStateIgnore(
            WordMemoryStateEntity(
                wordId = wordId,
                fsrsState = FsrsState.NEW.name,
                difficulty = 0.0,
                stability = 0.0,
                dueAtEpochMs = Long.MAX_VALUE,
                lastReviewAtEpochMs = null,
                reps = 0,
                lapses = 0,
                masteredUi = false,
                lastQuestionMode = null,
                isFavorite = favorite,
                learnedContentHash = dao.getById(wordId)?.contentHash,
                updatedAtEpochMs = now,
            ),
        )
        dao.setFavorite(wordId, favorite, now)
    }

    // ------------------------------------------------------------------

    private suspend fun persistAssessmentReview(
        itemId: String,
        assessment: VocabularySelfAssessment,
        now: Instant,
    ) {
        val rating = when (assessment) {
            VocabularySelfAssessment.UNFAMILIAR -> Rating.AGAIN
            VocabularySelfAssessment.FUZZY -> Rating.HARD
            VocabularySelfAssessment.MASTERED -> Rating.GOOD
        }
        val phrase = isPhraseId(itemId)
        val oldPhrase = if (phrase) itemMemoryDao.get(itemId, ItemType.PHRASE.name) else null
        val oldWord = if (phrase) null else dao.getMemoryState(itemId)
        val isNew = oldPhrase == null && oldWord == null
        val cardBefore = if (phrase) {
            ItemFsrsSupport.toCard(oldPhrase, now)
        } else if (oldWord == null) {
            FsrsCard(due = now)
        } else {
            ItemFsrsSupport.fromPersisted(
                fsrsState = oldWord.fsrsState,
                stability = oldWord.stability,
                difficulty = oldWord.difficulty,
                dueAtEpochMs = oldWord.dueAtEpochMs,
                lastReviewAtEpochMs = oldWord.lastReviewAtEpochMs,
            )
        }
        val reviewed = scheduler.reviewCard(cardBefore, rating, now).card
        val mastered = assessment == VocabularySelfAssessment.MASTERED
        // Always use FSRS due; masteredUi is a badge only (never Long.MAX_VALUE).
        val dueMs = reviewed.due.toEpochMilli()
        val reps = (oldPhrase?.reps ?: oldWord?.reps ?: 0) + 1
        val lapses = (oldPhrase?.lapses ?: oldWord?.lapses ?: 0) +
            if (rating == Rating.AGAIN) 1 else 0

        if (phrase) {
            itemMemoryDao.upsert(
                ItemFsrsSupport.toEntity(
                    itemId = itemId,
                    itemType = ItemType.PHRASE.name,
                    card = reviewed,
                    reps = reps,
                    lapses = lapses,
                    learnedContentHash = phraseDao.getById(itemId)?.contentHash,
                    now = now,
                ).copy(masteredUi = mastered),
            )
        } else {
            dao.upsertMemoryState(
                WordMemoryStateEntity(
                    wordId = itemId,
                    fsrsState = reviewed.state.name,
                    difficulty = reviewed.difficulty ?: 0.0,
                    stability = reviewed.stability ?: 0.0,
                    dueAtEpochMs = dueMs,
                    lastReviewAtEpochMs = now.toEpochMilli(),
                    reps = reps,
                    lapses = lapses,
                    masteredUi = mastered,
                    lastQuestionMode = null,
                    isFavorite = oldWord?.isFavorite ?: false,
                    learnedContentHash = dao.getById(itemId)?.contentHash,
                    updatedAtEpochMs = now.toEpochMilli(),
                ),
            )
        }
        dao.insertReviewLog(
            ReviewLogEntity(
                id = UUID.randomUUID().toString(),
                wordId = itemId,
                rating = rating.name,
                questionMode = "SELF_ASSESSMENT",
                usedHint = false,
                revealedAnswer = true,
                reviewedAtEpochMs = now.toEpochMilli(),
                responseTimeMs = null,
                scheduledDays = Duration.between(now, reviewed.due)
                    .toDays().coerceAtLeast(0),
                elapsedDays = 0,
                stateBefore = cardBefore.state.name,
                stateAfter = reviewed.state.name,
            ),
        )
        val taskDao = db.studyTaskDao()
        val date = LocalDate.ofInstant(now, zoneId).toEpochDay()
        taskDao.getByDate(date)?.let { task ->
            taskDao.upsert(
                task.copy(
                    newWordDone = task.newWordDone + if (isNew) 1 else 0,
                    reviewDone = task.reviewDone + if (isNew) 0 else 1,
                    updatedAtEpochMs = now.toEpochMilli(),
                ),
            )
        }
    }

    private suspend fun memoryForQuestionMode(itemId: String): WordMemoryStateEntity? {
        return if (isPhraseId(itemId)) {
            itemMemoryDao.get(itemId, ItemType.PHRASE.name)?.let {
                WordMemoryStateEntity(
                    wordId = it.itemId,
                    fsrsState = it.fsrsState,
                    difficulty = it.difficulty,
                    stability = it.stability,
                    dueAtEpochMs = it.dueAtEpochMs,
                    lastReviewAtEpochMs = it.lastReviewAtEpochMs,
                    reps = it.reps,
                    lapses = it.lapses,
                    masteredUi = false,
                    lastQuestionMode = null,
                    isFavorite = false,
                    learnedContentHash = it.learnedContentHash,
                    updatedAtEpochMs = it.updatedAtEpochMs,
                )
            }
        } else {
            dao.getMemoryState(itemId)
        }
    }

    /** Deterministic question-mode rule (TDD §4.1). */
    private fun questionModeFor(memory: WordMemoryStateEntity?): QuestionMode {
        if (memory == null || memory.fsrsState == FsrsState.NEW.name) return QuestionMode.INTRODUCE
        return when (memory.fsrsState) {
            FsrsState.LEARNING.name, FsrsState.RELEARNING.name ->
                if (memory.reps % 2 == 0) QuestionMode.EN2ZH else QuestionMode.ZH2EN
            else -> when (memory.reps % 3) {
                0 -> QuestionMode.LISTENING
                1 -> QuestionMode.TRANSFER
                else -> QuestionMode.EN2ZH
            }
        }
    }

    private suspend fun linkedExamples(itemId: String) =
        db.exampleDao().getAllActive().filter {
            itemId in it.linkedTermIdsJson.toStringList()
        }

    private suspend fun exampleLinks() =
        buildMap<String, MutableList<com.bess.salestrainer.core.database.entity.ExampleEntity>> {
            db.exampleDao().getAllActive().forEach { example ->
                example.linkedTermIdsJson.toStringList().forEach { itemId ->
                    getOrPut(itemId) { mutableListOf() }.add(example)
                }
            }
        }
}
