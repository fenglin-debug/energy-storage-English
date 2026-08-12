package com.bess.salestrainer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bess.salestrainer.core.database.entity.PhraseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhraseDao {

    @Query("SELECT * FROM phrases WHERE id = :id AND active = 1")
    fun observeById(id: String): Flow<PhraseEntity?>

    @Query("SELECT * FROM phrases WHERE id = :id")
    suspend fun getById(id: String): PhraseEntity?

    @Query("SELECT * FROM phrases WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<PhraseEntity>

    @Query(
        """
        SELECT p.* FROM phrases p
        LEFT JOIN item_memory_states m ON m.itemId = p.id AND m.itemType = 'PHRASE'
        WHERE p.active = 1 AND m.itemId IS NULL
        ORDER BY p.id
        LIMIT :limit
        """,
    )
    suspend fun getNewPhrases(limit: Int): List<PhraseEntity>

    @Query(
        """
        SELECT p.* FROM phrases p
        INNER JOIN item_memory_states m ON m.itemId = p.id AND m.itemType = 'PHRASE'
        WHERE p.active = 1 AND m.masteredUi = 0 AND m.dueAtEpochMs <= :nowEpochMs
        ORDER BY m.dueAtEpochMs ASC, p.id ASC
        """,
    )
    suspend fun getDueReviews(nowEpochMs: Long): List<PhraseEntity>

    @Query("SELECT * FROM phrases WHERE active = 1 ORDER BY scene, id")
    fun observeAllActive(): Flow<List<PhraseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<PhraseEntity>)

    @Query("DELETE FROM phrases")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM phrases WHERE active = 1")
    suspend fun activeCount(): Int

    @Query("SELECT id, contentHash FROM phrases")
    suspend fun getAllHashRows(): List<PhraseHashRow>
}

data class PhraseHashRow(
    val id: String,
    val contentHash: String,
)
