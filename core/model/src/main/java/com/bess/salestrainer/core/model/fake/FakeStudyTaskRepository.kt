package com.bess.salestrainer.core.model.fake

import com.bess.salestrainer.core.model.ResumeTarget
import com.bess.salestrainer.core.model.ScenarioSummary
import com.bess.salestrainer.core.model.TodayStudyTask
import com.bess.salestrainer.core.model.contract.StudyTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate

class FakeStudyTaskRepository : StudyTaskRepository {
    private val today = MutableStateFlow(
        TodayStudyTask(
            date = LocalDate.now(),
            newWordTarget = 15,
            newWordDone = 3,
            reviewTarget = 12,
            reviewDone = 5,
            recommendedScenario = ScenarioSummary(
                id = "S001",
                title = "首次技术交流：储能方案介绍",
                topic = "方案介绍",
                salesStage = "初步接触",
                customerRole = "项目技术负责人",
                difficulty = "B1–B2",
                projectType = "光储项目",
                estimatedMinutes = 8,
                status = null,
            ),
            studySecondsToday = 540,
            streakDays = 4,
            last7DaysActive = listOf(true, true, false, true, true, true, false),
            completed = false,
        )
    )
    private val resume = MutableStateFlow<ResumeTarget?>(
        ResumeTarget.VocabularyResume(
            sessionId = "vocab_fake",
            currentWordId = "V_001",
            remainingCount = 10,
            updatedAt = Instant.now(),
        )
    )

    override fun observeTodayTask(): Flow<TodayStudyTask> = today

    override fun observeResumeTarget(): Flow<ResumeTarget?> = resume

    override suspend fun refreshTodayTask() {
        today.value = today.value
    }
}
