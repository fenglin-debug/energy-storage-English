package com.bess.salestrainer.core.data.repository

import com.bess.salestrainer.core.data.mapper.aiStatusEnum
import com.bess.salestrainer.core.data.mapper.modeEnum
import com.bess.salestrainer.core.data.mapper.statusEnum
import com.bess.salestrainer.core.data.mapper.toModel
import com.bess.salestrainer.core.data.mapper.toSummary
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.TurnAttemptEntity
import com.bess.salestrainer.core.database.tx.SessionTxRunner
import com.bess.salestrainer.core.model.AcceptTurnAttempt
import com.bess.salestrainer.core.model.AiStatus
import com.bess.salestrainer.core.model.LocalEvaluation
import com.bess.salestrainer.core.model.ScenarioFilter
import com.bess.salestrainer.core.model.ScenarioSessionDetail
import com.bess.salestrainer.core.model.ScenarioSessionSummary
import com.bess.salestrainer.core.model.ScenarioSummary
import com.bess.salestrainer.core.model.SessionAdvance
import com.bess.salestrainer.core.model.SessionCompletion
import com.bess.salestrainer.core.model.SessionStatus
import com.bess.salestrainer.core.model.Speaker
import com.bess.salestrainer.core.model.StartScenario
import com.bess.salestrainer.core.model.contract.ScenarioRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * Room-backed [ScenarioRepository]. Enforces AC-03 (single accepted attempt per
 * turn) via [SessionTxRunner] and idempotent start-or-resume.
 */
