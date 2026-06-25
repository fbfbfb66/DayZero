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
- **Identity Layer & Fixed Development Auth**: Added `CurrentIdentityProvider` and `CompositeIdentityProvider`. The current Hilt production path wires `SupabaseFixedPasswordIdentityProvider` with `FixedDevelopmentAccountCredentialsProvider`, exposes it as `SupabaseAuthSessionProvider`, and rejects anonymous or unexpected-user stored sessions. `SupabaseAnonymousIdentityProvider` remains in source/history but is not the current Hilt-provided remote identity path.
- **Supabase Remote Sync Gateway**: Added `SupabaseRemoteSyncGateway` which maps queued `SyncPayload` items and pushes them to Supabase via REST/PostgREST. Gracefully falls back to `NoopRemoteSyncGateway` if Supabase config is missing.
- **Remote Pull Implementation & Sync Lifecycle**: Added `PullCoordinator`, `PullStateStore`, and `SupabaseRemotePullGateway` to fetch updates from Supabase. Defined a strict manual sync order (Push -> Backfill -> Push -> Pull) and added comprehensive pull failure/recovery mechanisms, completing the two-way sync loop.
- **Supabase remote sync lifecycle & stability fixed**. Existing anonymous sessions now refresh with `/auth/v1/token?grant_type=refresh_token` before expiry instead of creating a new anonymous user. Refresh token rotation is persisted as a complete token pair. Temporary refresh failures pause sync without signup, and permanent refresh rejection blocks cloud sync instead of silently switching to a new `user_id`.
- **Fixed Development Account Migration (2026-06-25)**. Added `user_profiles` for non-anonymous Supabase Auth users, fixed development email/password credentials, and `RemoteIdentityBindingCoordinator` so local sync/backfill/pull cursors reset when the bound remote user changes.
- **Debug Installation & Data Preservation Verified**: Verified that standard Android Studio deployments (`:app:installDebug`) safely preserve `SharedPreferences` (holding `local_owner_id`) and the Room database. A new safe script `scripts/install-debug-preserve-data.ps1` was added to standardize local installation without wiping data.
- **Data Persistence & Sync Recovery Verified**:
  - Overwriting installs preserve both local Room records and the Supabase `user_id`.
  - When a user explicitly clears local business records using the in-app debug menu (preserving identity), the `PullCoordinator` successfully restores the Calendar data from the Supabase backend.
  - Historical anonymous-auth limitation: fully uninstalling the app or clearing storage via system settings permanently deleted the `local_owner_id`; under the current fixed development auth path, the remote Supabase user is expected to remain the configured fixed account when credentials are available.
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
- `assistant-turn-v2-stream` (Version 12) is the current primary AI runtime entrypoint. `assistant-turn-v2` (Version 21) remains as a compatibility fallback.
- Room chat persistence is fully enabled. User messages, AI replies, and cards are fully persistent.
- **AI history conversation data foundation (Phase 1) complete**. Local Room now has a `conversations` table and every `ai_chat_messages` row belongs to a non-null `conversationId`. The database migration from version 9 to 10 safely groups the old single chat stream by device-local natural day, creates one legacy conversation per day with a stable UUID, and copies existing messages without changing message text, card payload JSON, card state, or ordering.
- **AI history UI (Phase 3) is implemented locally**. The AI tab now opens an AI home screen with a large first-message input and a Room-backed history list. Conversation detail is a second-level route that renders only the selected `conversationId` messages and hides the app bottom navigation bar.
- **Chat cloud runtime sync is production-wired and real-device verified through Phase 6D**. Phase 6B adds Push and Backfill. Phase 6C adds remote Pull transport and local Merge. Phase 6D adds production Chat Pull lifecycle integration, Scheduler, Hilt, and Health Reporting, with ownership migration, real-device Pull restore, idempotency, restart stability, and UI/card rendering verified on 2026-06-21. Chat deletion UI, history search, rename, pinning, formal login/account binding, and anonymous identity recovery after uninstall are still not implemented.
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
- Still not implemented: multi-device lifecycle orchestration, history search, delete, rename, pinning, and AI-generated titles.
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
- Phase 6B Push verification status: Real-device verification of Chat Push has been successfully completed. Verified that new conversations and final messages are successfully pushed. No placeholders or `reply_delta` messages are uploaded to Supabase. Card payload is saved as native JSONB without double-encoding. Card status updates reuse the same message ID without duplicate row generation. After app restart and repeated backfill sync execution, remote table rows remain stable (conversations = 3, messages = 16) with no duplicates. Chat push is triggered automatically in the background by the existing SyncScheduler. Phase 6D Chat Pull real-device recovery verification is also complete.
- Primary Edge Function: `assistant-turn-v2-stream` (Version 12, timeout=15s)
- Fallback Edge Function: `assistant-turn-v2` (Version 21)
- Retired Edge Function: `ai-assistant-turn` should stay deleted/unused
- Remote status: `ACTIVE`
- Remote current prompt versions: `assistant-turn-v2-stream` uses `stream_compact_v2`; `assistant-turn-v2` uses `compact_v3_timing`.
- The 2026-06-26 nutrition Edge Function deployment is complete. There is no longer a local-only pending Edge Function prompt version for these two functions.
- `verify_jwt=false`

## Sync Architecture

- **Pull-based sync**: DayZero uses a pull-based sync engine, coordinated locally via `PullCoordinator`.
- **Separate Chat vs Business Record pipelines**: The sync process is strictly separated into Daily Record (business record) sync and Chat (AI Conversation) sync.
- Chat push happens immediately via `ChatSyncQueueWriter`, while business record push uses `SyncQueueWriter`.
- Backfill scans for missing items and populates the sync queue.

## Architecture reference

- AI architecture reference is `docs/AI_ASSISTANT_TURN_V2_ARCHITECTURE.md`.
- Data sync architecture reference is `docs/DATA_SYNC_ARCHITECTURE.md`.
- Chat sync architecture reference is `docs/CHAT_SYNC_ARCHITECTURE.md`.
- Current code architecture is now multi-module and Hilt-based. Future changes should respect module boundaries: UI/feature modules must not depend directly on Room DAO, Retrofit services, Supabase gateways, or sync coordinators; domain/use cases must not depend on Compose, Android UI, Room entities, or remote DTOs.
- Future AI history refinements must keep DayZero's current visual language, rounded corners, spacing, typography, motion, and fresh style. Reuse existing components and theme; do not drop in generic Material sample pages or introduce a mismatched design system.
- Phase 6D-1 complete: `ChatPullCoordinator` implemented for production lifecycle orchestration, wiring conversation and message pulls sequentially with strict single-flight blocking. Phase 6D-2 & 6D-3 complete: True `SyncScheduler` / Hilt wiring, Health reporting, and end-to-end testing are implemented. Real-device Chat Pull verification completed successfully (2026-06-21).

### Phase 6A Chat Sync Contract

- Remote schema source: `supabase/migrations/20260621060000_dayzero_ai_chat_sync_schema.sql`.
- Verification SQL: `supabase/verification/20260621060000_verify_ai_chat_sync_schema.sql`.
- Design doc: `docs/CHAT_SYNC_ARCHITECTURE.md`.
- Client contract models: `ChatSyncConversationSnapshot`, `ChatSyncMessageSnapshot`, and `ChatSyncServerCursor` in `:core:model`.
- Network DTOs: `RemoteConversationDto`, `RemoteAiChatMessageDto`, and `RemoteChatSyncMapper` in `:core:network`.
- Queue constants: `ChatSyncQueueContract` in `:core:sync`; Phase 6B wires conversation/message upsert operations into `SyncPayloadParser`, `LocalFirstSyncCoordinator`, and scheduler-driven Chat Backfill. Pull coordinators from Phase 6C remain intentionally unwired from production lifecycle.
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
- Formal login remains unimplemented. Uninstall/system clear-data still loses anonymous identity recovery. Account recovery remains unimplemented.

