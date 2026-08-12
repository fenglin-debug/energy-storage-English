package com.bess.salestrainer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bess.salestrainer.core.model.AppSettings
import com.bess.salestrainer.core.model.AppSupportInfo
import com.bess.salestrainer.core.model.BackupDocumentRef
import com.bess.salestrainer.core.model.CorpusImportPreview
import com.bess.salestrainer.core.model.CorpusImportResult
import com.bess.salestrainer.core.model.CorpusImportState
import com.bess.salestrainer.core.model.CorpusMetadata
import com.bess.salestrainer.core.model.CorpusSource
import com.bess.salestrainer.core.model.PlaybackSpeed
import com.bess.salestrainer.core.model.LearningBackupInspection
import com.bess.salestrainer.core.model.LearningBackupResult
import com.bess.salestrainer.core.model.LearningBackupState
import com.bess.salestrainer.core.model.UpdateSettings
import com.bess.salestrainer.core.model.contract.CorpusRepository
import com.bess.salestrainer.core.model.contract.LearningBackupRepository
import com.bess.salestrainer.core.model.contract.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val corpusRepository: CorpusRepository,
    private val learningBackupRepository: LearningBackupRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.observeSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val activeCorpus: StateFlow<CorpusMetadata?> = corpusRepository.observeActiveCorpus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val importState: StateFlow<CorpusImportState> = corpusRepository.observeImportState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CorpusImportState.Idle)

    val backupState: StateFlow<LearningBackupState> = learningBackupRepository.observeState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LearningBackupState.Idle)

    private val _backupInspection = MutableStateFlow<LearningBackupInspection?>(null)
    val backupInspection: StateFlow<LearningBackupInspection?> = _backupInspection.asStateFlow()

    private val _backupResult = MutableStateFlow<LearningBackupResult?>(null)
    val backupResult: StateFlow<LearningBackupResult?> = _backupResult.asStateFlow()

    private val _supportInfo = MutableStateFlow<AppSupportInfo?>(null)
    val supportInfo: StateFlow<AppSupportInfo?> = _supportInfo.asStateFlow()

    private var pendingBackupSource: String? = null

    init {
        refreshSupportInfo()
    }

    private val _preview = MutableStateFlow<CorpusImportPreview?>(null)
    val preview: StateFlow<CorpusImportPreview?> = _preview.asStateFlow()

    private val _lastResult = MutableStateFlow<CorpusImportResult?>(null)
    val lastResult: StateFlow<CorpusImportResult?> = _lastResult.asStateFlow()

    fun setPlaybackSpeed(speed: PlaybackSpeed) =
        update(UpdateSettings(playbackSpeed = speed))

    fun setDailyNewWordTarget(target: Int) =
        update(UpdateSettings(dailyNewWordTarget = target))

    fun setAutoPlayCustomerAudio(enabled: Boolean) =
        update(UpdateSettings(autoPlayCustomerAudio = enabled))

    fun setAutoPlayVocabularyAudio(enabled: Boolean) =
        update(UpdateSettings(autoPlayVocabularyAudio = enabled))

    fun setAutoPlayNextArticle(enabled: Boolean) =
        update(UpdateSettings(autoPlayNextArticle = enabled))

    fun setDailyReminderEnabled(enabled: Boolean) =
        update(UpdateSettings(dailyReminderEnabled = enabled))

    fun setDailyReminderTime(hour: Int, minute: Int) =
        update(UpdateSettings(dailyReminderHour = hour, dailyReminderMinute = minute))

    private fun update(command: UpdateSettings) {
        viewModelScope.launch { settingsRepository.updateSettings(command) }
    }

    /** SAF entry: documentRef is the content:// Uri string. */
    fun inspectPackage(documentRef: String) {
        viewModelScope.launch {
            _lastResult.value = null
            _preview.value = corpusRepository.inspectPackage(CorpusSource(documentRef))
        }
    }

    fun confirmImport() {
        val p = _preview.value ?: return
        viewModelScope.launch {
            _lastResult.value = corpusRepository.activatePreview(p.previewId)
            _preview.value = null
        }
    }

    fun discardPreview() {
        val p = _preview.value ?: return
        viewModelScope.launch {
            corpusRepository.discardPreview(p.previewId)
            _preview.value = null
        }
    }

    fun restoreBundled() {
        viewModelScope.launch {
            _lastResult.value = corpusRepository.restoreBundledCorpus()
        }
    }

    fun exportLearningBackup(documentRef: String, password: String?) {
        viewModelScope.launch {
            _backupResult.value = learningBackupRepository.exportBackup(
                BackupDocumentRef(documentRef),
                password,
            )
            refreshSupportInfoInternal()
        }
    }

    fun inspectLearningBackup(documentRef: String, password: String? = null) {
        pendingBackupSource = documentRef
        viewModelScope.launch {
            _backupResult.value = null
            _backupInspection.value = learningBackupRepository.inspectBackup(
                BackupDocumentRef(documentRef),
                password,
            )
            refreshSupportInfoInternal()
        }
    }

    fun retryLearningBackupPassword(password: String) {
        pendingBackupSource?.let { inspectLearningBackup(it, password) }
    }

    fun restoreLearningBackup() {
        val ready = backupInspection.value as? LearningBackupInspection.Ready ?: return
        viewModelScope.launch {
            _backupResult.value = learningBackupRepository.restoreBackup(ready.preview.previewId)
            _backupInspection.value = null
            pendingBackupSource = null
            refreshSupportInfoInternal()
        }
    }

    fun discardLearningBackupPreview() {
        val ready = backupInspection.value as? LearningBackupInspection.Ready
        viewModelScope.launch {
            ready?.let { learningBackupRepository.discardPreview(it.preview.previewId) }
            _backupInspection.value = null
            pendingBackupSource = null
        }
    }

    fun clearBackupInspection() {
        _backupInspection.value = null
        pendingBackupSource = null
    }

    fun exportDiagnostics(documentRef: String) {
        viewModelScope.launch {
            _backupResult.value = learningBackupRepository.exportDiagnostics(
                BackupDocumentRef(documentRef),
            )
            refreshSupportInfoInternal()
        }
    }

    fun refreshSupportInfo() {
        viewModelScope.launch { refreshSupportInfoInternal() }
    }

    private suspend fun refreshSupportInfoInternal() {
        _supportInfo.value = learningBackupRepository.getSupportInfo()
    }
}
