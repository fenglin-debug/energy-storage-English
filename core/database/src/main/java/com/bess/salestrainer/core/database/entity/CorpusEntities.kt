package com.bess.salestrainer.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ---------------------------------------------------------------------------
// Room v2 content tables (TDD §5.1). Content tables only hold the ACTIVE
// corpus version; replacement happens inside a single transaction.
// ---------------------------------------------------------------------------

/** Audit row for every successfully imported corpus package. */
@Entity(tableName = "corpus_versions")
data class CorpusVersionEntity(
    @PrimaryKey val packageKey: String,
    val packageId: String,
    val schemaVersion: Int,
    val contentVersion: String,
    val vocabularyCount: Int,
    val scenarioCount: Int,
    val dialogueTurnCount: Int,
    val dialoguePairCount: Int,
    val audioAssetCount: Int,
    val manifestSha256: String,
    val isBundled: Boolean,
    val importedAtEpochMs: Long,
)

/** Singleton pointer to the active corpus version (exactly one row, id = 1). */
@Entity(tableName = "active_corpus")
data class ActiveCorpusEntity(
    @PrimaryKey val id: Int = 1,
    val packageKey: String,
)

/** Vocabulary corpus entry. Learning state lives in word_memory_states by stable id. */
@Entity(
    tableName = "vocabulary_entries",
    indices = [
        Index("normalizedTerm", unique = true),
        Index("topic"),
        Index("cefrLevel"),
        Index("active"),
    ],
)
data class VocabularyEntryEntity(
    @PrimaryKey val id: String,
    val term: String,
    val normalizedTerm: String,
    val ipa: String,
    val partOfSpeech: String,
    val chineseGloss: String,
    val englishDefinition: String?,
    val collocationsJson: String,   // JSON array
    val exampleSentenceEn: String,
    val exampleSentenceZh: String?,
    val commonMistakes: String,
    val topic: String,
    val scenarioTagsJson: String,   // JSON array
    val cefrLevel: String,
    val wordAudioAssetId: String,
    val exampleAudioAssetId: String,
    val contentSource: String,      // CORE / EXTERNAL
    val contentHash: String,
    val active: Boolean = true,
)

/** Search aliases pointing at a canonical vocabulary entry. */
@Entity(
    tableName = "vocabulary_aliases",
    primaryKeys = ["wordId", "aliasNormalized"],
    indices = [Index("aliasNormalized")],
)
data class VocabularyAliasEntity(
    val wordId: String,
    val alias: String,
    val aliasNormalized: String,
)

/** Scenario metadata (S001–S060). */
@Entity(
    tableName = "scenarios",
    indices = [
        Index("topic"),
        Index("salesStage"),
        Index("difficulty"),
        Index("active"),
    ],
)
data class ScenarioEntity(
    @PrimaryKey val id: String,
    val title: String,
    val topic: String,
    val salesStage: String,
    val customerRole: String,
    val difficulty: String,
    val projectType: String,
    val estimatedMinutes: Int,
    val description: String?,
    val contentHash: String,
    val active: Boolean = true,
)

/** A raw dialogue turn; id = Snnn_Tnnn. */
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
    val hint: String? = null,       // v3: 对话轮次提示
    val audioAssetId: String?,      // required for CUSTOMER turns
    val contentHash: String,
)

/** One practice unit = one customer turn + the following sales turn; id = Snnn_Pnnn. */
@Entity(
    tableName = "dialogue_pairs",
    indices = [Index("scenarioId", "pairIndex", unique = true)],
)
data class DialoguePairEntity(
    @PrimaryKey val id: String,
    val scenarioId: String,
    val pairIndex: Int,
    val customerTurnId: String,
    val salesTurnId: String,
    val referenceCoreEn: String,
    val referenceChineseHint: String,
    val formalAlternativesJson: String, // JSON array, 2–4 items
    val scoringPointsJson: String = "[]", // JSON array of scoring points (display only)
    val riskNote: String? = null,
    val contentHash: String,
)

/** Keyword link between a practice pair and a vocabulary entry. */
@Entity(
    tableName = "dialogue_pair_words",
    primaryKeys = ["pairId", "wordId"],
    indices = [Index("wordId")],
)
data class DialoguePairWordEntity(
    val pairId: String,
    val wordId: String,
    val sortOrder: Int,
)

/** Local audio asset index; paths resolve only inside the active corpus dir. */
@Entity(tableName = "audio_assets")
data class AudioAssetEntity(
    @PrimaryKey val id: String,
    val kind: String,               // CUSTOMER_TURN / SALES_TURN / WORD / EXAMPLE / PHRASE
    val relativePath: String,
    val sha256: String,
    val mimeType: String,
    val codec: String,
    val durationMs: Long,
    val sizeBytes: Long,
)

// ---------------------------------------------------------------------------
// Room v3 additions (v2 corpus: 短语 / 例句 / 文章).
// ---------------------------------------------------------------------------

/** 常用短语 corpus entry (PHR-nnnn). Learning state in item_memory_states. */
@Entity(
    tableName = "phrases",
    indices = [Index("scene"), Index("category"), Index("active")],
)
data class PhraseEntity(
    @PrimaryKey val id: String,
    val industry: String,
    val scene: String,
    val category: String,
    val textEn: String,
    val textZh: String,
    val linkedTermIdsJson: String,  // JSON array of vocabulary ids
    val sourceType: String,
    val audioAssetId: String,
    val contentHash: String,
    val active: Boolean = true,
)

/** 常用例句 corpus entry (EX-nnnn); the sentence-practice learning unit. */
@Entity(
    tableName = "examples",
    indices = [Index("scene"), Index("speaker"), Index("active")],
)
data class ExampleEntity(
    @PrimaryKey val id: String,
    val industry: String,
    val scene: String,
    val speaker: String,            // CUSTOMER / SALES_ENGINEER
    val textEn: String,
    val textZh: String,
    val linkedTermIdsJson: String,  // JSON array of vocabulary ids
    val dialogueGroupId: String?,
    val sourceType: String,
    val audioAssetId: String,
    val contentHash: String,
    val active: Boolean = true,
)

/** Keyword link between a practice pair and a phrase. */
@Entity(
    tableName = "dialogue_pair_phrases",
    primaryKeys = ["pairId", "phraseId"],
    indices = [Index("phraseId")],
)
data class DialoguePairPhraseEntity(
    val pairId: String,
    val phraseId: String,
    val sortOrder: Int,
)

/** 磨耳朵文章 (bundled or user-imported via .bessarticle). */
@Entity(
    tableName = "articles",
    indices = [Index("topic"), Index("source")],
)
data class ArticleEntity(
    @PrimaryKey val id: String,     // ART-nnnn
    val title: String,
    val titleZh: String,
    val topic: String,
    val paragraphsJson: String,     // JSON array of {textEn, textZh}
    val audioAssetId: String,       // also present in audio_assets (kind=ARTICLE)
    val durationMs: Long,
    val source: String,             // BUNDLED / IMPORTED
    val contentScope: String = "UNSPECIFIED", // BESS / UNSPECIFIED
    val contentHash: String,
    val createdAtEpochMs: Long,
)
