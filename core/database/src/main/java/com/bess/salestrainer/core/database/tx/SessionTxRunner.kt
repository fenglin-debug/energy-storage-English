package com.bess.salestrainer.core.database.tx

import androidx.room.withTransaction
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.ReviewActionKeyEntity
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.ScenarioTurnProgressEntity
import com.bess.salestrainer.core.database.entity.VocabularySessionCheckpointEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity

/**
 * Atomic vocabulary review transaction (TDD §5.3):
 * idempotency key + review log + FSRS memory + study-task counter + checkpoint
 * advance must commit together. A crash mid-way leaves no partial state.
 */
class ReviewTxRunner(
    private val db: BessDatabase,
) {
    data class ReviewTx(
        val actionKey: ReviewActionKeyEntity,
        val reviewLog: ReviewLogEntity,
        val memoryState: WordMemoryStateEntity,
        val taskDateEpochDay: Long,
        val isNewWord: Boolean,
        val newWordTarget: Int,
        val reviewTarget: Int,
        val advancedCheckpoint: VocabularySessionCheckpointEntity,
    )

    /** @return false when the same (sessionId, currentIndex) action was already committed. */
    suspend fun recordReviewAtomic(tx: ReviewTx): Boolean = db.withTransaction {
        val vocabDao = db.vocabularyDao()
        if (vocabDao.insertActionKey(tx.actionKey) == -1L) {
            return@withTransaction false // duplicate tap: silently ignore
        }
        vocabDao.insertReviewLog(tx.reviewLog)
        vocabDao.upsertMemoryState(tx.memoryState)
        vocabDao.upsertCheckpoint(tx.advancedCheckpoint)

        val taskDao = db.studyTaskDao()
        val existing = taskDao.getByDate(tx.taskDateEpochDay)
        val now = tx.reviewLog.reviewedAtEpochMs
        val updated = if (existing == null) {
            com.bess.salestrainer.core.database.entity.StudyTaskEntity(
                dateEpochDay = tx.taskDateEpochDay,
                newWordTarget = tx.newWordTarget,
                newWordDone = if (tx.isNewWord) 1 else 0,
                reviewTarget = tx.reviewTarget,
                reviewDone = if (tx.isNewWord) 0 else 1,
                recommendedScenarioId = null,
                studySeconds = 0,
                completed = false,
                updatedAtEpochMs = now,
            )
        } else {
            existing.copy(
                newWordDone = existing.newWordDone + if (tx.isNewWord) 1 else 0,
                reviewDone = existing.reviewDone + if (tx.isNewWord) 0 else 1,
                updatedAtEpochMs = now,
            )
        }
        taskDao.upsert(updated)
        true
    }
}

/**
 * Atomic scenario advance transaction (TDD §5.3):
 * turn progress (self rating persisted) + session advance/complete commit
 * together. Rating is rejected unless the reference answer has been revealed.
 */
class ScenarioTxRunner(
    private val db: BessDatabase,
) {
    data class RateTx(
        val ratedProgress: ScenarioTurnProgressEntity,
        val updatedSession: ScenarioSessionEntity,
        val taskDateEpochDay: Long,
        val completed: Boolean,
        val studySecondsDelta: Long = 0,
        /** D3: optional FSRS pair-memory write committed in the same transaction. */
        val pairMemoryState: com.bess.salestrainer.core.database.entity.ItemMemoryStateEntity? = null,
        val reviewLog: ReviewLogEntity? = null,
        val nextProgress: ScenarioTurnProgressEntity? = null,
    )

    suspend fun rateAndAdvanceAtomic(tx: RateTx) = db.withTransaction {
        db.scenarioDao().upsertTurnProgress(tx.ratedProgress)
        db.scenarioDao().upsertSession(tx.updatedSession)
        tx.pairMemoryState?.let { db.itemMemoryDao().upsert(it) }
        tx.reviewLog?.let { db.vocabularyDao().insertReviewLog(it) }
        tx.nextProgress?.let { db.scenarioDao().upsertTurnProgress(it) }

        if (tx.completed && tx.studySecondsDelta > 0) {
            val taskDao = db.studyTaskDao()
            taskDao.getByDate(tx.taskDateEpochDay)?.let { existing ->
                taskDao.upsert(
                    existing.copy(
                        studySeconds = existing.studySeconds + tx.studySecondsDelta,
                        updatedAtEpochMs = tx.updatedSession.updatedAtEpochMs,
                    ),
                )
            }
        }
    }
}
