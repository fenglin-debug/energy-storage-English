package com.bess.salestrainer.core.data.repository

import com.bess.salestrainer.core.data.mapper.formalAlternatives
import com.bess.salestrainer.core.data.mapper.scoringPoints
import com.bess.salestrainer.core.data.mapper.toModel
import com.bess.salestrainer.core.data.mapper.toSummary
import com.bess.salestrainer.core.data.mapper.toJsonString
import com.bess.salestrainer.core.data.mapper.toStringList
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.DialoguePairEntity
import com.bess.salestrainer.core.database.entity.DialogueTurnEntity
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.ScenarioTurnProgressEntity
import com.bess.salestrainer.core.database.entity.VocabularyEntryEntity
import com.bess.salestrainer.core.database.tx.ScenarioTxRunner
import com.bess.salestrainer.core.model.CustomerTextView
import com.bess.salestrainer.core.model.ItemType
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.fsrs.FsrsScheduler
import com.bess.salestrainer.core.model.DialogueSelfRating
import com.bess.salestrainer.core.model.Keyword
import com.bess.salestrainer.core.model.KeywordsView
import com.bess.salestrainer.core.model.ReferenceAnswer
import com.bess.salestrainer.core.model.ReferenceAnswerView
import com.bess.salestrainer.core.model.ScenarioAdvance
import com.bess.salestrainer.core.model.ScenarioFilter
import com.bess.salestrainer.core.model.ScenarioPracticeUnit
import com.bess.salestrainer.core.model.ScenarioSessionSummary
import com.bess.salestrainer.core.model.ScenarioSummary
import com.bess.salestrainer.core.model.ScenarioTurnProgress
import com.bess.salestrainer.core.model.ScenarioUnitView
import com.bess.salestrainer.core.model.SessionStatus
import com.bess.salestrainer.core.model.contract.ScenarioRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Room-backed [ScenarioRepository]. Reveal actions are one-way and persisted
 * before the UI updates; rating requires the reference answer to be revealed
 * and advances inside a single transaction (TDD §5.3, §9.2).
 *
 * Concealed content never crosses this boundary: the unit view only contains
 * the revealed branches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioRepositoryImpl(
    private val db: BessDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val scheduler: FsrsScheduler = FsrsScheduler(enableFuzzing = true),
) : ScenarioRepository {

    private val dao get() = db.scenarioDao()

    override fun observeScenarios(filter: ScenarioFilter): Flow<List<ScenarioSummary>> =
        dao.observeAllActive().flatMapLatest { scenarios ->
            db.itemMemoryDao().observeDuePairIds(System.currentTimeMillis()).map { dueIds ->
                val dueSet = dueIds.toSet()
                // Per-scenario status: an in-progress session wins; otherwise a
                // completed one; otherwise null (not started).
                val summaries = scenarios.map { scenario ->
                    val inProgress = dao.getInProgressForScenario(scenario.id)
                    val completed = inProgress == null && dao.completedCountForScenario(scenario.id) > 0
                    val ratings = dao.ratingSummaryForScenario(scenario.id)
                        .associate { DialogueSelfRating.valueOf(it.rating) to it.count }
                    val status = when {
                        inProgress != null -> SessionStatus.IN_PROGRESS
                        completed -> SessionStatus.COMPLETED
                        else -> null
                    }
                    val dueCount = if (dueSet.isEmpty()) {
                        0
                    } else {
                        dao.getPairs(scenario.id).count { it.id in dueSet }
                    }
                    scenario.toSummary(status = status, ratingSummary = ratings)
                        .copy(duePairCount = dueCount)
                }
                summaries.asSequence()
                    .filter { s -> filter.topic == null || s.topic == filter.topic }
                    .filter { s -> filter.salesStage == null || s.salesStage == filter.salesStage }
                    .filter { s -> filter.difficulty == null || s.difficulty == filter.difficulty }
                    .filter { s -> !filter.onlyIncomplete || s.status != SessionStatus.COMPLETED }
                    .toList()
            }
        }

    override fun observeDuePairIds(nowEpochMs: Long): Flow<List<String>> =
        db.itemMemoryDao().observeDuePairIds(nowEpochMs)

    override fun observeCurrentUnit(sessionId: String): Flow<ScenarioUnitView> =
        dao.observeSession(sessionId).flatMapLatest { session ->
            requireNotNull(session) { "Scenario session $sessionId not found" }
            val pairId = session.currentPairId
            if (pairId == null || session.status != SessionStatus.IN_PROGRESS.name) {
                return@flatMapLatest flowOf(
                    ScenarioUnitView(
                        sessionId = session.id,
                        status = SessionStatus.valueOf(session.status),
                        unit = completedPlaceholderUnit(session),
                        progress = terminalProgress(session),
                    ),
                )
            }
            dao.observeTurnProgress(sessionId, pairId).flatMapLatest { progressRow ->
                val progress = progressRow?.toModel() ?: freshProgress(sessionId, pairId)
                val pair = requireNotNull(dao.getPairById(pairId)) { "Pair $pairId not found" }
                val turns = dao.getTurnsByIds(listOf(pair.customerTurnId, pair.salesTurnId))
                    .associateBy { it.id }
                val customerTurn = requireNotNull(turns[pair.customerTurnId])
                val keywordWords = dao.getPairWords(pairId)
                    .mapNotNull { link -> db.vocabularyDao().getById(link.wordId) }
                flowOf(
                    ScenarioUnitView(
                        sessionId = session.id,
                        status = SessionStatus.IN_PROGRESS,
                        unit = buildUnit(session, pair, customerTurn, keywordWords, progress),
                        progress = progress,
                    ),
                )
            }
        }

    override fun observeLatestInProgress(): Flow<ScenarioSessionSummary?> =
        dao.observeLatestInProgress().flatMapLatest { session ->
            if (session == null) return@flatMapLatest flowOf(null)
            val title = dao.getById(session.scenarioId)?.title ?: session.scenarioId
            flowOf(session.toSummary(title))
        }

    override suspend fun startOrResume(scenarioId: String): String {
        dao.getInProgressForScenario(scenarioId)?.let { return it.id }

        val scenario = requireNotNull(dao.getById(scenarioId)) { "Scenario $scenarioId not found" }
        val pairs = dao.getPairs(scenarioId)
        require(pairs.isNotEmpty()) { "Scenario $scenarioId has no practice pairs" }

        val now = System.currentTimeMillis()
        val sessionId = "scenario_${UUID.randomUUID()}"
        dao.upsertSession(
            ScenarioSessionEntity(
                id = sessionId,
                scenarioId = scenarioId,
                scenarioContentHash = scenario.contentHash,
                status = SessionStatus.IN_PROGRESS.name,
                currentPairId = pairs.first().id,
                currentPairIndex = 0,
                pairCount = pairs.size,
                startedAtEpochMs = now,
                completedAtEpochMs = null,
                updatedAtEpochMs = now,
            ),
        )
        return sessionId
    }

    override suspend fun startOrResumeRandom(): String {
        dao.getInProgressRandom()?.let { return it.id }
        val pairs = dao.getAllActivePairs()
        require(pairs.isNotEmpty()) { "No dialogue pairs available for random practice" }
        val memories = db.itemMemoryDao()
            .getForItems(pairs.map { it.id }, ItemType.PAIR.name)
            .associateBy { it.itemId }
        val now = System.currentTimeMillis()
        val priority = pairs.filter { pair ->
            memories[pair.id]?.let {
                it.dueAtEpochMs <= now || it.lapses > 0 || it.fsrsState != "REVIEW"
            } == true
        }.shuffled()
        val regular = pairs.filterNot { it in priority }.shuffled()
        val queue = (priority + regular).map { it.id }
        val first = pairs.associateBy { it.id }.getValue(queue.first())
        val sessionId = "scenario_random_${UUID.randomUUID()}"
        dao.upsertSession(
            ScenarioSessionEntity(
                id = sessionId,
                scenarioId = RANDOM_SCENARIO_ID,
                scenarioContentHash = "random",
                status = SessionStatus.IN_PROGRESS.name,
                currentPairId = first.id,
                currentPairIndex = 0,
                pairCount = queue.size,
                practiceMode = "RANDOM",
                queuePairIdsJson = queue.toJsonString(),
                startedAtEpochMs = now,
                completedAtEpochMs = null,
                updatedAtEpochMs = now,
            ),
        )
        return sessionId
    }

    override suspend fun endRandomSession(sessionId: String) {
        val session = requireNotNull(dao.getSession(sessionId))
        if (session.practiceMode != "RANDOM" || session.status != SessionStatus.IN_PROGRESS.name) return
        val now = System.currentTimeMillis()
        dao.upsertSession(
            session.copy(
                status = SessionStatus.COMPLETED.name,
                completedAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
    }

    override suspend fun markCustomerAudioCompleted(sessionId: String, pairId: String) {
        mutateProgress(sessionId, pairId) { it.copy(customerAudioCompleted = true) }
    }

    override suspend fun revealCustomerText(sessionId: String, pairId: String) {
        mutateProgress(sessionId, pairId) { it.copy(customerTextRevealed = true) }
    }

    override suspend fun revealKeywords(sessionId: String, pairId: String) {
        mutateProgress(sessionId, pairId) { it.copy(keywordsRevealed = true) }
    }

    override suspend fun revealReferenceAnswer(sessionId: String, pairId: String) {
        mutateProgress(sessionId, pairId) { it.copy(answerRevealed = true) }
    }

    override suspend fun rateAndAdvance(
        sessionId: String,
        pairId: String,
        rating: DialogueSelfRating,
    ): ScenarioAdvance {
        val session = requireNotNull(dao.getSession(sessionId)) { "Session $sessionId not found" }
        check(session.status == SessionStatus.IN_PROGRESS.name) { "Session $sessionId is not in progress" }
        require(session.currentPairId == pairId) { "Rating action is stale" }

        val existing = dao.getTurnProgress(sessionId, pairId)
        require(existing?.answerRevealed == true) { "Reference answer must be revealed before rating" }
        require(existing.selfRating == null) { "Pair has already been rated" }

        val now = System.currentTimeMillis()
        val pairById = dao.getAllActivePairs().associateBy { it.id }
        val scenarioPairs = if (session.practiceMode == "RANDOM") {
            session.queuePairIdsJson.toStringList().mapNotNull(pairById::get)
        } else {
            dao.getPairs(session.scenarioId)
        }
        val nextIndex = session.currentPairIndex + 1
        val completed = session.practiceMode != "RANDOM" && nextIndex >= scenarioPairs.size

        // D3: self-rating maps to an FSRS review of the pair (itemType=PAIR).
        // CANNOT_ANSWER -> AGAIN, BASIC -> HARD, FLUENT -> GOOD.
        val fsrsRating = when (rating) {
            DialogueSelfRating.CANNOT_ANSWER -> Rating.AGAIN
            DialogueSelfRating.BASIC -> Rating.HARD
            DialogueSelfRating.FLUENT -> Rating.GOOD
        }
        val reviewInstant = Instant.ofEpochMilli(now)
        val itemDao = db.itemMemoryDao()
        val existingMemory = itemDao.get(pairId, ItemType.PAIR.name)
        val cardBefore = ItemFsrsSupport.toCard(existingMemory, reviewInstant)
        val cardAfter = scheduler.reviewCard(cardBefore, fsrsRating, reviewInstant).card
        val pairMemoryState = ItemFsrsSupport.toEntity(
            itemId = pairId,
            itemType = ItemType.PAIR.name,
            card = cardAfter,
            reps = (existingMemory?.reps ?: 0) + 1,
            lapses = (existingMemory?.lapses ?: 0) + if (fsrsRating == Rating.AGAIN) 1 else 0,
            learnedContentHash = dao.getPairById(pairId)?.contentHash,
            now = reviewInstant,
        )
        val reviewLog = ReviewLogEntity(
            id = UUID.randomUUID().toString(),
            wordId = pairId, // column semantic: generic itemId (D1)
            rating = fsrsRating.name,
            questionMode = QuestionMode.LISTENING.name,
            usedHint = existing.keywordsRevealed,
            revealedAnswer = true,
            reviewedAtEpochMs = now,
            responseTimeMs = null,
            scheduledDays = 0,
            elapsedDays = 0,
            stateBefore = cardBefore.state.name,
            stateAfter = cardAfter.state.name,
        )

        val ratedProgress = existing.copy(
            selfRating = rating.name,
            updatedAtEpochMs = now,
        )
        val updatedSession = if (completed) {
            session.copy(
                status = SessionStatus.COMPLETED.name,
                completedAtEpochMs = now,
                updatedAtEpochMs = now,
            )
        } else if (session.practiceMode == "RANDOM") {
            val updatedQueue = buildNextRandomQueue(
                session.queuePairIdsJson.toStringList(),
                session.currentPairIndex,
                pairId,
                rating,
                pairById.keys,
            )
            session.copy(
                currentPairId = updatedQueue[nextIndex],
                currentPairIndex = nextIndex,
                pairCount = updatedQueue.size,
                queuePairIdsJson = updatedQueue.toJsonString(),
                updatedAtEpochMs = now,
            )
        } else {
            session.copy(
                currentPairId = scenarioPairs[nextIndex].id,
                currentPairIndex = nextIndex,
                updatedAtEpochMs = now,
            )
        }
        val nextProgress = updatedSession.currentPairId
            ?.takeIf { !completed }
            ?.let { nextPairId ->
                ScenarioTurnProgressEntity(
                    sessionId = sessionId,
                    pairId = nextPairId,
                    customerAudioCompleted = false,
                    customerTextRevealed = false,
                    keywordsRevealed = false,
                    answerRevealed = false,
                    selfRating = null,
                    updatedAtEpochMs = now,
                )
            }

        ScenarioTxRunner(db).rateAndAdvanceAtomic(
            ScenarioTxRunner.RateTx(
                ratedProgress = ratedProgress,
                updatedSession = updatedSession,
                taskDateEpochDay = LocalDate.now(zoneId).toEpochDay(),
                completed = completed,
                studySecondsDelta = ((now - session.startedAtEpochMs) / 1000).coerceAtLeast(0),
                pairMemoryState = pairMemoryState,
                reviewLog = reviewLog,
                nextProgress = nextProgress,
            ),
        )

        return if (completed) {
            val distribution = dao.getAllTurnProgress(sessionId)
                .mapNotNull { it.selfRating }
                .groupingBy { DialogueSelfRating.valueOf(it) }
                .eachCount()
            ScenarioAdvance.Completed(sessionId, distribution)
        } else {
            ScenarioAdvance.NextPair(
                requireNotNull(updatedSession.currentPairId),
                nextIndex,
                updatedSession.pairCount,
            )
        }
    }

    // ------------------------------------------------------------------

    private suspend fun mutateProgress(
        sessionId: String,
        pairId: String,
        transform: (ScenarioTurnProgressEntity) -> ScenarioTurnProgressEntity,
    ) {
        val session = requireNotNull(dao.getSession(sessionId)) { "Session $sessionId not found" }
        check(session.status == SessionStatus.IN_PROGRESS.name) { "Session $sessionId is not in progress" }
        require(session.currentPairId == pairId) { "Action targets a stale pair" }
        val now = System.currentTimeMillis()
        val current = dao.getTurnProgress(sessionId, pairId) ?: ScenarioTurnProgressEntity(
            sessionId = sessionId,
            pairId = pairId,
            customerAudioCompleted = false,
            customerTextRevealed = false,
            keywordsRevealed = false,
            answerRevealed = false,
            selfRating = null,
            updatedAtEpochMs = now,
        )
        dao.upsertTurnProgress(transform(current).copy(updatedAtEpochMs = now))
        dao.upsertSession(session.copy(updatedAtEpochMs = now))
    }

    private fun buildUnit(
        session: ScenarioSessionEntity,
        pair: DialoguePairEntity,
        customerTurn: DialogueTurnEntity,
        keywordWords: List<VocabularyEntryEntity>,
        progress: ScenarioTurnProgress,
    ): ScenarioPracticeUnit =
        ScenarioPracticeUnit(
            scenarioId = pair.scenarioId,
            pairId = pair.id,
            pairIndex = pair.pairIndex,
            pairCount = session.pairCount,
            customerAudioAssetId = customerTurn.audioAssetId.orEmpty(),
            customerText = if (progress.customerTextRevealed) {
                CustomerTextView.Revealed(customerTurn.textEn)
            } else {
                CustomerTextView.Concealed
            },
            keywords = if (progress.keywordsRevealed) {
                KeywordsView.Revealed(
                    keywordWords.map { word ->
                        Keyword(word.id, word.term, word.ipa, word.chineseGloss)
                    },
                )
            } else {
                KeywordsView.Concealed
            },
            answer = if (progress.answerRevealed) {
                ReferenceAnswerView.Revealed(
                    ReferenceAnswer(
                        coreEnglish = pair.referenceCoreEn,
                        chineseHint = pair.referenceChineseHint,
                        formalAlternatives = pair.formalAlternatives(),
                        scoringPoints = pair.scoringPoints(),
                    ),
                )
            } else {
                ReferenceAnswerView.Concealed
            },
        )

    private fun freshProgress(sessionId: String, pairId: String): ScenarioTurnProgress =
        ScenarioTurnProgress(
            sessionId = sessionId,
            pairId = pairId,
            customerAudioCompleted = false,
            customerTextRevealed = false,
            keywordsRevealed = false,
            answerRevealed = false,
            selfRating = null,
            updatedAt = Instant.EPOCH,
        )

    private fun completedPlaceholderUnit(session: ScenarioSessionEntity): ScenarioPracticeUnit =
        ScenarioPracticeUnit(
            scenarioId = session.scenarioId,
            pairId = session.currentPairId ?: "",
            pairIndex = session.currentPairIndex,
            pairCount = session.pairCount,
            customerAudioAssetId = "",
            customerText = CustomerTextView.Concealed,
            keywords = KeywordsView.Concealed,
            answer = ReferenceAnswerView.Concealed,
        )

    private fun terminalProgress(session: ScenarioSessionEntity): ScenarioTurnProgress =
        ScenarioTurnProgress(
            sessionId = session.id,
            pairId = session.currentPairId ?: "",
            customerAudioCompleted = true,
            customerTextRevealed = false,
            keywordsRevealed = false,
            answerRevealed = false,
            selfRating = null,
            updatedAt = Instant.ofEpochMilli(session.updatedAtEpochMs),
        )

    private companion object {
        const val RANDOM_SCENARIO_ID = "__RANDOM__"
    }
}

internal fun buildNextRandomQueue(
    original: List<String>,
    currentIndex: Int,
    pairId: String,
    rating: DialogueSelfRating,
    allPairIds: Set<String>,
): List<String> {
    val queue = original.toMutableList()
    val gap = when (rating) {
        DialogueSelfRating.CANNOT_ANSWER -> 2
        DialogueSelfRating.BASIC -> 5
        DialogueSelfRating.FLUENT -> null
    }
    if (gap != null) {
        while (queue.size < currentIndex + gap + 1) {
            val bag = shuffledBag(allPairIds, queue.lastOrNull())
            queue.addAll(bag.filterNot { it == pairId } + pairId)
        }
        val target = currentIndex + gap + 1
        val existing = queue.indexOfFirstFrom(currentIndex + 1) { it == pairId }
        if (existing < 0) {
            queue.add(target, pairId)
        } else if (existing > target) {
            queue.removeAt(existing)
            queue.add(target, pairId)
        }
    }
    if (currentIndex + 1 >= queue.size) {
        queue.addAll(shuffledBag(allPairIds, queue.lastOrNull()))
    }
    return queue
}

private fun shuffledBag(allPairIds: Set<String>, previous: String?): List<String> {
    val bag = allPairIds.shuffled().toMutableList()
    if (bag.size > 1 && bag.first() == previous) {
        val swap = bag.indexOfFirst { it != previous }
        val value = bag[0]
        bag[0] = bag[swap]
        bag[swap] = value
    }
    return bag
}

private fun <T> List<T>.indexOfFirstFrom(start: Int, predicate: (T) -> Boolean): Int {
    for (index in start.coerceAtLeast(0) until size) {
        if (predicate(this[index])) return index
    }
    return -1
}
