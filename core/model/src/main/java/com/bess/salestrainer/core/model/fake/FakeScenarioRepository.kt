package com.bess.salestrainer.core.model.fake

import com.bess.salestrainer.core.model.AcceptTurnAttempt
import com.bess.salestrainer.core.model.AiStatus
import com.bess.salestrainer.core.model.DialogueTurn
import com.bess.salestrainer.core.model.LocalEvaluation
import com.bess.salestrainer.core.model.ScenarioFilter
import com.bess.salestrainer.core.model.ScenarioMode
import com.bess.salestrainer.core.model.ScenarioSessionDetail
import com.bess.salestrainer.core.model.ScenarioSessionSummary
import com.bess.salestrainer.core.model.ScenarioSummary
import com.bess.salestrainer.core.model.SessionAdvance
import com.bess.salestrainer.core.model.SessionCompletion
import com.bess.salestrainer.core.model.SessionStatus
import com.bess.salestrainer.core.model.Speaker
import com.bess.salestrainer.core.model.StartScenario
import com.bess.salestrainer.core.model.TurnAttempt
import com.bess.salestrainer.core.model.contract.ScenarioRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory fake for scenario flows (index, session state machine, resume).
 * One sample scenario with 3 customer turns drives both SIMULATION and PRACTICE.
 */
class FakeScenarioRepository : ScenarioRepository {

    private val scenarios = MutableStateFlow(sampleScenarios())

    // sessionId -> detail
    private val sessions = ConcurrentHashMap<String, MutableStateFlow<ScenarioSessionDetail>>()
    private val latestInProgress = MutableStateFlow<ScenarioSessionSummary?>(null)

    override fun observeScenarios(filter: ScenarioFilter): Flow<List<ScenarioSummary>> =
        scenarios.map { list ->
            list.filter { s ->
                (filter.topic == null || s.topic == filter.topic) &&
                    (filter.salesStage == null || s.salesStage == filter.salesStage) &&
                    (filter.difficulty == null || s.difficulty == filter.difficulty) &&
                    (!filter.onlyIncomplete || !s.completed)
            }
        }

    override fun observeSession(sessionId: String): Flow<ScenarioSessionDetail> =
        sessions.getValue(sessionId)

    override fun observeLatestInProgress(): Flow<ScenarioSessionSummary?> = latestInProgress

    override suspend fun startOrResume(command: StartScenario): String {
        // Idempotent: reuse existing in-progress session for same scenario.
        latestInProgress.value?.let { existing ->
            if (existing.scenarioId == command.scenarioId) return existing.sessionId
        }
        val sessionId = "sess_" + UUID.randomUUID().toString().take(8)
        val scenario = scenarios.value.first { it.id == command.scenarioId }
        val turns = sampleTurns(command.scenarioId)
        val detail = ScenarioSessionDetail(
            sessionId = sessionId,
            scenarioId = command.scenarioId,
            mode = command.mode,
            status = SessionStatus.IN_PROGRESS,
            turns = turns,
            currentCustomerTurnNo = 1,
            attempts = emptyList(),
            localScore = null,
            aiStatus = AiStatus.NOT_REQUESTED,
            aiAdviceId = null,
        )
        sessions[sessionId] = MutableStateFlow(detail)
        latestInProgress.value = ScenarioSessionSummary(
            sessionId = sessionId,
            scenarioId = command.scenarioId,
            scenarioTitle = scenario.title,
            mode = command.mode,
            currentCustomerTurnNo = 1,
            totalCustomerTurns = turns.count { it.speaker == Speaker.CUSTOMER },
            updatedAt = Instant.now(),
        )
        return sessionId
    }

    override suspend fun acceptTurnAttempt(command: AcceptTurnAttempt): SessionAdvance {
        val flow = sessions.getValue(command.sessionId)
        val cur = flow.value
        val attempt = TurnAttempt(
            id = "att_" + UUID.randomUUID().toString().take(8),
            sessionId = command.sessionId,
            turnNo = command.turnNo,
            accepted = true,
            rawTranscript = command.rawTranscript,
            editedTranscript = command.editedTranscript,
            wpm = command.metrics?.wpm,
            pauseRatio = command.metrics?.pauseRatio,
            maxPauseMs = command.metrics?.maxPauseMs,
            fillerCount = command.metrics?.fillerCount,
            keywordCoverage = command.metrics?.keywordCoverage,
            audioFileRef = command.audioFileRef,
            createdAt = Instant.now(),
        )
        val customerTurns = cur.turns.filter { it.speaker == Speaker.CUSTOMER }.map { it.turnNo }.sorted()
        val nextCustomer = customerTurns.firstOrNull { it > cur.currentCustomerTurnNo }

        return if (nextCustomer == null) {
            // No more customer turns -> session completes.
            val eval = LocalEvaluation(
                keywordCoverageScore = 80, terminologyScore = 75, fluencyScore = 70,
                conditionalScore = 65, consultativeScore = 60,
                weightedTotal = (80 * 35 + 75 * 25 + 70 * 20 + 65 * 15 + 60 * 5) / 100,
            )
            val completed = cur.copy(
                status = SessionStatus.COMPLETED,
                attempts = cur.attempts + attempt,
                localScore = eval,
            )
            flow.value = completed
            latestInProgress.value = null
            SessionAdvance.SessionCompleted(SessionCompletion(command.sessionId, eval))
        } else {
            flow.value = cur.copy(
                attempts = cur.attempts + attempt,
                currentCustomerTurnNo = nextCustomer,
            )
            latestInProgress.value = latestInProgress.value?.copy(
                currentCustomerTurnNo = nextCustomer, updatedAt = Instant.now(),
            )
            SessionAdvance.NextCustomerTurn(nextCustomer)
        }
    }

