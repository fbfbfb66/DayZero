package com.example.di

import android.content.Context
import com.example.data.identity.CompositeIdentityProvider
import com.example.data.identity.FixedDevelopmentAccountCredentialsProvider
import com.example.data.identity.LocalIdentityProvider
import com.example.data.identity.SupabaseAuthSessionProvider
import com.example.data.identity.SupabaseFixedPasswordIdentityProvider
import com.example.data.local.dao.AiChatMessageDao
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.dao.MediaAssetDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.database.DayZeroDatabase
import com.example.data.network.AndroidNetworkAvailabilityProvider
import com.example.data.remote.NetworkModule
import com.example.data.remote.PromptCacheKeyProvider
import com.example.data.remote.api.AiDraftApiService
import com.example.assistant.VisionAssistantTurnOrchestrator
import com.example.data.remote.stream.AssistantTurnStreamClient
import com.example.data.repository.RemoteAiAssistantRepository
import com.example.data.repository.RemoteAiDraftRepository
import com.example.data.repository.RoomChatMediaTransactionRepository
import com.example.data.repository.RoomFoodCardConfirmationRepository
import com.example.data.repository.RoomConversationRepository
import com.example.data.repository.RoomMediaRepository
import com.example.data.repository.RoomRecordRepository
import com.example.data.sync.BackfillCoordinator
import com.example.data.sync.BackfillStateStore
import com.example.data.sync.LocalFirstSyncCoordinator
import com.example.data.sync.PullCoordinator
import com.example.data.sync.PullStateStore
import com.example.data.sync.ChatRemotePullGateway
import com.example.data.sync.RemotePullGateway
import com.example.data.sync.RemoteSyncGateway
import com.example.data.sync.RemoteIdentityBindingCoordinator
import com.example.data.sync.RemoteIdentityBindingStateStore
import com.example.data.sync.SupabaseChatRemotePullGateway
import com.example.data.sync.SupabaseCloudBackupCleaner
import com.example.data.sync.SupabaseRemotePullGateway
import com.example.data.sync.SupabaseRemoteSyncGateway
import com.example.data.sync.SyncCoordinator
import com.example.data.sync.SyncHealthReporter
import com.example.data.sync.SyncQueueWriter
import com.example.data.sync.chat.ChatBackfillCoordinator
import com.example.data.sync.chat.ChatBackfillStateStore
import com.example.data.sync.chat.ChatSyncQueueWriter
import com.example.data.time.SystemCurrentDateProvider
import com.example.data.telemetry.AiLatencyTraceLogger
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.ChatMediaTransactionRepository
import com.example.domain.repository.ConversationRepository
import com.example.domain.repository.FoodCardConfirmationRepository
import com.example.domain.repository.MediaRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.network.NetworkAvailabilityProvider
import com.example.domain.time.CurrentDateProvider
import com.example.domain.usecase.ClearLocalDataUseCase
import com.example.domain.usecase.ConfirmFoodCardUseCase
import com.example.domain.usecase.ConfirmFoodRecordUseCase
import com.example.domain.usecase.CreateStagedMediaAssetsUseCase
import com.example.domain.usecase.CreateConversationWithFirstMessageUseCase
import com.example.domain.usecase.ObserveConversationMediaUseCase
import com.example.domain.usecase.ImportLocalMediaUseCase
import com.example.domain.usecase.RetryLocalMediaImportUseCase
import com.example.domain.usecase.DiscardStagedMediaUseCase
import com.example.domain.usecase.CleanupStaleMediaUseCase
import com.example.domain.usecase.SendUserMessageWithMediaUseCase
import com.example.domain.usecase.MediaIdGenerator
import com.example.data.media.MediaFileStore
import com.example.data.media.AndroidMediaFileStore
import com.example.data.media.MediaImageProcessor
import com.example.data.media.AndroidMediaImageProcessor
import com.example.data.media.AiImageDerivativeProcessor
import com.example.data.media.AndroidAiImageDerivativeProcessor
import com.example.data.repository.AndroidLocalMediaImportRepository
import com.example.data.repository.AndroidVisionAttachmentPreparationRepository
import com.example.domain.repository.VisionAttachmentPreparationRepository
import com.example.domain.usecase.PrepareVisionAttachmentsForMessageUseCase
import com.example.domain.usecase.ReleasePreparedVisionAttachmentsUseCase
import com.example.domain.repository.LocalMediaImportRepository
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

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DayZeroHiltModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): kotlinx.coroutines.CoroutineScope {
        return kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    }
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
    fun provideConversationDao(database: DayZeroDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideSyncQueueDao(database: DayZeroDatabase): SyncQueueDao = database.syncQueueDao()

    @Provides
    fun provideMediaAssetDao(database: DayZeroDatabase): MediaAssetDao = database.mediaAssetDao()

    @Provides
    @Singleton
    fun provideLatencyLogger(@ApplicationContext context: Context): AiLatencyTraceLogger {
        return AiLatencyTraceLogger(context)
    }

    @Provides
    @Singleton
    fun provideNetworkAvailabilityProvider(@ApplicationContext context: Context): NetworkAvailabilityProvider {
        return AndroidNetworkAvailabilityProvider(context)
    }

    @Provides
    @Singleton
    fun provideLocalIdentityProvider(@ApplicationContext context: Context): LocalIdentityProvider {
        return LocalIdentityProvider(context)
    }

    @Provides
    @Singleton
    fun provideFixedDevelopmentAccountCredentialsProvider(): FixedDevelopmentAccountCredentialsProvider {
        return FixedDevelopmentAccountCredentialsProvider()
    }

    @Provides
    @Singleton
    fun provideSupabaseIdentityProvider(
        @ApplicationContext context: Context,
        localIdentityProvider: LocalIdentityProvider,
        @SyncHttpClient okHttpClient: OkHttpClient,
        credentialsProvider: FixedDevelopmentAccountCredentialsProvider
    ): SupabaseFixedPasswordIdentityProvider {
        return SupabaseFixedPasswordIdentityProvider(
            context = context,
            localIdentityProvider = localIdentityProvider,
            okHttpClient = okHttpClient,
            credentialsProvider = credentialsProvider
        )
    }

    @Provides
    @Singleton
    fun provideCurrentIdentityProvider(
        localIdentityProvider: LocalIdentityProvider,
        supabaseIdentityProvider: SupabaseFixedPasswordIdentityProvider
    ): CurrentIdentityProvider {
        return CompositeIdentityProvider(
            localIdentityProvider = localIdentityProvider,
            remoteIdentityProvider = supabaseIdentityProvider
        )
    }

    @Provides
    @Singleton
    fun provideSupabaseAuthSessionProvider(
        supabaseIdentityProvider: SupabaseFixedPasswordIdentityProvider
    ): SupabaseAuthSessionProvider {
        return supabaseIdentityProvider
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
        sessionProvider: SupabaseAuthSessionProvider
    ): RemoteSyncGateway {
        return SupabaseRemoteSyncGateway(okHttpClient = okHttpClient, sessionProvider = sessionProvider)
    }

    @Provides
    @Singleton
    fun provideRemotePullGateway(
        @SyncHttpClient okHttpClient: OkHttpClient,
        sessionProvider: SupabaseAuthSessionProvider
    ): RemotePullGateway {
        return SupabaseRemotePullGateway(okHttpClient = okHttpClient, sessionProvider = sessionProvider)
    }

    @Provides
    @Singleton
    fun provideChatRemotePullGateway(
        @SyncHttpClient okHttpClient: OkHttpClient,
        sessionProvider: SupabaseAuthSessionProvider
    ): ChatRemotePullGateway {
        return SupabaseChatRemotePullGateway(okHttpClient = okHttpClient, sessionProvider = sessionProvider)
    }

    @Provides
    @Singleton
    fun provideCloudBackupCleaner(
        @SyncHttpClient okHttpClient: OkHttpClient,
        sessionProvider: SupabaseAuthSessionProvider
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
    fun provideSyncQueueWriter(syncQueueDao: SyncQueueDao): SyncQueueWriter {
        return SyncQueueWriter(syncQueueDao)
    }

    @Provides
    @Singleton
    fun provideFoodCardConfirmationRepository(
        database: DayZeroDatabase,
        identityProvider: CurrentIdentityProvider,
        syncQueueWriter: SyncQueueWriter,
        chatSyncQueueWriter: ChatSyncQueueWriter
    ): FoodCardConfirmationRepository {
        return RoomFoodCardConfirmationRepository(
            database = database,
            identityProvider = identityProvider,
            syncQueueWriter = syncQueueWriter,
            chatSyncQueueWriter = chatSyncQueueWriter
        )
    }

    @Provides
    fun provideConfirmFoodCardUseCase(
        repository: FoodCardConfirmationRepository
    ): ConfirmFoodCardUseCase {
        return ConfirmFoodCardUseCase(repository)
    }

    @Provides
    @Singleton
    fun provideMediaRepository(database: DayZeroDatabase): MediaRepository {
        return RoomMediaRepository(database)
    }

    @Provides
    fun provideObserveConversationMediaUseCase(
        mediaRepository: MediaRepository
    ): ObserveConversationMediaUseCase {
        return ObserveConversationMediaUseCase(mediaRepository)
    }

    @Provides
    fun provideCreateStagedMediaAssetsUseCase(
        mediaRepository: MediaRepository
    ): CreateStagedMediaAssetsUseCase {
        return CreateStagedMediaAssetsUseCase(mediaRepository)
    }

    @Provides
    @Singleton
    fun provideMediaFileStore(@ApplicationContext context: Context): MediaFileStore {
        return AndroidMediaFileStore(context)
    }

    @Provides
    @Singleton
    fun provideMediaImageProcessor(): MediaImageProcessor {
        return AndroidMediaImageProcessor()
    }

    @Provides
    @Singleton
    fun provideAiImageDerivativeProcessor(): AiImageDerivativeProcessor {
        return AndroidAiImageDerivativeProcessor()
    }

    @Provides
    @Singleton
    fun provideVisionAttachmentPreparationRepository(
        @ApplicationContext context: Context,
        database: DayZeroDatabase,
        fileStore: MediaFileStore,
        derivativeProcessor: AiImageDerivativeProcessor
    ): VisionAttachmentPreparationRepository {
        return AndroidVisionAttachmentPreparationRepository(
            context = context,
            messageDao = database.aiChatMessageDao(),
            mediaDao = database.mediaAssetDao(),
            fileStore = fileStore,
            derivativeProcessor = derivativeProcessor
        )
    }

    @Provides
    fun providePrepareVisionAttachmentsForMessageUseCase(
        repository: VisionAttachmentPreparationRepository
    ): PrepareVisionAttachmentsForMessageUseCase {
        return PrepareVisionAttachmentsForMessageUseCase(repository)
    }

    @Provides
    fun provideReleasePreparedVisionAttachmentsUseCase(
        repository: VisionAttachmentPreparationRepository
    ): ReleasePreparedVisionAttachmentsUseCase {
        return ReleasePreparedVisionAttachmentsUseCase(repository)
    }

    @Provides
    @Singleton
    fun provideVisionAssistantTurnOrchestrator(
        prepareUseCase: PrepareVisionAttachmentsForMessageUseCase,
        releaseUseCase: ReleasePreparedVisionAttachmentsUseCase,
        aiAssistantRepository: AiAssistantRepository,
        aiDraftRepository: AiDraftRepository,
        recordRepository: RecordRepository,
        conversationRepository: ConversationRepository,
        currentDateProvider: CurrentDateProvider,
        latencyLogger: AiLatencyTraceLogger
    ): VisionAssistantTurnOrchestrator {
        return VisionAssistantTurnOrchestrator(
            prepareUseCase = prepareUseCase,
            releaseUseCase = releaseUseCase,
            aiAssistantRepository = aiAssistantRepository,
            aiDraftRepository = aiDraftRepository,
            recordRepository = recordRepository,
            conversationRepository = conversationRepository,
            currentDateProvider = currentDateProvider,
            latencyLogger = latencyLogger
        )
    }

    @Provides
    @Singleton
    fun provideLocalMediaImportRepository(
        mediaRepository: MediaRepository,
        fileStore: MediaFileStore,
        imageProcessor: MediaImageProcessor
    ): LocalMediaImportRepository {
        return AndroidLocalMediaImportRepository(
            mediaRepository = mediaRepository,
            fileStore = fileStore,
            imageProcessor = imageProcessor
        )
    }

    @Provides
    @Singleton
    fun provideMediaIdGenerator(): MediaIdGenerator {
        return MediaIdGenerator { java.util.UUID.randomUUID().toString() }
    }

    @Provides
    fun provideImportLocalMediaUseCase(
        mediaRepository: MediaRepository,
        importRepository: LocalMediaImportRepository,
        idGenerator: MediaIdGenerator
    ): ImportLocalMediaUseCase {
        return ImportLocalMediaUseCase(
            mediaRepository = mediaRepository,
            importRepository = importRepository,
            idGenerator = idGenerator
        )
    }

    @Provides
    fun provideRetryLocalMediaImportUseCase(
        mediaRepository: MediaRepository,
        importRepository: LocalMediaImportRepository
    ): RetryLocalMediaImportUseCase {
        return RetryLocalMediaImportUseCase(
            mediaRepository = mediaRepository,
            importRepository = importRepository
        )
    }

    @Provides
    fun provideDiscardStagedMediaUseCase(
        mediaRepository: MediaRepository,
        importRepository: LocalMediaImportRepository
    ): DiscardStagedMediaUseCase {
        return DiscardStagedMediaUseCase(
            mediaRepository = mediaRepository,
            importRepository = importRepository
        )
    }

    @Provides
    fun provideCleanupStaleMediaUseCase(
        mediaRepository: MediaRepository,
        importRepository: LocalMediaImportRepository
    ): CleanupStaleMediaUseCase {
        return CleanupStaleMediaUseCase(
            mediaRepository = mediaRepository,
            importRepository = importRepository
        )
    }

    @Provides
    fun provideCreateConversationWithFirstMessageUseCase(
        aiDraftRepository: AiDraftRepository
    ): CreateConversationWithFirstMessageUseCase {
        return CreateConversationWithFirstMessageUseCase(aiDraftRepository = aiDraftRepository)
    }

    @Provides
    @Singleton
    fun provideCurrentDateProvider(): CurrentDateProvider {
        return SystemCurrentDateProvider()
    }

    @Provides
    @Singleton
    fun provideAiDraftRepository(
        apiService: AiDraftApiService,
        database: DayZeroDatabase,
        syncQueueDao: SyncQueueDao,
        identityProvider: CurrentIdentityProvider
    ): AiDraftRepository {
        return RemoteAiDraftRepository(
            apiService = apiService,
            database = database,
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider
        )
    }

    @Provides
    @Singleton
    fun provideConversationRepository(
        conversationDao: ConversationDao,
        database: DayZeroDatabase,
        syncQueueDao: SyncQueueDao,
        identityProvider: CurrentIdentityProvider
    ): ConversationRepository {
        return RoomConversationRepository(
            conversationDao = conversationDao,
            database = database,
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider
        )
    }

    @Provides
    @Singleton
    fun provideChatMediaTransactionRepository(
        database: DayZeroDatabase,
        identityProvider: CurrentIdentityProvider,
        chatSyncQueueWriter: ChatSyncQueueWriter
    ): ChatMediaTransactionRepository {
        return RoomChatMediaTransactionRepository(
            database = database,
            identityProvider = identityProvider,
            chatSyncQueueWriter = chatSyncQueueWriter
        )
    }

    @Provides
    fun provideSendUserMessageWithMediaUseCase(
        repository: ChatMediaTransactionRepository
    ): SendUserMessageWithMediaUseCase {
        return SendUserMessageWithMediaUseCase(repository)
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
        dailyRecordDao: DailyRecordDao,
        conversationDao: ConversationDao,
        chatSyncQueueWriter: ChatSyncQueueWriter
    ): SyncCoordinator {
        return LocalFirstSyncCoordinator(
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider,
            remoteSyncGateway = remoteSyncGateway,
            dailyRecordDao = dailyRecordDao,
            conversationDao = conversationDao,
            chatSyncQueueWriter = chatSyncQueueWriter
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
    fun provideChatBackfillStateStore(@ApplicationContext context: Context): ChatBackfillStateStore {
        return ChatBackfillStateStore(context)
    }

    @Provides
    @Singleton
    fun provideRemoteIdentityBindingStateStore(@ApplicationContext context: Context): RemoteIdentityBindingStateStore {
        return RemoteIdentityBindingStateStore(context)
    }

    @Provides
    @Singleton
    fun provideRemoteIdentityBindingCoordinator(
        identityProvider: CurrentIdentityProvider,
        bindingStateStore: RemoteIdentityBindingStateStore,
        backfillStateStore: BackfillStateStore,
        pullStateStore: PullStateStore,
        chatBackfillStateStore: ChatBackfillStateStore,
        chatConversationPullStateStore: com.example.data.sync.chat.ChatConversationPullStateStore,
        chatMessagePullStateStore: com.example.data.sync.chat.ChatMessagePullStateStore,
        chatPullHealthStateStore: com.example.data.sync.chat.ChatPullHealthStateStore
    ): RemoteIdentityBindingCoordinator {
        return RemoteIdentityBindingCoordinator(
            identityProvider = identityProvider,
            bindingStateStore = bindingStateStore,
            backfillStateStore = backfillStateStore,
            pullStateStore = pullStateStore,
            chatBackfillStateStore = chatBackfillStateStore,
            chatConversationPullStateStore = chatConversationPullStateStore,
            chatMessagePullStateStore = chatMessagePullStateStore,
            chatPullHealthStateStore = chatPullHealthStateStore
        )
    }

    @Provides
    @Singleton
    fun provideChatSyncQueueWriter(syncQueueDao: SyncQueueDao): ChatSyncQueueWriter {
        return ChatSyncQueueWriter(syncQueueDao)
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
    fun provideChatBackfillCoordinator(
        conversationDao: ConversationDao,
        messageDao: AiChatMessageDao,
        identityProvider: CurrentIdentityProvider,
        stateStore: ChatBackfillStateStore,
        queueWriter: ChatSyncQueueWriter
    ): ChatBackfillCoordinator {
        return ChatBackfillCoordinator(
            conversationDao = conversationDao,
            messageDao = messageDao,
            identityProvider = identityProvider,
            stateStore = stateStore,
            queueWriter = queueWriter
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
        chatPullHealthStateStore: com.example.data.sync.chat.ChatPullHealthStateStore,
        dailyRecordDao: DailyRecordDao
    ): SyncHealthReporter {
        return SyncHealthReporter(
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider,
            backfillStateStore = backfillStateStore,
            pullStateStore = pullStateStore,
            chatPullHealthStateStore = chatPullHealthStateStore,
            dailyRecordDao = dailyRecordDao,
            remoteSyncEnabledProvider = { com.example.data.remote.SupabaseConfig.isConfigured() }
        )
    }

    @Provides
    @Singleton
    fun provideSyncScheduler(
        @ApplicationScope scope: kotlinx.coroutines.CoroutineScope,
        syncCoordinator: SyncCoordinator,
        backfillCoordinator: BackfillCoordinator,
        chatBackfillCoordinator: ChatBackfillCoordinator,
        pullCoordinator: PullCoordinator,
        chatPullCoordinator: com.example.data.sync.chat.ChatPullCoordinator,
        chatPullHealthStateStore: com.example.data.sync.chat.ChatPullHealthStateStore,
        remoteIdentityBindingCoordinator: RemoteIdentityBindingCoordinator,
        syncHealthReporter: SyncHealthReporter
    ): com.example.data.sync.SyncScheduler {
        return com.example.data.sync.InProcessSyncScheduler(
            scope = scope,
            syncCoordinator = syncCoordinator,
            backfillCoordinator = backfillCoordinator,
            chatBackfillCoordinator = chatBackfillCoordinator,
            pullCoordinator = pullCoordinator,
            chatPullCoordinator = chatPullCoordinator,
            chatPullHealthStateStore = chatPullHealthStateStore,
            remoteIdentityBindingCoordinator = remoteIdentityBindingCoordinator,
            syncHealthReporter = syncHealthReporter
        )
    }

    @Provides
    @Singleton
    fun provideSyncStatusRepository(
        syncCoordinator: SyncCoordinator,
        backfillCoordinator: BackfillCoordinator,
        syncHealthReporter: SyncHealthReporter,
        syncScheduler: com.example.data.sync.SyncScheduler
    ): com.example.data.sync.SyncStatusRepository {
        return com.example.data.sync.SyncStatusRepository(
            syncCoordinator = syncCoordinator,
            backfillCoordinator = backfillCoordinator,
            syncHealthReporter = syncHealthReporter,
            syncScheduler = syncScheduler
        )
    }

    @Provides
    @Singleton
    fun provideChatConversationPullStateStore(@ApplicationContext context: Context): com.example.data.sync.chat.ChatConversationPullStateStore {
        return com.example.data.sync.chat.ChatConversationPullStateStore(context)
    }

    @Provides
    @Singleton
    fun provideChatMessagePullStateStore(@ApplicationContext context: Context): com.example.data.sync.chat.ChatMessagePullStateStore {
        return com.example.data.sync.chat.ChatMessagePullStateStore(context)
    }

    @Provides
    @Singleton
    fun provideChatPullHealthStateStore(@ApplicationContext context: Context): com.example.data.sync.chat.ChatPullHealthStateStore {
        return com.example.data.sync.chat.ChatPullHealthStateStore(context)
    }

    @Provides
    @Singleton
    fun provideChatConversationRemoteMerger(
        database: DayZeroDatabase,
        conversationDao: ConversationDao,
        syncQueueDao: SyncQueueDao
    ): com.example.data.sync.chat.ChatConversationRemoteMerger {
        return com.example.data.sync.chat.ChatConversationRemoteMerger(
            database = database,
            conversationDao = conversationDao,
            syncQueueDao = syncQueueDao
        )
    }

    @Provides
    @Singleton
    fun provideChatMessageRemoteMerger(
        database: DayZeroDatabase,
        messageDao: com.example.data.local.dao.AiChatMessageDao,
        conversationDao: ConversationDao,
        syncQueueDao: SyncQueueDao
    ): com.example.data.sync.chat.ChatMessageRemoteMerger {
        return com.example.data.sync.chat.ChatMessageRemoteMerger(
            database = database,
            messageDao = messageDao,
            conversationDao = conversationDao,
            syncQueueDao = syncQueueDao
        )
    }

    @Provides
    @Singleton
    fun provideChatConversationPullCoordinator(
        identityProvider: CurrentIdentityProvider,
        chatRemotePullGateway: ChatRemotePullGateway,
        remoteMerger: com.example.data.sync.chat.ChatConversationRemoteMerger,
        stateStore: com.example.data.sync.chat.ChatConversationPullStateStore
    ): com.example.data.sync.chat.ChatConversationPullCoordinator {
        return com.example.data.sync.chat.ChatConversationPullCoordinator(
            identityProvider = identityProvider,
            remotePullGateway = chatRemotePullGateway,
            remoteMerger = remoteMerger,
            stateStore = stateStore
        )
    }

    @Provides
    @Singleton
    fun provideChatMessagePullCoordinator(
        identityProvider: CurrentIdentityProvider,
        chatRemotePullGateway: ChatRemotePullGateway,
        remoteMerger: com.example.data.sync.chat.ChatMessageRemoteMerger,
        stateStore: com.example.data.sync.chat.ChatMessagePullStateStore
    ): com.example.data.sync.chat.ChatMessagePullCoordinator {
        return com.example.data.sync.chat.ChatMessagePullCoordinator(
            identityProvider = identityProvider,
            remotePullGateway = chatRemotePullGateway,
            remoteMerger = remoteMerger,
            stateStore = stateStore
        )
    }

    @Provides
    @Singleton
    fun provideChatPullCoordinator(
        conversationPullCoordinator: com.example.data.sync.chat.ChatConversationPullCoordinator,
        messagePullCoordinator: com.example.data.sync.chat.ChatMessagePullCoordinator
    ): com.example.data.sync.chat.ChatPullCoordinator {
        return com.example.data.sync.chat.ChatPullCoordinator(
            conversationPullCoordinator = conversationPullCoordinator,
            messagePullCoordinator = messagePullCoordinator
        )
    }
}
