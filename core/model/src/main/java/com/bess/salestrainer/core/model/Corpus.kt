package com.bess.salestrainer.core.model

/** Active corpus metadata shown in settings (FR-CORPUS-001). */
data class CorpusMetadata(
    val packageId: String,
    val contentVersion: String,
    val vocabularyCount: Int,
    val scenarioCount: Int,
    val isBundled: Boolean,
)

/** Preview before importing a package (FR-CORPUS-002). */
data class CorpusPackagePreview(
    val packageId: String,
    val schemaVersion: Int,
    val contentVersion: String,
    val vocabularyCount: Int,
    val scenarioCount: Int,
    val minimumAppVersionCode: Int,
    val compatible: Boolean,
    val incompatibleReason: String?,
)

/** Diff report after a successful import (FR-CORPUS-002). */
data class CorpusImportResult(
    val addedCount: Int,
    val updatedCount: Int,
    val deactivatedCount: Int,
    val unchangedCount: Int,
    val newContentVersion: String,
)
