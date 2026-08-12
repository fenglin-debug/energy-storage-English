package com.bess.salestrainer.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.ExampleEntity
import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.ItemType
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.ReviewAdvance
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

/**
 * SentenceRepositoryImpl: FSRS loop over examples (itemType=EXAMPLE) with
 * an in-memory frozen queue. Start -> reveal -> rate persists memory + log
 * and the queue reflects the rating.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SentenceRepositoryImplTest {

    private lateinit var db: BessDatabase
    private lateinit var repo: SentenceRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BessDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = SentenceRepositoryImpl(
            db = db,
            scheduler = FsrsScheduler(enableFuzzing = false),
            newItemTarget = 10,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun example(id: String) = ExampleEntity(
        id = id,
        industry = "storage",
        scene = "quotation",
        speaker = "CUSTOMER",
        textEn = "What is the round-trip efficiency?",
        textZh = "往返效率是多少？",
        linkedTermIdsJson = "[]",
        dialogueGroupId = null,
        sourceType = "CORE",
        audioAssetId = "aud_example_$id",
        contentHash = "hash-$id",
    )

    @Test
    fun fullSessionFlowPersistsMemoryAndLog() = runTest {
        db.exampleDao().upsertAll(listOf(example("EX-9001"), example("EX-9002")))
        val sessionId = repo.startOrResumeSession()
        val now = Instant.parse("2024-01-01T12:00:00Z")

        repo.revealAnswer(sessionId)
        val first = repo.recordReview(sessionId, "EX-9001", 0, Rating.GOOD, now)
        assertTrue(first is ReviewAdvance.Next)

        val mem = db.itemMemoryDao().get("EX-9001", ItemType.EXAMPLE.name)
        assertNotNull(mem)
        assertEquals(1, mem!!.reps)
        assertTrue(mem.fsrsState != FsrsState.NEW.name)
        assertEquals("hash-EX-9001", mem.learnedContentHash)

        repo.revealAnswer(sessionId)
        val second = repo.recordReview(sessionId, "EX-9002", 1, Rating.AGAIN, now)
        assertTrue(second is ReviewAdvance.Completed)

        val mem2 = db.itemMemoryDao().get("EX-9002", ItemType.EXAMPLE.name)!!
        assertEquals(1, mem2.lapses)
    }

    @Test
    fun staleIndexRejected() = runTest {
        db.exampleDao().upsertAll(listOf(example("EX-9001"), example("EX-9002")))
        val sessionId = repo.startOrResumeSession()
        repo.revealAnswer(sessionId)
        try {
            repo.recordReview(sessionId, "EX-9001", 5, Rating.GOOD, Instant.now())
            org.junit.Assert.fail("stale index must throw")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun unratedRevealRequiredBeforeRating() = runTest {
        db.exampleDao().upsertAll(listOf(example("EX-9001")))
        val sessionId = repo.startOrResumeSession()
        try {
            repo.recordReview(sessionId, "EX-9001", 0, Rating.GOOD, Instant.now())
            org.junit.Assert.fail("rating before reveal must throw")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun dueQueueSplitsNewAndDue() = runTest {
        db.exampleDao().upsertAll(listOf(example("EX-9001"), example("EX-9002"), example("EX-9003")))
        // Seed one due memory for EX-9003.
        db.itemMemoryDao().upsert(
            com.bess.salestrainer.core.database.entity.ItemMemoryStateEntity(
                itemId = "EX-9003",
                itemType = ItemType.EXAMPLE.name,
                fsrsState = FsrsState.REVIEW.name,
                difficulty = 5.0,
                stability = 2.0,
                dueAtEpochMs = 1L, // long past
                lastReviewAtEpochMs = 1L,
                reps = 3,
                lapses = 0,
                learnedContentHash = "hash-EX-9003",
                updatedAtEpochMs = 1L,
            ),
        )
        repo.observeTodayQueue().test {
            val queue = awaitItem()
            assertEquals(listOf("EX-9003"), queue.dueReviews.map { it.id })
            assertEquals(listOf("EX-9001", "EX-9002"), queue.newItems.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
