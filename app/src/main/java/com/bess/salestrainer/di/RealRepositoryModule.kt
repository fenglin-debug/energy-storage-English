package com.bess.salestrainer.di

import android.content.Context
import androidx.room.Room
import com.bess.salestrainer.BuildConfig
import com.bess.salestrainer.core.audio.Media3AudioPlaybackRepository
import com.bess.salestrainer.core.corpus.importing.CorpusImporter
import com.bess.salestrainer.core.data.repository.ArticleRepositoryImpl
import com.bess.salestrainer.core.data.backup.LearningBackupRepositoryImpl
import com.bess.salestrainer.core.data.repository.CorpusRepositoryImpl
import com.bess.salestrainer.core.data.repository.ScenarioRepositoryImpl
import com.bess.salestrainer.core.data.repository.SettingsRepositoryImpl
import com.bess.salestrainer.core.data.repository.StudyTaskRepositoryImpl
import com.bess.salestrainer.core.data.repository.VocabularyRepositoryImpl
import com.bess.salestrainer.core.database.BessDatabase
import com.bess.salestrainer.core.model.contract.ArticleRepository
import com.bess.salestrainer.core.model.contract.LearningBackupRepository
import com.bess.salestrainer.core.model.contract.AudioPlaybackRepository
import com.bess.salestrainer.core.model.contract.CorpusRepository
import com.bess.salestrainer.core.model.contract.ScenarioRepository
import com.bess.salestrainer.core.model.contract.SettingsRepository
import com.bess.salestrainer.core.model.contract.StudyTaskRepository
import com.bess.salestrainer.core.model.contract.VocabularyRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * M-10: real Room/DataStore/Media3/file-backed bindings (both build types
 * once M-14 removes the debug Fake module). No network, no recording,
 * no AI — ever (AGENTS.md non-negotiables).
 */
@Module
@InstallIn(SingletonComponent::class)
object RealRepositoryModule {

    @Provides @Singleton
    fun provideBessDatabase(@ApplicationContext context: Context): BessDatabase =
        Room.databaseBuilder(context, BessDatabase::class.java, "bess.db")
            .addMigrations(
                BessDatabase.MIGRATION_1_2,
                BessDatabase.MIGRATION_2_3,
                BessDatabase.MIGRATION_3_4,
            )
            .build()

    @Provides @Singleton
    fun provideCorpusImporter(
        @ApplicationContext context: Context,
        db: BessDatabase,
    ): CorpusImporter = CorpusImporter(
        db = db,
        corpusRootDir = File(context.filesDir, "corpus"),
    )

    @Provides @Singleton
    fun provideVocabularyRepository(
        db: BessDatabase,
        settingsRepository: SettingsRepository,
    ): VocabularyRepository =
        VocabularyRepositoryImpl(db, settingsRepository = settingsRepository)

    @Provides @Singleton
    fun provideScenarioRepository(db: BessDatabase): ScenarioRepository =
        ScenarioRepositoryImpl(db)

    @Provides @Singleton
    fun provideStudyTaskRepository(
        db: BessDatabase,
        settingsRepository: SettingsRepository,
    ): StudyTaskRepository =
        StudyTaskRepositoryImpl(db, settingsRepository = settingsRepository)

    @Provides @Singleton
    fun provideCorpusRepository(
        @ApplicationContext context: Context,
        db: BessDatabase,
        importer: CorpusImporter,
    ): CorpusRepository = CorpusRepositoryImpl(
        context = context,
        db = db,
        importer = importer,
        appVersionCode = BuildConfig.VERSION_CODE,
    )

    @Provides @Singleton
    fun provideArticleRepository(
        @ApplicationContext context: Context,
        db: BessDatabase,
    ): ArticleRepository =
        ArticleRepositoryImpl(
            context = context,
            db = db,
            articlesDir = File(context.filesDir, "articles"),
        )

    @Provides @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepositoryImpl(context)

    @Provides @Singleton
    fun provideLearningBackupRepository(
        @ApplicationContext context: Context,
        db: BessDatabase,
    ): LearningBackupRepository = LearningBackupRepositoryImpl(
        context = context,
        db = db,
        appVersionName = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE,
    )

    @Provides @Singleton
    fun provideAudioPlaybackRepository(
        @ApplicationContext context: Context,
        db: BessDatabase,
        importer: CorpusImporter,
    ): AudioPlaybackRepository {
        val articlesDir = File(context.filesDir, "articles")
        val locator = Media3AudioPlaybackRepository.AudioAssetLocator { assetId ->
            val asset = db.corpusDao().getAudioAsset(assetId) ?: return@AudioAssetLocator null
            // Article audio lives in the shared articles dir, independent of
            // the active corpus package key (D5: upsert semantics survive
            // corpus replacement).
            val rootDirectory: File
            val file = if (asset.kind == "ARTICLE") {
                rootDirectory = articlesDir
                File(rootDirectory, asset.relativePath.removePrefix("articles/"))
            } else {
                val active = db.corpusDao().getActive() ?: return@AudioAssetLocator null
                rootDirectory = importer.audioBaseDir(active.packageKey)
                File(rootDirectory, asset.relativePath)
            }
            if (!file.isFile) return@AudioAssetLocator null
            Media3AudioPlaybackRepository.LocatedAsset(
                file = file,
                rootDirectory = rootDirectory,
                expectedSha256 = asset.sha256,
                durationMs = asset.durationMs,
                displayTitle = when (asset.kind) {
                    "ARTICLE" -> "文章磨耳朵"
                    "WORD" -> "词汇朗读"
                    "EXAMPLE" -> "例句朗读"
                    else -> "情景对话"
                },
            )
        }
        return Media3AudioPlaybackRepository(context, locator)
    }
}
