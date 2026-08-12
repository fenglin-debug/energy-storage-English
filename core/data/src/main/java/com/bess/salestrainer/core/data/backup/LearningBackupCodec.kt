package com.bess.salestrainer.core.data.backup

import com.bess.salestrainer.core.database.entity.ArticleProgressEntity
import com.bess.salestrainer.core.database.entity.ItemMemoryStateEntity
import com.bess.salestrainer.core.database.entity.ReviewActionKeyEntity
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.ScenarioTurnProgressEntity
import com.bess.salestrainer.core.database.entity.StudyTaskEntity
import com.bess.salestrainer.core.database.entity.VocabularySessionCheckpointEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import com.bess.salestrainer.core.model.LearningBackupCounts
import com.bess.salestrainer.core.model.LearningBackupError
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class LearningBackupPayload(
    val wordMemoryStates: List<WordMemoryStateEntity> = emptyList(),
    val reviewLogs: List<ReviewLogEntity> = emptyList(),
    val vocabularyCheckpoints: List<VocabularySessionCheckpointEntity> = emptyList(),
    val reviewActionKeys: List<ReviewActionKeyEntity> = emptyList(),
    val scenarioSessions: List<ScenarioSessionEntity> = emptyList(),
    val scenarioTurnProgress: List<ScenarioTurnProgressEntity> = emptyList(),
    val studyTasks: List<StudyTaskEntity> = emptyList(),
    val itemMemoryStates: List<ItemMemoryStateEntity> = emptyList(),
    val articleProgress: List<ArticleProgressEntity> = emptyList(),
) {
    fun counts() = LearningBackupCounts(
        wordMemoryStates = wordMemoryStates.size,
        reviewLogs = reviewLogs.size,
        vocabularyCheckpoints = vocabularyCheckpoints.size,
        reviewActionKeys = reviewActionKeys.size,
        scenarioSessions = scenarioSessions.size,
        scenarioTurnProgress = scenarioTurnProgress.size,
        studyTasks = studyTasks.size,
        itemMemoryStates = itemMemoryStates.size,
        articleProgress = articleProgress.size,
    )

    fun validate() {
        require(counts().total <= MAX_RECORDS) { "too many records" }
        fun validId(value: String) = value.isNotBlank() && value.length <= MAX_ID_LENGTH
        fun validFsrs(value: String) = value in VALID_FSRS_STATES
        fun validSchedule(difficulty: Double, stability: Double, reps: Int, lapses: Int) =
            difficulty.isFinite() && difficulty in 0.0..10.0 &&
                stability.isFinite() && stability in 0.0..MAX_STABILITY &&
                reps in 0..MAX_COUNTER && lapses in 0..MAX_COUNTER
        fun <T, K> unique(values: List<T>, key: (T) -> K) = values.map(key).distinct().size == values.size
        fun validIdQueue(value: String): List<String>? = runCatching {
            val array = Json.parseToJsonElement(value) as? JsonArray ?: return@runCatching null
            val ids = array.map { element ->
                val primitive = element as? JsonPrimitive ?: return@runCatching null
                if (!primitive.isString || !validId(primitive.content)) return@runCatching null
                primitive.content
            }
            ids.takeIf { it.size <= MAX_QUEUE_ITEMS && it.distinct().size == it.size }
        }.getOrNull()

        require(wordMemoryStates.all {
            validId(it.wordId) && validFsrs(it.fsrsState) &&
                validSchedule(it.difficulty, it.stability, it.reps, it.lapses) &&
                it.dueAtEpochMs >= 0L && (it.lastReviewAtEpochMs ?: 0L) >= 0L &&
                it.updatedAtEpochMs >= 0L &&
                (it.lastQuestionMode == null || it.lastQuestionMode in VALID_QUESTION_MODES) &&
                (it.learnedContentHash?.length ?: 0) <= 128
        } && unique(wordMemoryStates) { it.wordId })
        require(reviewLogs.all {
            validId(it.id) && validId(it.wordId) && it.rating in VALID_RATINGS &&
                it.questionMode in VALID_QUESTION_MODES && it.stateBefore in VALID_FSRS_STATES &&
                it.stateAfter in VALID_FSRS_STATES && it.reviewedAtEpochMs >= 0L &&
                (it.responseTimeMs ?: 0L) >= 0L && it.scheduledDays >= 0L && it.elapsedDays >= 0L
        } && unique(reviewLogs) { it.id })
        require(vocabularyCheckpoints.all {
            validId(it.sessionId) && it.status in VALID_VOCAB_STATUSES &&
                it.corpusVersion.length <= 128 && it.queueWordIdsJson.length <= MAX_QUEUE_JSON_LENGTH &&
                validIdQueue(it.queueWordIdsJson)?.let { ids -> it.currentIndex in 0..ids.size } == true &&
                it.questionMode in VALID_QUESTION_MODES &&
                (it.selectedAssessment == null || it.selectedAssessment in VALID_VOCAB_ASSESSMENTS) &&
                it.startedAtEpochMs >= 0L && it.updatedAtEpochMs >= 0L
        } && unique(vocabularyCheckpoints) { it.sessionId })
        require(reviewActionKeys.all {
            validId(it.actionKey) && validId(it.sessionId) &&
                it.currentIndex >= 0 && it.createdAtEpochMs >= 0L
        } && unique(reviewActionKeys) { it.actionKey })
        require(scenarioSessions.all {
            validId(it.id) && validId(it.scenarioId) && it.scenarioContentHash.length <= 128 &&
                it.status in VALID_SCENARIO_STATUSES && (it.currentPairId?.let(::validId) != false) &&
                it.currentPairIndex >= 0 && it.pairCount >= 0 && it.currentPairIndex <= it.pairCount &&
                it.practiceMode in VALID_SCENARIO_MODES && it.queuePairIdsJson.length <= MAX_QUEUE_JSON_LENGTH &&
                validIdQueue(it.queuePairIdsJson)?.let { ids -> ids.isEmpty() || it.currentPairIndex in 0..ids.size } == true &&
                it.startedAtEpochMs >= 0L && (it.completedAtEpochMs ?: 0L) >= 0L &&
                it.updatedAtEpochMs >= 0L
        } && unique(scenarioSessions) { it.id })
        require(scenarioTurnProgress.all {
            validId(it.sessionId) && validId(it.pairId) &&
                (it.selfRating == null || it.selfRating in VALID_SELF_RATINGS) && it.updatedAtEpochMs >= 0L
        } && unique(scenarioTurnProgress) { it.sessionId to it.pairId })
        require(studyTasks.all {
            it.dateEpochDay in -1_000_000L..1_000_000L && it.newWordTarget >= 0 &&
                it.newWordDone >= 0 && it.reviewTarget >= 0 && it.reviewDone >= 0 &&
                (it.recommendedScenarioId?.let(::validId) != false) && it.studySeconds >= 0L &&
                it.updatedAtEpochMs >= 0L
        } && unique(studyTasks) { it.dateEpochDay })
        require(itemMemoryStates.all {
            validId(it.itemId) && it.itemType in VALID_ITEM_TYPES && validFsrs(it.fsrsState) &&
                validSchedule(it.difficulty, it.stability, it.reps, it.lapses) &&
                it.dueAtEpochMs >= 0L && (it.lastReviewAtEpochMs ?: 0L) >= 0L &&
                (it.learnedContentHash?.length ?: 0) <= 128 && it.updatedAtEpochMs >= 0L
        } && unique(itemMemoryStates) { it.itemId to it.itemType })
        require(articleProgress.all {
            validId(it.articleId) && it.lastPositionMs >= 0L && it.listenCount >= 0 &&
                (it.completedAtEpochMs ?: 0L) >= 0L && it.updatedAtEpochMs >= 0L
        } && unique(articleProgress) { it.articleId })
    }
}

