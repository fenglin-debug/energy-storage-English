package com.bess.salestrainer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bess.salestrainer.core.database.entity.DialoguePairEntity
import com.bess.salestrainer.core.database.entity.DialoguePairPhraseEntity
import com.bess.salestrainer.core.database.entity.DialoguePairWordEntity
import com.bess.salestrainer.core.database.entity.DialogueTurnEntity
import com.bess.salestrainer.core.database.entity.ScenarioEntity
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.ScenarioTurnProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScenarioDao {

    // ---- scenarios ----

    @Query("SELECT * FROM scenarios WHERE active = 1 ORDER BY id")
    fun observeAllActive(): Flow<List<ScenarioEntity>>

    @Query("SELECT * FROM scenarios WHERE active = 1 ORDER BY id")
    suspend fun getAllActive(): List<ScenarioEntity>

    @Query("SELECT * FROM scenarios WHERE id = :id")
    suspend fun getById(id: String): ScenarioEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(scenarios: List<ScenarioEntity>)

    @Query("DELETE FROM scenarios")
    suspend fun deleteAllScenarios()

    @Query("SELECT COUNT(*) FROM scenarios WHERE active = 1")
    suspend fun activeCount(): Int

    // ---- dialogue turns ----

    @Query("SELECT * FROM dialogue_turns WHERE scenarioId = :scenarioId ORDER BY turnNo")
    fun observeTurns(scenarioId: String): Flow<List<DialogueTurnEntity>>

    @Query("SELECT * FROM dialogue_turns WHERE scenarioId = :scenarioId ORDER BY turnNo")
    suspend fun getTurns(scenarioId: String): List<DialogueTurnEntity>

    @Query("SELECT * FROM dialogue_turns WHERE id IN (:ids)")
    suspend fun getTurnsByIds(ids: List<String>): List<DialogueTurnEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTurns(turns: List<DialogueTurnEntity>)

    @Query("DELETE FROM dialogue_turns")
    suspend fun deleteAllTurns()

    // ---- dialogue pairs ----

    @Query("SELECT * FROM dialogue_pairs WHERE scenarioId = :scenarioId ORDER BY pairIndex")
    suspend fun getPairs(scenarioId: String): List<DialoguePairEntity>

    @Query(
        """
        SELECT p.* FROM dialogue_pairs p
        INNER JOIN scenarios s ON s.id = p.scenarioId
        WHERE s.active = 1
        ORDER BY p.id
        """,
    )
    suspend fun getAllActivePairs(): List<DialoguePairEntity>

    @Query("SELECT * FROM dialogue_pairs WHERE id = :pairId")
    suspend fun getPairById(pairId: String): DialoguePairEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPairs(pairs: List<DialoguePairEntity>)

    @Query("DELETE FROM dialogue_pairs")
    suspend fun deleteAllPairs()

    // ---- pair keywords ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPairWords(links: List<DialoguePairWordEntity>)

    @Query("SELECT * FROM dialogue_pair_words WHERE pairId = :pairId ORDER BY sortOrder")
    suspend fun getPairWords(pairId: String): List<DialoguePairWordEntity>

    @Query("DELETE FROM dialogue_pair_words")
    suspend fun deleteAllPairWords()

    // ---- pair phrase links (v3) ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPairPhrases(links: List<DialoguePairPhraseEntity>)

    @Query("SELECT * FROM dialogue_pair_phrases WHERE pairId = :pairId ORDER BY sortOrder")
    suspend fun getPairPhrases(pairId: String): List<DialoguePairPhraseEntity>

    @Query("DELETE FROM dialogue_pair_phrases")
    suspend fun deleteAllPairPhrases()

    // ---- sessions ----

    @Query("SELECT * FROM scenario_sessions WHERE id = :id")
    fun observeSession(id: String): Flow<ScenarioSessionEntity?>

    @Query("SELECT * FROM scenario_sessions WHERE id = :id")
    suspend fun getSession(id: String): ScenarioSessionEntity?

    @Query(
        """
        SELECT * FROM scenario_sessions
        WHERE status = 'IN_PROGRESS'
        ORDER BY updatedAtEpochMs DESC
        LIMIT 1
        """
    )
    fun observeLatestInProgress(): Flow<ScenarioSessionEntity?>

    @Query(
        """
        SELECT * FROM scenario_sessions
        WHERE scenarioId = :scenarioId AND status = 'IN_PROGRESS'
        ORDER BY updatedAtEpochMs DESC
        LIMIT 1
        """
    )
    suspend fun getInProgressForScenario(scenarioId: String): ScenarioSessionEntity?

    @Query(
        """
        SELECT * FROM scenario_sessions
        WHERE practiceMode = 'RANDOM' AND status = 'IN_PROGRESS'
        ORDER BY updatedAtEpochMs DESC
        LIMIT 1
        """,
    )
    suspend fun getInProgressRandom(): ScenarioSessionEntity?

    @Query("SELECT * FROM scenario_sessions WHERE status = 'IN_PROGRESS'")
    suspend fun getAllInProgress(): List<ScenarioSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ScenarioSessionEntity)

    @Query(
        """
        UPDATE scenario_sessions
        SET status = 'ABORTED_CORPUS_CHANGED', updatedAtEpochMs = :nowEpochMs
        WHERE status = 'IN_PROGRESS' AND scenarioContentHash NOT IN (:activeHashes)
        """
    )
    suspend fun abortInProgressWithStaleHash(activeHashes: List<String>, nowEpochMs: Long): Int

    @Query(
        """
        UPDATE scenario_sessions
        SET status = 'ABORTED_CORPUS_CHANGED', updatedAtEpochMs = :nowEpochMs
        WHERE status = 'IN_PROGRESS'
        """
    )
    suspend fun abortAllInProgress(nowEpochMs: Long): Int

    @Query(
        "SELECT COUNT(*) FROM scenario_sessions WHERE scenarioId = :scenarioId AND status = 'COMPLETED'"
    )
    suspend fun completedCountForScenario(scenarioId: String): Int

    @Query(
        """
        SELECT p.selfRating AS rating, COUNT(*) AS count
        FROM scenario_turn_progress p
        INNER JOIN scenario_sessions s ON s.id = p.sessionId
        WHERE s.scenarioId = :scenarioId AND p.selfRating IS NOT NULL
        GROUP BY p.selfRating
        """
    )
    suspend fun ratingSummaryForScenario(scenarioId: String): List<RatingCount>

    // ---- turn progress ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTurnProgress(progress: ScenarioTurnProgressEntity)

    @Query("SELECT * FROM scenario_turn_progress WHERE sessionId = :sessionId AND pairId = :pairId")
    fun observeTurnProgress(sessionId: String, pairId: String): Flow<ScenarioTurnProgressEntity?>

    @Query("SELECT * FROM scenario_turn_progress WHERE sessionId = :sessionId AND pairId = :pairId")
    suspend fun getTurnProgress(sessionId: String, pairId: String): ScenarioTurnProgressEntity?

    @Query("SELECT * FROM scenario_turn_progress WHERE sessionId = :sessionId")
    suspend fun getAllTurnProgress(sessionId: String): List<ScenarioTurnProgressEntity>
}

data class RatingCount(
    val rating: String,
    val count: Int,
)