### Phase 6C-1 Chat Remote Pull Transport

- Gateway implemented: `ChatRemotePullGateway` and `SupabaseChatRemotePullGateway` are configured to fetch `ai_conversations` and `ai_chat_messages` directly via REST.
- Stable pagination: Implemented using a strictly ascending composite cursor `(server_updated_at, id)` mapped to PostgREST `or` filter (`server_updated_at > cursor.time OR (server_updated_at = cursor.time AND id > cursor.id)`). `server_updated_at` uses a precise ISO-8601 UTC string (not epoch milliseconds) to preserve microsecond precision and prevent truncation.
- Parsing precision: `assistant_cards` JSONB is extracted as raw string content via `JSONObject` to prevent field loss.
- Error Handling: Integrates accurately with the Supabase identity lifecycle. On 401/403, triggers exactly one session refresh and retry before mapping to `FatalFailure`. Timeouts and transient HTTP errors (e.g. 5xx, 429) result in `RetryableFailure`. Permanent refresh rejection maps to `FatalFailure`.
- No side effects: Data is only queried into `ChatRemoteConversationPage` and `ChatRemoteMessagePage` data models. This phase does **not** write to Room, does **not** persist formal cursor progress, and is **not** integrated into the `PullCoordinator` lifecycle.
- Testing: Local Unit tests implemented. Supabase verification confirmed reading correct schemas, tombstone recognition, proper pagination without duplicates, and raw JSONB preserving all schema variations.

### Phase 6C-2 Chat Remote Pull Conversation Merge
- Conversation merge implemented: `ChatConversationRemoteMerger` merges remote conversation snapshots directly into Room via DAO and bypasses `SyncQueue`, so remote apply does not create push-loop queue items.
- Dirty query API: the generic `SyncQueueDao.countActiveTasksForEntity(ownerLocalId, entityType, entityLocalId)` remains operation-agnostic for existing daily record pull semantics. Conversation merge uses `countActiveTasksForEntityAndOperation(ownerLocalId, entityType = ai_conversation, entityLocalId, operation = UPSERT_AI_CONVERSATION)`, filtering owner (`ownerLocalId` or legacy `local_uninitialized`), entity type, entity id, operation, and active statuses (`PENDING`, `PROCESSING`, `FAILED_RETRYABLE`, `WAITING_FOR_AUTH`).
- Owner identity split: dirty checks use the local queue owner (`identity.localOwnerId`) plus legacy `local_uninitialized` compatibility; conversation pull cursors use Supabase `identity.remoteUserId` and are not keyed by local owner id.
- Tombstone monotonicity: local `deletedAt != null` is never revived by ordinary remote active pull, including exact timestamp ties. Remote tombstones soft-delete clean active local rows when `remote.updatedAt >= local.updatedAt`; older remote tombstones are ignored; dirty local rows defer both active and tombstone remote snapshots.
- Existing parent safety: remote active updates for existing conversations use `UPDATE` (`updateConversationSummary`) only. `@Insert(onConflict = REPLACE)` is used only for truly missing local conversations, preventing parent delete/reinsert cascades from removing `ai_chat_messages`.
- Immutable conflicts: mismatched `conversationDate` or `createdAt` throws `ImmutableConflictException` out of `database.withTransaction { ... }`, rolling back the entire page and preventing cursor advancement.
- Exact timestamp tie: when no matching active push queue exists and business `updatedAt` is equal but mutable content differs, remote mutable state is used as the deterministic convergence result. This rule does not apply to immutable fields or tombstone resurrection.
- Cursor state: `ChatConversationPullStateStore` saves `serverUpdatedAt` and `id` together under the Supabase remote user id using synchronous `commit()`. A failed cursor save throws so the page can be replayed.
- Tests added/updated: `ChatConversationRemoteMergerTest`, `ChatConversationPullCoordinatorTest`, `SupabaseAnonymousIdentityProviderTest`, and `DayZeroSyncBackfillTest` cover operation-specific conversation dirty checks, preservation of generic daily-record dirty behavior, tombstone rules, message cascade safety, transaction rollback, cursor identity isolation, and refresh user-id mismatch blocking.
- Regression executed on 2026-06-21: `./gradlew --stop`, `./gradlew clean`, `:core:database:testDebugUnitTest` (SUCCESS, NO-SOURCE), `:core:data:testDebugUnitTest` (SUCCESS, NO-SOURCE), `:core:sync:testDebugUnitTest` (SUCCESS), `:app:testDebugUnitTest` (SUCCESS), `:app:assembleDebug` (SUCCESS), and `./gradlew test` (SUCCESS). The formal sync/app test tasks reused build-cache results after same-source focused pre-regression passes.
- Phase boundary: Phase 6C-3 now implements Message/Card merge only. Production Pull lifecycle integration, global `PullCoordinator` integration, `SyncScheduler` integration, UI changes, AI prompt changes, and Edge Function changes remain out of scope.

