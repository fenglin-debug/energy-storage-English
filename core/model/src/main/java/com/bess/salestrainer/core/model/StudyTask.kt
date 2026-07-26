package com.bess.salestrainer.core.model

import java.time.Instant
import java.time.LocalDate

/** Today's study task dashboard (FR-HOME-001/003). */
data class TodayStudyTask(
    val date: LocalDate,
    val newWordTarget: Int,
    val newWordDone: Int,
    val reviewTarget: Int,
    val reviewDone: Int,
    val recommendedScenario: ScenarioSummary?,
    val studySecondsToday: Long,
    val streakDays: Int,
    val last7DaysActive: List<Boolean>,
    val completed: Boolean,
)

/** Resume entry point for interrupted learning (FR-HOME-002). */
sealed interface ResumeTarget {
    val updatedAt: Instant

    data class VocabularyResume(
        val nextWordId: String,
        val remainingCount: Int,
        override val updatedAt: Instant,
    ) : ResumeTarget

    data class ScenarioResume(
        val session: ScenarioSessionSummary,
        override val updatedAt: Instant,
    ) : ResumeTarget
}
