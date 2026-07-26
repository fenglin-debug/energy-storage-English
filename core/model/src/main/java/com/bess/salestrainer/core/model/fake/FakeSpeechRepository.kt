package com.bess.salestrainer.core.model.fake

import com.bess.salestrainer.core.model.AsrModelState
import com.bess.salestrainer.core.model.RecordingRequest
import com.bess.salestrainer.core.model.SpeechAnalysis
import com.bess.salestrainer.core.model.SpeechMetrics
import com.bess.salestrainer.core.model.SpeechPlaybackRequest
import com.bess.salestrainer.core.model.contract.SpeechRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** In-memory fake for playback/recording/ASR flows. Returns fixed transcript + metrics. */
class FakeSpeechRepository : SpeechRepository {

    private val asrState = MutableStateFlow<AsrModelState>(AsrModelState.Ready("fake-moonshine-v1"))
    private val activeRecordings = mutableSetOf<String>()

    override fun observeAsrModelState(): Flow<AsrModelState> = asrState

    override suspend fun play(request: SpeechPlaybackRequest) {
        // Fake playback completes immediately.
        delay(10)
    }

    override suspend fun stopPlayback() {
        // no-op
    }

    override suspend fun startRecording(request: RecordingRequest): String {
        val id = "rec_" + UUID.randomUUID().toString().take(8)
        activeRecordings += id
        return id
    }

    override suspend fun stopAndTranscribe(recordingId: String): SpeechAnalysis {
        activeRecordings -= recordingId
        return SpeechAnalysis(
            recordingId = recordingId,
            transcript = "We use LFP cells with over eight thousand cycles and ninety-five percent round-trip efficiency.",
            durationMs = 8200,
            metrics = SpeechMetrics(
                wpm = 132.0, pauseRatio = 0.14, maxPauseMs = 620,
                fillerCount = 1, keywordCoverage = 0.8,
            ),
            audioFileRef = "recordings/$recordingId.m4a",
            usedOfflineAsr = true,
        )
    }

    override suspend fun cancelRecording(recordingId: String) {
        activeRecordings -= recordingId
    }

    override suspend fun requestAsrModelDownload() {
        asrState.value = AsrModelState.Downloading(10, 12_000_000, 120_000_000)
        delay(50)
        asrState.value = AsrModelState.Verifying
        delay(50)
        asrState.value = AsrModelState.Ready("fake-moonshine-v1")
    }
}