### Phase 6C-3 Chat Remote Pull Message/Card Merge
- Message merge implemented: `ChatMessagePullCoordinator`, `ChatMessagePullStateStore`, `ChatMessageRemoteMerger`, and `ChatMessageCardMergePolicy` live in `:core:sync`. They are not injected into app production sync lifecycle.
- Remote message apply writes only `AiChatMessageEntity` and message cursor state. It does not call `DayZeroViewModel`, `ConfirmFoodRecordUseCase`, AI repositories, interaction handlers, `ChatSyncQueueWriter`, `SyncScheduler`, or ordinary repository insert/update paths.
- Immutable message fields for existing rows: `id`, `conversationId`, `role`, `messageType`, and `createdAt`. User final text is immutable. Assistant final text is immutable except for the one allowed transition from a local empty assistant placeholder to a remote final snapshot. Unsupported future `schemaVersion` values are fatal.
- Parent/orphan rule: every remote message requires an existing parent `ConversationEntity`. Active and tombstoned parents are allowed; missing parents throw `MissingParentConversationException`, roll back the page transaction, and do not advance cursor. No fake parent conversation is created.
- Dirty query API: Message merge uses `SyncQueueDao.countActiveTasksForEntityAndOperation(ownerLocalId = identity.localOwnerId, entityType = ai_chat_message, entityLocalId = messageId, operation = UPSERT_AI_CHAT_MESSAGE)`, including legacy `local_uninitialized` owner compatibility and active statuses `PENDING`, `PROCESSING`, `FAILED_RETRYABLE`, and `WAITING_FOR_AUTH`. Other entity types, operations, owners, `DONE`, and `FAILED_FATAL` do not mark the message dirty.
- Tombstone model: local `AiChatMessageEntity` natively supports `updatedAt` and `deletedAt` columns. Ordinary Pull never revives a locally tombstoned message. Missing-local remote tombstones insert a tombstone message row to preserve monotonic cursor-reset behavior.
- Card merge policy: assistant card JSON is parsed as generic JSON tree and merged by card `id`, validating `type` equality. Unknown fields, unknown card types, nested objects, `pendingOriginalCard`, `meals`, `weightKg`, null, `{}`, and `[]` are preserved without DTO round-tripping or double encoding.
- `show_confirm_card` state order is `pending < cancelled < confirmed`; `confirmed` wins over `cancelled`, and terminal states never return to `pending`.
- `date_mismatch_guard_card` states are `pending`, `approved`, and `cancelled`; terminal states never return to `pending`. `approved` versus `cancelled` resolves to `approved` only when the nested original card has merged to `confirmed`; otherwise `cancelled` wins. The nested original card remains present in both outcomes and obeys the show-confirm state machine.
- `contentJson` and `suggestedRepliesJson` are mutable clean-message fields only. Null and empty values remain distinct; semantically equal JSON with different key order is treated as equal. Exact timestamp ties prefer the remote mutable snapshot for deterministic convergence only after immutable and tombstone rules pass and no active local message push queue exists.
- Message cursor state is independent from conversation cursor state and keyed by Supabase `identity.remoteUserId`, storing `(serverUpdatedAt, id)` atomically with full ISO-8601 precision. Dirty checks still use the local queue owner `identity.localOwnerId`.
- Tests added: `ChatMessageRemoteMergerTest`, `ChatMessagePullCoordinatorTest`, and `ChatMessageCardMergePolicyTest` cover insert/idempotency, parent/orphan rollback, immutable/text conflicts, placeholder-to-final, dirty filtering, tombstone monotonicity, card state machines, Date Guard conflicts, unknown JSON preservation, side-effect isolation, transaction rollback, cursor isolation, cursor save failure replay, and message/conversation cursor independence.
- Regression executed on 2026-06-21: `./gradlew --stop`, `./gradlew clean`, `:core:database:testDebugUnitTest`, `:core:data:testDebugUnitTest`, `:core:sync:testDebugUnitTest`, `:app:testDebugUnitTest`, `:app:assembleDebug`, and `./gradlew test` all passed.
- Room Schema Migration Validation: `Migration10to11Test` was updated to perform true schema validation by initializing a real SQLite database file at version 10 (setting tables, indexes, foreign keys, version PRAGMA, and inserting 8 historical test rows), upgrading via Room's databaseBuilder with `MIGRATION_10_11` using `.allowMainThreadQueries()`, and verifying that the database was successfully opened, all rows preserved (user message, assistant message, card-only message, contentJson = null, contentJson = {}, contentJson = [], unknown assistant card fields, multiple messages per conversation), all columns preserved with default new values (`updatedAt = createdAt`, `deletedAt = null`), and indexes/foreign keys exist.
- Repository Tombstone Race Testing: `RemoteAiDraftRepositoryTombstoneTest` was added in `:app` module to test Streaming final, Fallback final, Card updates, and Active message update behaviors. It verified that updating a locally tombstoned message does not resurrect it (the conditional UPDATE affects 0 rows, so it does not enqueue sync queue tasks or update the conversation summary) while active message updates continue to succeed normally.
- Chat Sync Backfill Testing: `DayZeroChatSyncBackfillTest` was added to verify `ChatBackfillCoordinator` behavior, including using the persisted `updatedAt`/`deletedAt` timestamps in the sync queue payload (never using current time or `createdAt`), skipping empty assistant placeholders, enqueuing card-only messages, and demonstrating idempotency (re-running backfill coalesces rather than duplicating tasks and leaves payloads identical).
- Phase boundary: Phase 6D-1, 6D-2, and 6D-3 are complete. Chat Pull is fully integrated with SyncScheduler, Hilt, and Health Reporting, and the full real-device sync/recovery cycle has been validated.

### Phase 6D Chat Pull Production Orchestrator & Integration
- Orchestrator implemented: `ChatPullCoordinator` wraps both `ChatConversationPullCoordinator` and `ChatMessagePullCoordinator` in `:core:sync`.
- Production running behavior: Chat Pull is fully integrated into `InProcessSyncScheduler` and executes strictly after the daily business pull completes.
- Execution order: Strict sequential flow. `pullConversations` is executed first; `pullMessages` is executed only if conversations succeed.
- Error Handling: Errors from either layer are mapped to a sealed `ChatPullResult`. A conversation failure (retryable/fatal) skips message pull. A message failure propagates the error but retains the successful `ChatConversationMergeStats`. Missing parents (`DeferredMissingParent`) are un-recovered within the pull orchestrator and bubble up as a message retryable failure, preventing message cursor advancement.
- Single-Flight concurrency: Enforced via `Mutex.tryLock()`. Concurrent calls return `ChatPullResult.SkippedAlreadyRunning`.
- Scheduler & Health Integration (Phase 6D-2 & 6D-3): `ChatPullCoordinator` is injected via Hilt into `InProcessSyncScheduler`. `SyncHealthReporter` now tracks `chatPullStatus`, `chatPullLastError`, and `chatPullLastSuccessTime`. `DayZeroViewModel` depends only on clean `SyncScheduler` and `SyncHealthReporter` abstractions, removing raw manual instantiation.
- Tests added: `ChatPullCoordinatorTest` using MockK validates sequential execution, failure short-circuiting, success-stat retention, and `Mutex` concurrency locking. `DayZeroChatSyncPullIntegrationTest` validates full sequential pull execution, parent-child row persistence, error routing, `DeferredMissingParent` skipping rules, idempotency, tombstone isolation, and health snapshot accuracy with an in-memory database.
- Next phase: Chat deletion UI, history search, rename/pinning, formal login/account binding, and anonymous identity recovery after uninstall remain future work.

### Phase 6D-2 & 6D-3 Chat Pull Scheduler & Health Integration
- Scheduler Integration: `ChatPullCoordinator` is now injected into `InProcessSyncScheduler`. It is strictly executed **after** the Daily Pull completes, and only if `pullMode != null` (e.g. not during Push-only sync requests).
- Production Sync Order: Push -> Backfill -> Chat Backfill -> Push -> Daily Pull -> **Chat Pull**.
- Exception Handling: Fixed an issue in `InProcessSyncScheduler` where catching a generic `Exception` was swallowing Kotlin Coroutines `CancellationException`. `CancellationException` is now correctly re-thrown, ensuring `activeJob` and `mutex` are properly released upon job cancellation.
- Sync Health Integration: Created `ChatPullHealthStateStore` backed by `SharedPreferences` to persistently record Chat Pull's `status` and `lastError`.
- Health Aggregation: `SyncHealthSnapshot` now includes `chatPullStatus` and `chatPullLastError`. `SyncHealthReporter` automatically increments the overall `retryableFailureCount` and `fatalFailureCount` based on the status from `ChatPullHealthStateStore`.
- Hilt Wiring: Migrated all Chat Pull components (`ChatPullCoordinator`, `ChatConversationPullCoordinator`, `ChatMessagePullCoordinator`, `ChatConversationPullStateStore`, `ChatMessagePullStateStore`, and `ChatPullHealthStateStore`) to use `@Inject constructor` (with `@ApplicationContext` for state stores). Injected `ChatPullHealthStateStore` into `SyncHealthReporter` via `DayZeroHiltModule.kt`. `DayZeroViewModel` correctly depends only on `SyncScheduler` and `SyncHealthReporter` rather than internal coordinators.
- Tests added: `InProcessSyncSchedulerChatPullTest` to verify that `requestPull` executes Chat Pull while `requestSync` does not. `SyncHealthReporterChatPullTest` to ensure that Retryable and Fatal errors from Chat Pull are accurately aggregated into `SyncHealthSnapshot`.
- Real-device verification: **SUCCESSFULLY COMPLETED**.
  - Ownership migration completed for the current physical-device anonymous user.
  - First ordinary Daily and Chat Pull restored 6 conversations, 37 messages, 2 daily records, 4 meals, 7 food entries, and 2 weight records.
  - Second Pull was idempotent: Conversation/Message counts and cursors stayed stable, no Push Queue loop was created, and Sync Health stayed successful.
  - Restart verification passed: identity resolution stayed stable and restored data remained present without startup crash.
  - Current user tombstone count is 0. The remote database still contains 3 tombstoned conversations belonging to other test users; RLS isolates them from the current user and they do not appear in local UI/DAO results.
  - UI and card verification passed: confirmed cards and terminal card states render correctly without duplicate records or scroll/runtime crashes.
  - **connectedDebugAndroidTest incident**: instrumentation deployment previously reset the app sandbox and lost Room/SharedPreferences plus the anonymous Supabase session.
  - **Red line**: do not run Instrumentation tests on a real device that holds production recovery data.
  - The actual administrator migration used `session_replication_role = replica` as a one-time recovery shortcut. This must not become the formal migration pattern.
