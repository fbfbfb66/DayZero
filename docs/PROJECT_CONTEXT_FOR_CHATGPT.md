# DayZero Project Context

## Current status

- **Major Architecture Refactor Complete (Multi-module + Hilt)**. The project has been split from a single large `:app` module into a maintainable layered module graph: `:app`, `:core:model`, `:core:domain`, `:core:database`, `:core:network`, `:core:data`, `:core:sync`, `:core:ui`, `:feature:ai-record`, `:feature:calendar`, and `:feature:trends`.
- **Hilt Dependency Injection Enabled**. `DayZeroApplication` is annotated with `@HiltAndroidApp`, `MainActivity` is an `@AndroidEntryPoint`, and `DayZeroViewModel` is now an `@HiltViewModel`. Manual dependency construction in the old `DayZeroViewModel.Factory` has been removed and replaced with constructor injection plus `DayZeroHiltModule`.
- **Module Ownership Boundaries**:
  - `:app` owns application startup, activity, navigation, and Hilt wiring.
  - `:core:model` owns pure Kotlin domain/UI state models.
  - `:core:domain` owns repository interfaces, intent/domain helpers, summaries, and use cases.
  - `:core:database` owns Room database, DAO, entity, and local mappers.
  - `:core:network` owns Retrofit/OkHttp/Supabase API, DTO, remote mappers, and streaming client.
  - `:core:data` owns repository implementations and telemetry.
  - `:core:sync` owns identity, sync queue/coordinators/gateways/health reporting.
  - `:core:ui` owns shared Compose theme, AI card components, feedback overlay, and sync UI.
  - `:feature:*` modules own screen-level Compose UI for AI Record, Calendar, and Trends.
