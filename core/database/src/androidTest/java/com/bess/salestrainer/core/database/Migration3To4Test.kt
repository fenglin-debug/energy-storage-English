package com.bess.salestrainer.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration3To4Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BessDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsAssessmentMasteryAndRandomSessionColumns() {
        helper.createDatabase(DATABASE_NAME, 3).close()

        val db = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            4,
            true,
            BessDatabase.MIGRATION_3_4,
        )

        assertTrue("assessmentSubmitted" in db.columns("vocabulary_session_checkpoints"))
        assertTrue("selectedAssessment" in db.columns("vocabulary_session_checkpoints"))
        assertTrue("masteredUi" in db.columns("item_memory_states"))
        assertTrue("practiceMode" in db.columns("scenario_sessions"))
        assertTrue("queuePairIdsJson" in db.columns("scenario_sessions"))
        assertTrue("contentScope" in db.columns("articles"))
        db.close()
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.columns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private companion object {
        const val DATABASE_NAME = "migration-3-4-test"
    }
}
