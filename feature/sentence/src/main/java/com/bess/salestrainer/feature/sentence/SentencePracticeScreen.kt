package com.bess.salestrainer.feature.sentence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bess.salestrainer.core.model.QuestionMode
import com.bess.salestrainer.core.model.Rating

/**
 * 例句练习 flashcard: concealed answer never enters semantics until revealed
 * (a11y gate). Question mode cycles EN2ZH / ZH2EN / LISTENING by reps.
 */
@Composable
fun SentencePracticeScreen(
    onFinished: () -> Unit = {},
    viewModel: SentenceViewModel = hiltViewModel(),
) {
    val state by viewModel.practiceState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.startPractice() }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            state.loading -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }

            state.completed -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("本轮例句练习完成！", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onFinished, modifier = Modifier.padding(top = 16.dp)) {
                    Text("返回")
                }
            }

            state.current != null -> {
                val item = state.current!!
                LinearProgressIndicator(
                    progress = {
                        if (state.totalCount == 0) 0f
                        else state.currentIndex.toFloat() / state.totalCount
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "第 ${state.currentIndex + 1} / ${state.totalCount} 句 · " +
                        when (state.questionMode) {
                            QuestionMode.INTRODUCE -> "新句学习"
                            QuestionMode.EN2ZH -> "英译中"
                            QuestionMode.ZH2EN -> "中译英"
                            QuestionMode.LISTENING -> "听音辨句"
                            else -> "复习"
                        },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Prompt side depends on question mode; the opposite
                        // side stays concealed (and out of semantics) until reveal.
                        when (state.questionMode) {
                            QuestionMode.ZH2EN -> {
                                Text(item.textZh, style = MaterialTheme.typography.titleLarge)
                                if (state.answerRevealed) {
                                    Text(item.textEn, style = MaterialTheme.typography.bodyLarge)
                                } else {
                                    Text(
                                        "（想一想英文怎么说，然后显示答案）",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            QuestionMode.LISTENING -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { viewModel.playCurrentAudio() },
                                        modifier = Modifier.semantics {
                                            contentDescription = "播放例句音频"
                                        },
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                    }
                                    Text(
                                        if (state.playingAudio) "播放中…" else "点击播放，听音辨句",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                if (state.answerRevealed) {
                                    Text(item.textEn, style = MaterialTheme.typography.bodyLarge)
                                    Text(item.textZh, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            else -> { // INTRODUCE / EN2ZH
                                Text(item.textEn, style = MaterialTheme.typography.titleLarge)
                                if (state.answerRevealed) {
                                    Text(item.textZh, style = MaterialTheme.typography.bodyLarge)
                                } else {
                                    Text(
                                        "（想一想中文意思，然后显示答案）",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clearAndSetSemantics { },
                                    )
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IconButton(
                                onClick = { viewModel.playCurrentAudio() },
                                modifier = Modifier.semantics { contentDescription = "播放音频" },
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            }
                            Text(
                                "${item.scene} · ${item.id}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (!state.answerRevealed) {
                    Button(
                        onClick = { viewModel.reveal() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("显示答案") }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.rate(Rating.AGAIN) },
                            modifier = Modifier.weight(1f),
                        ) { Text("不熟") }
                        OutlinedButton(
                            onClick = { viewModel.rate(Rating.HARD) },
                            modifier = Modifier.weight(1f),
                        ) { Text("较难") }
                        OutlinedButton(
                            onClick = { viewModel.rate(Rating.GOOD) },
                            modifier = Modifier.weight(1f),
                        ) { Text("掌握") }
                        OutlinedButton(
                            onClick = { viewModel.rate(Rating.EASY) },
                            modifier = Modifier.weight(1f),
                        ) { Text("很熟") }
                    }
                }
            }

            else -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.error ?: "暂无例句",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onFinished, modifier = Modifier.padding(top = 16.dp)) {
                    Text("返回")
                }
            }
        }
    }
}
