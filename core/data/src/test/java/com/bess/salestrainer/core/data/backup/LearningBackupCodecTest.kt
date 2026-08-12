package com.bess.salestrainer.core.data.backup

import com.bess.salestrainer.core.database.entity.VocabularySessionCheckpointEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import com.bess.salestrainer.core.model.LearningBackupError
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningBackupCodecTest {
    private val json = Json { explicitNulls = true; encodeDefaults = true }
    private val payload = LearningBackupPayload(
        wordMemoryStates = listOf(
            WordMemoryStateEntity(
                wordId = "词汇-α",
                fsrsState = "REVIEW",
                difficulty = 4.2,
                stability = 12.5,
                dueAtEpochMs = 1_800_000_000_000,
                lastReviewAtEpochMs = 1_700_000_000_000,
                reps = 3,
                lapses = 1,
                masteredUi = false,
                lastQuestionMode = "EN2ZH",
                isFavorite = true,
                learnedContentHash = "hash",
                updatedAtEpochMs = 1_700_000_000_000,
            ),
        ),
        vocabularyCheckpoints = listOf(
            VocabularySessionCheckpointEntity(
                sessionId = "session-1",
                status = "IN_PROGRESS",
                corpusVersion = "2026.08",
                queueWordIdsJson = "[\"词汇-α\"]",
                currentIndex = 0,
                questionMode = "EN2ZH",
                answerRevealed = true,
                hintRevealed = false,
                assessmentSubmitted = false,
                selectedAssessment = null,
                startedAtEpochMs = 10,
                updatedAtEpochMs = 20,
            ),
        ),
    )
    private val metadata = LearningBackupMetadata(
        createdAtEpochMs = 1234,
        appVersionName = "0.2.0",
        appVersionCode = 2,
        databaseVersion = 4,
        corpusPackageKey = "bundled@2026.08",
        corpusContentVersion = "2026.08",
    )

    @Test
    fun `unencrypted backup round trips unicode and checkpoints`() {
        val encoded = LearningBackupCodec.encode(payload, metadata, password = null)
        val decoded = LearningBackupCodec.decode(encoded, password = null)

        assertFalse(decoded.header.encrypted)
        assertEquals(payload, decoded.payload)
        assertEquals(2, decoded.payload.counts().total)
    }

    @Test
    fun `encrypted backup requires the correct password`() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
        val encoded = LearningBackupCodec.encode(
            payload,
            metadata,
            password = "正确密码-1234",
            secureRandom = random,
            iterations = 100_000,
        )

        assertTrue(LearningBackupCodec.readHeader(encoded).encrypted)
        val missing = runCatching { LearningBackupCodec.decode(encoded, null) }.exceptionOrNull()
        assertEquals(LearningBackupError.PASSWORD_REQUIRED, (missing as LearningBackupCodecException).error)
        val wrong = runCatching { LearningBackupCodec.decode(encoded, "错误密码-1234") }.exceptionOrNull()
        assertEquals(
            LearningBackupError.WRONG_PASSWORD_OR_DAMAGED,
            (wrong as LearningBackupCodecException).error,
        )
        assertEquals(payload, LearningBackupCodec.decode(encoded, "正确密码-1234").payload)
    }

    @Test
    fun `encrypted header rejects nonstandard salt and nonce before key derivation`() {
        val encoded = LearningBackupCodec.encode(
            payload,
            metadata,
            password = "secure-password",
            secureRandom = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) },
            iterations = 100_000,
        )

        val badSalt = mutateHeader(encoded) {
            it.copy(saltBase64 = Base64.getEncoder().encodeToString(ByteArray(15)))
        }
        val badNonce = mutateHeader(encoded) {
            it.copy(nonceBase64 = Base64.getEncoder().encodeToString(ByteArray(13)))
        }

        listOf(badSalt, badNonce).forEach { malformed ->
            val error = runCatching { LearningBackupCodec.readHeader(malformed) }.exceptionOrNull()
            assertEquals(LearningBackupError.INVALID_FORMAT, (error as LearningBackupCodecException).error)
        }
    }

    @Test
    fun `duplicate primary keys are rejected before export`() {
        val duplicate = payload.copy(wordMemoryStates = payload.wordMemoryStates + payload.wordMemoryStates.first())

        val error = runCatching {
            LearningBackupCodec.encode(duplicate, metadata, password = null)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private fun mutateHeader(
        encoded: ByteArray,
        transform: (LearningBackupHeader) -> LearningBackupHeader,
    ): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(encoded)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val header = json.decodeFromString<LearningBackupHeader>(
            entries.getValue("manifest.json").toString(Charsets.UTF_8),
        )
        entries["manifest.json"] = json.encodeToString(transform(header)).toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }
}
