package com.bess.salestrainer.core.corpus.importing

import androidx.room.withTransaction
import com.bess.salestrainer.core.corpus.pack.PackScoringPointDto
import com.bess.salestrainer.core.corpus.pack.ParsedPack
import com.bess.salestrainer.core.corpus.pack.PackParser
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.dao.getAllHashes
import com.bess.salestrainer.core.database.entity.ActiveCorpusEntity
import com.bess.salestrainer.core.database.entity.AudioAssetEntity
import com.bess.salestrainer.core.database.entity.CorpusImportEventEntity
import com.bess.salestrainer.core.database.entity.CorpusVersionEntity
import com.bess.salestrainer.core.database.entity.DialoguePairEntity
import com.bess.salestrainer.core.database.entity.DialoguePairPhraseEntity
import com.bess.salestrainer.core.database.entity.DialoguePairWordEntity
import com.bess.salestrainer.core.database.entity.DialogueTurnEntity
import com.bess.salestrainer.core.database.entity.ExampleEntity
import com.bess.salestrainer.core.database.entity.PhraseEntity
import com.bess.salestrainer.core.database.entity.ScenarioEntity
import com.bess.salestrainer.core.database.entity.VocabularyAliasEntity
import com.bess.salestrainer.core.database.entity.VocabularyEntryEntity
import com.bess.salestrainer.core.model.CorpusDiff
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

/**
 * Atomic corpus importer (TDD §7.4): one Room transaction swaps all content
 * tables, writes audio files to the versioned corpus dir, flips the active
 * pointer and logs an import event. Any failure rolls the DB back; audio
 * files written before the failure are removed by the caller-side cleanup.
 */
