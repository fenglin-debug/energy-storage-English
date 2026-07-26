package com.bess.salestrainer.core.model.contract

import com.bess.salestrainer.core.model.AcceptTurnAttempt
import com.bess.salestrainer.core.model.AiAdvice
import com.bess.salestrainer.core.model.AiEvaluationResult
import com.bess.salestrainer.core.model.AppSettings
import com.bess.salestrainer.core.model.AsrModelState
import com.bess.salestrainer.core.model.ConnectionTestResult
import com.bess.salestrainer.core.model.CorpusImportResult
import com.bess.salestrainer.core.model.CorpusImportState
import com.bess.salestrainer.core.model.CorpusMetadata
import com.bess.salestrainer.core.model.CorpusPackagePreview
import com.bess.salestrainer.core.model.RecordWordReview
import com.bess.salestrainer.core.model.ResumeTarget
import com.bess.salestrainer.core.model.ReviewResult
import com.bess.salestrainer.core.model.ScenarioFilter
import com.bess.salestrainer.core.model.ScenarioSessionDetail
import com.bess.salestrainer.core.model.ScenarioSessionSummary
import com.bess.salestrainer.core.model.ScenarioSummary
import com.bess.salestrainer.core.model.SessionAdvance
import com.bess.salestrainer.core.model.SessionCompletion
import com.bess.salestrainer.core.model.SpeechAnalysis
import com.bess.salestrainer.core.model.SpeechPlaybackRequest
import com.bess.salestrainer.core.model.RecordingRequest
import com.bess.salestrainer.core.model.StartScenario
import com.bess.salestrainer.core.model.TodayStudyTask
import com.bess.salestrainer.core.model.UpdateSettings
import com.bess.salestrainer.core.model.Vocabulary
import com.bess.salestrainer.core.model.VocabularyFilter
import com.bess.salestrainer.core.model.VocabularyQueue
import kotlinx.coroutines.flow.Flow

/**
 * FROZEN PUBLIC CONTRACTS (per 03_Agent开发任务分解与协作规范 §4.2).
 * Owned exclusively by 主 Agent. Sub-agents may implement/consume but never modify.
 * Query = Flow, one-shot command = suspend. No Room/Retrofit/Context types leak here.
 */

interface VocabularyRepository {
    fun observeWord(wordId: String): Flow<Vocabulary>
    fun observeTodayQueue(): Flow<VocabularyQueue>
    fun observeVocabulary(filter: VocabularyFilter): Flow<List<Vocabulary>>
    suspend fun recordReview(command: RecordWordReview): ReviewResult
}

interface ScenarioRepository {
    fun observeScenarios(filter: ScenarioFilter): Flow<List<ScenarioSummary>>
    fun observeSession(sessionId: String): Flow<ScenarioSessionDetail>
    fun observeLatestInProgress(): Flow<ScenarioSessionSummary?>
    suspend fun startOrResume(command: StartScenario): String
    suspend fun acceptTurnAttempt(command: AcceptTurnAttempt): SessionAdvance
    suspend fun completeSession(sessionId: String): SessionCompletion
}

interface StudyTaskRepository {
    fun observeTodayTask(): Flow<TodayStudyTask>
    fun observeResumeTarget(): Flow<ResumeTarget?>
    suspend fun refreshTodayTask()
}

interface CorpusRepository {
    fun observeActiveCorpus(): Flow<CorpusMetadata>
    fun observeImportState(): Flow<CorpusImportState>
    suspend fun inspectPackage(uri: String): CorpusPackagePreview
    suspend fun importPackage(uri: String): CorpusImportResult
    suspend fun restoreBundledCorpus(): CorpusImportResult
}

interface SpeechRepository {
    fun observeAsrModelState(): Flow<AsrModelState>
    suspend fun play(request: SpeechPlaybackRequest)
    suspend fun stopPlayback()
    suspend fun startRecording(request: RecordingRequest): String
    suspend fun stopAndTranscribe(recordingId: String): SpeechAnalysis
    suspend fun cancelRecording(recordingId: String)
    suspend fun requestAsrModelDownload()
}

interface AiCoachRepository {
    fun observeAdvice(adviceId: String): Flow<AiAdvice>
    suspend fun evaluateSession(sessionId: String): AiEvaluationResult
    suspend fun retryEvaluation(adviceId: String): AiEvaluationResult
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun updateSettings(command: UpdateSettings)
    suspend fun saveDeepSeekKey(key: CharArray)
    suspend fun testDeepSeekConnection(): ConnectionTestResult
    suspend fun deleteDeepSeekKey()
    suspend fun hasDeepSeekKey(): Boolean
}
