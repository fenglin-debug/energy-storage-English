package com.bess.salestrainer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bess.salestrainer.core.database.entity.ArticleProgressEntity
import com.bess.salestrainer.core.database.entity.ItemMemoryStateEntity
import com.bess.salestrainer.core.database.entity.ReviewActionKeyEntity
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.ScenarioTurnProgressEntity
import com.bess.salestrainer.core.database.entity.StudyTaskEntity
import com.bess.salestrainer.core.database.entity.VocabularySessionCheckpointEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity

/** Complete, content-free access surface used only by local backup/restore. */
@Dao
interface LearningStateDao {
    @Query("SELECT * FROM word_memory_states ORDER BY wordId")
    suspend fun wordMemoryStates(): List<WordMemoryStateEntity>

    @Query("SELECT * FROM review_logs ORDER BY reviewedAtEpochMs, id")
    suspend fun reviewLogs(): List<ReviewLogEntity>

    @Query("SELECT * FROM vocabulary_session_checkpoints ORDER BY startedAtEpochMs, sessionId")
    suspend fun vocabularyCheckpoints(): List<VocabularySessionCheckpointEntity>

    @Query("SELECT * FROM review_action_keys ORDER BY createdAtEpochMs, actionKey")
    suspend fun reviewActionKeys(): List<ReviewActionKeyEntity>

    @Query("SELECT * FROM scenario_sessions ORDER BY startedAtEpochMs, id")
    suspend fun scenarioSessions(): List<ScenarioSessionEntity>

    @Query("SELECT * FROM scenario_turn_progress ORDER BY sessionId, pairId")
    suspend fun scenarioTurnProgress(): List<ScenarioTurnProgressEntity>

    @Query("SELECT * FROM study_tasks ORDER BY dateEpochDay")
    suspend fun studyTasks(): List<StudyTaskEntity>

    @Query("SELECT * FROM item_memory_states ORDER BY itemType, itemId")
    suspend fun itemMemoryStates(): List<ItemMemoryStateEntity>

    @Query("SELECT * FROM article_progress ORDER BY articleId")
    suspend fun articleProgress(): List<ArticleProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWordMemoryStates(rows: List<WordMemoryStateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReviewLogs(rows: List<ReviewLogEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVocabularyCheckpoints(rows: List<VocabularySessionCheckpointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReviewActionKeys(rows: List<ReviewActionKeyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScenarioSessions(rows: List<ScenarioSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScenarioTurnProgress(rows: List<ScenarioTurnProgressEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudyTasks(rows: List<StudyTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItemMemoryStates(rows: List<ItemMemoryStateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArticleProgress(rows: List<ArticleProgressEntity>)

    @Query("DELETE FROM review_action_keys")
    suspend fun deleteReviewActionKeys()

    @Query("DELETE FROM scenario_turn_progress")
    suspend fun deleteScenarioTurnProgress()

    @Query("DELETE FROM vocabulary_session_checkpoints")
    suspend fun deleteVocabularyCheckpoints()

    @Query("DELETE FROM scenario_sessions")
    suspend fun deleteScenarioSessions()

    @Query("DELETE FROM review_logs")
    suspend fun deleteReviewLogs()

    @Query("DELETE FROM word_memory_states")
    suspend fun deleteWordMemoryStates()

    @Query("DELETE FROM study_tasks")
    suspend fun deleteStudyTasks()

    @Query("DELETE FROM item_memory_states")
    suspend fun deleteItemMemoryStates()

    @Query("DELETE FROM article_progress")
    suspend fun deleteArticleProgress()
}
