package com.example.di

import android.content.Context
import com.example.data.identity.CompositeIdentityProvider
import com.example.data.identity.LocalIdentityProvider
import com.example.data.identity.SupabaseAnonymousIdentityProvider
import com.example.data.local.dao.AiChatMessageDao
import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.database.DayZeroDatabase
import com.example.data.remote.NetworkModule
import com.example.data.remote.PromptCacheKeyProvider
import com.example.data.remote.api.AiDraftApiService
import com.example.data.remote.stream.AssistantTurnStreamClient
import com.example.data.repository.RemoteAiAssistantRepository
import com.example.data.repository.RemoteAiDraftRepository
import com.example.data.repository.RoomRecordRepository
import com.example.data.sync.BackfillCoordinator
import com.example.data.sync.BackfillStateStore
import com.example.data.sync.LocalFirstSyncCoordinator
import com.example.data.sync.PullCoordinator
import com.example.data.sync.PullStateStore
import com.example.data.sync.RemotePullGateway
import com.example.data.sync.RemoteSyncGateway
import com.example.data.sync.SupabaseCloudBackupCleaner
import com.example.data.sync.SupabaseRemotePullGateway
import com.example.data.sync.SupabaseRemoteSyncGateway
import com.example.data.sync.SyncCoordinator
import com.example.data.sync.SyncHealthReporter
import com.example.data.telemetry.AiLatencyTraceLogger
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.usecase.ClearLocalDataUseCase
import com.example.domain.usecase.ConfirmFoodRecordUseCase
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
private annotation class GeneralHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
private annotation class StreamingHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
private annotation class SyncHttpClient

