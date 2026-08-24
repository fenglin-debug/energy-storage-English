package com.bess.salestrainer.core.model.fake

import com.bess.salestrainer.core.model.CustomerTextView
import com.bess.salestrainer.core.model.DialogueSelfRating
import com.bess.salestrainer.core.model.Keyword
import com.bess.salestrainer.core.model.KeywordsView
import com.bess.salestrainer.core.model.ReferenceAnswer
import com.bess.salestrainer.core.model.ReferenceAnswerView
import com.bess.salestrainer.core.model.ScenarioAdvance
import com.bess.salestrainer.core.model.ScenarioFilter
import com.bess.salestrainer.core.model.ScenarioPracticeUnit
import com.bess.salestrainer.core.model.ScenarioSessionSummary
import com.bess.salestrainer.core.model.ScenarioSummary
import com.bess.salestrainer.core.model.ScenarioTurnProgress
import com.bess.salestrainer.core.model.ScenarioUnitView
import com.bess.salestrainer.core.model.SessionStatus
import com.bess.salestrainer.core.model.contract.ScenarioRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FakeScenarioRepository : ScenarioRepository {
    private data class PairContent(
        val pairId: String,
        val customerEnglish: String,
        val customerChinese: String? = null,
        val keywords: List<Keyword>,
        val answer: ReferenceAnswer,
        val audioAssetId: String,
    )

    private val scenarios = MutableStateFlow(sampleScenarios())
    private val sessions = ConcurrentHashMap<String, MutableStateFlow<ScenarioUnitView>>()
    private val sessionScenarioIds = ConcurrentHashMap<String, String>()
    private val ratings = ConcurrentHashMap<String, MutableList<DialogueSelfRating>>()
    private val latest = MutableStateFlow<ScenarioSessionSummary?>(null)
    private val contents = samplePairs()

    override fun observeScenarios(filter: ScenarioFilter): Flow<List<ScenarioSummary>> =
        scenarios.map { values ->
            values.filter { scenario ->
                (filter.topic == null || scenario.topic == filter.topic) &&
                    (filter.salesStage == null || scenario.salesStage == filter.salesStage) &&
                    (filter.difficulty == null || scenario.difficulty == filter.difficulty) &&
                    (!filter.onlyIncomplete || scenario.status != SessionStatus.COMPLETED)
            }
        }

    override fun observeDuePairIds(nowEpochMs: Long): Flow<List<String>> =
        MutableStateFlow(emptyList())

    override fun observeCurrentUnit(sessionId: String): Flow<ScenarioUnitView> =
        sessions.getValue(sessionId)

    override fun observeLatestInProgress(): Flow<ScenarioSessionSummary?> = latest

    override suspend fun startOrResume(scenarioId: String): String {
        latest.value?.let { existing ->
            if (existing.scenarioId == scenarioId && existing.status == SessionStatus.IN_PROGRESS) {
                return existing.sessionId
            }
        }
        val sessionId = "scenario_${UUID.randomUUID()}"
        val scenario = scenarios.value.first { it.id == scenarioId }
        val firstPair = contents.getValue(scenarioId).first()
        val now = Instant.now()
        val view = concealedView(sessionId, scenarioId, firstPair, 0, now)
        sessions[sessionId] = MutableStateFlow(view)
        sessionScenarioIds[sessionId] = scenarioId
        ratings[sessionId] = mutableListOf()
        latest.value = ScenarioSessionSummary(
            sessionId = sessionId,
            scenarioId = scenarioId,
            scenarioTitle = scenario.title,
            status = SessionStatus.IN_PROGRESS,
            currentPairIndex = 0,
            pairCount = contents.getValue(scenarioId).size,
            updatedAt = now,
        )
        return sessionId
    }

    override suspend fun startOrResumeRandom(): String =
        startOrResume(scenarios.value.first().id)

    override suspend fun endRandomSession(sessionId: String) {
        sessions[sessionId]?.let { flow ->
            flow.value = flow.value.copy(status = SessionStatus.COMPLETED)
        }
        latest.value = null
    }

    override suspend fun markCustomerAudioCompleted(sessionId: String, pairId: String) {
        updateCurrent(sessionId, pairId) { view ->
            view.copy(
                progress = view.progress.copy(customerAudioCompleted = true, updatedAt = Instant.now())
            )
        }
    }

    override suspend fun revealCustomerText(sessionId: String, pairId: String) {
        updateCurrent(sessionId, pairId) { view ->
            val content = contentFor(sessionId, pairId)
            view.copy(
                unit = view.unit.copy(
                    customerText = CustomerTextView.Revealed(
                        english = content.customerEnglish,
                        chinese = content.customerChinese,
                    ),
                ),
                progress = view.progress.copy(customerTextRevealed = true, updatedAt = Instant.now()),
            )
        }
    }

    override suspend fun revealKeywords(sessionId: String, pairId: String) {
        updateCurrent(sessionId, pairId) { view ->
            val content = contentFor(sessionId, pairId)
            view.copy(
                unit = view.unit.copy(keywords = KeywordsView.Revealed(content.keywords)),
                progress = view.progress.copy(keywordsRevealed = true, updatedAt = Instant.now()),
            )
        }
    }

    override suspend fun revealReferenceAnswer(sessionId: String, pairId: String) {
        updateCurrent(sessionId, pairId) { view ->
            val content = contentFor(sessionId, pairId)
            view.copy(
                unit = view.unit.copy(answer = ReferenceAnswerView.Revealed(content.answer)),
                progress = view.progress.copy(answerRevealed = true, updatedAt = Instant.now()),
            )
        }
    }

    override suspend fun rateAndAdvance(
        sessionId: String,
        pairId: String,
        rating: DialogueSelfRating,
    ): ScenarioAdvance {
        val flow = sessions.getValue(sessionId)
        val current = flow.value
        require(current.unit.pairId == pairId) { "Rating action is stale" }
        require(current.progress.answerRevealed) { "Reference answer must be revealed before rating" }
        require(current.progress.selfRating == null) { "Pair has already been rated" }
        ratings.getValue(sessionId) += rating

        val scenarioId = sessionScenarioIds.getValue(sessionId)
        val pairs = contents.getValue(scenarioId)
        val nextIndex = current.unit.pairIndex + 1
        return if (nextIndex >= pairs.size) {
            val distribution = DialogueSelfRating.entries.associateWith { value ->
                ratings.getValue(sessionId).count { it == value }
            }
            val completedProgress = current.progress.copy(selfRating = rating, updatedAt = Instant.now())
            flow.value = current.copy(
                status = SessionStatus.COMPLETED,
                progress = completedProgress,
            )
            latest.value = null
            scenarios.value = scenarios.value.map {
                if (it.id == scenarioId) {
                    it.copy(status = SessionStatus.COMPLETED, latestRatingSummary = distribution)
                } else {
                    it
                }
            }
            ScenarioAdvance.Completed(sessionId, distribution)
        } else {
            val next = pairs[nextIndex]
            val now = Instant.now()
            flow.value = concealedView(sessionId, scenarioId, next, nextIndex, now)
            latest.value = latest.value?.copy(currentPairIndex = nextIndex, updatedAt = now)
            ScenarioAdvance.NextPair(next.pairId, nextIndex, pairs.size)
        }
    }

    private fun updateCurrent(
        sessionId: String,
        pairId: String,
        transform: (ScenarioUnitView) -> ScenarioUnitView,
    ) {
        val flow = sessions.getValue(sessionId)
        require(flow.value.status == SessionStatus.IN_PROGRESS)
        require(flow.value.unit.pairId == pairId) { "Reveal action is stale" }
        flow.value = transform(flow.value)
        latest.value = latest.value?.copy(updatedAt = flow.value.progress.updatedAt)
    }

    private fun contentFor(sessionId: String, pairId: String): PairContent {
        val scenarioId = sessionScenarioIds.getValue(sessionId)
        return contents.getValue(scenarioId).first { it.pairId == pairId }
    }

    private fun concealedView(
        sessionId: String,
        scenarioId: String,
        pair: PairContent,
        pairIndex: Int,
        now: Instant,
    ): ScenarioUnitView = ScenarioUnitView(
        sessionId = sessionId,
        status = SessionStatus.IN_PROGRESS,
        unit = ScenarioPracticeUnit(
            scenarioId = scenarioId,
            pairId = pair.pairId,
            pairIndex = pairIndex,
            pairCount = contents.getValue(scenarioId).size,
            customerAudioAssetId = pair.audioAssetId,
            customerText = CustomerTextView.Concealed,
            keywords = KeywordsView.Concealed,
            answer = ReferenceAnswerView.Concealed,
        ),
        progress = ScenarioTurnProgress(
            sessionId = sessionId,
            pairId = pair.pairId,
            customerAudioCompleted = false,
            customerTextRevealed = false,
            keywordsRevealed = false,
            answerRevealed = false,
            selfRating = null,
            updatedAt = now,
        ),
    )

    private fun sampleScenarios() = listOf(
        ScenarioSummary(
            id = "S001",
            title = "首次技术交流：储能方案介绍",
            topic = "方案介绍",
            salesStage = "初步接触",
            customerRole = "项目技术负责人",
            difficulty = "B1–B2",
            projectType = "光储项目",
            estimatedMinutes = 8,
            status = null,
        )
    )

    private fun samplePairs() = mapOf(
        "S001" to listOf(
            PairContent(
                pairId = "S001_P001",
                customerEnglish = "How does your system maintain stable output during long-duration discharge?",
                customerChinese = "长时放电过程中，你们的系统如何保持出力稳定？",
                keywords = listOf(
                    Keyword("V_001", "round-trip efficiency", "/ˌraʊnd ˈtrɪp ɪˈfɪʃənsi/", "往返效率")
                ),
                answer = ReferenceAnswer(
                    coreEnglish = "The control system coordinates the stacks and power conversion system throughout discharge.",
                    chineseHint = "说明控制系统如何协调电堆与变流器。",
                    formalAlternatives = listOf(
                        "The supervisory controller maintains coordinated operation throughout the discharge window.",
                        "Our control architecture keeps the battery stacks and PCS synchronized.",
                    ),
                ),
                audioAssetId = "audio_customer_s001_p001",
            ),
            PairContent(
                pairId = "S001_P002",
                customerEnglish = "What information do you need before you prepare the technical proposal?",
                customerChinese = "准备技术方案之前，你们需要哪些信息？",
                keywords = listOf(
                    Keyword("V_002", "auxiliary consumption", "/ɔːɡˈzɪliəri kənˈsʌmpʃən/", "辅助用电")
                ),
                answer = ReferenceAnswer(
                    coreEnglish = "We need the load profile, grid conditions, site constraints, and operating strategy.",
                    chineseHint = "列出负荷、电网、场地及运行策略信息。",
                    formalAlternatives = listOf(
                        "Please provide the load profile and point-of-connection requirements.",
                        "Site constraints and the intended operating strategy are also required.",
                    ),
                ),
                audioAssetId = "audio_customer_s001_p002",
            ),
        )
    )
}
