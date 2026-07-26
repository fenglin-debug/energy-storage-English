package com.bess.salestrainer.core.model

import java.time.Instant

/** A vocabulary entry as exposed to UI (never a Room entity). FR-VOC-001. */
data class Vocabulary(
    val id: String,
    val term: String,
    val normalizedTerm: String,
    val ipa: String?,
    val partOfSpeech: String?,
    val chineseGloss: String,
    val englishDefinition: String?,
    val collocations: List<String> = emptyList(),
    val exampleSentenceEn: String?,
    val exampleSentenceZh: String?,
    val commonMistakes: String?,
    val topic: String?,
    val scenarioTags: List<String> = emptyList(),
    val cefrLevel: String?,
    val contentSource: ContentSource = ContentSource.CORE,
    val memoryState: WordMemoryState? = null,
)

/** UI-facing memory/scheduling snapshot of a word. */
data class WordMemoryState(
    val wordId: String,
    val fsrsState: FsrsState,
    val difficulty: Double,
    val stability: Double,
    val dueAt: Instant,
    val reps: Int,
    val lapses: Int,
    /** Display-only mastery flag — never stops scheduling (TDD §8). */
    val masteredUi: Boolean,
    val lastQuestionMode: QuestionMode?,
    val isFavorite: Boolean = false,
)

/** Today queue: new words + due reviews (FR-HOME-001). */
data class VocabularyQueue(
    val newWords: List<Vocabulary>,
    val dueReviews: List<Vocabulary>,
    val overflowDeferredCount: Int,
)

/** Combined filter for vocabulary browsing (FR-VOC-006). */
data class VocabularyFilter(
    val topic: String? = null,
    val cefrLevel: String? = null,
    val mastery: MasteryFilter = MasteryFilter.ALL,
    val dueOnly: Boolean = false,
    val favoritesOnly: Boolean = false,
    val query: String? = null,
)

enum class MasteryFilter { ALL, NOT_STARTED, LEARNING, MASTERED }

/** Command to record a review. Submitted exactly once per user action. */
data class RecordWordReview(
    val wordId: String,
    val rating: Rating,
    val questionMode: QuestionMode,
    val usedHint: Boolean,
    val revealedAnswer: Boolean,
    val reviewedAt: Instant,
    val responseTimeMs: Long? = null,
)

/** Result of a recorded review. */
data class ReviewResult(
    val wordId: String,
    val newState: FsrsState,
    val nextDueAt: Instant,
    val reps: Int,
    val lapses: Int,
)