@Module
@InstallIn(SingletonComponent::class)
object DayZeroHiltModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DayZeroDatabase {
        return DayZeroDatabase.getDatabase(context)
    }

    @Provides
    fun provideDailyRecordDao(database: DayZeroDatabase): DailyRecordDao = database.dailyRecordDao()

    @Provides
    fun provideAiChatMessageDao(database: DayZeroDatabase): AiChatMessageDao = database.aiChatMessageDao()

    @Provides
    fun provideSyncQueueDao(database: DayZeroDatabase): SyncQueueDao = database.syncQueueDao()

    @Provides
    @Singleton
    fun provideLatencyLogger(@ApplicationContext context: Context): AiLatencyTraceLogger {
        return AiLatencyTraceLogger(context)
    }

    @Provides
    @Singleton
    fun provideLocalIdentityProvider(@ApplicationContext context: Context): LocalIdentityProvider {
        return LocalIdentityProvider(context)
    }

    @Provides
    @Singleton
    fun provideSupabaseIdentityProvider(
        @ApplicationContext context: Context,
        localIdentityProvider: LocalIdentityProvider,
        @SyncHttpClient okHttpClient: OkHttpClient
    ): SupabaseAnonymousIdentityProvider {
        return SupabaseAnonymousIdentityProvider(
            context = context,
            localIdentityProvider = localIdentityProvider,
            okHttpClient = okHttpClient
        )
    }

    @Provides
    @Singleton
    fun provideCurrentIdentityProvider(
        localIdentityProvider: LocalIdentityProvider,
        supabaseIdentityProvider: SupabaseAnonymousIdentityProvider
    ): CurrentIdentityProvider {
        return CompositeIdentityProvider(
            localIdentityProvider = localIdentityProvider,
            remoteIdentityProvider = supabaseIdentityProvider
        )
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = NetworkModule.moshi

    @Provides
    @Singleton
    @GeneralHttpClient
    fun provideGeneralHttpClient(): OkHttpClient = NetworkModule.okHttpClient

    @Provides
    @Singleton
    @StreamingHttpClient
    fun provideStreamingHttpClient(): OkHttpClient = NetworkModule.streamingOkHttpClient

    @Provides
    @Singleton
    @SyncHttpClient
    fun provideSyncHttpClient(): OkHttpClient = NetworkModule.syncOkHttpClient

    @Provides
    @Singleton
    fun provideAiDraftApiService(): AiDraftApiService = NetworkModule.aiDraftApiService

    @Provides
    @Singleton
    fun provideRemoteSyncGateway(
        @SyncHttpClient okHttpClient: OkHttpClient,
        sessionProvider: SupabaseAnonymousIdentityProvider
    ): RemoteSyncGateway {
        return SupabaseRemoteSyncGateway(okHttpClient = okHttpClient, sessionProvider = sessionProvider)
    }

    @Provides
    @Singleton
    fun provideRemotePullGateway(
        @SyncHttpClient okHttpClient: OkHttpClient,
        sessionProvider: SupabaseAnonymousIdentityProvider
    ): RemotePullGateway {
        return SupabaseRemotePullGateway(okHttpClient = okHttpClient, sessionProvider = sessionProvider)
    }

    @Provides
    @Singleton
    fun provideCloudBackupCleaner(
        @SyncHttpClient okHttpClient: OkHttpClient,
        sessionProvider: SupabaseAnonymousIdentityProvider
    ): SupabaseCloudBackupCleaner {
        return SupabaseCloudBackupCleaner(okHttpClient = okHttpClient, sessionProvider = sessionProvider)
    }

    @Provides
    @Singleton
    fun provideRecordRepository(
        dailyRecordDao: DailyRecordDao,
        syncQueueDao: SyncQueueDao,
        identityProvider: CurrentIdentityProvider
    ): RecordRepository {
        return RoomRecordRepository(
            dao = dailyRecordDao,
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider
        )
    }

    @Provides
    fun provideClearLocalDataUseCase(
        recordRepository: RecordRepository,
        aiDraftRepository: AiDraftRepository
    ): ClearLocalDataUseCase {
        return ClearLocalDataUseCase(
            recordRepository = recordRepository,
            aiDraftRepository = aiDraftRepository
        )
    }

    @Provides
    fun provideConfirmFoodRecordUseCase(
        recordRepository: RecordRepository
    ): ConfirmFoodRecordUseCase {
        return ConfirmFoodRecordUseCase(recordRepository = recordRepository)
    }

    @Provides
    @Singleton
    fun provideAiDraftRepository(
        apiService: AiDraftApiService,
        chatDao: AiChatMessageDao
    ): AiDraftRepository {
        return RemoteAiDraftRepository(apiService = apiService, chatDao = chatDao)
    }

    @Provides
    @Singleton
    fun provideAiAssistantRepository(
        @ApplicationContext context: Context,
        apiService: AiDraftApiService,
        latencyLogger: AiLatencyTraceLogger,
        @StreamingHttpClient streamingOkHttpClient: OkHttpClient,
        moshi: Moshi
    ): AiAssistantRepository {
        val promptCacheKeyProvider = PromptCacheKeyProvider(context)
        val streamClient = AssistantTurnStreamClient(
            okHttpClient = streamingOkHttpClient,
            moshi = moshi
        )
        return RemoteAiAssistantRepository(
            apiService = apiService,
            latencyLogger = latencyLogger,
            streamClient = streamClient,
            promptCacheKeyProvider = { promptCacheKeyProvider.getPromptCacheKey() }
        )
    }

    @Provides
    @Singleton
    fun provideSyncCoordinator(
        syncQueueDao: SyncQueueDao,
        identityProvider: CurrentIdentityProvider,
        remoteSyncGateway: RemoteSyncGateway,
        dailyRecordDao: DailyRecordDao
    ): SyncCoordinator {
        return LocalFirstSyncCoordinator(
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider,
            remoteSyncGateway = remoteSyncGateway,
            dailyRecordDao = dailyRecordDao
        )
    }

    @Provides
    @Singleton
    fun provideBackfillStateStore(@ApplicationContext context: Context): BackfillStateStore {
        return BackfillStateStore(context)
    }

    @Provides
    @Singleton
    fun providePullStateStore(@ApplicationContext context: Context): PullStateStore {
        return PullStateStore(context)
    }

    @Provides
    @Singleton
    fun provideBackfillCoordinator(
        dailyRecordDao: DailyRecordDao,
        syncQueueDao: SyncQueueDao,
        identityProvider: CurrentIdentityProvider,
        stateStore: BackfillStateStore
    ): BackfillCoordinator {
        return BackfillCoordinator(
            dailyRecordDao = dailyRecordDao,
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider,
            stateStore = stateStore
        )
    }

    @Provides
    @Singleton
    fun providePullCoordinator(
        database: DayZeroDatabase,
        remotePullGateway: RemotePullGateway,
        identityProvider: CurrentIdentityProvider,
        stateStore: PullStateStore
    ): PullCoordinator {
        return PullCoordinator(
            database = database,
            remotePullGateway = remotePullGateway,
            identityProvider = identityProvider,
            stateStore = stateStore
        )
    }

    @Provides
    @Singleton
    fun provideSyncHealthReporter(
        syncQueueDao: SyncQueueDao,
        identityProvider: CurrentIdentityProvider,
        backfillStateStore: BackfillStateStore,
        pullStateStore: PullStateStore,
        dailyRecordDao: DailyRecordDao
    ): SyncHealthReporter {
        return SyncHealthReporter(
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider,
            backfillStateStore = backfillStateStore,
            pullStateStore = pullStateStore,
            dailyRecordDao = dailyRecordDao,
            remoteSyncEnabledProvider = { com.example.data.remote.SupabaseConfig.isConfigured() }
        )
    }
}
