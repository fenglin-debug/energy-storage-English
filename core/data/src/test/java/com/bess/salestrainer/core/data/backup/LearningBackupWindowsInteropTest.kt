package com.bess.salestrainer.core.data.backup

import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningBackupWindowsInteropTest {
    @Test
    fun decodeWindowsAndWriteAndroidFixtureWhenRequested() {
        val input = System.getenv("BESS_WINDOWS_INTEROP_INPUT") ?: return
        val decoded = LearningBackupCodec.decode(
            Files.readAllBytes(Path.of(input)),
            "储能-pass-2026",
        )
        assertEquals(1, decoded.payload.wordMemoryStates.size)

        val output = System.getenv("BESS_ANDROID_INTEROP_OUTPUT") ?: return
        val now = 1_800_000_000_000L
        val payload = LearningBackupPayload(
            wordMemoryStates = listOf(
                WordMemoryStateEntity(
                    wordId = "ANDROID-INTEROP-001",
                    fsrsState = "REVIEW",
                    difficulty = 5.0,
                    stability = 10.0,
                    dueAtEpochMs = now + 86_400_000L,
                    lastReviewAtEpochMs = now,
                    reps = 3,
                    lapses = 0,
                    masteredUi = true,
                    lastQuestionMode = "LISTENING",
                    isFavorite = true,
                    learnedContentHash = "interop-hash",
                    updatedAtEpochMs = now,
                ),
            ),
        )
        val encoded = LearningBackupCodec.encode(
            payload = payload,
            metadata = LearningBackupMetadata(
                createdAtEpochMs = now,
                appVersionName = "0.2.0",
                appVersionCode = 2,
                databaseVersion = 4,
                corpusPackageKey = null,
                corpusContentVersion = null,
            ),
            password = "储能-pass-2026",
        )
        Files.write(Path.of(output), encoded)
    }
}
