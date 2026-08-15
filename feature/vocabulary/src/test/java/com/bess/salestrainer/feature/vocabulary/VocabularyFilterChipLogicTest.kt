package com.bess.salestrainer.feature.vocabulary

import com.bess.salestrainer.core.model.MasteryFilter
import com.bess.salestrainer.core.model.VocabularyFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyFilterChipLogicTest {
    @Test
    fun tappingSameMasteryClearsFilter() {
        assertEquals(
            MasteryFilter.ALL,
            nextMasteryFilter(MasteryFilter.LEARNING, MasteryFilter.LEARNING),
        )
    }

    @Test
    fun tappingDifferentMasterySelectsIt() {
        assertEquals(
            MasteryFilter.MASTERED,
            nextMasteryFilter(MasteryFilter.LEARNING, MasteryFilter.MASTERED),
        )
    }

    @Test
    fun queryAndChipsCountAsActiveConstraints() {
        assertFalse(VocabularyFilter().hasActiveConstraints())
        assertTrue(VocabularyFilter(query = "inverter").hasActiveConstraints())
        assertTrue(VocabularyFilter(mastery = MasteryFilter.NOT_STARTED).hasActiveConstraints())
        assertTrue(VocabularyFilter(dueOnly = true).hasActiveConstraints())
        assertTrue(VocabularyFilter(favoritesOnly = true).hasActiveConstraints())
    }
}
