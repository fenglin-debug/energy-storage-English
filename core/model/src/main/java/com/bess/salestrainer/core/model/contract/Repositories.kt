package com.bess.salestrainer.core.model.contract

import com.bess.salestrainer.core.model.AppSettings
import com.bess.salestrainer.core.model.AppSupportInfo
import com.bess.salestrainer.core.model.Article
import com.bess.salestrainer.core.model.ArticleDeletionResult
import com.bess.salestrainer.core.model.ArticleImportResult
import com.bess.salestrainer.core.model.AudioPlaybackSnapshot
import com.bess.salestrainer.core.model.AudioQueueItem
import com.bess.salestrainer.core.model.CorpusImportPreview
import com.bess.salestrainer.core.model.CorpusImportResult
import com.bess.salestrainer.core.model.CorpusImportState
import com.bess.salestrainer.core.model.CorpusMetadata
import com.bess.salestrainer.core.model.CorpusSource
import com.bess.salestrainer.core.model.LocalArticleAudioInspection
import com.bess.salestrainer.core.model.LocalArticleImportCommand
import com.bess.salestrainer.core.model.LocalArticleImportResult
import com.bess.salestrainer.core.model.LocalArticleInspectionResult
import com.bess.salestrainer.core.model.LocalArticleSubtitleInspection
import com.bess.salestrainer.core.model.BackupDocumentRef
import com.bess.salestrainer.core.model.LearningBackupInspection
import com.bess.salestrainer.core.model.LearningBackupResult
import com.bess.salestrainer.core.model.LearningBackupState
import com.bess.salestrainer.core.model.DialogueSelfRating
import com.bess.salestrainer.core.model.PlaybackSpeed
import com.bess.salestrainer.core.model.ResumeTarget
import com.bess.salestrainer.core.model.ScenarioAdvance
import com.bess.salestrainer.core.model.ScenarioFilter
import com.bess.salestrainer.core.model.ScenarioSessionSummary
import com.bess.salestrainer.core.model.ScenarioSummary
import com.bess.salestrainer.core.model.ScenarioUnitView
import com.bess.salestrainer.core.model.TodayStudyTask
import com.bess.salestrainer.core.model.UpdateSettings
import com.bess.salestrainer.core.model.Vocabulary
import com.bess.salestrainer.core.model.VocabularyFilter
import com.bess.salestrainer.core.model.VocabularyQueue
import com.bess.salestrainer.core.model.VocabularySessionView
import com.bess.salestrainer.core.model.VocabularySelfAssessment
import kotlinx.coroutines.flow.Flow

/**
 * Offline contract baseline v1, frozen at Gate 1.
 *
 * Queries expose [Flow], one-shot commands are suspending, and no Android,
 * Room, Media3, file-system or transport type crosses this boundary.
 */
interface VocabularyRepository {
    fun observeWord(wordId: String): Flow<Vocabulary>
    fun observeVocabulary(filter: VocabularyFilter): Flow<List<Vocabulary>>
    fun observeTodayQueue(): Flow<VocabularyQueue>
    fun observeSession(sessionId: String): Flow<VocabularySessionView>
    suspend fun startOrResumeSession(): String
    suspend fun revealVocabularyAnswer(sessionId: String)
    suspend fun submitAssessment(
        sessionId: String,
        itemId: String,
        assessment: VocabularySelfAssessment,
    )
    suspend fun advanceToNext(sessionId: String)
    suspend fun setFavorite(wordId: String, favorite: Boolean)
}

interface ScenarioRepository {
    fun observeScenarios(filter: ScenarioFilter): Flow<List<ScenarioSummary>>
    /** Pair ids (itemType=PAIR) due for FSRS review at [nowEpochMs]. */
    fun observeDuePairIds(nowEpochMs: Long): Flow<List<String>>
    fun observeCurrentUnit(sessionId: String): Flow<ScenarioUnitView>
    fun observeLatestInProgress(): Flow<ScenarioSessionSummary?>
    suspend fun startOrResume(scenarioId: String): String
    suspend fun startOrResumeRandom(): String
    suspend fun endRandomSession(sessionId: String)
    suspend fun markCustomerAudioCompleted(sessionId: String, pairId: String)
    suspend fun revealCustomerText(sessionId: String, pairId: String)
    suspend fun revealKeywords(sessionId: String, pairId: String)
    suspend fun revealReferenceAnswer(sessionId: String, pairId: String)
    suspend fun rateAndAdvance(
        sessionId: String,
        pairId: String,
        rating: DialogueSelfRating,
    ): ScenarioAdvance
}

