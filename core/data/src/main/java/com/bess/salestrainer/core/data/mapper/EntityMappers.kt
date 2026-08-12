package com.bess.salestrainer.core.data.mapper

import com.bess.salestrainer.core.database.entity.AudioAssetEntity
import com.bess.salestrainer.core.database.entity.CorpusVersionEntity
import com.bess.salestrainer.core.database.entity.DialoguePairEntity
import com.bess.salestrainer.core.database.entity.ScenarioEntity
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.ScenarioTurnProgressEntity
import com.bess.salestrainer.core.database.entity.VocabularyEntryEntity
import com.bess.salestrainer.core.database.entity.VocabularySessionCheckpointEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import com.bess.salestrainer.core.model.AudioAsset
import com.bess.salestrainer.core.model.AudioKind
import com.bess.salestrainer.core.model.ContentSource
import com.bess.salestrainer.core.model.CorpusMetadata
import com.bess.salestrainer.core.model.DialogueSelfRating
import com.bess.salestrainer.core.model.FsrsState
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.ScenarioSessionSummary
import com.bess.salestrainer.core.model.ScenarioSummary
import com.bess.salestrainer.core.model.ScenarioTurnProgress
import com.bess.salestrainer.core.model.SessionStatus
import com.bess.salestrainer.core.model.Vocabulary
import com.bess.salestrainer.core.model.VocabularyExample
import com.bess.salestrainer.core.model.VocabularySessionCheckpoint
import com.bess.salestrainer.core.model.VocabularySessionStatus
import com.bess.salestrainer.core.model.WordMemoryState
import kotlinx.serialization.json.Json
import java.time.Instant

private val json = Json { ignoreUnknownKeys = true }

internal fun String.toStringList(): List<String> =
    runCatching { json.decodeFromString<List<String>>(this) }.getOrDefault(emptyList())

internal fun List<String>.toJsonString(): String = json.encodeToString(this)

/**
 * Older bundled packs used domain labels such as WIND/STORAGE in this column.
 * They are bundled core content, while only an explicit EXTERNAL value denotes
 * user-imported content. Unknown persisted labels must not crash app startup.
 */
internal fun String.toContentSource(): ContentSource = when (uppercase()) {
    ContentSource.EXTERNAL.name -> ContentSource.EXTERNAL
    else -> ContentSource.CORE
}

// ---- vocabulary ----

internal fun VocabularyEntryEntity.toModel(
    memory: WordMemoryStateEntity?,
    aliases: List<String> = emptyList(),
    linkedExamples: List<com.bess.salestrainer.core.database.entity.ExampleEntity> = emptyList(),
): Vocabulary =
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
        aliases = aliases,
        cefrLevel = cefrLevel,
        wordAudioAssetId = wordAudioAssetId,
        exampleAudioAssetId = exampleAudioAssetId,
        examples = (
            listOf(
                VocabularyExample(
                    id = "$id-primary",
                    textEn = exampleSentenceEn,
                    textZh = exampleSentenceZh.orEmpty(),
                    audioAssetId = exampleAudioAssetId,
                ),
            ) + linkedExamples.map {
                VocabularyExample(it.id, it.textEn, it.textZh, it.audioAssetId)
            }
        ).distinctBy { it.textEn.trim().lowercase() },
        contentHash = contentHash,
        contentSource = contentSource.toContentSource(),
        memoryState = memory?.toModel(),
    )

