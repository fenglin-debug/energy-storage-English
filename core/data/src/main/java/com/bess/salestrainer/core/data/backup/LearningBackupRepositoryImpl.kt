package com.bess.salestrainer.core.data.backup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.withTransaction
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.model.AppSupportInfo
import com.bess.salestrainer.core.model.BackupDocumentRef
import com.bess.salestrainer.core.model.LearningBackupError
import com.bess.salestrainer.core.model.LearningBackupInspection
import com.bess.salestrainer.core.model.LearningBackupPreview
import com.bess.salestrainer.core.model.LearningBackupResult
import com.bess.salestrainer.core.model.LearningBackupState
import com.bess.salestrainer.core.model.contract.LearningBackupRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LearningBackupRepositoryImpl(
    private val context: Context,
    private val db: BessDatabase,
    private val appVersionName: String,
    private val appVersionCode: Int,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : LearningBackupRepository {
    private val state = MutableStateFlow<LearningBackupState>(LearningBackupState.Idle)
    private val previews = ConcurrentHashMap<String, DecodedLearningBackup>()
    private val supportPrefs = context.getSharedPreferences(SUPPORT_PREFS, Context.MODE_PRIVATE)
    private val json = Json { prettyPrint = true }

    override fun observeState(): Flow<LearningBackupState> = state

    override suspend fun exportBackup(
        destination: BackupDocumentRef,
        password: String?,
    ): LearningBackupResult = withContext(Dispatchers.IO) {
        state.value = LearningBackupState.Writing
        try {
            val active = db.corpusDao().getActive()
            val payload = snapshot()
            val createdAt = nowEpochMs()
            val bytes = LearningBackupCodec.encode(
                payload = payload,
                metadata = LearningBackupMetadata(
                    createdAtEpochMs = createdAt,
                    appVersionName = appVersionName,
                    appVersionCode = appVersionCode,
                    databaseVersion = db.openHelper.readableDatabase.version,
                    corpusPackageKey = active?.packageKey,
                    corpusContentVersion = active?.contentVersion,
                ),
                password = password?.takeIf(String::isNotEmpty),
            )
            openOutput(destination.documentRef).use { it.write(bytes) }
            supportPrefs.edit().putLong(LAST_BACKUP_AT, createdAt).remove(LAST_ERROR).apply()
            LearningBackupResult.Success(payload.counts().total, createdAt)
        } catch (e: LearningBackupCodecException) {
            failure(e.error)
        } catch (_: Exception) {
            failure(LearningBackupError.DESTINATION_UNWRITABLE)
        } finally {
            state.value = LearningBackupState.Idle
        }
    }

    override suspend fun inspectBackup(
        source: BackupDocumentRef,
        password: String?,
    ): LearningBackupInspection = withContext(Dispatchers.IO) {
        state.value = LearningBackupState.Reading
        try {
            val bytes = openInput(source.documentRef).use {
                it.readLimited(LearningBackupCodec.MAX_CONTAINER_BYTES)
            }
            state.value = LearningBackupState.Validating
            val header = LearningBackupCodec.readHeader(bytes)
            if (header.encrypted && password.isNullOrEmpty()) {
                return@withContext LearningBackupInspection.PasswordRequired
            }
            val decoded = LearningBackupCodec.decode(bytes, password)
            val active = db.corpusDao().getActive()
            val previewId = UUID.randomUUID().toString()
            previews[previewId] = decoded
            supportPrefs.edit().remove(LAST_ERROR).apply()
            LearningBackupInspection.Ready(
                LearningBackupPreview(
                    previewId = previewId,
                    createdAtEpochMs = decoded.header.createdAtEpochMs,
                    sourceAppVersionName = decoded.header.appVersionName,
                    sourceAppVersionCode = decoded.header.appVersionCode,
                    sourceDatabaseVersion = decoded.header.databaseVersion,
                    sourceCorpusPackageKey = decoded.header.corpusPackageKey,
                    sourceCorpusContentVersion = decoded.header.corpusContentVersion,
                    currentCorpusMatches = decoded.header.corpusPackageKey == active?.packageKey,
                    encrypted = decoded.header.encrypted,
                    counts = decoded.header.counts.toModel(),
                ),
            )
        } catch (e: LearningBackupCodecException) {
            supportPrefs.edit().putString(LAST_ERROR, e.error.name).apply()
            if (e.error == LearningBackupError.PASSWORD_REQUIRED) {
                LearningBackupInspection.PasswordRequired
            } else {
                LearningBackupInspection.Failure(e.error)
            }
        } catch (_: Exception) {
            supportPrefs.edit().putString(LAST_ERROR, LearningBackupError.SOURCE_UNREADABLE.name).apply()
            LearningBackupInspection.Failure(LearningBackupError.SOURCE_UNREADABLE)
        } finally {
            state.value = LearningBackupState.Idle
        }
    }

    override suspend fun restoreBackup(previewId: String): LearningBackupResult =
        withContext(Dispatchers.IO) {
            val decoded = previews.remove(previewId)
                ?: return@withContext failure(LearningBackupError.VALIDATION_FAILED)
            state.value = LearningBackupState.Restoring
            try {
                val active = db.corpusDao().getActive()
                val corpusMatches = decoded.header.corpusPackageKey == active?.packageKey
                val restoredAt = nowEpochMs()
                val payload = if (corpusMatches) decoded.payload else decoded.payload.copy(
                    vocabularyCheckpoints = decoded.payload.vocabularyCheckpoints.map {
                        if (it.status == "IN_PROGRESS") {
                            it.copy(status = "EXPIRED", updatedAtEpochMs = restoredAt)
                        } else it
                    },
                    scenarioSessions = decoded.payload.scenarioSessions.map {
                        if (it.status == "IN_PROGRESS") {
                            it.copy(status = "ABORTED_CORPUS_CHANGED", updatedAtEpochMs = restoredAt)
                        } else it
                    },
                )
                replaceAll(payload)
                supportPrefs.edit().remove(LAST_ERROR).apply()
                LearningBackupResult.Success(payload.counts().total)
            } catch (_: Exception) {
                failure(LearningBackupError.TRANSACTION_FAILED)
            } finally {
                state.value = LearningBackupState.Idle
            }
        }

    override suspend fun discardPreview(previewId: String) {
        previews.remove(previewId)
    }

    override suspend fun getSupportInfo(): AppSupportInfo = withContext(Dispatchers.IO) {
        val active = db.corpusDao().getActive()
        val counts = snapshot().counts()
        AppSupportInfo(
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            databaseVersion = db.openHelper.readableDatabase.version,
            packageName = context.packageName,
            signingCertificateSha256 = signingCertificateSha256(),
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            activeCorpusContentVersion = active?.contentVersion,
            lastBackupAtEpochMs = supportPrefs.getLong(LAST_BACKUP_AT, 0L).takeIf { it > 0L },
            lastErrorCode = supportPrefs.getString(LAST_ERROR, null),
            counts = counts,
        )
    }

    override suspend fun exportDiagnostics(destination: BackupDocumentRef): LearningBackupResult =
        withContext(Dispatchers.IO) {
            state.value = LearningBackupState.Writing
            try {
                val info = getSupportInfo()
                val document = buildJsonObject {
                    put("generatedAtEpochMs", nowEpochMs())
                    put("appVersionName", info.appVersionName)
                    put("appVersionCode", info.appVersionCode)
                    put("databaseVersion", info.databaseVersion)
                    put("packageName", info.packageName)
                    put("signingCertificateSha256", info.signingCertificateSha256)
                    put("androidVersion", info.androidVersion)
                    put("deviceAbi", info.deviceAbi)
                    put("activeCorpusContentVersion", info.activeCorpusContentVersion ?: "")
                    put("lastBackupAtEpochMs", info.lastBackupAtEpochMs ?: 0L)
                    put("lastErrorCode", info.lastErrorCode ?: "")
                    put("learningRecordCount", info.counts.total)
                }
                openOutput(destination.documentRef).bufferedWriter(Charsets.UTF_8).use {
                    it.write(json.encodeToString(document))
                }
                LearningBackupResult.Success(info.counts.total)
            } catch (_: Exception) {
                failure(LearningBackupError.DESTINATION_UNWRITABLE)
            } finally {
                state.value = LearningBackupState.Idle
            }
        }

    private suspend fun snapshot(): LearningBackupPayload = db.withTransaction {
        val dao = db.learningStateDao()
        LearningBackupPayload(
            wordMemoryStates = dao.wordMemoryStates(),
            reviewLogs = dao.reviewLogs(),
            vocabularyCheckpoints = dao.vocabularyCheckpoints(),
            reviewActionKeys = dao.reviewActionKeys(),
            scenarioSessions = dao.scenarioSessions(),
            scenarioTurnProgress = dao.scenarioTurnProgress(),
            studyTasks = dao.studyTasks(),
            itemMemoryStates = dao.itemMemoryStates(),
            articleProgress = dao.articleProgress(),
        )
    }

    private suspend fun replaceAll(payload: LearningBackupPayload) = db.withTransaction {
        val dao = db.learningStateDao()
        dao.deleteReviewActionKeys()
        dao.deleteScenarioTurnProgress()
        dao.deleteVocabularyCheckpoints()
        dao.deleteScenarioSessions()
        dao.deleteReviewLogs()
        dao.deleteWordMemoryStates()
        dao.deleteStudyTasks()
        dao.deleteItemMemoryStates()
        dao.deleteArticleProgress()
        dao.insertWordMemoryStates(payload.wordMemoryStates)
        dao.insertReviewLogs(payload.reviewLogs)
        dao.insertVocabularyCheckpoints(payload.vocabularyCheckpoints)
        dao.insertReviewActionKeys(payload.reviewActionKeys)
        dao.insertScenarioSessions(payload.scenarioSessions)
        dao.insertScenarioTurnProgress(payload.scenarioTurnProgress)
        dao.insertStudyTasks(payload.studyTasks)
        dao.insertItemMemoryStates(payload.itemMemoryStates)
        dao.insertArticleProgress(payload.articleProgress)
    }

    private fun openInput(documentRef: String): InputStream {
        val uri = android.net.Uri.parse(documentRef)
        if (uri.scheme == "content") {
            context.contentResolver.openInputStream(uri)?.let { return it }
        }
        return FileInputStream(File(documentRef))
    }

    private fun openOutput(documentRef: String): OutputStream {
        val uri = android.net.Uri.parse(documentRef)
        if (uri.scheme == "content") {
            context.contentResolver.openOutputStream(uri, "wt")?.let { return it }
        }
        return FileOutputStream(File(documentRef), false)
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw LearningBackupCodecException(LearningBackupError.FILE_TOO_LARGE)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateSha256(): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            packageInfo.signatures?.firstOrNull()
        } ?: return "UNKNOWN"
        return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }

    private fun failure(error: LearningBackupError): LearningBackupResult.Failure {
        supportPrefs.edit().putString(LAST_ERROR, error.name).apply()
        return LearningBackupResult.Failure(error)
    }

    private companion object {
        const val SUPPORT_PREFS = "bess_support"
        const val LAST_BACKUP_AT = "last_backup_at"
        const val LAST_ERROR = "last_error"
    }
}
