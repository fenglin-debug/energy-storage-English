package com.bess.salestrainer.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Room v2 user-state tables (TDD §5.2). These survive corpus swaps by stable
// id and are coordinated, never blindly deleted, on corpus upgrade.
// ---------------------------------------------------------------------------

/** FSRS scheduling state per word. Survives corpus swaps by stable word id. */
@Entity(
    tableName = "word_memory_states",
    indices = [Index("dueAtEpochMs")],
)
@Serializable
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
    val learnedContentHash: String?,
    /**
     * Transient migration aid: v1 normalized term, written by MIGRATION_1_2 and
     * consumed once by the first bundled-corpus import to reconcile learning
     * state. Not read by the runtime after that.
     */
    val legacyNormalizedTerm: String? = null,
    val updatedAtEpochMs: Long,
)

/** Append-only review log (insert-never-update). */
@Entity(
    tableName = "review_logs",
    indices = [Index("wordId"), Index("reviewedAtEpochMs")],
)
@Serializable
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

/**
 * Precise vocabulary learning checkpoint. The frozen queue keeps the session
 * stable across midnight, settings changes and corpus flows.
 */
@Entity(
    tableName = "vocabulary_session_checkpoints",
    indices = [Index("status"), Index("updatedAtEpochMs")],
)
@Serializable
data class VocabularySessionCheckpointEntity(
    @PrimaryKey val sessionId: String,
    val status: String,             // IN_PROGRESS / COMPLETED / EXPIRED
    val corpusVersion: String,
    val queueWordIdsJson: String,   // JSON array of stable word ids, frozen at start
    val currentIndex: Int,
    val questionMode: String,
    val answerRevealed: Boolean,
    val hintRevealed: Boolean,
    val assessmentSubmitted: Boolean = false,
    val selectedAssessment: String? = null,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** Idempotency guard for review actions: one action per (sessionId, currentIndex). */
@Entity(tableName = "review_action_keys")
@Serializable
data class ReviewActionKeyEntity(
    @PrimaryKey val actionKey: String, // "<sessionId>:<currentIndex>"
    val sessionId: String,
    val currentIndex: Int,
    val createdAtEpochMs: Long,
)

/** A scenario practice session (single in-progress per scenario). */
@Entity(
    tableName = "scenario_sessions",
    indices = [Index("scenarioId"), Index("status"), Index("updatedAtEpochMs")],
)
@Serializable
data class ScenarioSessionEntity(
    @PrimaryKey val id: String,
    val scenarioId: String,
    val scenarioContentHash: String,
    val status: String,             // IN_PROGRESS / COMPLETED / ABORTED_CORPUS_CHANGED / ABORTED_LEGACY
    val currentPairId: String?,
    val currentPairIndex: Int,
    val pairCount: Int,
    val practiceMode: String = "SCENARIO",
    val queuePairIdsJson: String = "[]",
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
)

/** Per-pair precise progress: reveal states and self rating. */
@Entity(
    tableName = "scenario_turn_progress",
    primaryKeys = ["sessionId", "pairId"],
)
@Serializable
data class ScenarioTurnProgressEntity(
    val sessionId: String,
    val pairId: String,
    val customerAudioCompleted: Boolean,
    val customerTextRevealed: Boolean,
    val keywordsRevealed: Boolean,
    val answerRevealed: Boolean,
    val selfRating: String?,        // CANNOT_ANSWER / BASIC / FLUENT
    val updatedAtEpochMs: Long,
)

/** Daily study task snapshot (one row per local date). */
@Entity(tableName = "study_tasks")
@Serializable
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

/** Import audit event (no file paths or content — ids, counts and codes only). */
@Entity(tableName = "corpus_import_events")
data class CorpusImportEventEntity(
    @PrimaryKey val id: String,
    val previewId: String?,
    val packageKey: String?,
    val resultCode: String,         // SUCCESS / failure CorpusError code
    val addedCount: Int,
    val updatedCount: Int,
    val removedCount: Int,
    val unchangedCount: Int,
    val createdAtEpochMs: Long,
)

// ---------------------------------------------------------------------------
// Room v3 additions.
// ---------------------------------------------------------------------------

/**
 * FSRS scheduling state for non-word items (phrases, sentence examples,
 * dialogue pairs). Words keep using word_memory_states (D1 decision).
 */
@Entity(
    tableName = "item_memory_states",
    primaryKeys = ["itemId", "itemType"],
    indices = [Index("dueAtEpochMs"), Index("itemType")],
)
@Serializable
data class ItemMemoryStateEntity(
    val itemId: String,             // PHR-nnnn / EX-nnnn / MIS-nnnn_Pnnn
    val itemType: String,           // PHRASE / EXAMPLE / PAIR
    val fsrsState: String,          // NEW / LEARNING / REVIEW / RELEARNING
    val difficulty: Double,
    val stability: Double,
    val dueAtEpochMs: Long,
    val lastReviewAtEpochMs: Long?,
    val reps: Int,
    val lapses: Int,
    val masteredUi: Boolean = false,
    val learnedContentHash: String?,
    val updatedAtEpochMs: Long,
)

/** Listening progress for 磨耳朵 articles (one row per article). */
@Entity(tableName = "article_progress")
@Serializable
data class ArticleProgressEntity(
    @PrimaryKey val articleId: String,
    val lastPositionMs: Long,
    val listenCount: Int,
    val completedAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
)
