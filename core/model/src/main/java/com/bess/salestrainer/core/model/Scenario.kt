package com.bess.salestrainer.core.model

import java.time.Instant

/** Scenario list item (FR-DIA-001). */
data class ScenarioSummary(
    val id: String,
    val title: String,
    val topic: String,
    val salesStage: String?,
    val difficulty: String?,
    val estimatedMinutes: Int?,
    val completed: Boolean,
    val bestScore: Int?,
    val lastScore: Int?,
)

/** Filter for scenario index. */
data class ScenarioFilter(
    val topic: String? = null,
    val salesStage: String? = null,
    val difficulty: String? = null,
    val onlyIncomplete: Boolean = false,
)

/** A single dialogue turn (TDD §4 DialogueTurn). */
data class DialogueTurn(
    val scenarioId: String,
    val turnNo: Int,
    val speaker: Speaker,
    val textEn: String,
    val textZh: String?,
    val ttsText: String,
    val audioRef: String?,
    val keywords: List<String> = emptyList(),
    val expectedPoints: List<String> = emptyList(),
    val referenceAnswerEn: String?,
)

/** One accepted/recorded attempt for a sales turn. */
data class TurnAttempt(
    val id: String,
    val sessionId: String,
    val turnNo: Int,
    val accepted: Boolean,
    val rawTranscript: String?,
    val editedTranscript: String?,
    val wpm: Double?,
    val pauseRatio: Double?,
    val maxPauseMs: Long?,
    val fillerCount: Int?,
    val keywordCoverage: Double?,
    val audioFileRef: String?,
    val createdAt: Instant,
)

/** Full session detail for the scenario screen. */
data class ScenarioSessionDetail(
    val sessionId: String,
    val scenarioId: String,
    val mode: ScenarioMode,
    val status: SessionStatus,
    val turns: List<DialogueTurn>,
    /** The customer turn number the user must respond to (resume anchor, FR-DIA-003/AC-03). */
    val currentCustomerTurnNo: Int,
    val attempts: List<TurnAttempt>,
    val localScore: LocalEvaluation?,
    val aiStatus: AiStatus,
    val aiAdviceId: String?,
)

/** Resumable in-progress session (FR-HOME-002). */
data class ScenarioSessionSummary(
    val sessionId: String,
    val scenarioId: String,
    val scenarioTitle: String,
    val mode: ScenarioMode,
    val currentCustomerTurnNo: Int,
    val totalCustomerTurns: Int,
    val updatedAt: Instant,
)

/** Command to start or resume a session (idempotent). */
data class StartScenario(
    val scenarioId: String,
    val mode: ScenarioMode,
)

/** Command to accept a turn attempt. */
data class AcceptTurnAttempt(
    val sessionId: String,
    val turnNo: Int,
    val rawTranscript: String?,
    val editedTranscript: String?,
    val metrics: SpeechMetrics?,
    val audioFileRef: String?,
)

/** Local (offline) scoring metrics (FR-DIA-005). */
data class SpeechMetrics(
    val wpm: Double?,
    val pauseRatio: Double?,
    val maxPauseMs: Long?,
    val fillerCount: Int?,
    val keywordCoverage: Double?,
)

/** Result of accepting a turn — how the session advanced. */
sealed interface SessionAdvance {
    data class NextCustomerTurn(val turnNo: Int) : SessionAdvance
    data class SessionCompleted(val completion: SessionCompletion) : SessionAdvance
}

/** Session completion payload. */
data class SessionCompletion(
    val sessionId: String,
    val localEvaluation: LocalEvaluation,
)

/** Local five-dimension base evaluation (TDD §9.5). UI labels it "本地基础评分". */
data class LocalEvaluation(
    val keywordCoverageScore: Int,
    val terminologyScore: Int,
    val fluencyScore: Int,
    val conditionalScore: Int,
    val consultativeScore: Int,
    /** App-computed weighted total using 35/25/20/15/5 (FR-DIA-006). */
    val weightedTotal: Int,
)