@Serializable
internal data class LearningBackupHeader(
    val formatVersion: Int = FORMAT_VERSION,
    val createdAtEpochMs: Long,
    val appVersionName: String,
    val appVersionCode: Int,
    val databaseVersion: Int,
    val corpusPackageKey: String? = null,
    val corpusContentVersion: String? = null,
    val encrypted: Boolean,
    val algorithm: String,
    val kdfIterations: Int? = null,
    val saltBase64: String? = null,
    val nonceBase64: String? = null,
    val payloadSha256: String,
    val counts: LearningBackupCountsDto,
)

@Serializable
internal data class LearningBackupCountsDto(
    val wordMemoryStates: Int,
    val reviewLogs: Int,
    val vocabularyCheckpoints: Int,
    val reviewActionKeys: Int,
    val scenarioSessions: Int,
    val scenarioTurnProgress: Int,
    val studyTasks: Int,
    val itemMemoryStates: Int,
    val articleProgress: Int,
) {
    fun toModel() = LearningBackupCounts(
        wordMemoryStates,
        reviewLogs,
        vocabularyCheckpoints,
        reviewActionKeys,
        scenarioSessions,
        scenarioTurnProgress,
        studyTasks,
        itemMemoryStates,
        articleProgress,
    )

    companion object {
        fun from(value: LearningBackupCounts) = LearningBackupCountsDto(
            value.wordMemoryStates,
            value.reviewLogs,
            value.vocabularyCheckpoints,
            value.reviewActionKeys,
            value.scenarioSessions,
            value.scenarioTurnProgress,
            value.studyTasks,
            value.itemMemoryStates,
            value.articleProgress,
        )
    }
}

