package com.bess.salestrainer.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.VocabularyEntryEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.MasteryFilter
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.RecordWordReview
import com.bess.salestrainer.core.model.ReviewAdvance
import com.bess.salestrainer.core.model.VocabularyFilter
import com.bess.salestrainer.core.model.VocabularySessionStatus
import com.bess.salestrainer.core.model.VocabularySelfAssessment
import com.bess.salestrainer.core.model.fsrs.FsrsScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
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
 * Repository-level integration tests over an in-memory Room v2 database.
 * Verifies the FSRS loop and the precise session checkpoint end-to-end:
 * start/reveal/rate persists state and the today queue reflects it.
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
    fun fullSessionFlowPersistsMemoryLogAndCheckpoint() = runTest {
        db.vocabularyDao().upsert(vocab("w1", "battery"))
        val sessionId = repo.startOrResumeSession()
        val now = Instant.parse("2024-01-01T12:00:00Z")

        repo.revealVocabularyAnswer(sessionId)
        val advance = repo.recordReview(
            sessionId,
            RecordWordReview(
                expectedWordId = "w1",
                expectedIndex = 0,
                rating = Rating.GOOD,
                usedHint = false,
                reviewedAt = now,
            ),
        )

        assertTrue(advance is ReviewAdvance.Completed)

        val mem = db.vocabularyDao().getMemoryState("w1")!!
        assertEquals(FsrsState.LEARNING.name, mem.fsrsState)
        assertEquals(1, mem.reps)
        assertEquals(1, db.vocabularyDao().reviewLogCountForWord("w1"))

        val checkpoint = db.vocabularyDao().getCheckpoint(sessionId)!!
        assertEquals(VocabularySessionStatus.COMPLETED.name, checkpoint.status)
        assertEquals(1, checkpoint.currentIndex)
    }

    @Test
    fun ratingRequiresReveal() = runTest {
        db.vocabularyDao().upsert(vocab("w1", "battery"))
        val now = Instant.now()
        db.vocabularyDao().upsertMemoryState(
            WordMemoryStateEntity(
                wordId = "w1",
                fsrsState = FsrsState.REVIEW.name,
                difficulty = 5.0,
                stability = 5.0,
                dueAtEpochMs = now.minusSeconds(60).toEpochMilli(),
                lastReviewAtEpochMs = now.minusSeconds(600).toEpochMilli(),
                reps = 3,
                lapses = 0,
                masteredUi = false,
                lastQuestionMode = null,
                learnedContentHash = "hash_w1",
                updatedAtEpochMs = now.toEpochMilli(),
            ),
        )
        val sessionId = repo.startOrResumeSession()

        val error = runCatching {
            repo.recordReview(
                sessionId,
                RecordWordReview("w1", 0, Rating.GOOD, false, Instant.now()),
            )
        }
        assertTrue(error.isFailure)
    }

    @Test
    fun doubleTapSubmitsOnlyOnce() = runTest {
        db.vocabularyDao().upsertAll(listOf(vocab("w1", "battery"), vocab("w2", "inverter")))
        val sessionId = repo.startOrResumeSession()
        repo.revealVocabularyAnswer(sessionId)

        val command = RecordWordReview("w1", 0, Rating.GOOD, false, Instant.now())
        val first = repo.recordReview(sessionId, command)
        assertTrue(first is ReviewAdvance.Next)

        // Same action submitted again (double tap): memory stays single-counted.
        val error = runCatching { repo.recordReview(sessionId, command) }
        // The checkpoint already advanced, so a stale-index rejection is fine;
        // what matters is no duplicate write happened.
        assertEquals(1, db.vocabularyDao().reviewLogCountForWord("w1"))
        assertEquals(1, db.vocabularyDao().getMemoryState("w1")!!.reps)
    }

    @Test
    fun resumeReturnsSameSessionAcrossRecreate() = runTest {
        db.vocabularyDao().upsertAll(listOf(vocab("w1", "battery"), vocab("w2", "inverter")))
        val firstId = repo.startOrResumeSession()

        // Simulate process death: a fresh repository instance over the same DB.
        val recreated = VocabularyRepositoryImpl(
            db = db,
            scheduler = FsrsScheduler(enableFuzzing = false),
            zoneId = zone,
        )
        assertEquals(firstId, recreated.startOrResumeSession())
    }

    @Test
    fun todayQueueSplitsNewAndDue() = runTest {
        db.vocabularyDao().upsertAll(listOf(vocab("w1", "battery"), vocab("w2", "inverter")))
        val sessionId = repo.startOrResumeSession()
        repo.revealVocabularyAnswer(sessionId)
        repo.recordReview(
            sessionId,
            RecordWordReview("w1", 0, Rating.AGAIN, false, Instant.now().minusSeconds(120)),
        )

        repo.observeTodayQueue().test {
            val queue = awaitItem()
            assertEquals(1, queue.newWords.size) // w2 still new
            assertEquals("w2", queue.newWords[0].id)
            assertEquals(1, queue.dueReviews.size) // w1 due (Again -> short step)
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
        val sessionId = repo.startOrResumeSession()
        repo.revealVocabularyAnswer(sessionId)
        repo.recordReview(sessionId, RecordWordReview("w1", 0, Rating.GOOD, false, Instant.now()))

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

    @Test
    fun assessmentPersistsWithoutAdvancingAndSurvivesRepositoryRecreate() = runTest {
        db.vocabularyDao().upsertAll(listOf(vocab("w1", "battery"), vocab("w2", "inverter")))
        val sessionId = repo.startOrResumeSession()

        repo.submitAssessment(sessionId, "w1", VocabularySelfAssessment.FUZZY)

        val persisted = db.vocabularyDao().getCheckpoint(sessionId)!!
        assertEquals(0, persisted.currentIndex)
        assertTrue(persisted.assessmentSubmitted)
        assertEquals(VocabularySelfAssessment.FUZZY.name, persisted.selectedAssessment)
        assertEquals(0, db.vocabularyDao().getMemoryState("w1")?.reps ?: 0)
        assertEquals(0, db.vocabularyDao().reviewLogCountForWord("w1"))
        val recreated = VocabularyRepositoryImpl(
            db = db,
            scheduler = FsrsScheduler(enableFuzzing = false),
            zoneId = zone,
        )
        val restoredView = recreated.observeSession(sessionId).first()
        assertEquals("w1", restoredView.currentWord?.id)
        assertTrue(restoredView.checkpoint.assessmentSubmitted)

        recreated.advanceToNext(sessionId)
        val advanced = recreated.observeSession(sessionId).first()
        assertEquals("w2", advanced.currentWord?.id)
        assertEquals(1, advanced.checkpoint.currentIndex)
        assertTrue(!advanced.checkpoint.assessmentSubmitted)
        assertEquals(1, db.vocabularyDao().getMemoryState("w1")!!.reps)
        assertEquals(1, db.vocabularyDao().reviewLogCountForWord("w1"))
    }

    @Test
    fun duplicateAssessmentIsIdempotentAndMasteredRemainsSchedulable() = runTest {
        db.vocabularyDao().upsert(vocab("w1", "battery"))
        val sessionId = repo.startOrResumeSession()

        repo.submitAssessment(sessionId, "w1", VocabularySelfAssessment.MASTERED)
        repo.submitAssessment(sessionId, "w1", VocabularySelfAssessment.MASTERED)
        assertEquals(0, db.vocabularyDao().reviewLogCountForWord("w1"))

        repo.advanceToNext(sessionId)

        assertEquals(1, db.vocabularyDao().reviewLogCountForWord("w1"))
        val mem = db.vocabularyDao().getMemoryState("w1")!!
        assertEquals(1, mem.reps)
        assertTrue(mem.masteredUi)
        assertTrue(mem.dueAtEpochMs < Long.MAX_VALUE)
        val logs = db.vocabularyDao().observeReviewLogsSince(0L).first()
        assertEquals(Rating.GOOD.name, logs.single().rating)
    }

    @Test
    fun infiniteDueReviewedWordIsRepairedIntoTodayQueue() = runTest {
        val now = Instant.now()
        db.vocabularyDao().upsert(vocab("w1", "battery"))
        db.vocabularyDao().upsertMemoryState(
            WordMemoryStateEntity(
                wordId = "w1",
                fsrsState = FsrsState.REVIEW.name,
                difficulty = 5.0,
                stability = 10.0,
                dueAtEpochMs = Long.MAX_VALUE,
                lastReviewAtEpochMs = now.minusSeconds(86_400 * 40).toEpochMilli(),
                reps = 3,
                lapses = 0,
                masteredUi = true,
                lastQuestionMode = null,
                learnedContentHash = "hash_w1",
                updatedAtEpochMs = now.toEpochMilli(),
            ),
        )
        // Do not start a session first: home counts come from observeTodayQueue,
        // and the start button is disabled when new+due == 0.
        val queue = repo.observeTodayQueue().first()
        assertEquals("w1", queue.dueReviews.single().id)
        val dueAt = db.vocabularyDao().getMemoryState("w1")!!.dueAtEpochMs
        assertTrue(dueAt < Long.MAX_VALUE)
        assertTrue(dueAt <= Instant.now().toEpochMilli())
    }

    @Test
    fun favoritePlaceholderIsNotTreatedAsDue() = runTest {
        val now = Instant.now()
        db.vocabularyDao().upsert(vocab("w1", "battery"))
        db.vocabularyDao().upsertMemoryState(
            WordMemoryStateEntity(
                wordId = "w1",
                fsrsState = FsrsState.NEW.name,
                difficulty = 0.0,
                stability = 0.0,
                dueAtEpochMs = Long.MAX_VALUE,
                lastReviewAtEpochMs = null,
                reps = 0,
                lapses = 0,
                masteredUi = false,
                lastQuestionMode = null,
                isFavorite = true,
                learnedContentHash = "hash_w1",
                updatedAtEpochMs = now.toEpochMilli(),
            ),
        )
        val queue = repo.observeTodayQueue().first()
        assertTrue(queue.dueReviews.none { it.id == "w1" })
    }

    @Test
    fun assessmentRestoresPersistedLearningCardWithValidStep() = runTest {
        db.vocabularyDao().upsert(vocab("w1", "battery"))
        val now = Instant.now()
        db.vocabularyDao().upsertMemoryState(
            WordMemoryStateEntity(
                wordId = "w1",
                fsrsState = FsrsState.LEARNING.name,
                difficulty = 5.0,
                stability = 0.4,
                dueAtEpochMs = now.minusSeconds(60).toEpochMilli(),
                lastReviewAtEpochMs = now.minusSeconds(600).toEpochMilli(),
                reps = 1,
                lapses = 0,
                masteredUi = false,
                lastQuestionMode = null,
                learnedContentHash = "hash_w1",
                updatedAtEpochMs = now.minusSeconds(600).toEpochMilli(),
            ),
        )

        val sessionId = repo.startOrResumeSession()
        repo.revealVocabularyAnswer(sessionId)
        repo.submitAssessment(sessionId, "w1", VocabularySelfAssessment.FUZZY)

        assertEquals(1, db.vocabularyDao().getMemoryState("w1")!!.reps)
        assertTrue(db.vocabularyDao().getCheckpoint(sessionId)!!.assessmentSubmitted)

        repo.advanceToNext(sessionId)
        assertEquals(2, db.vocabularyDao().getMemoryState("w1")!!.reps)
    }

    @Test
    fun assessmentRequiresRevealExceptIntroduce() = runTest {
        db.vocabularyDao().upsert(vocab("w1", "battery"))
        val now = Instant.now()
        db.vocabularyDao().upsertMemoryState(
            WordMemoryStateEntity(
                wordId = "w1",
                fsrsState = FsrsState.REVIEW.name,
                difficulty = 5.0,
                stability = 5.0,
                dueAtEpochMs = now.minusSeconds(60).toEpochMilli(),
                lastReviewAtEpochMs = now.minusSeconds(600).toEpochMilli(),
                reps = 3,
                lapses = 0,
                masteredUi = false,
                lastQuestionMode = null,
                learnedContentHash = "hash_w1",
                updatedAtEpochMs = now.toEpochMilli(),
            ),
        )
        val sessionId = repo.startOrResumeSession()
        val mode = repo.observeSession(sessionId).first().checkpoint.questionMode
        assertTrue(mode != QuestionMode.INTRODUCE)

        val blocked = runCatching {
            repo.submitAssessment(sessionId, "w1", VocabularySelfAssessment.FUZZY)
        }
        assertTrue(blocked.isFailure)

        repo.revealVocabularyAnswer(sessionId)
        repo.submitAssessment(sessionId, "w1", VocabularySelfAssessment.FUZZY)
        assertTrue(repo.observeSession(sessionId).first().checkpoint.assessmentSubmitted)
    }

    @Test
    fun introduceSessionStartsRevealed() = runTest {
        db.vocabularyDao().upsert(vocab("w1", "battery"))
        val sessionId = repo.startOrResumeSession()
        val view = repo.observeSession(sessionId).first()
        assertEquals(QuestionMode.INTRODUCE, view.checkpoint.questionMode)
        assertTrue(view.checkpoint.answerRevealed)
    }

    @Test
    fun assessmentPromotesLegacyNewMemoryRowIntoLearning() = runTest {
        db.vocabularyDao().upsert(vocab("w1", "battery"))
        val now = Instant.now()
        db.vocabularyDao().upsertMemoryState(
            WordMemoryStateEntity(
                wordId = "w1",
                fsrsState = FsrsState.NEW.name,
                difficulty = 0.0,
                stability = 0.0,
                dueAtEpochMs = now.minusSeconds(60).toEpochMilli(),
                lastReviewAtEpochMs = null,
                reps = 0,
                lapses = 0,
                masteredUi = false,
                lastQuestionMode = null,
                isFavorite = true,
                learnedContentHash = "hash_w1",
                updatedAtEpochMs = now.minusSeconds(60).toEpochMilli(),
            ),
        )

        val sessionId = repo.startOrResumeSession()
        repo.submitAssessment(sessionId, "w1", VocabularySelfAssessment.UNFAMILIAR)

        assertEquals(FsrsState.NEW.name, db.vocabularyDao().getMemoryState("w1")!!.fsrsState)

        repo.advanceToNext(sessionId)
        assertEquals(FsrsState.LEARNING.name, db.vocabularyDao().getMemoryState("w1")!!.fsrsState)
    }

    @Test
    fun assessmentCanBeChangedBeforeAdvance() = runTest {
        db.vocabularyDao().upsertAll(listOf(vocab("w1", "battery"), vocab("w2", "inverter")))
        val sessionId = repo.startOrResumeSession()
        repo.submitAssessment(sessionId, "w1", VocabularySelfAssessment.MASTERED)
        repo.submitAssessment(sessionId, "w1", VocabularySelfAssessment.UNFAMILIAR)
        assertEquals(
            VocabularySelfAssessment.UNFAMILIAR.name,
            db.vocabularyDao().getCheckpoint(sessionId)!!.selectedAssessment,
        )
        assertEquals(0, db.vocabularyDao().reviewLogCountForWord("w1"))

        repo.advanceToNext(sessionId)
        assertEquals(1, db.vocabularyDao().reviewLogCountForWord("w1"))
        val mem = db.vocabularyDao().getMemoryState("w1")!!
        assertEquals(1, mem.lapses)
    }

    @Test
    fun advanceWithoutAssessmentIsNoOp() = runTest {
        db.vocabularyDao().upsert(vocab("w1", "battery"))
        val sessionId = repo.startOrResumeSession()
        repo.advanceToNext(sessionId)
        assertEquals(0, db.vocabularyDao().getCheckpoint(sessionId)!!.currentIndex)
    }

    private fun vocab(id: String, term: String) = VocabularyEntryEntity(
        id = id, term = term, normalizedTerm = term.lowercase(),
        ipa = "/x/", partOfSpeech = "n.", chineseGloss = "释义",
        englishDefinition = null, collocationsJson = "[]",
        exampleSentenceEn = "Example for $term.", exampleSentenceZh = null,
        commonMistakes = "None.", topic = "general", scenarioTagsJson = "[]",
        cefrLevel = "B1", wordAudioAssetId = "aud_$id", exampleAudioAssetId = "aud_ex_$id",
        contentSource = "CORE", contentHash = "hash_$id", active = true,
    )
}
