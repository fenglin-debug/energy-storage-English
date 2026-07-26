package com.bess.salestrainer.core.data.repository

import com.bess.salestrainer.core.data.mapper.modeEnum
import com.bess.salestrainer.core.data.mapper.toSummary
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.StudyTaskEntity
import com.bess.salestrainer.core.model.ResumeTarget
import com.bess.salestrainer.core.model.ScenarioSessionSummary
import com.bess.salestrainer.core.model.TodayStudyTask
import com.bess.salestrainer.core.model.contract.StudyTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Room-backed [StudyTaskRepository]. Aggregates the daily dashboard from
 * study_tasks rows plus live due counts, and exposes the resume anchor
 * (FR-HOME-002): an in-progress scenario wins; otherwise the next due word.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudyTaskRepositoryImpl(
    private val db: BessDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val dailyNewWordTarget: Int = 15,
    private val dailyReviewTarget: Int = 60,
) : StudyTaskRepository {

    private val taskDao get() = db.studyTaskDao()
    private val vocabDao get() = db.vocabularyDao()
    private val scenarioDao get() = db.scenarioDao()

    private fun todayEpochDay(): Long = LocalDate.now(zoneId).toEpochDay()

    override fun observeTodayTask(): Flow<TodayStudyTask> {
        val today = todayEpochDay()
        val weekAgo = today - 6
        return combine(
            taskDao.observeByDate(today),
            kotlinx.coroutines.flow.flow { emit(taskDao.getSince(weekAgo)) },
        ) { task, week -> task to week }
            .flatMapLatest { (task, week) ->
                recommendedScenarioFlow(task?.recommendedScenarioId).map { recommended ->
                    val activeDays = week
                        .filter { it.newWordDone + it.reviewDone > 0 }
                        .map { it.dateEpochDay }
                        .toSet()
                    TodayStudyTask(
                        date = LocalDate.ofEpochDay(today),
                        newWordTarget = task?.newWordTarget ?: dailyNewWordTarget,
                        newWordDone = task?.newWordDone ?: 0,
                        reviewTarget = task?.reviewTarget ?: dailyReviewTarget,
                        reviewDone = task?.reviewDone ?: 0,
                        recommendedScenario = recommended,
                        studySecondsToday = task?.studySeconds ?: 0L,
                        streakDays = computeStreak(activeDays, today),
                        last7DaysActive = (0L..6L).map { (today - it) in activeDays }.reversed(),
                        completed = task?.completed ?: false,
                    )
                }
            }
    }

    override fun observeResumeTarget(): Flow<ResumeTarget?> =
        scenarioDao.observeLatestInProgress().flatMapLatest { session ->
            if (session == null) {
                // No in-progress scenario -> vocabulary resume from the due queue.
                return@flatMapLatest combine(
                    vocabDao.observeDueReviews(System.currentTimeMillis(), 1),
                    vocabDao.observeDueCount(System.currentTimeMillis()),
                ) { due, count ->
                    val next = due.firstOrNull()
                    if (next == null) null
                    else ResumeTarget.VocabularyResume(
                        nextWordId = next.id,
                        remainingCount = count,
                        updatedAt = Instant.now(),
                    ) as ResumeTarget?
                }
            }
            scenarioDao.observeTurns(session.scenarioId).map { turns ->
                val title = scenarioDao.getById(session.scenarioId)?.title ?: ""
                ResumeTarget.ScenarioResume(
                    session = ScenarioSessionSummary(
                        sessionId = session.id,
                        scenarioId = session.scenarioId,
                        scenarioTitle = title,
                        mode = session.modeEnum(),
                        currentCustomerTurnNo = session.currentCustomerTurnNo,
                        totalCustomerTurns = turns.count { it.speaker == "CUSTOMER" },
                        updatedAt = Instant.ofEpochMilli(session.updatedAtEpochMs),
                    ),
                    updatedAt = Instant.ofEpochMilli(session.updatedAtEpochMs),
                ) as ResumeTarget?
            }
        }

    override suspend fun refreshTodayTask() {
        val today = todayEpochDay()
        val now = System.currentTimeMillis()
        if (taskDao.getByDate(today) == null) {
            taskDao.upsert(
                StudyTaskEntity(
                    dateEpochDay = today,
                    newWordTarget = dailyNewWordTarget,
                    newWordDone = 0,
                    reviewTarget = dailyReviewTarget,
                    reviewDone = 0,
                    recommendedScenarioId = null, // filled by Track B home logic later
                    studySeconds = 0L,
                    completed = false,
                    updatedAtEpochMs = now,
                ),
            )
        }
    }

    // ------------------------------------------------------------------

    private fun recommendedScenarioFlow(scenarioId: String?) =
        kotlinx.coroutines.flow.flow { emit(scenarioId?.let { scenarioDao.getById(it) }) }
            .map { it?.toSummary(completed = false, bestScore = null, lastScore = null) }

    private fun computeStreak(activeDays: Set<Long>, today: Long): Int {
        var streak = 0
        var day = today
        if (day !in activeDays) day -= 1 // today may be unfinished; anchor to yesterday
        while (day in activeDays) {
            streak++
            day -= 1
        }
        return streak
    }
}
