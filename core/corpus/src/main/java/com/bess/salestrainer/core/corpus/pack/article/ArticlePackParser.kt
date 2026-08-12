package com.bess.salestrainer.core.corpus.pack.article

import com.bess.salestrainer.core.corpus.pack.PackParser
import com.bess.salestrainer.core.model.CorpusError
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * App-side .bessarticle parser/validator. Lightweight sibling of PackParser
 * (D5 decision): zip safety, size limits, checksum coverage, JSON schema and
 * article-level invariants — but no replace-all corpus semantics.
 */
object ArticlePackParser {

    const val SUPPORTED_SCHEMA_VERSION = 2
    private const val MAX_PACK_BYTES = 50L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED = 100L * 1024 * 1024
    private const val MAX_ARTICLE_DURATION_MS = 30L * 60 * 1000

    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    class ArticlePackException(val error: CorpusError) : Exception(error.toString())

    fun parse(input: InputStream): ParsedArticlePack {
        // ---- safe unzip with size limits ----
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        val limitedInput = LimitedInputStream(input, MAX_PACK_BYTES) {
            throw ArticlePackException(CorpusError.SizeLimitExceeded("pack"))
        }
        ZipInputStream(limitedInput.buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val bad = name.isBlank() || name.startsWith("/") || name.contains("..") ||
                    name.contains('\\') || Regex("^[A-Za-z]:").containsMatchIn(name)
                if (bad) throw ArticlePackException(CorpusError.InvalidZipPath(name))
                if (entries.containsKey(name)) {
                    throw ArticlePackException(CorpusError.InvalidZipPath("duplicate:$name"))
                }
                val bytes = readEntryBytes(zis, name)
                total += bytes.size
                if (total > MAX_TOTAL_UNCOMPRESSED) {
                    throw ArticlePackException(CorpusError.SizeLimitExceeded("total"))
                }
                entries[name] = bytes
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // ---- required entries ----
        val manifestBytes = entries["manifest.json"]
            ?: throw ArticlePackException(CorpusError.MissingManifestEntry("manifest.json"))
        val checksumBytes = entries["checksums.sha256"]
            ?: throw ArticlePackException(CorpusError.MissingManifestEntry("checksums.sha256"))

        // ---- checksums ----
        val checksumMap = checksumBytes.toString(Charsets.UTF_8)
            .lines().filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) {
                    throw ArticlePackException(CorpusError.ChecksumMismatch("checksums:format"))
                }
                parts[1] to parts[0]
            }
        entries.keys.forEach { name ->
            if (name == "checksums.sha256") return@forEach
            val expected = checksumMap[name]
                ?: throw ArticlePackException(CorpusError.ChecksumMismatch("$name:not-listed"))
            if (!expected.equals(PackParser.sha256Hex(entries.getValue(name)), ignoreCase = true)) {
                throw ArticlePackException(CorpusError.ChecksumMismatch(name))
            }
        }

        // ---- manifest ----
        val manifest = try {
            json.decodeFromString<ArticlePackManifestDto>(manifestBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ArticlePackException(CorpusError.InvalidJson("manifest.json:${e.message?.take(80)}"))
        }
        if (manifest.schemaVersion !in 1..SUPPORTED_SCHEMA_VERSION) {
            throw ArticlePackException(
                CorpusError.IncompatibleSchema(manifest.schemaVersion, SUPPORTED_SCHEMA_VERSION),
            )
        }
        if (manifest.packageId != "bess-article") {
            throw ArticlePackException(CorpusError.InvariantViolation("packageId:${manifest.packageId}"))
        }

        // ---- article invariants + audio presence ----
        val seenIds = HashSet<String>()
        val audioBytes = HashMap<String, ByteArray>()
        manifest.articles.forEach { a ->
            if (!seenIds.add(a.id)) {
                throw ArticlePackException(CorpusError.DuplicateId(a.id))
            }
            if (a.title.isBlank() || a.topic.isBlank()) {
                throw ArticlePackException(CorpusError.InvariantViolation("article-blank:${a.id}"))
            }
            if (a.paragraphs.isEmpty() || a.paragraphs.any { it.textEn.isBlank() || it.textZh.isBlank() }) {
                throw ArticlePackException(CorpusError.InvariantViolation("article-paragraphs:${a.id}"))
            }
            if (a.durationMs <= 0 || a.durationMs > MAX_ARTICLE_DURATION_MS) {
                throw ArticlePackException(CorpusError.InvariantViolation("article-duration:${a.id}"))
            }
            if (a.contentScope !in setOf("BESS", "UNSPECIFIED")) {
                throw ArticlePackException(CorpusError.InvariantViolation("content-scope:${a.id}"))
            }
            if (manifest.schemaVersion >= 2 && a.paragraphs.any {
                    it.startMs == null || it.endMs == null ||
                        it.startMs < 0 || it.endMs <= it.startMs || it.endMs > a.durationMs
                }
            ) {
                throw ArticlePackException(CorpusError.InvariantViolation("article-cues:${a.id}"))
            }
            if (a.audioFile != "audio/${a.id}.m4a") {
                throw ArticlePackException(CorpusError.InvariantViolation("article-audiofile:${a.id}"))
            }
            val bytes = entries[a.audioFile]
                ?: throw ArticlePackException(CorpusError.MissingAudio(a.id))
            audioBytes[a.id] = bytes
        }

        return ParsedArticlePack(manifest = manifest, audioBytes = audioBytes)
    }

    private fun readEntryBytes(input: InputStream, entryName: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) {
                throw ArticlePackException(CorpusError.SizeLimitExceeded("entry:$entryName"))
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private class LimitedInputStream(
        input: InputStream,
        private val maxBytes: Long,
        private val onLimitExceeded: () -> Nothing,
    ) : FilterInputStream(input) {
        private var consumed = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) account(read.toLong())
            return read
        }

        private fun account(bytes: Long) {
            consumed += bytes
            if (consumed > maxBytes) onLimitExceeded()
        }
    }
}
