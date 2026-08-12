package com.bess.salestrainer.feature.scenario

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bess.salestrainer.core.model.DialogueSelfRating
import com.bess.salestrainer.core.model.AudioPlaybackState
import com.bess.salestrainer.core.model.PlaybackSpeed
import com.bess.salestrainer.core.model.ScenarioAdvance
import com.bess.salestrainer.core.model.ScenarioFilter
import com.bess.salestrainer.core.model.ScenarioSummary
import com.bess.salestrainer.core.model.ScenarioUnitView
import com.bess.salestrainer.core.model.SessionStatus
import com.bess.salestrainer.core.model.contract.AudioPlaybackRepository
import com.bess.salestrainer.core.model.contract.ScenarioRepository
import com.bess.salestrainer.core.model.contract.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScenarioListUiState(
    val scenarios: List<ScenarioSummary> = emptyList(),
    val filter: ScenarioFilter = ScenarioFilter(),
    val loading: Boolean = true,
)

data class ScenarioPracticeUiState(
    val sessionId: String? = null,
    val unitView: ScenarioUnitView? = null,
    val completed: Boolean = false,
    val ratingDistribution: Map<DialogueSelfRating, Int> = emptyMap(),
    val submitting: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScenarioViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val scenarioRepository: ScenarioRepository,
    private val audioPlaybackRepository: AudioPlaybackRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(ScenarioFilter())

    val listState: StateFlow<ScenarioListUiState> =
        _filter.flatMapLatest { f -> scenarioRepository.observeScenarios(f) }
            .let { flow ->
                kotlinx.coroutines.flow.combine(flow, _filter) { list, f ->
                    ScenarioListUiState(scenarios = list, filter = f, loading = false)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScenarioListUiState())

    private val _practiceState = MutableStateFlow(ScenarioPracticeUiState())
    val practiceState: StateFlow<ScenarioPracticeUiState> = _practiceState.asStateFlow()
    private var customerAudioJob: Job? = null
    private var autoPlayedPairId: String? = null

    private var sessionId: String?
        get() = savedStateHandle["scenario_session_id"]
        set(value) {
            savedStateHandle["scenario_session_id"] = value
        }

    fun updateFilter(filter: ScenarioFilter) {
        _filter.value = filter
    }

    fun startOrResume(scenarioId: String) {
        viewModelScope.launch {
            val random = scenarioId == RANDOM_SCENARIO_ROUTE_ID
            savedStateHandle["scenario_random_mode"] = random
            val id = sessionId ?: (
                if (random) scenarioRepository.startOrResumeRandom()
                else scenarioRepository.startOrResume(scenarioId)
                ).also { sessionId = it }
            observeSession(id)
        }
    }

    private fun observeSession(id: String) {
        viewModelScope.launch {
            scenarioRepository.observeCurrentUnit(id).collect { view ->
                val done = view.status == SessionStatus.COMPLETED
                _practiceState.value = _practiceState.value.copy(
                    sessionId = id,
                    unitView = view,
                    completed = done,
                )
                // Auto-play customer audio on a fresh pair (setting-gated).
                if (!done && !view.progress.customerAudioCompleted) {
                    val settings = settingsRepository.observeSettings().first()
                    if (settings.autoPlayCustomerAudio && autoPlayedPairId != view.unit.pairId) {
                        autoPlayedPairId = view.unit.pairId
                        playCustomerAudio(view.unit.customerAudioAssetId)
                    }
                }
            }
        }
    }

    fun playCustomerAudio(assetId: String) {
        val id = _practiceState.value.sessionId ?: return
        val pairId = _practiceState.value.unitView?.unit?.pairId ?: return
        customerAudioJob?.cancel()
        customerAudioJob = viewModelScope.launch {
            val settings = settingsRepository.observeSettings().first()
            audioPlaybackRepository.play(assetId, settings.playbackSpeed)
            val terminal = audioPlaybackRepository.observePlayback().first { snapshot ->
                snapshot.assetId == assetId &&
                    (snapshot.state == AudioPlaybackState.COMPLETED ||
                        snapshot.state == AudioPlaybackState.FAILED)
            }
            if (terminal.state == AudioPlaybackState.COMPLETED &&
                _practiceState.value.sessionId == id &&
                _practiceState.value.unitView?.unit?.pairId == pairId
            ) {
                scenarioRepository.markCustomerAudioCompleted(id, pairId)
            }
        }
    }

    fun revealCustomerText() = mutate { s, p -> scenarioRepository.revealCustomerText(s, p) }
    fun revealKeywords() = mutate { s, p -> scenarioRepository.revealKeywords(s, p) }
    fun revealAnswer() = mutate { s, p -> scenarioRepository.revealReferenceAnswer(s, p) }

    private fun mutate(block: suspend (String, String) -> Unit) {
        val s = _practiceState.value.sessionId ?: return
        val p = _practiceState.value.unitView?.unit?.pairId ?: return
        viewModelScope.launch { block(s, p) }
    }

    fun rate(rating: DialogueSelfRating) {
        val state = _practiceState.value
        val s = state.sessionId ?: return
        val p = state.unitView?.unit?.pairId ?: return
        if (state.submitting) return
        _practiceState.value = state.copy(submitting = true)
        viewModelScope.launch {
            try {
                when (val advance = scenarioRepository.rateAndAdvance(s, p, rating)) {
                    is ScenarioAdvance.NextPair -> Unit // flow emits the new unit
                    is ScenarioAdvance.Completed -> _practiceState.value =
                        _practiceState.value.copy(
                            completed = true,
                            ratingDistribution = advance.ratingDistribution,
                        )
                }
            } finally {
                _practiceState.value = _practiceState.value.copy(submitting = false)
            }
        }
    }

    fun leavePractice() {
        customerAudioJob?.cancel()
        val activeSession = sessionId
        val random = savedStateHandle["scenario_random_mode"] ?: false
        viewModelScope.launch {
            audioPlaybackRepository.stop()
            if (random && activeSession != null) {
                scenarioRepository.endRandomSession(activeSession)
            }
        }
        sessionId = null
        savedStateHandle["scenario_random_mode"] = false
        _practiceState.value = ScenarioPracticeUiState()
    }
}

const val RANDOM_SCENARIO_ROUTE_ID = "__RANDOM__"
