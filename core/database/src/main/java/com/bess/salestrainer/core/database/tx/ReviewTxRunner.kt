package com.bess.salestrainer.core.database.tx

import androidx.room.withTransaction
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.StudyTaskEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity

/**
 * Atomic vocabulary review transaction (TDD §5, FR-VOC-003, AC-02):
 * ReviewLog insert + WordMemoryState upsert + StudyTask counter update must
 * commit together — UI only advances after this returns. A crash mid-way leaves no partial state.
 */
class ReviewTxRunner(
    private val db: BessDatabase,
) {
    data class ReviewTx(
        val reviewLog: ReviewLogEntity,
        val memoryState: WordMemoryStateEntity,
        val taskDateEpochDay: Long,
        val isNewWord: Boolean,
    )

    suspend fun recordReviewAtomic(tx: ReviewTx) = db.withTransaction {
        db.vocabularyDao().insertReviewLog(tx.reviewLog)
        db.vocabularyDao().upsertMemoryState(tx.memoryState)

        val taskDao = db.studyTaskDao()
        val existing = taskDao.getByDate(tx.taskDateEpochDay)
        val updated = if (existing == null) {
            StudyTaskEntity(
                dateEpochDay = tx.taskDateEpochDay,
                newWordTarget = 0, newWordDone = if (tx.isNewWord) 1 else 0,
                reviewTarget = 0, reviewDone = if (tx.isNewWord) 0 else 1,
                recommendedScenarioId = null, studySeconds = 0,
                completed = false, updatedAtEpochMs = tx.reviewLog.reviewedAtEpochMs,
            )
        } else {
            existing.copy(
                newWordDone = existing.newWordDone + if (tx.isNewWord) 1 else 0,
                reviewDone = existing.reviewDone + if (tx.isNewWord) 0 else 1,
                updatedAtEpochMs = tx.reviewLog.reviewedAtEpochMs,
            )
        }
        taskDao.upsert(updated)
    }
}
