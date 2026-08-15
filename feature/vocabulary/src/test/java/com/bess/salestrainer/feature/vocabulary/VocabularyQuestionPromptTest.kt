package com.bess.salestrainer.feature.vocabulary

import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Vocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyQuestionPromptTest {
    @Test
    fun listeningHidesTerm() {
        val word = sampleWord(term = "battery energy storage")
        assertEquals("听音辨词", questionPromptFor(QuestionMode.LISTENING, word))
    }

    @Test
    fun transferMasksTerm() {
        val word = sampleWord(term = "inverter", example = "The inverter converts DC to AC.")
        val prompt = questionPromptFor(QuestionMode.TRANSFER, word)
        assertFalse(prompt.contains("inverter", ignoreCase = true))
        assertTrue(prompt.contains("____"))
    }
}

private fun sampleWord(
    term: String,
    example: String = "Example for $term.",
) = Vocabulary(
    id = "w_sample",
    term = term,
    normalizedTerm = term.lowercase(),
    ipa = "/x/",
    partOfSpeech = "n.",
    chineseGloss = "释义",
    exampleSentenceEn = example,
    commonMistakes = "None.",
    topic = "general",
    cefrLevel = "B1",
    wordAudioAssetId = "aud",
    exampleAudioAssetId = "aud_ex",
    contentHash = "hash",
)
