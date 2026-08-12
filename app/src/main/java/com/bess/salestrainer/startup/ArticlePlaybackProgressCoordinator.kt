package com.bess.salestrainer.startup

import android.os.SystemClock
import com.bess.salestrainer.core.model.Article
import com.bess.salestrainer.core.model.AudioPlaybackState
import com.bess.salestrainer.core.model.AudioTransitionReason
import com.bess.salestrainer.core.model.contract.ArticleRepository
import com.bess.salestrainer.core.model.contract.AudioPlaybackRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Persists article listening progress independently of the article screen lifecycle. */
@Singleton
class ArticlePlaybackProgressCoordinator @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val audioPlaybackRepository: AudioPlaybackRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var articlesByAssetId: Map<String, Article> = emptyMap()
    @Volatile private var started = false
    private val lastPeriodicSaveAt = mutableMapOf<String, Long>()
    private var lastTransitionKey: String? = null

    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            articleRepository.observeArticles().collect { articles ->
                articlesByAssetId = articles.associateBy { it.audioAssetId }
            }
        }
        scope.launch {
            audioPlaybackRepository.observePlayback().collect { snapshot ->
                val transition = snapshot.transition
                if (transition != null && transition.fromAssetId != null) {
                    val key = listOf(
                        transition.fromAssetId,
                        transition.toAssetId,
                        transition.fromPositionMs,
                        transition.reason,
                    ).joinToString("|")
                    if (key != lastTransitionKey) {
                        lastTransitionKey = key
                        val previous = articlesByAssetId[transition.fromAssetId]
                        if (previous != null && transition.fromPositionMs > 0L) {
                            val completed = transition.reason == AudioTransitionReason.AUTOMATIC ||
                                transition.reason == AudioTransitionReason.REPEAT
                            articleRepository.saveProgress(
                                previous.id,
                                if (completed) previous.durationMs else transition.fromPositionMs,
                                completed = completed,
                            )
                        }
                    }
                }

                val assetId = snapshot.assetId ?: return@collect
                val article = articlesByAssetId[assetId] ?: return@collect
                when (snapshot.state) {
                    AudioPlaybackState.COMPLETED -> articleRepository.saveProgress(
                        article.id,
                        article.durationMs,
                        completed = true,
                    )
                    AudioPlaybackState.PAUSED -> if (snapshot.positionMs > 0L) {
                        articleRepository.saveProgress(
                            article.id,
                            snapshot.positionMs,
                            completed = false,
                        )
                    }
                    AudioPlaybackState.PLAYING -> {
                        val now = SystemClock.elapsedRealtime()
                        val last = lastPeriodicSaveAt[assetId] ?: 0L
                        if (snapshot.positionMs > 0L && now - last >= SAVE_INTERVAL_MS) {
                            lastPeriodicSaveAt[assetId] = now
                            articleRepository.saveProgress(
                                article.id,
                                snapshot.positionMs,
                                completed = false,
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}
