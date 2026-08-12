package com.bess.salestrainer.core.corpus.pack.article

import kotlinx.serialization.Serializable

/** .bessarticle manifest DTO (article library pack, append/upsert semantics). */
@Serializable
data class ArticlePackManifestDto(
    val schemaVersion: Int,
    val packageId: String,
    val contentVersion: String,
    val createdAt: String = "",
    val articles: List<ArticlePackEntryDto>,
)

@Serializable
data class ArticlePackEntryDto(
    val id: String,
    val title: String,
    val titleZh: String = "",
    val topic: String,
    val paragraphs: List<ArticleParagraphDto>,
    val audioFile: String,
    val durationMs: Long,
    val contentHash: String,
    val contentScope: String = "UNSPECIFIED",
)

@Serializable
data class ArticleParagraphDto(
    val textEn: String,
    val textZh: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
)

/** Fully parsed and validated article pack. */
data class ParsedArticlePack(
    val manifest: ArticlePackManifestDto,
    /** articleId -> audio bytes. */
    val audioBytes: Map<String, ByteArray>,
)
