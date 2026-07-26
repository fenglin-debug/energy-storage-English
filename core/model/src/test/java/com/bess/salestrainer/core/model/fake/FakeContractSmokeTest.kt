package com.bess.salestrainer.core.model.fake

import app.cash.turbine.test
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.RecordWordReview
import com.bess.salestrainer.core.model.ScenarioFilter
import com.bess.salestrainer.core.model.ScenarioMode
import com.bess.salestrainer.core.model.SessionAdvance
import com.bess.salestrainer.core.model.StartScenario
import com.bess.salestrainer.core.model.UpdateSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Wave 0 contract smoke test — verifies each Fake repository satisfies its frozen interface
 * well enough to drive the app shell end-to-end.
 */
class FakeContractSmokeTest {

    @Test
    fun `vocabulary queue emits and review removes word from queue`() = runTest {
        val repo = FakeVocabularyRepository()
        val initial = repo.observeTodayQueue().first()
        assertTrue(initial.newWords.isNotEmpty())

        val word = initial.newWords.first()
        val result = repo.recordReview(
            RecordWordReview(
                wordId = word.id,
                rating = Rating.GOOD,
                questionMode = QuestionMode.INTRODUCE,
                usedHint = false,
                revealedAnswer = false,
                reviewedAt = Instant.now(),
            )
        )
        assertEquals(word.id, result.wordId)

        val after = repo.observeTodayQueue().first()
        assertFalse(after.newWords.any { it.id == word.id })
    }

    @Test
    fun `scenario startOrResume is idempotent and acceptTurnAttempt advances`() = runTest {
        val repo = FakeScenarioRepository()
        val scenarios = repo.observeScenarios(ScenarioFilter()).first()
        assertTrue(scenarios.isNotEmpty())

        val s1 = repo.startOrResume(StartScenario(scenarios.first().id, ScenarioMode.SIMULATION))
        val s2 = repo.startOrResume(StartScenario(scenarios.first().id, ScenarioMode.SIMULATION))
        assertEquals("startOrResume must be idempotent", s1, s2)

        val detail = repo.observeSession(s1).first()
        assertEquals(1, detail.currentCustomerTurnNo)

        val advance = repo.acceptTurnAttempt(
            com.bess.salestrainer.core.model.AcceptTurnAttempt(
                sessionId = s1, turnNo = 1,
                rawTranscript = "test", editedTranscript = null,
                metrics = null, audioFileRef = null,
            )
        )
        assertTrue(advance is SessionAdvance.NextCustomerTurn)
    }

    @Test
    fun `study task and resume target emit`() = runTest {
        val repo = FakeStudyTaskRepository()
        val task = repo.observeTodayTask().first()
        assertTrue(task.newWordTarget > 0)
        assertNotNull(repo.observeResumeTarget().first())
    }

    @Test
    fun `settings update applies partial fields`() = runTest {
        val repo = FakeSettingsRepository()
        repo.updateSettings(UpdateSettings(dailyNewWordTarget = 20, desiredRetentionPercent = 92))
        val s = repo.observeSettings().first()
        assertEquals(20, s.dailyNewWordTarget)
        assertEquals(92, s.desiredRetentionPercent)
        assertFalse(repo.hasDeepSeekKey())
    }

    @Test
    fun `corpus, speech and ai fakes emit expected states`() = runTest {
        val corpus = FakeCorpusRepository()
        assertTrue(corpus.observeActiveCorpus().first().vocabularyCount > 0)

        val speech = FakeSpeechRepository()
        speech.observeAsrModelState().test {
            assertNotNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        val recId = speech.startRecording(com.bess.salestrainer.core.model.RecordingRequest())
        val analysis = speech.stopAndTranscribe(recId)
        assertTrue(analysis.transcript.isNotBlank())

        val ai = FakeAiCoachRepository()
        val result = ai.evaluateSession("sess_x")
        assertTrue(result is com.bess.salestrainer.core.model.AiEvaluationResult.Success)
    }
}
