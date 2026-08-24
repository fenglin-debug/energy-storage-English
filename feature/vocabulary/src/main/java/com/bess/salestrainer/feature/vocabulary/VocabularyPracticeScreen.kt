package com.bess.salestrainer.feature.vocabulary

import androidx.compose.foundation.BorderStroke
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
            val mode = displayQuestionMode(checkpoint.questionMode)
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
                    } else {
                        val prefix = if (submitted) "改成：" else ""
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AssessmentButton(
                                label = "${prefix}陌生",
                                modifier = Modifier.weight(1f),
                                submitting = state.submitting,
                                selected = checkpoint.selectedAssessment ==
                                    VocabularySelfAssessment.UNFAMILIAR,
                            ) {
                                viewModel.submitAssessment(VocabularySelfAssessment.UNFAMILIAR)
                            }
                            AssessmentButton(
                                label = "${prefix}模糊",
                                modifier = Modifier.weight(1f),
                                submitting = state.submitting,
                                selected = checkpoint.selectedAssessment ==
                                    VocabularySelfAssessment.FUZZY,
                            ) {
                                viewModel.submitAssessment(VocabularySelfAssessment.FUZZY)
                            }
                            AssessmentButton(
                                label = "${prefix}掌握",
                                modifier = Modifier.weight(1f),
                                submitting = state.submitting,
                                selected = checkpoint.selectedAssessment ==
                                    VocabularySelfAssessment.MASTERED,
                            ) {
                                viewModel.submitAssessment(VocabularySelfAssessment.MASTERED)
                            }
                        }
                        if (state.submitting) {
                            Text("正在保存评价…", color = MaterialTheme.colorScheme.primary)
                        }
                        if (submitted) {
                            Button(
                                onClick = viewModel::advanceToNext,
                                enabled = !state.submitting,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text(if (view.hasNext) "下一条" else "完成本次学习")
                            }
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
        QuestionMode.INTRODUCE, QuestionMode.EN2ZH, QuestionMode.TRANSFER -> {
            WordAudioBlock(word.term, word.ipa, viewModel)
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (mode == QuestionMode.ZH2EN || mode == QuestionMode.LISTENING) {
            WordAudioBlock(word.term, word.ipa, viewModel)
        }
        if (mode != QuestionMode.ZH2EN) {
            Text(word.chineseGloss, style = MaterialTheme.typography.titleMedium)
        }
        if (mode == QuestionMode.INTRODUCE && word.collocations.isNotEmpty()) {
            Text(
                word.collocations.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ExampleAudioBlock(
            english = word.exampleSentenceEn,
            chinese = word.exampleSentenceZh,
            audioAssetId = word.exampleAudioAssetId,
            viewModel = viewModel,
        )
        word.examples.filter { it.textEn != word.exampleSentenceEn }.forEach { example ->
            ExampleAudioBlock(
                english = example.textEn,
                chinese = example.textZh,
                audioAssetId = example.audioAssetId,
                viewModel = viewModel,
            )
        }
        if (word.commonMistakes.isNotBlank()) {
            Text(
                word.commonMistakes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WordAudioBlock(term: String, ipa: String, viewModel: VocabularyViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(term, style = MaterialTheme.typography.headlineMedium)
        if (ipa.isNotBlank()) {
            Text(ipa, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PlayWordButton(viewModel)
    }
}

@Composable
private fun ExampleAudioBlock(
    english: String,
    chinese: String?,
    audioAssetId: String,
    viewModel: VocabularyViewModel,
) {
    if (english.isBlank()) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(english, style = MaterialTheme.typography.bodyLarge)
        chinese?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (audioAssetId.isNotBlank()) {
            PlayExampleButton(viewModel, audioAssetId)
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
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = !submitting,
        modifier = modifier.heightIn(min = 48.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) { Text(label) }
}

internal fun displayQuestionMode(mode: QuestionMode): QuestionMode =
    if (mode == QuestionMode.TRANSFER) QuestionMode.EN2ZH else mode

internal fun modeLabel(mode: QuestionMode): String = when (displayQuestionMode(mode)) {
    QuestionMode.INTRODUCE -> "新词学习"
    QuestionMode.ZH2EN -> "中译英"
    QuestionMode.LISTENING -> "听音辨词"
    QuestionMode.EN2ZH, QuestionMode.TRANSFER -> "英译中"
}
