package com.bess.salestrainer.core.corpus.pack

import kotlinx.serialization.Serializable

/**
 * .besspack JSON DTOs (TDD §7.3). Field names must match the offline
 * packager output exactly (strict unknown-key checking is on).
 */
@Serializable
data class PackManifestDto(
    val schemaVersion: Int,
    val packageId: String,
    val contentVersion: String,
    val createdAt: String,
    val minimumAppVersionCode: Int,
    val locale: PackLocaleDto? = null,
    val audioProfile: PackAudioProfileDto? = null,
    val counts: PackCountsDto,
    val dataFiles: List<PackFileDto>,
)

@Serializable
data class PackLocaleDto(
    val learning: String,
    val speech: String,
)

@Serializable
data class PackAudioProfileDto(
    val container: String,
    val codec: String,
    val sampleRateHz: Int,
    val channels: Int,
    val customerVoice: String? = null,
    /** v2: sales-engineer turns use a distinct voice. */
    val salesVoice: String? = null,
    val generationRate: Double? = null,
)

@Serializable
data class PackCountsDto(
    val vocabulary: Int,
    val phrases: Int = 0,
    val examples: Int = 0,
    val scenarios: Int,
    val dialogueTurns: Int,
    val dialoguePairs: Int,
    val audioAssets: Int,
)

@Serializable
data class PackFileDto(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
)

@Serializable
data class PackVocabularyDto(
    val id: String,
    val term: String,
    val normalizedTerm: String,
    val ipa: String,
    val partOfSpeech: String = "",
    val chineseGloss: String,
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
    val contentSource: String = "CORE",
    val contentHash: String,
)

@Serializable
data class PackPhraseDto(
    val id: String,
    val industry: String = "",
    val scene: String = "",
    val category: String = "",
    val textEn: String,
    val textZh: String,
    val linkedTermIds: List<String> = emptyList(),
    val sourceType: String = "",
    val audioAssetId: String,
    val contentHash: String,
)

@Serializable
data class PackExampleDto(
    val id: String,
    val industry: String = "",
    val scene: String = "",
    val speaker: String = "",
    val textEn: String,
    val textZh: String,
    val linkedTermIds: List<String> = emptyList(),
    val dialogueGroupId: String? = null,
    val sourceType: String = "",
    val audioAssetId: String,
    val contentHash: String,
)

@Serializable
data class PackScenarioDto(
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
data class PackDialogueTurnDto(
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

@Serializable
data class PackScoringPointDto(
    val id: String,
    val type: String = "",
    val descriptionZh: String,
    val keywordsEn: String = "",
    val required: Boolean = false,
    val weight: Int = 0,
)

@Serializable
data class PackDialoguePairDto(
    val id: String,
    val scenarioId: String,
    val pairIndex: Int,
    val customerTurnId: String,
    val salesTurnId: String,
    val referenceCoreEn: String,
    val referenceChineseHint: String,
    val formalAlternatives: List<String>,
    val scoringPoints: List<PackScoringPointDto> = emptyList(),
    val riskNote: String? = null,
    val contentHash: String,
)

@Serializable
data class PackDialoguePairWordDto(
    val pairId: String,
    val wordId: String,
    val sortOrder: Int = 0,
)

@Serializable
data class PackDialoguePairPhraseDto(
    val pairId: String,
    val phraseId: String,
    val sortOrder: Int = 0,
)

@Serializable
data class PackAudioAssetDto(
    val id: String,
    val kind: String,
    val relativePath: String,
    val sha256: String,
    val mimeType: String = "audio/mp4",
    val codec: String = "aac-lc",
    val durationMs: Long,
    val sizeBytes: Long,
)

@Serializable
data class AudioManifestDto(
    val assets: List<PackAudioAssetDto>,
)

/** Fully parsed and validated pack content, ready for DB import. */
data class ParsedPack(
    val manifest: PackManifestDto,
    val vocabulary: List<PackVocabularyDto>,
    val phrases: List<PackPhraseDto>,
    val examples: List<PackExampleDto>,
    val scenarios: List<PackScenarioDto>,
    val turns: List<PackDialogueTurnDto>,
    val pairs: List<PackDialoguePairDto>,
    val pairWords: List<PackDialoguePairWordDto>,
    val pairPhrases: List<PackDialoguePairPhraseDto>,
    val audioAssets: List<PackAudioAssetDto>,
    /** relativePath -> bytes for audio entries. */
    val audioBytes: Map<String, ByteArray>,
)
