package com.bess.salestrainer.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.ArticleEntity
import com.bess.salestrainer.core.database.entity.ArticleProgressEntity
import com.bess.salestrainer.core.database.entity.AudioAssetEntity
import com.bess.salestrainer.core.corpus.importing.ArticleImporter
import com.bess.salestrainer.core.corpus.pack.article.ArticlePackEntryDto
import com.bess.salestrainer.core.corpus.pack.article.ArticlePackManifestDto
import com.bess.salestrainer.core.corpus.pack.article.ArticleParagraphDto
import com.bess.salestrainer.core.corpus.pack.article.ParsedArticlePack
import com.bess.salestrainer.core.model.ArticleDeletionResult
import com.bess.salestrainer.core.model.CorpusSource
import com.bess.salestrainer.core.model.LocalArticleImportCommand
import com.bess.salestrainer.core.model.LocalArticleImportStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ArticleRepositoryImplTest {

    private lateinit var db: BessDatabase
    private lateinit var articlesDir: File
    private lateinit var repository: ArticleRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, BessDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        articlesDir = Files.createTempDirectory("bess-article-delete").toFile()
        repository = ArticleRepositoryImpl(
            context = context,
            db = db,
            articlesDir = articlesDir,
            audioDurationReader = { 5_000L },
        )
    }

    @After
    fun tearDown() {
        db.close()
        articlesDir.deleteRecursively()
    }

    @Test
    fun deleteImportedArticleRemovesMetadataProgressAudioIndexAndFile() = runTest {
        val article = article(id = "imported-1", source = "IMPORTED")
        val audio = audio(article.audioAssetId, article.id)
        val audioFile = File(articlesDir, "${article.id}.m4a").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        db.articleDao().upsert(article)
        db.corpusDao().upsertAudioAssets(listOf(audio))
        db.articleDao().upsertProgress(
            ArticleProgressEntity(
                articleId = article.id,
                lastPositionMs = 500,
                listenCount = 1,
                completedAtEpochMs = null,
                updatedAtEpochMs = 1,
            ),
        )

        val result = repository.deleteImportedArticle(article.id)

        assertEquals(ArticleDeletionResult.DELETED, result)
        assertNull(db.articleDao().getById(article.id))
        assertNull(db.articleDao().getProgress(article.id))
        assertNull(db.corpusDao().getAudioAsset(article.audioAssetId))
        assertFalse(audioFile.exists())
    }

    @Test
    fun deleteImportedArticleProtectsBundledContent() = runTest {
        val article = article(id = "bundled-1", source = "BUNDLED")
        db.articleDao().upsert(article)

        val result = repository.deleteImportedArticle(article.id)

        assertEquals(ArticleDeletionResult.BUNDLED_PROTECTED, result)
        assertNotNull(db.articleDao().getById(article.id))
    }

    @Test
    fun localImportUsesAudioHashToUpdateAndKeepsListeningProgress() = runTest {
        val sourceAudio = File(articlesDir.parentFile, "source-${System.nanoTime()}.mp3").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        }
        val subtitle = File(articlesDir.parentFile, "source-${System.nanoTime()}.srt").apply {
            writeText(
                """
                    1
                    00:00:00,000 --> 00:00:02,000
                    Battery storage supports the grid.
                    储能支撑电网。
                """.trimIndent(),
            )
        }
        try {
            val first = repository.importLocalArticle(
                LocalArticleImportCommand(
                    audioSource = CorpusSource(sourceAudio.absolutePath),
                    audioDisplayName = sourceAudio.name,
                    subtitleSource = CorpusSource(subtitle.absolutePath),
                    subtitleDisplayName = subtitle.name,
                    title = "First title",
                    topic = "Grid support",
                ),
            )
            assertEquals(LocalArticleImportStatus.CREATED, first.status)
            val articleId = requireNotNull(first.articleId)
            repository.saveProgress(articleId, 1_500L, completed = false)

            val second = repository.importLocalArticle(
                LocalArticleImportCommand(
                    audioSource = CorpusSource(sourceAudio.absolutePath),
                    audioDisplayName = sourceAudio.name,
                    title = "Updated title",
                    topic = "Local",
                    includeInRandomPractice = false,
                ),
            )

            assertEquals(articleId, second.articleId)
            assertEquals(LocalArticleImportStatus.UPDATED, second.status)
            assertEquals("Updated title", db.articleDao().getById(articleId)?.title)
            assertEquals("UNSPECIFIED", db.articleDao().getById(articleId)?.contentScope)
            assertEquals(1_500L, db.articleDao().getProgress(articleId)?.lastPositionMs)
            assertEquals(1, db.articleDao().count())
        } finally {
            sourceAudio.delete()
            subtitle.delete()
        }
    }

    @Test
    fun bundledPacksActivateAsOneSetWithoutPruningEachOtherOrProgress() = runTest {
        val importer = ArticleImporter(db, articlesDir, isBundled = true)
        val first = bundledPack("bundled-a", byteArrayOf(1, 2, 3))
        val second = bundledPack("bundled-b", byteArrayOf(4, 5, 6))
        db.articleDao().upsertProgress(
            ArticleProgressEntity(
                articleId = "bundled-a",
                lastPositionMs = 700,
                listenCount = 2,
                completedAtEpochMs = null,
                updatedAtEpochMs = 1,
            ),
        )

        val outcome = importer.importBundledPacks(listOf(first, second), nowEpochMs = 2)

        assertEquals(2, outcome.imported)
        assertNotNull(db.articleDao().getById("bundled-a"))
        assertNotNull(db.articleDao().getById("bundled-b"))
        assertEquals(700L, db.articleDao().getProgress("bundled-a")?.lastPositionMs)
        assertTrue(File(articlesDir, "bundled-a.m4a").isFile)
        assertTrue(File(articlesDir, "bundled-b.m4a").isFile)
    }

    private fun article(id: String, source: String) = ArticleEntity(
        id = id,
        title = "Imported article",
        titleZh = "导入文章",
        topic = "BESS",
        paragraphsJson = "[]",
        audioAssetId = "aud_article_$id",
        durationMs = 1_000,
        source = source,
        contentScope = "BESS",
        contentHash = "hash-$id",
        createdAtEpochMs = 1,
    )

    private fun audio(assetId: String, articleId: String) = AudioAssetEntity(
        id = assetId,
        kind = "ARTICLE",
        relativePath = "articles/$articleId.m4a",
        sha256 = "hash",
        mimeType = "audio/mp4",
        codec = "aac-lc",
        durationMs = 1_000,
        sizeBytes = 3,
    )

    private fun bundledPack(id: String, audio: ByteArray) = ParsedArticlePack(
        manifest = ArticlePackManifestDto(
            schemaVersion = 2,
            packageId = "bess-article",
            contentVersion = "test-$id",
            articles = listOf(
                ArticlePackEntryDto(
                    id = id,
                    title = id,
                    titleZh = id,
                    topic = "BESS",
                    paragraphs = listOf(
                        ArticleParagraphDto("Battery storage.", "储能。", 0, 1_000),
                    ),
                    audioFile = "audio/$id.m4a",
                    durationMs = 1_000,
                    contentHash = "hash-$id",
                    contentScope = "BESS",
                ),
            ),
        ),
        audioBytes = mapOf(id to audio),
    )
}
