package com.bess.salestrainer.feature.vocabulary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.bess.salestrainer.core.model.VocabularyFilter

/** B-02: vocabulary browser + favorites. */
@Composable
fun VocabularyScreen(
    onStartPractice: () -> Unit = {},
    onOpenWord: (String) -> Unit = {},
    viewModel: VocabularyViewModel = hiltViewModel(),
) {
    val homeState by viewModel.homeState.collectAsStateWithLifecycle()
    val state by viewModel.listState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TodaySummary(homeState, onStartPractice)

        OutlinedTextField(
            value = state.filter.query.orEmpty(),
            onValueChange = { viewModel.updateFilter(state.filter.copy(query = it.ifBlank { null })) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索单词 / 释义") },
            singleLine = true,
        )

        FilterChipRow(
            filter = state.filter,
            onFilterChange = viewModel::updateFilter,
        )

        when {
            state.loading -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
            }
            state.words.isEmpty() && state.filter.hasActiveConstraints() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "没有符合条件的词",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = viewModel::clearFilter,
                        modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp),
                    ) { Text("清除筛选") }
                }
            }
            state.words.isEmpty() -> {
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
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.words, key = { it.id }) { word ->
                        VocabularyRow(
                            word = word,
                            onOpenWord = onOpenWord,
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
}

@Composable
private fun TodaySummary(
    state: VocabularyHomeUiState,
    onStartPractice: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("今日词汇", style = MaterialTheme.typography.titleLarge)
        Text(
            "新内容 ${state.newCount} · 到期 ${state.dueCount}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onStartPractice,
            enabled = !state.loading && state.newCount + state.dueCount > 0,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilterChipRow(
    filter: VocabularyFilter,
    onFilterChange: (VocabularyFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter.mastery == MasteryFilter.NOT_STARTED,
            onClick = {
                onFilterChange(filter.copy(mastery = nextMasteryFilter(filter.mastery, MasteryFilter.NOT_STARTED)))
            },
            label = { Text("未学") },
        )
        FilterChip(
            selected = filter.mastery == MasteryFilter.LEARNING,
            onClick = {
                onFilterChange(filter.copy(mastery = nextMasteryFilter(filter.mastery, MasteryFilter.LEARNING)))
            },
            label = { Text("学习中") },
        )
        FilterChip(
            selected = filter.mastery == MasteryFilter.MASTERED,
            onClick = {
                onFilterChange(filter.copy(mastery = nextMasteryFilter(filter.mastery, MasteryFilter.MASTERED)))
            },
            label = { Text("已掌握") },
        )
        FilterChip(
            selected = filter.dueOnly,
            onClick = { onFilterChange(filter.copy(dueOnly = !filter.dueOnly)) },
            label = { Text("到期") },
        )
        FilterChip(
            selected = filter.favoritesOnly,
            onClick = { onFilterChange(filter.copy(favoritesOnly = !filter.favoritesOnly)) },
            label = { Text("收藏") },
        )
    }
}

@Composable
private fun VocabularyRow(
    word: Vocabulary,
    onOpenWord: (String) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val favorite = word.memoryState?.isFavorite ?: false
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenWord(word.id) },
    ) {
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

internal fun nextMasteryFilter(current: MasteryFilter, tapped: MasteryFilter): MasteryFilter =
    if (current == tapped) MasteryFilter.ALL else tapped

internal fun VocabularyFilter.hasActiveConstraints(): Boolean =
    !query.isNullOrBlank() ||
        mastery != MasteryFilter.ALL ||
        dueOnly ||
        favoritesOnly ||
        !topic.isNullOrBlank() ||
        !cefrLevel.isNullOrBlank()

/** Clears semantics for concealed content (a11y gate: hidden text must not leak). */
fun Modifier.concealedSemantics(): Modifier = clearAndSetSemantics { }
