package com.bess.salestrainer.core.data.mapper

import com.bess.salestrainer.core.database.entity.DialogueTurnEntity
import com.bess.salestrainer.core.database.entity.ScenarioEntity
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.TurnAttemptEntity
import com.bess.salestrainer.core.database.entity.VocabularyEntryEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import com.bess.salestrainer.core.model.AiStatus
import com.bess.salestrainer.core.model.ContentSource
import com.bess.salestrainer.core.model.DialogueTurn
import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.ScenarioMode
import com.bess.salestrainer.core.model.ScenarioSummary
import com.bess.salestrainer.core.model.SessionStatus
import com.bess.salestrainer.core.model.Speaker
import com.bess.salestrainer.core.model.TurnAttempt
import com.bess.salestrainer.core.model.Vocabulary
import com.bess.salestrainer.core.model.WordMemoryState
import kotlinx.serialization.json.Json
import java.time.Instant

private val json = Json { ignoreUnknownKeys = true }

internal fun String.toStringList(): List<String> =
    runCatching { json.decodeFromString<List<String>>(this) }.getOrDefault(emptyList())

// ---- vocabulary ----

internal fun VocabularyEntryEntity.toModel(memory: WordMemoryStateEntity?): Vocabulary =
    Vocabulary(
        id = id,
        term = term,
        normalizedTerm = normalizedTerm,
        ipa = ipa,
        partOfSpeech = partOfSpeech,
        chineseGloss = chineseGloss,
        englishDefinition = englishDefinition,
        collocations = collocationsJson.toStringList(),
        exampleSentenceEn = exampleSentenceEn,
        exampleSentenceZh = exampleSentenceZh,
        commonMistakes = commonMistakes,
        topic = topic,
        scenarioTags = scenarioTagsJson.toStringList(),
        cefrLevel = cefrLevel,
        contentSource = ContentSource.valueOf(contentSource),
        memoryState = memory?.toModel(),
    )

internal fun WordMemoryStateEntity.toModel(): WordMemoryState =
    WordMemoryState(
        wordId = wordId,
        fsrsState = FsrsState.valueOf(fsrsState),
        difficulty = difficulty,
        stability = stability,
        dueAt = Instant.ofEpochMilli(dueAtEpochMs),
        reps = reps,
        lapses = lapses,
        masteredUi = masteredUi,
        lastQuestionMode = lastQuestionMode?.let { QuestionMode.valueOf(it) },
        isFavorite = isFavorite,
    )

// ---- scenario ----

internal fun ScenarioEntity.toSummary(
    completed: Boolean,
    bestScore: Int?,
    lastScore: Int?,
): ScenarioSummary =
    ScenarioSummary(
        id = id,
        title = title,
        topic = topic,
        salesStage = salesStage,
        difficulty = difficulty,
        estimatedMinutes = estimatedMinutes,
        completed = completed,
        bestScore = bestScore,
        lastScore = lastScore,
    )

internal fun DialogueTurnEntity.toModel(): DialogueTurn =
    DialogueTurn(
        scenarioId = scenarioId,
        turnNo = turnNo,
        speaker = Speaker.valueOf(speaker),
        textEn = textEn,
        textZh = textZh,
        ttsText = ttsText,
        audioRef = audioRef,
        keywords = keywordsJson.toStringList(),
        expectedPoints = expectedPointsJson.toStringList(),
        referenceAnswerEn = referenceAnswerEn,
    )

internal fun TurnAttemptEntity.toModel(): TurnAttempt =
    TurnAttempt(
        id = id,
        sessionId = sessionId,
        turnNo = turnNo,
        accepted = accepted,
        rawTranscript = rawTranscript,
        editedTranscript = editedTranscript,
        wpm = wpm,
        pauseRatio = pauseRatio,
        maxPauseMs = maxPauseMs,
        fillerCount = fillerCount,
        keywordCoverage = keywordCoverage,
        audioFileRef = audioFileRef,
        createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    )

internal fun ScenarioSessionEntity.statusEnum(): SessionStatus = SessionStatus.valueOf(status)
internal fun ScenarioSessionEntity.modeEnum(): ScenarioMode = ScenarioMode.valueOf(mode)
internal fun ScenarioSessionEntity.aiStatusEnum(): AiStatus = AiStatus.valueOf(aiStatus)
