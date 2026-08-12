package com.bess.salestrainer.core.model.fake

import com.bess.salestrainer.core.model.AudioPlaybackState
import com.bess.salestrainer.core.model.CorpusImportResult
import com.bess.salestrainer.core.model.CorpusSource
import com.bess.salestrainer.core.model.CustomerTextView
import com.bess.salestrainer.core.model.DialogueSelfRating
import com.bess.salestrainer.core.model.PlaybackSpeed
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.RecordWordReview
import com.bess.salestrainer.core.model.ReferenceAnswerView
import com.bess.salestrainer.core.model.ReviewAdvance
import com.bess.salestrainer.core.model.ScenarioAdvance
import com.bess.salestrainer.core.model.ScenarioFilter
import com.bess.salestrainer.core.model.UpdateSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FakeContractSmokeTest {
    @Test
    fun `vocabulary session restores reveal state and rejects stale review`() = runTest {
        val repository = FakeVocabularyRepository()
        val sessionId = repository.startOrResumeSession()
        assertEquals(sessionId, repository.startOrResumeSession())

        val concealed = repository.observeSession(sessionId).first()
        assertFalse(concealed.checkpoint.answerRevealed)
        repository.revealVocabularyAnswer(sessionId)
        val revealed = repository.observeSession(sessionId).first()
        assertTrue(revealed.checkpoint.answerRevealed)

        val command = RecordWordReview(
            expectedWordId = requireNotNull(revealed.currentWord).id,
            expectedIndex = revealed.checkpoint.currentIndex,
            rating = Rating.GOOD,
            usedHint = false,
            reviewedAt = Instant.now(),
        )
        assertTrue(repository.recordReview(sessionId, command) is ReviewAdvance.Next)
        val failure = runCatching { repository.recordReview(sessionId, command) }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `scenario content is absent until each reveal command`() = runTest {
        val repository = FakeScenarioRepository()
        val scenarioId = repository.observeScenarios(ScenarioFilter()).first().single().id
        val sessionId = repository.startOrResume(scenarioId)
        assertEquals(sessionId, repository.startOrResume(scenarioId))

        val initial = repository.observeCurrentUnit(sessionId).first()
        assertSame(CustomerTextView.Concealed, initial.unit.customerText)
        assertSame(ReferenceAnswerView.Concealed, initial.unit.answer)
        assertFalse(initial.progress.customerTextRevealed)
        assertFalse(initial.progress.answerRevealed)

        repository.revealCustomerText(sessionId, initial.unit.pairId)
        val textRevealed = repository.observeCurrentUnit(sessionId).first()
        assertTrue(textRevealed.unit.customerText is CustomerTextView.Revealed)
        assertSame(ReferenceAnswerView.Concealed, textRevealed.unit.answer)

        val earlyRating = runCatching {
            repository.rateAndAdvance(
                sessionId,
                initial.unit.pairId,
                DialogueSelfRating.BASIC,
            )
        }
        assertTrue(earlyRating.isFailure)

        repository.revealReferenceAnswer(sessionId, initial.unit.pairId)
        val advance = repository.rateAndAdvance(
            sessionId,
            initial.unit.pairId,
            DialogueSelfRating.BASIC,
        )
        assertTrue(advance is ScenarioAdvance.NextPair)
    }

    @Test
    fun `settings only expose offline learning preferences`() = runTest {
        val repository = FakeSettingsRepository()
        val before = repository.observeSettings().first()
        repository.updateSettings(
            UpdateSettings(
                playbackSpeed = PlaybackSpeed.FAST,
                dailyNewWordTarget = 20,
                autoPlayCustomerAudio = false,
            )
        )
        val after = repository.observeSettings().first()
        assertNotEquals(before, after)
        assertEquals(PlaybackSpeed.FAST, after.playbackSpeed)
        assertEquals(20, after.dailyNewWordTarget)
        assertFalse(after.autoPlayCustomerAudio)
    }

    @Test
    fun `corpus preview token is required for activation`() = runTest {
        val repository = FakeCorpusRepository()
        val preview = repository.inspectPackage(CorpusSource("document:fake"))
        val result = repository.activatePreview(preview.previewId)
        assertTrue(result is CorpusImportResult.Success)
        assertNotNull(repository.observeActiveCorpus().first())
        assertTrue(repository.activatePreview(preview.previewId) is CorpusImportResult.Failure)
    }

    @Test
    fun `audio fake exposes local playback lifecycle`() = runTest {
        val repository = FakeAudioPlaybackRepository()
        repository.play("audio_customer_s001_p001", PlaybackSpeed.NORMAL)
        assertEquals(AudioPlaybackState.COMPLETED, repository.observePlayback().first().state)
        repository.stop()
        assertEquals(AudioPlaybackState.IDLE, repository.observePlayback().first().state)
    }
}
