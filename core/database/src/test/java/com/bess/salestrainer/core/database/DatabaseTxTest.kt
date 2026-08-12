package com.bess.salestrainer.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.bess.salestrainer.core.database.entity.ReviewActionKeyEntity
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.ScenarioTurnProgressEntity
import com.bess.salestrainer.core.database.entity.VocabularyEntryEntity
import com.bess.salestrainer.core.database.entity.VocabularySessionCheckpointEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import com.bess.salestrainer.core.database.tx.ReviewTxRunner
import com.bess.salestrainer.core.database.tx.ScenarioTxRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DAO and transaction-runner tests against an in-memory Room v2 database.
 *
 * Covers the atomic invariants from TDD §5.3:
 *  - review log + memory state + study task + checkpoint commit together
 *  - (sessionId, currentIndex) idempotency key rejects double submission
 *  - scenario rating + session advance commit together
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

        dao.upsertMemoryState(memoryState("w1").copy(dueAtEpochMs = 999_999L))
        assertNotNull(dao.getMemoryState("w1"))
        assertNull(dao.getMemoryState("w2"))

        dao.observeDueCount(nowEpochMs = 1000L).test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        dao.upsertMemoryState(memoryState("w1", reps = 1).copy(dueAtEpochMs = 500L))
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
    // ReviewTxRunner: atomicity + idempotency
    // ---------------------------------------------------------------

    @Test
    fun recordReviewAtomicCommitsAllWrites() = runTest {
        val dao = db.vocabularyDao()
        dao.upsert(vocab("w1", "battery"))
        dao.upsertCheckpoint(checkpoint("vs1", index = 0))

        val committed = ReviewTxRunner(db).recordReviewAtomic(reviewTx("vs1", index = 0))
        assertTrue(committed)

        assertEquals(1, dao.reviewLogCountForWord("w1"))
        assertEquals(1, dao.getMemoryState("w1")!!.reps)
        assertEquals(1, dao.getCheckpoint("vs1")!!.currentIndex)

        val task = db.studyTaskDao().getByDate(19723)!!
        assertEquals(1, task.newWordDone)
        assertEquals(0, task.reviewDone)
    }

    @Test
    fun recordReviewAtomicRejectsDuplicateActionKey() = runTest {
        val dao = db.vocabularyDao()
        dao.upsert(vocab("w1", "battery"))
        dao.upsertCheckpoint(checkpoint("vs1", index = 0))

        val runner = ReviewTxRunner(db)
        assertTrue(runner.recordReviewAtomic(reviewTx("vs1", index = 0)))
        // Same (sessionId, currentIndex) submitted again (double tap).
        assertFalse(runner.recordReviewAtomic(reviewTx("vs1", index = 0)))

        assertEquals(1, dao.reviewLogCountForWord("w1"))
        assertEquals(1, dao.getMemoryState("w1")!!.reps)
    }

    // ---------------------------------------------------------------
    // ScenarioTxRunner: rating + advance commit together
    // ---------------------------------------------------------------

    @Test
    fun rateAndAdvanceAtomicUpdatesProgressAndSession() = runTest {
        val scenarioDao = db.scenarioDao()
        scenarioDao.upsertSession(scenarioSession("ss1", pairIndex = 0, pairCount = 2))
        scenarioDao.upsertTurnProgress(progress("ss1", "S001_P001").copy(answerRevealed = true))

        ScenarioTxRunner(db).rateAndAdvanceAtomic(
            ScenarioTxRunner.RateTx(
                ratedProgress = progress("ss1", "S001_P001").copy(
                    answerRevealed = true,
                    selfRating = "BASIC",
                    updatedAtEpochMs = 2_000L,
                ),
                updatedSession = scenarioSession("ss1", pairIndex = 1, pairCount = 2)
                    .copy(currentPairId = "S001_P002", updatedAtEpochMs = 2_000L),
                taskDateEpochDay = 19723,
                completed = false,
            ),
        )

        val p = scenarioDao.getTurnProgress("ss1", "S001_P001")!!
        assertEquals("BASIC", p.selfRating)
        val s = scenarioDao.getSession("ss1")!!
        assertEquals(1, s.currentPairIndex)
        assertEquals("S001_P002", s.currentPairId)
    }

    // ---------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------

    private fun vocab(id: String, term: String) = VocabularyEntryEntity(
        id = id, term = term, normalizedTerm = term.lowercase(),
        ipa = "/x/", partOfSpeech = "n.", chineseGloss = "释义",
        englishDefinition = null, collocationsJson = "[]",
        exampleSentenceEn = "Example for $term.", exampleSentenceZh = null,
        commonMistakes = "None.", topic = "general", scenarioTagsJson = "[]",
        cefrLevel = "B1", wordAudioAssetId = "aud_$id", exampleAudioAssetId = "aud_ex_$id",
        contentSource = "CORE", contentHash = "hash_$id", active = true,
    )

    private fun memoryState(wordId: String, reps: Int = 0) = WordMemoryStateEntity(
        wordId = wordId, fsrsState = "LEARNING", difficulty = 5.0, stability = 1.0,
        dueAtEpochMs = 0L, lastReviewAtEpochMs = null, reps = reps, lapses = 0,
        masteredUi = false, lastQuestionMode = null, isFavorite = false,
        learnedContentHash = null, updatedAtEpochMs = 0L,
    )

    private fun reviewLog(id: String, wordId: String, at: Long) = ReviewLogEntity(
        id = id, wordId = wordId, rating = "GOOD", questionMode = "EN2ZH",
        usedHint = false, revealedAnswer = true, reviewedAtEpochMs = at,
        responseTimeMs = null, scheduledDays = 1, elapsedDays = 0,
        stateBefore = "LEARNING", stateAfter = "REVIEW",
    )

    private fun checkpoint(sessionId: String, index: Int) = VocabularySessionCheckpointEntity(
        sessionId = sessionId, status = "IN_PROGRESS", corpusVersion = "test-1",
        queueWordIdsJson = "[\"w1\"]", currentIndex = index, questionMode = "INTRODUCE",
        answerRevealed = true, hintRevealed = false,
        startedAtEpochMs = 0L, updatedAtEpochMs = 0L,
    )

    private fun reviewTx(sessionId: String, index: Int) = ReviewTxRunner.ReviewTx(
        actionKey = ReviewActionKeyEntity(
            actionKey = "$sessionId:$index",
            sessionId = sessionId,
            currentIndex = index,
            createdAtEpochMs = 1_000L,
        ),
        reviewLog = reviewLog("r_${sessionId}_$index", "w1", 1_000L),
        memoryState = memoryState("w1", reps = 1),
        taskDateEpochDay = 19723, // 2024-01-01
        isNewWord = true,
        newWordTarget = 15,
        reviewTarget = 0,
        advancedCheckpoint = checkpoint(sessionId, index + 1),
    )

    private fun scenarioSession(id: String, pairIndex: Int, pairCount: Int) = ScenarioSessionEntity(
        id = id, scenarioId = "S001", scenarioContentHash = "hash_s001",
        status = "IN_PROGRESS", currentPairId = "S001_P001",
        currentPairIndex = pairIndex, pairCount = pairCount,
        startedAtEpochMs = 0L, completedAtEpochMs = null, updatedAtEpochMs = 0L,
    )

    private fun progress(sessionId: String, pairId: String) = ScenarioTurnProgressEntity(
        sessionId = sessionId, pairId = pairId,
        customerAudioCompleted = true, customerTextRevealed = false,
        keywordsRevealed = false, answerRevealed = false,
        selfRating = null, updatedAtEpochMs = 0L,
    )
}
