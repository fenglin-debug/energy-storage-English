package com.bess.salestrainer.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bess.salestrainer.core.model.AppSettings
import com.bess.salestrainer.core.model.PlaybackSpeed
import com.bess.salestrainer.core.model.UpdateSettings
import com.bess.salestrainer.core.model.contract.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "bess_settings")

/** C-03: DataStore-backed settings (offline, synchronous reads via Flow). */
class SettingsRepositoryImpl(
    private val context: Context,
) : SettingsRepository {

    private object Keys {
        val PLAYBACK_SPEED = stringPreferencesKey("playback_speed")
        val DAILY_NEW_WORD_TARGET = intPreferencesKey("daily_new_word_target")
        val DESIRED_RETENTION = intPreferencesKey("desired_retention_percent")
        val AUTO_PLAY_CUSTOMER = booleanPreferencesKey("auto_play_customer_audio")
        val AUTO_PLAY_VOCAB = booleanPreferencesKey("auto_play_vocabulary_audio")
        val AUTO_PLAY_NEXT_ARTICLE = booleanPreferencesKey("auto_play_next_article")
        val DAILY_REMINDER_ENABLED = booleanPreferencesKey("daily_reminder_enabled")
        val DAILY_REMINDER_HOUR = intPreferencesKey("daily_reminder_hour")
        val DAILY_REMINDER_MINUTE = intPreferencesKey("daily_reminder_minute")
    }

    override fun observeSettings(): Flow<AppSettings> =
        context.settingsDataStore.data.map { prefs ->
            AppSettings(
                playbackSpeed = prefs[Keys.PLAYBACK_SPEED]
                    ?.let { runCatching { PlaybackSpeed.valueOf(it) }.getOrNull() }
                    ?: PlaybackSpeed.NORMAL,
                dailyNewWordTarget = prefs[Keys.DAILY_NEW_WORD_TARGET]?.coerceIn(0, 50) ?: 15,
                desiredRetentionPercent = prefs[Keys.DESIRED_RETENTION]?.coerceIn(85, 95) ?: 90,
                autoPlayCustomerAudio = prefs[Keys.AUTO_PLAY_CUSTOMER] ?: true,
                autoPlayVocabularyAudio = prefs[Keys.AUTO_PLAY_VOCAB] ?: true,
                autoPlayNextArticle = prefs[Keys.AUTO_PLAY_NEXT_ARTICLE] ?: true,
                dailyReminderEnabled = prefs[Keys.DAILY_REMINDER_ENABLED] ?: false,
                dailyReminderHour = prefs[Keys.DAILY_REMINDER_HOUR]?.coerceIn(0, 23) ?: 20,
                dailyReminderMinute = prefs[Keys.DAILY_REMINDER_MINUTE]?.coerceIn(0, 59) ?: 0,
            )
        }

    override suspend fun updateSettings(command: UpdateSettings) {
        context.settingsDataStore.edit { prefs ->
            command.playbackSpeed?.let { prefs[Keys.PLAYBACK_SPEED] = it.name }
            command.dailyNewWordTarget?.let {
                prefs[Keys.DAILY_NEW_WORD_TARGET] = it.coerceIn(0, 50)
            }
            command.desiredRetentionPercent?.let {
                prefs[Keys.DESIRED_RETENTION] = it.coerceIn(85, 95)
            }
            command.autoPlayCustomerAudio?.let { prefs[Keys.AUTO_PLAY_CUSTOMER] = it }
            command.autoPlayVocabularyAudio?.let { prefs[Keys.AUTO_PLAY_VOCAB] = it }
            command.autoPlayNextArticle?.let { prefs[Keys.AUTO_PLAY_NEXT_ARTICLE] = it }
            command.dailyReminderEnabled?.let { prefs[Keys.DAILY_REMINDER_ENABLED] = it }
            command.dailyReminderHour?.let { prefs[Keys.DAILY_REMINDER_HOUR] = it.coerceIn(0, 23) }
            command.dailyReminderMinute?.let {
                prefs[Keys.DAILY_REMINDER_MINUTE] = it.coerceIn(0, 59)
            }
        }
    }
}
