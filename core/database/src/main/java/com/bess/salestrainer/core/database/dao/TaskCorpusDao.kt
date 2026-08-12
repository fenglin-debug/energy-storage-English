package com.bess.salestrainer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bess.salestrainer.core.database.entity.ActiveCorpusEntity
import com.bess.salestrainer.core.database.entity.AudioAssetEntity
import com.bess.salestrainer.core.database.entity.CorpusImportEventEntity
import com.bess.salestrainer.core.database.entity.CorpusVersionEntity
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

    // ---- versions ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVersion(version: CorpusVersionEntity)

    @Query("SELECT * FROM corpus_versions WHERE packageKey = :packageKey")
    suspend fun getVersion(packageKey: String): CorpusVersionEntity?

    // ---- active pointer ----

    @Query("SELECT v.* FROM corpus_versions v INNER JOIN active_corpus a ON a.packageKey = v.packageKey WHERE a.id = 1")
    fun observeActive(): Flow<CorpusVersionEntity?>

    @Query("SELECT v.* FROM corpus_versions v INNER JOIN active_corpus a ON a.packageKey = v.packageKey WHERE a.id = 1")
    suspend fun getActive(): CorpusVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setActive(pointer: ActiveCorpusEntity)

    @Query("DELETE FROM active_corpus")
    suspend fun clearActive()

    // ---- audio assets ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAudioAssets(assets: List<AudioAssetEntity>)

    @Query("SELECT * FROM audio_assets WHERE id = :id")
    suspend fun getAudioAsset(id: String): AudioAssetEntity?

    @Query("SELECT COUNT(*) FROM audio_assets")
    suspend fun audioAssetCount(): Int

    @Query("DELETE FROM audio_assets")
    suspend fun deleteAllAudioAssets()

    @Query("DELETE FROM audio_assets WHERE kind != 'ARTICLE'")
    suspend fun deleteCorpusAudioAssets()

    @Query("DELETE FROM audio_assets WHERE kind = 'ARTICLE' AND id IN (:assetIds)")
    suspend fun deleteArticleAudioAssets(assetIds: List<String>)

    // ---- import events (audit, no paths/content) ----

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertImportEvent(event: CorpusImportEventEntity)

    @Query("SELECT * FROM corpus_import_events ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun observeRecentImportEvents(limit: Int): Flow<List<CorpusImportEventEntity>>

    @Query("SELECT COUNT(*) FROM vocabulary_entries")
    suspend fun totalVocabularyCount(): Int
}