class CorpusImporter(
    private val db: BessDatabase,
    private val corpusRootDir: File,
) {
    private val json = Json { encodeDefaults = true }

    data class ImportOutcome(
        val packageKey: String,
        val diff: CorpusDiff,
        val abortedVocabCheckpoints: Int,
        val abortedScenarioSessions: Int,
    )

    val packageKeyOf: (ParsedPack) -> String = { pack ->
        val packageId = safePathSegment(pack.manifest.packageId, "packageId")
        val contentVersion = safePathSegment(pack.manifest.contentVersion, "contentVersion")
        val identity = buildString {
            append(packageId).append('|').append(contentVersion)
            pack.vocabulary.sortedBy { it.id }.forEach { append('|').append(it.id).append(':').append(it.contentHash) }
            pack.phrases.sortedBy { it.id }.forEach { append('|').append(it.id).append(':').append(it.contentHash) }
            pack.examples.sortedBy { it.id }.forEach { append('|').append(it.id).append(':').append(it.contentHash) }
            pack.scenarios.sortedBy { it.id }.forEach { append('|').append(it.id).append(':').append(it.contentHash) }
            pack.audioAssets.sortedBy { it.id }.forEach { append('|').append(it.id).append(':').append(it.sha256) }
        }
        "$packageId-$contentVersion-${PackParser.sha256Hex(identity.toByteArray()).take(12)}"
    }

    /** Compute a preview diff without touching the database. */
    suspend fun diffAgainstActive(pack: ParsedPack): CorpusDiff {
        val newHashes = pack.vocabulary.associate { it.id to it.contentHash }
        var added = 0
        var updated = 0
        var unchanged = 0
        val existing = db.vocabularyDao().getAllHashes()
        newHashes.forEach { (id, hash) ->
            when (val old = existing[id]) {
                null -> added++
                hash -> unchanged++
                else -> updated++
            }
        }
        val removed = existing.keys.count { it !in newHashes }
        return CorpusDiff(added = added, updated = updated, removed = removed, unchanged = unchanged)
    }

    suspend fun countSessionImpact(): Pair<Int, Int> =
        db.vocabularyDao().inProgressCheckpointCount() to db.scenarioDao().getAllInProgress().size

    /**
     * Run the atomic swap. [nowEpochMs] is injected for deterministic tests.
     */
    suspend fun importAtomic(
        pack: ParsedPack,
        previewId: String?,
        isBundled: Boolean,
        nowEpochMs: Long,
    ): ImportOutcome {
        val packageKey = packageKeyOf(pack)
        val diff = diffAgainstActive(pack)
        val scenarioHashes = pack.scenarios.map { it.contentHash }

        corpusRootDir.mkdirs()
        val versionDir = resolveContained(corpusRootDir, packageKey)
        val stagingDir = resolveContained(
            corpusRootDir,
            ".$packageKey-${UUID.randomUUID()}.staging",
        )
        val backupDir = resolveContained(
            corpusRootDir,
            ".$packageKey-${UUID.randomUUID()}.backup",
        )
        var finalDirectoryInstalled = false
        var backupCreated = false
        var databaseCommitted = false

        var abortedCheckpoints = 0
        var abortedSessions = 0
        try {
            check(stagingDir.mkdirs()) { "Unable to create corpus staging directory" }
            pack.audioBytes.forEach { (relPath, bytes) ->
                val target = resolveContained(stagingDir, relPath)
                check(target.parentFile?.mkdirs() != false || target.parentFile?.isDirectory == true) {
                    "Unable to create audio directory"
                }
                target.writeBytes(bytes)
            }

            if (versionDir.exists()) {
                check(versionDir.renameTo(backupDir)) { "Unable to back up active corpus directory" }
                backupCreated = true
            }
            check(stagingDir.renameTo(versionDir)) { "Unable to activate staged corpus directory" }
            finalDirectoryInstalled = true

            db.withTransaction {
            val vocabDao = db.vocabularyDao()
            val scenarioDao = db.scenarioDao()
            val corpusDao = db.corpusDao()

            // Session impact: freeze in-flight work against stale content.
            abortedCheckpoints = vocabDao.expireAllInProgressCheckpoints(nowEpochMs)
            abortedSessions = if (scenarioHashes.isEmpty()) {
                scenarioDao.abortAllInProgress(nowEpochMs)
            } else {
                scenarioDao.abortInProgressWithStaleHash(scenarioHashes, nowEpochMs)
            }

            // Content swap.
            vocabDao.deleteAllAliases()
            vocabDao.deleteAllEntries()
            vocabDao.upsertAll(pack.vocabulary.map { w ->
                VocabularyEntryEntity(
                    id = w.id,
                    term = w.term,
                    normalizedTerm = w.normalizedTerm,
                    ipa = w.ipa,
                    partOfSpeech = w.partOfSpeech,
                    chineseGloss = w.chineseGloss,
                    englishDefinition = null,
                    collocationsJson = JsonArray(w.collocations.map { JsonPrimitive(it) }).toString(),
                    exampleSentenceEn = w.exampleSentenceEn,
                    exampleSentenceZh = w.exampleSentenceZh,
                    commonMistakes = w.commonMistakes,
                    topic = w.topic,
                    scenarioTagsJson = JsonArray(w.scenarioTags.map { JsonPrimitive(it) }).toString(),
                    cefrLevel = w.cefrLevel,
                    wordAudioAssetId = w.wordAudioAssetId,
                    exampleAudioAssetId = w.exampleAudioAssetId,
                    contentSource = w.contentSource,
                    contentHash = w.contentHash,
                    active = true,
                )
            })
            vocabDao.upsertAliases(pack.vocabulary.flatMap { w ->
                w.aliases.map { alias ->
                    VocabularyAliasEntity(
                        wordId = w.id,
                        alias = alias,
                        aliasNormalized = alias.lowercase().trim(),
                    )
                }
            })

            // v3: phrases + examples replace-all.
            val phraseDao = db.phraseDao()
            val exampleDao = db.exampleDao()
            phraseDao.deleteAll()
            phraseDao.upsertAll(pack.phrases.map { p ->
                PhraseEntity(
                    id = p.id,
                    industry = p.industry,
                    scene = p.scene,
                    category = p.category,
                    textEn = p.textEn,
                    textZh = p.textZh,
                    linkedTermIdsJson = JsonArray(p.linkedTermIds.map { JsonPrimitive(it) }).toString(),
                    sourceType = p.sourceType,
                    audioAssetId = p.audioAssetId,
                    contentHash = p.contentHash,
                    active = true,
                )
            })
            exampleDao.deleteAll()
            exampleDao.upsertAll(pack.examples.map { e ->
                ExampleEntity(
                    id = e.id,
                    industry = e.industry,
                    scene = e.scene,
                    speaker = e.speaker,
                    textEn = e.textEn,
                    textZh = e.textZh,
                    linkedTermIdsJson = JsonArray(e.linkedTermIds.map { JsonPrimitive(it) }).toString(),
                    dialogueGroupId = e.dialogueGroupId,
                    sourceType = e.sourceType,
                    audioAssetId = e.audioAssetId,
                    contentHash = e.contentHash,
                    active = true,
                )
            })

            scenarioDao.deleteAllPairPhrases()
            scenarioDao.deleteAllPairWords()
            scenarioDao.deleteAllPairs()
            scenarioDao.deleteAllTurns()
            scenarioDao.deleteAllScenarios()
            scenarioDao.upsertAll(pack.scenarios.map { s ->
                ScenarioEntity(
                    id = s.id,
                    title = s.title,
                    topic = s.topic,
                    salesStage = s.salesStage,
                    customerRole = s.customerRole,
                    difficulty = s.difficulty,
                    projectType = s.projectType,
                    estimatedMinutes = s.estimatedMinutes,
                    description = s.description,
                    contentHash = s.contentHash,
                    active = true,
                )
            })
            scenarioDao.upsertTurns(pack.turns.map { t ->
                DialogueTurnEntity(
                    id = t.id,
                    scenarioId = t.scenarioId,
                    turnNo = t.turnNo,
                    speaker = t.speaker,
                    textEn = t.textEn,
                    textZh = t.textZh,
                    hint = t.hint,
                    audioAssetId = t.audioAssetId,
                    contentHash = t.contentHash,
                )
            })
            scenarioDao.upsertPairs(pack.pairs.map { p ->
                DialoguePairEntity(
                    id = p.id,
                    scenarioId = p.scenarioId,
                    pairIndex = p.pairIndex,
                    customerTurnId = p.customerTurnId,
                    salesTurnId = p.salesTurnId,
                    referenceCoreEn = p.referenceCoreEn,
                    referenceChineseHint = p.referenceChineseHint,
                    formalAlternativesJson = JsonArray(
                        p.formalAlternatives.map { JsonPrimitive(it) },
                    ).toString(),
                    scoringPointsJson = json.encodeToString(
                        ListSerializer(PackScoringPointDto.serializer()),
                        p.scoringPoints,
                    ),
                    riskNote = p.riskNote,
                    contentHash = p.contentHash,
                )
            })
            val pairWordsWithOrder = pack.pairWords.map { link ->
                DialoguePairWordEntity(
                    pairId = link.pairId,
                    wordId = link.wordId,
                    sortOrder = link.sortOrder,
                )
            }
            scenarioDao.upsertPairWords(pairWordsWithOrder)
            scenarioDao.upsertPairPhrases(pack.pairPhrases.map { link ->
                DialoguePairPhraseEntity(
                    pairId = link.pairId,
                    phraseId = link.phraseId,
                    sortOrder = link.sortOrder,
                )
            })

            // Article audio has independent upsert semantics and must survive
            // a vocabulary/scenario corpus replacement.
            corpusDao.deleteCorpusAudioAssets()
            corpusDao.upsertAudioAssets(pack.audioAssets.map { a ->
                AudioAssetEntity(
                    id = a.id,
                    kind = a.kind,
                    relativePath = a.relativePath,
                    sha256 = a.sha256,
                    mimeType = a.mimeType,
                    codec = a.codec,
                    durationMs = a.durationMs,
                    sizeBytes = a.sizeBytes,
                )
            })

            corpusDao.upsertVersion(
                CorpusVersionEntity(
                    packageKey = packageKey,
                    packageId = pack.manifest.packageId,
                    schemaVersion = pack.manifest.schemaVersion,
                    contentVersion = pack.manifest.contentVersion,
                    vocabularyCount = pack.vocabulary.size,
                    scenarioCount = pack.scenarios.size,
                    dialogueTurnCount = pack.turns.size,
                    dialoguePairCount = pack.pairs.size,
                    audioAssetCount = pack.audioAssets.size,
                    manifestSha256 = PackParser.sha256Hex(
                        pack.manifest.toString().toByteArray(Charsets.UTF_8),
                    ),
                    isBundled = isBundled,
                    importedAtEpochMs = nowEpochMs,
                ),
            )
            corpusDao.setActive(ActiveCorpusEntity(packageKey = packageKey))
            corpusDao.insertImportEvent(
                CorpusImportEventEntity(
                    id = UUID.randomUUID().toString(),
                    previewId = previewId,
                    packageKey = packageKey,
                    resultCode = "SUCCESS",
                    addedCount = diff.added,
                    updatedCount = diff.updated,
                    removedCount = diff.removed,
                    unchangedCount = diff.unchanged,
                    createdAtEpochMs = nowEpochMs,
                ),
            )
            }
            databaseCommitted = true
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                if (finalDirectoryInstalled) versionDir.deleteRecursively()
                if (backupCreated) {
                    check(backupDir.renameTo(versionDir)) {
                        "Corpus transaction failed and the prior audio directory could not be restored"
                    }
                }
            }
            stagingDir.deleteRecursively()
            throw failure
        }

        backupDir.deleteRecursively()
        cleanOrphanDirs(keepPackageKey = packageKey)
        return ImportOutcome(
            packageKey = packageKey,
            diff = diff,
            abortedVocabCheckpoints = abortedCheckpoints,
            abortedScenarioSessions = abortedSessions,
        )
    }

    /** Remove version dirs that are not the active package. */
    fun cleanOrphanDirs(keepPackageKey: String) {
        corpusRootDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name != keepPackageKey) {
                dir.deleteRecursively()
            }
        }
    }

    /** Absolute audio dir for a package; used by the playback layer. */
    fun audioBaseDir(packageKey: String): File =
        resolveContained(
            corpusRootDir,
            packageKey.also {
                require(SAFE_PACKAGE_KEY.matches(it)) { "Unsafe packageKey" }
            },
        )

    private fun safePathSegment(value: String, field: String): String {
        require(SAFE_PATH_SEGMENT.matches(value)) { "Unsafe $field" }
        return value
    }

    private fun resolveContained(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, relativePath).canonicalFile
        require(candidate.toPath().startsWith(canonicalRoot.toPath())) {
            "Path escapes corpus root"
        }
        return candidate
    }

    private companion object {
        val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
        val SAFE_PACKAGE_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,359}")
    }
}
