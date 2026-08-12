package com.bess.salestrainer.feature.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Rating
import com.bess.salestrainer.core.model.VocabularySelfAssessment

/** B-03: vocabulary practice — deterministic question modes, reveal-before-rate. */
@Composable
fun VocabularyPracticeScreen(
    onFinished: () -> Unit = {},
    viewModel: VocabularyViewModel = hiltViewModel(),
) {
    val state by viewModel.practiceState.collectAsStateWithLifecycle()
    var cardShownAtMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) { viewModel.startOrResumePractice() }

    VocabularySelfAssessmentContent(state, viewModel, onFinished)
    return

    @Suppress("UNREACHABLE_CODE")
    val view = state.view
    when {
        state.error != null -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.error ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    onClick = {
                        viewModel.leavePractice()
                        onFinished()
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("返回词汇列表") }
            }
        }
        state.completed -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("本次学习完成", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = {
                    viewModel.leavePractice()
                    onFinished()
                }, modifier = Modifier.padding(top = 16.dp)) { Text("返回") }
            }
        }
        view == null || view.currentWord == null -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
        }
        else -> {
            val word = view.currentWord ?: run {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
                return
            }
            val checkpoint = view.checkpoint
            val mode = checkpoint.questionMode

            // Reset timer whenever the card changes; LISTENING auto-plays.
            LaunchedEffect(word.id, checkpoint.currentIndex) {
                cardShownAtMs = System.currentTimeMillis()
                if (mode == QuestionMode.LISTENING) {
                    viewModel.playWordAudio()
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "剩余 ${view.remainingCount} · ${modeLabel(mode)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Question side: always visible; the ANSWER side stays
                        // out of composition (and semantics) until revealed.
                        Text(
                            questionPromptFor(mode, word),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        if (mode != QuestionMode.ZH2EN && mode != QuestionMode.LISTENING) {
                            Text(
                                word.ipa,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // Word audio replay (core interaction for LISTENING).
                        OutlinedButton(onClick = { viewModel.playWordAudio() }) {
                            Text("🔊 播放单词")
                        }

                        if (checkpoint.answerRevealed) {
                            AnswerPanel(mode, word)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.playWordAudio() }) {
                                    Text("🔊 单词")
                                }
                                OutlinedButton(onClick = { viewModel.playExampleAudio() }) {
                                    Text("🔊 例句")
                                }
                            }
                        } else {
                            Button(onClick = { viewModel.revealAnswer() }) { Text("显示答案") }
                        }
                    }
                }

                if (checkpoint.answerRevealed) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RatingButton("再来", Modifier.weight(1f), state.submitting) {
                            viewModel.rate(Rating.AGAIN, cardShownAtMs)
                        }
                        RatingButton("困难", Modifier.weight(1f), state.submitting) {
                            viewModel.rate(Rating.HARD, cardShownAtMs)
                        }
                        RatingButton("良好", Modifier.weight(1f), state.submitting) {
                            viewModel.rate(Rating.GOOD, cardShownAtMs)
                        }
                        RatingButton("简单", Modifier.weight(1f), state.submitting) {
                            viewModel.rate(Rating.EASY, cardShownAtMs)
                        }
                    }
                }

                OutlinedButton(onClick = {
                    viewModel.leavePractice()
                    onFinished()
                }, modifier = Modifier.fillMaxWidth()) { Text("结束本次学习") }
            }
        }
    }
}

