package com.bess.salestrainer.core.model.fake

import com.bess.salestrainer.core.model.CorpusImportResult
import com.bess.salestrainer.core.model.CorpusImportState
import com.bess.salestrainer.core.model.CorpusMetadata
import com.bess.salestrainer.core.model.CorpusPackagePreview
import com.bess.salestrainer.core.model.contract.CorpusRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory fake for corpus metadata + import state machine. */
class FakeCorpusRepository : CorpusRepository {

    private val active = MutableStateFlow(
        CorpusMetadata(
            packageId = "bess-corpus", contentVersion = "v1.0-fake",
            vocabularyCount = 300, scenarioCount = 24, isBundled = true,
        )
    )

    private val importState = MutableStateFlow<CorpusImportState>(CorpusImportState.Idle)

    override fun observeActiveCorpus(): Flow<CorpusMetadata> = active

    override fun observeImportState(): Flow<CorpusImportState> = importState

    override suspend fun inspectPackage(uri: String): CorpusPackagePreview {
        importState.value = CorpusImportState.Selected
        return CorpusPackagePreview(
            packageId = "bess-corpus", schemaVersion = 1, contentVersion = "v1.1-import",
            vocabularyCount = 320, scenarioCount = 26, minimumAppVersionCode = 1,
            compatible = true, incompatibleReason = null,
        )
    }

    override suspend fun importPackage(uri: String): CorpusImportResult {
        importState.value = CorpusImportState.Copying
        delay(50)
        importState.value = CorpusImportState.VerifyingChecksums
        delay(50)
        importState.value = CorpusImportState.Validating
        delay(50)
        importState.value = CorpusImportState.Committing
        delay(50)
        active.value = active.value.copy(contentVersion = "v1.1-import", vocabularyCount = 320, scenarioCount = 26)
        importState.value = CorpusImportState.Idle
        return CorpusImportResult(
            addedCount = 20, updatedCount = 5, deactivatedCount = 2, unchangedCount = 295,
            newContentVersion = "v1.1-import",
        )
    }

    override suspend fun restoreBundledCorpus(): CorpusImportResult {
        active.value = active.value.copy(contentVersion = "v1.0-fake", isBundled = true)
        return CorpusImportResult(0, 0, 0, 300, "v1.0-fake")
    }
}
