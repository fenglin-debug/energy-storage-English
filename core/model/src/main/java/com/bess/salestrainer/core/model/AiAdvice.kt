package com.bess.salestrainer.core.model

import java.time.Instant

/** Five AI scoring dimensions (FR-DIA-006). Total is computed by app, never taken from model. */
data class AiDimensionScores(
    val contentCoverage: Int,   // 35%
    val terminology: Int,       // 25%
    val fluency: Int,           // 20%
    val grammar: Int,           // 15%
    val professionalism: Int,   // 5%
) {
    init {
        listOf(contentCoverage, terminology, fluency, grammar, professionalism).forEach {
            require(it in 0..100) { "AI dimension score out of range: $it" }
        }
    }

    /** App-side weighted total (35/25/20/15/5). */
    fun weightedTotal(): Int =
        (contentCoverage * 35 + terminology * 25 + fluency * 20 + grammar * 15 + professionalism * 5) / 100
}

/** A fact risk flagged by AI output (FR-AI-002, AC-11). */
data class FactRisk(
    val claim: String,
    val status: FactStatus = FactStatus.VERIFY,
    val note: String?,
)

/** AI advice record (persisted; materials are AI-sourced, never promoted to core). */
data class AiAdvice(
    val id: String,
    val sessionId: String,
    val scores: AiDimensionScores?,
    val strengths: List<String>,
    val issues: List<AiIssue>,
    val missingPoints: List<String>,
    val improvedResponses: List<ImprovedResponse>,
    val recommendedWordIds: List<String>,
    val retellingTaskEn: String?,
    val factRisks: List<FactRisk>,
    val promptVersion: String,
    val modelName: String,
    val createdAt: Instant,
    val isFavorite: Boolean = false,
)

data class AiIssue(
    val turnIndex: Int,
    val severity: String,
    val evidence: String,
)

data class ImprovedResponse(
    val turnIndex: Int,
    val b1b2: String,
    val b2c1: String,
)

/** Result wrapper for evaluation calls. */
sealed interface AiEvaluationResult {
    data class Success(val advice: AiAdvice, val fromLocalFallback: Boolean) : AiEvaluationResult
    data class Failure(val reason: AiFailureReason, val retryable: Boolean, val fallbackAdvice: AiAdvice?) : AiEvaluationResult
}

enum class AiFailureReason {
    NO_KEY, UNAUTHORIZED, INSUFFICIENT_BALANCE, RATE_LIMITED,
    SERVER_ERROR, TIMEOUT, NETWORK, INVALID_RESPONSE, MODEL_UNAVAILABLE, UNKNOWN
}
