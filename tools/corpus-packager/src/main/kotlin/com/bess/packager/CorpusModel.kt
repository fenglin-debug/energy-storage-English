package com.bess.packager

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// .besspack schema 2 wire format (v2 corpus: 风电储能行业英文词汇表.xlsx).
// Key order is fixed by the deterministic writer; arrays sorted by stable id.
// ---------------------------------------------------------------------------

@Serializable
data class PackManifest(
    val schemaVersion: Int,
    val packageId: String,
    val contentVersion: String,
    val createdAt: String,
    val minimumAppVersionCode: Int,
    val locale: LocaleInfo,
    val audioProfile: AudioProfile,
    val counts: Counts,
    val dataFiles: List<DataFileEntry>,
) {
    @Serializable
    data class LocaleInfo(val learning: String, val speech: String)

    @Serializable
    data class AudioProfile(
        val container: String,
        val codec: String,
        val sampleRateHz: Int,
        val channels: Int,
        val customerVoice: String,
        val salesVoice: String,
        val generationRate: Double,
    )

    @Serializable
    data class Counts(
        val vocabulary: Int,
        val phrases: Int,
        val examples: Int,
        val scenarios: Int,
        val dialogueTurns: Int,
        val dialoguePairs: Int,
        val audioAssets: Int,
    )

    @Serializable
    data class DataFileEntry(
        val path: String,
        val sha256: String,
        val sizeBytes: Long,
    )
}

@Serializable
data class PackVocabulary(
    val id: String,
    val term: String,
    val normalizedTerm: String,
    val ipa: String,
    val partOfSpeech: String,
    val chineseGloss: String,
    val englishDefinition: String? = null,
    val collocations: List<String> = emptyList(),
    val exampleSentenceEn: String,
    val exampleSentenceZh: String? = null,
    val commonMistakes: String,
    val topic: String,
    val scenarioTags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val cefrLevel: String,
    val wordAudioAssetId: String,
    val exampleAudioAssetId: String,
    val contentSource: String,
    val contentHash: String,
)

/** 常用短语 sheet 条目（v2 新增）。 */
@Serializable
data class PackPhrase(
    val id: String,
    val industry: String,
    val scene: String,
    val category: String,
    val textEn: String,
    val textZh: String,
    val linkedTermIds: List<String> = emptyList(),
    val sourceType: String,
    val audioAssetId: String,
    val contentHash: String,
)

/** 常用例句 sheet 条目（v2 新增）。 */
@Serializable
data class PackExample(
    val id: String,
    val industry: String,
    val scene: String,
    val speaker: String,
    val textEn: String,
    val textZh: String,
    val linkedTermIds: List<String> = emptyList(),
    val dialogueGroupId: String? = null,
    val sourceType: String,
    val audioAssetId: String,
    val contentHash: String,
)

@Serializable
data class PackScenario(
    val id: String,
    val title: String,
    val topic: String,
    val salesStage: String,
    val customerRole: String,
    val difficulty: String,
    val projectType: String,
    val estimatedMinutes: Int,
    val description: String? = null,
    val contentHash: String,
)

@Serializable
data class PackDialogueTurn(
    val id: String,
    val scenarioId: String,
    val turnNo: Int,
    val speaker: String,
    val textEn: String,
    val textZh: String? = null,
    val hint: String? = null,
    val audioAssetId: String? = null,
    val contentHash: String,
)

/** 评分要点（嵌入 dialogue pair，仅展示用）。 */
@Serializable
data class PackScoringPoint(
    val id: String,
    val type: String,
    val descriptionZh: String,
    val keywordsEn: String,
    val required: Boolean,
    val weight: Int,
)

@Serializable
data class PackDialoguePair(
    val id: String,
    val scenarioId: String,
    val pairIndex: Int,
    val customerTurnId: String,
    val salesTurnId: String,
    val referenceCoreEn: String,
    val referenceChineseHint: String,
    val formalAlternatives: List<String>,
    val scoringPoints: List<PackScoringPoint> = emptyList(),
    val riskNote: String? = null,
    val contentHash: String,
)

@Serializable
data class PackDialoguePairWord(
    val pairId: String,
    val wordId: String,
    val sortOrder: Int,
)

@Serializable
data class PackDialoguePairPhrase(
    val pairId: String,
    val phraseId: String,
    val sortOrder: Int,
)

@Serializable
data class PackAudioAsset(
    val id: String,
    val kind: String, // CUSTOMER_TURN / SALES_TURN / WORD / EXAMPLE / PHRASE
    val relativePath: String,
    val sha256: String,
    val mimeType: String,
    val codec: String,
    val durationMs: Long,
    val sizeBytes: Long,
)

@Serializable
data class AudioManifest(
    val assets: List<PackAudioAsset>,
)

@Serializable
data class BuildReport(
    val packageId: String,
    val contentVersion: String,
    val generatedAt: String,
    val vocabularyMerged: VocabularyMergeReport,
    val scenarioChecks: ScenarioCheckReport,
    val anonymizationScan: AnonymizationReport,
    val overlayDecisions: List<String>,
    val audio: AudioReport,
) {
    @Serializable
    data class VocabularyMergeReport(
        val mainCount: Int,
        val windCount: Int,
        val storageCount: Int,
        val duplicatesMerged: Int,
        val templateExampleFallbacks: List<String> = emptyList(),
        val missingRequiredFields: List<String>,
        val missingAudioPlaceholders: List<String>,
    )

    @Serializable
    data class ScenarioCheckReport(
        val scenarioCount: Int,
        val turnCount: Int,
        val pairCount: Int,
        val phraseCount: Int = 0,
        val exampleCount: Int = 0,
        val turnsPerScenario: Map<String, Int>,
        val unassignedScoringPoints: List<String> = emptyList(),
        val violations: List<String>,
    )

    @Serializable
    data class AnonymizationReport(
        val blockedHits: List<String>,
        val amountPatternHits: Int,
    )

    @Serializable
    data class AudioReport(
        val requiredAssets: Int,
        val placeholders: Int,
        val presentOnDisk: Int,
    )
}
