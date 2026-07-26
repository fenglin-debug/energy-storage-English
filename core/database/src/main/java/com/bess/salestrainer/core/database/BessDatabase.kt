package com.bess.salestrainer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.bess.salestrainer.core.database.dao.AiAdviceDao
import com.bess.salestrainer.core.database.dao.CorpusDao
import com.bess.salestrainer.core.database.dao.ScenarioDao
import com.bess.salestrainer.core.database.dao.StudyTaskDao
import com.bess.salestrainer.core.database.dao.VocabularyDao
import com.bess.salestrainer.core.database.entity.AiAdviceEntity
import com.bess.salestrainer.core.database.entity.ConflictGuardEntity
import com.bess.salestrainer.core.database.entity.CorpusVersionEntity
import com.bess.salestrainer.core.database.entity.DialogueTurnEntity
import com.bess.salestrainer.core.database.entity.ReviewLogEntity
import com.bess.salestrainer.core.database.entity.SafeFactEntity
import com.bess.salestrainer.core.database.entity.ScenarioEntity
import com.bess.salestrainer.core.database.entity.ScenarioSessionEntity
import com.bess.salestrainer.core.database.entity.ScenarioTrainingPackEntity
import com.bess.salestrainer.core.database.entity.StudyTaskEntity
import com.bess.salestrainer.core.database.entity.TurnAttemptEntity
import com.bess.salestrainer.core.database.entity.VocabularyEntryEntity
import com.bess.salestrainer.core.database.entity.WordMemoryStateEntity

@Database(
    entities = [
        VocabularyEntryEntity::class,
        ScenarioEntity::class,
        DialogueTurnEntity::class,
        ScenarioTrainingPackEntity::class,
        SafeFactEntity::class,
        ConflictGuardEntity::class,
        CorpusVersionEntity::class,
        WordMemoryStateEntity::class,
        ReviewLogEntity::class,
        ScenarioSessionEntity::class,
        TurnAttemptEntity::class,
        StudyTaskEntity::class,
        AiAdviceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BessDatabase : RoomDatabase() {
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun scenarioDao(): ScenarioDao
    abstract fun studyTaskDao(): StudyTaskDao
    abstract fun corpusDao(): CorpusDao
    abstract fun aiAdviceDao(): AiAdviceDao

    companion object {
        const val NAME = "bess.db"
    }
}
