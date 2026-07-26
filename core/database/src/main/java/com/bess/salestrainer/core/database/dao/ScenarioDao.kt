package com.bess.salestrainer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bess.salestrainer.core.database.entity.DialogueTurnEntity
import com.bess.salestrainer.core.database.entity.ScenarioEntity
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.ScenarioTrainingPackEntity
import com.bess.salestrainer.core.database.entity.TurnAttemptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScenarioDao {

    @Query("SELECT * FROM scenarios WHERE active = 1 ORDER BY title")
    fun observeAllActive(): Flow<List<ScenarioEntity>>

    @Query("SELECT * FROM scenarios WHERE id = :id")
    suspend fun getById(id: String): ScenarioEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(scenarios: List<ScenarioEntity>)

    @Query("UPDATE scenarios SET active = 0 WHERE id IN (:ids)")
    suspend fun deactivateByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM scenarios WHERE active = 1")
    suspend fun activeCount(): Int

    // ---- dialogue turns ----

    @Query("SELECT * FROM dialogue_turns WHERE scenarioId = :scenarioId ORDER BY turnNo")
    fun observeTurns(scenarioId: String): Flow<List<DialogueTurnEntity>>

    @Query("SELECT * FROM dialogue_turns WHERE scenarioId = :scenarioId ORDER BY turnNo")
    suspend fun getTurns(scenarioId: String): List<DialogueTurnEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTurns(turns: List<DialogueTurnEntity>)

    @Query("DELETE FROM dialogue_turns WHERE scenarioId IN (:scenarioIds)")
    suspend fun deleteTurnsForScenarios(scenarioIds: List<String>)

    // ---- training packs ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrainingPacks(packs: List<ScenarioTrainingPackEntity>)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ScenarioSessionEntity)

    @Query(
        """
        SELECT scenarioId, MAX(localScoreJson) FROM scenario_sessions
        WHERE status = 'COMPLETED'
        GROUP BY scenarioId
        """
    )
    fun observeBestScoresRaw(): Flow<List<ScenarioBestScore>>

    // ---- turn attempts ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttempt(attempt: TurnAttemptEntity)

    @Query("SELECT * FROM turn_attempts WHERE sessionId = :sessionId ORDER BY turnNo, createdAtEpochMs")
    fun observeAttempts(sessionId: String): Flow<List<TurnAttemptEntity>>

    @Query("SELECT * FROM turn_attempts WHERE sessionId = :sessionId ORDER BY turnNo, createdAtEpochMs")
    suspend fun getAttempts(sessionId: String): List<TurnAttemptEntity>

    @Query("UPDATE turn_attempts SET accepted = 0 WHERE sessionId = :sessionId AND turnNo = :turnNo")
    suspend fun clearAcceptedForTurn(sessionId: String, turnNo: Int)
}

data class ScenarioBestScore(
    val scenarioId: String,
    val bestScoreJson: String?,
)
