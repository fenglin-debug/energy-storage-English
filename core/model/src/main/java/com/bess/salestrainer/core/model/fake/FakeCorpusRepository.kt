package com.bess.salestrainer.core.model.fake

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FakeCorpusRepository : CorpusRepository {
    private val bundled = CorpusMetadata(
        packageKey = "bundled-fake-1",
        packageId = "bess-sales-english-core",
        schemaVersion = 1,
        contentVersion = "2026.07.26.fake",
        vocabularyCount = 462,
        scenarioCount = 60,
        dialogueTurnCount = 840,
        dialoguePairCount = 420,
        audioAssetCount = 1_344,
        isBundled = true,
    )
    private val active = MutableStateFlow<CorpusMetadata?>(bundled)
    private val importState = MutableStateFlow<CorpusImportState>(CorpusImportState.Idle)
    private val previews = ConcurrentHashMap<String, CorpusImportPreview>()

    override fun observeActiveCorpus(): Flow<CorpusMetadata?> = active

    override fun observeImportState(): Flow<CorpusImportState> = importState

    override suspend fun inspectPackage(source: CorpusSource): CorpusImportPreview {
        require(source.documentRef.isNotBlank())
        importState.value = CorpusImportState.Verifying
        val preview = CorpusImportPreview(
            previewId = UUID.randomUUID().toString(),
            packageId = "bess-sales-english-core",
            schemaVersion = 1,
            contentVersion = "2026.08.01.fake",
            minimumAppVersionCode = 1,
            counts = CorpusCounts(470, 60, 840, 420, 1_360),
            diff = CorpusDiff(8, 2, 0, 880),
            sessionImpact = CorpusSessionImpact(0, 0),
            compatible = true,
        )
        previews[preview.previewId] = preview
        importState.value = CorpusImportState.PreviewReady
        return preview
    }

    override suspend fun activatePreview(previewId: String): CorpusImportResult {
        val preview = previews.remove(previewId)
            ?: return CorpusImportResult.Failure(CorpusError.PreviewExpired)
        if (!preview.compatible || preview.errors.isNotEmpty()) {
            return CorpusImportResult.Failure(preview.errors.firstOrNull() ?: CorpusError.PreviewExpired)
        }
        importState.value = CorpusImportState.Committing
        val packageKey = "${preview.packageId}-${preview.contentVersion}"
        active.value = CorpusMetadata(
            packageKey = packageKey,
            packageId = preview.packageId,
            schemaVersion = preview.schemaVersion,
            contentVersion = preview.contentVersion,
            vocabularyCount = preview.counts.vocabulary,
            scenarioCount = preview.counts.scenarios,
            dialogueTurnCount = preview.counts.dialogueTurns,
            dialoguePairCount = preview.counts.dialoguePairs,
            audioAssetCount = preview.counts.audioAssets,
            isBundled = false,
        )
        importState.value = CorpusImportState.Idle
        return CorpusImportResult.Success(packageKey, preview.contentVersion, preview.diff)
    }

    override suspend fun discardPreview(previewId: String) {
        previews.remove(previewId)
        importState.value = CorpusImportState.Idle
    }

    override suspend fun restoreBundledCorpus(): CorpusImportResult {
        val alreadyActive = active.value == bundled
        active.value = bundled
        importState.value = CorpusImportState.Idle
        return CorpusImportResult.Success(
            packageKey = bundled.packageKey,
            contentVersion = bundled.contentVersion,
            diff = CorpusDiff(0, 0, 0, bundled.vocabularyCount + bundled.scenarioCount),
            alreadyActive = alreadyActive,
        )
    }

    override suspend fun ensureBundledCorpusActivated(): CorpusImportResult? =
        if (active.value == null) restoreBundledCorpus() else null
}
