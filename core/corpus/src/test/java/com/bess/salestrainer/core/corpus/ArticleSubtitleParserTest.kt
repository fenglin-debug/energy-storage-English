package com.bess.salestrainer.core.corpus

import com.bess.salestrainer.core.corpus.article.ArticleSubtitleParser
import com.bess.salestrainer.core.model.LocalArticleImportError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class ArticleSubtitleParserTest {
    @Test
    fun `bilingual srt creates timed paragraphs`() {
        val subtitles = """
            1
            00:00:00,000 --> 00:00:02,000
            Battery energy storage improves flexibility.
            储能提高系统灵活性。

            2
            00:00:02,000 --> 00:00:04,500
            The PCS controls power conversion.
            PCS 控制功率变换。
        """.trimIndent().toByteArray()

        val result = ArticleSubtitleParser.parse(subtitles, "lesson.srt", 5_000)

        assertEquals(2, result.size)
        assertEquals("储能提高系统灵活性。", result[0].textZh)
        assertEquals(2_000L, result[1].startMs)
    }

    @Test
    fun `english only srt keeps translation empty`() {
        val subtitles = """
            00:00:00.000 --> 00:00:01.500
            English listening only.
        """.trimIndent().toByteArray()

        val result = ArticleSubtitleParser.parse(subtitles, "lesson.srt", 2_000)

        assertEquals("", result.single().textZh)
    }

    @Test
    fun `bilingual lrc uses next timestamp as end`() {
        val subtitles = """
            [00:00.00]Battery storage supports the grid. ｜ 储能支撑电网。
            [00:03.25]It responds quickly. ｜ 它响应迅速。
        """.trimIndent().toByteArray()

        val result = ArticleSubtitleParser.parse(subtitles, "lesson.lrc", 6_000)

        assertEquals(3_250L, result.first().endMs)
        assertEquals(6_000L, result.last().endMs)
    }

    @Test
    fun `utf16 bom is decoded`() {
        val content = "[00:00.00]Battery storage. ｜ 储能。"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + content.toByteArray(Charsets.UTF_16LE)

        val result = ArticleSubtitleParser.parse(bytes, "lesson.lrc", 2_000)

        assertEquals("储能。", result.single().textZh)
    }

    @Test
    fun `gb18030 chinese subtitle falls back correctly`() {
        val content = "[00:00.00]Battery storage. ｜ 储能。"

        val result = ArticleSubtitleParser.parse(
            content.toByteArray(Charset.forName("GB18030")),
            "lesson.lrc",
            2_000,
        )

        assertEquals("储能。", result.single().textZh)
    }

    @Test
    fun `overlapping srt is rejected`() {
        val subtitles = """
            1
            00:00:00,000 --> 00:00:03,000
            First.

            2
            00:00:02,000 --> 00:00:04,000
            Second.
        """.trimIndent().toByteArray()

        val error = runCatching {
            ArticleSubtitleParser.parse(subtitles, "lesson.srt", 5_000)
        }.exceptionOrNull()

        assertTrue(error is ArticleSubtitleParser.SubtitleException)
        assertEquals(
            LocalArticleImportError.INVALID_SUBTITLE_TIMELINE,
            (error as ArticleSubtitleParser.SubtitleException).error,
        )
    }
}
