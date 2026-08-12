package com.bess.salestrainer.core.model

/** Platform-neutral reference to a user-selected document. */
data class BackupDocumentRef(val documentRef: String)

data class LearningBackupCounts(
    val wordMemoryStates: Int = 0,
    val reviewLogs: Int = 0,
    val vocabularyCheckpoints: Int = 0,
    val reviewActionKeys: Int = 0,
    val scenarioSessions: Int = 0,
    val scenarioTurnProgress: Int = 0,
    val studyTasks: Int = 0,
    val itemMemoryStates: Int = 0,
    val articleProgress: Int = 0,
) {
    val total: Int
        get() = wordMemoryStates + reviewLogs + vocabularyCheckpoints + reviewActionKeys +
            scenarioSessions + scenarioTurnProgress + studyTasks + itemMemoryStates +
            articleProgress
}

data class LearningBackupPreview(
    val previewId: String,
    val createdAtEpochMs: Long,
    val sourceAppVersionName: String,
    val sourceAppVersionCode: Int,
    val sourceDatabaseVersion: Int,
    val sourceCorpusPackageKey: String?,
    val sourceCorpusContentVersion: String?,
    val currentCorpusMatches: Boolean,
    val encrypted: Boolean,
    val counts: LearningBackupCounts,
)

sealed interface LearningBackupInspection {
    data class Ready(val preview: LearningBackupPreview) : LearningBackupInspection
    data object PasswordRequired : LearningBackupInspection
    data class Failure(val error: LearningBackupError) : LearningBackupInspection
}

sealed interface LearningBackupResult {
    data class Success(
        val affectedRecords: Int,
        val createdAtEpochMs: Long? = null,
    ) : LearningBackupResult

    data class Failure(val error: LearningBackupError) : LearningBackupResult
}

enum class LearningBackupError {
    SOURCE_UNREADABLE,
    DESTINATION_UNWRITABLE,
    PASSWORD_REQUIRED,
    WRONG_PASSWORD_OR_DAMAGED,
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
    FILE_TOO_LARGE,
    VALIDATION_FAILED,
    TRANSACTION_FAILED,
}

sealed interface LearningBackupState {
    data object Idle : LearningBackupState
    data object Reading : LearningBackupState
    data object Writing : LearningBackupState
    data object Validating : LearningBackupState
    data object Restoring : LearningBackupState
}

data class AppSupportInfo(
    val appVersionName: String,
    val appVersionCode: Int,
    val databaseVersion: Int,
    val packageName: String,
    val signingCertificateSha256: String,
    val androidVersion: String,
    val deviceAbi: String,
    val activeCorpusContentVersion: String?,
    val lastBackupAtEpochMs: Long?,
    val lastErrorCode: String?,
    val counts: LearningBackupCounts,
)
