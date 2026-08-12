package com.bess.salestrainer.core.model

import java.time.Instant

/** A vocabulary entry as exposed to UI (never a Room entity). FR-VOC-001. */
data class Vocabulary(
    val id: String,
    /** FR-v2: WORD = 词汇表词条, PHRASE = 常用短语 (mixed into the practice queue). */
    val itemKind: ItemKind = ItemKind.WORD,
    val term: String,
    val normalizedTerm: String,
    val ipa: String,
    val partOfSpeech: String,
    val chineseGloss: String,
    val englishDefinition: String? = null,
    val collocations: List<String> = emptyList(),
    val exampleSentenceEn: String,
    val exampleSentenceZh: String? = null,
    val commonMistakes: String,
    val topic: String,
    val scenarioTags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val cefrLevel: String,
    val wordAudioAssetId: String,
    val exampleAudioAssetId: String,
    val examples: List<VocabularyExample> = emptyList(),
    val contentHash: String,
    val contentSource: ContentSource = ContentSource.CORE,
    val memoryState: WordMemoryState? = null,
)

enum class ItemKind { WORD, PHRASE }

data class VocabularyExample(
    val id: String,
    val textEn: String,
    val textZh: String,
    val audioAssetId: String,
)

enum class VocabularySelfAssessment {
    UNFAMILIAR,
    FUZZY,
    MASTERED,
}

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
    val expectedWordId: String,
    val expectedIndex: Int,
    val rating: Rating,
    val usedHint: Boolean,
    val reviewedAt: Instant,
    val responseTimeMs: Long? = null,
)

data class VocabularySessionCheckpoint(
    val sessionId: String,
    val status: VocabularySessionStatus,
    val corpusVersion: String,
    val queueWordIds: List<String>,
    val currentIndex: Int,
    val questionMode: QuestionMode,
    val answerRevealed: Boolean,
    val hintRevealed: Boolean,
    val assessmentSubmitted: Boolean = false,
    val selectedAssessment: VocabularySelfAssessment? = null,
    val startedAt: Instant,
    val updatedAt: Instant,
)

data class VocabularySessionView(
    val checkpoint: VocabularySessionCheckpoint,
    val currentWord: Vocabulary?,
    val remainingCount: Int,
    val hasNext: Boolean = false,
)

sealed interface ReviewAdvance {
    data class Next(
        val wordId: String,
        val questionMode: QuestionMode,
        val remainingCount: Int,
    ) : ReviewAdvance

    data class Completed(val sessionId: String) : ReviewAdvance
}

data class ScheduledReview(
    val wordId: String,
    val newState: FsrsState,
    val nextDueAt: Instant,
    val reps: Int,
    val lapses: Int,
)