- **ViewModel Scope Reduced**. `DayZeroViewModel` remains the shared app state holder for now, but dependencies are injected and clear/confirm flows have started moving into domain use cases. `ClearLocalDataUseCase` handles local cleanup policy and `ConfirmFoodRecordUseCase` handles `show_confirm_card(food_record)` persistence.
- **AI Record UI Decoupled**. AI Record screens no longer receive `DayZeroViewModel` directly. They use `AiRecordViewModel` for conversation history/detail state and an `AiRecordActionHandler` bridge for existing send/card/confirm actions. AI business card dispatch stays in `AssistantCardRenderer`, so new card types should be added there instead of expanding the main screen body.
- **Build Verification After Refactor**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` and `./gradlew test` pass after the module split and Hilt migration.
- **Local-First Sync Architecture (Phase 5) implemented**. Established local-first sync foundation for daily records, meals, food entries, and weight records using Room as the local source of truth.
- **Identity Layer & Anonymous Auth**: Added `CurrentIdentityProvider` and `CompositeIdentityProvider`. Implemented `SupabaseAnonymousIdentityProvider` which logs in anonymously and holds a `SupabaseAuthSession` so data can be synced to Supabase without requiring user manual login.
- **Supabase Remote Sync Gateway**: Added `SupabaseRemoteSyncGateway` which maps queued `SyncPayload` items and pushes them to Supabase via REST/PostgREST. Gracefully falls back to `NoopRemoteSyncGateway` if Supabase config is missing.
- **Remote Pull Implementation & Sync Lifecycle**: Added `PullCoordinator`, `PullStateStore`, and `SupabaseRemotePullGateway` to fetch updates from Supabase. Defined a strict manual sync order (Push -> Backfill -> Push -> Pull) and added comprehensive pull failure/recovery mechanisms, completing the two-way sync loop.
- **Supabase remote sync lifecycle & stability fixed**. Existing anonymous sessions now refresh with `/auth/v1/token?grant_type=refresh_token` before expiry instead of creating a new anonymous user. Refresh token rotation is persisted as a complete token pair. Temporary refresh failures pause sync without signup, and permanent refresh rejection blocks cloud sync instead of silently switching to a new `user_id`.
- **Debug Installation & Data Preservation Verified**: Verified that standard Android Studio deployments (`:app:installDebug`) safely preserve `SharedPreferences` (holding `local_owner_id`) and the Room database. A new safe script `scripts/install-debug-preserve-data.ps1` was added to standardize local installation without wiping data.
- **Data Persistence & Sync Recovery Verified**:
  - Overwriting installs preserve both local Room records and the Supabase `user_id`.
  - When a user explicitly clears local business records using the in-app debug menu (preserving identity), the `PullCoordinator` successfully restores the Calendar data from the Supabase backend.
  - As expected by the anonymous auth architecture, fully uninstalling the app or clearing storage via system settings permanently deletes the `local_owner_id`. A new anonymous `user_id` will be generated upon next launch, leaving the previous remote data orphaned. This is a known, expected limitation and will require a full account binding system in the future.
- **Supabase Schema Verification**: Added `docs/SUPABASE_SCHEMA_VERIFICATION.md` as the definitive checklist for the remote sync schema, RLS policies, and idempotency requirements.
- **UI Integration for Sync Status Completed**: Added `SyncStatusRepository` and UI components (`ui/sync/`) to observe and display the `SyncHealthSnapshot`. Integrated sync status indicators into `AiRecordScreen` and `TrendsScreen`. Also updated `SupabaseRemoteSyncGateway` to handle remote deletions.
- **Backfill & Sync Health Completed**: Fully implemented `BackfillCoordinator`, `BackfillStateStore`, and `SyncHealthReporter`. The system can now automatically discover unsynced historical records (`DailyRecordDao.getUnsyncedRecords`) and enqueue them, ensuring complete local-to-remote data consistency. Comprehensive testing added via `DayZeroSyncBackfillTest`.
- **Phase 6A Chat Sync Contract Complete**. Added remote schema migration and client DTO/contracts for future AI conversation sync. Remote table names are `ai_conversations` and `ai_chat_messages`. They use local UUIDs as remote primary keys, `user_id default auth.uid()`, strict owner-scoped RLS, soft-delete tombstones, composite message ownership FK, and database-controlled `server_updated_at` cursors. This phase does not implement Chat Push, Chat Pull, Chat Backfill, scheduler changes, UI changes, or a merge engine.
- **Phase 6B Chat Push + Backfill Implemented**. AI conversations and final chat messages now enqueue into the existing `sync_queue` with `UPSERT_AI_CONVERSATION` and `UPSERT_AI_CHAT_MESSAGE`. Room remains the immediate local source of truth. `SupabaseRemoteSyncGateway` pushes `ai_conversations` and `ai_chat_messages` through the existing anonymous Supabase session provider. `ChatBackfillCoordinator` scans existing local conversations first and messages second using `(createdAt, id)` pagination and skips empty assistant placeholders. Chat Pull, multi-device merge, chat deletion UI, account binding, and anonymous identity recovery after uninstall are still not implemented.
- **Phase 4D-1 Complete**: Real database writing for `show_confirm_card` (`food_record`) has been fully implemented on the client side, now supporting multiple meals (`meals[]`) and optional weight recording (`weightKg`).
- **Draft Card State Persistence Fix**: Resolved a critical bug where manually edited weight/meals on the draft card were reset in the UI once the card status transitioned to "confirmed". Now, the local UI state in `FoodDraftConfirmCard.kt` is keyed on `card.id` instead of `card.state` to prevent resets, and `updateCardState(...)` in `DayZeroViewModel.kt` persists the final user edits directly into the Room database chat history.
- **Weight Pre-population**: Configured the server-side normalization wrapper `normalizeActions()` to read `todayRecord` from the database and pre-populate `action.payload.weightKg` with the existing weight record in the database if the AI does not output a new weight.
- **Fast Fallback (15s Timeout)**: Reduced the Deno streaming fetch abort timeout in `assistant-turn-v2-stream` from **35 seconds** to **15 seconds**. If Kimi API hangs or suffers from high TTFT, Deno will abort after 15s, triggering immediate client fallback to the non-streaming `assistant-turn-v2` endpoint, saving 20 seconds of empty waiting time.
- **Kimi Latency Analysis**: Identified that high latency is 100% caused by Kimi (Moonshot API `kimi-k2.6`) response time and network routing between Supabase (outside China) and Moonshot (inside China). Deno edge function execution overhead is negligible (< 2ms).
- `assistant-turn-v2-stream` (Version 11) is the current primary AI runtime entrypoint. `assistant-turn-v2` (Version 18) remains as a compatibility fallback.
- Room chat persistence is fully enabled. User messages, AI replies, and cards are fully persistent. 
- **AI history conversation data foundation (Phase 1) complete**. Local Room now has a `conversations` table and every `ai_chat_messages` row belongs to a non-null `conversationId`. The database migration from version 9 to 10 safely groups the old single chat stream by device-local natural day, creates one legacy conversation per day with a stable UUID, and copies existing messages without changing message text, card payload JSON, card state, or ordering.
- **AI history UI (Phase 3) is implemented locally**. The AI tab now opens an AI home screen with a large first-message input and a Room-backed history list. Conversation detail is a second-level route that renders only the selected `conversationId` messages and hides the app bottom navigation bar.
- **Chat cloud runtime sync is partially implemented**. Phase 6B adds Push and Backfill only. No Chat Pull, deletion sync/UI, WorkManager-specific chat scheduler, UI behavior change, or multi-device merge engine has been added for conversations or chat messages.
- `show_confirm_card`, its prompt/action/payload contract, action normalization/parsing, multi-meal record writes, optional weight writes, Draft Card edit/confirm/cancel flow, `assistant-turn-v2-stream`, and `assistant-turn-v2` fallback remain unchanged by the conversation data foundation.
- **AI history local feature (Phase 4) complete**. Date mismatch guarding is now implemented for new `show_confirm_card(food_record)` cards. When a conversation's fixed `conversationDate` differs from the device-local date at card handling time, the client persists and renders a local system guard card before exposing the original record card.
- **Streaming Context Alignment (Phase 4 Streaming) complete**. Addressed an issue where AI replies did not stream incrementally on the new multi-conversation AI history UI. The transient streaming state is now mapped by `conversationId` and combined purely in memory within the `observeChatMessages` flow in the `AiDraftRepository`, bypassing Room for real-time `reply_delta` display. This ensures the conversational UI instantly updates with partial tokens per session, safely clearing state and merging with the database upon stream completion or fallback.
- **AI history conversation domain logic (Phase 2) complete**. New conversations and first user messages are created atomically through `CreateConversationWithFirstMessageUseCase` and the local chat repository. User messages, stream placeholders, final AI replies, fallback replies, card messages, card state updates, and local confirm/cancel feedback now carry an explicit `conversationId`.
- **AI context is conversation-scoped**. Client requests still keep the existing recent-message clipping size of 10, but now read those messages from the target `conversationId` instead of the compatibility all-message stream. No server prompt or API protocol was changed.
- **Async replies are pinned to the send-time conversation**. Each send/interaction captures an immutable target conversation id before network work starts, so stream completion and fallback update the original placeholder in that conversation even if later state points elsewhere.
- **Interaction results resolve their original conversation from persisted card messages**. The ViewModel looks up the message containing the clicked card id, then builds context and writes replies using that message's `conversationId`; it does not rely only on the current active conversation.
- **Feature-level AI conversation state is wired into visible UI**. `AiRecordViewModel` in `:feature:ai-record` exposes history state, selected conversation detail state, create-first-message state, `SavedStateHandle` conversation restoration, home input draft state, and one-shot creation events consumed by app navigation.
- **AI history visible UI (Phase 3) complete**. `AI_HOME` (`ai_record`) shows the large first-message input, empty/history states, and active conversations sorted by repository order. `AI_CONVERSATION/{conversationId}` shows the existing chat bubbles, streaming placeholder, existing input animation, and existing `AssistantCardRenderer` card UI for that conversation only.
- **Bottom navigation behavior**: AI home, Calendar, and Trends remain top-level pages with the app bottom navigation bar. Conversation detail is a second-level route and does not compose the bottom navigation bar, freeing the bottom space for the chat input. The detail input owns `imePadding()` plus `navigationBarsPadding()` so it follows the keyboard and system gesture area.
- **First-message flow**: home submit calls `CreateConversationWithFirstMessageUseCase` through `AiRecordViewModel`, navigates to detail on the one-shot creation event, then starts the existing assistant turn for the already-persisted first user message. This prevents duplicate first-message persistence.
- **Current concurrency policy**: the visible UI remains a single global generation surface. While `isAnalyzing` is true, the home input and detail input are disabled. Users may return to AI home while generation continues; replies are still persisted to the send-time conversation and are visible when reopening it. Multi-conversation simultaneous generation UI is not introduced.
- Still not implemented: Chat Pull, multi-device chat merge, history search, delete, rename, pinning, and AI-generated titles.
- **Launcher Double Icon Issue Resolved**. Fixed an issue where building/running the debug app installed duplicate launcher icons on the device. The root cause was that `feature/ai-record/src/debug/AndroidManifest.xml` incorrectly declared `androidx.activity.ComponentActivity` with `MAIN` and `LAUNCHER` intent-filters. This has been removed, preserving the registration of the activity for local Compose test rules while preventing duplicate launcher icons.

## Current Phase Features (Phase 4D-1 Complete)

- **Streaming Protocol**: Edge function `assistant-turn-v2-stream` streams `reply_delta` first, then emits final `reply + actions + debugTiming`. The client renders text with a typewriter-style display and renders cards only after final actions arrive.
- **Fallback Protocol**: Edge function `assistant-turn-v2` still returns `{"reply": "...", "actions": []}` and is used only when stream parsing/protocol fails.
- **Protocol Validation**: `RemoteAiAssistantRepository` validates the response structure. `show_confirm_card` expects the new `meals` array but falls back to older structures if `meals` is missing.
- **Turn Type**: Requests support `turnType = "user_message"` (for normal chat) and `turnType = "interaction_result"` (when clicking card options).
- **Tools Supported**: Currently allows `debug_show_choice_card`, `ask_record_intent_card`, `ask_missing_info_card`, and `show_confirm_card`.
- **Interaction Result**: Clicking intermediate cards (like `ask_record_intent_card` or `ask_missing_info_card`) posts `interaction_result` back to `assistant-turn-v2`. Clicking `show_confirm_card` bypasses the network and is handled exclusively by the client to write to the local database.

## Old runtime chain (Legacy)

- The old intent-routing chain remains completely disconnected and must not be restored:
  - `HybridIntentRouter`
  - `LocalIntentRouter`
  - `IntentClassifierRepository`
  - `classify-user-intent`
  - `generate-checkin-draft`
  - `AiCompanionReplyRepository`
  - `AiSummaryRepository`

Note: Several legacy interfaces/classes still exist in migrated modules for compatibility and tests, but the production AI record path must continue to use `assistant-turn-v2-stream` with `assistant-turn-v2` only as the explicit fallback.

## Logging

- `DayZeroAiV2: send message`
- `DayZeroAiV2: assistant-turn-v2 start`
- `DayZeroAiV2: assistant-turn-v2 success`
- `DayZeroAiV2: assistant-turn-v2 error`
- `DayZeroAiV2: action parse start / success`
- `DayZeroAiV2: interaction_result created / send to assistant-turn-v2`
- `DayZeroAiV2: confirm food card clicked confirm / cancel`
- `DayZeroAiV2: food record save start / success / error`
- `DayZeroAiV2: confirm card state updated confirmed / cancelled`

## Supabase

- Project: `sybenxmxnwwtlvkeojtj` (`DayZero`)
- Phase 6A chat tables: `ai_conversations`, `ai_chat_messages`
- Chat server cursor: `server_updated_at` plus `id` as stable secondary cursor
- Chat card JSON: full `assistantCardsJson` is stored in `ai_chat_messages.assistant_cards` as `jsonb`; null and `[]` are distinct
- Phase 6A deployment status: applied to Supabase project `sybenxmxnwwtlvkeojtj` via MCP on 2026-06-21. Static schema/RLS/grant/index/trigger verification was read back from the project.
- Phase 6B RLS probe status: two real anonymous authenticated sessions verified A-owned conversation insert/read/update, B isolation from A rows, B message attach rejection with HTTP 403, and unauthenticated rejection with HTTP 401. The separate `user_id` mutation probe and hard DELETE probe were also verified with a local powershell script using the anon key: cross-user updates and hard deletes returned HTTP 403, and management readbacks by User A confirmed the data remained safely owned by User A. The probe row was tombstoned.
- Phase 6B Push verification status: Real-device verification of Chat Push has been successfully completed. Verified that new conversations and final messages are successfully pushed. No placeholders or `reply_delta` messages are uploaded to Supabase. Card payload is saved as native JSONB without double-encoding. Card status updates reuse the same message ID without duplicate row generation. After app restart and repeated backfill sync execution, remote table rows remain stable (conversations = 3, messages = 16) with no duplicates. Chat push is triggered automatically in the background by the existing SyncScheduler. Chat Pull is still not implemented.
- Primary Edge Function: `assistant-turn-v2-stream` (Version 11, timeout=15s)
- Fallback Edge Function: `assistant-turn-v2` (Version 18)
- Retired Edge Function: `ai-assistant-turn` should stay deleted/unused
- Remote status: `ACTIVE`
- Stream prompt version: `stream_compact_v1`
- `verify_jwt=false`

## Architecture reference

- AI architecture reference is `docs/AI_ASSISTANT_TURN_V2_ARCHITECTURE.md`.
- Data sync architecture reference is `docs/DATA_SYNC_ARCHITECTURE.md`.
- Current code architecture is now multi-module and Hilt-based. Future changes should respect module boundaries: UI/feature modules must not depend directly on Room DAO, Retrofit services, Supabase gateways, or sync coordinators; domain/use cases must not depend on Compose, Android UI, Room entities, or remote DTOs.
- Future AI history refinements must keep DayZero's current visual language, rounded corners, spacing, typography, motion, and fresh style. Reuse existing components and theme; do not drop in generic Material sample pages or introduce a mismatched design system.
- Next sync step is Phase 6C: Chat Pull plus Merge. General architecture work can continue narrowing `DayZeroViewModel` into feature-specific state holders.

### Phase 6A Chat Sync Contract

- Remote schema source: `supabase/migrations/20260621060000_dayzero_ai_chat_sync_schema.sql`.
- Verification SQL: `supabase/verification/20260621060000_verify_ai_chat_sync_schema.sql`.
- Design doc: `docs/CHAT_SYNC_ARCHITECTURE.md`.
- Client contract models: `ChatSyncConversationSnapshot`, `ChatSyncMessageSnapshot`, and `ChatSyncServerCursor` in `:core:model`.
- Network DTOs: `RemoteConversationDto`, `RemoteAiChatMessageDto`, and `RemoteChatSyncMapper` in `:core:network`.
- Queue constants: `ChatSyncQueueContract` in `:core:sync`; Phase 6B wires conversation/message upsert operations into `SyncPayloadParser`, `LocalFirstSyncCoordinator`, and scheduler-driven Chat Backfill. Pull remains intentionally unwired.
- RLS rule: rows are visible/mutable only when `auth.uid() = user_id`.
- Message ownership rule: `ai_chat_messages(conversation_id, user_id)` references `ai_conversations(id, user_id)`, preventing orphan and cross-owner message attachment.
- Server cursor rule: future chat Pull must page by `(server_updated_at, id)`, not by client/business `updated_at` alone.
- Synced state: conversation fixed date/title/preview/timestamps/tombstone, final user messages, final assistant messages, full assistant card JSON, card edits, confirmed/cancelled state, and date guard pending/approved/cancelled state.
- Unsynced state: `reply_delta`, `StreamingState`, `isAnalyzing`, typewriter progress, input drafts, selected route, `activeConversationId`, keyboard/Compose temporary state, and transient network errors.
- Formal login is still not implemented. Uninstall or system clear-data still loses the anonymous Supabase identity and cannot recover old anonymous-owned remote data.

### Phase 6B Chat Push + Backfill

- Queue operations: `UPSERT_AI_CONVERSATION` (`entityType = ai_conversation`) and `UPSERT_AI_CHAT_MESSAGE` (`entityType = ai_chat_message`).
- Enqueue timing: conversation insert/summary/activity/tombstone changes; user final messages immediately; assistant final messages only after `completeAssistantMessage(...)` persists final text/cards; card edit/confirm/cancel and date guard approve/cancel update and re-enqueue the same message id.
- Queue behavior: pending/retry/waiting items coalesce by owner, entity type, entity id, and operation. If an old snapshot is already processing, later local changes leave a new pending item.
- Parent order: `UPSERT_AI_CONVERSATION` is ordered before `UPSERT_AI_CHAT_MESSAGE`; message HTTP 409 can re-enqueue its parent conversation and remains retryable.
- Backfill: `ChatBackfillCoordinator` scans conversations before messages with stable `(createdAt, id)` pagination, persists progress in `ChatBackfillStateStore`, and skips empty assistant placeholders.
- Synced chat state: fixed conversation date, title, preview, timestamps, tombstones, final user/assistant messages, full assistant card JSON, edited/confirmed/cancelled cards, and date guard state.
- Unsynced chat state: `reply_delta`, `StreamingState`, `isAnalyzing`, typewriter progress, input drafts, active route/conversation UI state, keyboard/Compose state, and transient network errors.
- Chat Pull and multi-device merge remain unimplemented. Formal login remains unimplemented. Uninstall/system clear-data still loses anonymous identity recovery.

### AI History & Conversation Foundation (Phases 1, 2, 3 & 4 Technical Details)

To support multiple chat histories, the database schema, domain layer, and view models have been updated to isolate chat sessions.

### 1. Data Models & Database Entities
- **[Conversation](file:///D:/Goings/APPProjects/DayZero/core/model/src/main/java/com/example/domain/model/ai/Conversation.kt)** (in `:core:model`):
  Pure domain model representing a chat session.
  ```kotlin
  data class Conversation(
      val id: String = UUID.randomUUID().toString(),
      val conversationDate: LocalDate,
      val title: String,
      val lastMessagePreview: String,
      val createdAt: Long = System.currentTimeMillis(),
      val updatedAt: Long = createdAt,
      val lastActivityAt: Long = createdAt,
      val deletedAt: Long? = null
  )
  ```
- **[ConversationEntity](file:///D:/Goings/APPProjects/DayZero/core/database/src/main/java/com/example/data/local/entity/ConversationEntity.kt)** (in `:core:database`):
  Room entity mapped to the `conversations` table. Has indices on `conversationDate` and `lastActivityAt`.
- **[AiChatMessageEntity](file:///D:/Goings/APPProjects/DayZero/core/database/src/main/java/com/example/data/local/entity/AiChatMessageEntity.kt)** (in `:core:database`):
  Modified to add `conversationId: String` which has a foreign key constraint referencing `conversations(id)` with `ON DELETE CASCADE`. Indexes are added for `conversationId` and `(conversationId, createdAt)`.

### 2. Room Migration (9 -> 10)
Implemented in **[DayZeroDatabase](file:///D:/Goings/APPProjects/DayZero/core/database/src/main/java/com/example/data/local/database/DayZeroDatabase.kt)**, the migration safely ports existing single-stream chat records into grouped conversations:
- **Group by Date**: Queries all existing `ai_chat_messages` and groups them by natural date using the device's system default timezone (`ZoneId.systemDefault()`).
- **Stable UUID Generation**: For each date group, a stable conversation UUID is generated deterministically via:
  `UUID.nameUUIDFromBytes("dayzero-legacy-ai-chat-${'$'}date".toByteArray(StandardCharsets.UTF_8)).toString()`
  This prevents duplicate conversations if migration/re-entry happens.
- **Title and Preview Extrapolations**:
  - The conversation **title** is extracted from the first user message in that date's group (truncated to a maximum of 32 characters), falling back to a neutral title (e.g., `6月18日的对话`) if no user text is found.
  - The conversation **preview text** is set to the last non-empty message text or `"这条对话包含一张记录卡片"` if it only contains interactive/checkin cards.
- **Orphan Prevention**: After inserting conversations and creating the new `ai_chat_messages` table with the foreign key constraint, the migration checks that no messages are left with a null/orphaned `conversationId`.

### 3. DAO & Repository Layer API
- **[ConversationDao](file:///D:/Goings/APPProjects/DayZero/core/database/src/main/java/com/example/data/local/dao/ConversationDao.kt)**:
  Exposes queries to insert, fetch by ID, observe all active conversations sorted by `lastActivityAt DESC, createdAt DESC`, update summary titles/previews, and soft delete.
- **[ConversationRepository](file:///D:/Goings/APPProjects/DayZero/core/domain/src/main/java/com/example/domain/repository/ConversationRepository.kt)** & **[RoomConversationRepository](file:///D:/Goings/APPProjects/DayZero/core/data/src/main/java/com/example/data/repository/RoomConversationRepository.kt)**:
  Domain repository interface and Room-backed implementation for managing conversations.
- **[AiDraftRepository](file:///D:/Goings/APPProjects/DayZero/core/domain/src/main/java/com/example/domain/repository/AiDraftRepository.kt)**:
  Expanded to support conversation-scoped operations:
  - `fun observeChatMessages(conversationId: String): Flow<List<AiChatMessage>>`: Observes messages belonging to a specific conversation.
  - `suspend fun createConversationWithFirstMessage(text: String, now: Long): String?`: Atomically inserts a new conversation and its first user message in a single database transaction.
  - `suspend fun getRecentChatMessages(conversationId: String, limit: Int): List<AiChatMessage>`: Fetches the recent messages for conversation context extraction.
  - `suspend fun findMessageByAssistantCardId(cardId: String): AiChatMessage?`: Looks up the chat message containing the card ID to route interactions correctly.
  - `suspend fun insertChatMessage(conversationId: String, message: AiChatMessage)`: Inserts a message in the designated conversation and updates its preview summary.
- **[RemoteAiDraftRepository](file:///D:/Goings/APPProjects/DayZero/core/data/src/main/java/com/example/data/repository/RemoteAiDraftRepository.kt)**:
  Now accepts the full `database` instance to support safe, multi-table transactions (`database.withTransaction {}`). Both `createConversationWithFirstMessage` and `clearChatMessages` are executed transactionally.

### 4. Use Cases & ViewModels
- **[CreateConversationWithFirstMessageUseCase](file:///D:/Goings/APPProjects/DayZero/core/domain/src/main/java/com/example/domain/usecase/CreateConversationWithFirstMessageUseCase.kt)** (in `:core:domain`):
  Validates user input text and delegates new conversation creation to the repository layer.
- **[AiRecordViewModel](file:///D:/Goings/APPProjects/DayZero/feature/ai-record/src/main/java/com/example/ui/screens/AiRecordViewModel.kt)** (in `:feature:ai-record`):
  Exposes reactive state objects:
  - `AiConversationHistoryState`: Holds active conversations list, sorted by `lastActivityAt DESC`.
  - `AiConversationDetailState`: Holds current conversation details and message list.
  - State restoration: Uses `SavedStateHandle` to preserve and restore `conversationId` across process death.
  - Creation events: Exposes `events: SharedFlow<AiRecordConversationEvent>` to signal successful conversation initiation to the UI layer.
- **[DayZeroViewModel](file:///D:/Goings/APPProjects/DayZero/app/src/main/java/com/example/DayZeroViewModel.kt)**:
  - Tracks `activeConversationId` in `AppState` and includes it in all outgoing client messages.
  - Pinning for Asynchronous Streams: During network call initiation, the target conversation ID is captured to ensure streaming/fallback updates write back to the original placeholder even if the user switches active conversations mid-stream.
  - Interaction Routing: Option clicks and confirm/cancel actions retrieve the original conversation ID via `findMessageByAssistantCardId(interactionId)` to guarantee that database records are updated in the correct conversation thread.

### 5. Testing & Verification
- **[DayZeroConversationMigrationTest](file:///D:/Goings/APPProjects/DayZero/app/src/test/java/com/example/DayZeroConversationMigrationTest.kt)** (Phase 1):
  Verifies Room database migration 9->10, Natural Day grouping, UUID stability, and legacy detail preservation.
- **[DayZeroConversationPhase2Test](file:///D:/Goings/APPProjects/DayZero/app/src/test/java/com/example/DayZeroConversationPhase2Test.kt)** (Phase 2):
  Verifies:
  - Transactional atomicity of new conversation creation.
  - Isolation of contextual recent messages by `conversationId`.
  - Continuation updates to conversation previews and last activity timestamps.
  - Asynchronous reply flows and card interaction events pinning back to their original conversations.
  - Feature-level `AiRecordViewModel` state emission, observation, and saved state restoration.
  - Phase 3 regression: starting the assistant for an already-created first user message does not duplicate that user message.

### 6. AI History UI Integration (Phase 3)
- **Navigation**:
  - `Screen.AiRecord.route` (`ai_record`) is the AI home top-level tab.
  - `ai_conversation/{conversationId}` is the second-level conversation detail route.
  - Calendar and Trends routes are unchanged.
- **Home UI**:
  - `AiRecordHomeScreen` renders the large first-message input, history title, empty state, and conversation rows.
  - Sending from home updates `AiRecordViewModel` home draft state and calls `submitHomeInput()`. Blank text is rejected and repeated clicks while `isCreating` is true are ignored.
  - On `ConversationCreated(conversationId, firstMessageText)`, app navigation opens detail and calls `DayZeroViewModel.startAssistantTurnForExistingUserMessage(...)`, so the first user message is not inserted twice.
- **Detail UI**:
  - `AiConversationScreen` receives the route `conversationId`, calls `AiRecordViewModel.openConversation(conversationId)`, and renders `AiConversationDetailState.messages`.
  - It does not use the compatibility all-message stream for visible chat content.
  - Existing chat bubbles, stream placeholder behavior, `AssistantCardRenderer`, card clicks, confirm/cancel, and `FoodDraftConfirmCard` remain reused.
- **Insets and bottom bar**:
  - App bottom navigation is only composed for top-level routes. It is not composed for `ai_conversation/{conversationId}`.
  - The detail input keeps the existing plus/input fusion animation and uses `imePadding()` and `navigationBarsPadding()` so the input tracks the keyboard and system gesture area.
- **Tests**:
  - `AiRecordPhase3Test` in `:feature:ai-record` covers history observation, blank rejection, duplicate create prevention, one-shot creation event, home input clearing, detail conversation isolation/restoration, home/detail Compose rendering, card rendering through the existing renderer, and disabled send state during generation.

### 7. Date Mismatch Guard & Conversation-Date Record Binding (Phase 4)
- **Local-only system card**:
  - `DateMismatchGuardCardPayload` is a client-side card model, not an AI tool and not a server action.
  - It is persisted inside the existing `assistantCardsJson` message JSON as `date_mismatch_guard_card`.
  - The original `show_confirm_card` is preserved unchanged as `pendingOriginalCard`, including its original card id, action payload, meals, weight, and state.
- **Insertion point**:
  - `DayZeroViewModel.completeAssistantMessage(...)` receives parsed AI cards from `assistant-turn-v2-stream` or the `assistant-turn-v2` fallback.
  - Before the final assistant placeholder is updated in Room, the ViewModel compares the message's owning `conversation.conversationDate` with `CurrentDateProvider.currentDate()`.
  - Matching dates keep the original card list unchanged. Mismatched past or future dates wrap only `show_confirm_card(confirmType=food_record)` cards in a pending guard.
- **User decisions**:
  - Pending guard cards render in `AssistantCardRenderer`, using DayZero's existing card styling.
  - "Continue recording" changes the guard state from `pending` to `approved`; the renderer then shows the embedded original `FoodDraftConfirmCard` exactly once.
  - "Cancel" changes the guard state from `pending` to `cancelled`; the original record card remains hidden and no food/weight record is written.
  - State transitions are idempotent and only allow `pending -> approved` or `pending -> cancelled`.
- **Record date source**:
  - Final confirm/cancel actions look up the message containing the original card id, including cards nested inside a date guard.
  - Record writes use `conversationRepository.getConversationById(message.conversationId).conversationDate`.
  - `LocalDate.now()`, AI payload date fields, active UI conversation state, and route state do not decide the final `DailyRecord` natural date.
  - Current-date conversations still behave like before because no guard is inserted and the conversation date equals the device-local date.
- **Compatibility**:
  - Existing historical unwrapped `show_confirm_card` messages are not rewritten.
  - If a user confirms an old unwrapped card now, the save path still resolves its owning `conversationId` and writes food, meals, and weight to that conversation's fixed date.
  - Non-confirm cards (`ask_record_intent_card`, `ask_missing_info_card`, debug choice cards, and other card types) are not intercepted.
- **Tests**:
  - `DayZeroDateMismatchGuardTest` covers same-date pass-through, past/future mismatch guard insertion, approve/cancel idempotency, no-network guard decisions, conversation-date food/meal/weight writes after page state changes, and old unwrapped card compatibility.
  - `AiRecordPhase3Test` includes feature-level Compose coverage for pending/approved/cancelled guard rendering.
- **Cloud sync status**:
  - Chat/conversation cloud sync is still not implemented.
  - Supabase schema, Edge Functions, record sync queue/backfill/pull, and existing food/weight sync remain unchanged.

## Troubleshooting: Double Application / Launcher Icon Issue

### Phenomenon
When starting DayZero from the development environment, the device appeared to install two application packages simultaneously. On the launcher screen, two icons for the application were visible. Uninstalling either icon deleted both from the device, indicating they shared the same package/application namespace.

### Root Cause
During debug builds, Gradle merges the manifests from all dependent modules. The debug-specific manifest `feature/ai-record/src/debug/AndroidManifest.xml` (introduced to register `ComponentActivity` for local Compose test rules) incorrectly included the following intent-filter under `androidx.activity.ComponentActivity`:
```xml
<intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LAUNCHER" />
</intent-filter>
```
This configuration caused `ComponentActivity` to register as a launcher activity inside the final debug APK. As a result, the Android OS created two launcher icons on the system home screen for the single application package (`com.aistudio.dayzero.djwqop`).

### Fix
- The `<intent-filter>` block has been removed from `androidx.activity.ComponentActivity` in `feature/ai-record/src/debug/AndroidManifest.xml`.
- The activity itself remains registered to ensure Compose test rules and Robolectric/device tests function correctly without warning/failure.
- Running the ordinary run configuration now only creates a single launcher icon for the app.
- To clean up any stale launcher state, run:
  ```powershell
  adb uninstall com.aistudio.dayzero.djwqop
  ```
  And then reinstall the app normally.

## Resolved Issues

### AI Reply Streaming (Phase 4E)
- **Problem**: AI replies were not displaying incrementally (streaming), but instead appearing all at once at the end of the request.
- **Root Cause**: The introduction of the multi-conversation UI shifted message observation to Room (filtered by `conversationId`). However, streaming tokens were only updating a global/legacy state which Room was not reflecting.
- **Resolution**: Implemented conversation-isolated in-memory streaming state (`StreamingReplyState`). The detail screen maps this in-memory transient state along with Room-persisted messages. The database is only updated once with the final completed assistant message (including its final text and cards).
- **Key Architectures Retained**:
  - Streaming delta uses conversationId/messageId isolated in-memory state.
  - Final message is persisted once to Room upon final response/completion.
  - Bypasses writing every token to Room database.
  - Stream display is now fully functional and stable.
