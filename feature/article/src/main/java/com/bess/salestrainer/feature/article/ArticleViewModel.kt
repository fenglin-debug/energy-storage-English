package com.bess.salestrainer.feature.article

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bess.salestrainer.core.model.Article
import com.bess.salestrainer.core.model.ArticleDeletionResult
import com.bess.salestrainer.core.model.AudioPlaybackState
import com.bess.salestrainer.core.model.AudioQueueItem
import com.bess.salestrainer.core.model.CorpusSource
import com.bess.salestrainer.core.model.LocalArticleAudioInspection
import com.bess.salestrainer.core.model.LocalArticleImportCommand
import com.bess.salestrainer.core.model.LocalArticleImportError
import com.bess.salestrainer.core.model.LocalArticleImportStatus
import com.bess.salestrainer.core.model.LocalArticleSubtitleInspection
import com.bess.salestrainer.core.model.PlaybackSpeed
import com.bess.salestrainer.core.model.contract.ArticleRepository
import com.bess.salestrainer.core.model.contract.AudioPlaybackRepository
import com.bess.salestrainer.core.model.contract.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 文章库 list state. */
data class ArticleListState(
    val loading: Boolean = true,
    val articles: List<Article> = emptyList(),
    val importing: Boolean = false,
    val importMessage: String? = null,
    val deletingArticleId: String? = null,
    val deleteMessage: String? = null,
    val localImportDraft: LocalArticleImportDraft? = null,
)

data class LocalArticleImportDraft(
    val audioSource: CorpusSource,
    val audioDisplayName: String,
    val title: String,
    val topic: String = "本地导入",
    val includeInRandomPractice: Boolean = true,
    val audioInfo: LocalArticleAudioInspection? = null,
    val subtitleSource: CorpusSource? = null,
    val subtitleDisplayName: String? = null,
    val subtitleInfo: LocalArticleSubtitleInspection? = null,
    val inspectingAudio: Boolean = true,
    val inspectingSubtitle: Boolean = false,
    val validationMessage: String? = null,
)

/** 文章播放 state (foreground-only, D4). */
data class ArticlePlayerState(
    val loading: Boolean = true,
    val article: Article? = null,
    val playing: Boolean = false,
    val paused: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    val randomMode: Boolean = false,
    val canGoNext: Boolean = false,
    val queueSize: Int = 0,
)

