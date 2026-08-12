package com.bess.salestrainer.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bess.salestrainer.core.model.CorpusImportResult
import com.bess.salestrainer.core.model.CorpusImportState
import com.bess.salestrainer.core.model.LearningBackupInspection
import com.bess.salestrainer.core.model.LearningBackupResult
import com.bess.salestrainer.core.model.LearningBackupState
import com.bess.salestrainer.core.model.PlaybackSpeed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val corpus by viewModel.activeCorpus.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val backupInspection by viewModel.backupInspection.collectAsStateWithLifecycle()
    val backupResult by viewModel.backupResult.collectAsStateWithLifecycle()
    val supportInfo by viewModel.supportInfo.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var enableAfterPermission by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showUnencryptedWarning by remember { mutableStateOf(false) }
    var protectWithPassword by remember { mutableStateOf(true) }
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirm by remember { mutableStateOf("") }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    var importPassword by remember { mutableStateOf("") }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationGranted = granted
        if (granted && enableAfterPermission) viewModel.setDailyReminderEnabled(true)
        enableAfterPermission = false
    }
    LaunchedEffect(notificationGranted, settings.dailyReminderEnabled) {
        if (!notificationGranted && settings.dailyReminderEnabled) {
            viewModel.setDailyReminderEnabled(false)
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.inspectPackage(it.toString()) } }

    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let { viewModel.exportLearningBackup(it.toString(), pendingExportPassword) }
        pendingExportPassword = null
        exportPassword = ""
        exportPasswordConfirm = ""
    }
    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.inspectLearningBackup(it.toString()) } }
    val createDiagnosticsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportDiagnostics(it.toString()) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("播放", style = MaterialTheme.typography.titleMedium)
                Text("语速", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    PlaybackSpeed.entries.forEachIndexed { index, speed ->
                        SegmentedButton(
                            selected = settings.playbackSpeed == speed,
                            onClick = { viewModel.setPlaybackSpeed(speed) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = PlaybackSpeed.entries.size,
                            ),
                        ) {
                            Text(
                                when (speed) {
                                    PlaybackSpeed.SLOW -> "慢速"
                                    PlaybackSpeed.NORMAL -> "标准"
                                    PlaybackSpeed.FAST -> "快速"
                                },
                            )
                        }
                    }
                }
                SettingsSwitchRow(
                    label = "自动播放客户音频",
                    checked = settings.autoPlayCustomerAudio,
                    onCheckedChange = viewModel::setAutoPlayCustomerAudio,
                )
                SettingsSwitchRow(
                    label = "自动播放词汇音频",
                    checked = settings.autoPlayVocabularyAudio,
                    onCheckedChange = viewModel::setAutoPlayVocabularyAudio,
                )
                SettingsSwitchRow(
                    label = "自动播放下一篇文章",
                    checked = settings.autoPlayNextArticle,
                    onCheckedChange = viewModel::setAutoPlayNextArticle,
                )
                Text(
                    "播放时会在通知栏和锁屏显示播放状态与控制按钮。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("学习记录备份", style = MaterialTheme.typography.titleMedium)
                Text(
                    "备份只包含学习进度和练习断点，不包含语料、音频、导入文章或提醒设置。恢复会完整覆盖本机学习记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                supportInfo?.lastBackupAtEpochMs?.let {
                    Text("最近备份：${formatEpoch(it)}", style = MaterialTheme.typography.bodySmall)
                }
                if (backupState != LearningBackupState.Idle) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        Text("正在处理本地文件……", style = MaterialTheme.typography.bodySmall)
                    }
                }
                backupResult?.let { result ->
                    Text(
                        when (result) {
                            is LearningBackupResult.Success -> "操作成功，共处理 ${result.affectedRecords} 条学习记录"
                            is LearningBackupResult.Failure -> "操作失败：${backupErrorText(result.error.name)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result is LearningBackupResult.Success) {
                            MaterialTheme.colorScheme.primary
                        } else MaterialTheme.colorScheme.error,
                    )
                }
                (backupInspection as? LearningBackupInspection.Failure)?.let {
                    Text(
                        "无法读取备份：${backupErrorText(it.error.name)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showExportDialog = true },
                        enabled = backupState == LearningBackupState.Idle,
                    ) { Text("导出学习记录") }
                    OutlinedButton(
                        onClick = {
                            openBackupLauncher.launch(
                                arrayOf("application/zip", "application/octet-stream", "*/*"),
                            )
                        },
                        enabled = backupState == LearningBackupState.Idle,
                    ) { Text("恢复学习记录") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("版本与支持", style = MaterialTheme.typography.titleMedium)
                supportInfo?.let { info ->
                    Text("版本 ${info.appVersionName} (${info.appVersionCode})")
                    Text(
                        "数据库 v${info.databaseVersion} · Android ${info.androidVersion} · ${info.deviceAbi}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "语料 ${info.activeCorpusContentVersion ?: "未激活"} · 学习记录 ${info.counts.total} 条",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "签名 SHA-256：${info.signingCertificateSha256.take(23)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    info.lastErrorCode?.let {
                        Text("最近错误：$it", style = MaterialTheme.typography.bodySmall)
                    }
                } ?: Text("正在读取版本信息……", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = {
                        createDiagnosticsLauncher.launch(
                            "BESS-diagnostics-${System.currentTimeMillis()}.json",
                        )
                    },
                    enabled = backupState == LearningBackupState.Idle,
                ) { Text("导出本地诊断报告") }
                Text(
                    "诊断报告不包含学习正文、文件路径或个人内容。更新 APK 时不要卸载旧版或清除数据，请直接覆盖安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("学习计划", style = MaterialTheme.typography.titleMedium)
                Text(
                    "每日新词目标：${settings.dailyNewWordTarget} 个",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = settings.dailyNewWordTarget.toFloat(),
                    onValueChange = { viewModel.setDailyNewWordTarget(it.toInt()) },
                    valueRange = 0f..50f,
                    steps = 9,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("通知与提醒", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (notificationGranted) "通知权限：已允许" else "通知权限：未允许",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notificationGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Button(onClick = {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) {
                        Text("允许通知")
                    }
                }
                SettingsSwitchRow(
                    label = "每日学习提醒",
                    checked = settings.dailyReminderEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            viewModel.setDailyReminderEnabled(false)
                        } else if (notificationGranted) {
                            viewModel.setDailyReminderEnabled(true)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            enableAfterPermission = true
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
                OutlinedButton(
                    onClick = { showTimePicker = true },
                    enabled = notificationGranted,
                ) {
                    Text(
                        "提醒时间 %02d:%02d".format(
                            settings.dailyReminderHour,
                            settings.dailyReminderMinute,
                        ),
                    )
                }
                Text(
                    "提醒仅在本机生成；受系统省电策略影响，送达时间可能有少量延迟。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("语料库", style = MaterialTheme.typography.titleMedium)
                corpus?.let {
                    Text("版本：${it.contentVersion}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "词汇 ${it.vocabularyCount} · 情景 ${it.scenarioCount} · " +
                            "对话 ${it.dialogueTurnCount} · 音频 ${it.audioAssetCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (it.isBundled) {
                        Text(
                            "内置语料",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } ?: Text("尚未激活语料库", style = MaterialTheme.typography.bodyMedium)

                when (importState) {
                    CorpusImportState.Idle, CorpusImportState.PreviewReady -> Unit
                    is CorpusImportState.Failed -> Text(
                        "导入失败：${(importState as CorpusImportState.Failed).error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        Text("正在处理语料包……", style = MaterialTheme.typography.bodySmall)
                    }
                }

                lastResult?.let { result ->
                    Text(
                        when (result) {
                            is CorpusImportResult.Success ->
                                "导入成功：新增 ${result.diff.added}，更新 ${result.diff.updated}，" +
                                    "移除 ${result.diff.removed}"
                            is CorpusImportResult.Failure -> "导入失败：${result.error}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result is CorpusImportResult.Success) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        openDocumentLauncher.launch(
                            arrayOf("application/zip", "application/octet-stream"),
                        )
                    }) { Text("导入语料包") }
                    OutlinedButton(onClick = viewModel::restoreBundled) { Text("恢复内置语料") }
                }
            }
        }

        Text(
            "本应用完全离线运行，不请求网络或录音权限；通知仅用于本地提醒和播放状态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = settings.dailyReminderHour,
            initialMinute = settings.dailyReminderMinute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("设置每日提醒时间") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDailyReminderTime(timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
        )
    }

    if (showExportDialog) {
        val passwordValid = !protectWithPassword ||
            (exportPassword.codePointCount(0, exportPassword.length) in 8..128 &&
                exportPassword == exportPasswordConfirm)
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出学习记录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsSwitchRow(
                        label = "使用密码保护",
                        checked = protectWithPassword,
                        onCheckedChange = { protectWithPassword = it },
                    )
                    if (protectWithPassword) {
                        OutlinedTextField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            label = { Text("密码（8–128 个字符）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        OutlinedTextField(
                            value = exportPasswordConfirm,
                            onValueChange = { exportPasswordConfirm = it },
                            label = { Text("再次输入密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            isError = exportPasswordConfirm.isNotEmpty() && exportPassword != exportPasswordConfirm,
                        )
                        Text(
                            "密码不会保存或上传，遗忘后无法恢复备份。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        if (protectWithPassword) {
                            pendingExportPassword = exportPassword
                            createBackupLauncher.launch(defaultBackupName())
                        } else {
                            showUnencryptedWarning = true
                        }
                    },
                    enabled = passwordValid,
                ) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            },
        )
    }

    if (showUnencryptedWarning) {
        AlertDialog(
            onDismissRequest = { showUnencryptedWarning = false },
            title = { Text("导出未加密备份？") },
            text = { Text("任何获得该文件的人都能读取你的学习历史。建议只保存在可信设备中。") },
            confirmButton = {
                TextButton(onClick = {
                    showUnencryptedWarning = false
                    pendingExportPassword = null
                    createBackupLauncher.launch(defaultBackupName())
                }) { Text("仍然导出") }
            },
            dismissButton = {
                TextButton(onClick = { showUnencryptedWarning = false }) { Text("返回") }
            },
        )
    }

    val backupPasswordRequired = backupInspection == LearningBackupInspection.PasswordRequired ||
        (backupInspection as? LearningBackupInspection.Failure)?.error?.name ==
        "WRONG_PASSWORD_OR_DAMAGED"
    if (backupPasswordRequired) {
        AlertDialog(
            onDismissRequest = {
                importPassword = ""
                viewModel.clearBackupInspection()
            },
            title = { Text("输入备份密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (backupInspection is LearningBackupInspection.Failure) {
                        Text(
                            "密码错误或备份已损坏，请重新输入密码。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.retryLearningBackupPassword(importPassword)
                        importPassword = ""
                    },
                    enabled = importPassword.isNotEmpty(),
                ) { Text("读取备份") }
            },
            dismissButton = {
                TextButton(onClick = {
                    importPassword = ""
                    viewModel.clearBackupInspection()
                }) { Text("取消") }
            },
        )
    }

    (backupInspection as? LearningBackupInspection.Ready)?.preview?.let { backup ->
        AlertDialog(
            onDismissRequest = viewModel::discardLearningBackupPreview,
            title = { Text("确认覆盖学习记录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("备份时间：${formatEpoch(backup.createdAtEpochMs)}")
                    Text("来源版本：${backup.sourceAppVersionName} (${backup.sourceAppVersionCode})")
                    Text("学习记录：${backup.counts.total} 条")
                    Text("词汇记忆 ${backup.counts.wordMemoryStates} · 复习日志 ${backup.counts.reviewLogs}")
                    Text("场景会话 ${backup.counts.scenarioSessions} · 文章进度 ${backup.counts.articleProgress}")
                    if (!backup.currentCorpusMatches) {
                        Text(
                            "备份语料与当前语料不同。历史记录会保留，进行中的练习将被安全终止。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "恢复后，本机现有学习记录将被完整替换，此操作无法撤销。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::restoreLearningBackup) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::discardLearningBackupPreview) { Text("取消") }
            },
        )
    }

    preview?.let { p ->
        AlertDialog(
            onDismissRequest = viewModel::discardPreview,
            title = { Text("确认导入语料包") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("版本：${p.contentVersion}")
                    Text("词汇 ${p.counts.vocabulary} · 情景 ${p.counts.scenarios} · 音频 ${p.counts.audioAssets}")
                    Text(
                        "变更：新增 ${p.diff.added} · 更新 ${p.diff.updated} · " +
                            "移除 ${p.diff.removed} · 不变 ${p.diff.unchanged}",
                    )
                    if (
                        p.sessionImpact.vocabularyCheckpointCount > 0 ||
                        p.sessionImpact.scenarioSessionCount > 0
                    ) {
                        Text(
                            "进行中的 ${p.sessionImpact.vocabularyCheckpointCount} 个词汇会话和 " +
                                "${p.sessionImpact.scenarioSessionCount} 个情景会话将被中止。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (p.errors.isNotEmpty()) {
                        Text(
                            "校验失败：${p.errors.first()}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmImport,
                    enabled = p.compatible && p.errors.isEmpty(),
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::discardPreview) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

private fun defaultBackupName(): String =
    "BESS-learning-backup-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(java.time.LocalDateTime.now())}.bessbackup"

private fun formatEpoch(epochMs: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun backupErrorText(code: String): String = when (code) {
    "SOURCE_UNREADABLE" -> "无法读取所选文件"
    "DESTINATION_UNWRITABLE" -> "无法写入所选位置"
    "PASSWORD_REQUIRED" -> "需要输入密码"
    "WRONG_PASSWORD_OR_DAMAGED" -> "密码错误或备份已损坏"
    "INVALID_FORMAT" -> "不是有效的 BESS 备份"
    "UNSUPPORTED_VERSION" -> "备份格式版本过新"
    "FILE_TOO_LARGE" -> "备份文件超过安全限制"
    "VALIDATION_FAILED" -> "备份内容校验失败"
    "TRANSACTION_FAILED" -> "恢复失败，原记录未修改"
    else -> code
}
