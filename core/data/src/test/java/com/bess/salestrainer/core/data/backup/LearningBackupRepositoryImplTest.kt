package com.bess.salestrainer.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity
import com.bess.salestrainer.core.model.BackupDocumentRef
import com.bess.salestrainer.core.model.LearningBackupInspection
import com.bess.salestrainer.core.model.LearningBackupResult
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LearningBackupRepositoryImplTest {
    private lateinit var db: BessDatabase
    private lateinit var context: Context
    private lateinit var repository: LearningBackupRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BessDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LearningBackupRepositoryImpl(context, db, "0.2.0-test", 2) { 999L }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `restore fully replaces learning state`() = runTest {
        val original = memory("w1", reps = 4)
        db.learningStateDao().insertWordMemoryStates(listOf(original))
        val backup = File(context.cacheDir, "roundtrip-${System.nanoTime()}.bessbackup")

        val exported = repository.exportBackup(BackupDocumentRef(backup.absolutePath), null)
        assertTrue(exported is LearningBackupResult.Success)

        db.learningStateDao().deleteWordMemoryStates()
        db.learningStateDao().insertWordMemoryStates(listOf(memory("w2", reps = 1)))
        val inspection = repository.inspectBackup(BackupDocumentRef(backup.absolutePath), null)
        val ready = inspection as LearningBackupInspection.Ready
        val restored = repository.restoreBackup(ready.preview.previewId)

        assertTrue(restored is LearningBackupResult.Success)
        assertEquals(listOf(original), db.learningStateDao().wordMemoryStates())
        backup.delete()
    }

    private fun memory(id: String, reps: Int) = WordMemoryStateEntity(
        wordId = id,
        fsrsState = "REVIEW",
        difficulty = 3.0,
        stability = 8.0,
        dueAtEpochMs = 100,
        lastReviewAtEpochMs = 50,
        reps = reps,
        lapses = 0,
        masteredUi = false,
        lastQuestionMode = "EN2ZH",
        learnedContentHash = null,
        updatedAtEpochMs = 75,
    )
}