@HiltViewModel
class ArticleViewModel @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val audioPlayback: AudioPlaybackRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _listState = MutableStateFlow(ArticleListState())
    val listState: StateFlow<ArticleListState> = _listState.asStateFlow()

    private val _playerState = MutableStateFlow(ArticlePlayerState())
    val playerState: StateFlow<ArticlePlayerState> = _playerState.asStateFlow()

    private var articleObservationJob: Job? = null
    private var randomQueue: List<String> = emptyList()
    private var autoPlayNextArticle = true
    private var playbackSpeed = PlaybackSpeed.NORMAL

    init {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                autoPlayNextArticle = settings.autoPlayNextArticle
                playbackSpeed = settings.playbackSpeed
            }
        }
        viewModelScope.launch {
            articleRepository.observeArticles().collect { articles ->
                _listState.update { it.copy(loading = false, articles = articles) }
                val currentId = _playerState.value.article?.id
                articles.firstOrNull { it.id == currentId }?.let { refreshed ->
                    _playerState.update { it.copy(article = refreshed) }
                }
            }
        }
        viewModelScope.launch {
            audioPlayback.observePlayback().collect { snapshot ->
                val matchingArticle = _listState.value.articles
                    .firstOrNull { it.audioAssetId == snapshot.assetId }
                if (
                    matchingArticle != null &&
                    matchingArticle.id != _playerState.value.article?.id
                ) {
                    openResolvedArticle(
                        articleId = matchingArticle.id,
                        autoPlay = false,
                        randomMode = _playerState.value.randomMode,
                    )
                }
                val current = matchingArticle ?: _playerState.value.article ?: return@collect
                if (snapshot.assetId != current.audioAssetId) return@collect
                _playerState.update {
                    it.copy(
                        article = current,
                        playing = snapshot.state == AudioPlaybackState.PLAYING,
                        paused = snapshot.state == AudioPlaybackState.PAUSED,
                        positionMs = snapshot.positionMs,
                        durationMs = if (snapshot.durationMs > 0) snapshot.durationMs else it.durationMs,
                        queueSize = snapshot.queueSize,
                        error = when (snapshot.state) {
                            AudioPlaybackState.FAILED -> "本地音频不可用，请检查语料包后重试"
                            else -> null
                        },
                    )
                }
                if (snapshot.state == AudioPlaybackState.COMPLETED) {
                    articleRepository.saveProgress(current.id, current.durationMs, completed = true)
                    _playerState.update {
                        it.copy(
                            playing = false,
                            paused = false,
                            positionMs = current.durationMs,
                        )
                    }
                }
            }
        }
    }

    fun openArticle(articleId: String) {
        if (articleId == RANDOM_ARTICLE_ROUTE_ID) {
            openRandomArticle()
            return
        }
        openResolvedArticle(articleId, autoPlay = false, randomMode = false)
    }

    private fun openRandomArticle() {
        viewModelScope.launch {
            randomQueue = articleRepository.randomArticleIds()
            val id = randomQueue.firstOrNull()
            if (id == null) {
                _playerState.update {
                    it.copy(loading = false, error = "没有可用于随机练习的储能文章")
                }
            } else {
                openResolvedArticle(id, autoPlay = true, randomMode = true)
            }
        }
    }

    private fun openResolvedArticle(articleId: String, autoPlay: Boolean, randomMode: Boolean) {
        articleObservationJob?.cancel()
        articleObservationJob = viewModelScope.launch {
            var autoPlayStarted = false
            _playerState.update { it.copy(loading = true, error = null) }
            articleRepository.observeArticle(articleId).collect { article ->
                _playerState.update {
                    val changedArticle = it.article?.id != article?.id
                    it.copy(
                        loading = false,
                        article = article,
                        durationMs = article?.durationMs ?: 0,
                        positionMs = if (changedArticle) {
                            article?.progress?.lastPositionMs ?: 0L
                        } else {
                            it.positionMs
                        },
                        randomMode = randomMode,
                        canGoNext = randomMode || _listState.value.articles.size > 1,
                        error = if (article == null) "文章不存在或尚未完成导入" else it.error,
                    )
                }
                if (autoPlay && article != null && !autoPlayStarted) {
                    autoPlayStarted = true
                    startQueuePlayback(article)
                }
            }
        }
    }

    fun nextArticle() {
        val current = _playerState.value.article ?: return
        viewModelScope.launch {
            persistPosition()
            val state = _playerState.value
            if (state.queueSize > 1 && (state.playing || state.paused)) {
                audioPlayback.skipToNext()
                return@launch
            }
            val ids = if (state.randomMode) {
                randomQueue
            } else {
                _listState.value.articles.map { it.id }
            }
            val currentIndex = ids.indexOf(current.id)
            val nextIndex = if (currentIndex in ids.indices) {
                (currentIndex + 1).mod(ids.size.coerceAtLeast(1))
            } else {
                0
            }
            ids.getOrNull(nextIndex)?.let {
                openResolvedArticle(it, autoPlay = false, randomMode = state.randomMode)
            }
        }
    }

    fun previousArticle() {
        val current = _playerState.value.article ?: return
        viewModelScope.launch {
            persistPosition()
            val state = _playerState.value
            if (state.queueSize > 1 && (state.playing || state.paused)) {
                audioPlayback.skipToPrevious()
                return@launch
            }
            val ids = if (state.randomMode) {
                randomQueue
            } else {
                _listState.value.articles.map { it.id }
            }
            val currentIndex = ids.indexOf(current.id)
            val previousIndex = if (currentIndex > 0) currentIndex - 1 else ids.lastIndex
            ids.getOrNull(previousIndex)?.let {
                openResolvedArticle(it, autoPlay = false, randomMode = state.randomMode)
            }
        }
    }

    /** Play (or resume from saved progress). */
    fun play() {
        val article = _playerState.value.article ?: return
        viewModelScope.launch {
            if (_playerState.value.paused) {
                audioPlayback.resume()
            } else {
                startQueuePlayback(article)
            }
        }
    }

    fun pause() {
        viewModelScope.launch {
            audioPlayback.pause()
            persistPosition()
        }
    }

    fun seekTo(positionMs: Long) {
        viewModelScope.launch {
            audioPlayback.seekTo(positionMs)
            persistPosition()
        }
    }

    fun seekBy(deltaMs: Long) {
        val state = _playerState.value
        val duration = state.durationMs.takeIf { it > 0 } ?: state.article?.durationMs ?: return
        seekTo((state.positionMs + deltaMs).coerceIn(0L, duration))
    }

    fun stopAndLeave() {
        viewModelScope.launch {
            persistPosition()
            audioPlayback.stop()
        }
    }

    private suspend fun startQueuePlayback(article: Article) {
        val state = _playerState.value
        val articlesById = _listState.value.articles.associateBy { it.id }
        val orderedArticles = if (state.randomMode) {
            randomQueue.mapNotNull(articlesById::get)
        } else {
            _listState.value.articles
        }.ifEmpty { listOf(article) }
        val startIndex = orderedArticles.indexOfFirst { it.id == article.id }
            .takeIf { it >= 0 } ?: 0
        val savedPosition = article.progress?.lastPositionMs ?: state.positionMs
        val startAt = if (savedPosition >= article.durationMs - 10_000L) 0L else savedPosition
        audioPlayback.playQueue(
            items = orderedArticles.map {
                AudioQueueItem(
                    assetId = it.audioAssetId,
                    title = it.titleZh.ifBlank { it.title },
                    subtitle = it.topic,
                )
            },
            startIndex = startIndex,
            startPositionMs = startAt.coerceAtLeast(0L),
            speed = playbackSpeed,
            autoAdvance = autoPlayNextArticle,
            repeatAll = orderedArticles.size > 1,
            reshuffleOnRepeat = state.randomMode,
        )
    }

    private suspend fun persistPosition() {
        val state = _playerState.value
        val article = state.article ?: return
        if (state.positionMs > 0) {
            articleRepository.saveProgress(article.id, state.positionMs, completed = false)
        }
    }

    /** Import a user-picked .bessarticle (SAF document URI string). */
    fun importPack(documentRef: String) {
        viewModelScope.launch {
            _listState.update { it.copy(importing = true, importMessage = null) }
            val result = articleRepository.importArticlePack(CorpusSource(documentRef))
            _listState.update {
                it.copy(
                    importing = false,
                    importMessage = if (result.errors.isEmpty()) {
                        "成功导入 ${result.imported} 篇文章"
                    } else {
                        "导入失败：${result.errors.first()}"
                    },
                )
            }
        }
    }

    fun beginLocalAudioImport(documentRef: String, displayName: String) {
        val source = CorpusSource(documentRef)
        _listState.update {
            it.copy(
                localImportDraft = LocalArticleImportDraft(
                    audioSource = source,
                    audioDisplayName = displayName,
                    title = displayName.substringBeforeLast('.').ifBlank { "本地音频" },
                ),
                importMessage = null,
            )
        }
        viewModelScope.launch {
            val result = articleRepository.inspectLocalArticleAudio(source, displayName)
            _listState.update { state ->
                val draft = state.localImportDraft
                if (draft?.audioSource != source) return@update state
                state.copy(
                    localImportDraft = draft.copy(
                        audioInfo = result.value,
                        inspectingAudio = false,
                        validationMessage = result.error?.toUserMessage(),
                    ),
                )
            }
        }
    }

    fun updateLocalImportTitle(value: String) {
        updateLocalDraft { it.copy(title = value, validationMessage = null) }
    }

    fun updateLocalImportTopic(value: String) {
        updateLocalDraft { it.copy(topic = value, validationMessage = null) }
    }

    fun updateLocalImportRandom(value: Boolean) {
        updateLocalDraft { it.copy(includeInRandomPractice = value) }
    }

    fun selectLocalSubtitle(documentRef: String, displayName: String) {
        val source = CorpusSource(documentRef)
        val duration = _listState.value.localImportDraft?.audioInfo?.durationMs ?: return
        updateLocalDraft {
            it.copy(
                subtitleSource = source,
                subtitleDisplayName = displayName,
                subtitleInfo = null,
                inspectingSubtitle = true,
                validationMessage = null,
            )
        }
        viewModelScope.launch {
            val result = articleRepository.inspectLocalArticleSubtitle(source, displayName, duration)
            _listState.update { state ->
                val draft = state.localImportDraft
                if (draft?.subtitleSource != source) return@update state
                state.copy(
                    localImportDraft = draft.copy(
                        subtitleInfo = result.value,
                        inspectingSubtitle = false,
                        validationMessage = result.error?.toUserMessage(),
                    ),
                )
            }
        }
    }

    fun removeLocalSubtitle() {
        updateLocalDraft {
            it.copy(
                subtitleSource = null,
                subtitleDisplayName = null,
                subtitleInfo = null,
                inspectingSubtitle = false,
                validationMessage = null,
            )
        }
    }

    fun dismissLocalImport() {
        if (_listState.value.importing) return
        _listState.update { it.copy(localImportDraft = null) }
    }

    fun submitLocalImport() {
        val draft = _listState.value.localImportDraft ?: return
        if (draft.title.isBlank()) {
            updateLocalDraft { it.copy(validationMessage = "标题不能为空") }
            return
        }
        if (draft.audioInfo == null || draft.inspectingAudio || draft.inspectingSubtitle) return
        if (draft.subtitleSource != null && draft.subtitleInfo == null) return
        viewModelScope.launch {
            _listState.update { it.copy(importing = true, importMessage = null) }
            val result = articleRepository.importLocalArticle(
                LocalArticleImportCommand(
                    audioSource = draft.audioSource,
                    audioDisplayName = draft.audioDisplayName,
                    subtitleSource = draft.subtitleSource,
                    subtitleDisplayName = draft.subtitleDisplayName,
                    title = draft.title,
                    topic = draft.topic,
                    includeInRandomPractice = draft.includeInRandomPractice,
                ),
            )
            val importError = result.error
            _listState.update { state ->
                if (importError == null) {
                    state.copy(
                        importing = false,
                        localImportDraft = null,
                        importMessage = when (result.status) {
                            LocalArticleImportStatus.UPDATED -> "已更新文章，原收听进度已保留"
                            else -> "文章已导入，可离线播放"
                        },
                    )
                } else {
                    state.copy(
                        importing = false,
                        localImportDraft = state.localImportDraft?.copy(
                            validationMessage = importError.toUserMessage(),
                        ),
                    )
                }
            }
        }
    }

    private fun updateLocalDraft(transform: (LocalArticleImportDraft) -> LocalArticleImportDraft) {
        _listState.update { state ->
            state.copy(localImportDraft = state.localImportDraft?.let(transform))
        }
    }

    fun clearImportMessage() {
        _listState.update { it.copy(importMessage = null) }
    }

    fun deleteImportedArticle(articleId: String) {
        if (_listState.value.deletingArticleId != null) return
        viewModelScope.launch {
            _listState.update {
                it.copy(deletingArticleId = articleId, deleteMessage = null)
            }
            try {
                val result = articleRepository.deleteImportedArticle(articleId)
                _listState.update {
                    it.copy(
                        deleteMessage = when (result) {
                            ArticleDeletionResult.DELETED -> "已删除导入文章及其本地音频"
                            ArticleDeletionResult.NOT_FOUND -> "文章已不存在"
                            ArticleDeletionResult.BUNDLED_PROTECTED -> "内置文章不能删除"
                        },
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _listState.update {
                    it.copy(deleteMessage = "删除失败，文章和音频已保留，请重试")
                }
            } finally {
                _listState.update { it.copy(deletingArticleId = null) }
            }
        }
    }

    fun clearDeleteMessage() {
        _listState.update { it.copy(deleteMessage = null) }
    }
}

private fun LocalArticleImportError.toUserMessage(): String = when (this) {
    LocalArticleImportError.EMPTY_TITLE -> "标题不能为空"
    LocalArticleImportError.SOURCE_UNREADABLE -> "无法读取所选文件，请重新选择或检查文件是否仍然存在"
    LocalArticleImportError.UNSUPPORTED_AUDIO_FORMAT -> "仅支持 M4A、MP3 或 WAV 音频"
    LocalArticleImportError.AUDIO_TOO_LARGE -> "音频超过 100MB，请压缩后重试"
    LocalArticleImportError.AUDIO_TOO_LONG -> "音频超过 30 分钟，请拆分后导入"
    LocalArticleImportError.INVALID_AUDIO -> "音频已损坏或系统无法识别，请更换文件"
    LocalArticleImportError.UNSUPPORTED_SUBTITLE_FORMAT -> "字幕仅支持 SRT 或 LRC"
    LocalArticleImportError.SUBTITLE_TOO_LARGE -> "字幕超过 2MB，请精简后重试"
    LocalArticleImportError.TOO_MANY_SUBTITLE_CUES -> "字幕超过 10,000 条，请拆分后导入"
    LocalArticleImportError.INVALID_SUBTITLE_ENCODING -> "字幕编码无法识别，请保存为 UTF-8 后重试"
    LocalArticleImportError.INVALID_SUBTITLE_TIMELINE -> "字幕时间轴有重叠、倒序或超过音频时长，请修正后重试"
    LocalArticleImportError.EMPTY_SUBTITLE -> "字幕中没有可导入的有效内容"
    LocalArticleImportError.TRANSACTION_FAILURE -> "导入未完成，未留下半成品，请重试"
}

const val RANDOM_ARTICLE_ROUTE_ID = "__RANDOM__"
