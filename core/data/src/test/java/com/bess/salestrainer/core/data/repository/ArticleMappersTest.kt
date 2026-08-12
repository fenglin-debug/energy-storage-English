package com.bess.salestrainer.core.data.repository

import com.bess.salestrainer.core.database.entity.ArticleEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleMappersTest {

    @Test
    fun `decodes bilingual paragraphs persisted by article importer`() {
        val entity = ArticleEntity(
            id = "article-1",
            title = "Title",
            titleZh = "标题",
            topic = "Topic",
            paragraphsJson = """[{"textEn":"Hello client.","textZh":"客户您好。"}]""",
            audioAssetId = "audio-1",
            durationMs = 1_000,
            source = "BUNDLED",
            contentHash = "hash",
            createdAtEpochMs = 0,
        )

        val article = entity.toModel(progress = null)

        assertEquals(1, article.paragraphs.size)
        assertEquals("Hello client.", article.paragraphs.single().textEn)
        assertEquals("客户您好。", article.paragraphs.single().textZh)
    }
}
