package com.bess.salestrainer.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.VocabularyEntryEntity
import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.MasteryFilter
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.RecordWordReview
import com.bess.salestrainer.core.model.VocabularyFilter
import com.bess.salestrainer.core.model.fsrs.FsrsScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/**
 * Repository-level integration tests over an in-memory Room database.
 * Verifies the FSRS loop end-to-end: recordReview persists state and the
 * today queue reflects it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VocabularyRepositoryImplTest {

    private lateinit var db: BessDatabase
    private lateinit var repo: VocabularyRepositoryImpl
    private val zone = ZoneId.of("UTC")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BessDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = VocabularyRepositoryImpl(
            db = db,
            scheduler = FsrsScheduler(enableFuzzing = false),
            zoneId = zone,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun recordReviewCreatesMemoryStateAndLog() = runTest {
        db.vocabularyDao().upsert(vocab("w1", "battery"))
        val now = Instant.parse("2024-01-01T12:00:00Z")

        val result = repo.recordReview(
            RecordWordReview("w1", Rating.GOOD, QuestionMode.EN2ZH, false, false, now),
        )

        assertEquals(FsrsState.LEARNING, result.newState)
        assertEquals(1, result.reps)
        assertTrue(result.nextDueAt.isAfter(now))

        val mem = db.vocabularyDao().getMemoryState("w1")!!
        assertEquals("LEARNING", mem.fsrsState)
        assertEquals(1, db.vocabularyDao().reviewLogCountForWord("w1"))
    }

    @Test
    fun firstEasyReviewGraduatesToReview() = runTest {
        db.vocabularyDao().upsert(vocab("w1", "battery"))
        val now = Instant.parse("2024-01-01T12:00:00Z")
        val result = repo.recordReview(
            RecordWordReview("w1", Rating.EASY, QuestionMode.EN2ZH, false, false, now),
        )
        assertEquals(FsrsState.REVIEW, result.newState)
        assertEquals(8.2956, db.vocabularyDao().getMemoryState("w1")!!.stability, 1e-6)
    }

    @Test
    fun todayQueueSplitsNewAndDue() = runTest {
        db.vocabularyDao().upsertAll(listOf(vocab("w1", "battery"), vocab("w2", "inverter")))
        // Review w1 so it leaves the "new" pool and becomes due immediately.
        val past = Instant.now().minusSeconds(86_400)
        repo.recordReview(RecordWordReview("w1", Rating.AGAIN, QuestionMode.EN2ZH, false, false, past))

        repo.observeTodayQueue().test {
            val queue = awaitItem()
            assertEquals(1, queue.newWords.size) // w2 still new
            assertEquals("w2", queue.newWords[0].id)
            assertEquals(1, queue.dueReviews.size) // w1 due (Again -> 1 min)
            assertEquals("w1", queue.dueReviews[0].id)
            assertNotNull(queue.dueReviews[0].memoryState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun vocabularyFilterByMasteryAndQuery() = runTest {
        db.vocabularyDao().upsertAll(
            listOf(vocab("w1", "battery"), vocab("w2", "inverter"), vocab("w3", "photovoltaic")),
        )
        repo.recordReview(
            RecordWordReview("w1", Rating.GOOD, QuestionMode.EN2ZH, false, false, Instant.now()),
        )

        repo.observeVocabulary(VocabularyFilter(mastery = MasteryFilter.NOT_STARTED)).test {
            val list = awaitItem()
            assertEquals(2, list.size) // w2, w3
            cancelAndIgnoreRemainingEvents()
        }
        repo.observeVocabulary(VocabularyFilter(query = "invert")).test {
            val list = awaitItem()
            assertEquals(listOf("w2"), list.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun vocab(id: String, term: String) = VocabularyEntryEntity(
        id = id, term = term, normalizedTerm = term.lowercase(),
        ipa = null, partOfSpeech = "n.", chineseGloss = "释义",
        englishDefinition = null, collocationsJson = "[]",
        exampleSentenceEn = null, exampleSentenceZh = null, commonMistakes = null,
        topic = "general", scenarioTagsJson = "[]", cefrLevel = "B1",
        audioRef = null, exampleAudioRef = null,
        contentSource = "CORE", contentVersion = "1.0.0", aliasOf = null, active = true,
    )
}
