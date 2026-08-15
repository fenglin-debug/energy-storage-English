package com.bess.salestrainer.feature.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Vocabulary
import com.bess.salestrainer.core.model.VocabularySelfAssessment

/** B-03: vocabulary practice — deterministic question modes, reveal-before-rate. */
@Composable
fun VocabularyPracticeScreen(
    onFinished: () -> Unit = {},
    viewModel: VocabularyViewModel = hiltViewModel(),
) {
    val state by viewModel.practiceState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.startOrResumePractice() }

    val view = state.view
    when {
        state.error != null -> {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.error.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                )
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
            val checkpoint = view.checkpoint
            val mode = checkpoint.questionMode
            val submitted = checkpoint.assessmentSubmitted
            val revealed = checkpoint.answerRevealed

            LaunchedEffect(word.id, mode) {
                if (mode == QuestionMode.LISTENING) {
                    viewModel.playWordAudio()
                }
            }

            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(20.dp),
                ) {
                    item {
                        Text(
                            "第 ${checkpoint.currentIndex + 1} 条 · 剩余 ${view.remainingCount} · ${modeLabel(mode)}",
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
                                PromptPanel(mode, word, viewModel)
                                if (revealed) {
                                    AnswerPanel(mode, word, viewModel)
                                }
                            }
                        }
                    }
                }

                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!revealed) {
                        Button(
                            onClick = { viewModel.revealAnswer() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("显示答案") }
                    } else if (!submitted) {
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
                            "已选择：${assessmentLabel(checkpoint.selectedAssessment)}",
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
                    OutlinedButton(
                        onClick = {
                            viewModel.leavePractice()
                            onFinished()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("结束本次学习") }
                }
            }
        }
    }
}

@Composable
private fun PromptPanel(
    mode: QuestionMode,
    word: Vocabulary,
    viewModel: VocabularyViewModel,
) {
    when (mode) {
        QuestionMode.INTRODUCE -> {
            Text(word.term, style = MaterialTheme.typography.headlineMedium)
            if (word.ipa.isNotBlank()) {
                Text(word.ipa, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PlayWordButton(viewModel)
        }
        QuestionMode.EN2ZH -> {
            Text(word.term, style = MaterialTheme.typography.headlineMedium)
            if (word.ipa.isNotBlank()) {
                Text(word.ipa, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PlayWordButton(viewModel)
        }
        QuestionMode.ZH2EN -> {
            Text(word.chineseGloss, style = MaterialTheme.typography.headlineSmall)
            Text(
                word.topic,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        QuestionMode.LISTENING -> {
            Text(
                questionPromptFor(mode, word),
                style = MaterialTheme.typography.headlineSmall,
            )
            PlayWordButton(viewModel)
        }
        QuestionMode.TRANSFER -> {
            Text(
                questionPromptFor(mode, word),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

@Composable
private fun AnswerPanel(
    mode: QuestionMode,
    word: Vocabulary,
    viewModel: VocabularyViewModel,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (mode) {
            QuestionMode.INTRODUCE -> {
                Text(word.chineseGloss, style = MaterialTheme.typography.titleMedium)
                if (word.collocations.isNotEmpty()) {
                    Text(
                        word.collocations.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(word.exampleSentenceEn, style = MaterialTheme.typography.bodyLarge)
                word.exampleSentenceZh?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    word.commonMistakes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlayExampleButton(viewModel, word.exampleAudioAssetId)
            }
            QuestionMode.EN2ZH -> {
                Text(word.chineseGloss, style = MaterialTheme.typography.titleMedium)
                Text(word.exampleSentenceEn, style = MaterialTheme.typography.bodyLarge)
                Text(
                    word.commonMistakes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlayExampleButton(viewModel, word.exampleAudioAssetId)
            }
            QuestionMode.ZH2EN -> {
                Text(word.term, style = MaterialTheme.typography.headlineMedium)
                if (word.ipa.isNotBlank()) {
                    Text(word.ipa, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(word.exampleSentenceEn, style = MaterialTheme.typography.bodyLarge)
                PlayWordButton(viewModel)
                PlayExampleButton(viewModel, word.exampleAudioAssetId)
            }
            QuestionMode.LISTENING -> {
                Text(word.term, style = MaterialTheme.typography.headlineMedium)
                Text(word.chineseGloss, style = MaterialTheme.typography.titleMedium)
            }
            QuestionMode.TRANSFER -> {
                Text(word.exampleSentenceEn, style = MaterialTheme.typography.bodyLarge)
                Text(word.chineseGloss, style = MaterialTheme.typography.titleMedium)
                PlayExampleButton(viewModel, word.exampleAudioAssetId)
            }
        }
    }
}

@Composable
private fun PlayWordButton(viewModel: VocabularyViewModel) {
    OutlinedButton(
        onClick = viewModel::playWordAudio,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Icon(Icons.Filled.VolumeUp, contentDescription = null)
        Text("朗读", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun PlayExampleButton(viewModel: VocabularyViewModel, assetId: String) {
    OutlinedButton(
        onClick = { viewModel.playExampleAudio(assetId) },
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Icon(Icons.Filled.VolumeUp, contentDescription = null)
        Text("朗读例句", modifier = Modifier.padding(start = 8.dp))
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

internal fun modeLabel(mode: QuestionMode): String = when (mode) {
    QuestionMode.INTRODUCE -> "新词学习"
    QuestionMode.EN2ZH -> "英译中"
    QuestionMode.ZH2EN -> "中译英"
    QuestionMode.LISTENING -> "听音辨词"
    QuestionMode.TRANSFER -> "迁移运用"
}
