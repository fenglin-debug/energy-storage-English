package com.bess.salestrainer.notification

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.bess.salestrainer.core.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager by lazy { WorkManager.getInstance(context) }
    private val preferences by lazy {
        context.getSharedPreferences("daily_reminder_schedule", Context.MODE_PRIVATE)
    }

    fun sync(settings: AppSettings) {
        if (!settings.dailyReminderEnabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            preferences.edit().remove(SCHEDULE_SIGNATURE).apply()
            return
        }

        val signature = "%02d:%02d".format(
            settings.dailyReminderHour,
            settings.dailyReminderMinute,
        )
        if (preferences.getString(SCHEDULE_SIGNATURE, null) == signature) return

        val initialDelay = nextReminderDelay(
            now = ZonedDateTime.now(),
            hour = settings.dailyReminderHour,
            minute = settings.dailyReminderMinute,
        )
        val request = PeriodicWorkRequest.Builder(
            DailyReminderWorker::class.java,
            24,
            TimeUnit.HOURS,
        )
            .setInitialDelay(initialDelay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        preferences.edit().putString(SCHEDULE_SIGNATURE, signature).apply()
    }

    companion object {
        const val WORK_NAME = "daily_study_reminder"
        private const val SCHEDULE_SIGNATURE = "schedule_signature"
    }
}

internal fun nextReminderDelay(now: ZonedDateTime, hour: Int, minute: Int): Duration {
    var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    if (!target.isAfter(now)) target = target.plusDays(1)
    return Duration.between(now, target)
}