- Next phase / Constraints: The sync system is fully assembled and real-device verified for Phase 6D. Future steps should focus on Chat deletion UI, history search, rename/pinning, and formal account binding. Formal login and recovery are not implemented. Uninstall after logging in as anonymous will cause data loss.

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
  - The conversation **title** is extracted from the first user message in that date's group (truncated to a maximum of 32 characters), falling back to a neutral title (e.g., `6月8日的对话`) if no user text is found.
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
  - Chat/conversation cloud sync integration and real-device recovery validation are complete through Phase 6D.
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

### Startup Crash Fix (ClassCastException) - 2026-06-21
- **Problem**: App crashed immediately on startup during Hilt initialization with `java.lang.ClassCastException: com.example.data.sync.SupabaseRemotePullGateway cannot be cast to com.example.data.sync.ChatRemotePullGateway`.
- **Root Cause**: `DayZeroHiltModule.kt` did not provide `ChatRemotePullGateway` or `SupabaseChatRemotePullGateway`. In `provideChatConversationPullCoordinator` and `provideChatMessagePullCoordinator`, the dependency was declared as `remotePullGateway: RemotePullGateway` and cast as `remotePullGateway as ChatRemotePullGateway`. At runtime, Hilt injected the provided `SupabaseRemotePullGateway` (which only implements `RemotePullGateway`, not `ChatRemotePullGateway`), resulting in a ClassCastException.
- **Resolution**:
  - Added a `@Provides` method for `ChatRemotePullGateway` returning a `SupabaseChatRemotePullGateway` instance in `DayZeroHiltModule.kt`.
  - Updated `provideChatConversationPullCoordinator` and `provideChatMessagePullCoordinator` parameters to request `chatRemotePullGateway: ChatRemotePullGateway` directly and removed the unsafe typecast.
- **Data Preservation**: Verified that no user data, SharedPreferences, or local Room databases were wiped or modified during the fix.
- **Verification Results**:
  - All Gradle compile, build, and unit test tasks (`:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:core:sync:testDebugUnitTest`, `:app:assembleDebug`, `test`) completed successfully.
  - Reinstalled debug build using standard `./gradlew :app:installDebug` without data loss.
  - Verified app launch on real device (`10AE9X0J0Z001SJ`). App launched cleanly without crashing, successfully resolved local anonymous identity, refreshed Supabase auth session, and stayed active.

## Remote Time Parsing & Sync Health Recovery Fix (2026-06-21)

### User-Visible Problem
- The "数据同步" sync health card on the Trends page turned yellow with the warnings:
  - "部分记录需要处理"
  - "本地记录仍然可用，云端同步遇到不能自动恢复的问题"
  - "等待同步：0条"

### Verified Root Cause
- PostgREST returned timestamp strings with numeric offsets (e.g. `"2026-06-21T13:39:20.154+00:00"`).
- In Java 8 / Android desugaring, `Instant.parse()` strictly expects timezone offset to be `'Z'` and throws `DateTimeParseException` for numeric offsets like `+00:00`.
- The parser previously caught this parsing failure and fell back to returning `System.currentTimeMillis()`.
- Returning `System.currentTimeMillis()` for remote times created an inconsistency between local and remote `createdAt` values for the same conversation/message entity.
- This inconsistency triggered an `ImmutableConflictException` ("immutable conflict: createdAt local=... remote=...") during local database merge.
- The merge transaction rolled back, enqueuing a fatal pull failure in `ChatPullHealthStateStore`, which turned the Trends sync card yellow.

### Gemini's Original Analysis Correctness
- **Correct**: `Instant.parse()` indeed fails for timestamps with offsets on Java 8/Android desugaring, and using `OffsetDateTime.parse` first is the correct solution.
- **Incorrect/Unproven**: Gemini's suggestion to retain a fallback to `System.currentTimeMillis()` on parser failure was the core mechanism of the bug, masking parsing issues and causing database immutable field conflicts.

