package com.bess.salestrainer.core.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bess.salestrainer.core.model.AudioError
import com.bess.salestrainer.core.model.AudioPlaybackSnapshot
import com.bess.salestrainer.core.model.AudioPlaybackState
import com.bess.salestrainer.core.model.AudioPlaybackTransition
import com.bess.salestrainer.core.model.AudioQueueItem
import com.bess.salestrainer.core.model.AudioTransitionReason
import com.bess.salestrainer.core.model.PlaybackSpeed
import com.bess.salestrainer.core.model.contract.AudioPlaybackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Local-only playback backed by a Media3 session service.
 *
 * Asset ids are resolved inside the active package, canonical paths are checked,
 * and SHA-256 is verified before the service receives a file URI. The media
 * session provides Android's notification/lock-screen playback state.
 */
class Media3AudioPlaybackRepository(
    context: Context,
    private val locator: AudioAssetLocator,
) : AudioPlaybackRepository {

    fun interface AudioAssetLocator {
        suspend fun locate(assetId: String): LocatedAsset?
    }

    data class LocatedAsset(
        val file: File,
        val rootDirectory: File,
        val expectedSha256: String,
        val durationMs: Long,
        val displayTitle: String = "储能英语训练",
        val displaySubtitle: String = "AndrewNeural 男声",
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _snapshot = MutableStateFlow(
        AudioPlaybackSnapshot(assetId = null, state = AudioPlaybackState.IDLE),
    )
    private val controllerFuture by lazy {
        MediaController.Builder(
            appContext,
            SessionToken(
                appContext,
                ComponentName(appContext, AudioPlaybackService::class.java),
            ),
        ).buildAsync()
    }
    @Volatile private var mediaController: MediaController? = null
    private var listenerRegistered = false
    private var currentAssetId: String? = null
    private var progressJob: Job? = null
    private var placeholderJob: Job? = null
    private var autoAdvance = true
    private var reshuffleOnRepeat = false
    private var activeMediaItems: List<MediaItem> = emptyList()

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val controller = mediaController ?: return
            val old = _snapshot.value
            val newAssetId = mediaItem?.mediaId
            val transitionReason = reason.toDomainTransitionReason()
            if (
                transitionReason == AudioTransitionReason.AUTOMATIC &&
                reshuffleOnRepeat && old.queueSize > 1 &&
                old.queueIndex == old.queueSize - 1 && controller.currentMediaItemIndex == 0
            ) {
                val shuffled = activeMediaItems.shuffled().toMutableList()
                if (shuffled.firstOrNull()?.mediaId == old.assetId) {
                    val swapIndex = shuffled.indexOfFirst { it.mediaId != old.assetId }
                    if (swapIndex > 0) {
                        val first = shuffled[0]
                        shuffled[0] = shuffled[swapIndex]
                        shuffled[swapIndex] = first
                    }
                }
                activeMediaItems = shuffled
                val shouldPlay = controller.playWhenReady
                controller.setMediaItems(shuffled, 0, 0L)
                controller.prepare()
                controller.playWhenReady = shouldPlay
                return
            }
            currentAssetId = newAssetId
            if (transitionReason == AudioTransitionReason.AUTOMATIC && !autoAdvance) {
                controller.pause()
            }
            emit(
                assetId = newAssetId,
                state = when {
                    controller.isPlaying -> AudioPlaybackState.PLAYING
                    controller.playbackState == Player.STATE_BUFFERING -> AudioPlaybackState.BUFFERING
                    else -> AudioPlaybackState.PAUSED
                },
                positionMs = controller.currentPosition.coerceAtLeast(0L),
                durationMs = safeDuration(controller, 0L),
                queueIndex = controller.currentMediaItemIndex,
                queueSize = controller.mediaItemCount,
                transition = AudioPlaybackTransition(
                    fromAssetId = old.assetId,
                    toAssetId = newAssetId,
                    fromPositionMs = old.positionMs,
                    reason = transitionReason,
                ),
            )
            newAssetId?.let { startProgressUpdates(controller, it) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val controller = mediaController ?: return
            val assetId = controller.currentMediaItem?.mediaId ?: currentAssetId ?: return
            when (playbackState) {
                Player.STATE_BUFFERING -> emit(
                    assetId,
                    AudioPlaybackState.BUFFERING,
                    positionMs = controller.currentPosition.coerceAtLeast(0),
                    durationMs = safeDuration(controller, _snapshot.value.durationMs),
                    queueIndex = controller.currentMediaItemIndex,
                    queueSize = controller.mediaItemCount,
                    transition = _snapshot.value.transition.takeIf { it?.toAssetId == assetId },
                )
                Player.STATE_READY -> {
                    emit(
                        assetId,
                        if (controller.isPlaying || controller.playWhenReady) {
                            AudioPlaybackState.PLAYING
                        } else {
                            AudioPlaybackState.PAUSED
                        },
                        positionMs = controller.currentPosition.coerceAtLeast(0),
                        durationMs = safeDuration(controller, _snapshot.value.durationMs),
                        queueIndex = controller.currentMediaItemIndex,
                        queueSize = controller.mediaItemCount,
                        transition = _snapshot.value.transition.takeIf { it?.toAssetId == assetId },
                    )
                    startProgressUpdates(controller, assetId)
                }
                Player.STATE_ENDED -> emit(
                    assetId,
                    AudioPlaybackState.COMPLETED,
                    positionMs = safeDuration(controller, _snapshot.value.durationMs),
                    durationMs = safeDuration(controller, _snapshot.value.durationMs),
                    queueIndex = controller.currentMediaItemIndex,
                    queueSize = controller.mediaItemCount,
                    transition = _snapshot.value.transition.takeIf { it?.toAssetId == assetId },
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val controller = mediaController ?: return
            val assetId = controller.currentMediaItem?.mediaId ?: currentAssetId ?: return
            if (controller.playbackState == Player.STATE_READY) {
                emit(
                    assetId,
                    if (isPlaying) AudioPlaybackState.PLAYING else AudioPlaybackState.PAUSED,
                    positionMs = controller.currentPosition.coerceAtLeast(0),
                    durationMs = safeDuration(controller, _snapshot.value.durationMs),
                    queueIndex = controller.currentMediaItemIndex,
                    queueSize = controller.mediaItemCount,
                    transition = _snapshot.value.transition.takeIf { it?.toAssetId == assetId },
                )
                if (isPlaying) startProgressUpdates(controller, assetId)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val assetId = currentAssetId ?: return
            emit(assetId, AudioPlaybackState.FAILED, error = AudioError.DecodeFailure(assetId))
        }
    }

    override fun observePlayback(): StateFlow<AudioPlaybackSnapshot> = _snapshot

    override suspend fun play(assetId: String, speed: PlaybackSpeed) {
        playQueue(
            items = listOf(AudioQueueItem(assetId = assetId, title = "")),
            startIndex = 0,
            startPositionMs = 0L,
            speed = speed,
            autoAdvance = false,
            repeatAll = false,
            reshuffleOnRepeat = false,
        )
    }

    override suspend fun playQueue(
        items: List<AudioQueueItem>,
        startIndex: Int,
        startPositionMs: Long,
        speed: PlaybackSpeed,
        autoAdvance: Boolean,
        repeatAll: Boolean,
        reshuffleOnRepeat: Boolean,
    ) {
        if (items.isEmpty() || startIndex !in items.indices) {
            emit(null, AudioPlaybackState.FAILED, error = AudioError.PlayerFailure("EMPTY_QUEUE"))
            return
        }

        val prepared = mutableListOf<Pair<AudioQueueItem, LocatedAsset>>()
        for (item in items) {
            validateAndLocate(item.assetId)?.let { prepared += item to it }
        }
        val requestedStartId = items[startIndex].assetId
        val preparedStartIndex = prepared.indexOfFirst { it.first.assetId == requestedStartId }
        if (preparedStartIndex < 0) {
            emit(
                requestedStartId,
                AudioPlaybackState.FAILED,
                error = AudioError.AssetNotFound(requestedStartId),
            )
            return
        }

        val startLocated = prepared[preparedStartIndex].second
        clearCurrentPlayback()
        currentAssetId = requestedStartId
        this.autoAdvance = autoAdvance
        this.reshuffleOnRepeat = reshuffleOnRepeat

        if (prepared.size == 1 && isPlaceholder(startLocated.file)) {
            emit(
                requestedStartId,
                AudioPlaybackState.PLAYING,
                durationMs = startLocated.durationMs,
                queueIndex = 0,
                queueSize = 1,
            )
            placeholderJob = scope.launch {
                delay(startLocated.durationMs.coerceAtMost(2_000L))
                if (currentAssetId == requestedStartId) {
                    emit(
                        requestedStartId,
                        AudioPlaybackState.COMPLETED,
                        positionMs = startLocated.durationMs,
                        durationMs = startLocated.durationMs,
                        queueIndex = 0,
                        queueSize = 1,
                    )
                }
            }
            return
        }

        emit(
            requestedStartId,
            AudioPlaybackState.BUFFERING,
            durationMs = startLocated.durationMs,
            queueIndex = preparedStartIndex,
            queueSize = prepared.size,
        )
        val controller = try {
            getController()
        } catch (_: Exception) {
            emit(
                requestedStartId,
                AudioPlaybackState.FAILED,
                error = AudioError.PlayerFailure("CONTROLLER_CONNECT"),
            )
            return
        }
        val mediaItems = prepared.map { (queueItem, located) ->
            MediaItem.Builder()
                .setMediaId(queueItem.assetId)
                .setUri(Uri.fromFile(located.file))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(queueItem.title.ifBlank { located.displayTitle })
                        .setArtist(queueItem.subtitle.ifBlank { located.displaySubtitle })
                        .build(),
                )
                .build()
        }
        activeMediaItems = mediaItems
        withContext(Dispatchers.Main.immediate) {
            controller.stop()
            controller.clearMediaItems()
            controller.setMediaItems(
                mediaItems,
                preparedStartIndex,
                startPositionMs.coerceIn(0L, startLocated.durationMs.coerceAtLeast(0L)),
            )
            controller.repeatMode = if (repeatAll) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            controller.shuffleModeEnabled = false
            controller.setPlaybackSpeed(speed.factor)
            controller.prepare()
            controller.play()
        }
        startProgressUpdates(controller, requestedStartId)
    }

    override suspend fun pause() {
        val controller = mediaController ?: return
        withContext(Dispatchers.Main.immediate) { controller.pause() }
    }

    override suspend fun resume() {
        val controller = mediaController ?: return
        withContext(Dispatchers.Main.immediate) { controller.play() }
    }

    override suspend fun seekTo(positionMs: Long) {
        val controller = mediaController ?: return
        withContext(Dispatchers.Main.immediate) {
            controller.seekTo(positionMs.coerceAtLeast(0L))
        }
    }

    override suspend fun skipToNext() {
        val controller = mediaController ?: return
        withContext(Dispatchers.Main.immediate) {
            if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
        }
    }

    override suspend fun skipToPrevious() {
        val controller = mediaController ?: return
        withContext(Dispatchers.Main.immediate) {
            if (controller.hasPreviousMediaItem()) controller.seekToPreviousMediaItem()
        }
    }

    override suspend fun stop() {
        val old = _snapshot.value
        clearCurrentPlayback()
        _snapshot.value = AudioPlaybackSnapshot(
            assetId = null,
            state = AudioPlaybackState.IDLE,
            transition = AudioPlaybackTransition(
                fromAssetId = old.assetId,
                toAssetId = null,
                fromPositionMs = old.positionMs,
                reason = AudioTransitionReason.STOPPED,
            ),
        )
    }

    private suspend fun getController(): MediaController {
        mediaController?.let { return it }
        val controller = withContext(Dispatchers.IO) {
            controllerFuture.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        withContext(Dispatchers.Main.immediate) {
            if (!listenerRegistered) {
                controller.addListener(playerListener)
                listenerRegistered = true
            }
            mediaController = controller
        }
        return controller
    }

    private suspend fun clearCurrentPlayback() {
        progressJob?.cancel()
        progressJob = null
        placeholderJob?.cancel()
        placeholderJob = null
        mediaController?.let { controller ->
            withContext(Dispatchers.Main.immediate) {
                controller.stop()
                controller.clearMediaItems()
            }
        }
        currentAssetId = null
        activeMediaItems = emptyList()
    }

    private fun emit(
        assetId: String?,
        state: AudioPlaybackState,
        positionMs: Long = 0,
        durationMs: Long = 0,
        queueIndex: Int = -1,
        queueSize: Int = 0,
        transition: AudioPlaybackTransition? = null,
        error: AudioError? = null,
    ) {
        _snapshot.value = AudioPlaybackSnapshot(
            assetId = assetId,
            state = state,
            positionMs = positionMs,
            durationMs = durationMs,
            queueIndex = queueIndex,
            queueSize = queueSize,
            transition = transition,
            error = error,
        )
    }

    private fun startProgressUpdates(controller: MediaController, assetId: String) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && mediaController === controller && currentAssetId == assetId) {
                val snapshot = _snapshot.value
                if (
                    snapshot.state == AudioPlaybackState.COMPLETED ||
                    snapshot.state == AudioPlaybackState.FAILED ||
                    snapshot.state == AudioPlaybackState.IDLE
                ) break
                _snapshot.value = snapshot.copy(
                    positionMs = controller.currentPosition.coerceAtLeast(0),
                    durationMs = safeDuration(controller, snapshot.durationMs),
                    queueIndex = controller.currentMediaItemIndex,
                    queueSize = controller.mediaItemCount,
                    transition = null,
                )
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun safeDuration(player: Player, fallback: Long): Long =
        player.duration.takeIf { it > 0 } ?: fallback

    private suspend fun validateAndLocate(assetId: String): LocatedAsset? {
        val located = locator.locate(assetId) ?: return null
        val canonicalRoot = runCatching { located.rootDirectory.canonicalFile.toPath() }.getOrNull()
            ?: return null
        val canonicalFile = runCatching { located.file.canonicalFile.toPath() }.getOrNull()
            ?: return null
        if (!canonicalFile.startsWith(canonicalRoot) || !located.file.isFile) return null
        val actualHash = withContext(Dispatchers.IO) {
            runCatching { sha256(located.file) }.getOrNull()
        } ?: return null
        return located.takeIf { actualHash.equals(it.expectedSha256, ignoreCase = true) }
    }

    private fun isPlaceholder(file: File): Boolean = runCatching {
        FileInputStream(file).use { stream ->
            val head = ByteArray(PLACEHOLDER_HEADER.length)
            stream.read(head) == head.size && String(head, Charsets.UTF_8) == PLACEHOLDER_HEADER
        }
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Int.toDomainTransitionReason(): AudioTransitionReason = when (this) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> AudioTransitionReason.AUTOMATIC
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> AudioTransitionReason.SEEK
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> AudioTransitionReason.REPEAT
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> AudioTransitionReason.PLAYLIST_CHANGED
        else -> AudioTransitionReason.UNKNOWN
    }

    private companion object {
        const val CONTROLLER_TIMEOUT_SECONDS = 10L
        const val PROGRESS_INTERVAL_MS = 250L
        const val PLACEHOLDER_HEADER = "BESS-PLACEHOLDER-AUDIO"
    }
}
