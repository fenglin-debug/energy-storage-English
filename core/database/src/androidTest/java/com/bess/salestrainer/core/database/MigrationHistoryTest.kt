package com.bess.salestrainer.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationHistoryTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BessDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun version1To4PreservesLearningRowsAndAbortsLegacyScenario() {
        helper.createDatabase("migration-1-4", 1).apply {
            execSQL(
                "INSERT INTO vocabulary_entries " +
                    "(id,term,normalizedTerm,chineseGloss,collocationsJson,scenarioTagsJson," +
                    "contentSource,contentVersion,active) VALUES " +
                    "('w1','BESS','bess','储能系统','[]','[]','BESS','v1',1)",
            )
            execSQL(
                "INSERT INTO word_memory_states " +
                    "(wordId,fsrsState,difficulty,stability,dueAtEpochMs,reps,lapses,masteredUi," +
                    "isFavorite,updatedAtEpochMs) VALUES ('w1','REVIEW',3.0,8.0,100,4,1,0,1,90)",
            )
            execSQL(
                "INSERT INTO review_logs " +
                    "(id,wordId,rating,questionMode,usedHint,revealedAnswer,reviewedAtEpochMs," +
                    "scheduledDays,elapsedDays,stateBefore,stateAfter) VALUES " +
                    "('r1','w1','GOOD','EN2ZH',0,1,80,3,2,'LEARNING','REVIEW')",
            )
            execSQL(
                "INSERT INTO study_tasks " +
                    "(dateEpochDay,newWordTarget,newWordDone,reviewTarget,reviewDone,studySeconds," +
                    "completed,updatedAtEpochMs) VALUES (1,15,3,4,2,600,0,90)",
            )
            execSQL(
                "INSERT INTO scenario_sessions " +
                    "(id,scenarioId,mode,status,currentCustomerTurnNo,aiStatus,startedAtEpochMs," +
                    "updatedAtEpochMs) VALUES ('s1','scenario-1','STANDARD','IN_PROGRESS',1,'NONE',10,20)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            "migration-1-4",
            4,
            true,
            BessDatabase.MIGRATION_1_2,
            BessDatabase.MIGRATION_2_3,
            BessDatabase.MIGRATION_3_4,
        )
        assertEquals(1, db.count("word_memory_states"))
        assertEquals(1, db.count("review_logs"))
        assertEquals(1, db.count("study_tasks"))
        assertEquals("ABORTED_LEGACY", db.string("SELECT status FROM scenario_sessions"))
        assertEquals("bess", db.string("SELECT legacyNormalizedTerm FROM word_memory_states"))
        db.close()
    }

    @Test
    fun version2To4PreservesLearningRows() {
        helper.createDatabase("migration-2-4", 2).apply {
            execSQL(
                "INSERT INTO word_memory_states " +
                    "(wordId,fsrsState,difficulty,stability,dueAtEpochMs,reps,lapses,masteredUi," +
                    "isFavorite,updatedAtEpochMs) VALUES ('w2','REVIEW',2.0,6.0,200,5,0,0,0,190)",
            )
            execSQL(
                "INSERT INTO study_tasks " +
                    "(dateEpochDay,newWordTarget,newWordDone,reviewTarget,reviewDone,studySeconds," +
                    "completed,updatedAtEpochMs) VALUES (2,15,4,5,3,900,0,190)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            "migration-2-4",
            4,
            true,
            BessDatabase.MIGRATION_2_3,
            BessDatabase.MIGRATION_3_4,
        )
        assertEquals(1, db.count("word_memory_states"))
        assertEquals(5, db.long("SELECT reps FROM word_memory_states"))
        assertEquals(1, db.count("study_tasks"))
        db.close()
    }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.string(sql: String): String = query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getString(0)
    }

    private fun SupportSQLiteDatabase.long(sql: String): Long = query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }
}
