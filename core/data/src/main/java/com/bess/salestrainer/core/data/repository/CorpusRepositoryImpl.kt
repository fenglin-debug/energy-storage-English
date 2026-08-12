package com.bess.salestrainer.core.data.repository

import android.content.Context
import com.bess.salestrainer.core.corpus.importing.CorpusImporter
import com.bess.salestrainer.core.corpus.pack.PackParser
import com.bess.salestrainer.core.corpus.pack.ParsedPack
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.model.CorpusCounts
import com.bess.salestrainer.core.model.CorpusDiff
import com.bess.salestrainer.core.model.CorpusError
import com.bess.salestrainer.core.model.CorpusImportPreview
import com.bess.salestrainer.core.model.CorpusImportResult
import com.bess.salestrainer.core.model.CorpusImportState
import com.bess.salestrainer.core.model.CorpusMetadata
import com.bess.salestrainer.core.model.CorpusSessionImpact
import com.bess.salestrainer.core.model.CorpusSource
import com.bess.salestrainer.core.model.contract.CorpusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * C-04/C-05: real corpus import pipeline (TDD §7.4).
 *
 * SAF document -> copy to cache -> PackParser (6-stage validation) ->
 * preview (diff + session impact) -> activatePreview = atomic Room
 * transaction via [CorpusImporter]. Bundled asset .besspack restores the
 * factory corpus. Preview payloads live only in memory + cache; discarding
 * removes both.
 */
