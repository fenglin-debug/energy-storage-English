package com.bess.salestrainer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bess.salestrainer.core.database.entity.AiAdviceEntity
import com.bess.salestrainer.core.database.entity.ConflictGuardEntity
import com.bess.salestrainer.core.database.entity.CorpusVersionEntity
import com.bess.salestrainer.core.database.entity.SafeFactEntity
import com.bess.salestrainer.core.database.entity.StudyTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyTaskDao {
    @Query("SELECT * FROM study_tasks WHERE dateEpochDay = :dateEpochDay")
    fun observeByDate(dateEpochDay: Long): Flow<StudyTaskEntity?>

    @Query("SELECT * FROM study_tasks WHERE dateEpochDay = :dateEpochDay")
    suspend fun getByDate(dateEpochDay: Long): StudyTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: StudyTaskEntity)

    @Query("SELECT * FROM study_tasks WHERE dateEpochDay >= :sinceEpochDay ORDER BY dateEpochDay")
    suspend fun getSince(sinceEpochDay: Long): List<StudyTaskEntity>
}

@Dao
interface CorpusDao {
    @Query("SELECT * FROM corpus_versions WHERE active = 1 LIMIT 1")
    fun observeActive(): Flow<CorpusVersionEntity?>

    @Query("SELECT * FROM corpus_versions WHERE active = 1 LIMIT 1")
    suspend fun getActive(): CorpusVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(version: CorpusVersionEntity)

    @Query("UPDATE corpus_versions SET active = 0")
    suspend fun deactivateAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSafeFacts(facts: List<SafeFactEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConflictGuards(guards: List<ConflictGuardEntity>)

    @Query("SELECT * FROM safe_facts")
    suspend fun getAllSafeFacts(): List<SafeFactEntity>

    @Query("SELECT * FROM conflict_guards")
    suspend fun getAllConflictGuards(): List<ConflictGuardEntity>

    @Query("SELECT COUNT(*) FROM vocabulary_entries")
    suspend fun totalVocabularyCount(): Int
}

@Dao
interface AiAdviceDao {
    @Query("SELECT * FROM ai_advices WHERE id = :id")
    fun observeById(id: String): Flow<AiAdviceEntity?>

    @Query("SELECT * FROM ai_advices WHERE id = :id")
    suspend fun getById(id: String): AiAdviceEntity?

    @Query("SELECT * FROM ai_advices WHERE evaluationRequestId = :requestId")
    suspend fun getByRequestId(requestId: String): AiAdviceEntity?

    @Query("SELECT * FROM ai_advices WHERE sessionId = :sessionId ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun getLatestForSession(sessionId: String): AiAdviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(advice: AiAdviceEntity)

    @Query("SELECT * FROM ai_advices WHERE isFavorite = 1 ORDER BY createdAtEpochMs DESC")
    fun observeFavorites(): Flow<List<AiAdviceEntity>>
}
