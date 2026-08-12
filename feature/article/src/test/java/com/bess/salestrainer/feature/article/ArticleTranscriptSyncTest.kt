package com.bess.salestrainer.feature.article

import com.bess.salestrainer.core.model.ArticleParagraph
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleTranscriptSyncTest {

    @Test
    fun timedCueIsNeverSplitAndKeepsExactTimeline() {
        val timed = ArticleParagraph(
            textEn = "First sentence. Second sentence.",
            textZh = "第一句。第二句。",
            startMs = 1_000L,
            endMs = 3_000L,
        )

        assertEquals(listOf(timed), bilingualSentencePairs(listOf(timed)))
    }

    private val paragraphs = listOf(
        ArticleParagraph(textEn = "one two", textZh = "第一句"),
        ArticleParagraph(textEn = "three four five six", textZh = "第二句"),
        ArticleParagraph(textEn = "seven eight", textZh = "第三句"),
    )

    @Test
    fun `returns no active sentence for an empty article`() {
        assertEquals(-1, syncedParagraphIndex(1_000, 8_000, emptyList()))
    }

    @Test
    fun `uses English word weight to select the active sentence`() {
        assertEquals(0, syncedParagraphIndex(0, 8_000, paragraphs))
        assertEquals(1, syncedParagraphIndex(3_000, 8_000, paragraphs))
        assertEquals(2, syncedParagraphIndex(7_000, 8_000, paragraphs))
    }

    @Test
    fun `selects the last sentence at completion`() {
        assertEquals(2, syncedParagraphIndex(8_000, 8_000, paragraphs))
        assertEquals(2, syncedParagraphIndex(9_000, 8_000, paragraphs))
    }

    @Test
    fun `uses explicit sentence cues with binary lookup`() {
        val timed = listOf(
            ArticleParagraph("First.", "第一句。", startMs = 0, endMs = 900),
            ArticleParagraph("Second.", "第二句。", startMs = 900, endMs = 1_750),
            ArticleParagraph("Third.", "第三句。", startMs = 1_750, endMs = 3_000),
        )

        assertEquals(0, syncedParagraphIndex(899, 3_000, timed))
        assertEquals(1, syncedParagraphIndex(900, 3_000, timed))
        assertEquals(2, syncedParagraphIndex(1_750, 3_000, timed))
    }

    @Test
    fun `splits aligned bilingual paragraphs into sentence pairs`() {
        val result = bilingualSentencePairs(
            listOf(
                ArticleParagraph(
                    textEn = "Hello client. This is the tower.",
                    textZh = "客户您好。这是塔架。",
                ),
            ),
        )

        assertEquals(2, result.size)
        assertEquals("Hello client.", result[0].textEn)
        assertEquals("客户您好。", result[0].textZh)
        assertEquals("This is the tower.", result[1].textEn)
        assertEquals("这是塔架。", result[1].textZh)
    }

    @Test
    fun `keeps paragraph intact when bilingual sentence counts differ`() {
        val paragraph = ArticleParagraph(
            textEn = "First. Second.",
            textZh = "合并翻译。",
        )

        assertEquals(listOf(paragraph), bilingualSentencePairs(listOf(paragraph)))
    }
}
