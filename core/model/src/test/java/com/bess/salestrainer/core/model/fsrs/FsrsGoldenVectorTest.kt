package com.bess.salestrainer.core.model.fsrs

import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.Rating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Golden-vector tests for the pure Kotlin FSRS v6 port.
 *
 * Vectors were generated from py-fsrs 6.3.1 (MIT) with fuzzing disabled.
 * Any drift between this port and the reference implementation fails here.
 */
class FsrsGoldenVectorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val tolerance = 1e-6

    @Test
    fun goldenVectorsMatch() {
        val resource = javaClass.classLoader!!.getResource("fsrs_golden.json")
            ?: error("fsrs_golden.json not found on test classpath")
        val root = json.parseToJsonElement(resource.readText()).jsonObject
        val meta = root.getValue("meta").jsonObject
        val params = meta.getValue("parameters").jsonArray.map { it.jsonPrimitive.double }
        val retention = meta.getValue("desired_retention").jsonPrimitive.double
        val base = Instant.parse(meta.getValue("base_time").jsonPrimitive.content)

        val scheduler = FsrsScheduler(
            parameters = params.toDoubleArray(),
            desiredRetention = retention,
            enableFuzzing = false,
        )

        val sequenceNames = listOf(
            "first_rating_good_then_good",
            "first_rating_easy",
            "first_rating_hard",
            "first_rating_again",
            "mixed_sequence",
            "same_day_repeat",
            "learning_to_review",
        )

        for (name in sequenceNames) {
            val steps = root.getValue(name).jsonArray
            var card = FsrsCard(due = base) // Learning, step 0
            var reviewTime = base

            for ((index, stepEl) in steps.withIndex()) {
                val step = stepEl.jsonObject
                val rating = Rating.valueOf(step.getValue("rating").jsonPrimitive.content.uppercase())
                val elapsedDays = step.getValue("elapsed_days").jsonPrimitive.int
                val expectedState = FsrsState.valueOf(step.getValue("state").jsonPrimitive.content.uppercase())
                val expectedStability = step.getValue("stability").jsonPrimitive.double
                val expectedDifficulty = step.getValue("difficulty").jsonPrimitive.double
                val expectedDue = Instant.parse(step.getValue("due_iso").jsonPrimitive.content)
                val expectedStep = step["step"]?.jsonPrimitive?.intOrNull

                // Advance review time by elapsed days from the previous review
                // (vectors are authored relative to previous step's review time).
                if (index > 0) {
                    reviewTime = reviewTime.plus(Duration.ofDays(elapsedDays.toLong()))
                }

                val outcome = scheduler.reviewCard(card, rating, reviewTime)
                card = outcome.card

                val label = "$name[$index] rating=$rating"
                assertEquals("$label state", expectedState, card.state)
                assertEquals("$label stability", expectedStability, card.stability!!, tolerance)
                assertEquals("$label difficulty", expectedDifficulty, card.difficulty!!, tolerance)
                assertEquals("$label due", expectedDue, card.due)
                assertEquals("$label step", expectedStep, card.step)
            }
        }
    }

    @Test
    fun newCardFirstReviewMatchesInitialParams() {
        val scheduler = FsrsScheduler(enableFuzzing = false)
        val now = Instant.parse("2024-01-01T12:00:00Z")
        val card = FsrsCard(due = now)

        // Again -> initial stability = p[0], difficulty = p4 - e^(p5*0) + 1 = p4 (6.4133)
        val again = scheduler.reviewCard(card, Rating.AGAIN, now).card
        assertEquals(0.212, again.stability!!, tolerance)
        assertEquals(6.4133, again.difficulty!!, tolerance)
        assertEquals(FsrsState.LEARNING, again.state)
        assertEquals(0, again.step)

        // Hard -> p[1]
        val hard = scheduler.reviewCard(card, Rating.HARD, now).card
        assertEquals(1.2931, hard.stability!!, tolerance)
        assertEquals(FsrsState.LEARNING, hard.state)

        // Good -> p[2]
        val good = scheduler.reviewCard(card, Rating.GOOD, now).card
        assertEquals(2.3065, good.stability!!, tolerance)
        assertEquals(FsrsState.LEARNING, good.state)
        assertEquals(1, good.step)

        // Easy -> p[3], graduates to Review immediately
        val easy = scheduler.reviewCard(card, Rating.EASY, now).card
        assertEquals(8.2956, easy.stability!!, tolerance)
        assertEquals(FsrsState.REVIEW, easy.state)
        assertEquals(null, easy.step)
    }

    @Test
    fun intervalNeverNegativeOrZero() {
        val scheduler = FsrsScheduler(enableFuzzing = false)
        val now = Instant.parse("2024-01-01T12:00:00Z")
        var card = FsrsCard(due = now)

        // Force very low stability through repeated Again reviews
        repeat(10) {
            card = scheduler.reviewCard(card, Rating.AGAIN, card.due).card
        }
        // Due must always be strictly after the review time
        assert(card.due.isAfter(card.lastReview)) { "due must be after lastReview" }
    }

    @Test
    fun parameterBoundsEnforced() {
        // Too few parameters
        try {
            FsrsScheduler(parameters = DoubleArray(20) { 1.0 })
            error("should have thrown")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        // Out-of-bounds parameter (decay < 0.1)
        val bad = FsrsScheduler.DEFAULT_PARAMETERS.copyOf()
        bad[20] = 0.05
        try {
            FsrsScheduler(parameters = bad)
            error("should have thrown")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    private fun java.net.URL.readText(): String = openStream().bufferedReader().use { it.readText() }
}
