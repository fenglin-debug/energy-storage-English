package com.bess.salestrainer.core.model.fake

import com.bess.salestrainer.core.model.AiAdvice
import com.bess.salestrainer.core.model.AiDimensionScores
import com.bess.salestrainer.core.model.AiEvaluationResult
import com.bess.salestrainer.core.model.AiIssue
import com.bess.salestrainer.core.model.FactRisk
import com.bess.salestrainer.core.model.FactStatus
import com.bess.salestrainer.core.model.ImprovedResponse
import com.bess.salestrainer.core.model.contract.AiCoachRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-memory fake returning a fixed five-dimension AI evaluation. */
class FakeAiCoachRepository : AiCoachRepository {

    private val advices = ConcurrentHashMap<String, MutableStateFlow<AiAdvice>>()

    override fun observeAdvice(adviceId: String): Flow<AiAdvice> =
        advices.getValue(adviceId)

    override suspend fun evaluateSession(sessionId: String): AiEvaluationResult {
        delay(50) // simulate latency
        val advice = buildAdvice(sessionId)
        advices[advice.id] = MutableStateFlow(advice)
        return AiEvaluationResult.Success(advice, fromLocalFallback = false)
    }

    override suspend fun retryEvaluation(adviceId: String): AiEvaluationResult {
        delay(50)
        val existing = advices.getValue(adviceId).value
        val refreshed = existing.copy(createdAt = Instant.now())
        advices[adviceId]!!.value = refreshed
        return AiEvaluationResult.Success(refreshed, fromLocalFallback = false)
    }

    private fun buildAdvice(sessionId: String): AiAdvice {
        val scores = AiDimensionScores(
            contentCoverage = 82, terminology = 78, fluency = 70, grammar = 85, professionalism = 74,
        )
        return AiAdvice(
            id = "adv_" + UUID.randomUUID().toString().take(8),
            sessionId = sessionId,
            scores = scores,
            strengths = listOf("Clear statement of LFP chemistry and cycle life"),
            issues = listOf(
                AiIssue(turnIndex = 1, severity = "minor", evidence = "Hesitation before stating round-trip efficiency"),
            ),
            missingPoints = listOf("Did not mention auxiliary consumption value"),
            improvedResponses = listOf(
                ImprovedResponse(
                    turnIndex = 1,
                    b1b2 = "We use LFP cells. They last over 8000 cycles and keep 70% capacity.",
                    b2c1 = "We utilize LFP cell chemistry, delivering in excess of 8000 cycles while retaining no less than 70% state of health.",
                ),
            ),
            recommendedWordIds = listOf("V_001", "V_003"),
            retellingTaskEn = "In 45 seconds, summarize the key performance figures of your system.",
            factRisks = listOf(
                FactRisk(claim = "8000 cycles at 70% SOH", status = FactStatus.VERIFY, note = "Verify against latest datasheet"),
            ),
            promptVersion = "scenario-eval-v1",
            modelName = "deepseek-v4-flash",
            createdAt = Instant.now(),
        )
    }
}
