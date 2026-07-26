package com.bess.salestrainer.core.model

/** FSRS four-grade rating (FR-VOC-003). */
enum class Rating { AGAIN, HARD, GOOD, EASY }

/** FSRS card scheduling state. */
enum class FsrsState { NEW, LEARNING, REVIEW, RELEARNING }

/** Five vocabulary learning stages (FR-VOC-002). */
enum class QuestionMode { INTRODUCE, EN2ZH, ZH2EN, LISTENING, TRANSFER }

/** Scenario session mode (FR-DIA-002). */
enum class ScenarioMode { SIMULATION, PRACTICE }

/** Scenario session lifecycle. */
enum class SessionStatus { IN_PROGRESS, COMPLETED, ABANDONED }

/** Dialogue speaker roles (TDD §4). */
enum class Speaker { CUSTOMER, SALES_ENGINEER }

/** Content provenance — AI content never enters core corpus (TDD §6). */
enum class ContentSource { CORE, EXTERNAL, AI }

/** Safe-fact verification status (FR-AI-002). */
enum class FactStatus { CONFIRMED, CONTEXTUAL, VERIFY }

/** AI evaluation status for a session. */
enum class AiStatus { NOT_REQUESTED, PENDING, SUCCEEDED, FAILED }

/** ASR model lifecycle states (FR-DIA-005, TASK-C-02). */
sealed interface AsrModelState {
    data object NotDownloaded : AsrModelState
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : AsrModelState
    data object Verifying : AsrModelState
    data class Ready(val modelVersion: String) : AsrModelState
    data class Error(val reason: String, val canRetry: Boolean) : AsrModelState
}

/** Corpus import state machine (TDD §6.5). */
sealed interface CorpusImportState {
    data object Idle : CorpusImportState
    data object Selected : CorpusImportState
    data object Copying : CorpusImportState
    data object VerifyingChecksums : CorpusImportState
    data object Parsing : CorpusImportState
    data object Staging : CorpusImportState
    data object Validating : CorpusImportState
    data object Committing : CorpusImportState
    data class Failed(val stage: String, val reason: String) : CorpusImportState
}
