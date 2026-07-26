package com.bess.salestrainer.core.model

/** Playback request (FR-VOC-004, FR-DIA-003). */
data class SpeechPlaybackRequest(
    /** Corpus audio reference (e.g. packaged OGG path), if present takes priority over [ttsText]. */
    val audioRef: String? = null,
    /** Text for device TTS fallback. */
    val ttsText: String? = null,
    val speed: PlaybackSpeed = PlaybackSpeed.NORMAL,
)

enum class PlaybackSpeed(val factor: Float) {
    SLOW(0.85f), NORMAL(1.0f), FAST(1.15f)
}

/** Recording request (FR-DIA-004). */
data class RecordingRequest(
    val maxDurationSeconds: Int = 180,
    /** When false, audio is deleted right after metrics are computed. */
    val keepAudio: Boolean = true,
)

/** Result of stop + transcribe. */
data class SpeechAnalysis(
    val recordingId: String,
    val transcript: String,
    val durationMs: Long,
    val metrics: SpeechMetrics,
    /** File reference of the kept audio (AAC/M4A), null when not kept. */
    val audioFileRef: String?,
    /** True when on-device ASR produced the transcript; false when TTS/stub fallback used. */
    val usedOfflineAsr: Boolean,
)
