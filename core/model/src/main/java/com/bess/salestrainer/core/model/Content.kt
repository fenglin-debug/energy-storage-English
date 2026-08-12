package com.bess.salestrainer.core.model

import java.time.Instant

/** 短语 (PHR-nnnn) as exposed to UI. FR-v2: phrase practice joins the vocabulary queue. */
data class Phrase(
    val id: String,
    val industry: String,
    val scene: String,
    val category: String,
    val textEn: String,
    val textZh: String,
    val linkedTermIds: List<String> = emptyList(),
    val audioAssetId: String,
    val contentHash: String,
)

/** 例句 (EX-nnnn): the sentence-practice learning unit. */
data class SentenceExample(
    val id: String,
    val industry: String,
    val scene: String,
    val speaker: Speaker,
    val textEn: String,
    val textZh: String,
    val linkedTermIds: List<String> = emptyList(),
    val dialogueGroupId: String? = null,
    val audioAssetId: String,
    val contentHash: String,
)

/** Generic FSRS state snapshot for non-word items (PHRASE / EXAMPLE / PAIR). */
data class ItemMemoryState(
    val itemId: String,
    val itemType: ItemType,
    val fsrsState: FsrsState,
    val dueAt: Instant,
    val reps: Int,
    val lapses: Int,
    val mastered: Boolean = false,
)

enum class ItemType { PHRASE, EXAMPLE, PAIR }

/** Sentence practice queue: new examples + due reviews. */
data class SentenceQueue(
    val newItems: List<SentenceExample>,
    val dueReviews: List<SentenceExample>,
)

data class SentenceSessionView(
    val sessionId: String,
    val queueIds: List<String>,
    val currentIndex: Int,
    val currentItem: SentenceExample?,
    val questionMode: QuestionMode,
    val answerRevealed: Boolean,
    val remainingCount: Int,
)

/** 磨耳朵文章. */
data class Article(
    val id: String,
    val title: String,
    val titleZh: String,
    val topic: String,
    val paragraphs: List<ArticleParagraph>,
    val audioAssetId: String,
    val durationMs: Long,
    val source: ArticleSource,
    val contentScope: ArticleContentScope = ArticleContentScope.UNSPECIFIED,
    val progress: ArticleProgress? = null,
)

data class ArticleParagraph(
    val textEn: String,
    val textZh: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
)

enum class ArticleSource { BUNDLED, IMPORTED }
enum class ArticleContentScope { BESS, UNSPECIFIED }

data class ArticleProgress(
    val lastPositionMs: Long,
    val listenCount: Int,
    val completed: Boolean,
)

data class ArticleImportResult(
    val imported: Int,
    val errors: List<CorpusError> = emptyList(),
)

/** Command used by the simple audio + optional subtitle import flow. */
data class LocalArticleImportCommand(
    val audioSource: CorpusSource,
    val audioDisplayName: String,
    val subtitleSource: CorpusSource? = null,
    val subtitleDisplayName: String? = null,
    val title: String,
    val topic: String = "本地导入",
    val includeInRandomPractice: Boolean = true,
)

data class LocalArticleAudioInspection(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val sha256: String,
)

data class LocalArticleSubtitleInspection(
    val displayName: String,
    val cueCount: Int,
)

enum class LocalArticleImportStatus { CREATED, UPDATED }

enum class LocalArticleImportError {
    EMPTY_TITLE,
    SOURCE_UNREADABLE,
    UNSUPPORTED_AUDIO_FORMAT,
    AUDIO_TOO_LARGE,
    AUDIO_TOO_LONG,
    INVALID_AUDIO,
    UNSUPPORTED_SUBTITLE_FORMAT,
    SUBTITLE_TOO_LARGE,
    TOO_MANY_SUBTITLE_CUES,
    INVALID_SUBTITLE_ENCODING,
    INVALID_SUBTITLE_TIMELINE,
    EMPTY_SUBTITLE,
    TRANSACTION_FAILURE,
}

data class LocalArticleInspectionResult<T>(
    val value: T? = null,
    val error: LocalArticleImportError? = null,
)

data class LocalArticleImportResult(
    val articleId: String? = null,
    val status: LocalArticleImportStatus? = null,
    val error: LocalArticleImportError? = null,
)

enum class ArticleDeletionResult {
    DELETED,
    NOT_FOUND,
    BUNDLED_PROTECTED,
}
