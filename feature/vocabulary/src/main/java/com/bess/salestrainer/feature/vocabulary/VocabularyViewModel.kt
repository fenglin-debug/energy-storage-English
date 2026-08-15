package com.bess.salestrainer.feature.vocabulary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.Vocabulary
import com.bess.salestrainer.core.model.VocabularyFilter
import com.bess.salestrainer.core.model.VocabularySessionView
import com.bess.salestrainer.core.model.VocabularySelfAssessment
import com.bess.salestrainer.core.model.contract.AudioPlaybackRepository
import com.bess.salestrainer.core.model.contract.SettingsRepository
import com.bess.salestrainer.core.model.contract.VocabularyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

data class VocabularyListUiState(
    val words: List<Vocabulary> = emptyList(),
    val filter: VocabularyFilter = VocabularyFilter(),
    val loading: Boolean = true,
)

data class VocabularyHomeUiState(
    val newCount: Int = 0,
    val dueCount: Int = 0,
    val loading: Boolean = true,
)

/** Practice session UI state; hides question text until reveal (accessibility gate). */
data class PracticeUiState(
    val sessionId: String? = null,
    val view: VocabularySessionView? = null,
    val completed: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VocabularyViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val vocabularyRepository: VocabularyRepository,
    private val audioPlaybackRepository: AudioPlaybackRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(VocabularyFilter())

    val listState: StateFlow<VocabularyListUiState> =
        _filter.flatMapLatest { f -> vocabularyRepository.observeVocabulary(f) }
            .let { flow ->
                kotlinx.coroutines.flow.combine(flow, _filter) { words, f ->
                    VocabularyListUiState(words = words, filter = f, loading = false)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VocabularyListUiState())

    val homeState: StateFlow<VocabularyHomeUiState> =
        vocabularyRepository.observeTodayQueue()
            .map {
                VocabularyHomeUiState(
                    newCount = it.newWords.size,
                    dueCount = it.dueReviews.size,
                    loading = false,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VocabularyHomeUiState())

    private val _practiceState = MutableStateFlow(PracticeUiState())
    val practiceState: StateFlow<PracticeUiState> = _practiceState.asStateFlow()

    /** Session id survives process death via SavedStateHandle. */
    private var sessionId: String?
        get() = savedStateHandle["vocab_session_id"]
        set(value) {
            savedStateHandle["vocab_session_id"] = value
        }

    fun updateFilter(filter: VocabularyFilter) {
        _filter.value = filter
    }

    fun toggleFavorite(wordId: String, favorite: Boolean) {
        viewModelScope.launch { vocabularyRepository.setFavorite(wordId, favorite) }
    }

    fun startOrResumePractice() {
        viewModelScope.launch {
            runCatching {
                sessionId ?: vocabularyRepository.startOrResumeSession().also { sessionId = it }
            }.onSuccess { id ->
                _practiceState.value = _practiceState.value.copy(error = null)
                observeSession(id)
            }.onFailure {
                sessionId = null
                _practiceState.value = _practiceState.value.copy(
                    error = "离线学习内容尚未准备完成，请返回后稍候重试",
                )
            }
        }
    }

    private fun observeSession(id: String) {
        viewModelScope.launch {
            vocabularyRepository.observeSession(id).collect { view ->
                val done = view.checkpoint.status ==
                    com.bess.salestrainer.core.model.VocabularySessionStatus.COMPLETED
                _practiceState.value = _practiceState.value.copy(
                    sessionId = id,
                    view = view,
                    completed = done,
                )
            }
        }
    }

    fun playWordAudio() {
        val word = _practiceState.value.view?.currentWord ?: return
        playAsset(word.wordAudioAssetId)
    }

    fun playExampleAudio(assetId: String) = playAsset(assetId)

    @Deprecated("Use playExampleAudio(assetId)")
    fun playExampleAudio() {
        _practiceState.value.view?.currentWord?.exampleAudioAssetId?.let(::playAsset)
    }

    fun revealAnswer() {
        val id = _practiceState.value.sessionId ?: return
        viewModelScope.launch {
            runCatching { vocabularyRepository.revealVocabularyAnswer(id) }
                .onFailure {
                    _practiceState.value = _practiceState.value.copy(error = "无法显示答案，请重试")
                }
        }
    }

    private fun playAsset(assetId: String) {
        viewModelScope.launch {
            val settings = settingsRepository.observeSettings().first()
            audioPlaybackRepository.play(assetId, settings.playbackSpeed)
        }
    }

    fun submitAssessment(assessment: VocabularySelfAssessment) {
        val state = _practiceState.value
        val id = state.sessionId ?: return
        val view = state.view ?: return
        val word = view.currentWord ?: return
        if (state.submitting) return
        _practiceState.value = state.copy(submitting = true)
        viewModelScope.launch {
            try {
                vocabularyRepository.submitAssessment(id, word.id, assessment)
            } catch (_: Throwable) {
                _practiceState.value = _practiceState.value.copy(
                    error = "学习进度保存失败，请返回词汇页后重试",
                )
            } finally {
                _practiceState.value = _practiceState.value.copy(submitting = false)
            }
        }
    }

    fun advanceToNext() {
        val state = _practiceState.value
        val id = state.sessionId ?: return
        if (state.submitting || state.view?.checkpoint?.assessmentSubmitted != true) return
        _practiceState.value = state.copy(submitting = true)
        viewModelScope.launch {
            try {
                vocabularyRepository.advanceToNext(id)
            } catch (_: Throwable) {
                _practiceState.value = _practiceState.value.copy(
                    error = "暂时无法进入下一条，请返回词汇页后重试",
                )
            } finally {
                _practiceState.value = _practiceState.value.copy(submitting = false)
            }
        }
    }

    @Deprecated("Use submitAssessment")
    fun rate(rating: Rating, shownAtMs: Long) {
        submitAssessment(
            when (rating) {
                Rating.AGAIN -> VocabularySelfAssessment.UNFAMILIAR
                Rating.HARD -> VocabularySelfAssessment.FUZZY
                Rating.GOOD, Rating.EASY -> VocabularySelfAssessment.MASTERED
            },
        )
    }

    fun leavePractice() {
        sessionId = null
        _practiceState.value = PracticeUiState()
    }
}

/** Question prompt respects conceal rule: hidden side never enters semantics. */
fun questionPromptFor(mode: QuestionMode, word: Vocabulary): String = when (mode) {
    QuestionMode.INTRODUCE -> word.term
    QuestionMode.EN2ZH -> word.term
    QuestionMode.ZH2EN -> word.chineseGloss
    QuestionMode.LISTENING -> "听音辨词"
    QuestionMode.TRANSFER -> word.exampleSentenceEn.replace(word.term, "____", ignoreCase = true)
}