class ScenarioRepositoryImpl(
    private val db: BessDatabase,
) : ScenarioRepository {

    private val dao get() = db.scenarioDao()
    private val json = Json { ignoreUnknownKeys = true }

    override fun observeScenarios(filter: ScenarioFilter): Flow<List<ScenarioSummary>> =
        combine(
            dao.observeAllActive(),
            dao.observeBestScoresRaw(),
        ) { scenarios, bestScores ->
            val bestById = bestScores.associate { it.scenarioId to it.bestScoreJson }
            scenarios.asSequence()
                .filter { s -> filter.topic == null || s.topic == filter.topic }
                .filter { s -> filter.salesStage == null || s.salesStage == filter.salesStage }
                .filter { s -> filter.difficulty == null || s.difficulty == filter.difficulty }
                .map { s ->
                    val bestJson = bestById[s.id]
                    val best = bestJson?.let { parseWeightedTotal(it) }
                    s.toSummary(
                        completed = best != null,
                        bestScore = best,
                        lastScore = best,
                    )
                }
                .filter { s -> !filter.onlyIncomplete || !s.completed }
                .toList()
        }

    override fun observeSession(sessionId: String): Flow<ScenarioSessionDetail> =
        dao.observeSession(sessionId).flatMapLatest { session ->
            requireNotNull(session) { "Session $sessionId not found" }
            combine(
                dao.observeTurns(session.scenarioId),
                dao.observeAttempts(sessionId),
            ) { turns, attempts ->
                ScenarioSessionDetail(
                    sessionId = session.id,
                    scenarioId = session.scenarioId,
                    mode = session.modeEnum(),
                    status = session.statusEnum(),
                    turns = turns.map { it.toModel() },
                    currentCustomerTurnNo = session.currentCustomerTurnNo,
                    attempts = attempts.map { it.toModel() },
                    localScore = session.localScoreJson?.let { parseLocalEvaluation(it) },
                    aiStatus = session.aiStatusEnum(),
                    aiAdviceId = session.aiEvaluationId,
                )
            }
        }

    override fun observeLatestInProgress(): Flow<ScenarioSessionSummary?> =
        dao.observeLatestInProgress().flatMapLatest { session ->
            if (session == null) return@flatMapLatest flowOf(null)
            dao.observeTurns(session.scenarioId).map { turns ->
                val scenarioTitle = dao.getById(session.scenarioId)?.title ?: ""
                ScenarioSessionSummary(
                    sessionId = session.id,
                    scenarioId = session.scenarioId,
                    scenarioTitle = scenarioTitle,
                    mode = session.modeEnum(),
                    currentCustomerTurnNo = session.currentCustomerTurnNo,
                    totalCustomerTurns = turns.count { it.speaker == Speaker.CUSTOMER.name },
                    updatedAt = Instant.ofEpochMilli(session.updatedAtEpochMs),
                )
            }
        }

    override suspend fun startOrResume(command: StartScenario): String {
        val existing = dao.getInProgressForScenario(command.scenarioId)
        if (existing != null && existing.mode == command.mode.name) {
            return existing.id // idempotent resume
        }
        val now = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()
        val firstCustomerTurn = dao.getTurns(command.scenarioId)
            .firstOrNull { it.speaker == Speaker.CUSTOMER.name }?.turnNo ?: 1
        dao.upsertSession(
            ScenarioSessionEntity(
                id = sessionId,
                scenarioId = command.scenarioId,
                mode = command.mode.name,
                status = SessionStatus.IN_PROGRESS.name,
                currentCustomerTurnNo = firstCustomerTurn,
                localScoreJson = null,
                aiStatus = AiStatus.NOT_REQUESTED.name,
                aiEvaluationId = null,
                startedAtEpochMs = now,
                completedAtEpochMs = null,
                updatedAtEpochMs = now,
            ),
        )
        return sessionId
    }

    override suspend fun acceptTurnAttempt(command: AcceptTurnAttempt): SessionAdvance {
        val session = requireNotNull(dao.getSession(command.sessionId)) {
            "Session ${command.sessionId} not found"
        }
        val now = System.currentTimeMillis()
        val attempt = TurnAttemptEntity(
            id = UUID.randomUUID().toString(),
            sessionId = command.sessionId,
            turnNo = command.turnNo,
            accepted = false, // set true inside the tx
            rawTranscript = command.rawTranscript,
            editedTranscript = command.editedTranscript,
            wpm = command.metrics?.wpm,
            pauseRatio = command.metrics?.pauseRatio,
            maxPauseMs = command.metrics?.maxPauseMs,
            fillerCount = command.metrics?.fillerCount,
            keywordCoverage = command.metrics?.keywordCoverage,
            audioFileRef = command.audioFileRef,
            createdAtEpochMs = now,
        )

        val turns = dao.getTurns(session.scenarioId)
        val nextCustomerTurn = turns
            .filter { it.speaker == Speaker.CUSTOMER.name && it.turnNo > command.turnNo }
            .minByOrNull { it.turnNo }?.turnNo

        val updatedSession = if (nextCustomerTurn == null) {
            // No more customer turns -> complete with local evaluation.
            val evaluation = computeLocalEvaluation(session.id)
            session.copy(
                status = SessionStatus.COMPLETED.name,
                localScoreJson = json.encodeToString(LocalEvaluation.serializer(), evaluation),
                completedAtEpochMs = now,
                updatedAtEpochMs = now,
            )
        } else {
            session.copy(
                currentCustomerTurnNo = nextCustomerTurn,
                updatedAtEpochMs = now,
            )
        }

        SessionTxRunner(db).acceptAttemptAtomic(
            SessionTxRunner.AcceptAttemptTx(
                attempt = attempt,
                updatedSession = updatedSession,
            ),
        )

        return if (nextCustomerTurn == null) {
            SessionAdvance.SessionCompleted(
                SessionCompletion(
                    sessionId = session.id,
                    localEvaluation = computeLocalEvaluation(session.id),
                ),
            )
        } else {
            SessionAdvance.NextCustomerTurn(nextCustomerTurn)
        }
    }

    override suspend fun completeSession(sessionId: String): SessionCompletion {
        val session = requireNotNull(dao.getSession(sessionId)) { "Session $sessionId not found" }
        val now = System.currentTimeMillis()
        val evaluation = computeLocalEvaluation(sessionId)
        dao.upsertSession(
            session.copy(
                status = SessionStatus.COMPLETED.name,
                localScoreJson = json.encodeToString(LocalEvaluation.serializer(), evaluation),
                completedAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        return SessionCompletion(sessionId = sessionId, localEvaluation = evaluation)
    }

    // ------------------------------------------------------------------
    // Local five-dimension base evaluation (TDD §9.5).
    // Offline heuristic from accepted attempts; AI advice may refine later.
    // ------------------------------------------------------------------

    private suspend fun computeLocalEvaluation(sessionId: String): LocalEvaluation {
        val attempts = dao.getAttempts(sessionId).filter { it.accepted }
        if (attempts.isEmpty()) {
            return LocalEvaluation(0, 0, 0, 0, 0, 0)
        }
        val keyword = attempts.mapNotNull { it.keywordCoverage }.average().takeIf { !it.isNaN() } ?: 0.0
        val fluency = attempts.mapNotNull { it.wpm }.average().takeIf { !it.isNaN() } ?: 0.0
        val pauses = attempts.mapNotNull { it.pauseRatio }.average().takeIf { !it.isNaN() } ?: 1.0

        val keywordScore = (keyword * 100).toInt().coerceIn(0, 100)
        val fluencyScore = wpmToScore(fluency)
        val conditionalScore = ((1.0 - pauses.coerceIn(0.0, 1.0)) * 100).toInt()
        // Terminology & consultative default to keyword proxy until AI refines.
        val terminologyScore = keywordScore
        val consultativeScore = conditionalScore

        val weighted = (keywordScore * 35 + terminologyScore * 25 + fluencyScore * 20 +
            conditionalScore * 15 + consultativeScore * 5) / 100
        return LocalEvaluation(
            keywordCoverageScore = keywordScore,
            terminologyScore = terminologyScore,
            fluencyScore = fluencyScore,
            conditionalScore = conditionalScore,
            consultativeScore = consultativeScore,
            weightedTotal = weighted.coerceIn(0, 100),
        )
    }

    private fun wpmToScore(wpm: Double): Int = when {
        wpm <= 0.0 -> 0
        wpm < 90.0 -> (wpm / 90.0 * 60).toInt()
        wpm in 90.0..160.0 -> 60 + ((wpm - 90.0) / 70.0 * 40).toInt()
        else -> 100
    }.coerceIn(0, 100)

    private fun parseWeightedTotal(scoreJson: String): Int? =
        runCatching { json.decodeFromString(LocalEvaluation.serializer(), scoreJson).weightedTotal }
            .getOrNull()

    private fun parseLocalEvaluation(scoreJson: String): LocalEvaluation? =
        runCatching { json.decodeFromString(LocalEvaluation.serializer(), scoreJson) }.getOrNull()
}
