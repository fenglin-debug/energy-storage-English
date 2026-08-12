package com.bess.salestrainer.core.corpus.pack

import com.bess.salestrainer.core.model.CorpusError
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * App-side .besspack parser/validator (TDD §7.3, §7.4).
 *
 * Gates (fail-fast, in order):
 *  1. ZIP path safety: no traversal, absolute paths, backslashes, drive
 *     letters, or duplicate entries.
 *  2. Size limits: pack <= 256 MB, any single entry <= 64 MB, uncompressed
 *     total <= 512 MB (zip-bomb guard).
 *  3. Required entries present (manifest.json, checksums.sha256, data json files).
 *  4. checksums.sha256 covers every entry except itself; each listed entry
 *     matches its bytes; manifest.files agrees with checksums.
 *  5. JSON parses; schemaVersion supported; counts in manifest match arrays.
 *  6. Business invariants: stable-id uniqueness, scenario ids S001.., strict
 *     CUSTOMER-first alternation, pair adjacency, audio coverage.
 */
object PackParser {

    const val SUPPORTED_SCHEMA_VERSION = 3
    private const val MAX_PACK_BYTES = 256L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED = 512L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    private val requiredDataEntries = listOf(
        "data/vocabulary.json",
        "data/phrases.json",
        "data/examples.json",
        "data/scenarios.json",
        "data/dialogue_turns.json",
        "data/dialogue_pairs.json",
        "data/dialogue_pair_words.json",
        "data/dialogue_pair_phrases.json",
        "data/audio_manifest.json",
    )

    class PackException(val error: CorpusError) : Exception(error.toString())

