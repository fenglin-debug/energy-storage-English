package com.bess.salestrainer.core.model.fake

import com.bess.salestrainer.core.model.ContentSource
import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.MasteryFilter
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.RecordWordReview
import com.bess.salestrainer.core.model.ReviewAdvance
import com.bess.salestrainer.core.model.Vocabulary
import com.bess.salestrainer.core.model.VocabularyFilter
import com.bess.salestrainer.core.model.VocabularyQueue
import com.bess.salestrainer.core.model.VocabularySessionCheckpoint
import com.bess.salestrainer.core.model.VocabularySessionStatus
import com.bess.salestrainer.core.model.VocabularySessionView
import com.bess.salestrainer.core.model.VocabularySelfAssessment
import com.bess.salestrainer.core.model.WordMemoryState
import com.bess.salestrainer.core.model.contract.VocabularyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FakeVocabularyRepository : VocabularyRepository {
    private val words = MutableStateFlow(sampleWords())
    private val sessions = ConcurrentHashMap<String, MutableStateFlow<VocabularySessionView>>()
    private var activeSessionId: String? = null

    override fun observeWord(wordId: String): Flow<Vocabulary> =
        words.map { values -> values.first { it.id == wordId } }

    override fun observeVocabulary(filter: VocabularyFilter): Flow<List<Vocabulary>> =
        words.map { values ->
            values.filter { word ->
                val query = filter.query?.trim().orEmpty()
                (filter.topic == null || word.topic == filter.topic) &&
                    (filter.cefrLevel == null || word.cefrLevel == filter.cefrLevel) &&
                    (!filter.favoritesOnly || word.memoryState?.isFavorite == true) &&
                    (!filter.dueOnly || word.memoryState?.dueAt?.let { !it.isAfter(Instant.now()) } == true) &&
                    matchesMastery(word, filter.mastery) &&
                    (query.isEmpty() ||
                        word.term.contains(query, ignoreCase = true) ||
                        word.chineseGloss.contains(query, ignoreCase = true) ||
                        word.aliases.any { it.contains(query, ignoreCase = true) })
            }
        }

    override fun observeTodayQueue(): Flow<VocabularyQueue> =
        words.map { values ->
            VocabularyQueue(
                dueReviews = values.filter { it.memoryState?.fsrsState != FsrsState.NEW },
                newWords = values.filter { it.memoryState?.fsrsState == FsrsState.NEW }.take(15),
            )
        }

    override fun observeSession(sessionId: String): Flow<VocabularySessionView> =
        sessions.getValue(sessionId)

    override suspend fun startOrResumeSession(): String {
        activeSessionId?.let { id ->
            if (sessions[id]?.value?.checkpoint?.status == VocabularySessionStatus.IN_PROGRESS) return id
        }
        val queue = words.value
            .sortedWith(compareBy<Vocabulary> { it.memoryState?.fsrsState == FsrsState.NEW }
                .thenBy { it.memoryState?.dueAt }
                .thenBy { it.id })
            .map { it.id }
        require(queue.isNotEmpty()) { "No vocabulary is available" }
        val now = Instant.now()
        val sessionId = "vocab_${UUID.randomUUID()}"
        val checkpoint = VocabularySessionCheckpoint(
            sessionId = sessionId,
            status = VocabularySessionStatus.IN_PROGRESS,
            corpusVersion = "fake-1",
            queueWordIds = queue,
            currentIndex = 0,
            questionMode = questionModeFor(words.value.first { it.id == queue.first() }),
            answerRevealed = false,
            hintRevealed = false,
            startedAt = now,
            updatedAt = now,
        )
        sessions[sessionId] = MutableStateFlow(
            VocabularySessionView(checkpoint, words.value.first { it.id == queue.first() }, queue.size)
        )
        activeSessionId = sessionId
        return sessionId
    }

    override suspend fun submitAssessment(
        sessionId: String,
        itemId: String,
        assessment: VocabularySelfAssessment,
    ) {
        val flow = sessions.getValue(sessionId)
        val view = flow.value
        require(view.currentWord?.id == itemId)
        if (view.checkpoint.assessmentSubmitted) return
        val now = Instant.now()
        val rating = when (assessment) {
            VocabularySelfAssessment.UNFAMILIAR -> Rating.AGAIN
            VocabularySelfAssessment.FUZZY -> Rating.HARD
            VocabularySelfAssessment.MASTERED -> Rating.EASY
        }
        updateMemory(
            itemId,
            RecordWordReview(
                expectedWordId = itemId,
                expectedIndex = view.checkpoint.currentIndex,
                rating = rating,
                usedHint = false,
                reviewedAt = now,
            ),
            if (assessment == VocabularySelfAssessment.UNFAMILIAR) 0 else 1,
        )
        if (assessment == VocabularySelfAssessment.MASTERED) {
            words.value = words.value.map {
                if (it.id == itemId) {
                    it.copy(memoryState = it.memoryState?.copy(masteredUi = true, dueAt = Instant.MAX))
                } else {
                    it
                }
            }
        }
        flow.value = view.copy(
            currentWord = words.value.first { it.id == itemId },
            checkpoint = view.checkpoint.copy(
                assessmentSubmitted = true,
                selectedAssessment = assessment,
                updatedAt = now,
            ),
        )
    }

    override suspend fun advanceToNext(sessionId: String) {
        val flow = sessions.getValue(sessionId)
        val view = flow.value
        if (!view.checkpoint.assessmentSubmitted) return
        val nextIndex = view.checkpoint.currentIndex + 1
        if (nextIndex >= view.checkpoint.queueWordIds.size) {
            flow.value = view.copy(
                checkpoint = view.checkpoint.copy(
                    status = VocabularySessionStatus.COMPLETED,
                    currentIndex = nextIndex,
                    updatedAt = Instant.now(),
                ),
                currentWord = null,
                remainingCount = 0,
                hasNext = false,
            )
        } else {
            val next = words.value.first { it.id == view.checkpoint.queueWordIds[nextIndex] }
            flow.value = VocabularySessionView(
                checkpoint = view.checkpoint.copy(
                    currentIndex = nextIndex,
                    assessmentSubmitted = false,
                    selectedAssessment = null,
                    updatedAt = Instant.now(),
                ),
                currentWord = next,
                remainingCount = view.checkpoint.queueWordIds.size - nextIndex,
                hasNext = nextIndex < view.checkpoint.queueWordIds.lastIndex,
            )
        }
    }

    suspend fun revealVocabularyAnswer(sessionId: String) {
        val flow = sessions.getValue(sessionId)
        val view = flow.value
        require(view.checkpoint.status == VocabularySessionStatus.IN_PROGRESS)
        flow.value = view.copy(
            checkpoint = view.checkpoint.copy(answerRevealed = true, updatedAt = Instant.now())
        )
    }

    suspend fun recordReview(
        sessionId: String,
        command: RecordWordReview,
    ): ReviewAdvance {
        val flow = sessions.getValue(sessionId)
        val view = flow.value
        val checkpoint = view.checkpoint
        require(checkpoint.status == VocabularySessionStatus.IN_PROGRESS)
        require(checkpoint.answerRevealed) { "Answer must be revealed before rating" }
        require(command.expectedIndex == checkpoint.currentIndex) { "Review action is stale" }
        require(command.expectedWordId == view.currentWord?.id) { "Review word is stale" }

        val days = when (command.rating) {
            Rating.AGAIN -> 0L
            Rating.HARD -> 1L
            Rating.GOOD -> 3L
            Rating.EASY -> 7L
        }
        updateMemory(command.expectedWordId, command, days)
        val nextIndex = checkpoint.currentIndex + 1
        return if (nextIndex >= checkpoint.queueWordIds.size) {
            flow.value = view.copy(
                checkpoint = checkpoint.copy(
                    status = VocabularySessionStatus.COMPLETED,
                    currentIndex = nextIndex,
                    updatedAt = command.reviewedAt,
                ),
                currentWord = null,
                remainingCount = 0,
            )
            activeSessionId = null
            ReviewAdvance.Completed(sessionId)
        } else {
            val nextWord = words.value.first { it.id == checkpoint.queueWordIds[nextIndex] }
            val nextMode = questionModeFor(nextWord)
            flow.value = VocabularySessionView(
                checkpoint = checkpoint.copy(
                    currentIndex = nextIndex,
                    questionMode = nextMode,
                    answerRevealed = false,
                    hintRevealed = false,
                    updatedAt = command.reviewedAt,
                ),
                currentWord = nextWord,
                remainingCount = checkpoint.queueWordIds.size - nextIndex,
            )
            ReviewAdvance.Next(nextWord.id, nextMode, checkpoint.queueWordIds.size - nextIndex)
        }
    }

    override suspend fun setFavorite(wordId: String, favorite: Boolean) {
        words.value = words.value.map { word ->
            if (word.id != wordId) word
            else word.copy(
                memoryState = (word.memoryState ?: newMemory(word.id)).copy(isFavorite = favorite)
            )
        }
    }

    private fun updateMemory(wordId: String, command: RecordWordReview, days: Long) {
        words.value = words.value.map { word ->
            if (word.id != wordId) word
            else {
                val previous = word.memoryState ?: newMemory(word.id)
                word.copy(
                    memoryState = previous.copy(
                        fsrsState = if (command.rating == Rating.AGAIN) FsrsState.RELEARNING else FsrsState.REVIEW,
                        stability = days.coerceAtLeast(1).toDouble(),
                        dueAt = command.reviewedAt.plus(days, ChronoUnit.DAYS),
                        reps = previous.reps + 1,
                        lapses = previous.lapses + if (command.rating == Rating.AGAIN) 1 else 0,
                        masteredUi = command.rating == Rating.EASY && previous.reps >= 2,
                    )
                )
            }
        }
    }

    private fun questionModeFor(word: Vocabulary): QuestionMode {
        val memory = word.memoryState ?: return QuestionMode.INTRODUCE
        if (memory.fsrsState == FsrsState.NEW) return QuestionMode.INTRODUCE
        return when (memory.reps % 3) {
            0 -> QuestionMode.LISTENING
            1 -> QuestionMode.TRANSFER
            else -> QuestionMode.EN2ZH
        }
    }

    private fun matchesMastery(word: Vocabulary, filter: MasteryFilter): Boolean =
        when (filter) {
            MasteryFilter.ALL -> true
            MasteryFilter.NOT_STARTED -> word.memoryState?.fsrsState == FsrsState.NEW
            MasteryFilter.LEARNING -> word.memoryState?.let { !it.masteredUi && it.fsrsState != FsrsState.NEW } == true
            MasteryFilter.MASTERED -> word.memoryState?.masteredUi == true
        }

    private fun newMemory(wordId: String) = WordMemoryState(
        wordId = wordId,
        fsrsState = FsrsState.NEW,
        difficulty = 5.0,
        stability = 0.0,
        dueAt = Instant.EPOCH,
        reps = 0,
        lapses = 0,
        masteredUi = false,
        lastQuestionMode = null,
    )

    private fun sampleWords(): List<Vocabulary> = listOf(
        Vocabulary(
            id = "V_001",
            term = "round-trip efficiency",
            normalizedTerm = "round-trip efficiency",
            ipa = "/ˌraʊnd ˈtrɪp ɪˈfɪʃənsi/",
            partOfSpeech = "n.",
            chineseGloss = "往返效率",
            collocations = listOf("improve round-trip efficiency"),
            exampleSentenceEn = "Our system achieves high round-trip efficiency.",
            exampleSentenceZh = "我们的系统实现了较高的往返效率。",
            commonMistakes = "Do not say “back-and-forth efficiency”.",
            topic = "系统性能",
            cefrLevel = "B2",
            wordAudioAssetId = "audio_word_v001",
            exampleAudioAssetId = "audio_example_v001",
            contentHash = "fake-v001",
            contentSource = ContentSource.CORE,
            memoryState = newMemory("V_001"),
        ),
        Vocabulary(
            id = "V_002",
            term = "auxiliary consumption",
            normalizedTerm = "auxiliary consumption",
            ipa = "/ɔːɡˈzɪliəri kənˈsʌmpʃən/",
            partOfSpeech = "n.",
            chineseGloss = "辅助用电",
            aliases = listOf("auxiliary power consumption"),
            collocations = listOf("reduce auxiliary consumption"),
            exampleSentenceEn = "Auxiliary consumption depends on the operating mode.",
            commonMistakes = "Distinguish auxiliary consumption from round-trip loss.",
            topic = "系统性能",
            cefrLevel = "B2",
            wordAudioAssetId = "audio_word_v002",
            exampleAudioAssetId = "audio_example_v002",
            contentHash = "fake-v002",
            memoryState = newMemory("V_002").copy(
                fsrsState = FsrsState.REVIEW,
                dueAt = Instant.now().minus(1, ChronoUnit.DAYS),
                reps = 2,
            ),
        ),
    )
}
