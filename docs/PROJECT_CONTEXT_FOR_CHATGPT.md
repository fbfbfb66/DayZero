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
- **End-to-End Sync Verification & Schema Alignment**: Refined Remote Pull DTO mapping to strictly align with canonical remote schema fields in `SupabaseRemotePullGateway`. Updated `docs/SUPABASE_SCHEMA_VERIFICATION.md` with explicit Pull DTO field mappings, canonical field names, and SQL deployment procedures, finalizing full-loop sync data fidelity on real devices.
- **Data Clearing & Cloud Reset Helpers**: Added `SupabaseCloudBackupCleaner` and expanded options in `AiRecordScreen` to allow granular clearing of Chat Messages, Local Records, All Local Data, or Cloud Backup (Debug). This facilitates testing of sync backfill and remote pull capabilities.
- **Network Dispatcher Optimization**: Wrapped Supabase remote sync and pull requests in `Dispatchers.IO` within `SupabaseRemoteSyncGateway` and `SupabaseRemotePullGateway` to prevent blocking the main thread during network operations.
- **Offline Retry Optimization Completed**: Implemented `RetryPolicy` with exponential backoff and `SyncScheduler` to manage pending sync operations efficiently and reliably.
- **Supabase Schema Verification**: Added `docs/SUPABASE_SCHEMA_VERIFICATION.md` as the definitive checklist for the remote sync schema, RLS policies, and idempotency requirements.
- **UI Integration for Sync Status Completed**: Added `SyncStatusRepository` and UI components (`ui/sync/`) to observe and display the `SyncHealthSnapshot`. Integrated sync status indicators into `AiRecordScreen` and `TrendsScreen`. Also updated `SupabaseRemoteSyncGateway` to handle remote deletions.
- **Backfill & Sync Health Completed**: Fully implemented `BackfillCoordinator`, `BackfillStateStore`, and `SyncHealthReporter`. The system can now automatically discover unsynced historical records (`DailyRecordDao.getUnsyncedRecords`) and enqueue them, ensuring complete local-to-remote data consistency. Comprehensive testing added via `DayZeroSyncBackfillTest`.
- **Phase 4D-1 Complete**: Real database writing for `show_confirm_card` (`food_record`) has been fully implemented on the client side, now supporting multiple meals (`meals[]`) and optional weight recording (`weightKg`).
- **Draft Card State Persistence Fix**: Resolved a critical bug where manually edited weight/meals on the draft card were reset in the UI once the card status transitioned to "confirmed". Now, the local UI state in `FoodDraftConfirmCard.kt` is keyed on `card.id` instead of `card.state` to prevent resets, and `updateCardState(...)` in `DayZeroViewModel.kt` persists the final user edits directly into the Room database chat history.
- **Weight Pre-population**: Configured the server-side normalization wrapper `normalizeActions()` to read `todayRecord` from the database and pre-populate `action.payload.weightKg` with the existing weight record in the database if the AI does not output a new weight.
- **Fast Fallback (15s Timeout)**: Reduced the Deno streaming fetch abort timeout in `assistant-turn-v2-stream` from **35 seconds** to **15 seconds**. If Kimi API hangs or suffers from high TTFT, Deno will abort after 15s, triggering immediate client fallback to the non-streaming `assistant-turn-v2` endpoint, saving 20 seconds of empty waiting time.
- **Kimi Latency Analysis**: Identified that high latency is 100% caused by Kimi (Moonshot API `kimi-k2.6`) response time and network routing between Supabase (outside China) and Moonshot (inside China). Deno edge function execution overhead is negligible (< 2ms).
- `assistant-turn-v2-stream` (Version 11) is the current primary AI runtime entrypoint. `assistant-turn-v2` (Version 18) remains as a compatibility fallback.
- Room chat persistence is fully enabled. User messages, AI replies, and cards are fully persistent. 
- **AI history conversation data foundation (Phase 1) complete**. Local Room now has a `conversations` table and every `ai_chat_messages` row belongs to a non-null `conversationId`. The database migration from version 9 to 10 safely groups the old single chat stream by device-local natural day, creates one legacy conversation per day with a stable UUID, and copies existing messages without changing message text, card payload JSON, card state, or ordering.
- **AI history UI (Phase 3) is implemented locally**. The AI tab now opens an AI home screen with a large first-message input and a Room-backed history list. Conversation detail is a second-level route that renders only the selected `conversationId` messages and hides the app bottom navigation bar.
- **Chat cloud sync is not implemented yet**. No Supabase table, Edge Function, Push/Pull/Backfill path, or sync queue behavior was added for conversations or chat messages.
- `show_confirm_card`, its prompt/action/payload contract, action normalization/parsing, multi-meal record writes, optional weight writes, Draft Card edit/confirm/cancel flow, `assistant-turn-v2-stream`, and `assistant-turn-v2` fallback remain unchanged by the conversation data foundation.
- **AI history local feature (Phase 4) complete**. Date mismatch guarding is now implemented for new `show_confirm_card(food_record)` cards. When a conversation's fixed `conversationDate` differs from the device-local date at card handling time, the client persists and renders a local system guard card before exposing the original record card.
- **AI history conversation domain logic (Phase 2) complete**. New conversations and first user messages are created atomically through `CreateConversationWithFirstMessageUseCase` and the local chat repository. User messages, stream placeholders, final AI replies, fallback replies, card messages, card state updates, and local confirm/cancel feedback now carry an explicit `conversationId`.
- **AI context is conversation-scoped**. Client requests still keep the existing recent-message clipping size of 10, but now read those messages from the target `conversationId` instead of the compatibility all-message stream. No server prompt or API protocol was changed.
- **Async replies are pinned to the send-time conversation**. Each send/interaction captures an immutable target conversation id before network work starts, so stream completion and fallback update the original placeholder in that conversation even if later state points elsewhere.
- **Interaction results resolve their original conversation from persisted card messages**. The ViewModel looks up the message containing the clicked card id, then builds context and writes replies using that message's `conversationId`; it does not rely only on the current active conversation.
- **Feature-level AI conversation state is wired into visible UI**. `AiRecordViewModel` in `:feature:ai-record` exposes history state, selected conversation detail state, create-first-message state, `SavedStateHandle` conversation restoration, home input draft state, and one-shot creation events consumed by app navigation.
- **AI history visible UI (Phase 3) complete**. `AI_HOME` (`ai_record`) shows the large first-message input, empty/history states, and active conversations sorted by repository order. `AI_CONVERSATION/{conversationId}` shows the existing chat bubbles, streaming placeholder, existing input animation, and existing `AssistantCardRenderer` card UI for that conversation only.
- **Bottom navigation behavior**: AI home, Calendar, and Trends remain top-level pages with the app bottom navigation bar. Conversation detail is a second-level route and does not compose the bottom navigation bar, freeing the bottom space for the chat input. The detail input owns `imePadding()` plus `navigationBarsPadding()` so it follows the keyboard and system gesture area.
- **First-message flow**: home submit calls `CreateConversationWithFirstMessageUseCase` through `AiRecordViewModel`, navigates to detail on the one-shot creation event, then starts the existing assistant turn for the already-persisted first user message. This prevents duplicate first-message persistence.
- **Current concurrency policy**: the visible UI remains a single global generation surface. While `isAnalyzing` is true, the home input and detail input are disabled. Users may return to AI home while generation continues; replies are still persisted to the send-time conversation and are visible when reopening it. Multi-conversation simultaneous generation UI is not introduced.
- Still not implemented: chat/conversation cloud sync, history search, delete, rename, pinning, and AI-generated titles.

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
- Next step is to continue narrowing `DayZeroViewModel` into feature-specific state holders (`AiRecordViewModel`, Calendar/Trends state holders) and to add focused unit/UI tests around the extracted use cases and card renderer.

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
