package com.bess.salestrainer.core.model

data class CorpusMetadata(
    val packageKey: String,
    val packageId: String,
    val schemaVersion: Int,
    val contentVersion: String,
    val vocabularyCount: Int,
    val scenarioCount: Int,
    val dialogueTurnCount: Int,
    val dialoguePairCount: Int,
    val audioAssetCount: Int,
    val isBundled: Boolean,
)

data class CorpusSource(
    val documentRef: String,
)

data class CorpusCounts(
    val vocabulary: Int,
    val scenarios: Int,
    val dialogueTurns: Int,
    val dialoguePairs: Int,
    val audioAssets: Int,
)

data class CorpusDiff(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val unchanged: Int,
)

data class CorpusSessionImpact(
    val vocabularyCheckpointCount: Int,
    val scenarioSessionCount: Int,
    val affectedScenarioIds: List<String> = emptyList(),
)

data class CorpusImportPreview(
    val previewId: String,
    val packageId: String,
    val schemaVersion: Int,
    val contentVersion: String,
    val minimumAppVersionCode: Int,
    val counts: CorpusCounts,
    val diff: CorpusDiff,
    val sessionImpact: CorpusSessionImpact,
    val compatible: Boolean,
    val errors: List<CorpusError> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** Lightweight audio status summary for the settings corpus info panel. */
data class AudioStatus(
    val totalAssets: Int,
    val verifiedOnDisk: Boolean,
)

sealed interface CorpusImportResult {
    data class Success(
        val packageKey: String,
        val contentVersion: String,
        val diff: CorpusDiff,
        val alreadyActive: Boolean = false,
    ) : CorpusImportResult

    data class Failure(val error: CorpusError) : CorpusImportResult
}
