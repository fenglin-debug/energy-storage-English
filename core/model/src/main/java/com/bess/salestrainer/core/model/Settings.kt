package com.bess.salestrainer.core.model

data class AppSettings(
    val playbackSpeed: PlaybackSpeed = PlaybackSpeed.NORMAL,
    val dailyNewWordTarget: Int = 15,
    val desiredRetentionPercent: Int = 90,
    val autoPlayCustomerAudio: Boolean = true,
    val autoPlayVocabularyAudio: Boolean = true,
    val autoPlayNextArticle: Boolean = true,
    val dailyReminderEnabled: Boolean = false,
    val dailyReminderHour: Int = 20,
    val dailyReminderMinute: Int = 0,
) {
    init {
        require(dailyNewWordTarget in 0..50)
        require(desiredRetentionPercent in 85..95)
        require(dailyReminderHour in 0..23)
        require(dailyReminderMinute in 0..59)
    }
}

data class UpdateSettings(
    val playbackSpeed: PlaybackSpeed? = null,
    val dailyNewWordTarget: Int? = null,
    val desiredRetentionPercent: Int? = null,
    val autoPlayCustomerAudio: Boolean? = null,
    val autoPlayVocabularyAudio: Boolean? = null,
    val autoPlayNextArticle: Boolean? = null,
    val dailyReminderEnabled: Boolean? = null,
    val dailyReminderHour: Int? = null,
    val dailyReminderMinute: Int? = null,
)
