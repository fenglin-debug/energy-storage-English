package com.bess.salestrainer.feature.sentence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bess.salestrainer.core.model.AudioPlaybackState
import com.bess.salestrainer.core.model.PlaybackSpeed
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.ReviewAdvance
import com.bess.salestrainer.core.model.SentenceExample
import com.bess.salestrainer.core.model.SentenceQueue
import com.bess.salestrainer.core.model.contract.AudioPlaybackRepository
import com.bess.salestrainer.core.model.contract.SentenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** 例句练习 list state. */
data class SentenceListState(
    val loading: Boolean = true,
    val queue: SentenceQueue? = null,
    val error: String? = null,
)

/** 例句练习 practice (flashcard) state. */
data class SentencePracticeState(
    val loading: Boolean = true,
    val sessionId: String? = null,
    val current: SentenceExample? = null,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val remainingCount: Int = 0,
    val questionMode: QuestionMode = QuestionMode.INTRODUCE,
    val answerRevealed: Boolean = false,
    val playingAudio: Boolean = false,
    val completed: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SentenceViewModel @Inject constructor(
    private val sentenceRepository: SentenceRepository,
    private val audioPlayback: AudioPlaybackRepository,
) : ViewModel() {

    private val _listState = MutableStateFlow(SentenceListState())
    val listState: StateFlow<SentenceListState> = _listState.asStateFlow()

    private val _practiceState = MutableStateFlow(SentencePracticeState())
    val practiceState: StateFlow<SentencePracticeState> = _practiceState.asStateFlow()

    init {
        viewModelScope.launch {
            sentenceRepository.observeTodayQueue().collect { queue ->
                _listState.update { it.copy(loading = false, queue = queue) }
            }
        }
        viewModelScope.launch {
            audioPlayback.observePlayback().collect { snapshot ->
                _practiceState.update {
                    it.copy(playingAudio = snapshot.state == AudioPlaybackState.PLAYING)
                }
            }
        }
    }

    fun startPractice() {
        viewModelScope.launch {
            _practiceState.update { it.copy(loading = true, completed = false, error = null) }
            try {
                val sessionId = sentenceRepository.startOrResumeSession()
                _practiceState.update { it.copy(sessionId = sessionId) }
                refreshCurrent(sessionId)
            } catch (e: Exception) {
                _practiceState.update {
                    it.copy(loading = false, error = e.message ?: "无法开始例句练习")
                }
            }
        }
    }

    private suspend fun refreshCurrent(sessionId: String) {
        // Session state is in-memory inside the repository; take a single
        // snapshot after each advance.
        sentenceRepository.observeSession().first().let { s ->
            if (s == null) {
                _practiceState.update { it.copy(loading = false, completed = true) }
            } else {
                _practiceState.update {
                    it.copy(
                        loading = false,
                        current = s.currentItem,
                        currentIndex = s.currentIndex,
                        totalCount = s.queueIds.size,
                        remainingCount = s.remainingCount,
                        questionMode = s.questionMode,
                        answerRevealed = s.answerRevealed,
                    )
                }
            }
        }
    }

    fun playCurrentAudio() {
        val current = _practiceState.value.current ?: return
        viewModelScope.launch {
            audioPlayback.play(current.audioAssetId, PlaybackSpeed.NORMAL)
        }
    }

    fun reveal() {
        val sessionId = _practiceState.value.sessionId ?: return
        viewModelScope.launch {
            sentenceRepository.revealAnswer(sessionId)
            _practiceState.update { it.copy(answerRevealed = true) }
        }
    }

    fun rate(rating: Rating) {
        val state = _practiceState.value
        val sessionId = state.sessionId ?: return
        val current = state.current ?: return
        viewModelScope.launch {
            try {
                val advance = sentenceRepository.recordReview(
                    sessionId = sessionId,
                    expectedItemId = current.id,
                    expectedIndex = state.currentIndex,
                    rating = rating,
                    reviewedAt = Instant.now(),
                )
                when (advance) {
                    is ReviewAdvance.Completed ->
                        _practiceState.update { it.copy(completed = true, current = null) }
                    is ReviewAdvance.Next -> refreshCurrent(sessionId)
                }
            } catch (e: Exception) {
                _practiceState.update { it.copy(error = e.message) }
            }
        }
    }

    fun stopAudio() {
        viewModelScope.launch { audioPlayback.stop() }
    }
}
