package com.bess.salestrainer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bess.salestrainer.core.database.entity.ReviewActionKeyEntity
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.VocabularyAliasEntity
import com.bess.salestrainer.core.database.entity.VocabularyEntryEntity
import com.bess.salestrainer.core.database.entity.VocabularySessionCheckpointEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {

    // ---- entries ----

    @Query("SELECT * FROM vocabulary_entries WHERE id = :id AND active = 1")
    fun observeById(id: String): Flow<VocabularyEntryEntity?>

    @Query("SELECT * FROM vocabulary_entries WHERE id = :id")
    suspend fun getById(id: String): VocabularyEntryEntity?

    @Query("SELECT * FROM vocabulary_entries WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<VocabularyEntryEntity>

    /** Active words with no memory state yet = candidates for "new words". */
    @Query(
        """
        SELECT v.* FROM vocabulary_entries v
        LEFT JOIN word_memory_states m ON m.wordId = v.id
        WHERE v.active = 1 AND (
            m.wordId IS NULL OR (m.reps = 0 AND m.masteredUi = 0)
        )
        ORDER BY v.id
        LIMIT :limit
        """
    )
    suspend fun getNewWords(limit: Int): List<VocabularyEntryEntity>

    /** All due reviews, ordered by due time; no truncation cap. */
    @Query(
        """
        SELECT v.* FROM vocabulary_entries v
        INNER JOIN word_memory_states m ON m.wordId = v.id
        WHERE v.active = 1 AND m.masteredUi = 0 AND m.reps > 0
            AND m.dueAtEpochMs <= :nowEpochMs
        ORDER BY m.dueAtEpochMs ASC, v.id ASC
        """
    )
    suspend fun getDueReviews(nowEpochMs: Long): List<VocabularyEntryEntity>

    @Query(
        """
        SELECT COUNT(*) FROM vocabulary_entries v
        INNER JOIN word_memory_states m ON m.wordId = v.id
        WHERE v.active = 1 AND m.masteredUi = 0 AND m.reps > 0
            AND m.dueAtEpochMs <= :nowEpochMs
        """
    )
    fun observeDueCount(nowEpochMs: Long): Flow<Int>

    @Query("SELECT * FROM vocabulary_entries WHERE active = 1 ORDER BY term")
    fun observeAllActive(): Flow<List<VocabularyEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<VocabularyEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: VocabularyEntryEntity)

    @Query("DELETE FROM vocabulary_entries")
    suspend fun deleteAllEntries()

    @Query("SELECT COUNT(*) FROM vocabulary_entries WHERE active = 1")
    suspend fun activeCount(): Int

    // ---- aliases ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAliases(aliases: List<VocabularyAliasEntity>)

    @Query("SELECT * FROM vocabulary_aliases WHERE wordId = :wordId")
    suspend fun getAliases(wordId: String): List<VocabularyAliasEntity>

    @Query("SELECT * FROM vocabulary_aliases")
    suspend fun getAllAliases(): List<VocabularyAliasEntity>

    @Query("DELETE FROM vocabulary_aliases")
    suspend fun deleteAllAliases()

    // ---- memory state ----

    @Query("SELECT * FROM word_memory_states WHERE wordId = :wordId")
    fun observeMemoryState(wordId: String): Flow<WordMemoryStateEntity?>

    @Query("SELECT * FROM word_memory_states WHERE wordId = :wordId")
    suspend fun getMemoryState(wordId: String): WordMemoryStateEntity?

    @Query("SELECT * FROM word_memory_states WHERE wordId IN (:wordIds)")
    suspend fun getMemoryStates(wordIds: List<String>): List<WordMemoryStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemoryState(state: WordMemoryStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemoryStates(states: List<WordMemoryStateEntity>)

    @Query("SELECT * FROM word_memory_states")
    fun observeAllMemoryStates(): Flow<List<WordMemoryStateEntity>>

    @Query("UPDATE word_memory_states SET isFavorite = :favorite, updatedAtEpochMs = :nowEpochMs WHERE wordId = :wordId")
    suspend fun setFavorite(wordId: String, favorite: Boolean, nowEpochMs: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemoryStateIgnore(state: WordMemoryStateEntity): Long

    // ---- review log (append-only) ----

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReviewLog(log: ReviewLogEntity)

    @Query("SELECT COUNT(*) FROM review_logs WHERE wordId = :wordId")
    suspend fun reviewLogCountForWord(wordId: String): Int

    @Query("SELECT * FROM review_logs WHERE reviewedAtEpochMs >= :sinceEpochMs")
    fun observeReviewLogsSince(sinceEpochMs: Long): Flow<List<ReviewLogEntity>>

    // ---- review action idempotency ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActionKey(key: ReviewActionKeyEntity): Long

    // ---- session checkpoints ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: VocabularySessionCheckpointEntity)

    @Query("SELECT * FROM vocabulary_session_checkpoints WHERE sessionId = :sessionId")
    fun observeCheckpoint(sessionId: String): Flow<VocabularySessionCheckpointEntity?>

    @Query("SELECT * FROM vocabulary_session_checkpoints WHERE sessionId = :sessionId")
    suspend fun getCheckpoint(sessionId: String): VocabularySessionCheckpointEntity?

    @Query(
        """
        SELECT * FROM vocabulary_session_checkpoints
        WHERE status = 'IN_PROGRESS'
        ORDER BY updatedAtEpochMs DESC
        LIMIT 1
        """
    )
    fun observeLatestInProgressCheckpoint(): Flow<VocabularySessionCheckpointEntity?>

    @Query(
        """
        SELECT * FROM vocabulary_session_checkpoints
        WHERE status = 'IN_PROGRESS'
        ORDER BY updatedAtEpochMs DESC
        LIMIT 1
        """
    )
    suspend fun getLatestInProgressCheckpoint(): VocabularySessionCheckpointEntity?

    @Query(
        "UPDATE vocabulary_session_checkpoints SET status = 'EXPIRED', updatedAtEpochMs = :nowEpochMs WHERE status = 'IN_PROGRESS'"
    )
    suspend fun expireAllInProgressCheckpoints(nowEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM vocabulary_session_checkpoints WHERE status = 'IN_PROGRESS'")
    suspend fun inProgressCheckpointCount(): Int

    @Query("SELECT id, contentHash FROM vocabulary_entries")
    suspend fun getAllHashRows(): List<WordHashRow>
}

/** Lightweight projection for import diffing. */
data class WordHashRow(
    val id: String,
    val contentHash: String,
)

suspend fun VocabularyDao.getAllHashes(): Map<String, String> =
    getAllHashRows().associate { it.id to it.contentHash }
