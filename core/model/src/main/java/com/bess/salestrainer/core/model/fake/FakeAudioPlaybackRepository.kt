package com.bess.salestrainer.core.model.fake

import com.bess.salestrainer.core.model.AudioPlaybackSnapshot
import com.bess.salestrainer.core.model.AudioPlaybackState
import com.bess.salestrainer.core.model.AudioQueueItem
import com.bess.salestrainer.core.model.PlaybackSpeed
import com.bess.salestrainer.core.model.contract.AudioPlaybackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAudioPlaybackRepository : AudioPlaybackRepository {
    private var queue: List<AudioQueueItem> = emptyList()
    private val playback = MutableStateFlow(
        AudioPlaybackSnapshot(assetId = null, state = AudioPlaybackState.IDLE)
    )

    override fun observePlayback(): Flow<AudioPlaybackSnapshot> = playback

    override suspend fun play(assetId: String, speed: PlaybackSpeed) {
        require(assetId.isNotBlank())
        require(speed in PlaybackSpeed.entries)
        playback.value = AudioPlaybackSnapshot(assetId, AudioPlaybackState.PLAYING)
        playback.value = AudioPlaybackSnapshot(assetId, AudioPlaybackState.COMPLETED)
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
        require(items.isNotEmpty())
        require(startIndex in items.indices)
        queue = items
        playback.value = AudioPlaybackSnapshot(
            assetId = items[startIndex].assetId,
            state = AudioPlaybackState.PLAYING,
            positionMs = startPositionMs,
            queueIndex = startIndex,
            queueSize = items.size,
        )
    }

    override suspend fun pause() {
        val current = playback.value
        if (current.state == AudioPlaybackState.PLAYING) {
            playback.value = current.copy(state = AudioPlaybackState.PAUSED)
        }
    }

    override suspend fun resume() {
        val current = playback.value
        if (current.state == AudioPlaybackState.PAUSED) {
            playback.value = current.copy(state = AudioPlaybackState.PLAYING)
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        playback.value = playback.value.copy(positionMs = positionMs.coerceAtLeast(0L))
    }

    override suspend fun skipToNext() {
        val current = playback.value
        if (current.queueSize <= 1) return
        val nextIndex = (current.queueIndex + 1).mod(current.queueSize)
        playback.value = current.copy(
            assetId = queue[nextIndex].assetId,
            positionMs = 0,
            queueIndex = nextIndex,
        )
    }

    override suspend fun skipToPrevious() {
        val current = playback.value
        if (current.queueSize <= 1) return
        val previousIndex = (current.queueIndex - 1).mod(current.queueSize)
        playback.value = current.copy(
            assetId = queue[previousIndex].assetId,
            positionMs = 0,
            queueIndex = previousIndex,
        )
    }

    override suspend fun stop() {
        playback.value = AudioPlaybackSnapshot(assetId = null, state = AudioPlaybackState.IDLE)
    }
}
