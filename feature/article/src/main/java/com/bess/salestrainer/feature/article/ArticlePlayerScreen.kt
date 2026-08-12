package com.bess.salestrainer.feature.article

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bess.salestrainer.core.model.ArticleParagraph

/**
 * 前台文章播放器。正文始终显示完整中英对照，并按音频进度高亮当前英语句子。
 *
 * 文章包没有逐句时间戳，因此同步位置按各段英文词数比例估算。
 */
@Composable
fun ArticlePlayerScreen(
    articleId: String,
    onBack: () -> Unit = {},
    viewModel: ArticleViewModel = hiltViewModel(),
) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()

    LaunchedEffect(articleId) { viewModel.openArticle(articleId) }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopAndLeave() }
    }

    val article = state.article
    val transcript = remember(article?.id, article?.paragraphs) {
        bilingualSentencePairs(article?.paragraphs.orEmpty())
    }
    val durationMs = state.durationMs.takeIf { it > 0 } ?: article?.durationMs ?: 0L
    val activeParagraph = syncedParagraphIndex(state.positionMs, durationMs, transcript)

    ArticleFixedPlayer(
        state = state,
        transcript = transcript,
        activeParagraph = activeParagraph,
        onBack = onBack,
        viewModel = viewModel,
    )
}

@Composable
private fun ArticleFixedPlayer(
    state: ArticlePlayerState,
    transcript: List<ArticleParagraph>,
    activeParagraph: Int,
    onBack: () -> Unit,
    viewModel: ArticleViewModel,
) {
    val article = state.article
    when {
        state.loading -> LoadingArticle()
        article == null -> MissingArticle(state.error ?: "文章不存在", onBack)
        else -> {
            var expandedTranslations by remember(article.id) { mutableStateOf(emptySet<Int>()) }
            val listState = rememberLazyListState()
            LaunchedEffect(activeParagraph, state.playing) {
                if (state.playing && activeParagraph >= 0) {
                    listState.animateScrollToItem(activeParagraph + 1)
                }
            }
            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (state.randomMode) "随机磨耳朵" else "文章磨耳朵",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    if (transcript.isEmpty()) {
                                        "纯听练习"
                                    } else {
                                        "英文全文 · 点击有翻译的句子查看中文"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (transcript.isEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "此文章未附字幕，可进行纯听练习",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        "播放、暂停、拖动进度和下一篇功能仍可正常使用。",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    itemsIndexed(
                        transcript,
                        key = { index, item -> "$index-${item.textEn.hashCode()}" },
                    ) { index, paragraph ->
                        val active = index == activeParagraph
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = paragraph.textZh.isNotBlank()) {
                                    expandedTranslations =
                                        if (index in expandedTranslations) {
                                            expandedTranslations - index
                                        } else {
                                            expandedTranslations + index
                                        }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (active) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                            ),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(
                                    paragraph.textEn,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (active) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                                if (paragraph.textZh.isNotBlank() && index in expandedTranslations) {
                                    Text(
                                        paragraph.textZh,
                                        modifier = Modifier.padding(top = 8.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .navigationBarsPadding(),
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            article.titleZh.ifBlank { article.title },
                            maxLines = 1,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Slider(
                            value = state.positionMs
                                .coerceIn(0L, state.durationMs.coerceAtLeast(1L)).toFloat(),
                            onValueChange = { viewModel.seekTo(it.toLong()) },
                            valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilledTonalIconButton(
                                    onClick = viewModel::previousArticle,
                                    enabled = state.canGoNext,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一篇")
                                }
                                FilledIconButton(
                                    onClick = {
                                        if (state.playing) viewModel.pause() else viewModel.play()
                                    },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (state.playing) "暂停" else "播放",
                                    )
                                }
                                FilledTonalIconButton(
                                    onClick = viewModel::nextArticle,
                                    enabled = state.canGoNext,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Filled.SkipNext, contentDescription = "下一篇")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingArticle() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MissingArticle(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
            Text("返回文章列表")
        }
    }
}

internal fun syncedParagraphIndex(
    positionMs: Long,
    durationMs: Long,
    paragraphs: List<ArticleParagraph>,
): Int {
    if (paragraphs.isEmpty()) return -1
    if (durationMs <= 0L || positionMs <= 0L) return 0
    if (positionMs >= durationMs) return paragraphs.lastIndex

    val hasCues = paragraphs.all { it.startMs != null && it.endMs != null }
    if (hasCues) {
        var low = 0
        var high = paragraphs.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val item = paragraphs[mid]
            val start = requireNotNull(item.startMs)
            val end = requireNotNull(item.endMs)
            when {
                positionMs < start -> high = mid - 1
                positionMs >= end -> low = mid + 1
                else -> return mid
            }
        }
        return low.coerceIn(0, paragraphs.lastIndex)
    }

    val weights = paragraphs.map { paragraph ->
        paragraph.textEn.trim().split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
    }
    val totalWeight = weights.sum().coerceAtLeast(1)
    val targetWeight = positionMs.toDouble() / durationMs.toDouble() * totalWeight
    var cumulativeWeight = 0
    weights.forEachIndexed { index, weight ->
        cumulativeWeight += weight
        if (targetWeight < cumulativeWeight) return index
    }
    return paragraphs.lastIndex
}

/**
 * Article packs store several sentences in each paragraph. Split only when the
 * English and Chinese sentence counts match, so bilingual alignment is never
 * guessed incorrectly.
 */
internal fun bilingualSentencePairs(paragraphs: List<ArticleParagraph>): List<ArticleParagraph> =
    paragraphs.flatMap { paragraph ->
        // SRT cues are already the authoritative synchronization unit. Splitting
        // them would discard their exact timestamps and make the whole transcript
        // fall back to approximate word-weight synchronization.
        if (paragraph.startMs != null && paragraph.endMs != null) {
            return@flatMap listOf(paragraph)
        }
        val english = paragraph.textEn
            .split(Regex("(?<=[.!?])\\s+"))
            .map(String::trim)
            .filter(String::isNotBlank)
        val chinese = paragraph.textZh
            .split(Regex("(?<=[。！？])"))
            .map(String::trim)
            .filter(String::isNotBlank)

        if (english.size == 1 && chinese.size == 1) {
            listOf(paragraph)
        } else if (english.isNotEmpty() && english.size == chinese.size) {
            english.indices.map { index ->
                ArticleParagraph(textEn = english[index], textZh = chinese[index])
            }
        } else {
            listOf(paragraph)
        }
    }

private fun formatMs(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
