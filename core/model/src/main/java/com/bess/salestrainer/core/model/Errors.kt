package com.bess.salestrainer.core.model

sealed interface CorpusError {
    data object SourceUnreadable : CorpusError
    data class InvalidZipPath(val entry: String) : CorpusError
    data class SizeLimitExceeded(val limitName: String) : CorpusError
    data class MissingManifestEntry(val entry: String) : CorpusError
    data class ChecksumMismatch(val entry: String) : CorpusError
    data class IncompatibleSchema(val actual: Int, val supported: Int) : CorpusError
    data class MinimumAppVersionNotMet(val required: Int) : CorpusError
    data class InvalidJson(val entry: String) : CorpusError
    data class DuplicateId(val id: String) : CorpusError
    data class InvariantViolation(val code: String) : CorpusError
    data class MissingAudio(val assetId: String) : CorpusError
    data class AnonymizationFailure(val code: String) : CorpusError
    data object InsufficientStorage : CorpusError
    data object PreviewExpired : CorpusError
    data class TransactionFailure(val code: String) : CorpusError
}

sealed interface StudyError {
    data object CorpusNotReady : StudyError
    data class ContentInactive(val stableId: String) : StudyError
    data class CheckpointExpired(val sessionId: String) : StudyError
    data class IllegalTransition(val sessionId: String, val action: String) : StudyError
    data class DatabaseFailure(val code: String) : StudyError
}
