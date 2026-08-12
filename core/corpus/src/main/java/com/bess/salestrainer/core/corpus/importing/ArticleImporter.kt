package com.bess.salestrainer.core.corpus.importing

import androidx.room.withTransaction
import com.bess.salestrainer.core.corpus.pack.PackParser
import com.bess.salestrainer.core.corpus.pack.article.ParsedArticlePack
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.ArticleEntity
import com.bess.salestrainer.core.database.entity.AudioAssetEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Article library importer (upsert semantics, NOT replace-all): articles from
 * a .bessarticle pack are inserted or overwritten by id; audio files land in
 * a shared articles dir. Registered in audio_assets with kind=ARTICLE so the
 * playback layer resolves them through the same locator.
 */
class ArticleImporter(
    private val db: BessDatabase,
    private val articlesDir: File,
    private val isBundled: Boolean,
) {
    data class ArticleImportOutcome(
        val imported: Int,
        val source: String,
    )

    /** Treats every bundled archive as one set, preventing per-pack pruning. */
    suspend fun importBundledPacks(
        packs: List<ParsedArticlePack>,
        nowEpochMs: Long,
    ): ArticleImportOutcome {
        require(isBundled) { "Bundled pack import requires a bundled importer" }
        require(packs.isNotEmpty()) { "At least one bundled article pack is required" }
        val articles = packs.flatMap { it.manifest.articles }
        require(articles.map { it.id }.distinct().size == articles.size) {
            "Duplicate article id across bundled packs"
        }
        val audioBytes = buildMap {
            packs.forEach { pack -> putAll(pack.audioBytes) }
        }
        val merged = ParsedArticlePack(
            manifest = packs.first().manifest.copy(
                packageId = "bess-bundled-article-set",
                contentVersion = packs.joinToString("+") { it.manifest.contentVersion },
                articles = articles,
            ),
            audioBytes = audioBytes,
        )
        return importUpsert(merged, nowEpochMs)
    }

    suspend fun importUpsert(
        pack: ParsedArticlePack,
        nowEpochMs: Long,
    ): ArticleImportOutcome {
        val source = if (isBundled) "BUNDLED" else "IMPORTED"
        val staleBundledIds = if (isBundled) {
            db.articleDao().getBundledIds() - pack.manifest.articles.map { it.id }.toSet()
        } else {
            emptyList()
        }

        articlesDir.mkdirs()
        pack.audioBytes.forEach { (articleId, bytes) ->
            writeAudioAtomically(articleId, bytes)
        }

        db.withTransaction {
            val articleDao = db.articleDao()
            val corpusDao = db.corpusDao()
            pack.manifest.articles.forEach { a ->
                val paragraphsJson = JsonArray(
                    a.paragraphs.map { p ->
                        JsonObject(
                            mapOf(
                                "textEn" to JsonPrimitive(p.textEn),
                                "textZh" to JsonPrimitive(p.textZh),
                                "startMs" to (
                                    p.startMs?.let(::JsonPrimitive)
                                        ?: kotlinx.serialization.json.JsonNull
                                    ),
                                "endMs" to (
                                    p.endMs?.let(::JsonPrimitive)
                                        ?: kotlinx.serialization.json.JsonNull
                                    ),
                            ),
                        )
                    },
                ).toString()
                articleDao.upsert(
                    ArticleEntity(
                        id = a.id,
                        title = a.title,
                        titleZh = a.titleZh,
                        topic = a.topic,
                        paragraphsJson = paragraphsJson,
                        audioAssetId = "aud_article_${a.id}",
                        durationMs = a.durationMs,
                        source = source,
                        contentScope = a.contentScope,
                        contentHash = a.contentHash,
                        createdAtEpochMs = nowEpochMs,
                    ),
                )
                corpusDao.upsertAudioAssets(
                    listOf(
                        AudioAssetEntity(
                            id = "aud_article_${a.id}",
                            kind = "ARTICLE",
                            relativePath = "articles/${a.id}.m4a",
                            sha256 = PackParser.sha256Hex(pack.audioBytes.getValue(a.id)),
                            mimeType = "audio/mp4",
                            codec = "aac-lc",
                            durationMs = a.durationMs,
                            sizeBytes = pack.audioBytes.getValue(a.id).size.toLong(),
                        ),
                    ),
                )
            }
            if (isBundled) {
                articleDao.deleteBundledNotIn(pack.manifest.articles.map { it.id })
                if (staleBundledIds.isNotEmpty()) {
                    corpusDao.deleteArticleAudioAssets(
                        staleBundledIds.map { "aud_article_$it" },
                    )
                }
            }
        }
        staleBundledIds.forEach { staleId ->
            File(articlesDir, "$staleId.m4a").delete()
        }

        return ArticleImportOutcome(imported = pack.manifest.articles.size, source = source)
    }

    private fun writeAudioAtomically(articleId: String, bytes: ByteArray) {
        val target = File(articlesDir, "$articleId.m4a")
        val expectedHash = PackParser.sha256Hex(bytes)
        if (
            target.isFile &&
            target.length() == bytes.size.toLong() &&
            PackParser.sha256Hex(target.readBytes()) == expectedHash
        ) {
            return
        }
        val staging = File(articlesDir, ".$articleId-${System.nanoTime()}.tmp")
        try {
            staging.writeBytes(bytes)
            check(PackParser.sha256Hex(staging.readBytes()) == expectedHash) {
                "Article audio integrity check failed"
            }
            runCatching {
                Files.move(
                    staging.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    staging.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            staging.delete()
        }
    }
}