    override suspend fun completeSession(sessionId: String): SessionCompletion {
        val flow = sessions.getValue(sessionId)
        val eval = flow.value.localScore ?: LocalEvaluation(70, 70, 70, 70, 70, 70)
        flow.value = flow.value.copy(status = SessionStatus.COMPLETED, localScore = eval)
        latestInProgress.value = null
        return SessionCompletion(sessionId, eval)
    }

    private fun sampleScenarios(): List<ScenarioSummary> = listOf(
        ScenarioSummary(
            id = "S_001", title = "首次技术交流：集装箱储能方案介绍",
            topic = "方案介绍", salesStage = "初步接触", difficulty = "中等",
            estimatedMinutes = 8, completed = false, bestScore = null, lastScore = null,
        ),
        ScenarioSummary(
            id = "S_002", title = "客户质疑：往返效率与衰减承诺",
            topic = "异议处理", salesStage = "方案论证", difficulty = "较高",
            estimatedMinutes = 10, completed = false, bestScore = null, lastScore = null,
        ),
        ScenarioSummary(
            id = "S_003", title = "现场勘察：并网接入条件确认",
            topic = "现场勘察", salesStage = "方案设计", difficulty = "中等",
            estimatedMinutes = 7, completed = false, bestScore = null, lastScore = null,
        ),
    )

    private fun sampleTurns(scenarioId: String): List<DialogueTurn> = listOf(
        DialogueTurn(
            scenarioId = scenarioId, turnNo = 1, speaker = Speaker.CUSTOMER,
            textEn = "We need a 5MWh containerized solution for a solar-plus-storage site. What cell chemistry do you use?",
            textZh = "我们一个光储项目需要5MWh集装箱方案，你们用什么电芯？",
            ttsText = "We need a 5 megawatt-hour containerized solution for a solar-plus-storage site. What cell chemistry do you use?",
            audioRef = null,
            keywords = listOf("LFP", "5MWh", "cell chemistry"),
            expectedPoints = listOf("Confirm LFP chemistry", "Mention cycle life"),
            referenceAnswerEn = "We use LFP cells with over 8000 cycles at 70% SOH retention.",
        ),
        DialogueTurn(
            scenarioId = scenarioId, turnNo = 2, speaker = Speaker.SALES_ENGINEER,
            textEn = "(Your answer)",
            textZh = "(你的回答)",
            ttsText = "",
            audioRef = null,
            keywords = emptyList(), expectedPoints = emptyList(), referenceAnswerEn = null,
        ),
        DialogueTurn(
            scenarioId = scenarioId, turnNo = 3, speaker = Speaker.CUSTOMER,
            textEn = "What about round-trip efficiency and auxiliary consumption?",
            textZh = "往返效率和自耗电呢？",
            ttsText = "What about round-trip efficiency and auxiliary consumption?",
            audioRef = null,
            keywords = listOf("round-trip efficiency", "auxiliary consumption"),
            expectedPoints = listOf("State RTE value", "State auxiliary consumption"),
            referenceAnswerEn = "Our system delivers 95% round-trip efficiency with auxiliary consumption below 2%.",
        ),
        DialogueTurn(
            scenarioId = scenarioId, turnNo = 4, speaker = Speaker.SALES_ENGINEER,
            textEn = "(Your answer)", textZh = "(你的回答)", ttsText = "", audioRef = null,
            keywords = emptyList(), expectedPoints = emptyList(), referenceAnswerEn = null,
        ),
        DialogueTurn(
            scenarioId = scenarioId, turnNo = 5, speaker = Speaker.CUSTOMER,
            textEn = "Can you guarantee the degradation rate over 15 years?",
            textZh = "你们能保证15年的衰减率吗？",
            ttsText = "Can you guarantee the degradation rate over 15 years?",
            audioRef = null,
            keywords = listOf("degradation", "warranty", "SOH"),
            expectedPoints = listOf("State warranty terms", "SOH commitment"),
            referenceAnswerEn = "We offer a 15-year warranty with guaranteed SOH of at least 70% at end of life.",
        ),
    )
}
