package com.bess.salestrainer.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** FSRS scheduling state per word (TDD §4 WordMemoryState). Survives corpus swaps by stable id. */
@Entity(tableName = "word_memory_states")
data class WordMemoryStateEntity(
    @PrimaryKey val wordId: String,
    val fsrsState: String,          // NEW / LEARNING / REVIEW / RELEARNING
    val difficulty: Double,
    val stability: Double,
    val dueAtEpochMs: Long,
    val lastReviewAtEpochMs: Long?,
    val reps: Int,
    val lapses: Int,
    val masteredUi: Boolean,
    val lastQuestionMode: String?,
    val isFavorite: Boolean = false,
    val updatedAtEpochMs: Long,
)

/** Append-only review log (TDD §4 ReviewLog, insert-never-update). */
@Entity(
    tableName = "review_logs",
    indices = [Index("wordId"), Index("reviewedAtEpochMs")],
)
data class ReviewLogEntity(
    @PrimaryKey val id: String,
    val wordId: String,
    val rating: String,             // AGAIN / HARD / GOOD / EASY
    val questionMode: String,       // INTRODUCE / EN2ZH / ZH2EN / LISTENING / TRANSFER
    val usedHint: Boolean,
    val revealedAnswer: Boolean,
    val reviewedAtEpochMs: Long,
    val responseTimeMs: Long?,
    val scheduledDays: Long,
    val elapsedDays: Long,
    val stateBefore: String,
    val stateAfter: String,
)

/** A scenario practice/simulation session (TDD §4 ScenarioSession). */
@Entity(
    tableName = "scenario_sessions",
    indices = [Index("scenarioId"), Index("status"), Index("updatedAtEpochMs")],
)
data class ScenarioSessionEntity(
    @PrimaryKey val id: String,
    val scenarioId: String,
    val mode: String,               // SIMULATION / PRACTICE
    val status: String,             // IN_PROGRESS / COMPLETED / ABANDONED
    val currentCustomerTurnNo: Int,
    val localScoreJson: String?,    // JSON LocalEvaluation
    val aiStatus: String,           // NOT_REQUESTED / PENDING / SUCCEEDED / FAILED
    val aiEvaluationId: String?,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
)

/** One recording attempt for a sales turn; exactly one accepted per (sessionId, turnNo). */
@Entity(
    tableName = "turn_attempts",
    indices = [Index("sessionId", "turnNo")],
)
data class TurnAttemptEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val turnNo: Int,
    val accepted: Boolean,
    val rawTranscript: String?,
    val editedTranscript: String?,
    val wpm: Double?,
    val pauseRatio: Double?,
    val maxPauseMs: Long?,
    val fillerCount: Int?,
    val keywordCoverage: Double?,
    val audioFileRef: String?,
    val createdAtEpochMs: Long,
)

/** Daily study task snapshot (one row per local date). */
@Entity(tableName = "study_tasks")
data class StudyTaskEntity(
    @PrimaryKey val dateEpochDay: Long,   // LocalDate.toEpochDay()
    val newWordTarget: Int,
    val newWordDone: Int,
    val reviewTarget: Int,
    val reviewDone: Int,
    val recommendedScenarioId: String?,
    val studySeconds: Long,
    val completed: Boolean,
    val updatedAtEpochMs: Long,
)

/** Persisted AI advice (AI-sourced materials; never promoted to core corpus). */
@Entity(
    tableName = "ai_advices",
    indices = [Index("sessionId")],
)
data class AiAdviceEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val scoresJson: String?,        // JSON AiDimensionScores
    val strengthsJson: String,      // JSON array
    val issuesJson: String,         // JSON array
    val missingPointsJson: String,  // JSON array
    val improvedResponsesJson: String, // JSON array
    val recommendedWordIdsJson: String, // JSON array
    val retellingTaskEn: String?,
    val factRisksJson: String,      // JSON array
    val promptVersion: String,
    val modelName: String,
    val isLocalFallback: Boolean,
    val evaluationRequestId: String, // idempotency key
    val isFavorite: Boolean = false,
    val createdAtEpochMs: Long,
)
