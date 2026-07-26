package com.bess.salestrainer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.VocabularyEntryEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {

    @Query("SELECT * FROM vocabulary_entries WHERE id = :id AND active = 1")
    fun observeById(id: String): Flow<VocabularyEntryEntity?>

    @Query("SELECT * FROM vocabulary_entries WHERE id = :id")
    suspend fun getById(id: String): VocabularyEntryEntity?

    /** Active words with no memory state yet = candidates for "new words". */
    @Query(
        """
        SELECT v.* FROM vocabulary_entries v
        LEFT JOIN word_memory_states m ON m.wordId = v.id
        WHERE v.active = 1 AND m.wordId IS NULL
        ORDER BY v.id
        LIMIT :limit
        """
    )
    fun observeNewWords(limit: Int): Flow<List<VocabularyEntryEntity>>

    /** Due reviews: memory state due and not beyond now. */
    @Query(
        """
        SELECT v.* FROM vocabulary_entries v
        INNER JOIN word_memory_states m ON m.wordId = v.id
        WHERE v.active = 1 AND m.dueAtEpochMs <= :nowEpochMs
        ORDER BY m.dueAtEpochMs ASC
        LIMIT :limit
        """
    )
    fun observeDueReviews(nowEpochMs: Long, limit: Int): Flow<List<VocabularyEntryEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM vocabulary_entries v
        INNER JOIN word_memory_states m ON m.wordId = v.id
        WHERE v.active = 1 AND m.dueAtEpochMs <= :nowEpochMs
        """
    )
    fun observeDueCount(nowEpochMs: Long): Flow<Int>

    @Query("SELECT * FROM vocabulary_entries WHERE active = 1 ORDER BY term")
    fun observeAllActive(): Flow<List<VocabularyEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<VocabularyEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: VocabularyEntryEntity)

    @Query("UPDATE vocabulary_entries SET active = 0 WHERE id IN (:ids)")
    suspend fun deactivateByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM vocabulary_entries WHERE active = 1")
    suspend fun activeCount(): Int

    // ---- memory state ----

    @Query("SELECT * FROM word_memory_states WHERE wordId = :wordId")
    fun observeMemoryState(wordId: String): Flow<WordMemoryStateEntity?>

    @Query("SELECT * FROM word_memory_states WHERE wordId = :wordId")
    suspend fun getMemoryState(wordId: String): WordMemoryStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemoryState(state: WordMemoryStateEntity)

    @Query("SELECT * FROM word_memory_states")
    fun observeAllMemoryStates(): Flow<List<WordMemoryStateEntity>>

    // ---- review log (append-only) ----

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReviewLog(log: ReviewLogEntity)

    @Query("SELECT COUNT(*) FROM review_logs WHERE wordId = :wordId")
    suspend fun reviewLogCountForWord(wordId: String): Int

    @Query("SELECT * FROM review_logs WHERE reviewedAtEpochMs >= :sinceEpochMs")
    fun observeReviewLogsSince(sinceEpochMs: Long): Flow<List<ReviewLogEntity>>
}
