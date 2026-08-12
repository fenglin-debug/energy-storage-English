package com.bess.salestrainer.feature.article

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bess.salestrainer.core.model.Article
import com.bess.salestrainer.core.model.ArticleSource

/** 文章朗读（磨耳朵）tab: library + import entry + format requirements. */
@Composable
private fun LegacyArticleListScreen(
    onOpenArticle: (String) -> Unit = {},
    viewModel: ArticleViewModel = hiltViewModel(),
) {
    val state by viewModel.listState.collectAsStateWithLifecycle()
    var showFormatDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Article?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.importPack(it.toString()) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("文章朗读 · 磨耳朵", style = MaterialTheme.typography.headlineSmall)
            Row {
                IconButton(
                    onClick = { showFormatDialog = true },
                    modifier = Modifier.semantics { contentDescription = "导入文件格式要求" },
                ) { Icon(Icons.Filled.Info, contentDescription = null) }
                IconButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.semantics { contentDescription = "导入文章包" },
                ) { Icon(Icons.Filled.Add, contentDescription = null) }
            }
        }

        Card(
            onClick = { onOpenArticle(RANDOM_ARTICLE_ROUTE_ID) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("随机练习", style = MaterialTheme.typography.titleLarge)
                Text(
                    "随机播放储能文章磨耳朵，整袋播放前不重复。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.importMessage?.let { msg ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearImportMessage() }) { Text("知道了") }
                }
            }
        }

        state.deleteMessage?.let { msg ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearDeleteMessage) { Text("知道了") }
                }
            }
        }

        when {
            state.loading -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }

            state.articles.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "文章库为空。点击右上角 + 导入 .bessarticle 文章包。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { showFormatDialog = true },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("查看导入格式要求") }
            }

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.articles, key = { it.id }) { article ->
                    ArticleRow(
                        article = article,
                        deleting = state.deletingArticleId == article.id,
                        onClick = { onOpenArticle(article.id) },
                        onDelete = { pendingDelete = article },
                    )
                }
            }
        }
    }

    if (showFormatDialog) {
        AlertDialog(
            onDismissRequest = { showFormatDialog = false },
            confirmButton = {
                TextButton(onClick = { showFormatDialog = false }) { Text("关闭") }
            },
            title = { Text("文章包导入格式要求") },
            text = {
                Text(
                    """
                    文件格式：.bessarticle（ZIP 压缩包）
                    
                    包内结构：
                    • manifest.json — 文章清单（packageId 必须为 "bess-article"，schemaVersion=1）
                    • checksums.sha256 — 所有文件的 SHA-256 校验
                    • audio/<文章ID>.m4a — 每篇文章的朗读音频（AAC-LC，单声道）
                    
                    manifest.json 中每篇文章需包含：
                    id、title（英文标题）、titleZh（中文标题）、topic（主题分类）、
                    paragraphs（段落数组，每段 textEn + textZh 中英对照）、
                    audioFile（必须为 audio/<文章ID>.m4a）、
                    durationMs（音频时长，单篇不得超过 6 分钟）、contentHash。
                    
                    限制：
                    • 单个文章包不超过 50MB
                    • 文章 ID 在包内唯一；与库中已有文章同 ID 时会覆盖更新
                    • 导入为增量更新，不会删除已有文章
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
    }


    pendingDelete?.let { article ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这篇导入文章？") },
            text = {
                Text(
                    "“${article.titleZh.ifBlank { article.title }}”将从文章库中移除，" +
                        "其本地音频和收听进度也会一并删除。此操作无法撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteImportedArticle(article.id)
                        pendingDelete = null
                    },
                    enabled = state.deletingArticleId == null,
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ArticleRow(
    article: Article,
    deleting: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !deleting, onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    article.titleZh.ifBlank { article.title },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(article.title, style = MaterialTheme.typography.bodyMedium)
                val minutes = article.durationMs / 60000.0
                val progress = article.progress
                Text(
                    buildString {
                        append("%.1f 分钟 · %s".format(minutes, article.topic))
                        if (article.source == ArticleSource.IMPORTED) append(" · 导入文章")
                        if (progress != null && progress.completed) {
                            append(" · 已听完 ${progress.listenCount} 遍")
                        } else if (progress != null && progress.lastPositionMs > 0) {
                            append(
                                " · 已听 %.0f%%".format(
                                    100.0 * progress.lastPositionMs / article.durationMs,
                                ),
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (article.source == ArticleSource.IMPORTED) {
                IconButton(
                    onClick = onDelete,
                    enabled = !deleting,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "删除文章：${article.titleZh.ifBlank { article.title }}",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
