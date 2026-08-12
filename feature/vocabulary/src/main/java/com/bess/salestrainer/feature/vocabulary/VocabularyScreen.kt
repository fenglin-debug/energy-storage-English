package com.bess.salestrainer.feature.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bess.salestrainer.core.model.MasteryFilter
import com.bess.salestrainer.core.model.Vocabulary

/** B-02: vocabulary browser + favorites. */
@Composable
fun VocabularyScreen(
    onStartPractice: () -> Unit = {},
    viewModel: VocabularyViewModel = hiltViewModel(),
) {
    val homeState by viewModel.homeState.collectAsStateWithLifecycle()
    VocabularyHomeContent(homeState, onStartPractice)
    return

    @Suppress("UNREACHABLE_CODE")
    val state by viewModel.listState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("词汇库", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = state.filter.query.orEmpty(),
            onValueChange = { viewModel.updateFilter(state.filter.copy(query = it.ifBlank { null })) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索单词 / 释义") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.filter.mastery == MasteryFilter.NOT_STARTED,
                onClick = {
                    viewModel.updateFilter(
                        state.filter.copy(
                            mastery = if (state.filter.mastery == MasteryFilter.NOT_STARTED) {
                                MasteryFilter.ALL
                            } else {
                                MasteryFilter.NOT_STARTED
                            },
                        ),
                    )
                },
                label = { Text("未学") },
            )
            FilterChip(
                selected = state.filter.dueOnly,
                onClick = { viewModel.updateFilter(state.filter.copy(dueOnly = !state.filter.dueOnly)) },
                label = { Text("到期") },
            )
            FilterChip(
                selected = state.filter.favoritesOnly,
                onClick = {
                    viewModel.updateFilter(state.filter.copy(favoritesOnly = !state.filter.favoritesOnly))
                },
                label = { Text("收藏") },
            )
        }

        androidx.compose.material3.Button(
            onClick = onStartPractice,
            enabled = !state.loading && state.words.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("开始学习 / 复习") }

        if (state.loading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
        } else if (state.words.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "正在准备离线学习内容，请稍候",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (state.words.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "正在准备离线学习内容，请稍候",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (state.words.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "正在准备离线学习内容，请稍候",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.words, key = { it.id }) { word ->
                    VocabularyRow(
                        word = word,
                        onToggleFavorite = {
                            viewModel.toggleFavorite(
                                word.id,
                                !(word.memoryState?.isFavorite ?: false),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VocabularyHomeContent(
    state: VocabularyHomeUiState,
    onStartPractice: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("今日词汇", style = MaterialTheme.typography.headlineMedium)
        Text(
            "新内容 ${state.newCount} 条 · 到期 ${state.dueCount} 条",
            modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.Button(
            onClick = onStartPractice,
            enabled = !state.loading && state.newCount + state.dueCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 12.dp),
                    strokeWidth = 2.dp,
                )
                Text("正在准备")
            } else {
                Text("开始背单词")
            }
        }
        if (!state.loading && state.newCount + state.dueCount == 0) {
            Text(
                "今天的自动学习任务已完成",
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VocabularyRow(word: Vocabulary, onToggleFavorite: () -> Unit) {
    val favorite = word.memoryState?.isFavorite ?: false
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(word.term, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "  ${word.ipa}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(word.chineseGloss, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${word.cefrLevel} · ${word.topic}" +
                        (word.memoryState?.let { " · 复习 ${it.reps} 次" } ?: " · 未开始"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.semantics {
                    contentDescription = if (favorite) "取消收藏 ${word.term}" else "收藏 ${word.term}"
                },
            ) {
                Icon(
                    imageVector = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (favorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Clears semantics for concealed content (a11y gate: hidden text must not leak). */
fun Modifier.concealedSemantics(): Modifier = clearAndSetSemantics { }
