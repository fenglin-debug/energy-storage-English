package com.bess.salestrainer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bess.salestrainer.core.database.dao.ArticleDao
import com.bess.salestrainer.core.database.dao.CorpusDao
import com.bess.salestrainer.core.database.dao.ExampleDao
import com.bess.salestrainer.core.database.dao.ItemMemoryDao
import com.bess.salestrainer.core.database.dao.LearningStateDao
import com.bess.salestrainer.core.database.dao.PhraseDao
import com.bess.salestrainer.core.database.dao.ScenarioDao
import com.bess.salestrainer.core.database.dao.StudyTaskDao
import com.bess.salestrainer.core.database.dao.VocabularyDao
import com.bess.salestrainer.core.database.entity.ActiveCorpusEntity
import com.bess.salestrainer.core.database.entity.ArticleEntity
import com.bess.salestrainer.core.database.entity.ArticleProgressEntity
import com.bess.salestrainer.core.database.entity.AudioAssetEntity
import com.bess.salestrainer.core.database.entity.CorpusImportEventEntity
import com.bess.salestrainer.core.database.entity.CorpusVersionEntity
import com.bess.salestrainer.core.database.entity.DialoguePairEntity
import com.bess.salestrainer.core.database.entity.DialoguePairPhraseEntity
import com.bess.salestrainer.core.database.entity.DialoguePairWordEntity
import com.bess.salestrainer.core.database.entity.DialogueTurnEntity
import com.bess.salestrainer.core.database.entity.ExampleEntity
import com.bess.salestrainer.core.database.entity.ItemMemoryStateEntity
import com.bess.salestrainer.core.database.entity.PhraseEntity
import com.bess.salestrainer.core.database.entity.ReviewActionKeyEntity
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.ScenarioEntity
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.ScenarioTurnProgressEntity
import com.bess.salestrainer.core.database.entity.StudyTaskEntity
import com.bess.salestrainer.core.database.entity.VocabularyAliasEntity
import com.bess.salestrainer.core.database.entity.VocabularyEntryEntity
import com.bess.salestrainer.core.database.entity.VocabularySessionCheckpointEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity

