package com.bess.salestrainer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bess.salestrainer.core.database.entity.ItemMemoryStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemMemoryDao {

    @Query("SELECT * FROM item_memory_states WHERE itemId = :itemId AND itemType = :itemType")
    suspend fun get(itemId: String, itemType: String): ItemMemoryStateEntity?

    @Query("SELECT * FROM item_memory_states WHERE itemId IN (:itemIds) AND itemType = :itemType")
    suspend fun getForItems(itemIds: List<String>, itemType: String): List<ItemMemoryStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ItemMemoryStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(state: ItemMemoryStateEntity): Long

    @Query("SELECT * FROM item_memory_states WHERE itemType = :itemType")
    fun observeByType(itemType: String): Flow<List<ItemMemoryStateEntity>>

    /** Pair ids whose FSRS review is due (for scenario due badges). */
    @Query(
        """
        SELECT itemId FROM item_memory_states
        WHERE itemType = 'PAIR' AND dueAtEpochMs <= :nowEpochMs
        """,
    )
    fun observeDuePairIds(nowEpochMs: Long): Flow<List<String>>

    @Query(
        """
        SELECT COUNT(*) FROM item_memory_states
        WHERE itemType = :itemType AND dueAtEpochMs <= :nowEpochMs
        """,
    )
    fun observeDueCount(itemType: String, nowEpochMs: Long): Flow<Int>

    /** Repair legacy infinite-due item rows so reviewed items re-enter the queue. */
    @Query(
        """
        UPDATE item_memory_states
        SET dueAtEpochMs = CASE
            WHEN lastReviewAtEpochMs IS NULL THEN :nowEpochMs
            ELSE lastReviewAtEpochMs + 86400000
        END
        WHERE fsrsState != 'NEW' AND dueAtEpochMs >= 9223372036854775807
        """,
    )
    suspend fun repairInfiniteDueReviewedItems(nowEpochMs: Long)
}
