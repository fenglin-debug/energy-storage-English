package com.bess.packager

import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Deterministic .besspack writer (TDD §7.1, §7.3): fixed JSON key order,
 * arrays sorted by stable id, ZIP timestamps fixed, sorted entry order —
 * identical inputs produce identical bytes.
 */
object PackWriter {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = false
    }

    private const val FIXED_ZIP_TIME = 0L // 1980-01-01

    fun write(
        corpus: CorpusBuilder.BuiltCorpus,
        contentVersion: String,
        output: File,
        audioDir: File?,
        emitAudioTextDir: File?,
    ) {
        val sortedVocab = corpus.vocabulary.sortedBy { it.id }
        val sortedPhrases = corpus.phrases.sortedBy { it.id }
        val sortedExamples = corpus.examples.sortedBy { it.id }
        val sortedScenarios = corpus.scenarios.sortedBy { it.id }
        val sortedTurns = corpus.turns.sortedBy { it.id }
        val sortedPairs = corpus.pairs.sortedBy { it.id }
        val sortedPairWords = corpus.pairWords.sortedWith(compareBy({ it.pairId }, { it.sortOrder }))
        val sortedPairPhrases = corpus.pairPhrases.sortedWith(compareBy({ it.pairId }, { it.sortOrder }))
        val sortedAssets = corpus.audioAssets.sortedBy { it.id }

        // ---------- payload JSON ----------
        val vocabularyJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PackVocabulary.serializer()), sortedVocab,
        )
        val phrasesJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PackPhrase.serializer()), sortedPhrases,
        )
        val examplesJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PackExample.serializer()), sortedExamples,
        )
        val scenariosJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PackScenario.serializer()), sortedScenarios,
        )
        val turnsJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PackDialogueTurn.serializer()), sortedTurns,
        )
        val pairsJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PackDialoguePair.serializer()), sortedPairs,
        )
        val pairWordsJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PackDialoguePairWord.serializer()), sortedPairWords,
        )
        val pairPhrasesJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PackDialoguePairPhrase.serializer()), sortedPairPhrases,
        )
        val audioManifestJson = json.encodeToString(AudioManifest.serializer(), AudioManifest(sortedAssets))

        val dataPayloads = linkedMapOf(
            "data/vocabulary.json" to vocabularyJson.toByteArray(Charsets.UTF_8),
            "data/phrases.json" to phrasesJson.toByteArray(Charsets.UTF_8),
            "data/examples.json" to examplesJson.toByteArray(Charsets.UTF_8),
            "data/scenarios.json" to scenariosJson.toByteArray(Charsets.UTF_8),
            "data/dialogue_turns.json" to turnsJson.toByteArray(Charsets.UTF_8),
            "data/dialogue_pairs.json" to pairsJson.toByteArray(Charsets.UTF_8),
            "data/dialogue_pair_words.json" to pairWordsJson.toByteArray(Charsets.UTF_8),
            "data/dialogue_pair_phrases.json" to pairPhrasesJson.toByteArray(Charsets.UTF_8),
            "data/audio_manifest.json" to audioManifestJson.toByteArray(Charsets.UTF_8),
        )

        val report = corpus.report.copy(contentVersion = contentVersion)
        val reportJson = json.encodeToString(BuildReport.serializer(), report)
        dataPayloads["reports/build_report.json"] = reportJson.toByteArray(Charsets.UTF_8)

        // ---------- audio payload bytes ----------
        val audioPayloads = sortedMapOf<String, ByteArray>()
        sortedAssets.forEach { asset ->
            val onDisk = audioDir?.resolve(asset.relativePath.removePrefix("audio/"))
            if (onDisk != null && onDisk.isFile) {
                audioPayloads[asset.relativePath] = onDisk.readBytes()
            } else {
                // Deterministic placeholder: tiny text marker so import and
                // hash verification flow end-to-end before TTS audio lands.
                val text = corpus.audioTexts[asset.id].orEmpty()
                audioPayloads[asset.relativePath] =
                    "BESS-PLACEHOLDER-AUDIO\n$text".toByteArray(Charsets.UTF_8)
            }
        }

        // ---------- manifest ----------
        val dataFiles = (dataPayloads.keys + audioPayloads.keys).sorted().map { path ->
            val bytes = dataPayloads[path] ?: audioPayloads.getValue(path)
            PackManifest.DataFileEntry(path = path, sha256 = sha256Hex(bytes), sizeBytes = bytes.size.toLong())
        }
        val manifest = PackManifest(
            schemaVersion = 3,
            packageId = "bess-sales-english-core",
            contentVersion = contentVersion,
            createdAt = CorpusBuilder.deterministicInstant(),
            minimumAppVersionCode = 1,
            locale = PackManifest.LocaleInfo(learning = "zh-CN", speech = "en-US"),
            audioProfile = PackManifest.AudioProfile(
                container = "m4a",
                codec = "aac-lc",
                sampleRateHz = 24000,
                channels = 1,
                customerVoice = "en-US-AndrewNeural",
                salesVoice = "en-US-GuyNeural",
                generationRate = 0.95,
            ),
            counts = PackManifest.Counts(
                vocabulary = sortedVocab.size,
                phrases = sortedPhrases.size,
                examples = sortedExamples.size,
                scenarios = sortedScenarios.size,
                dialogueTurns = sortedTurns.size,
                dialoguePairs = sortedPairs.size,
                audioAssets = sortedAssets.size,
            ),
            dataFiles = dataFiles,
        )
        val manifestBytes = json.encodeToString(PackManifest.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)

        // ---------- checksums (covers everything but itself) ----------
        val checksumLines = (dataPayloads.keys + audioPayloads.keys + "manifest.json").sorted()
            .joinToString("\n") { path ->
                val bytes = when (path) {
                    "manifest.json" -> manifestBytes
                    else -> dataPayloads[path] ?: audioPayloads.getValue(path)
                }
                "${sha256Hex(bytes)}  $path"
            } + "\n"
        val checksumBytes = checksumLines.toByteArray(Charsets.UTF_8)

        // ---------- zip ----------
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                val entry = ZipEntry(path)
                entry.time = FIXED_ZIP_TIME
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            put("manifest.json", manifestBytes)
            put("checksums.sha256", checksumBytes)
            dataPayloads.toSortedMap().forEach { (path, bytes) -> put(path, bytes) }
            audioPayloads.forEach { (path, bytes) -> put(path, bytes) }
        }

        // ---------- side outputs for the offline TTS pipeline ----------
        if (emitAudioTextDir != null) {
            emitAudioTextDir.mkdirs()
            sortedAssets.forEach { asset ->
                val text = corpus.audioTexts[asset.id].orEmpty()
                val sub = emitAudioTextDir.resolve(asset.relativePath.removePrefix("audio/"))
                sub.parentFile?.mkdirs()
                sub.writeText(text)
            }
        }
    }
}