@Database(
    entities = [
        // content (active corpus)
        CorpusVersionEntity::class,
        ActiveCorpusEntity::class,
        VocabularyEntryEntity::class,
        VocabularyAliasEntity::class,
        ScenarioEntity::class,
        DialogueTurnEntity::class,
        DialoguePairEntity::class,
        DialoguePairWordEntity::class,
        AudioAssetEntity::class,
        // v3 content
        PhraseEntity::class,
        ExampleEntity::class,
        DialoguePairPhraseEntity::class,
        ArticleEntity::class,
        // user state
        WordMemoryStateEntity::class,
        ReviewLogEntity::class,
        VocabularySessionCheckpointEntity::class,
        ReviewActionKeyEntity::class,
        ScenarioSessionEntity::class,
        ScenarioTurnProgressEntity::class,
        StudyTaskEntity::class,
        CorpusImportEventEntity::class,
        // v3 user state
        ItemMemoryStateEntity::class,
        ArticleProgressEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class BessDatabase : RoomDatabase() {
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun scenarioDao(): ScenarioDao
    abstract fun studyTaskDao(): StudyTaskDao
    abstract fun corpusDao(): CorpusDao
    abstract fun phraseDao(): PhraseDao
    abstract fun exampleDao(): ExampleDao
    abstract fun articleDao(): ArticleDao
    abstract fun itemMemoryDao(): ItemMemoryDao
    abstract fun learningStateDao(): LearningStateDao

    companion object {
        const val NAME = "bess.db"

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `vocabulary_session_checkpoints` " +
                        "ADD COLUMN `assessmentSubmitted` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `vocabulary_session_checkpoints` " +
                        "ADD COLUMN `selectedAssessment` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `item_memory_states` " +
                        "ADD COLUMN `masteredUi` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `scenario_sessions` " +
                        "ADD COLUMN `practiceMode` TEXT NOT NULL DEFAULT 'SCENARIO'",
                )
                db.execSQL(
                    "ALTER TABLE `scenario_sessions` " +
                        "ADD COLUMN `queuePairIdsJson` TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL(
                    "ALTER TABLE `articles` " +
                        "ADD COLUMN `contentScope` TEXT NOT NULL DEFAULT 'UNSPECIFIED'",
                )
                db.execSQL(
                    "UPDATE `word_memory_states` SET `dueAtEpochMs` = 9223372036854775807 " +
                        "WHERE `masteredUi` = 1",
                )
            }
        }

        /**
         * v2 -> v3: purely additive (no destructive change):
         *  - new content tables: phrases / examples / dialogue_pair_phrases / articles
         *  - new user-state tables: item_memory_states / article_progress
         *  - dialogue_turns gains nullable `hint`; dialogue_pairs gains
         *    `scoringPointsJson` (default '[]') and nullable `riskNote`
         * Existing rows are untouched.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---------- new content tables ----------
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `phrases` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `industry` TEXT NOT NULL,
                        `scene` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `textEn` TEXT NOT NULL,
                        `textZh` TEXT NOT NULL,
                        `linkedTermIdsJson` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `audioAssetId` TEXT NOT NULL,
                        `contentHash` TEXT NOT NULL,
                        `active` INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_phrases_scene` ON `phrases` (`scene`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_phrases_category` ON `phrases` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_phrases_active` ON `phrases` (`active`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `examples` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `industry` TEXT NOT NULL,
                        `scene` TEXT NOT NULL,
                        `speaker` TEXT NOT NULL,
                        `textEn` TEXT NOT NULL,
                        `textZh` TEXT NOT NULL,
                        `linkedTermIdsJson` TEXT NOT NULL,
                        `dialogueGroupId` TEXT,
                        `sourceType` TEXT NOT NULL,
                        `audioAssetId` TEXT NOT NULL,
                        `contentHash` TEXT NOT NULL,
                        `active` INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_examples_scene` ON `examples` (`scene`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_examples_speaker` ON `examples` (`speaker`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_examples_active` ON `examples` (`active`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dialogue_pair_phrases` (
                        `pairId` TEXT NOT NULL,
                        `phraseId` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`pairId`, `phraseId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dialogue_pair_phrases_phraseId` ON `dialogue_pair_phrases` (`phraseId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `articles` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `titleZh` TEXT NOT NULL,
                        `topic` TEXT NOT NULL,
                        `paragraphsJson` TEXT NOT NULL,
                        `audioAssetId` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `contentHash` TEXT NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_topic` ON `articles` (`topic`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_source` ON `articles` (`source`)")

                // ---------- new user-state tables ----------
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `item_memory_states` (
                        `itemId` TEXT NOT NULL,
                        `itemType` TEXT NOT NULL,
                        `fsrsState` TEXT NOT NULL,
                        `difficulty` REAL NOT NULL,
                        `stability` REAL NOT NULL,
                        `dueAtEpochMs` INTEGER NOT NULL,
                        `lastReviewAtEpochMs` INTEGER,
                        `reps` INTEGER NOT NULL,
                        `lapses` INTEGER NOT NULL,
                        `learnedContentHash` TEXT,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`itemId`, `itemType`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_memory_states_dueAtEpochMs` ON `item_memory_states` (`dueAtEpochMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_memory_states_itemType` ON `item_memory_states` (`itemType`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `article_progress` (
                        `articleId` TEXT NOT NULL PRIMARY KEY,
                        `lastPositionMs` INTEGER NOT NULL,
                        `listenCount` INTEGER NOT NULL,
                        `completedAtEpochMs` INTEGER,
                        `updatedAtEpochMs` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )

                // ---------- additive columns on existing tables ----------
                db.execSQL("ALTER TABLE `dialogue_turns` ADD COLUMN `hint` TEXT")
                db.execSQL("ALTER TABLE `dialogue_pairs` ADD COLUMN `scoringPointsJson` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `dialogue_pairs` ADD COLUMN `riskNote` TEXT")
            }
        }

        /**
         * v1 -> v2 (TDD §5.4). Non-destructive for learning state:
         *  - word_memory_states / review_logs / study_tasks are copied with
         *    compatible columns; word_memory_states gains legacyNormalizedTerm
         *    (used once by the first bundled-corpus import, then dropped).
         *  - v1 scenario sessions become minimal ABORTED_LEGACY rows; no
         *    transcript, AI advice, score or audio reference is carried over.
         *  - turn_attempts / ai_advices / safe_facts / conflict_guards and all
         *    v1 content tables are dropped; content arrives via .besspack import.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---------- user state: preserved ----------
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `word_memory_states_new` (
                        `wordId` TEXT NOT NULL PRIMARY KEY,
                        `fsrsState` TEXT NOT NULL,
                        `difficulty` REAL NOT NULL,
                        `stability` REAL NOT NULL,
                        `dueAtEpochMs` INTEGER NOT NULL,
                        `lastReviewAtEpochMs` INTEGER,
                        `reps` INTEGER NOT NULL,
                        `lapses` INTEGER NOT NULL,
                        `masteredUi` INTEGER NOT NULL,
                        `lastQuestionMode` TEXT,
                        `isFavorite` INTEGER NOT NULL,
                        `learnedContentHash` TEXT,
                        `legacyNormalizedTerm` TEXT,
                        `updatedAtEpochMs` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `word_memory_states_new` (
                        `wordId`, `fsrsState`, `difficulty`, `stability`, `dueAtEpochMs`,
                        `lastReviewAtEpochMs`, `reps`, `lapses`, `masteredUi`,
                        `lastQuestionMode`, `isFavorite`, `learnedContentHash`,
                        `legacyNormalizedTerm`, `updatedAtEpochMs`
                    )
                    SELECT
                        m.`wordId`, m.`fsrsState`, m.`difficulty`, m.`stability`, m.`dueAtEpochMs`,
                        m.`lastReviewAtEpochMs`, m.`reps`, m.`lapses`, m.`masteredUi`,
                        m.`lastQuestionMode`, m.`isFavorite`, NULL,
                        v.`normalizedTerm`, m.`updatedAtEpochMs`
                    FROM `word_memory_states` m
                    LEFT JOIN `vocabulary_entries` v ON v.`id` = m.`wordId`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `word_memory_states`")
                db.execSQL("ALTER TABLE `word_memory_states_new` RENAME TO `word_memory_states`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_word_memory_states_dueAtEpochMs` ON `word_memory_states` (`dueAtEpochMs`)")

                // review_logs / study_tasks keep identical shape in v2.

                // ---------- scenario sessions: minimal ABORTED_LEGACY rows ----------
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scenario_sessions_new` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `scenarioId` TEXT NOT NULL,
                        `scenarioContentHash` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `currentPairId` TEXT,
                        `currentPairIndex` INTEGER NOT NULL,
                        `pairCount` INTEGER NOT NULL,
                        `startedAtEpochMs` INTEGER NOT NULL,
                        `completedAtEpochMs` INTEGER,
                        `updatedAtEpochMs` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `scenario_sessions_new` (
                        `id`, `scenarioId`, `scenarioContentHash`, `status`,
                        `currentPairId`, `currentPairIndex`, `pairCount`,
                        `startedAtEpochMs`, `completedAtEpochMs`, `updatedAtEpochMs`
                    )
                    SELECT
                        `id`, `scenarioId`, 'legacy', 'ABORTED_LEGACY',
                        NULL, 0, 0,
                        `startedAtEpochMs`, `completedAtEpochMs`, `updatedAtEpochMs`
                    FROM `scenario_sessions`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `scenario_sessions`")
                db.execSQL("ALTER TABLE `scenario_sessions_new` RENAME TO `scenario_sessions`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scenario_sessions_scenarioId` ON `scenario_sessions` (`scenarioId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scenario_sessions_status` ON `scenario_sessions` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scenario_sessions_updatedAtEpochMs` ON `scenario_sessions` (`updatedAtEpochMs`)")

                // ---------- v1 tables removed entirely ----------
                db.execSQL("DROP TABLE IF EXISTS `turn_attempts`")
                db.execSQL("DROP TABLE IF EXISTS `ai_advices`")
                db.execSQL("DROP TABLE IF EXISTS `safe_facts`")
                db.execSQL("DROP TABLE IF EXISTS `conflict_guards`")
                db.execSQL("DROP TABLE IF EXISTS `scenario_training_packs`")
                db.execSQL("DROP TABLE IF EXISTS `vocabulary_entries`")
                db.execSQL("DROP TABLE IF EXISTS `scenarios`")
                db.execSQL("DROP TABLE IF EXISTS `dialogue_turns`")
                db.execSQL("DROP TABLE IF EXISTS `corpus_versions`")

                // ---------- v2 content tables (empty until first import) ----------
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `corpus_versions` (
                        `packageKey` TEXT NOT NULL PRIMARY KEY,
                        `packageId` TEXT NOT NULL,
                        `schemaVersion` INTEGER NOT NULL,
                        `contentVersion` TEXT NOT NULL,
                        `vocabularyCount` INTEGER NOT NULL,
                        `scenarioCount` INTEGER NOT NULL,
                        `dialogueTurnCount` INTEGER NOT NULL,
                        `dialoguePairCount` INTEGER NOT NULL,
                        `audioAssetCount` INTEGER NOT NULL,
                        `manifestSha256` TEXT NOT NULL,
                        `isBundled` INTEGER NOT NULL,
                        `importedAtEpochMs` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `active_corpus` (
                        `id` INTEGER NOT NULL PRIMARY KEY,
                        `packageKey` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vocabulary_entries` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `term` TEXT NOT NULL,
                        `normalizedTerm` TEXT NOT NULL,
                        `ipa` TEXT NOT NULL,
                        `partOfSpeech` TEXT NOT NULL,
                        `chineseGloss` TEXT NOT NULL,
                        `englishDefinition` TEXT,
                        `collocationsJson` TEXT NOT NULL,
                        `exampleSentenceEn` TEXT NOT NULL,
                        `exampleSentenceZh` TEXT,
                        `commonMistakes` TEXT NOT NULL,
                        `topic` TEXT NOT NULL,
                        `scenarioTagsJson` TEXT NOT NULL,
                        `cefrLevel` TEXT NOT NULL,
                        `wordAudioAssetId` TEXT NOT NULL,
                        `exampleAudioAssetId` TEXT NOT NULL,
                        `contentSource` TEXT NOT NULL,
                        `contentHash` TEXT NOT NULL,
                        `active` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vocabulary_entries_normalizedTerm` ON `vocabulary_entries` (`normalizedTerm`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_entries_topic` ON `vocabulary_entries` (`topic`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_entries_cefrLevel` ON `vocabulary_entries` (`cefrLevel`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_entries_active` ON `vocabulary_entries` (`active`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vocabulary_aliases` (
                        `wordId` TEXT NOT NULL,
                        `alias` TEXT NOT NULL,
                        `aliasNormalized` TEXT NOT NULL,
                        PRIMARY KEY(`wordId`, `aliasNormalized`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_aliases_aliasNormalized` ON `vocabulary_aliases` (`aliasNormalized`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scenarios` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `topic` TEXT NOT NULL,
                        `salesStage` TEXT NOT NULL,
                        `customerRole` TEXT NOT NULL,
                        `difficulty` TEXT NOT NULL,
                        `projectType` TEXT NOT NULL,
                        `estimatedMinutes` INTEGER NOT NULL,
                        `description` TEXT,
                        `contentHash` TEXT NOT NULL,
                        `active` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scenarios_topic` ON `scenarios` (`topic`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scenarios_salesStage` ON `scenarios` (`salesStage`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scenarios_difficulty` ON `scenarios` (`difficulty`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scenarios_active` ON `scenarios` (`active`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dialogue_turns` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `scenarioId` TEXT NOT NULL,
                        `turnNo` INTEGER NOT NULL,
                        `speaker` TEXT NOT NULL,
                        `textEn` TEXT NOT NULL,
                        `textZh` TEXT,
                        `audioAssetId` TEXT,
                        `contentHash` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_dialogue_turns_scenarioId_turnNo` ON `dialogue_turns` (`scenarioId`, `turnNo`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dialogue_pairs` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `scenarioId` TEXT NOT NULL,
                        `pairIndex` INTEGER NOT NULL,
                        `customerTurnId` TEXT NOT NULL,
                        `salesTurnId` TEXT NOT NULL,
                        `referenceCoreEn` TEXT NOT NULL,
                        `referenceChineseHint` TEXT NOT NULL,
                        `formalAlternativesJson` TEXT NOT NULL,
                        `contentHash` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_dialogue_pairs_scenarioId_pairIndex` ON `dialogue_pairs` (`scenarioId`, `pairIndex`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dialogue_pair_words` (
                        `pairId` TEXT NOT NULL,
                        `wordId` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`pairId`, `wordId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dialogue_pair_words_wordId` ON `dialogue_pair_words` (`wordId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `audio_assets` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `kind` TEXT NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `codec` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )

                // ---------- v2 user-state tables (new) ----------
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vocabulary_session_checkpoints` (
                        `sessionId` TEXT NOT NULL PRIMARY KEY,
                        `status` TEXT NOT NULL,
                        `corpusVersion` TEXT NOT NULL,
                        `queueWordIdsJson` TEXT NOT NULL,
                        `currentIndex` INTEGER NOT NULL,
                        `questionMode` TEXT NOT NULL,
                        `answerRevealed` INTEGER NOT NULL,
                        `hintRevealed` INTEGER NOT NULL,
                        `startedAtEpochMs` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_session_checkpoints_status` ON `vocabulary_session_checkpoints` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_session_checkpoints_updatedAtEpochMs` ON `vocabulary_session_checkpoints` (`updatedAtEpochMs`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `review_action_keys` (
                        `actionKey` TEXT NOT NULL PRIMARY KEY,
                        `sessionId` TEXT NOT NULL,
                        `currentIndex` INTEGER NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scenario_turn_progress` (
                        `sessionId` TEXT NOT NULL,
                        `pairId` TEXT NOT NULL,
                        `customerAudioCompleted` INTEGER NOT NULL,
                        `customerTextRevealed` INTEGER NOT NULL,
                        `keywordsRevealed` INTEGER NOT NULL,
                        `answerRevealed` INTEGER NOT NULL,
                        `selfRating` TEXT,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`, `pairId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `corpus_import_events` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `previewId` TEXT,
                        `packageKey` TEXT,
                        `resultCode` TEXT NOT NULL,
                        `addedCount` INTEGER NOT NULL,
                        `updatedCount` INTEGER NOT NULL,
                        `removedCount` INTEGER NOT NULL,
                        `unchangedCount` INTEGER NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
