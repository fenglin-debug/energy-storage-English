package com.bess.salestrainer.core.model.fake

import com.bess.salestrainer.core.model.ResumeTarget
import com.bess.salestrainer.core.model.ScenarioSummary
import com.bess.salestrainer.core.model.TodayStudyTask
import com.bess.salestrainer.core.model.contract.StudyTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate

/** In-memory fake for today's task + resume target. */
class FakeStudyTaskRepository : StudyTaskRepository {

    private val today = MutableStateFlow(
        TodayStudyTask(
            date = LocalDate.now(),
            newWordTarget = 15,
            newWordDone = 3,
            reviewTarget = 12,
            reviewDone = 5,
            recommendedScenario = ScenarioSummary(
                id = "S_001", title = "首次技术交流：集装箱储能方案介绍",
                topic = "方案介绍", salesStage = "初步接触", difficulty = "中等",
                estimatedMinutes = 8, completed = false, bestScore = null, lastScore = null,
            ),
            studySecondsToday = 540,
            streakDays = 4,
            last7DaysActive = listOf(true, true, false, true, true, true, false),
            completed = false,
        )
    )

    private val resume = MutableStateFlow<ResumeTarget?>(
        ResumeTarget.VocabularyResume(nextWordId = "V_001", remainingCount = 10, updatedAt = Instant.now())
    )

    override fun observeTodayTask(): Flow<TodayStudyTask> = today

    override fun observeResumeTarget(): Flow<ResumeTarget?> = resume

    override suspend fun refreshTodayTask() {
        // Fake: recompute is a no-op; emit current value to trigger collectors.
        today.value = today.value
    }
}
