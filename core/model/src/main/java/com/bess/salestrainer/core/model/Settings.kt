package com.bess.salestrainer.core.model

/** Non-sensitive app settings (persisted in DataStore; Key lives only in Keystore). */
data class AppSettings(
    val deepseekModelName: String = DEFAULT_DEEPSEEK_MODEL,
    val playbackSpeed: PlaybackSpeed = PlaybackSpeed.NORMAL,
    val dailyNewWordTarget: Int = 15,
    val dailyReviewLimit: Int = 60,
    val desiredRetentionPercent: Int = 90, // 85..95
    val recordingRetentionDays: Int = 30,  // 0 = don't keep, 7, 30
    val keepRecordings: Boolean = true,
    val asrModelUrlOverride: String? = null,
) {
    init {
        require(dailyNewWordTarget in 0..50)
        require(dailyReviewLimit in 1..200)
        require(desiredRetentionPercent in 85..95)
        require(recordingRetentionDays in listOf(0, 7, 30))
    }

    companion object {
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"
    }
}

/** Partial update command — null fields stay unchanged. */
data class UpdateSettings(
    val deepseekModelName: String? = null,
    val playbackSpeed: PlaybackSpeed? = null,
    val dailyNewWordTarget: Int? = null,
    val dailyReviewLimit: Int? = null,
    val desiredRetentionPercent: Int? = null,
    val recordingRetentionDays: Int? = null,
    val keepRecordings: Boolean? = null,
    val asrModelUrlOverride: String? = null,
)

/** Result of testing the DeepSeek connection. */
sealed interface ConnectionTestResult {
    data class Success(val modelName: String, val latencyMs: Long) : ConnectionTestResult
    data class Failure(val reason: AiFailureReason, val message: String) : ConnectionTestResult
}
