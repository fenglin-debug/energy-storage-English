package com.bess.salestrainer.core.model

enum class Rating { AGAIN, HARD, GOOD, EASY }

enum class FsrsState { NEW, LEARNING, REVIEW, RELEARNING }

enum class QuestionMode { INTRODUCE, EN2ZH, ZH2EN, LISTENING, TRANSFER }

enum class DialogueSelfRating { CANNOT_ANSWER, BASIC, FLUENT }

enum class SessionStatus {
    IN_PROGRESS,
    COMPLETED,
    ABORTED_CORPUS_CHANGED,
    ABORTED_LEGACY,
}

enum class Speaker { CUSTOMER, SALES_ENGINEER }

/** Vocabulary checkpoint lifecycle (distinct from scenario SessionStatus). */
enum class VocabularySessionStatus { IN_PROGRESS, COMPLETED, EXPIRED }

enum class ContentSource { CORE, EXTERNAL }

enum class PlaybackSpeed(val factor: Float) {
    SLOW(0.85f),
    NORMAL(1.0f),
    FAST(1.15f),
}

enum class AudioKind { CUSTOMER_TURN, SALES_TURN, WORD, EXAMPLE, PHRASE, ARTICLE }

enum class AudioPlaybackState { IDLE, BUFFERING, PLAYING, PAUSED, COMPLETED, FAILED }

sealed interface CorpusImportState {
    data object Idle : CorpusImportState
    data object Copying : CorpusImportState
    data object Verifying : CorpusImportState
    data object Parsing : CorpusImportState
    data object Staging : CorpusImportState
    data object PreviewReady : CorpusImportState
    data object Committing : CorpusImportState
    data class Failed(val error: CorpusError) : CorpusImportState
}