interface StudyTaskRepository {
    fun observeTodayTask(): Flow<TodayStudyTask>
    fun observeResumeTarget(): Flow<ResumeTarget?>
    suspend fun refreshTodayTask()
}

interface CorpusRepository {
    fun observeActiveCorpus(): Flow<CorpusMetadata?>
    fun observeImportState(): Flow<CorpusImportState>
    suspend fun inspectPackage(source: CorpusSource): CorpusImportPreview
    suspend fun activatePreview(previewId: String): CorpusImportResult
    suspend fun discardPreview(previewId: String)
    suspend fun restoreBundledCorpus(): CorpusImportResult
    /** Activate first launch content and upgrade an active bundled corpus; never replaces user imports. */
    suspend fun ensureBundledCorpusActivated(): CorpusImportResult?
}

interface AudioPlaybackRepository {
    fun observePlayback(): Flow<AudioPlaybackSnapshot>
    suspend fun play(assetId: String, speed: PlaybackSpeed)
    suspend fun playQueue(
        items: List<AudioQueueItem>,
        startIndex: Int,
        startPositionMs: Long,
        speed: PlaybackSpeed,
        autoAdvance: Boolean,
        repeatAll: Boolean,
        reshuffleOnRepeat: Boolean = false,
    )
    suspend fun pause()
    suspend fun resume()
    suspend fun seekTo(positionMs: Long)
    suspend fun skipToNext()
    suspend fun skipToPrevious()
    suspend fun stop()
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun updateSettings(command: UpdateSettings)
}

/** Offline, user-directed export and replacement restore of learning state. */
interface LearningBackupRepository {
    fun observeState(): Flow<LearningBackupState>
    suspend fun exportBackup(
        destination: BackupDocumentRef,
        password: String?,
    ): LearningBackupResult
    suspend fun inspectBackup(
        source: BackupDocumentRef,
        password: String?,
    ): LearningBackupInspection
    suspend fun restoreBackup(previewId: String): LearningBackupResult
    suspend fun discardPreview(previewId: String)
    suspend fun getSupportInfo(): AppSupportInfo
    suspend fun exportDiagnostics(destination: BackupDocumentRef): LearningBackupResult
}

/** 文章朗读 (磨耳朵): bundled + imported article library. */
interface ArticleRepository {
    fun observeArticles(): Flow<List<Article>>
    fun observeArticle(articleId: String): Flow<Article?>
    suspend fun saveProgress(articleId: String, positionMs: Long, completed: Boolean)
    /** Parse + validate a user-picked .bessarticle; returns imported count. */
    suspend fun importArticlePack(source: CorpusSource): ArticleImportResult
    /** Inspects an audio selection before showing the local-import confirmation form. */
    suspend fun inspectLocalArticleAudio(
        source: CorpusSource,
        displayName: String,
    ): LocalArticleInspectionResult<LocalArticleAudioInspection>
    /** Parses and validates a selected subtitle against the inspected audio duration. */
    suspend fun inspectLocalArticleSubtitle(
        source: CorpusSource,
        displayName: String,
        audioDurationMs: Long,
    ): LocalArticleInspectionResult<LocalArticleSubtitleInspection>
    /** Imports audio plus optional SRT/LRC, updating an existing matching audio hash. */
    suspend fun importLocalArticle(command: LocalArticleImportCommand): LocalArticleImportResult
    /** Deletes a user-imported article, its progress, audio index and local audio file. */
    suspend fun deleteImportedArticle(articleId: String): ArticleDeletionResult
    /** First-launch bundled article activation (idempotent). */
    suspend fun ensureBundledArticlesActivated()
    suspend fun randomArticleIds(): List<String>
}
