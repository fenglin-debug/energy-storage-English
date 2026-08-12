package com.bess.salestrainer.core.data.repository

import com.bess.salestrainer.core.data.mapper.toSummary
import com.bess.salestrainer.core.data.mapper.toStringList
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.StudyTaskEntity
import com.bess.salestrainer.core.model.ResumeTarget
import com.bess.salestrainer.core.model.SessionStatus
import com.bess.salestrainer.core.model.TodayStudyTask
import com.bess.salestrainer.core.model.contract.StudyTaskRepository
import com.bess.salestrainer.core.model.contract.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Room-backed [StudyTaskRepository]. Aggregates the daily dashboard and
 * exposes the resume anchor (TDD §9.3): the most recently updated active
 * vocabulary or scenario session wins — no fixed priority between the two.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudyTaskRepositoryImpl(
    private val db: BessDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val dailyNewWordTarget: Int = 15,
    private val settingsRepository: SettingsRepository? = null,
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
            flowOf(Unit).flatMapLatest { flowOf(taskDao.getSince(weekAgo)) },
            settingsRepository?.observeSettings() ?: flowOf(
                com.bess.salestrainer.core.model.AppSettings(
                    dailyNewWordTarget = dailyNewWordTarget,
                ),
            ),
        ) { task, week, settings ->
            val activeDays = week
                .filter { it.newWordDone + it.reviewDone > 0 }
                .map { it.dateEpochDay }
                .toSet()
            val recommended = task?.recommendedScenarioId?.let { id ->
                scenarioDao.getById(id)?.toSummary(status = null)
            }
            TodayStudyTask(
                date = LocalDate.ofEpochDay(today),
                newWordTarget = task?.newWordTarget ?: settings.dailyNewWordTarget,
                newWordDone = task?.newWordDone ?: 0,
                reviewTarget = task?.reviewTarget ?: 0,
                reviewDone = task?.reviewDone ?: 0,
                recommendedScenario = recommended,
                studySecondsToday = task?.studySeconds ?: 0L,
                streakDays = computeStreak(activeDays, today),
                last7DaysActive = (0L..6L).map { (today - it) in activeDays }.reversed(),
                completed = task?.completed ?: false,
            )
        }
    }

    override fun observeResumeTarget(): Flow<ResumeTarget?> =
        combine(
            vocabDao.observeLatestInProgressCheckpoint(),
            scenarioDao.observeLatestInProgress(),
        ) { vocabCheckpoint, scenarioSession ->
            val vocabUpdated = vocabCheckpoint?.updatedAtEpochMs ?: Long.MIN_VALUE
            val scenarioUpdated = scenarioSession?.updatedAtEpochMs ?: Long.MIN_VALUE
            when {
                vocabCheckpoint == null && scenarioSession == null -> null
                vocabUpdated >= scenarioUpdated -> {
                    val checkpoint = vocabCheckpoint!!
                    val queueIds = checkpoint.queueWordIdsJson.toStringList()
                    ResumeTarget.VocabularyResume(
                        sessionId = checkpoint.sessionId,
                        currentWordId = queueIds.getOrNull(checkpoint.currentIndex).orEmpty(),
                        remainingCount = (queueIds.size - checkpoint.currentIndex).coerceAtLeast(0),
                        updatedAt = Instant.ofEpochMilli(vocabUpdated),
                    )
                }
                else -> {
                    val session = scenarioSession!!
                    val title = scenarioDao.getById(session.scenarioId)?.title ?: session.scenarioId
                    ResumeTarget.ScenarioResume(
                        session = session.toSummary(title).copy(status = SessionStatus.IN_PROGRESS),
                        updatedAt = Instant.ofEpochMilli(scenarioUpdated),
                    )
                }
            }
        }

    override suspend fun refreshTodayTask() {
        val today = todayEpochDay()
        val now = System.currentTimeMillis()
        val target = settingsRepository?.observeSettings()?.first()?.dailyNewWordTarget
            ?: dailyNewWordTarget
        val existing = taskDao.getByDate(today)
        if (existing == null) {
            taskDao.upsert(
                StudyTaskEntity(
                    dateEpochDay = today,
                    newWordTarget = target,
                    newWordDone = 0,
                    reviewTarget = 0, // all due reviews, computed live
                    reviewDone = 0,
                    recommendedScenarioId = null,
                    studySeconds = 0L,
                    completed = false,
                    updatedAtEpochMs = now,
                ),
            )
        } else if (existing.newWordTarget != target) {
            taskDao.upsert(
                existing.copy(
                    newWordTarget = target,
                    updatedAtEpochMs = now,
                ),
            )
        }
    }

    // ------------------------------------------------------------------

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
