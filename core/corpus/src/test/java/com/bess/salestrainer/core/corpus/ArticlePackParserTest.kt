package com.bess.salestrainer.core.corpus

import com.bess.salestrainer.core.corpus.pack.article.ArticlePackParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ArticlePackParser (.bessarticle) validation tests. Fixtures are built
 * in-memory: manifest.json + checksums.sha256 + audio/<id>.m4a.
 */
class ArticlePackParserTest {

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Distinctive audio fixture: single 0x42 run, easy to locate in the zip. */
    private val fixtureAudio = ByteArray(512) { 0x42 }

    private fun buildPack(
        articleId: String = "ART-9001",
        audioBytes: ByteArray = fixtureAudio,
        durationMs: Long = 60_000L,
        audioFileOverride: String? = null,
        extraManifestFields: String = "",
        packageId: String = "bess-article",
        schemaVersion: Int = 1,
        omitChecksums: Boolean = false,
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val audioFile = audioFileOverride ?: "audio/$articleId.m4a"
        val paragraph = if (schemaVersion >= 2) {
            """{ "textEn": "Hello world.", "textZh": "你好，世界。", "startMs": 0, "endMs": 60000 }"""
        } else {
            """{ "textEn": "Hello world.", "textZh": "你好，世界。" }"""
        }
        val contentScope = if (schemaVersion >= 2) """, "contentScope": "BESS"""" else ""
        val manifest = """
            {
              "schemaVersion": $schemaVersion,
              "packageId": "$packageId",
              "contentVersion": "2026.07.28.t1",
              "createdAt": "2026-07-28T00:00:00Z",
              "articles": [
                {
                  "id": "$articleId",
                  "title": "Test Article",
                  "titleZh": "测试文章",
                  "topic": "储能",
                  "paragraphs": [
                    $paragraph
                  ],
                  "audioFile": "$audioFile",
                  "durationMs": $durationMs,
                  "contentHash": "deadbeef"$contentScope
                }
              ]$extraManifestFields
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val entries = LinkedHashMap<String, ByteArray>()
        entries["manifest.json"] = manifest
        entries[audioFile] = audioBytes
        entries.putAll(extraEntries)

        val checksums = entries.entries
            .joinToString("\n") { (name, bytes) -> "${sha256Hex(bytes)}  $name" }
            .toByteArray(Charsets.UTF_8)
        if (!omitChecksums) entries["checksums.sha256"] = checksums

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun validPackParses() {
        val pack = ArticlePackParser.parse(buildPack().inputStream())
        assertEquals(1, pack.manifest.articles.size)
        val article = pack.manifest.articles.first()
        assertEquals("ART-9001", article.id)
        assertEquals("测试文章", article.titleZh)
        assertEquals(1, article.paragraphs.size)
        assertTrue(pack.audioBytes.containsKey("ART-9001"))
    }

    @Test
    fun bundledBessV2PackHasOnlyTimedBessArticles() {
        val workspace = File("../../..").canonicalFile
        val file = workspace.resolve(
            "android/app/src/main/assets/articles/bundled.bessarticle",
        )
        val pack = FileInputStream(file).use(ArticlePackParser::parse)

        assertEquals(2, pack.manifest.schemaVersion)
        assertEquals(18, pack.manifest.articles.size)
        assertTrue(pack.manifest.articles.all { it.contentScope == "BESS" })
        assertTrue(pack.manifest.articles.none { it.topic.startsWith("风电") })
        assertTrue(pack.manifest.articles.flatMap { it.paragraphs }.all {
            it.startMs != null && it.endMs != null
        })
    }

    @Test
    fun companyProfileImportPackParsesWhenPresent() {
        val file = File("../../..").canonicalFile.resolve(
            "outputs/imports/company_profile_andrew.bessarticle",
        )
        assumeTrue("optional generated import pack is absent", file.isFile)

        val pack = FileInputStream(file).use(ArticlePackParser::parse)
        val article = pack.manifest.articles.single()
        assertEquals("ART-WONTAI-COMPANY-PROFILE", article.id)
        assertEquals("BESS", article.contentScope)
        assertEquals(434_708L, article.durationMs)
        assertEquals(58, article.paragraphs.size)
        assertEquals(article.durationMs, article.paragraphs.last().endMs)
        assertTrue(pack.audioBytes.getValue(article.id).size > 3_000_000)
    }

    @Test
    fun wrongPackageIdRejected() {
        try {
            ArticlePackParser.parse(buildPack(packageId = "bess-sales-english-core").inputStream())
            fail("packageId must be bess-article")
        } catch (e: ArticlePackParser.ArticlePackException) {
            // expected
        }
    }

    @Test
    fun wrongSchemaVersionRejected() {
        try {
            ArticlePackParser.parse(buildPack(schemaVersion = 3).inputStream())
            fail("schema 3 must be rejected")
        } catch (e: ArticlePackParser.ArticlePackException) {
            // expected
        }
    }

    @Test
    fun eightMinuteArticleAccepted() {
        val pack = ArticlePackParser.parse(
            buildPack(durationMs = 8 * 60 * 1000L).inputStream(),
        )
        assertEquals(8 * 60 * 1000L, pack.manifest.articles.single().durationMs)
    }

    @Test
    fun overLongArticleRejected() {
        try {
            ArticlePackParser.parse(buildPack(durationMs = 30 * 60 * 1000L + 1).inputStream())
            fail("duration over the 30-minute cap must be rejected")
        } catch (e: ArticlePackParser.ArticlePackException) {
            // expected
        }
    }

    @Test
    fun audioFileConventionEnforced() {
        try {
            ArticlePackParser.parse(
                buildPack(audioFileOverride = "audio/other-name.m4a").inputStream(),
            )
            fail("audioFile must equal audio/<id>.m4a")
        } catch (e: ArticlePackParser.ArticlePackException) {
            // expected
        }
    }

    @Test
    fun tamperedAudioFailsChecksum() {
        // Swap in different audio bytes but keep the original checksums
        // (checksums of the untampered audio): verification must reject it.
        val original = buildPack()
        val originalChecksums = readZipEntry(original, "checksums.sha256")!!
        val differentAudio = ByteArray(512) { 0x24 }
        val tampered = buildPackWithChecksumOverride(differentAudio, originalChecksums)
        try {
            ArticlePackParser.parse(tampered.inputStream())
            fail("tampered audio must fail checksum validation")
        } catch (e: ArticlePackParser.ArticlePackException) {
            // expected
        }
    }

    private fun readZipEntry(zipBytes: ByteArray, name: String): ByteArray? =
        java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == name) return zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
            null
        }

    /** Rebuilds the standard fixture pack with replaced audio and a fixed checksum file. */
    private fun buildPackWithChecksumOverride(audioBytes: ByteArray, checksumBytes: ByteArray): ByteArray {
        val manifest = readZipEntry(buildPack(), "manifest.json")!!
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            listOf(
                "manifest.json" to manifest,
                "audio/ART-9001.m4a" to audioBytes,
                "checksums.sha256" to checksumBytes,
            ).forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun zipSlipPathRejected() {
        val audio = ByteArray(64) { 1 }
        val manifest = """
            {
              "schemaVersion": 1,
              "packageId": "bess-article",
              "contentVersion": "t",
              "createdAt": "2026-07-28T00:00:00Z",
              "articles": [
                {
                  "id": "ART-9002",
                  "title": "T",
                  "titleZh": "题",
                  "topic": "x",
                  "paragraphs": [ { "textEn": "a", "textZh": "一" } ],
                  "audioFile": "audio/ART-9002.m4a",
                  "durationMs": 1000,
                  "contentHash": "h"
                }
              ]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json")); zos.write(manifest); zos.closeEntry()
            zos.putNextEntry(ZipEntry("../evil.m4a")); zos.write(audio); zos.closeEntry()
            zos.putNextEntry(ZipEntry("audio/ART-9002.m4a")); zos.write(audio); zos.closeEntry()
            val checksums = listOf(
                "manifest.json" to manifest,
                "../evil.m4a" to audio,
                "audio/ART-9002.m4a" to audio,
            ).joinToString("\n") { (n, b) -> "${sha256Hex(b)}  $n" }.toByteArray(Charsets.UTF_8)
            zos.putNextEntry(ZipEntry("checksums.sha256")); zos.write(checksums); zos.closeEntry()
        }
        try {
            ArticlePackParser.parse(out.toByteArray().inputStream())
            fail("zip-slip entry must be rejected")
        } catch (e: ArticlePackParser.ArticlePackException) {
            // expected
        }
    }

