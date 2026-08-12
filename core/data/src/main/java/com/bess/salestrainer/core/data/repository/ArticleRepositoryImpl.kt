package com.bess.salestrainer.core.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.room.withTransaction
import com.bess.salestrainer.core.corpus.article.ArticleSubtitleParser
import com.bess.salestrainer.core.corpus.importing.ArticleImporter
import com.bess.salestrainer.core.corpus.pack.article.ArticlePackParser
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.ArticleEntity
import com.bess.salestrainer.core.database.entity.ArticleProgressEntity
import com.bess.salestrainer.core.database.entity.AudioAssetEntity
import com.bess.salestrainer.core.model.Article
import com.bess.salestrainer.core.model.ArticleContentScope
import com.bess.salestrainer.core.model.ArticleDeletionResult
import com.bess.salestrainer.core.model.ArticleImportResult
import com.bess.salestrainer.core.model.ArticleParagraph
import com.bess.salestrainer.core.model.CorpusError
import com.bess.salestrainer.core.model.CorpusSource
import com.bess.salestrainer.core.model.LocalArticleAudioInspection
import com.bess.salestrainer.core.model.LocalArticleImportCommand
import com.bess.salestrainer.core.model.LocalArticleImportError
import com.bess.salestrainer.core.model.LocalArticleImportResult
import com.bess.salestrainer.core.model.LocalArticleImportStatus
import com.bess.salestrainer.core.model.LocalArticleInspectionResult
import com.bess.salestrainer.core.model.LocalArticleSubtitleInspection
import com.bess.salestrainer.core.model.contract.ArticleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * D4/D5: article library (磨耳朵) repository.
 *
 * - observe* flows merge article metadata with listening progress.
 * - importArticlePack parses a user-picked .bessarticle via the lightweight
 *   [ArticlePackParser] (upsert semantics — never replace-all) and registers
 *   audio in the shared articles dir + audio_assets(kind=ARTICLE).
 * - ensureBundledArticlesActivated is the idempotent first-launch hook that
 *   unpacks assets/articles/bundled.bessarticle when the library is empty.
 */
