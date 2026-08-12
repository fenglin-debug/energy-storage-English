package com.bess.salestrainer.startup

import com.bess.salestrainer.core.model.contract.ArticleRepository
import com.bess.salestrainer.core.model.contract.CorpusRepository
import com.bess.salestrainer.core.model.contract.SettingsRepository
import com.bess.salestrainer.notification.DailyReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Activates bundled data without blocking Application.onCreate.
 *
 * Imports are idempotent and Room flows refresh visible screens after commit.
 * A failed import leaves the current database untouched and remains recoverable
 * from Settings.
 */
@Singleton
class AppStartupCoordinator @Inject constructor(
    private val corpusRepository: CorpusRepository,
    private val articleRepository: ArticleRepository,
    private val settingsRepository: SettingsRepository,
    private val reminderScheduler: DailyReminderScheduler,
    private val articlePlaybackProgressCoordinator: ArticlePlaybackProgressCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false

    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        articlePlaybackProgressCoordinator.start()
        scope.launch {
            runCatching { corpusRepository.ensureBundledCorpusActivated() }
            runCatching { articleRepository.ensureBundledArticlesActivated() }
        }
        scope.launch {
            settingsRepository.observeSettings()
                .map { settings ->
                    Triple(
                        settings.dailyReminderEnabled,
                        settings.dailyReminderHour,
                        settings.dailyReminderMinute,
                    ) to settings
                }
                .distinctUntilChanged { old, new -> old.first == new.first }
                .collect { (_, settings) -> reminderScheduler.sync(settings) }
        }
    }
}
