package com.bess.salestrainer.core.database.tx

import androidx.room.withTransaction
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.TurnAttemptEntity

/**
 * Atomic scenario session transaction (TDD §5, FR-PERSIST-001, AC-03):
 * accepting an attempt clears other accepted flags for the same turn, inserts the
 * new attempt, and advances the session pointer — all in one transaction.
 */
class SessionTxRunner(
    private val db: BessDatabase,
) {
    data class AcceptAttemptTx(
        val attempt: TurnAttemptEntity,
        val updatedSession: ScenarioSessionEntity,
    )

    suspend fun acceptAttemptAtomic(tx: AcceptAttemptTx) = db.withTransaction {
        val scenarioDao = db.scenarioDao()
        // Only one accepted attempt per (sessionId, turnNo).
        scenarioDao.clearAcceptedForTurn(tx.attempt.sessionId, tx.attempt.turnNo)
        scenarioDao.upsertAttempt(tx.attempt.copy(accepted = true))
        scenarioDao.upsertSession(tx.updatedSession)
    }
}
