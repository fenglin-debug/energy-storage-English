package com.bess.salestrainer.core.data.repository

import com.bess.salestrainer.core.data.mapper.toStringList
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.ArticleEntity
import com.bess.salestrainer.core.database.entity.ArticleProgressEntity
import com.bess.salestrainer.core.model.Article
import com.bess.salestrainer.core.model.ArticleContentScope
import com.bess.salestrainer.core.model.ArticleParagraph
import com.bess.salestrainer.core.model.ArticleProgress
import com.bess.salestrainer.core.model.ArticleSource
import com.bess.salestrainer.core.model.contract.ArticleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun ArticleEntity.toModel(progress: ArticleProgressEntity?): Article {
    val paragraphs = runCatching {
        json.parseToJsonElement(paragraphsJson).jsonArray.map { element ->
            val item = element.jsonObject
            ArticleParagraph(
                textEn = (item["textEn"] ?: item["en"])?.jsonPrimitive?.content.orEmpty(),
                textZh = (item["textZh"] ?: item["zh"])?.jsonPrimitive?.content.orEmpty(),
                startMs = item["startMs"]?.jsonPrimitive?.content?.toLongOrNull(),
                endMs = item["endMs"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }.filter { it.textEn.isNotBlank() || it.textZh.isNotBlank() }
    }.getOrDefault(emptyList())
    return Article(
        id = id,
        title = title,
        titleZh = titleZh,
        topic = topic,
        paragraphs = paragraphs,
        audioAssetId = audioAssetId,
        durationMs = durationMs,
        source = runCatching { ArticleSource.valueOf(source) }.getOrDefault(ArticleSource.IMPORTED),
        contentScope = runCatching {
            ArticleContentScope.valueOf(contentScope)
        }.getOrDefault(ArticleContentScope.UNSPECIFIED),
        progress = progress?.let {
            ArticleProgress(
                lastPositionMs = it.lastPositionMs,
                listenCount = it.listenCount,
                completed = it.completedAtEpochMs != null,
            )
        },
    )
}

private val json = Json { ignoreUnknownKeys = true }
