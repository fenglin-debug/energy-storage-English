package com.bess.salestrainer.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Vocabulary corpus entry (TDD §4 VocabularyEntry). Soft-deleted via [active]. */
@Entity(
    tableName = "vocabulary_entries",
    indices = [Index("normalizedTerm", unique = true), Index("topic"), Index("active")],
)
data class VocabularyEntryEntity(
    @PrimaryKey val id: String,
    val term: String,
    val normalizedTerm: String,
    val ipa: String?,
    val partOfSpeech: String?,
    val chineseGloss: String,
    val englishDefinition: String?,
    val collocationsJson: String,   // JSON array
    val exampleSentenceEn: String?,
    val exampleSentenceZh: String?,
    val commonMistakes: String?,
    val topic: String?,
    val scenarioTagsJson: String,   // JSON array
    val cefrLevel: String?,
    val audioRef: String?,
    val exampleAudioRef: String?,
    val contentSource: String,      // CORE / EXTERNAL / AI
    val contentVersion: String,
    val aliasOf: String?,
    val active: Boolean = true,
)

/** Scenario metadata (TDD §4 Scenario). */
@Entity(
    tableName = "scenarios",
    indices = [Index("topic"), Index("active")],
)
data class ScenarioEntity(
    @PrimaryKey val id: String,
    val title: String,
    val topic: String,
    val salesStage: String?,
    val difficulty: String?,
    val estimatedMinutes: Int?,
    val description: String?,
    val contentVersion: String,
    val active: Boolean = true,
)

/** A dialogue turn; id = scenarioId_turnNo (TDD §4 DialogueTurn). */
@Entity(
    tableName = "dialogue_turns",
    indices = [Index("scenarioId", "turnNo", unique = true)],
)
data class DialogueTurnEntity(
    @PrimaryKey val id: String,
    val scenarioId: String,
    val turnNo: Int,
    val speaker: String,            // CUSTOMER / SALES_ENGINEER
    val textEn: String,
    val textZh: String?,
    val ttsText: String,
    val audioRef: String?,
    val keywordsJson: String,       // JSON array
    val expectedPointsJson: String, // JSON array
    val referenceAnswerEn: String?,
)

/** Per-scenario training pack 1:1 (TDD §4 ScenarioTrainingPack). */
@Entity(tableName = "scenario_training_packs")
data class ScenarioTrainingPackEntity(
    @PrimaryKey val scenarioId: String,
    val listeningTranscript: String?,
    val shadowingScript: String?,
    val comprehensionQuestionsJson: String?, // JSON array
    val retellingPrompt: String?,
    val rolePlayPrompt: String?,
    val surpriseQuestionsJson: String?,      // JSON array
)

/** Verified / contextual facts that constrain AI output (TDD §4 SafeFact). */
@Entity(tableName = "safe_facts")
data class SafeFactEntity(
    @PrimaryKey val id: String,
    val topic: String?,
    val claim: String,
    val valueText: String?,
    val unit: String?,
    val status: String,             // CONFIRMED / CONTEXTUAL / VERIFY
    val sourceNote: String?,
)

/** Conflict-guard wording guiding AI away from contradictions. */
@Entity(tableName = "conflict_guards")
data class ConflictGuardEntity(
    @PrimaryKey val id: String,
    val topic: String?,
    val guardText: String,
)

/** Active corpus version pointer (exactly one active row). */
@Entity(tableName = "corpus_versions")
data class CorpusVersionEntity(
    @PrimaryKey val packageId: String,
    val schemaVersion: Int,
    val contentVersion: String,
    val vocabularyCount: Int,
    val scenarioCount: Int,
    val isBundled: Boolean,
    val active: Boolean,
    val importedAtEpochMs: Long,
)