/** FR-v2: a 常用短语 joins the vocabulary practice queue as a pseudo-word. */
internal fun com.bess.salestrainer.core.database.entity.PhraseEntity.toVocabularyModel(
    memory: com.bess.salestrainer.core.database.entity.ItemMemoryStateEntity?,
    linkedExamples: List<com.bess.salestrainer.core.database.entity.ExampleEntity> = emptyList(),
): Vocabulary =
    Vocabulary(
        id = id,
        itemKind = com.bess.salestrainer.core.model.ItemKind.PHRASE,
        term = textEn,
        normalizedTerm = textEn.lowercase().trim(),
        ipa = "",
        partOfSpeech = "phrase",
        chineseGloss = textZh,
        englishDefinition = null,
        collocations = emptyList(),
        exampleSentenceEn = textEn,
        exampleSentenceZh = textZh,
        commonMistakes = "",
        topic = "$industry:$category",
        scenarioTags = listOf(scene),
        aliases = emptyList(),
        cefrLevel = "",
        wordAudioAssetId = audioAssetId,
        exampleAudioAssetId = audioAssetId,
        examples = linkedExamples.map {
            VocabularyExample(it.id, it.textEn, it.textZh, it.audioAssetId)
        },
        contentHash = contentHash,
        contentSource = ContentSource.CORE,
        memoryState = memory?.let {
            WordMemoryState(
                wordId = it.itemId,
                fsrsState = FsrsState.valueOf(it.fsrsState),
                difficulty = it.difficulty,
                stability = it.stability,
                dueAt = Instant.ofEpochMilli(it.dueAtEpochMs),
                reps = it.reps,
                lapses = it.lapses,
                masteredUi = it.masteredUi,
                lastQuestionMode = null,
                isFavorite = false,
            )
        },
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

internal fun VocabularySessionCheckpointEntity.toModel(): VocabularySessionCheckpoint =
    VocabularySessionCheckpoint(
        sessionId = sessionId,
        status = VocabularySessionStatus.valueOf(status),
        corpusVersion = corpusVersion,
        queueWordIds = queueWordIdsJson.toStringList(),
        currentIndex = currentIndex,
        questionMode = QuestionMode.valueOf(questionMode),
        answerRevealed = answerRevealed,
        hintRevealed = hintRevealed,
        assessmentSubmitted = assessmentSubmitted,
        selectedAssessment = selectedAssessment?.let {
            com.bess.salestrainer.core.model.VocabularySelfAssessment.valueOf(it)
        },
        startedAt = Instant.ofEpochMilli(startedAtEpochMs),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
    )

// ---- scenario ----

internal fun ScenarioEntity.toSummary(
    status: SessionStatus?,
    ratingSummary: Map<DialogueSelfRating, Int> = emptyMap(),
): ScenarioSummary =
    ScenarioSummary(
        id = id,
        title = title,
        topic = topic,
        salesStage = salesStage,
        customerRole = customerRole,
        difficulty = difficulty,
        projectType = projectType,
        estimatedMinutes = estimatedMinutes,
        status = status,
        latestRatingSummary = ratingSummary,
    )

internal fun ScenarioSessionEntity.toSummary(scenarioTitle: String): ScenarioSessionSummary =
    ScenarioSessionSummary(
        sessionId = id,
        scenarioId = scenarioId,
        scenarioTitle = scenarioTitle,
        status = SessionStatus.valueOf(status),
        currentPairIndex = currentPairIndex,
        pairCount = pairCount,
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
        practiceMode = com.bess.salestrainer.core.model.ScenarioPracticeMode.valueOf(practiceMode),
    )

internal fun ScenarioTurnProgressEntity.toModel(): ScenarioTurnProgress =
    ScenarioTurnProgress(
        sessionId = sessionId,
        pairId = pairId,
        customerAudioCompleted = customerAudioCompleted,
        customerTextRevealed = customerTextRevealed,
        keywordsRevealed = keywordsRevealed,
        answerRevealed = answerRevealed,
        selfRating = selfRating?.let { DialogueSelfRating.valueOf(it) },
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
    )

internal fun DialoguePairEntity.formalAlternatives(): List<String> =
    formalAlternativesJson.toStringList()

/** Local mirror of the pack scoring-point DTO (display only; core:data has no corpus DTO dependency). */
@kotlinx.serialization.Serializable
private data class ScoringPointJson(
    val id: String,
    val type: String = "",
    val descriptionZh: String,
    val keywordsEn: String = "",
    val required: Boolean = false,
    val weight: Int = 0,
)

internal fun DialoguePairEntity.scoringPoints(): List<com.bess.salestrainer.core.model.ScoringPoint> =
    runCatching { json.decodeFromString<List<ScoringPointJson>>(scoringPointsJson) }
        .getOrDefault(emptyList())
        .map {
            com.bess.salestrainer.core.model.ScoringPoint(
                id = it.id,
                type = it.type,
                descriptionZh = it.descriptionZh,
                keywordsEn = it.keywordsEn,
                required = it.required,
                weight = it.weight,
            )
        }

// ---- corpus / audio ----

internal fun CorpusVersionEntity.toMetadata(): CorpusMetadata =
    CorpusMetadata(
        packageKey = packageKey,
        packageId = packageId,
        schemaVersion = schemaVersion,
        contentVersion = contentVersion,
        vocabularyCount = vocabularyCount,
        scenarioCount = scenarioCount,
        dialogueTurnCount = dialogueTurnCount,
        dialoguePairCount = dialoguePairCount,
        audioAssetCount = audioAssetCount,
        isBundled = isBundled,
    )

internal fun AudioAssetEntity.toModel(): AudioAsset =
    AudioAsset(
        id = id,
        kind = AudioKind.valueOf(kind),
        relativePath = relativePath,
        sha256 = sha256,
        mimeType = mimeType,
        codec = codec,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
    )
