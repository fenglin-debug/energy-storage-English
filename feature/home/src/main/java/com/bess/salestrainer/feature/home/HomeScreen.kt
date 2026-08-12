package com.bess.salestrainer.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import com.bess.salestrainer.core.model.ResumeTarget

/** B-01: home dashboard — today's task, streak, resume entry. */
@Composable
fun HomeScreen(
    onStartVocabulary: () -> Unit = {},
    onResumeVocabulary: (sessionId: String) -> Unit = {},
    onOpenScenario: (scenarioId: String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.loading || state.task == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
        return
    }
    val task = state.task!!

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("今日任务", style = MaterialTheme.typography.headlineSmall)
        Text(
            "连续学习 ${task.streakDays} 天 · 今日已学 ${task.studySecondsToday / 60} 分钟",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 7-day activity dots
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            task.last7DaysActive.forEach { active ->
                Text(
                    text = if (active) "●" else "○",
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        // Resume card (most recent interrupted session wins, TDD §9.3)
        state.resumeTarget?.let { resume ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("继续上次学习", style = MaterialTheme.typography.titleMedium)
                    when (resume) {
                        is ResumeTarget.VocabularyResume -> {
                            Text(
                                "词汇复习 · 还剩 ${resume.remainingCount} 个",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = { onResumeVocabulary(resume.sessionId) }) {
                                Text("继续复习")
                            }
                        }
                        is ResumeTarget.ScenarioResume -> {
                            Text(
                                "情景「${resume.session.scenarioTitle}」 · 第 ${resume.session.currentPairIndex + 1}/${resume.session.pairCount} 轮",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = { onOpenScenario(resume.session.scenarioId) }) {
                                Text("继续情景")
                            }
                        }
                    }
                }
            }
        }

        // New words progress
        ProgressCard(
            title = "新词学习",
            done = task.newWordDone,
            target = task.newWordTarget,
        )
        // Review progress
        ProgressCard(
            title = "到期复习",
            done = task.reviewDone,
            target = task.reviewTarget,
        )

        Button(
            onClick = onStartVocabulary,
            modifier = Modifier.fillMaxWidth(),
            enabled = !task.completed,
        ) {
            Text(if (task.completed) "今日任务已完成" else "开始今日学习")
        }

        // Recommended scenario
        task.recommendedScenario?.let { scenario ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("推荐情景", style = MaterialTheme.typography.titleMedium)
                    Text(scenario.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${scenario.topic} · ${scenario.difficulty} · 约 ${scenario.estimatedMinutes} 分钟",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(onClick = { onOpenScenario(scenario.id) }) {
                        Text("进入情景")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(title: String, done: Int, target: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text("$done / $target", style = MaterialTheme.typography.bodyMedium)
            }
            LinearProgressIndicator(
                progress = { if (target == 0) 1f else (done.toFloat() / target).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
