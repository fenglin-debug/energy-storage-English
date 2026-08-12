package com.bess.salestrainer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bess.salestrainer.core.database.entity.ExampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExampleDao {

    @Query("SELECT * FROM examples WHERE id = :id AND active = 1")
    fun observeById(id: String): Flow<ExampleEntity?>

    @Query("SELECT * FROM examples WHERE id = :id")
    suspend fun getById(id: String): ExampleEntity?

    @Query("SELECT * FROM examples WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ExampleEntity>

    @Query(
        """
        SELECT e.* FROM examples e
        LEFT JOIN item_memory_states m ON m.itemId = e.id AND m.itemType = 'EXAMPLE'
        WHERE e.active = 1 AND m.itemId IS NULL
        ORDER BY e.id
        LIMIT :limit
        """,
    )
    suspend fun getNewExamples(limit: Int): List<ExampleEntity>

    @Query(
        """
        SELECT e.* FROM examples e
        INNER JOIN item_memory_states m ON m.itemId = e.id AND m.itemType = 'EXAMPLE'
        WHERE e.active = 1 AND m.dueAtEpochMs <= :nowEpochMs
        ORDER BY m.dueAtEpochMs ASC, e.id ASC
        """,
    )
    suspend fun getDueReviews(nowEpochMs: Long): List<ExampleEntity>

    @Query(
        """
        SELECT COUNT(*) FROM examples e
        INNER JOIN item_memory_states m ON m.itemId = e.id AND m.itemType = 'EXAMPLE'
        WHERE e.active = 1 AND m.dueAtEpochMs <= :nowEpochMs
        """,
    )
    fun observeDueCount(nowEpochMs: Long): Flow<Int>

    @Query("SELECT * FROM examples WHERE active = 1 ORDER BY scene, id")
    fun observeAllActive(): Flow<List<ExampleEntity>>

    @Query("SELECT * FROM examples WHERE active = 1 ORDER BY id")
    suspend fun getAllActive(): List<ExampleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<ExampleEntity>)

    @Query("DELETE FROM examples")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM examples WHERE active = 1")
    suspend fun activeCount(): Int

    @Query("SELECT id, contentHash FROM examples")
    suspend fun getAllHashRows(): List<ExampleHashRow>
}

data class ExampleHashRow(
    val id: String,
    val contentHash: String,
)