class CorpusRepositoryImpl(
    private val context: Context,
    private val db: BessDatabase,
    private val importer: CorpusImporter,
    private val bundledPackAssetPath: String = "corpus/bundled.besspack",
    private val appVersionCode: Int = Int.MAX_VALUE,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : CorpusRepository {

    private val importState = MutableStateFlow<CorpusImportState>(CorpusImportState.Idle)
    private val previews = ConcurrentHashMap<String, Pair<CorpusImportPreview, ParsedPack>>()

    override fun observeActiveCorpus(): Flow<CorpusMetadata?> =
        db.corpusDao().observeActive().map { v ->
            v?.let {
                CorpusMetadata(
                    packageKey = it.packageKey,
                    packageId = it.packageId,
                    schemaVersion = it.schemaVersion,
                    contentVersion = it.contentVersion,
                    vocabularyCount = it.vocabularyCount,
                    scenarioCount = it.scenarioCount,
                    dialogueTurnCount = it.dialogueTurnCount,
                    dialoguePairCount = it.dialoguePairCount,
                    audioAssetCount = it.audioAssetCount,
                    isBundled = it.isBundled,
                )
            }
        }

    override fun observeImportState(): Flow<CorpusImportState> = importState

    override suspend fun inspectPackage(source: CorpusSource): CorpusImportPreview =
        withContext(Dispatchers.IO) {
            importState.value = CorpusImportState.Copying
            val pack = try {
                importState.value = CorpusImportState.Verifying
                openSource(source.documentRef).use(PackParser::parse)
            } catch (e: PackParser.PackException) {
                importState.value = CorpusImportState.Failed(e.error)
                return@withContext failedPreview(e.error)
            } catch (e: Exception) {
                importState.value = CorpusImportState.Failed(CorpusError.SourceUnreadable)
                return@withContext failedPreview(CorpusError.SourceUnreadable)
            }

            importState.value = CorpusImportState.Parsing
            val (vocabImpact, scenarioImpact) = importer.countSessionImpact()
            val diff = importer.diffAgainstActive(pack)
            val versionError = pack.manifest.minimumAppVersionCode
                .takeIf { it > appVersionCode }
                ?.let(CorpusError::MinimumAppVersionNotMet)
            val preview = CorpusImportPreview(
                previewId = UUID.randomUUID().toString(),
                packageId = pack.manifest.packageId,
                schemaVersion = pack.manifest.schemaVersion,
                contentVersion = pack.manifest.contentVersion,
                minimumAppVersionCode = pack.manifest.minimumAppVersionCode,
                counts = CorpusCounts(
                    vocabulary = pack.vocabulary.size,
                    scenarios = pack.scenarios.size,
                    dialogueTurns = pack.turns.size,
                    dialoguePairs = pack.pairs.size,
                    audioAssets = pack.audioAssets.size,
                ),
                diff = diff,
                sessionImpact = CorpusSessionImpact(vocabImpact, scenarioImpact),
                compatible = versionError == null,
                errors = listOfNotNull(versionError),
            )
            previews[preview.previewId] = preview to pack
            importState.value = CorpusImportState.PreviewReady
            preview
        }

    override suspend fun activatePreview(previewId: String): CorpusImportResult {
        val entry = previews.remove(previewId)
            ?: return CorpusImportResult.Failure(CorpusError.PreviewExpired)
        val (preview, pack) = entry
        if (!preview.compatible || preview.errors.isNotEmpty()) {
            val error = preview.errors.firstOrNull() ?: CorpusError.PreviewExpired
            importState.value = CorpusImportState.Failed(error)
            return CorpusImportResult.Failure(error)
        }
        importState.value = CorpusImportState.Committing
        return try {
            val outcome = importer.importAtomic(
                pack = pack,
                previewId = previewId,
                isBundled = false,
                nowEpochMs = nowEpochMs(),
            )
            importState.value = CorpusImportState.Idle
            CorpusImportResult.Success(
                packageKey = outcome.packageKey,
                contentVersion = preview.contentVersion,
                diff = outcome.diff,
            )
        } catch (e: Exception) {
            importState.value = CorpusImportState.Failed(
                CorpusError.TransactionFailure(e.message?.take(60) ?: "unknown"),
            )
            CorpusImportResult.Failure(
                CorpusError.TransactionFailure(e.message?.take(60) ?: "unknown"),
            )
        }
    }

    override suspend fun discardPreview(previewId: String) {
        previews.remove(previewId)
        importState.value = CorpusImportState.Idle
    }

    override suspend fun restoreBundledCorpus(): CorpusImportResult = withContext(Dispatchers.IO) {
        importState.value = CorpusImportState.Verifying
        try {
            val pack = context.assets.open(bundledPackAssetPath).use { PackParser.parse(it) }
            val active = db.corpusDao().getActive()
            val key = importer.packageKeyOf(pack)
            if (active?.packageKey == key) {
                importState.value = CorpusImportState.Idle
                return@withContext CorpusImportResult.Success(
                    packageKey = key,
                    contentVersion = pack.manifest.contentVersion,
                    diff = CorpusDiff(0, 0, 0, pack.vocabulary.size + pack.scenarios.size),
                    alreadyActive = true,
                )
            }
            importState.value = CorpusImportState.Committing
            val outcome = importer.importAtomic(pack, null, isBundled = true, nowEpochMs = nowEpochMs())
            importState.value = CorpusImportState.Idle
            CorpusImportResult.Success(
                packageKey = outcome.packageKey,
                contentVersion = pack.manifest.contentVersion,
                diff = outcome.diff,
            )
        } catch (e: PackParser.PackException) {
            importState.value = CorpusImportState.Failed(e.error)
            CorpusImportResult.Failure(e.error)
        } catch (e: Exception) {
            importState.value = CorpusImportState.Failed(CorpusError.SourceUnreadable)
            CorpusImportResult.Failure(CorpusError.SourceUnreadable)
        }
    }

    /** First launch + bundled upgrade; an active user import is never replaced. */
    override suspend fun ensureBundledCorpusActivated(): CorpusImportResult? {
        val active = db.corpusDao().getActive()
        if (active != null && !active.isBundled) return null
        return restoreBundledCorpus()
    }

    private fun openSource(documentRef: String): java.io.InputStream {
        val uri = android.net.Uri.parse(documentRef)
        context.contentResolver.openInputStream(uri)?.let { return it }
        // Fallback for tests / file paths.
        val file = File(documentRef)
        if (file.isFile) return FileInputStream(file)
        throw IllegalArgumentException("unreadable source: $documentRef")
    }

    private fun failedPreview(error: CorpusError) = CorpusImportPreview(
        previewId = UUID.randomUUID().toString(),
        packageId = "",
        schemaVersion = 0,
        contentVersion = "",
        minimumAppVersionCode = 0,
        counts = CorpusCounts(0, 0, 0, 0, 0),
        diff = CorpusDiff(0, 0, 0, 0),
        sessionImpact = CorpusSessionImpact(0, 0),
        compatible = false,
        errors = listOf(error),
    )
}
