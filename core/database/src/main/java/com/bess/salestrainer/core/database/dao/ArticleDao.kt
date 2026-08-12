package com.bess.salestrainer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bess.salestrainer.core.database.entity.ArticleEntity
import com.bess.salestrainer.core.database.entity.ArticleProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {

    @Query("SELECT * FROM articles ORDER BY id")
    fun observeAll(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE id = :id")
    fun observeById(id: String): Flow<ArticleEntity?>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun getById(id: String): ArticleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(articles: List<ArticleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(article: ArticleEntity)

    @Query("DELETE FROM articles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM article_progress WHERE articleId = :articleId")
    suspend fun deleteProgress(articleId: String)

    @Query("SELECT COUNT(*) FROM articles")
    suspend fun count(): Int

    @Query(
        """
        SELECT id FROM articles
        WHERE source = 'BUNDLED' OR contentScope = 'BESS'
        ORDER BY id
        """,
    )
    suspend fun getRandomEligibleIds(): List<String>

    @Query("SELECT id FROM articles WHERE source = 'BUNDLED'")
    suspend fun getBundledIds(): List<String>

    @Query("SELECT COUNT(*) FROM articles WHERE source = 'BUNDLED'")
    suspend fun bundledCount(): Int

    @Query("DELETE FROM articles WHERE source = 'BUNDLED' AND id NOT IN (:activeIds)")
    suspend fun deleteBundledNotIn(activeIds: List<String>)

    // ---- listening progress ----

    @Query("SELECT * FROM article_progress WHERE articleId = :articleId")
    fun observeProgress(articleId: String): Flow<ArticleProgressEntity?>

    @Query("SELECT * FROM article_progress WHERE articleId = :articleId")
    suspend fun getProgress(articleId: String): ArticleProgressEntity?

    @Query("SELECT * FROM article_progress")
    fun observeAllProgress(): Flow<List<ArticleProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: ArticleProgressEntity)
}
