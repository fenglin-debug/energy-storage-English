package com.bess.salestrainer.feature.scenario

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bess.salestrainer.core.model.CustomerTextView
import com.bess.salestrainer.core.model.DialogueSelfRating
import com.bess.salestrainer.core.model.KeywordsView
import com.bess.salestrainer.core.model.ReferenceAnswerView

/**
 * B-05: scenario practice — one pair at a time, one-way reveals.
 *
 * Accessibility gate: concealed customer text / keywords / reference answer
 * never enter composition, so they cannot leak into TalkBack semantics.
 */
@Composable
fun ScenarioPracticeScreen(
    scenarioId: String,
    onFinished: () -> Unit = {},
    viewModel: ScenarioViewModel = hiltViewModel(),
) {
    val state by viewModel.practiceState.collectAsStateWithLifecycle()

    LaunchedEffect(scenarioId) { viewModel.startOrResume(scenarioId) }

    val view = state.unitView
    when {
        state.completed -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("情景练习完成", style = MaterialTheme.typography.headlineSmall)
                if (state.ratingDistribution.isNotEmpty()) {
                    Text(
                        "流利 ${state.ratingDistribution[DialogueSelfRating.FLUENT] ?: 0} · " +
                            "基本 ${state.ratingDistribution[DialogueSelfRating.BASIC] ?: 0} · " +
                            "待加强 ${state.ratingDistribution[DialogueSelfRating.CANNOT_ANSWER] ?: 0}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Button(onClick = {
                    viewModel.leavePractice()
                    onFinished()
                }, modifier = Modifier.padding(top = 16.dp)) { Text("返回列表") }
            }
        }
        view == null -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
        }
        else -> {
            val unit = view.unit
            val progress = view.progress

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "第 ${unit.pairIndex + 1} / ${unit.pairCount} 轮",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- Customer card: audio + concealed text ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("客户", style = MaterialTheme.typography.titleSmall)
                        when (val customer = unit.customerText) {
                            is CustomerTextView.Revealed -> Text(
                                customer.english,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            CustomerTextView.Concealed -> Text(
                                "▓▓▓▓▓▓▓▓▓▓（先听音频）",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clearAndSetSemantics { },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                viewModel.playCustomerAudio(unit.customerAudioAssetId)
                            }) { Text("▶ 播放客户音频") }
                            if (unit.customerText is CustomerTextView.Concealed) {
                                OutlinedButton(
                                    onClick = { viewModel.revealCustomerText() },
                                    enabled = progress.customerAudioCompleted,
                                ) { Text("显示原文") }
                            }
                        }
                    }
                }

                // ---- Keywords card ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("关键词汇", style = MaterialTheme.typography.titleSmall)
                        when (val keywords = unit.keywords) {
                            is KeywordsView.Revealed -> keywords.values.forEach { kw ->
                                Text(
                                    "${kw.english}  ${kw.ipa}  ${kw.chineseGloss}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            KeywordsView.Concealed -> OutlinedButton(onClick = { viewModel.revealKeywords() }) {
                                Text("查看关键词")
                            }
                        }
                    }
                }

                // ---- Reference answer card ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("参考回答", style = MaterialTheme.typography.titleSmall)
                        when (val answer = unit.answer) {
                            is ReferenceAnswerView.Revealed -> {
                                Text(answer.value.coreEnglish, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    answer.value.chineseHint,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                answer.value.formalAlternatives.forEachIndexed { i, alt ->
                                    Text(
                                        "备选 ${i + 1}：$alt",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                if (answer.value.scoringPoints.isNotEmpty()) {
                                    Text(
                                        "评分要点",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                    answer.value.scoringPoints.forEach { point ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                if (point.required) "【必答】" else "【加分】",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (point.required) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.tertiary
                                                },
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    point.descriptionZh,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                if (point.keywordsEn.isNotBlank()) {
                                                    Text(
                                                        "关键词：${point.keywordsEn}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            ReferenceAnswerView.Concealed -> OutlinedButton(onClick = { viewModel.revealAnswer() }) {
                                Text("查看参考回答")
                            }
                        }
                    }
                }

                // ---- Self rating (enabled only after answer revealed) ----
                if (unit.answer is ReferenceAnswerView.Revealed) {
                    Text("自评并进入下一轮", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.rate(DialogueSelfRating.CANNOT_ANSWER) },
                            modifier = Modifier.weight(1f),
                            enabled = !state.submitting,
                        ) { Text("答不出") }
                        OutlinedButton(
                            onClick = { viewModel.rate(DialogueSelfRating.BASIC) },
                            modifier = Modifier.weight(1f),
                            enabled = !state.submitting,
                        ) { Text("基本完成") }
                        Button(
                            onClick = { viewModel.rate(DialogueSelfRating.FLUENT) },
                            modifier = Modifier.weight(1f),
                            enabled = !state.submitting,
                        ) { Text("流利") }
                    }
                }

                OutlinedButton(onClick = {
                    viewModel.leavePractice()
                    onFinished()
                }, modifier = Modifier.fillMaxWidth()) { Text("退出本次练习") }
            }
        }
    }
}
