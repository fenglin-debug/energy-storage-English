package com.bess.packager

import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/**
 * Build-side validator for schema-2 packs, mirroring the app-side checks:
 * path safety, checksum coverage, manifest consistency, business invariants.
 * Returns a list of violations; empty means valid.
 */
object PackValidator {

    private val json = Json { ignoreUnknownKeys = true }

    fun validate(pack: File): List<String> {
        val errors = mutableListOf<String>()
        if (!pack.isFile) return listOf("pack not found: ${pack.absolutePath}")

        ZipFile(pack).use { zip ->
            val entries = zip.entries().toList()
            val names = entries.map { it.name }

            // ---- path safety ----
            entries.forEach { entry ->
                val name = entry.name
                when {
                    name.contains("..") -> errors += "path traversal: $name"
                    name.startsWith("/") || name.contains("\\") -> errors += "illegal path: $name"
                    Regex("^[A-Za-z]:").containsMatchIn(name) -> errors += "drive letter in path: $name"
                }
            }
            if (names.size != names.toSet().size) errors += "duplicate zip entries"

            // ---- required files ----
            val required = listOf(
                "manifest.json",
                "checksums.sha256",
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
            required.forEach { if (it !in names) errors += "missing entry: $it" }
            if (errors.isNotEmpty()) return errors

            // ---- checksums ----
            val checksumText = zip.getInputStream(zip.getEntry("checksums.sha256")).readBytes()
                .toString(Charsets.UTF_8)
            val declared = checksumText.lines().filter { it.isNotBlank() }.associate { line ->
                val hash = line.substring(0, 64)
                val path = line.substring(66)
                path to hash
            }
            names.filter { it != "checksums.sha256" }.forEach { name ->
                val bytes = zip.getInputStream(zip.getEntry(name)).readBytes()
                val actual = sha256Hex(bytes)
                val expected = declared[name]
                when {
                    expected == null -> errors += "entry missing from checksums: $name"
                    expected != actual -> errors += "checksum mismatch: $name"
                }
            }
            declared.keys.filter { it !in names }.forEach { errors += "checksum refers to missing entry: $it" }

            // ---- manifest consistency ----
            val manifest = json.decodeFromString(
                PackManifest.serializer(),
                zip.getInputStream(zip.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8),
            )
            if (manifest.schemaVersion != 3) errors += "unsupported schemaVersion ${manifest.schemaVersion}"
            manifest.dataFiles.forEach { df ->
                val expected = declared[df.path]
                if (expected == null || expected != df.sha256) {
                    errors += "manifest dataFile sha mismatch: ${df.path}"
                }
                val entry = zip.getEntry(df.path)
                if (entry == null || entry.size != df.sizeBytes) {
                    errors += "manifest dataFile size mismatch: ${df.path}"
                }
            }

            // ---- business invariants ----
            fun <T> decodeList(path: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> =
                json.decodeFromString(
                    serializer,
                    zip.getInputStream(zip.getEntry(path)).readBytes().toString(Charsets.UTF_8),
                )

            val scenarios = decodeList("data/scenarios.json",
                kotlinx.serialization.builtins.ListSerializer(PackScenario.serializer()))
            val turns = decodeList("data/dialogue_turns.json",
                kotlinx.serialization.builtins.ListSerializer(PackDialogueTurn.serializer()))
            val pairs = decodeList("data/dialogue_pairs.json",
                kotlinx.serialization.builtins.ListSerializer(PackDialoguePair.serializer()))
            val vocabulary = decodeList("data/vocabulary.json",
                kotlinx.serialization.builtins.ListSerializer(PackVocabulary.serializer()))
            val phrases = decodeList("data/phrases.json",
                kotlinx.serialization.builtins.ListSerializer(PackPhrase.serializer()))
            val examples = decodeList("data/examples.json",
                kotlinx.serialization.builtins.ListSerializer(PackExample.serializer()))

            if (manifest.counts.scenarios != scenarios.size) errors += "manifest scenario count mismatch"
            if (manifest.counts.dialogueTurns != turns.size) errors += "manifest turn count mismatch"
            if (manifest.counts.dialoguePairs != pairs.size) errors += "manifest pair count mismatch"
            if (manifest.counts.vocabulary != vocabulary.size) errors += "manifest vocabulary count mismatch"
            if (manifest.counts.phrases != phrases.size) errors += "manifest phrase count mismatch"
            if (manifest.counts.examples != examples.size) errors += "manifest example count mismatch"

            val allIds = turns.map { it.id } + vocabulary.map { it.id } +
                phrases.map { it.id } + examples.map { it.id } + scenarios.map { it.id }
            if (allIds.size != allIds.toSet().size) errors += "duplicate ids across content"
            val norms = vocabulary.map { it.normalizedTerm }
            if (norms.size != norms.toSet().size) errors += "duplicate normalized terms"
            if (vocabulary.any { it.id.startsWith("WIND-") || it.contentSource == "WIND" }) {
                errors += "WIND vocabulary is forbidden in a BESS pack"
            }

            turns.groupBy { it.scenarioId }.forEach { (sid, group) ->
                val ordered = group.sortedBy { it.turnNo }
                if (ordered.map { it.turnNo } != (1..ordered.size).toList()) {
                    errors += "$sid: non-continuous turns"
                }
                ordered.forEachIndexed { index, turn ->
                    val expected = if (index % 2 == 0) "CUSTOMER" else "SALES_ENGINEER"
                    if (turn.speaker != expected) errors += "${turn.id}: expected $expected"
                }
            }

            // every pair references existing adjacent turns; audio present on both
            val turnById = turns.associateBy { it.id }
            pairs.forEach { pair ->
                val customer = turnById[pair.customerTurnId]
                val sales = turnById[pair.salesTurnId]
                if (customer == null || sales == null) {
                    errors += "${pair.id}: unresolved turn reference"
                } else {
                    if (customer.speaker != "CUSTOMER") errors += "${pair.id}: customerTurnId not CUSTOMER"
                    if (sales.speaker != "SALES_ENGINEER") errors += "${pair.id}: salesTurnId not SALES"
                    if (sales.turnNo != customer.turnNo + 1) errors += "${pair.id}: turns not adjacent"
                    if (customer.audioAssetId.isNullOrBlank()) errors += "${pair.id}: missing customer audio ref"
                    if (sales.audioAssetId.isNullOrBlank()) errors += "${pair.id}: missing sales audio ref"
                }
                if (pair.formalAlternatives.size !in 2..4) {
                    errors += "${pair.id}: formalAlternatives=${pair.formalAlternatives.size}"
                }
                if (pair.referenceChineseHint.isBlank()) errors += "${pair.id}: blank chinese hint"
            }

            // every declared audio asset exists in zip; every referenced asset declared
            val audioAssets = json.decodeFromString(
                AudioManifest.serializer(),
                zip.getInputStream(zip.getEntry("data/audio_manifest.json")).readBytes().toString(Charsets.UTF_8),
            ).assets
            val assetById = audioAssets.associateBy { it.id }
            val placeholderMarker = "BESS-PLACEHOLDER-AUDIO".toByteArray()
            audioAssets.forEach { asset ->
                val bytes = zip.getInputStream(zip.getEntry(asset.relativePath)).readBytes()
                if (
                    bytes.size >= placeholderMarker.size &&
                    bytes.copyOfRange(0, placeholderMarker.size).contentEquals(placeholderMarker)
                ) {
                    errors += "${asset.id}: placeholder audio is not decodable"
                }
            }

            fun checkAsset(ownerId: String, aid: String?) {
                if (aid.isNullOrBlank()) {
                    errors += "$ownerId: blank audio asset id"
                    return
                }
                val asset = assetById[aid] ?: run {
                    errors += "$ownerId: audio asset not declared: $aid"; return
                }
                if (asset.relativePath !in names) errors += "$ownerId: audio file missing: ${asset.relativePath}"
            }

            vocabulary.forEach { w ->
                checkAsset(w.id, w.wordAudioAssetId)
                checkAsset(w.id, w.exampleAudioAssetId)
            }
            phrases.forEach { p -> checkAsset(p.id, p.audioAssetId) }
            examples.forEach { e -> checkAsset(e.id, e.audioAssetId) }
            turns.forEach { t -> checkAsset(t.id, t.audioAssetId) }
            vocabulary.forEach { word ->
                if (1 + examples.count { word.id in it.linkedTermIds } < 2) {
                    errors += "${word.id}: fewer than two examples"
                }
            }
            phrases.forEach { phrase ->
                if (examples.count { phrase.id in it.linkedTermIds } < 2) {
                    errors += "${phrase.id}: fewer than two examples"
                }
            }
        }
        return errors
    }
}
