package com.bess.salestrainer.core.model

import java.time.Instant

data class ScenarioSummary(
    val id: String,
    val title: String,
    val topic: String,
    val salesStage: String,
    val customerRole: String,
    val difficulty: String,
    val projectType: String,
    val estimatedMinutes: Int,
    val status: SessionStatus?,
    val latestRatingSummary: Map<DialogueSelfRating, Int> = emptyMap(),
    /** Number of this scenario's dialogue pairs due for FSRS review now. */
    val duePairCount: Int = 0,
)

data class ScenarioFilter(
    val topic: String? = null,
    val salesStage: String? = null,
    val difficulty: String? = null,
    val onlyIncomplete: Boolean = false,
)

sealed interface CustomerTextView {
    data object Concealed : CustomerTextView
    data class Revealed(val english: String) : CustomerTextView
}

data class Keyword(
    val vocabularyId: String,
    val english: String,
    val ipa: String,
    val chineseGloss: String,
)

sealed interface KeywordsView {
    data object Concealed : KeywordsView
    data class Revealed(val values: List<Keyword>) : KeywordsView
}

/** A checklist item shown with the revealed reference answer (FR-SCN-06). */
data class ScoringPoint(
    val id: String,
    val type: String,
    val descriptionZh: String,
    val keywordsEn: String,
    val required: Boolean,
    val weight: Int,
)

data class ReferenceAnswer(
    val coreEnglish: String,
    val chineseHint: String,
    val formalAlternatives: List<String>,
    val scoringPoints: List<ScoringPoint> = emptyList(),
)

sealed interface ReferenceAnswerView {
    data object Concealed : ReferenceAnswerView
    data class Revealed(val value: ReferenceAnswer) : ReferenceAnswerView
}

data class ScenarioPracticeUnit(
    val scenarioId: String,
    val pairId: String,
    val pairIndex: Int,
    val pairCount: Int,
    val customerAudioAssetId: String,
    val customerText: CustomerTextView,
    val keywords: KeywordsView,
    val answer: ReferenceAnswerView,
)

data class ScenarioTurnProgress(
    val sessionId: String,
    val pairId: String,
    val customerAudioCompleted: Boolean,
    val customerTextRevealed: Boolean,
    val keywordsRevealed: Boolean,
    val answerRevealed: Boolean,
    val selfRating: DialogueSelfRating?,
    val updatedAt: Instant,
)

data class ScenarioUnitView(
    val sessionId: String,
    val status: SessionStatus,
    val unit: ScenarioPracticeUnit,
    val progress: ScenarioTurnProgress,
)

data class ScenarioSessionSummary(
    val sessionId: String,
    val scenarioId: String,
    val scenarioTitle: String,
    val status: SessionStatus,
    val currentPairIndex: Int,
    val pairCount: Int,
    val updatedAt: Instant,
    val practiceMode: ScenarioPracticeMode = ScenarioPracticeMode.SCENARIO,
)

enum class ScenarioPracticeMode { SCENARIO, RANDOM }

sealed interface ScenarioAdvance {
    data class NextPair(
        val pairId: String,
        val pairIndex: Int,
        val pairCount: Int,
    ) : ScenarioAdvance

    data class Completed(
        val sessionId: String,
        val ratingDistribution: Map<DialogueSelfRating, Int>,
    ) : ScenarioAdvance
}