class ArticleRepositoryImpl(
    private val context: Context,
    private val db: BessDatabase,
    private val articlesDir: File,
    private val bundledArticleAssetPath: String = "articles/bundled.bessarticle",
    private val bundledYouTubeArticleAssetPath: String = "articles/youtube-bess.bessarticle",
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val audioDurationReader: ((String) -> Long?)? = null,
) : ArticleRepository {

    private val articleDao get() = db.articleDao()

    override fun observeArticles(): Flow<List<Article>> =
        combine(articleDao.observeAll(), articleDao.observeAllProgress()) { articles, progress ->
            val progressById = progress.associateBy { it.articleId }
            articles.map { it.toModel(progressById[it.id]) }
        }

    override fun observeArticle(articleId: String): Flow<Article?> =
        combine(articleDao.observeById(articleId), articleDao.observeProgress(articleId)) { a, p ->
            a?.toModel(p)
        }

    override suspend fun saveProgress(articleId: String, positionMs: Long, completed: Boolean) {
        val existing = articleDao.getProgress(articleId)
        val now = nowEpochMs()
        articleDao.upsertProgress(
            ArticleProgressEntity(
                articleId = articleId,
                lastPositionMs = positionMs.coerceAtLeast(0L),
                listenCount = if (completed && existing?.completedAtEpochMs == null) {
                    (existing?.listenCount ?: 0) + 1
                } else {
                    existing?.listenCount ?: 0
                },
                completedAtEpochMs = when {
                    completed -> existing?.completedAtEpochMs ?: now
                    else -> existing?.completedAtEpochMs
                },
                updatedAtEpochMs = now,
            ),
        )
    }

    override suspend fun importArticlePack(source: CorpusSource): ArticleImportResult =
        withContext(Dispatchers.IO) {
            val pack = try {
                openSource(source.documentRef).use(ArticlePackParser::parse)
            } catch (e: ArticlePackParser.ArticlePackException) {
                return@withContext ArticleImportResult(imported = 0, errors = listOf(e.error))
            } catch (e: Exception) {
                return@withContext ArticleImportResult(
                    imported = 0,
                    errors = listOf(CorpusError.SourceUnreadable),
                )
            }
            try {
                val outcome = ArticleImporter(db, articlesDir, isBundled = false)
                    .importUpsert(pack, nowEpochMs())
                ArticleImportResult(imported = outcome.imported)
            } catch (e: Exception) {
                ArticleImportResult(
                    imported = 0,
                    errors = listOf(CorpusError.TransactionFailure(e.message?.take(60) ?: "unknown")),
                )
            }
        }

    override suspend fun inspectLocalArticleAudio(
        source: CorpusSource,
        displayName: String,
    ): LocalArticleInspectionResult<LocalArticleAudioInspection> = withContext(Dispatchers.IO) {
        try {
            val extension = supportedAudioExtension(displayName)
                ?: return@withContext LocalArticleInspectionResult(
                    error = LocalArticleImportError.UNSUPPORTED_AUDIO_FORMAT,
                )
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            openSource(source.documentRef).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_LOCAL_AUDIO_BYTES) {
                        return@withContext LocalArticleInspectionResult(
                            error = LocalArticleImportError.AUDIO_TOO_LARGE,
                        )
                    }
                    digest.update(buffer, 0, count)
                }
            }
            val durationMs = readAudioDuration(source.documentRef)
                ?: return@withContext LocalArticleInspectionResult(
                    error = LocalArticleImportError.INVALID_AUDIO,
                )
            if (durationMs <= 0L) {
                return@withContext LocalArticleInspectionResult(error = LocalArticleImportError.INVALID_AUDIO)
            }
            if (durationMs > MAX_LOCAL_AUDIO_DURATION_MS) {
                return@withContext LocalArticleInspectionResult(
                    error = LocalArticleImportError.AUDIO_TOO_LONG,
                )
            }
            LocalArticleInspectionResult(
                value = LocalArticleAudioInspection(
                    displayName = displayName,
                    extension = extension,
                    mimeType = audioMimeType(extension),
                    durationMs = durationMs,
                    sizeBytes = size,
                    sha256 = digest.digest().toHex(),
                ),
            )
        } catch (_: Exception) {
            LocalArticleInspectionResult(error = LocalArticleImportError.SOURCE_UNREADABLE)
        }
    }

    override suspend fun inspectLocalArticleSubtitle(
        source: CorpusSource,
        displayName: String,
        audioDurationMs: Long,
    ): LocalArticleInspectionResult<LocalArticleSubtitleInspection> = withContext(Dispatchers.IO) {
        try {
            val bytes = readBoundedSource(source.documentRef, ArticleSubtitleParser.MAX_SUBTITLE_BYTES)
            val paragraphs = ArticleSubtitleParser.parse(bytes, displayName, audioDurationMs)
            LocalArticleInspectionResult(
                value = LocalArticleSubtitleInspection(displayName, paragraphs.size),
            )
        } catch (error: ArticleSubtitleParser.SubtitleException) {
            LocalArticleInspectionResult(error = error.error)
        } catch (_: Exception) {
            LocalArticleInspectionResult(error = LocalArticleImportError.SOURCE_UNREADABLE)
        }
    }

    override suspend fun importLocalArticle(
        command: LocalArticleImportCommand,
    ): LocalArticleImportResult = withContext(Dispatchers.IO) {
        if (command.title.isBlank()) {
            return@withContext LocalArticleImportResult(error = LocalArticleImportError.EMPTY_TITLE)
        }
        val inspection = inspectLocalArticleAudio(command.audioSource, command.audioDisplayName)
        val audio = inspection.value ?: return@withContext LocalArticleImportResult(
            error = inspection.error ?: LocalArticleImportError.INVALID_AUDIO,
        )
        val subtitleSource = command.subtitleSource
        val paragraphs = try {
            if (subtitleSource == null) {
                emptyList()
            } else {
                val subtitleName = command.subtitleDisplayName.orEmpty()
                val bytes = readBoundedSource(
                    subtitleSource.documentRef,
                    ArticleSubtitleParser.MAX_SUBTITLE_BYTES,
                )
                ArticleSubtitleParser.parse(bytes, subtitleName, audio.durationMs)
            }
        } catch (error: ArticleSubtitleParser.SubtitleException) {
            return@withContext LocalArticleImportResult(error = error.error)
        } catch (_: Exception) {
            return@withContext LocalArticleImportResult(error = LocalArticleImportError.SOURCE_UNREADABLE)
        }

        val articleId = "ART-LOCAL-${audio.sha256.take(20).uppercase(Locale.US)}"
        val audioAssetId = "aud_article_$articleId"
        val importDir = File(articlesDir, ".imports").apply { mkdirs() }
        importDir.listFiles()?.forEach { it.delete() }
        val tempFile = File(importDir, "${UUID.randomUUID()}.${audio.extension}")
        val finalFile = File(articlesDir, "$articleId.${audio.extension}")
        val finalExisted = finalFile.isFile
        try {
            val copied = copyAndHash(command.audioSource.documentRef, tempFile)
            if (copied.first > MAX_LOCAL_AUDIO_BYTES) {
                return@withContext LocalArticleImportResult(error = LocalArticleImportError.AUDIO_TOO_LARGE)
            }
            if (copied.second != audio.sha256) {
                return@withContext LocalArticleImportResult(error = LocalArticleImportError.SOURCE_UNREADABLE)
            }
            val copiedDuration = readAudioDuration(tempFile.absolutePath)
            if (copiedDuration == null || copiedDuration <= 0L) {
                return@withContext LocalArticleImportResult(error = LocalArticleImportError.INVALID_AUDIO)
            }
            articlesDir.mkdirs()
            if (finalFile.isFile) {
                tempFile.delete()
            } else if (!tempFile.renameTo(finalFile)) {
                tempFile.inputStream().use { input ->
                    FileOutputStream(finalFile).use(input::copyTo)
                }
                tempFile.delete()
            }

            val existing = articleDao.getById(articleId)
            val oldAudioPath = existing?.let { db.corpusDao().getAudioAsset(it.audioAssetId)?.relativePath }
            val paragraphsJson = paragraphs.toJson()
            val scope = if (command.includeInRandomPractice) {
                ArticleContentScope.BESS.name
            } else {
                ArticleContentScope.UNSPECIFIED.name
            }
            val contentHash = sha256Hex(
                listOf(audio.sha256, command.title.trim(), command.topic.trim(), scope, paragraphsJson)
                    .joinToString("\u0000")
                    .toByteArray(Charsets.UTF_8),
            )
            db.withTransaction {
                articleDao.upsert(
                    ArticleEntity(
                        id = articleId,
                        title = command.title.trim(),
                        titleZh = "",
                        topic = command.topic.trim().ifBlank { "本地导入" },
                        paragraphsJson = paragraphsJson,
                        audioAssetId = audioAssetId,
                        durationMs = copiedDuration,
                        source = "IMPORTED",
                        contentScope = scope,
                        contentHash = contentHash,
                        createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs(),
                    ),
                )
                db.corpusDao().upsertAudioAssets(
                    listOf(
                        AudioAssetEntity(
                            id = audioAssetId,
                            kind = "ARTICLE",
                            relativePath = "articles/${finalFile.name}",
                            sha256 = audio.sha256,
                            mimeType = audio.mimeType,
                            codec = audioCodec(audio.extension),
                            durationMs = copiedDuration,
                            sizeBytes = copied.first,
                        ),
                    ),
                )
            }
            if (oldAudioPath != null && oldAudioPath != "articles/${finalFile.name}") {
                safeArticleFile(oldAudioPath)?.delete()
            }
            LocalArticleImportResult(
                articleId = articleId,
                status = if (existing == null) LocalArticleImportStatus.CREATED else LocalArticleImportStatus.UPDATED,
            )
        } catch (_: Exception) {
            if (!finalExisted) finalFile.delete()
            LocalArticleImportResult(error = LocalArticleImportError.TRANSACTION_FAILURE)
        } finally {
            tempFile.delete()
        }
    }

    override suspend fun deleteImportedArticle(articleId: String): ArticleDeletionResult =
        withContext(Dispatchers.IO) {
            val (result, relativeAudioPath) = db.withTransaction {
                val article = articleDao.getById(articleId)
                    ?: return@withTransaction ArticleDeletionResult.NOT_FOUND to null
                if (article.source != "IMPORTED") {
                    return@withTransaction ArticleDeletionResult.BUNDLED_PROTECTED to null
                }

                val audioAsset = db.corpusDao().getAudioAsset(article.audioAssetId)
                articleDao.deleteProgress(articleId)
                articleDao.deleteById(articleId)
                db.corpusDao().deleteArticleAudioAssets(listOf(article.audioAssetId))
                ArticleDeletionResult.DELETED to audioAsset?.relativePath
            }

            if (result == ArticleDeletionResult.DELETED && relativeAudioPath != null) {
                runCatching {
                    val root = articlesDir.canonicalFile
                    val relative = relativeAudioPath.removePrefix("articles/")
                    val audioFile = File(root, relative).canonicalFile
                    if (audioFile.toPath().startsWith(root.toPath())) audioFile.delete()
                }
            }
            result
        }

    override suspend fun ensureBundledArticlesActivated() {
        withContext(Dispatchers.IO) {
            File(articlesDir, ".imports").listFiles()?.forEach { it.delete() }
            val marker = File(articlesDir, BUNDLED_SET_MARKER)
            if (
                marker.readTextOrNull() == BUNDLED_SET_VERSION &&
                articleDao.bundledCount() == EXPECTED_BUNDLED_ARTICLE_COUNT
            ) {
                return@withContext
            }
            try {
                // Parse every pack before writing anything. A corrupt/missing pack
                // therefore cannot prune content from a previously working set.
                val packs = listOf(bundledArticleAssetPath, bundledYouTubeArticleAssetPath)
                    .map { assetPath ->
                        context.assets.open(assetPath).use(ArticlePackParser::parse)
                    }
                val outcome = ArticleImporter(db, articlesDir, isBundled = true)
                    .importBundledPacks(packs, nowEpochMs())
                check(outcome.imported == EXPECTED_BUNDLED_ARTICLE_COUNT)
                writeMarkerAtomically(marker, BUNDLED_SET_VERSION)
                clearSupportError(BUNDLED_ARTICLE_IMPORT_ERROR)
            } catch (_: Exception) {
                recordSupportError(BUNDLED_ARTICLE_IMPORT_ERROR)
            }
        }
    }

    override suspend fun randomArticleIds(): List<String> =
        articleDao.getRandomEligibleIds().shuffled()

    private fun openSource(documentRef: String): java.io.InputStream {
        // Plain file paths are used by tests and internal tooling; check them
        // before parsing as a URI because Windows drive letters look like a URI scheme.
        val file = File(documentRef)
        if (file.isFile) return FileInputStream(file)
        val uri = android.net.Uri.parse(documentRef)
        context.contentResolver.openInputStream(uri)?.let { return it }
        throw IllegalArgumentException("unreadable source: $documentRef")
    }

    private fun readAudioDuration(documentRef: String): Long? {
        audioDurationReader?.let { return it(documentRef) }
        val retriever = MediaMetadataRetriever()
        return try {
            val file = File(documentRef)
            if (file.isFile) {
                retriever.setDataSource(file.absolutePath)
            } else {
                retriever.setDataSource(context, android.net.Uri.parse(documentRef))
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun readBoundedSource(documentRef: String, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        openSource(documentRef).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw ArticleSubtitleParser.SubtitleException(
                    LocalArticleImportError.SUBTITLE_TOO_LARGE,
                )
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun copyAndHash(documentRef: String, target: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        openSource(documentRef).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_LOCAL_AUDIO_BYTES) break
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        return total to digest.digest().toHex()
    }

    private fun List<ArticleParagraph>.toJson(): String = JsonArray(
        map { paragraph ->
            JsonObject(
                mapOf(
                    "textEn" to JsonPrimitive(paragraph.textEn),
                    "textZh" to JsonPrimitive(paragraph.textZh),
                    "startMs" to (paragraph.startMs?.let(::JsonPrimitive) ?: JsonNull),
                    "endMs" to (paragraph.endMs?.let(::JsonPrimitive) ?: JsonNull),
                ),
            )
        },
    ).toString()

    private fun supportedAudioExtension(displayName: String): String? =
        displayName.substringAfterLast('.', "").lowercase(Locale.US).takeIf { it in SUPPORTED_AUDIO }

    private fun audioMimeType(extension: String): String = when (extension) {
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "audio/wav"
    }

    private fun audioCodec(extension: String): String = when (extension) {
        "m4a" -> "aac"
        "mp3" -> "mp3"
        else -> "pcm"
    }

    private fun safeArticleFile(relativePath: String): File? = runCatching {
        val root = articlesDir.canonicalFile
        File(root, relativePath.removePrefix("articles/")).canonicalFile
            .takeIf { it.toPath().startsWith(root.toPath()) }
    }.getOrNull()

    private fun File.readTextOrNull(): String? =
        runCatching { takeIf(File::isFile)?.readText(Charsets.UTF_8)?.trim() }.getOrNull()

    private fun writeMarkerAtomically(marker: File, value: String) {
        marker.parentFile?.mkdirs()
        val staging = File(marker.parentFile, ".${marker.name}.${UUID.randomUUID()}.tmp")
        try {
            staging.writeText(value, Charsets.UTF_8)
            if (!staging.renameTo(marker)) {
                staging.copyTo(marker, overwrite = true)
            }
        } finally {
            staging.delete()
        }
    }

    private fun recordSupportError(code: String) {
        context.getSharedPreferences(SUPPORT_PREFS, Context.MODE_PRIVATE)
            .edit().putString(LAST_ERROR, code).apply()
    }

    private fun clearSupportError(code: String) {
        val prefs = context.getSharedPreferences(SUPPORT_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(LAST_ERROR, null) == code) {
            prefs.edit().remove(LAST_ERROR).apply()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private companion object {
        const val MAX_LOCAL_AUDIO_BYTES = 100L * 1024L * 1024L
        const val BUNDLED_SET_MARKER = ".bundled-article-set"
        const val BUNDLED_SET_VERSION = "2026.08.09.bess-articles-v1"
        const val EXPECTED_BUNDLED_ARTICLE_COUNT = 22
        const val SUPPORT_PREFS = "bess_support"
        const val LAST_ERROR = "last_error"
        const val BUNDLED_ARTICLE_IMPORT_ERROR = "BUNDLED_ARTICLE_IMPORT_FAILED"
        const val MAX_LOCAL_AUDIO_DURATION_MS = 30L * 60L * 1000L
        val SUPPORTED_AUDIO = setOf("m4a", "mp3", "wav")
    }
}
