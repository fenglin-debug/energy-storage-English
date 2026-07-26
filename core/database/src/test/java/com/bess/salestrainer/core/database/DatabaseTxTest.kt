package com.bess.salestrainer.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.StudyTaskEntity
import com.bess.salestrainer.core.database.entity.TurnAttemptEntity
import com.bess.salestrainer.core.database.entity.VocabularyEntryEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import com.bess.salestrainer.core.database.tx.ReviewTxRunner
import com.bess.salestrainer.core.database.tx.SessionTxRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DAO and transaction-runner tests against an in-memory Room database.
 *
 * Covers the two atomic invariants from TDD §5:
 *  - AC-02: review log + memory state + study task commit together (ReviewTxRunner)
 *  - AC-03: exactly one accepted attempt per (sessionId, turnNo) (SessionTxRunner)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DatabaseTxTest {

    private lateinit var db: BessDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BessDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    // ---------------------------------------------------------------
    // VocabularyDao basics
    // ---------------------------------------------------------------

    @Test
    fun newWordsExcludeThoseWithMemoryState() = runTest {
        val dao = db.vocabularyDao()
        dao.upsertAll(listOf(vocab("w1", "battery"), vocab("w2", "inverter")))
        assertEquals(2, dao.activeCount())

        // w1 has a memory state but is NOT yet due; w2 has no memory state.
        dao.upsertMemoryState(memoryState("w1").copy(dueAtEpochMs = 999_999L))
        assertNotNull(dao.getMemoryState("w1"))
        assertNull(dao.getMemoryState("w2"))

        // Only w1 has a memory state, and it is not due yet -> due count is 0.
        dao.observeDueCount(nowEpochMs = 1000L).test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // Make w1 due -> due count becomes 1.
        dao.upsertMemoryState(memoryState("w1").copy(dueAtEpochMs = 500L))
        dao.observeDueCount(nowEpochMs = 1000L).test {
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun reviewLogIsAppendOnly() = runTest {
        val dao = db.vocabularyDao()
        dao.upsert(vocab("w1", "battery"))
        dao.insertReviewLog(reviewLog("r1", "w1", 1000L))
        dao.insertReviewLog(reviewLog("r2", "w1", 2000L))
        assertEquals(2, dao.reviewLogCountForWord("w1"))
    }

    // ---------------------------------------------------------------
    // ReviewTxRunner: AC-02 atomicity
    // ---------------------------------------------------------------

    @Test
    fun recordReviewAtomicCommitsAllThreeWrites() = runTest {
        val dao = db.vocabularyDao()
        dao.upsert(vocab("w1", "battery"))

        val tx = ReviewTxRunner.ReviewTx(
            reviewLog = reviewLog("r1", "w1", 1000L),
            memoryState = memoryState("w1", reps = 1),
            taskDateEpochDay = 19723, // 2024-01-01
            isNewWord = true,
        )
        ReviewTxRunner(db).recordReviewAtomic(tx)

        assertEquals(1, dao.reviewLogCountForWord("w1"))
        assertEquals(1, dao.getMemoryState("w1")!!.reps)

        val task = db.studyTaskDao().getByDate(19723)!!
        assertEquals(1, task.newWordDone)
        assertEquals(0, task.reviewDone)
    }

    @Test
    fun recordReviewAtomicIncrementsExistingTask() = runTest {
        val dao = db.vocabularyDao()
        dao.upsert(vocab("w1", "battery"))
        dao.upsert(vocab("w2", "inverter"))

        val runner = ReviewTxRunner(db)
        runner.recordReviewAtomic(
            ReviewTxRunner.ReviewTx(reviewLog("r1", "w1", 1000L), memoryState("w1", 1), 19723, isNewWord = true)
        )
        runner.recordReviewAtomic(
            ReviewTxRunner.ReviewTx(reviewLog("r2", "w2", 2000L), memoryState("w2", 1), 19723, isNewWord = false)
        )

        val task = db.studyTaskDao().getByDate(19723)!!
        assertEquals(1, task.newWordDone)
        assertEquals(1, task.reviewDone)
    }

    // ---------------------------------------------------------------
    // SessionTxRunner: AC-03 single accepted attempt
    // ---------------------------------------------------------------

    @Test
    fun acceptAttemptAtomicKeepsSingleAcceptedPerTurn() = runTest {
        val scenarioDao = db.scenarioDao()
        val session = sessionEntity("s1", turnNo = 1)
        scenarioDao.upsertSession(session)

        val runner = SessionTxRunner(db)
        // First attempt accepted
        runner.acceptAttemptAtomic(
            SessionTxRunner.AcceptAttemptTx(
                attempt = attempt("a1", "s1", turnNo = 1, accepted = false, createdAt = 1000L),
                updatedSession = session.copy(currentCustomerTurnNo = 2, updatedAtEpochMs = 1000L),
            )
        )
        // Second attempt for the SAME turn supersedes the first
        runner.acceptAttemptAtomic(
            SessionTxRunner.AcceptAttemptTx(
                attempt = attempt("a2", "s1", turnNo = 1, accepted = false, createdAt = 2000L),
                updatedSession = session.copy(currentCustomerTurnNo = 2, updatedAtEpochMs = 2000L),
            )
        )

        val attempts = scenarioDao.getAttempts("s1")
        assertEquals(2, attempts.size)
        assertEquals(1, attempts.count { it.accepted })
        assertTrue(attempts.first { it.id == "a2" }.accepted)
        assertTrue(!attempts.first { it.id == "a1" }.accepted)
    }

    // ---------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------

    private fun vocab(id: String, term: String) = VocabularyEntryEntity(
        id = id, term = term, normalizedTerm = term.lowercase(),
        ipa = null, partOfSpeech = "n.", chineseGloss = "释义",
        englishDefinition = null, collocationsJson = "[]",
        exampleSentenceEn = null, exampleSentenceZh = null, commonMistakes = null,
        topic = "general", scenarioTagsJson = "[]", cefrLevel = "B1",
        audioRef = null, exampleAudioRef = null,
        contentSource = "CORE", contentVersion = "1.0.0", aliasOf = null, active = true,
    )

    private fun memoryState(wordId: String, reps: Int = 0) = WordMemoryStateEntity(
        wordId = wordId, fsrsState = "LEARNING", difficulty = 5.0, stability = 1.0,
        dueAtEpochMs = 0L, lastReviewAtEpochMs = null, reps = reps, lapses = 0,
        masteredUi = false, lastQuestionMode = null, isFavorite = false, updatedAtEpochMs = 0L,
    )

    private fun reviewLog(id: String, wordId: String, at: Long) = ReviewLogEntity(
        id = id, wordId = wordId, rating = "GOOD", questionMode = "EN2ZH",
        usedHint = false, revealedAnswer = false, reviewedAtEpochMs = at,
        responseTimeMs = null, scheduledDays = 1, elapsedDays = 0,
        stateBefore = "LEARNING", stateAfter = "REVIEW",
    )

    private fun sessionEntity(id: String, turnNo: Int) = ScenarioSessionEntity(
        id = id, scenarioId = "sc1", mode = "PRACTICE", status = "IN_PROGRESS",
        currentCustomerTurnNo = turnNo, localScoreJson = null,
        aiStatus = "NOT_REQUESTED", aiEvaluationId = null,
        startedAtEpochMs = 0L, completedAtEpochMs = null, updatedAtEpochMs = 0L,
    )

    private fun attempt(id: String, sessionId: String, turnNo: Int, accepted: Boolean, createdAt: Long) =
        TurnAttemptEntity(
            id = id, sessionId = sessionId, turnNo = turnNo, accepted = accepted,
            rawTranscript = "hello", editedTranscript = null,
            wpm = null, pauseRatio = null, maxPauseMs = null, fillerCount = null,
            keywordCoverage = null, audioFileRef = null, createdAtEpochMs = createdAt,
        )
}
