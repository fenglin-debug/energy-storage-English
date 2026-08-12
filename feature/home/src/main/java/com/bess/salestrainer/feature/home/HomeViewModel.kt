package com.bess.salestrainer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bess.salestrainer.core.model.ResumeTarget
import com.bess.salestrainer.core.model.TodayStudyTask
import com.bess.salestrainer.core.model.contract.StudyTaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val task: TodayStudyTask? = null,
    val resumeTarget: ResumeTarget? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val studyTaskRepository: StudyTaskRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        studyTaskRepository.observeTodayTask(),
        studyTaskRepository.observeResumeTarget(),
    ) { task, resume ->
        HomeUiState(loading = false, task = task, resumeTarget = resume)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    init {
        viewModelScope.launch { studyTaskRepository.refreshTodayTask() }
    }
}