    fun parse(input: InputStream): ParsedPack {
        // ---- stage 1-2: safe unzip with size limits ----
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        val limitedInput = LimitedInputStream(input, MAX_PACK_BYTES) {
            throw PackException(CorpusError.SizeLimitExceeded("pack"))
        }
        ZipInputStream(limitedInput.buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                validateEntryPath(name)
                if (entries.containsKey(name)) {
                    throw PackException(CorpusError.InvalidZipPath("duplicate:$name"))
                }
                val bytes = readEntryBytes(zis, name)
                total += bytes.size
                if (total > MAX_TOTAL_UNCOMPRESSED) {
                    throw PackException(CorpusError.SizeLimitExceeded("total"))
                }
                entries[name] = bytes
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // ---- stage 3: required entries ----
        if (!entries.containsKey("manifest.json")) {
            throw PackException(CorpusError.MissingManifestEntry("manifest.json"))
        }
        if (!entries.containsKey("checksums.sha256")) {
            throw PackException(CorpusError.MissingManifestEntry("checksums.sha256"))
        }
        requiredDataEntries.forEach { req ->
            if (!entries.containsKey(req)) {
                throw PackException(CorpusError.MissingManifestEntry(req))
            }
        }

        // ---- stage 4: checksums ----
        val checksumLines = entries.getValue("checksums.sha256").toString(Charsets.UTF_8)
            .lines().filter { it.isNotBlank() }
        val checksumMap = checksumLines.associate { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) throw PackException(CorpusError.ChecksumMismatch("checksums.sha256:format"))
            parts[1] to parts[0]
        }
        entries.keys.forEach { name ->
            if (name == "checksums.sha256") return@forEach
            val expected = checksumMap[name]
                ?: throw PackException(CorpusError.ChecksumMismatch("$name:not-listed"))
            val actual = sha256Hex(entries.getValue(name))
            if (!expected.equals(actual, ignoreCase = true)) {
                throw PackException(CorpusError.ChecksumMismatch(name))
            }
        }

        // ---- stage 5: manifest + JSON ----
        val manifest = decode<PackManifestDto>(entries.getValue("manifest.json"), "manifest.json")
        if (manifest.schemaVersion !in 2..SUPPORTED_SCHEMA_VERSION) {
            throw PackException(
                CorpusError.IncompatibleSchema(manifest.schemaVersion, SUPPORTED_SCHEMA_VERSION),
            )
        }
        val manifestFileMap = manifest.dataFiles.associate { it.path to it }
        checksumMap.forEach { (path, hash) ->
            // dataFiles lists data + audio entries; manifest.json itself is
            // covered by checksums.sha256 only.
            if (path == "manifest.json") return@forEach
            val mf = manifestFileMap[path]
                ?: throw PackException(CorpusError.MissingManifestEntry("manifest-files:$path"))
            if (!mf.sha256.equals(hash, ignoreCase = true)) {
                throw PackException(CorpusError.ChecksumMismatch("manifest:$path"))
            }
        }

        val vocabulary = decode<List<PackVocabularyDto>>(entries.getValue("data/vocabulary.json"), "data/vocabulary.json")
        val phrases = decode<List<PackPhraseDto>>(entries.getValue("data/phrases.json"), "data/phrases.json")
        val examples = decode<List<PackExampleDto>>(entries.getValue("data/examples.json"), "data/examples.json")
        val scenarios = decode<List<PackScenarioDto>>(entries.getValue("data/scenarios.json"), "data/scenarios.json")
        val turns = decode<List<PackDialogueTurnDto>>(entries.getValue("data/dialogue_turns.json"), "data/dialogue_turns.json")
        val pairs = decode<List<PackDialoguePairDto>>(entries.getValue("data/dialogue_pairs.json"), "data/dialogue_pairs.json")
        val pairWords = decode<List<PackDialoguePairWordDto>>(entries.getValue("data/dialogue_pair_words.json"), "data/dialogue_pair_words.json")
        val pairPhrases = decode<List<PackDialoguePairPhraseDto>>(entries.getValue("data/dialogue_pair_phrases.json"), "data/dialogue_pair_phrases.json")
        val audioManifest = decode<AudioManifestDto>(entries.getValue("data/audio_manifest.json"), "data/audio_manifest.json")
        val audioAssets = audioManifest.assets

        // counts agreement
        if (manifest.counts.vocabulary != vocabulary.size ||
            manifest.counts.phrases != phrases.size ||
            manifest.counts.examples != examples.size ||
            manifest.counts.scenarios != scenarios.size ||
            manifest.counts.dialogueTurns != turns.size ||
            manifest.counts.dialoguePairs != pairs.size ||
            manifest.counts.audioAssets != audioAssets.size
        ) {
            throw PackException(CorpusError.InvariantViolation("manifest-counts-mismatch"))
        }

        // ---- stage 6: business invariants ----
        checkUnique(vocabulary.map { it.id }) { CorpusError.DuplicateId(it) }
        checkUnique(phrases.map { it.id }) { CorpusError.DuplicateId(it) }
        checkUnique(examples.map { it.id }) { CorpusError.DuplicateId(it) }
        checkUnique(scenarios.map { it.id }) { CorpusError.DuplicateId(it) }
        checkUnique(turns.map { it.id }) { CorpusError.DuplicateId(it) }
        checkUnique(pairs.map { it.id }) { CorpusError.DuplicateId(it) }
        checkUnique(audioAssets.map { it.id }) { CorpusError.DuplicateId(it) }
        checkUnique(vocabulary.map { it.normalizedTerm }) { CorpusError.DuplicateId("norm:$it") }
        if (vocabulary.any { it.id.startsWith("WIND-") || it.contentSource == "WIND" }) {
            throw PackException(CorpusError.InvariantViolation("wind-content-forbidden"))
        }

        val scenarioIds = scenarios.map { it.id }.toSet()
        turns.groupBy { it.scenarioId }.forEach { (sid, group) ->
            if (sid !in scenarioIds) throw PackException(CorpusError.InvariantViolation("turn-orphan:$sid"))
            val ordered = group.sortedBy { it.turnNo }
            if (ordered.map { it.turnNo } != (1..ordered.size).toList()) {
                throw PackException(CorpusError.InvariantViolation("turn-seq:$sid"))
            }
            ordered.forEachIndexed { index, turn ->
                val expected = if (index % 2 == 0) "CUSTOMER" else "SALES_ENGINEER"
                if (turn.speaker != expected) {
                    throw PackException(CorpusError.InvariantViolation("alternation:${turn.id}"))
                }
            }
        }
        val turnById = turns.associateBy { it.id }
        pairs.forEach { pair ->
            val customer = turnById[pair.customerTurnId]
                ?: throw PackException(CorpusError.InvariantViolation("pair-customer:${pair.id}"))
            val sales = turnById[pair.salesTurnId]
                ?: throw PackException(CorpusError.InvariantViolation("pair-sales:${pair.id}"))
            if (customer.speaker != "CUSTOMER" || sales.speaker != "SALES_ENGINEER" ||
                sales.turnNo != customer.turnNo + 1
            ) {
                throw PackException(CorpusError.InvariantViolation("pair-adjacency:${pair.id}"))
            }
            if (pair.referenceCoreEn.isBlank() || pair.referenceChineseHint.isBlank()) {
                throw PackException(CorpusError.InvariantViolation("pair-ref-blank:${pair.id}"))
            }
            if (pair.formalAlternatives.size < 2) {
                throw PackException(CorpusError.InvariantViolation("pair-alternatives:${pair.id}"))
            }
        }
        val wordIds = vocabulary.map { it.id }.toSet()
        val pairIds = pairs.map { it.id }.toSet()
        pairWords.forEach { pw ->
            if (pw.pairId !in pairIds || pw.wordId !in wordIds) {
                throw PackException(CorpusError.InvariantViolation("pairword-ref:${pw.pairId}/${pw.wordId}"))
            }
        }
        val phraseIds = phrases.map { it.id }.toSet()
        pairPhrases.forEach { pp ->
            if (pp.pairId !in pairIds || pp.phraseId !in phraseIds) {
                throw PackException(CorpusError.InvariantViolation("pairphrase-ref:${pp.pairId}/${pp.phraseId}"))
            }
        }
        phrases.forEach { p ->
            if (p.textEn.isBlank() || p.textZh.isBlank() || p.audioAssetId.isBlank()) {
                throw PackException(CorpusError.InvariantViolation("phrase-blank:${p.id}"))
            }
        }
        examples.forEach { e ->
            if (e.textEn.isBlank() || e.textZh.isBlank() || e.audioAssetId.isBlank()) {
                throw PackException(CorpusError.InvariantViolation("example-blank:${e.id}"))
            }
        }
        vocabulary.forEach { word ->
            if (1 + examples.count { word.id in it.linkedTermIds } < 2) {
                throw PackException(CorpusError.InvariantViolation("examples-min:${word.id}"))
            }
        }
        phrases.forEach { phrase ->
            if (examples.count { phrase.id in it.linkedTermIds } < 2) {
                throw PackException(CorpusError.InvariantViolation("examples-min:${phrase.id}"))
            }
        }

        // audio coverage + bytes presence
        val audioBytes = HashMap<String, ByteArray>()
        audioAssets.forEach { asset ->
            val bytes = entries[asset.relativePath]
                ?: throw PackException(CorpusError.MissingAudio(asset.id))
            if (sha256Hex(bytes) != asset.sha256) {
                throw PackException(CorpusError.ChecksumMismatch(asset.relativePath))
            }
            audioBytes[asset.relativePath] = bytes
        }
        turns.forEach { turn ->
            if (turn.audioAssetId.isNullOrBlank()) {
                throw PackException(CorpusError.MissingAudio("turn:${turn.id}"))
            }
        }

        return ParsedPack(
            manifest = manifest,
            vocabulary = vocabulary,
            phrases = phrases,
            examples = examples,
            scenarios = scenarios,
            turns = turns,
            pairs = pairs,
            pairWords = pairWords,
            pairPhrases = pairPhrases,
            audioAssets = audioAssets,
            audioBytes = audioBytes,
        )
    }

    private fun validateEntryPath(name: String) {
        val bad = name.isBlank() || name.startsWith("/") || name.contains("..") ||
            name.contains('\\') || Regex("^[A-Za-z]:").containsMatchIn(name)
        if (bad) throw PackException(CorpusError.InvalidZipPath(name))
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
                throw PackException(CorpusError.SizeLimitExceeded("entry:$entryName"))
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

    private inline fun <reified T> decode(bytes: ByteArray, entry: String): T = try {
        json.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        throw PackException(CorpusError.InvalidJson("$entry:${e.message?.take(80)}"))
    }

    private inline fun checkUnique(ids: List<String>, err: (String) -> CorpusError) {
        val seen = HashSet<String>()
        ids.forEach { if (!seen.add(it)) throw PackException(err(it)) }
    }

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