    @Test
    fun duplicateArticleIdsRejected() {
        val audio = ByteArray(64) { 2 }
        val manifest = """
            {
              "schemaVersion": 1,
              "packageId": "bess-article",
              "contentVersion": "t",
              "createdAt": "2026-07-28T00:00:00Z",
              "articles": [
                {
                  "id": "ART-9003",
                  "title": "A",
                  "titleZh": "甲",
                  "topic": "x",
                  "paragraphs": [ { "textEn": "a", "textZh": "一" } ],
                  "audioFile": "audio/ART-9003.m4a",
                  "durationMs": 1000,
                  "contentHash": "h1"
                },
                {
                  "id": "ART-9003",
                  "title": "B",
                  "titleZh": "乙",
                  "topic": "y",
                  "paragraphs": [ { "textEn": "b", "textZh": "二" } ],
                  "audioFile": "audio/ART-9003.m4a",
                  "durationMs": 1000,
                  "contentHash": "h2"
                }
              ]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json")); zos.write(manifest); zos.closeEntry()
            zos.putNextEntry(ZipEntry("audio/ART-9003.m4a")); zos.write(audio); zos.closeEntry()
            val checksums = listOf("manifest.json" to manifest, "audio/ART-9003.m4a" to audio)
                .joinToString("\n") { (n, b) -> "${sha256Hex(b)}  $n" }.toByteArray(Charsets.UTF_8)
            zos.putNextEntry(ZipEntry("checksums.sha256")); zos.write(checksums); zos.closeEntry()
        }
        try {
            ArticlePackParser.parse(out.toByteArray().inputStream())
            fail("duplicate article ids must be rejected")
        } catch (e: ArticlePackParser.ArticlePackException) {
            // expected
        }
    }
}
