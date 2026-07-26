package com.bess.salestrainer.di

import com.bess.salestrainer.core.model.contract.AiCoachRepository
import com.bess.salestrainer.core.model.contract.CorpusRepository
import com.bess.salestrainer.core.model.contract.ScenarioRepository
import com.bess.salestrainer.core.model.contract.SettingsRepository
import com.bess.salestrainer.core.model.contract.SpeechRepository
import com.bess.salestrainer.core.model.contract.StudyTaskRepository
import com.bess.salestrainer.core.model.contract.VocabularyRepository
import com.bess.salestrainer.core.model.fake.FakeAiCoachRepository
import com.bess.salestrainer.core.model.fake.FakeCorpusRepository
import com.bess.salestrainer.core.model.fake.FakeScenarioRepository
import com.bess.salestrainer.core.model.fake.FakeSettingsRepository
import com.bess.salestrainer.core.model.fake.FakeSpeechRepository
import com.bess.salestrainer.core.model.fake.FakeStudyTaskRepository
import com.bess.salestrainer.core.model.fake.FakeVocabularyRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wave 0: binds in-memory Fakes so the app shell runs end-to-end.
 * Gate 1 (TASK-M-03): replace with real Room/Retrofit/sherpa-onnx implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides @Singleton
    fun provideVocabularyRepository(): VocabularyRepository = FakeVocabularyRepository()

    @Provides @Singleton
    fun provideScenarioRepository(): ScenarioRepository = FakeScenarioRepository()

    @Provides @Singleton
    fun provideStudyTaskRepository(): StudyTaskRepository = FakeStudyTaskRepository()

    @Provides @Singleton
    fun provideCorpusRepository(): CorpusRepository = FakeCorpusRepository()

    @Provides @Singleton
    fun provideSpeechRepository(): SpeechRepository = FakeSpeechRepository()

    @Provides @Singleton
    fun provideAiCoachRepository(): AiCoachRepository = FakeAiCoachRepository()

    @Provides @Singleton
    fun provideSettingsRepository(): SettingsRepository = FakeSettingsRepository()
}