@Composable
private fun VocabularySelfAssessmentContent(
    state: PracticeUiState,
    viewModel: VocabularyViewModel,
    onFinished: () -> Unit,
) {
    val view = state.view
    when {
        state.error != null -> {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.error.orEmpty())
                Button(
                    onClick = {
                        viewModel.leavePractice()
                        onFinished()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp),
                ) { Text("返回词汇") }
            }
        }
        state.completed -> {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("本次学习完成", style = MaterialTheme.typography.headlineSmall)
                Button(
                    onClick = {
                        viewModel.leavePractice()
                        onFinished()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp),
                ) { Text("返回") }
            }
        }
        view?.currentWord == null -> {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
        }
        else -> {
            val word = requireNotNull(view.currentWord)
            val submitted = view.checkpoint.assessmentSubmitted
            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                ) {
                    item {
                        Text(
                            "第 ${view.checkpoint.currentIndex + 1} 条 · 剩余 ${view.remainingCount}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(word.term, style = MaterialTheme.typography.headlineMedium)
                                if (word.ipa.isNotBlank()) {
                                    Text(word.ipa, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(word.chineseGloss, style = MaterialTheme.typography.titleMedium)
                                OutlinedButton(
                                    onClick = viewModel::playWordAudio,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Filled.VolumeUp, contentDescription = null)
                                    Text("朗读", modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                    items(word.examples.size) { index ->
                        val example = word.examples[index]
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(example.textEn, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    example.textZh,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    onClick = { viewModel.playExampleAudio(example.audioAssetId) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Filled.VolumeUp, contentDescription = null)
                                    Text("朗读例句", modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }

                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!submitted) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AssessmentButton("陌生", Modifier.weight(1f), state.submitting) {
                                viewModel.submitAssessment(VocabularySelfAssessment.UNFAMILIAR)
                            }
                            AssessmentButton("模糊", Modifier.weight(1f), state.submitting) {
                                viewModel.submitAssessment(VocabularySelfAssessment.FUZZY)
                            }
                            AssessmentButton("掌握", Modifier.weight(1f), state.submitting) {
                                viewModel.submitAssessment(VocabularySelfAssessment.MASTERED)
                            }
                        }
                        if (state.submitting) {
                            Text("正在保存评价…", color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        Text(
                            "已选择：${assessmentLabel(view.checkpoint.selectedAssessment)}",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Button(
                            onClick = viewModel::advanceToNext,
                            enabled = !state.submitting,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(if (view.hasNext) "下一条" else "完成本次学习")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssessmentButton(
    label: String,
    modifier: Modifier,
    submitting: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = !submitting,
        modifier = modifier.heightIn(min = 48.dp),
    ) { Text(label) }
}

private fun assessmentLabel(value: VocabularySelfAssessment?): String = when (value) {
    VocabularySelfAssessment.UNFAMILIAR -> "陌生"
    VocabularySelfAssessment.FUZZY -> "模糊"
    VocabularySelfAssessment.MASTERED -> "掌握"
    null -> ""
}

@Composable
private fun AnswerPanel(mode: QuestionMode, word: com.bess.salestrainer.core.model.Vocabulary) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (mode) {
            QuestionMode.EN2ZH, QuestionMode.INTRODUCE -> {
                if (mode == QuestionMode.INTRODUCE) {
                    Text(word.term, style = MaterialTheme.typography.titleLarge)
                }
                Text(word.chineseGloss, style = MaterialTheme.typography.titleMedium)
            }
            QuestionMode.ZH2EN -> {
                Text(word.term, style = MaterialTheme.typography.titleLarge)
                Text(word.ipa, style = MaterialTheme.typography.bodyMedium)
            }
            QuestionMode.LISTENING -> {
                Text(word.term, style = MaterialTheme.typography.titleLarge)
                Text(word.chineseGloss, style = MaterialTheme.typography.titleMedium)
            }
            QuestionMode.TRANSFER -> {
                Text(word.exampleSentenceEn, style = MaterialTheme.typography.bodyLarge)
                Text(word.chineseGloss, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "例句：${word.exampleSentenceEn}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "常见错误：${word.commonMistakes}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RatingButton(
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = !enabled) {
        Text(label)
    }
}

internal fun modeLabel(mode: QuestionMode): String = when (mode) {
    QuestionMode.INTRODUCE -> "新词学习"
    QuestionMode.EN2ZH -> "英译中"
    QuestionMode.ZH2EN -> "中译英"
    QuestionMode.LISTENING -> "听音辨词"
    QuestionMode.TRANSFER -> "迁移运用"
}
