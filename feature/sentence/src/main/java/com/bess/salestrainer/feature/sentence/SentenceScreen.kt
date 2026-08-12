package com.bess.salestrainer.feature.sentence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bess.salestrainer.core.model.SentenceExample
import com.bess.salestrainer.core.model.Speaker

/** 例句练习 tab: today queue overview + entry to the practice session. */
@Composable
fun SentenceScreen(
    onStartPractice: () -> Unit = {},
    viewModel: SentenceViewModel = hiltViewModel(),
) {
    val state by viewModel.listState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("例句练习", style = MaterialTheme.typography.headlineSmall)

        if (state.loading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Column
        }

        val queue = state.queue
        if (queue == null || (queue.newItems.isEmpty() && queue.dueReviews.isEmpty())) {
            Text(
                "今日没有可学习的例句。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("待复习 ${queue.dueReviews.size}", style = MaterialTheme.typography.titleMedium)
                    Text("新例句 ${queue.newItems.size}", style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onStartPractice) { Text("开始练习") }
            }
        }

        Text("今日队列", style = MaterialTheme.typography.titleSmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(queue.dueReviews + queue.newItems, key = { it.id }) { example ->
                SentenceRow(example)
            }
        }
    }
}

@Composable
private fun SentenceRow(example: SentenceExample) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(example.textEn, style = MaterialTheme.typography.bodyLarge)
            Text(example.textZh, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${if (example.speaker == Speaker.CUSTOMER) "客户" else "技术销售"} · ${example.scene}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
