package com.bess.salestrainer.core.data.repository

import com.bess.salestrainer.core.model.DialogueSelfRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScenarioRandomQueueTest {

    private val base = listOf("A", "B", "C", "D", "E", "F", "G")

    @Test
    fun `cannot answer repeats after two other pairs`() {
        val result = buildNextRandomQueue(
            base,
            currentIndex = 0,
            pairId = "A",
            rating = DialogueSelfRating.CANNOT_ANSWER,
            allPairIds = base.toSet(),
        )

        assertEquals("A", result[3])
    }

    @Test
    fun `basic repeats after five other pairs and fluent does not repeat`() {
        val basic = buildNextRandomQueue(
            base,
            currentIndex = 0,
            pairId = "A",
            rating = DialogueSelfRating.BASIC,
            allPairIds = base.toSet(),
        )
        val fluent = buildNextRandomQueue(
            base,
            currentIndex = 0,
            pairId = "A",
            rating = DialogueSelfRating.FLUENT,
            allPairIds = base.toSet(),
        )

        assertEquals("A", basic[6])
        assertFalse("A" in fluent.drop(1))
    }

    @Test
    fun `keeps an earlier pending repeat instead of duplicating it`() {
        val pending = listOf("A", "B", "C", "A", "D", "E")
        val result = buildNextRandomQueue(
            pending,
            currentIndex = 0,
            pairId = "A",
            rating = DialogueSelfRating.BASIC,
            allPairIds = base.toSet(),
        )

        assertEquals(2, result.count { it == "A" })
        assertEquals(3, result.indexOfLast { it == "A" })
    }
}
