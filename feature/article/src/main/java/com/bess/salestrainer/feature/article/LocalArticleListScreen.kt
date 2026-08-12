package com.bess.salestrainer.feature.article

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bess.salestrainer.core.model.Article
import com.bess.salestrainer.core.model.ArticleSource
import java.util.Locale

@Composable
fun ArticleListScreen(
    onOpenArticle: (String) -> Unit = {},
    viewModel: ArticleViewModel = hiltViewModel(),
) {
    val state by viewModel.listState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showImportMenu by remember { mutableStateOf(false) }
    var showFormatDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Article?>(null) }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persistDirectImportPermission(context, it)
            viewModel.beginLocalAudioImport(it.toString(), directImportDisplayName(context, it))
        }
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persistDirectImportPermission(context, it)
            viewModel.selectLocalSubtitle(it.toString(), directImportDisplayName(context, it))
        }
    }
    val packPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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
            Text("文章 · 磨耳朵", style = MaterialTheme.typography.headlineSmall)
            Column(horizontalAlignment = Alignment.End) {
                IconButton(
                    onClick = { showImportMenu = true },
                    enabled = !state.importing,
                    modifier = Modifier.semantics { contentDescription = "导入文章" },
                ) { Icon(Icons.Filled.Add, contentDescription = null) }
                DropdownMenu(expanded = showImportMenu, onDismissRequest = { showImportMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("从音频导入") },
                        onClick = {
                            showImportMenu = false
                            audioPicker.launch(arrayOf("audio/mp4", "audio/mpeg", "audio/wav", "audio/x-wav"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导入 .bessarticle 文章包") },
                        onClick = {
                            showImportMenu = false
                            packPicker.launch(arrayOf("*/*"))
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("查看格式说明") },
                        onClick = {
                            showImportMenu = false
                            showFormatDialog = true
                        },
                    )
                }
            }
        }

        Card(onClick = { onOpenArticle(RANDOM_ARTICLE_ROUTE_ID) }, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("随机练习", style = MaterialTheme.typography.titleLarge)
                Text("随机播放储能文章，整袋播放完前不会重复。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        state.importMessage?.let { DirectImportStatusCard(it, viewModel::clearImportMessage) }
        state.deleteMessage?.let { DirectImportStatusCard(it, viewModel::clearDeleteMessage) }

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
                Text("文章库为空。点击右上角 +，可直接导入音频。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = { audioPicker.launch(arrayOf("audio/*")) },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("从音频导入") }
            }

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.articles, key = { it.id }) { article ->
                    DirectImportArticleRow(
                        article = article,
                        deleting = state.deletingArticleId == article.id,
                        onClick = { onOpenArticle(article.id) },
                        onDelete = { pendingDelete = article },
                    )
                }
            }
        }
    }

    state.localImportDraft?.let { draft ->
        LocalArticleImportDialog(
            draft = draft,
            importing = state.importing,
            onDismiss = viewModel::dismissLocalImport,
            onTitleChange = viewModel::updateLocalImportTitle,
            onTopicChange = viewModel::updateLocalImportTopic,
            onRandomChange = viewModel::updateLocalImportRandom,
            onChooseSubtitle = {
                subtitlePicker.launch(arrayOf("application/x-subrip", "text/plain", "application/octet-stream"))
            },
            onRemoveSubtitle = viewModel::removeLocalSubtitle,
            onConfirm = viewModel::submitLocalImport,
        )
    }

    if (showFormatDialog) {
        AlertDialog(
            onDismissRequest = { showFormatDialog = false },
            confirmButton = { TextButton(onClick = { showFormatDialog = false }) { Text("关闭") } },
            title = { Text("文章导入格式") },
            text = {
                Text(
                    "普通导入：选择 M4A、MP3 或 WAV 音频，可再添加 SRT/LRC 字幕。" +
                        "音频上限 100MB、30 分钟；字幕上限 2MB、10,000 条，支持纯英文或中英双语。\n\n" +
                        "高级导入：.bessarticle 用于批量发布和内置语料，原有格式与校验规则保持不变。",
                )
            },
        )
    }

    pendingDelete?.let { article ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这篇导入文章？") },
            text = { Text("“${article.titleZh.ifBlank { article.title }}”及其本地音频和收听进度将被删除，无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteImportedArticle(article.id)
                        pendingDelete = null
                    },
                    enabled = state.deletingArticleId == null,
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun LocalArticleImportDialog(
    draft: LocalArticleImportDraft,
    importing: Boolean,
    onDismiss: () -> Unit,
    onTitleChange: (String) -> Unit,
    onTopicChange: (String) -> Unit,
    onRandomChange: (Boolean) -> Unit,
    onChooseSubtitle: () -> Unit,
    onRemoveSubtitle: () -> Unit,
    onConfirm: () -> Unit,
) {
    val ready = draft.audioInfo != null && !draft.inspectingAudio && !draft.inspectingSubtitle &&
        (draft.subtitleSource == null || draft.subtitleInfo != null) && draft.title.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从音频导入文章") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(draft.audioDisplayName, style = MaterialTheme.typography.titleSmall)
                when {
                    draft.inspectingAudio -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在校验音频…")
                    }
                    draft.audioInfo != null -> Text(
                        "${draft.audioInfo.extension.uppercase()} · ${directImportFileSize(draft.audioInfo.sizeBytes)} · " +
                            directImportDuration(draft.audioInfo.durationMs),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = onTitleChange,
                    label = { Text("标题（必填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.topic,
                    onValueChange = onTopicChange,
                    label = { Text("主题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                Text("字幕（可选）", style = MaterialTheme.typography.titleSmall)
                if (draft.subtitleDisplayName == null) {
                    OutlinedButton(onClick = onChooseSubtitle, enabled = draft.audioInfo != null) {
                        Text("添加 SRT / LRC")
                    }
                    Text("不添加字幕时，将保存为纯听练习。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(draft.subtitleDisplayName)
                    if (draft.inspectingSubtitle) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else draft.subtitleInfo?.let { Text("已识别 ${it.cueCount} 条字幕") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onChooseSubtitle) { Text("替换") }
                        TextButton(onClick = onRemoveSubtitle) { Text("移除") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onRandomChange(!draft.includeInRandomPractice) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text("加入随机练习")
                        Text("关闭后仍可从文章列表手动播放", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = draft.includeInRandomPractice, onCheckedChange = onRandomChange)
                }
                draft.validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (importing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在复制音频并保存文章，请勿重复提交…")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = ready && !importing) {
                Text(if (importing) "导入中" else "确认导入")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !importing) { Text("取消") } },
    )
}

@Composable
private fun DirectImportStatusCard(message: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun DirectImportArticleRow(
    article: Article,
    deleting: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f).clickable(enabled = !deleting, onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(article.titleZh.ifBlank { article.title }, style = MaterialTheme.typography.titleMedium)
                if (article.titleZh.isNotBlank() && article.titleZh != article.title) Text(article.title)
                val progress = article.progress
                Text(
                    buildString {
                        append("${directImportDuration(article.durationMs)} · ${article.topic}")
                        if (article.source == ArticleSource.IMPORTED) append(" · 导入文章")
                        if (progress?.completed == true) append(" · 已听完 ${progress.listenCount} 遍")
                        else if (progress != null && progress.lastPositionMs > 0 && article.durationMs > 0) {
                            append(" · 已听 ${100 * progress.lastPositionMs / article.durationMs}%")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (article.source == ArticleSource.IMPORTED) {
                IconButton(onClick = onDelete, enabled = !deleting, modifier = Modifier.size(48.dp)) {
                    if (deleting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "删除文章 ${article.titleZh.ifBlank { article.title }}",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun directImportDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0).orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
    }
    return uri.lastPathSegment.orEmpty().substringAfterLast('/').ifBlank { "本地文件" }
}

private fun persistDirectImportPermission(context: Context, uri: Uri) {
    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

private fun directImportFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
}

private fun directImportDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