### File Modifications
- **[SupabaseChatRemotePullGateway.kt](file:///D:/Goings/APPProjects/DayZero/core/sync/src/main/java/com/example/data/sync/SupabaseChatRemotePullGateway.kt)**: Made `parseRemoteTime` internal and updated it to parse using `OffsetDateTime` first, falling back to `Instant.parse`, and throwing the exception upon failure.
- **[SupabaseRemotePullGateway.kt](file:///D:/Goings/APPProjects/DayZero/core/sync/src/main/java/com/example/data/sync/SupabaseRemotePullGateway.kt)**: Implemented the same robust parser.
- **[SupabaseChatRemotePullGatewayTest.kt](file:///D:/Goings/APPProjects/DayZero/core/sync/src/test/java/com/example/data/sync/SupabaseChatRemotePullGatewayTest.kt)**: Added datetime parsing tests covering UTC `Z`, offsets (`+00:00`, `+08:00`, `-05:00`), varying sub-second precision (including microsecond formats), invalid formats, and blank inputs. Verified that failures do not fallback to system execution time.
- **[SyncHealthReporterChatPullTest.kt](file:///D:/Goings/APPProjects/DayZero/core/sync/src/test/java/com/example/data/sync/SyncHealthReporterChatPullTest.kt)**: Added a test verifying that subsequent successful pulls clear the fatal failure count and restore health status, and fixed the missing `assertNull` import compilation error.

### Time Parsing Strategy
- Parse remote times using:
  ```kotlin
  try {
      java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
  } catch (e: java.time.format.DateTimeParseException) {
      java.time.Instant.parse(value).toEpochMilli()
  }
  ```
- This format handles variable sub-second precision (e.g. microseconds) and timezone offsets seamlessly, while failing fast on invalid/blank inputs.
- All silent current-time fallbacks (e.g. `System.currentTimeMillis()`) have been removed from remote timestamp parsing.

### Transaction & Cursor Safety
- When parsing fails, the thrown exception aborts the remote page pull and rolls back the Room database transaction. No incorrect database writes are made, and no pull cursors are advanced.
- The failure is safely tracked in `ChatPullHealthStateStore` to alert the user of sync status via the health panel.

### Health State Recovery
- When a new sync pull is triggered and succeeds, the `ChatPullHealthStateStore` transitions back to `COMPLETED` and clears `lastError`.
- `SyncHealthReporter` reads the `COMPLETED` status, resetting the aggregated `fatalFailureCount` back to `0`. The Trends sync status card automatically recovers to the normal, healthy state on the next successful sync.

### Commands Executed & Results
- Compiled tests successfully: `.\gradlew.bat :core:sync:compileDebugUnitTestKotlin`
- Executed all project unit tests (clean and no-cache): `.\gradlew.bat test --no-build-cache --rerun-tasks` (passed with 220/220 successful tasks)
- Built debug APK successfully: `.\gradlew.bat :app:assembleDebug`

### Verification & Constraints
- NO database schema changes or migrations were made.
- Remote Supabase tables and RLS policies were left unchanged.
- Physical device data and anonymous session states were fully preserved (no `connectedDebugAndroidTest` or `adb uninstall` was run on the connected device).
- Normal recovery can be manually verified using a safe overwrite install.

---

## Phase A: Nutrition Capsule Data Link & Sync (2026-06-26)

### 2. 领域层与 Card DTO 的扩展
- **[FoodEntry](file:///D:/Goings/APPProjects/DayZero/core/model/src/main/java/com/example/domain/model/FoodEntry.kt)** 包含 `carbohydratesG`, `proteinG`, `fatG`, `fiberG` (Float? = null)。
- **[ConfirmCardItem](file:///D:/Goings/APPProjects/DayZero/core/model/src/main/java/com/example/domain/model/ai/assistant/AiChatCard.kt)** 新增上述四个字段。
- **[ConfirmCardItemDto](file:///D:/Goings/APPProjects/DayZero/core/network/src/main/java/com/example/data/remote/dto/assistant/AiChatCardDto.kt)** 及 **[AssistantActionItemDto](file:///D:/Goings/APPProjects/DayZero/core/network/src/main/java/com/example/data/remote/dto/assistant/AssistantActionDto.kt)** 补充上述四个字段，使用 Moshi 进行序列化。
- **[AssistantTurnV2ResponseMapper](file:///D:/Goings/APPProjects/DayZero/core/network/src/main/java/com/example/data/remote/mapper/AssistantTurnV2ResponseMapper.kt)** 及 **[AiAssistantRemoteMapper](file:///D:/Goings/APPProjects/DayZero/core/network/src/main/java/com/example/data/remote/mapper/AiAssistantRemoteMapper.kt)** 完整实现双向往返映射。
  - 历史 Card JSON 缺少营养字段时，正常解析得到 `null`，不崩溃且不转为 0。
  - `date_mismatch_guard_card` 中嵌套的 `pendingOriginalCard` 能对称传输并不丢营养字段。

### 3. 本地 mealsJson 存储与兼容性
- **存储方案**: 本地 Room 保持不变，不需要 Room Migration 且未改变 Database version，没有独立的本地 meals/food_entries/weight_records Room 表。
- **Room counts 说明**: 文档历史中的 `daily_records=3, meals=6, food_entries=9, weight_records=3` 并非 Room 中独立表行数。`daily_records` 指 Room 中记录实体行数，其余是指业务对象在 `mealsJson` 中的计数、Backfill 扫描任务计数或远端 Supabase 数据库表行数。
- **mealsJson 兼容**: 借助 Moshi，新/旧 `mealsJson` 反序列化为 `FoodEntry` 时能对未知键得到 `null`，保存时正常序列化并保留 0 与 null 的独立语义。

### 4. 同步对称性 (Sync Payload, Push, Pull & Backfill)
- **[SyncPayloadBuilder](file:///D:/Goings/APPProjects/DayZero/core/sync/src/main/java/com/example/data/sync/SyncPayloadBuilder.kt)**: `foodPayload` 输出 `carbsG`, `proteinG`, `fatG`, `fiberG`，显式支持 `null -> JSONObject.NULL`，确保能够被客户端清空及修改。
- **[SupabaseRemoteSyncGateway](file:///D:/Goings/APPProjects/DayZero/core/sync/src/main/java/com/example/data/sync/SupabaseRemoteSyncGateway.kt)**: `foodEntryBody` 新增 `fiber_g` 列的写入，向 Supabase 发送 Push 请求时，值保留 null/0/正数。
- **[SupabaseRemotePullGateway](file:///D:/Goings/APPProjects/DayZero/core/sync/src/main/java/com/example/data/sync/SupabaseRemotePullGateway.kt)**: `FoodEntryRemoteDto` 增加 `fiberG` 字段；`foodEntryFromJson` 在从 JSON 解析时，读取 `fiber_g` 并支持缺失字段兼容 (返回 null)。
- **[PullCoordinator](file:///D:/Goings/APPProjects/DayZero/core/sync/src/main/java/com/example/data/sync/PullCoordinator.kt)**: `buildMeals` 在构造 `FoodEntry` 领域模型时，成功映射 remote 的四个营养字段，实现了 Pull 流程的数据流补齐。
- **Backfill**: 因为 Backfill 调用 `foodPayload`，更新 `SyncPayloadBuilder` 后，历史营养字段和 null 属性会自动加入 Backfill 流程，实现了同步对称。

### 5. Supabase 变更已于 2026-06-26 成功部署并验证
- **Migration SQL**: 成功在项目 `sybenxmxnwwtlvkeojtj` 部署了 [20260626001000_add_food_entries_fiber_g.sql](file:///D:/Goings/APPProjects/DayZero/supabase/migrations/20260626001000_add_food_entries_fiber_g.sql)（使用 Supabase MCP `apply_migration` 工具），在 `public.food_entries` 中增加了 `fiber_g numeric null` 列。
- **Verification SQL**: 执行了 [20260626001000_verify_food_entries_nutrition.sql](file:///D:/Goings/APPProjects/DayZero/supabase/verification/20260626001000_verify_food_entries_nutrition.sql)，包含对列、类型、空值和既有 RLS 等安全策略的只读检验，所有项目均通过。
- **数据与安全一致性**: 既有 9 行数据的 `fiber_g` 初始化为 null，其他字段保持不变；表的 RLS、4 项所有权策略、唯一约束、外键、5 个索引及表权限等均保持一致，且行数、孤儿行、重复行或跨用户数据均未发生任何异常。

### 6. 测试与验证结果
- **新增的单元测试**:
  - `DailyRecordMapperTest`: 验证旧 JSON 不含键、新 JSON round-trip 以及更新时 null 不混淆的兼容性。
  - `SyncPayloadBuilderTest`: 验证 null/0/正数分别映射为 JSON null、数值 0 和正数的准确性。
  - `SupabaseFoodRemoteGatewayTest`: 验证 Push 网关映射和 Pull 网关解析（包括缺失 `fiber_g` 列时的向后兼容性）。
  - `AiAssistantRemoteMapperTest`: 验证 ConfirmCardItem 序列化往返、历史 card 兼容、action 响应解析以及 date mismatch 嵌套卡片。
  - `ConfirmFoodRecordUseCaseTest`: 验证 payload 确认时营养克数能正确写入最终的领域模型。
- **测试执行情况**:
  - JVM 单元测试均已成功执行并全部通过 (177 Actionable tasks executed, BUILD SUCCESSFUL)。

### 7. 部署与环境声明
- **“本阶段未发生 Room Schema 变化，未提升 Database version。Supabase 变更（新增 fiber_g 列）已于 2026-06-26 成功部署并验证。现在客户端 Schema 兼容阻塞已解除。Edge Function 营养字段版本已完成远端部署和烟雾验收。”**
- 数据契约阻断负数、NaN 和 Infinity；完整输入 normalization 已经在 Edge Function 远端代码中通过 `normalizeActions` 校验并实现。UI 渲染、编辑失效规则、计算器已完全实现，胶囊和动画于 Phase C2 最终实现。

---

## Edge Function & AI Prompt Nutrition Capsule Changes (2026-06-26)

### 1. 本地 Edge Function 外科式修改
- **Prompt 升级**：同时修改了 `assistant-turn-v2-stream` 与 `assistant-turn-v2` 的系统 Prompt：
  - 更新 `show_confirm_card` 的 JSON 示例，将营养克数 `carbohydratesG: 85, proteinG: 15, fatG: 22, fiberG: 6` 追加至 item 示例中。
  - 在热量和 `calorieConfidence` 说明后追加了一行精确的语义说明，明确指示字段克数对应 `amountText` 份量、不可靠时用 `null`、碳水包含纤维、且未知不得用 `0` 代替。
- **Prompt 版本升级**：
  - `assistant-turn-v2-stream` 的 `promptVersion` 从 `stream_compact_v1` 递增为 `stream_compact_v2`。
  - `assistant-turn-v2` 的 `promptVersion` 从 `compact_v2_timing` 递增为 `compact_v3_timing`。

### 2. Normalization 模块抽离与数值净化
- **[normalization.ts](file:///D:/Goings/APPProjects/DayZero/supabase/functions/assistant-turn-v2-stream/normalization.ts)**: 新增独立的归一化 TS 模块，将 `generateId`、`getMealLabel` 和 `normalizeActions` 抽离，减少 `index.ts` 冗余，方便 Deno 单体测试直接导入。
- **纯数值净化函数**：新增了 `normalizeNullableNonNegativeNumber` 辅助函数，严格实现：
  - 正数、小数、0 保留。
  - `null`/缺失/`undefined` 补齐为 `null`。
  - 负数、`NaN`、`Infinity`/`-Infinity` 净化为 `null`。
  - 字符串数字等其他类型一律净化为 `null`（不做隐式转换）。
- 在 `normalizeActions` 对 `meals[].items[]` 进行就地遍历修改，对其四个营养字段套用净化函数。

### 3. Deno 单元测试覆盖
- **[normalization_test.ts](file:///D:/Goings/APPProjects/DayZero/supabase/functions/assistant-turn-v2-stream/normalization_test.ts)**: 编写了 Deno 规格 of 单元测试：
  - 覆盖了辅助函数的数值边界（正数、小数、0、负数、`NaN`、`Infinity`、字符串数字等非数字类型）。
  - 覆盖了 `normalizeActions` 包含营养字段、全 null、缺失、旧版卡片无营养字段、非 `show_confirm_card` 卡片豁免、体重预填等全部 16 种边界情形。

### 4. 部署与环境状态
- **Edge Function 部署状态**：2026-06-26 已在 Supabase 项目 `sybenxmxnwwtlvkeojtj` 使用 Supabase MCP 按顺序部署并验证营养字段版本：先部署 fallback `assistant-turn-v2`，再部署 primary streaming `assistant-turn-v2-stream`。
  - `assistant-turn-v2`: 当前远端 Version 21 / `compact_v3_timing` / `ACTIVE` / `verify_jwt=false`。
  - `assistant-turn-v2-stream`: 当前远端 Version 12 / `stream_compact_v2` / `ACTIVE` / `verify_jwt=false`，stream timeout 仍为 15 秒。
  - 本次任务未发生客户端架构变化，未发生数据库 Schema 变化，未修改 secrets。
- **回滚源码保存**：部署前已真实保存当前远端回滚源码到 `%LOCALAPPDATA%\Temp\dayzero-edge-rollback-20260626-020433\`。
  - `assistant-turn-v2-version-20-index.ts`: 19444 bytes, SHA-256 `F80255EFECAF6E4536B97F1AD3E9E373F33A0822F35B18B6819261020A218304`。
  - `assistant-turn-v2-stream-version-11-index.ts`: 22970 bytes, SHA-256 `B8BF404CE780616712E69556DB72EC07C0F38DE991EB1AEBB631D80FE44196D8`。
  - 同目录还包含 `rollback-manifest.txt` 与 `sha256.txt`。
- **远端烟雾验收**：fallback 普通聊天 2/2 HTTP 200，`reply` 为字符串，`actions` 为数组，`debugTiming.promptVersion=compact_v3_timing`。fallback 基线输入 `午餐吃了一碗螺蛳粉和一个鸡蛋，帮我记录一下。` 第 1 次获得 `show_confirm_card`，2 个 item 均包含 `carbohydratesG/proteinG/fatG/fiberG`，字段类型均为非负有限 number 或 null，`mealType/subtotalCalories/totalCalories/weightKg/confirmType/name/amountText/calories/calorieConfidence` 正常。
- **Streaming 远端烟雾验收**：普通聊天获得 2 次成功 SSE 样本，事件顺序为 `status -> reply_delta* -> final -> debug_timing -> done`，final 只出现一次，`debugTiming.promptVersion=stream_compact_v2`。另有 1 次普通聊天样本返回 SSE `error: The signal has been aborted`，发生在 15 秒 timeout 保护内，未伴随 5xx 或模块加载错误。streaming 基线输入第 1 次获得 `show_confirm_card`，final action 中 2 个 item 均包含四个营养字段，字段类型均为非负有限 number 或 null，且 action 未提前出现在 `reply_delta` 中。部署后 fallback 复测 HTTP 200。
- **Schema 对称性**：fallback 与 streaming 的远端 action/schema 字段名称一致；营养字段契约一致，不要求两次模型调用产生相同估算数值。
- **日志与安全状态**：Supabase Edge Function 日志中本轮相关部署后调用均为 HTTP 200，未发现 import/module error、TypeError、normalization 异常、secrets 缺失或持续 5xx。烟雾测试只调用 Edge Function 返回结构，未确认客户端卡片，未写入 `food_entries`、`daily_records`、聊天数据库或 `user_profiles`。
- **Deno 环境状态**：2026-06-26 已用官方 Windows 用户级安装脚本安装 Deno 到 `%USERPROFILE%\.deno\bin\deno.exe`，并在本地执行 Edge Function 格式、lint、类型检查和 normalization 单元测试验收。
- **部署失败后恢复确认 (2026-06-26)**：通过 Supabase MCP 只读读取确认当前 `assistant-turn-v2` Version 20 状态为 `ACTIVE`、`verify_jwt=false`、源码为旧版 `compact_v2_timing` 形态，不含 `normalization.ts` import、营养字段或 `compact_v3_timing` 残留。旧版 fallback 健康采样显示普通聊天 2/2 返回 HTTP 200 且 `actions=[]`；明确记录请求 `午餐吃了一碗螺蛳粉和一个鸡蛋，帮我记录一下。` 5/5 返回 `show_confirm_card`；历史设计输入 `我今天中午吃了螺蛳粉` 先返回 `ask_record_intent_card`，随后按真实 `interaction_result` 选择“帮我记录”返回 `show_confirm_card`。上轮 90 秒超时未复现。
- **actions=[] 调查结论**：上轮 Version 19 三次远端日志均为 HTTP 200，无 import/module/TypeError/5xx 证据。Version 19 两次饮食烟雾请求与真实 Android fallback 请求不同：手工请求显式包含多个 `null` 字段、空 `todayRecord`、`promptCacheKey` 和“请直接生成记录确认卡”测试话术；真实 fallback DTO 通常不发送 `promptCacheKey`，null 字段由 Moshi 省略，`todayRecord` 无记录时为 null/省略，且 user flow 可能先出 `ask_record_intent_card` 再经 `interaction_result` 出确认卡。现有日志未暴露 Kimi raw content，无法证明 `actions=[]` 最早出现在 raw model、JSON 解析、校验或 normalization 哪一层；目前没有证据证明营养 Prompt、函数抽离或 normalization 导致代码回归。下一次部署验收应以确定性检查为主：remote module startup、HTTP 200、协议合法、promptVersion 正确、无 import/TypeError/5xx，以及一旦 action 存在则营养字段经过 normalization；自然语言是否单次出 `show_confirm_card` 属于非确定性采样，不应作为唯一部署门槛。
- **本轮环境声明**：本次重新部署与验收未修改 Prompt、未修改营养字段语义、未修改 normalization 规则、未修改客户端代码、未修改数据库 Schema、未修改 secrets、未安装或运行 APK、未执行真机操作。
- **真机/UI 状态**：APK 未重新安装运行，未进行真机 UI/卡片编辑/计算器/动画验证。

---

## Phase C1: Nutrition Capsule Client Logic & Functional UI (2026-06-26)

### 1. 计算逻辑
- 新增 `NutritionCapsuleCalculator`，位置：`core/ui/src/main/java/com/example/ui/components/ai/NutritionCapsuleCalculator.kt`。
- 胶囊按整张 `show_confirm_card` 的 `payload.meals[].items[]` 汇总，不从 `calories` 反推营养。
- `carbohydratesG` 表示包含 `fiberG` 的总碳水；展示用净碳水：
  - `totalCarbohydratesG = sum(items.carbohydratesG)`
  - `totalProteinG = sum(items.proteinG)`
  - `totalFatG = sum(items.fatG)`
  - `totalFiberG = sum(items.fiberG)`
  - `netCarbohydratesG = max(totalCarbohydratesG - totalFiberG, 0)`
- 胶囊四段固定为：净碳水、蛋白质、脂肪、膳食纤维。比例按克数计算：
  - `componentRatio = componentGrams / (netCarbohydratesG + totalProteinG + totalFatG + totalFiberG)`
  - 不使用 4/4/9 热量换算，不使用热量加权。

### 2. 显示/隐藏规则
- 只有卡片至少有一个食物 item、每个当前 item 的四个营养字段都非 null、有限且非负，并且四项展示总和大于 0 时，才显示整块营养胶囊。
- 任意 item 缺失字段、字段为 null、负数、NaN、Infinity、空 meals/items、历史卡片完全无营养字段，或四项展示总和为 0 时，整块胶囊完全隐藏，不显示残缺版本、空壳或“暂无数据”。
- 明确的 0 是合法值；若其他字段使总和大于 0，0 值组成不会导致崩溃。`fiberG > carbohydratesG` 时净碳水 clamp 为 0。

### 3. 编辑失效与持久化
- `FoodDraftConfirmCard` 的食物编辑现在通过 `NutritionCapsuleCalculator.applyFoodEdit(...)` 判断真实变化。
- 当某个 item 的 `name`、`amountText` 或 `calories` 发生真实变化时，仅该 item 的 `carbohydratesG/proteinG/fatG/fiberG` 被置为 null；其他 item 的营养值保留。
- 保存相同的 `name/amountText/calories` 不会使营养失效；`mealType`、meal label、`weightKg`、卡片状态切换、guard 状态变化不会使营养失效。
- 新增 item 使用 `NutritionCapsuleCalculator.newItem(...)`，四个营养字段默认为 null，因此胶囊会隐藏；删除 item 后按剩余 item 重新计算，剩余 item 全部完整时胶囊可重新显示。
- `FoodDraftConfirmCard` 新增 `onDraftChanged` callback，编辑、删除、新增和体重保存会立即写回同一张卡片的数据源。`DayZeroViewModel.updateFoodDraftCard(...)` 复用现有 `aiDraftRepository.updateChatMessage(...)` 路径更新 `assistantCardsJson`，不创建第二套 Compose-only 状态源。
- 普通 `show_confirm_card` 和 `date_mismatch_guard_card.pendingOriginalCard` 都由同一更新路径处理。guard approved 后展示原卡片时，编辑失效规则正常生效；guard cancelled 不写入食物记录；confirmed/cancelled 状态切换不会恢复旧营养值。
- `ConfirmFoodRecordUseCase` 接收到的是编辑后的 `PayloadSummary.meals`：未失效的营养值保留，已失效的字段为 null，并最终写入 `FoodEntry`。

### 4. 基础功能型 UI
- `FoodDraftConfirmCard` 在食物列表之后、确认/取消操作之前显示整卡级营养胶囊。
- UI 包含标题“营养构成”、一条横向圆角分段条、四项名称与克数。克数整数不显示小数，小数最多 1 位。
- 使用现有 DayZero 主题色、圆角、间距和字体；仅在组件内部新增少量语义色，不引入第三方 UI 依赖，不做高级动画。
- 胶囊带有合并语义 `contentDescription`；0 比例 segment 不传入非法 weight，避免小屏和 0 值场景崩溃。

### 5. 修改文件与测试
- 修改文件：
  - `core/ui/src/main/java/com/example/ui/components/ai/FoodDraftConfirmCard.kt`
  - `core/ui/src/main/java/com/example/ui/components/ai/NutritionCapsuleCalculator.kt`
  - `core/ui/build.gradle.kts`
  - `feature/ai-record/src/main/java/com/example/ui/screens/AiRecordScreen.kt`
  - `feature/ai-record/src/main/java/com/example/ui/screens/AssistantCardRenderer.kt`
  - `app/src/main/java/com/example/ui/AppNavigation.kt`
  - `app/src/main/java/com/example/DayZeroViewModel.kt`
  - `core/ui/src/test/java/com/example/ui/components/ai/NutritionCapsuleCalculatorTest.kt`
  - `feature/ai-record/src/test/java/com/example/ui/screens/AiRecordPhase3Test.kt`
  - `app/src/test/java/com/example/DayZeroDateMismatchGuardTest.kt`
  - `app/src/test/java/com/example/ConfirmFoodRecordUseCaseTest.kt`
  - `core/network/src/test/java/com/example/data/remote/mapper/AiAssistantRemoteMapperTest.kt`
  - `docs/PROJECT_CONTEXT_FOR_CHATGPT.md`
- 测试覆盖：
  - 纯计算：单 item、多 meal 多 item、总碳水含纤维、净碳水 clamp、null/负数/NaN/Infinity/空 items 隐藏、0 合法、比例和约等于 1。
  - 编辑失效：name/amountText/calories 真实变化只清空目标 item；相同值保存不失效；mealType/weightKg 不失效；新增 item 默认 null；删除不完整 item 后剩余完整 item 可显示。
  - 持久化/兼容：Card JSON round-trip 保留 0/null 区分；历史卡缺营养字段不崩溃；`pendingOriginalCard` 保留并可写回 null；`ConfirmFoodRecordUseCase` 写入编辑后的 null；confirmed/cancelled 不恢复旧值。
  - UI：完整营养数据时胶囊存在，任意 item 缺营养时胶囊不存在，0 值 segment 不崩溃，确认/取消、weight 与食物编辑入口仍存在。
- 执行结果：
  - `.\gradlew.bat :core:model:testDebugUnitTest`：当前项目不存在该任务，改用实际存在的 `:core:model:test`。
  - `.\gradlew.bat :core:model:test :core:ui:testDebugUnitTest :feature:ai-record:testDebugUnitTest :core:network:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug test --continue`：BUILD SUCCESSFUL。

### 6. 边界声明
- 本次任务未修改 Edge Function、AI Prompt、Supabase、数据库 Schema、secrets 或同步契约。
- 本次任务未进行 uninstall、pm clear 或 connectedDebugAndroidTest，保留了真机沙盒。
- 本次任务未执行 Git commit/push/reset/clean。

---

## Phase C2: Nutrition Capsule Final Visual Polishing & Real-Device Validation (2026-06-26)

### 1. 最终视觉结构与低饱和语义色
- **标题行**: 左侧“营养构成”（使用现有二级标题/Label风格，中粗加重，保持适当上下间隙），右侧补充弱化的小字体“按克数占比”，整体布局清爽、克制，融入现有卡片。
- **圆角胶囊分段条**: 高度 14dp，两端完整圆角 clip，背景使用浅灰色轨道 `SurfaceSecondary`。各分段使用非常细微的 1dp 间隔线（与卡片背景色 `MaterialTheme.colorScheme.surface` 融合），避免白色粗缝。四段固定按“净碳水”、“蛋白质”、“脂肪”、“膳食纤维”排列，0 值段完全不绘制（不设置 weight），极小正比例安全兜底（> 0.0001f）。
- **2x2 营养数据区**: 避免小屏拥挤，使用 2x2 自适应网格。每项包含：小色点（圆形 clip）、名称（小字、低饱和色）、以及“克数 · 百分比”（粗体中性色，例如 `25g · 42%`）。支持系统字体大小放大与横向安全自适应。
- **低饱和语义色**:
  - 净碳水: 燕麦色/暖米黄 (浅色 `0xFFDECBB7` / 深色 `0xFFE5D4C0`)
  - 蛋白质: 鼠尾草绿 (浅色 `0xFFA5BBA3` / 深色 `0xFFB1C2B0`)
  - 脂肪: 杏色/浅陶土 (浅色 `0xFFDCA18A` / 深色 `0xFFE2B29F`)
  - 膳食纤维: 灰紫/雾蓝紫 (浅色 `0xFF9E8FA9` / 深色 `0xFFB5A8C2`)
  整体区分明显且色调和谐，与 DayZero 既有 Theme 完全统一，不包含任何游戏 HUD 健身色、霓虹或粗重边框。

### 2. 轻量克制动画实现
- **组件显隐动画**: 首次显示满足条件时淡入并向下展开 (fadeIn + expandVertically, 240ms)；因编辑失效/添加不完整 item 导致数据不全时，淡出并向上收起 (fadeOut + shrinkVertically, 180ms)。使用 remembered 的 `lastNonNullSummary` 在淡出收起时暂存最后一次有效数据，防止瞬间变空或闪烁。
- **分段条比例动画**: 首次出现时，比例从 0 平滑延伸至目标比例，时长 400ms 并加入 FastOutSlowInEasing；四段按 index × 30ms 进行轻微错开延迟（最大 stagger 延时 90ms，不超过 100ms），无任何弹簧回弹。编辑或数值变化时，平滑从旧比例过渡至新比例，不瞬间跳变，不重置为 0。动画状态纯粹由 UI 局部 remember，不写入持久化层，以 `card.id` 作为稳定 key 避免重组重复从 0 伸展。
- **数值变化动画**: 克数与百分比采用轻量淡入淡出 (`Crossfade`, 200ms) 过渡新旧字符串值，无字符抖动或复杂翻牌。

### 3. 可访问性 (A11y) 与布局安全
- **合并 TalkBack 语义**: 在 `NutritionCapsule` 的最外层 Column 上使用 `.semantics(mergeDescendants = true) { contentDescription = "..." }`，合并四项营养素的克数与占比百分比（如“营养构成：净碳水 25克，占比 42%；蛋白质 20克，占比 33%…”），同时剥夺子色点和标签的独立 TalkBack 焦点，提升无障碍朗读流畅度。
- **布局溢出保护**: 采用 2x2 Grid 及无 maxLines 的换行机制，确保系统字体放大或长文案时不横向拉伸或溢出。

### 4. 测试与验证结果
- **执行命令**: `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :core:ui:testDebugUnitTest :feature:ai-record:testDebugUnitTest :app:testDebugUnitTest`
- **执行结果**: `BUILD SUCCESSFUL`。所有 15 个测试全部通过。
- **新增 Compose 交互与动画测试**:
  - `foodConfirmCardShowsNutritionCapsuleWhenAllItemsAreComplete`: 验证完整数据时标题、占比文字、4 项营养标签、克数占比均正确渲染，并断言合并的 contentDescription 完全匹配。
  - `foodConfirmCardHandlesFiberGreaterThanCarbsNetCarbsClamp`: 验证当纤维 > 碳水时，净碳水被正确 clamp 为 0g 且显示 `0g · 0%`。
  - `foodConfirmCardHidesOnEditInvalidate`: 验证点击编辑食物并修改参数确认后，数据被置空，`AnimatedVisibility` 收起且 capsule 节点在布局中最终为 0 个。
  - `foodConfirmCardShowsOnDeleteIncompleteItem`: 验证在包含不完整食物记录时，胶囊不显示；点击删除不完整食物项后，剩余完全项触发重新满足显示条件，胶囊重新自然展现。
  - 现有确认/取消/编辑/体重控件在所有测试中均被完好保留且能正确匹配。

### 5. 安全真机安装与运行
- **检测设备**: `adb devices` 输出 `10AE9X0J0Z001SJ device`（真机连接正常）。
- **安全覆盖安装**: 执行 `$env:PATH += ";C:\Users\Goings\AppData\Local\Android\Sdk\platform-tools"; $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; powershell -ExecutionPolicy Bypass -File scripts/install-debug-preserve-data.ps1`，成功编译并使用覆盖安装将 APK 推送至手机，未触发 `uninstall`，未进行 `pm clear`，完全保留了本地 Room 沙盒数据库和 anonymous 登录凭证。
- **启动验证**: 覆盖安装完成后，App 由 `am start` 成功吊起启动，启动过程及主 Activity 运转平稳，Logcat 中没有任何 DayZero/MainActivity 相关 ClassCastException、IllegalArgumentException、Compose 运行时崩溃或 Fatal Error。
- **无自动化写操作**: 整个构建部署过程未在真机上自动调用 API click 确认或增删真实数据，保障用户线上/测试沙盒的真实业务记录原封不动。

### 6. 人工视觉验收要点 (供用户执行)
1. 输入“午餐吃了一碗螺蛳粉和一个鸡蛋，帮我记录一下。”，检查是否显示卡片，并且“营养构成”以中粗字重呈现、右侧小字“按克数占比”弱化显示。
2. 观察圆角分段条是否在 400ms 内优雅分段伸展，每段之间是否有精细 1dp 间隔（无粗缝）。
3. 检查 2x2 网格中，净碳水、蛋白质、脂肪、膳星纤维的克数与四舍五入百分比（如 `25g · 42%`）是否美观对齐，颜色是否呈低饱和燕麦色、鼠尾草绿、柔杏色与雾蓝紫。
4. 点击编辑食物更改名字或克数，查看胶囊是否平滑向上淡出收缩并最终完全不占位置；点击删除缺失营养的食物，查看胶囊是否又平滑向下展开伸展。
5. 开启 TalkBack，用手指触摸卡片“营养构成”区域，检查是否一次性合并朗读所有四项营养素的具体克数和占比，并且小色点与单行字不会被重复聚焦。


## Phase C3: Nutrition Capsule Entry Animations & Progress Rings (2026-06-26)
- **Real Entry Animations**: Fixed missing entry animations. The `AnimatedVisibility` now triggers effectively upon initial component creation by using a `startVisibilityAnim` state delayed by `LaunchedEffect`. Added a horizontal sweep reveal animation to the top segment bar using `drawWithContent { clipRect(...) }` masking, keeping exact original proportions. Added `animateFloatAsState` to animate numbers (from `0g` to actual grams) and circle progress with appropriate staggering delays (160ms initial delay).
- **NutritionPercentageRing Component**: Removed the inline `·` separator and plain percentage text. Created a custom `NutritionPercentageRing` component using Compose `Canvas`. Draws a neutral background track color mapped to the theme (light/dark support) and an active sweep colored arc (`-90` degrees start). `clampedProgress` ensures valid ranges, preserving 0% and 100% boundary safety. The percentage text is centered directly inside the ring.
- **Unit Tests Updated**: Modified Phase C3 test files to verify independent text nodes (`25g` and `42%`) rather than the removed string concat format (`25g · 42%`). Added a Compose animation clock-based test `foodConfirmCardAnimatesNutritionGramsAndRatios` to guarantee values explicitly animate from `0g` and `0%` to final state. All tests passing, ensuring no regression on edge function logic, DTOs, calculation mapping, or sync paths.
