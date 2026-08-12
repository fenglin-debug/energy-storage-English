package com.bess.salestrainer.core.model

data class AudioAsset(
    val id: String,
    val kind: AudioKind,
    val relativePath: String,
    val sha256: String,
    val mimeType: String,
    val codec: String,
    val durationMs: Long,
    val sizeBytes: Long,
)

data class AudioPlaybackSnapshot(
    val assetId: String?,
    val state: AudioPlaybackState,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
    val transition: AudioPlaybackTransition? = null,
    val error: AudioError? = null,
)

data class AudioQueueItem(
    val assetId: String,
    val title: String,
    val subtitle: String = "",
)

data class AudioPlaybackTransition(
    val fromAssetId: String?,
    val toAssetId: String?,
    val fromPositionMs: Long,
    val reason: AudioTransitionReason,
)

enum class AudioTransitionReason {
    AUTOMATIC,
    SEEK,
    REPEAT,
    PLAYLIST_CHANGED,
    STOPPED,
    UNKNOWN,
}

sealed interface AudioError {
    data class AssetNotFound(val assetId: String) : AudioError
    data class InvalidPath(val assetId: String) : AudioError
    data class IntegrityFailure(val assetId: String) : AudioError
    data class DecodeFailure(val assetId: String) : AudioError
    data class PlayerFailure(val code: String) : AudioError
}
