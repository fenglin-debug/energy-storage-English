package com.bess.salestrainer.feature.scenario

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bess.salestrainer.core.model.SessionStatus

/** B-04: scenario browser with filters. */
@Composable
fun ScenarioScreen(
    onOpenScenario: (scenarioId: String) -> Unit = {},
    viewModel: ScenarioViewModel = hiltViewModel(),
) {
    val state by viewModel.listState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("情景模拟", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("随机练习", style = MaterialTheme.typography.titleLarge)
                Text(
                    "从全部储能对话中持续抽取，薄弱内容会按间隔再次出现。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { onOpenScenario(RANDOM_SCENARIO_ROUTE_ID) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("开始随机练习") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.filter.onlyIncomplete,
                onClick = {
                    viewModel.updateFilter(state.filter.copy(onlyIncomplete = !state.filter.onlyIncomplete))
                },
                label = { Text("未完成") },
            )
            FilterChip(
                selected = state.filter.difficulty == "B1-B2",
                onClick = {
                    viewModel.updateFilter(
                        state.filter.copy(
                            difficulty = if (state.filter.difficulty == "B1-B2") null else "B1-B2",
                        ),
                    )
                },
                label = { Text("B1-B2") },
            )
        }

        if (state.loading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.scenarios, key = { it.id }) { scenario ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    scenario.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (scenario.duePairCount > 0) {
                                    Text(
                                        "复习 ${scenario.duePairCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                StatusBadge(scenario.status)
                            }
                            Text(
                                "${scenario.topic} · ${scenario.salesStage} · ${scenario.customerRole}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${scenario.difficulty} · 约 ${scenario.estimatedMinutes} 分钟",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(onClick = { onOpenScenario(scenario.id) }) {
                                    Text(
                                        when (scenario.status) {
                                            SessionStatus.IN_PROGRESS -> "继续"
                                            SessionStatus.COMPLETED -> "再练一次"
                                            else -> "开始"
                                        },
                                    )
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
private fun StatusBadge(status: SessionStatus?) {
    val (label, color) = when (status) {
        SessionStatus.IN_PROGRESS -> "进行中" to MaterialTheme.colorScheme.tertiary
        SessionStatus.COMPLETED -> "已完成" to MaterialTheme.colorScheme.primary
        else -> return
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
}