internal data class DecodedLearningBackup(
    val header: LearningBackupHeader,
    val payload: LearningBackupPayload,
)

internal data class LearningBackupMetadata(
    val createdAtEpochMs: Long,
    val appVersionName: String,
    val appVersionCode: Int,
    val databaseVersion: Int,
    val corpusPackageKey: String?,
    val corpusContentVersion: String?,
)

internal class LearningBackupCodecException(val error: LearningBackupError) : Exception()

internal object LearningBackupCodec {
    private const val HEADER_ENTRY = "manifest.json"
    private const val PAYLOAD_ENTRY = "learning-data.bin"
    private const val NONE = "NONE_SHA256"
    private const val ENCRYPTED = "AES_256_GCM_PBKDF2_HMAC_SHA256"
    private const val DEFAULT_ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 2_000_000
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val MAX_HEADER_BYTES = 64 * 1024
    const val MAX_CONTAINER_BYTES = 40 * 1024 * 1024
    private const val MAX_PAYLOAD_BYTES = 32 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
    }

    fun encode(
        payload: LearningBackupPayload,
        metadata: LearningBackupMetadata,
        password: String?,
        secureRandom: SecureRandom = SecureRandom(),
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        payload.validate()
        val plainBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        if (plainBytes.size > MAX_PAYLOAD_BYTES) {
            throw LearningBackupCodecException(LearningBackupError.FILE_TOO_LARGE)
        }
        val counts = LearningBackupCountsDto.from(payload.counts())
        val encrypted = !password.isNullOrEmpty()
        val headerWithoutHash: LearningBackupHeader
        val storedPayload: ByteArray
        if (encrypted) {
            if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
                throw LearningBackupCodecException(LearningBackupError.VALIDATION_FAILED)
            }
            validatePassword(password)
            val salt = ByteArray(SALT_SIZE).also(secureRandom::nextBytes)
            val nonce = ByteArray(NONCE_SIZE).also(secureRandom::nextBytes)
            headerWithoutHash = LearningBackupHeader(
                createdAtEpochMs = metadata.createdAtEpochMs,
                appVersionName = metadata.appVersionName,
                appVersionCode = metadata.appVersionCode,
                databaseVersion = metadata.databaseVersion,
                corpusPackageKey = metadata.corpusPackageKey,
                corpusContentVersion = metadata.corpusContentVersion,
                encrypted = true,
                algorithm = ENCRYPTED,
                kdfIterations = iterations,
                saltBase64 = Base64.getEncoder().encodeToString(salt),
                nonceBase64 = Base64.getEncoder().encodeToString(nonce),
                payloadSha256 = "",
                counts = counts,
            )
            storedPayload = encrypt(
                plaintext = plainBytes,
                password = password,
                salt = salt,
                nonce = nonce,
                iterations = iterations,
                aad = canonicalAad(headerWithoutHash),
            )
        } else {
            headerWithoutHash = LearningBackupHeader(
                createdAtEpochMs = metadata.createdAtEpochMs,
                appVersionName = metadata.appVersionName,
                appVersionCode = metadata.appVersionCode,
                databaseVersion = metadata.databaseVersion,
                corpusPackageKey = metadata.corpusPackageKey,
                corpusContentVersion = metadata.corpusContentVersion,
                encrypted = false,
                algorithm = NONE,
                payloadSha256 = "",
                counts = counts,
            )
            storedPayload = plainBytes
        }
        val header = headerWithoutHash.copy(payloadSha256 = sha256(storedPayload))
        val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(HEADER_ENTRY))
                zip.write(headerBytes)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(PAYLOAD_ENTRY))
                zip.write(storedPayload)
                zip.closeEntry()
            }
            output.toByteArray()
        }.also {
            if (it.size > MAX_CONTAINER_BYTES) {
                throw LearningBackupCodecException(LearningBackupError.FILE_TOO_LARGE)
            }
        }
    }

    fun readHeader(container: ByteArray): LearningBackupHeader = readEntries(container).first.also {
        validateHeader(it)
    }

    fun decode(container: ByteArray, password: String?): DecodedLearningBackup {
        val (header, storedPayload) = readEntries(container)
        validateHeader(header)
        if (!MessageDigest.isEqual(
                header.payloadSha256.hexToBytes(),
                sha256Bytes(storedPayload),
            )
        ) {
            throw LearningBackupCodecException(LearningBackupError.WRONG_PASSWORD_OR_DAMAGED)
        }
        val plain = if (header.encrypted) {
            if (password.isNullOrEmpty()) {
                throw LearningBackupCodecException(LearningBackupError.PASSWORD_REQUIRED)
            }
            try {
                decrypt(
                    ciphertext = storedPayload,
                    password = password,
                    salt = decodeBase64Exact(header.saltBase64, SALT_SIZE),
                    nonce = decodeBase64Exact(header.nonceBase64, NONCE_SIZE),
                    iterations = header.kdfIterations!!,
                    aad = canonicalAad(header.copy(payloadSha256 = "")),
                )
            } catch (_: Exception) {
                throw LearningBackupCodecException(LearningBackupError.WRONG_PASSWORD_OR_DAMAGED)
            }
        } else {
            storedPayload
        }
        if (plain.size > MAX_PAYLOAD_BYTES) {
            throw LearningBackupCodecException(LearningBackupError.FILE_TOO_LARGE)
        }
        val payload = try {
            validateJsonDepth(plain, 32)
            json.decodeFromString<LearningBackupPayload>(plain.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
        }
        try {
            payload.validate()
            if (payload.counts() != header.counts.toModel()) error("count mismatch")
        } catch (_: Exception) {
            throw LearningBackupCodecException(LearningBackupError.VALIDATION_FAILED)
        }
        return DecodedLearningBackup(header, payload)
    }

    private fun readEntries(container: ByteArray): Pair<LearningBackupHeader, ByteArray> {
        if (container.size > MAX_CONTAINER_BYTES) {
            throw LearningBackupCodecException(LearningBackupError.FILE_TOO_LARGE)
        }
        var headerBytes: ByteArray? = null
        var payloadBytes: ByteArray? = null
        var entries = 0
        try {
            ZipInputStream(ByteArrayInputStream(container)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entry.isDirectory || entry.name.contains('/') || entry.name.contains('\\')) {
                        throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
                    }
                    when (entry.name) {
                        HEADER_ENTRY -> {
                            if (headerBytes != null) throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
                            headerBytes = zip.readLimited(MAX_HEADER_BYTES)
                        }
                        PAYLOAD_ENTRY -> {
                            if (payloadBytes != null) throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
                            payloadBytes = zip.readLimited(MAX_PAYLOAD_BYTES + 1024)
                        }
                        else -> throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: LearningBackupCodecException) {
            throw e
        } catch (_: Exception) {
            throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
        }
        if (entries != 2 || headerBytes == null || payloadBytes == null) {
            throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
        }
        val finalHeaderBytes = headerBytes
        val finalPayloadBytes = payloadBytes
        val header = try {
            json.decodeFromString<LearningBackupHeader>(finalHeaderBytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
        }
        return header to finalPayloadBytes
    }

    private fun validateHeader(header: LearningBackupHeader) {
        if (header.formatVersion != FORMAT_VERSION) {
            throw LearningBackupCodecException(LearningBackupError.UNSUPPORTED_VERSION)
        }
        if (header.appVersionName.length > 64 ||
            header.createdAtEpochMs < 0L || header.appVersionCode < 0 || header.databaseVersion < 1 ||
            (header.corpusPackageKey?.length ?: 0) > 512 ||
            (header.corpusContentVersion?.length ?: 0) > 128 ||
            header.counts.values().any { it < 0 } ||
            header.counts.values().sumOf(Int::toLong) > MAX_RECORDS
        ) {
            throw LearningBackupCodecException(LearningBackupError.VALIDATION_FAILED)
        }
        if (header.encrypted) {
            if (header.algorithm != ENCRYPTED || header.kdfIterations !in MIN_ITERATIONS..MAX_ITERATIONS ||
                header.saltBase64.isNullOrBlank() || header.nonceBase64.isNullOrBlank()
            ) {
                throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
            }
            decodeBase64Exact(header.saltBase64, SALT_SIZE)
            decodeBase64Exact(header.nonceBase64, NONCE_SIZE)
        } else if (header.algorithm != NONE) {
            throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
        }
    }

    private fun encrypt(
        plaintext: ByteArray,
        password: String,
        salt: ByteArray,
        nonce: ByteArray,
        iterations: Int,
        aad: ByteArray,
    ): ByteArray = crypt(Cipher.ENCRYPT_MODE, plaintext, password, salt, nonce, iterations, aad)

    private fun decrypt(
        ciphertext: ByteArray,
        password: String,
        salt: ByteArray,
        nonce: ByteArray,
        iterations: Int,
        aad: ByteArray,
    ): ByteArray = crypt(Cipher.DECRYPT_MODE, ciphertext, password, salt, nonce, iterations, aad)

    private fun crypt(
        mode: Int,
        input: ByteArray,
        password: String,
        salt: ByteArray,
        nonce: ByteArray,
        iterations: Int,
        aad: ByteArray,
    ): ByteArray {
        val normalized = Normalizer.normalize(password, Normalizer.Form.NFC)
        val spec = PBEKeySpec(normalized.toCharArray(), salt, iterations, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(input)
        } finally {
            spec.clearPassword()
            keyBytes.fill(0)
        }
    }

    private fun validatePassword(password: String) {
        val length = password.codePointCount(0, password.length)
        if (length !in 8..128) {
            throw LearningBackupCodecException(LearningBackupError.VALIDATION_FAILED)
        }
    }

    private fun decodeBase64Exact(value: String?, expectedBytes: Int): ByteArray {
        val encoded = value ?: throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
        if (encoded.length > ((expectedBytes + 2) / 3) * 4 + 4) {
            throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
        }
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
        }
        if (decoded.size != expectedBytes) {
            throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
        }
        return decoded
    }

    private fun canonicalAad(header: LearningBackupHeader): ByteArray =
        json.encodeToString(header).toByteArray(Charsets.UTF_8)

    private fun java.io.InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw LearningBackupCodecException(LearningBackupError.FILE_TOO_LARGE)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        sha256Bytes(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256Bytes(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun validateJsonDepth(bytes: ByteArray, maxDepth: Int) {
        var depth = 0
        var inString = false
        var escaped = false
        bytes.forEach { raw ->
            val char = raw.toInt().toChar()
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > maxDepth) {
                            throw LearningBackupCodecException(LearningBackupError.VALIDATION_FAILED)
                        }
                    }
                    '}', ']' -> {
                        depth--
                        if (depth < 0) {
                            throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
                        }
                    }
                }
            }
        }
        if (inString || escaped || depth != 0) {
            throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
        }
    }

    private fun String.hexToBytes(): ByteArray {
        if (length != 64 || any { it.digitToIntOrNull(16) == null }) {
            throw LearningBackupCodecException(LearningBackupError.INVALID_FORMAT)
        }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

private fun LearningBackupCountsDto.values(): List<Int> = listOf(
    wordMemoryStates,
    reviewLogs,
    vocabularyCheckpoints,
    reviewActionKeys,
    scenarioSessions,
    scenarioTurnProgress,
    studyTasks,
    itemMemoryStates,
    articleProgress,
)

private const val FORMAT_VERSION = 1
private const val MAX_RECORDS = 500_000
private const val MAX_ID_LENGTH = 512
private const val MAX_QUEUE_JSON_LENGTH = 1024 * 1024
private const val MAX_QUEUE_ITEMS = 100_000
private const val MAX_COUNTER = 1_000_000
private const val MAX_STABILITY = 1_000_000_000.0
private val VALID_FSRS_STATES = setOf("NEW", "LEARNING", "REVIEW", "RELEARNING")
private val VALID_RATINGS = setOf("AGAIN", "HARD", "GOOD", "EASY")
private val VALID_QUESTION_MODES = setOf("INTRODUCE", "EN2ZH", "ZH2EN", "LISTENING", "TRANSFER")
private val VALID_VOCAB_STATUSES = setOf("IN_PROGRESS", "COMPLETED", "EXPIRED")
private val VALID_VOCAB_ASSESSMENTS = setOf("UNFAMILIAR", "FUZZY", "MASTERED")
private val VALID_SCENARIO_STATUSES = setOf(
    "IN_PROGRESS",
    "COMPLETED",
    "ABORTED_CORPUS_CHANGED",
    "ABORTED_LEGACY",
)
private val VALID_SCENARIO_MODES = setOf("SCENARIO", "RANDOM")
private val VALID_SELF_RATINGS = setOf("CANNOT_ANSWER", "BASIC", "FLUENT")
private val VALID_ITEM_TYPES = setOf("PHRASE", "EXAMPLE", "PAIR")
