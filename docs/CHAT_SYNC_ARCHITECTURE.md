# DayZero Chat Sync Architecture

Phase 6A established the remote schema and client-side data contract for AI conversation sync. Phase 6B adds local enqueue, Chat Push, and historical Chat Backfill. Phase 6C-1 adds pull transport, Phase 6C-2 adds Conversation Merge, and Phase 6C-3 adds Message/Card Merge. Production Chat Pull lifecycle integration, chat deletion UI, search, rename, pinning, and login/account binding are still not implemented.

## Current Runtime Behavior

Room remains the only runtime source for AI conversations and chat messages:

- Local table `conversations` owns AI conversation metadata.
- Local table `ai_chat_messages` owns persisted user/assistant messages.
- Every local message has a non-null `conversationId`.
- Streaming reply deltas remain in memory and are not part of this sync contract.
- Local writes update Room first and enqueue chat sync in the same Room transaction where the repository owns the write path.
- Remote chat sync runs in the background through the existing sync queue and `LocalFirstSyncCoordinator`.

## Remote Tables

The Phase 6A remote table names are:

- `public.ai_conversations`
- `public.ai_chat_messages`

Local UUID strings are used directly as remote UUID primary keys. Upload code must not generate replacement remote ids.

## Conversation Contract

`ai_conversations` stores `id`, `user_id`, `conversation_date`, `title`, `last_message_preview`, `created_at`, `updated_at`, `last_activity_at`, `deleted_at`, `server_updated_at`, and `schema_version`.

`conversation_date` is a fixed natural date. It is not a timestamp and must not be shifted by device timezone during DTO mapping.

## Message Contract

`ai_chat_messages` stores `id`, `user_id`, `conversation_id`, `role`, `message_type`, `text`, `content_json`, `assistant_cards`, `suggested_replies_json`, `created_at`, `updated_at`, `deleted_at`, `server_updated_at`, and `schema_version`.

Message ordering for chat display remains `createdAt ASC, id ASC`, matching the local DAO. The remote table has an index on `(user_id, conversation_id, created_at, id)` for that stable order.

## Ownership

All chat rows are owned by Supabase Auth:

- `auth.uid() == ai_conversations.user_id`
- `auth.uid() == ai_chat_messages.user_id`
- `ai_chat_messages(conversation_id, user_id)` has a composite foreign key to `ai_conversations(id, user_id)`

This prevents orphan messages and prevents user A from attaching a message to user B's conversation. RLS policies allow authenticated users to select, insert, and update only their own rows. No hard delete grant or delete policy is added for the Android client; deletes are represented by `deleted_at` tombstones.

## Server Cursor

Business time and server sync time are separate:

- Business time: `created_at`, `updated_at`, `last_activity_at`, `deleted_at`
- Pull cursor time: `server_updated_at`

`server_updated_at` is set by the database on insert default and by trigger on every update. Future Pull must page by owner plus `(server_updated_at, id)` so same-timestamp rows are stable and no row is skipped.

## Card JSON

`assistant_cards` is `jsonb` and stores the full persisted `assistantCardsJson` payload. The client DTO contract keeps this as raw JSON, not as strongly typed cards, so future or unknown fields are not dropped during sync.

Remote sync must preserve `show_confirm_card`, `meals[]`, `weightKg`, edited card content, `confirmed` and `cancelled` card states, `ask_record_intent_card`, `ask_missing_info_card`, `debug_choice_card`, `date_mismatch_guard_card`, `pendingOriginalCard`, guard states, and unknown future card fields.

`null` means the local `assistantCardsJson` column was null. `[]` means an explicit empty card array and is distinct from null.

## Synced And Unsynced State

Future chat sync should include durable final state: conversation fixed date, title, preview, created/updated/last activity times, tombstone, final user messages, final assistant messages, full assistant card JSON, card edits, confirmed/cancelled state, and date guard state.

Future chat sync must not include transient UI/runtime state: `reply_delta`, `StreamingState`, `isAnalyzing`, typewriter progress, input drafts, selected route, active conversation UI state, keyboard/Compose state, or network error objects.

Only final assistant messages belong in the remote chat contract.

## Phase 6B Push Queue

Phase 6B wires two chat queue operations into the existing `sync_queue` table:

- `UPSERT_AI_CONVERSATION` with `entityType = ai_conversation`
- `UPSERT_AI_CHAT_MESSAGE` with `entityType = ai_chat_message`

The queue coalesces pending/retry/waiting tasks by owner, entity type, entity id, and operation. If an older snapshot is already `PROCESSING`, a later local change creates a new pending item so the latest local state is still uploaded after the in-flight task finishes.

Conversation changes enqueue when a conversation is inserted, its title/preview/activity/updated timestamp changes, or it receives a tombstone. Message changes enqueue for final user messages, final assistant messages, card edits, card confirm/cancel, and date guard approve/cancel because those paths update the same persisted message row.

Empty assistant placeholders are skipped. `reply_delta`, `StreamingState`, input drafts, route state, typewriter progress, and transient network errors are not serialized.

## Phase 6B Push Gateway

`RemoteSyncGateway` now includes chat upsert methods for `ai_conversations` and `ai_chat_messages`. `SupabaseRemoteSyncGateway` uses the existing `SupabaseAuthSessionProvider`; it does not sign up users itself, does not cache access tokens, and does not log full message text, card JSON, refresh tokens, or access tokens.

Chat upsert uses local UUIDs as remote primary keys and PostgREST `on_conflict=id`. Message JSON fields are sent as JSON values for the remote `jsonb` columns, not as double-encoded strings.

Queue ordering makes `UPSERT_AI_CONVERSATION` run before `UPSERT_AI_CHAT_MESSAGE`. If a message push hits a retryable parent/conflict condition such as HTTP 409, the coordinator re-enqueues the local parent conversation and keeps the message retryable.

## Phase 6B Backfill

`ChatBackfillCoordinator` scans existing Room conversations first and messages second. It uses stable `(createdAt, id)` pagination and stores progress in `ChatBackfillStateStore`, so interrupted backfill can resume.

Backfill only enqueues snapshots. It does not block UI and does not directly perform pull or merge. It skips empty assistant placeholders and records the skipped count. Re-running backfill is idempotent because the queue coalesces or skips duplicate active snapshots.

Scheduler order is effectively:

1. existing sync queue push, including business Push and Chat Push;
2. business Backfill;
3. Chat Backfill;
4. existing sync queue push again, including Chat Push;
5. existing business Pull.

There is no Chat Pull step in Phase 6B.

## Phase 6B Validation Status

Phase 6B Chat Push and Backfill have been fully verified on a real device:
- **Real-Device Push Verified**: New conversation sessions and final messages successfully push to Supabase.
- **Unique Assistant Final**: Only the final assistant response is persisted and pushed. Temporary placeholders and `reply_delta` text/tokens are not uploaded.
- **Native Card JSONB**: The `assistant_cards` JSONB column contains raw JSON without double-string encoding.
- **Card Reuse of Message ID**: Card confirmations/updates safely modify the `assistant_cards` state (e.g. `state=confirmed`) on the *same* remote message ID rather than generating duplicate message rows.
- **Idempotency & Backfill Stability**: The post-restart sync queue execution and repeated backfill sync run with perfect idempotency, keeping the remote database row counts stable (e.g., 3 conversations and 16 messages in verification tests) with no duplicates.
- **Automatic Scheduling**: Chat push is automatically scheduled and executed in the background by the existing `SyncScheduler`.
- **Security & RLS Enforcement**: Remote API client attempts to mutate `user_id` on existing rows or execute hard SQL `DELETE` queries are successfully rejected with `HTTP 403 Forbidden` errors.
- **Pull Status**: Chat Pull is still not implemented.

## Conflict Rules

Conversation mutable fields (`title`, `last_message_preview`, `last_activity_at`, `deleted_at`) use deterministic last server version wins. `deleted_at` is a tombstone and old active uploads must not casually revive it. `conversation_date` is fixed after creation.

Message `id`, `conversation_id`, `role`, `message_type`, and `created_at` are immutable after creation. User final text is immutable. Assistant final text is immutable except for the specific local placeholder to remote final transition. `assistant_cards`, `content_json`, and `suggested_replies_json` are mutable clean-message fields subject to tombstone, dirty, and card state-machine rules. Confirmed/cancelled cards must not be overwritten by older pending states, and date guards must not regress from approved/cancelled to pending.

## Phase Boundary

Phase 6C-3 implements local Message/Card Merge but still does not wire Chat Pull into production lifecycle. It does not implement chat deletion UI, remote hard delete, account binding, or anonymous identity recovery after uninstall.

## Phase 6C-1 Chat Pull Transport

Phase 6C-1 implements the pure network transport for Chat Pull without integrating it into the production application lifecycle.
- **Pull Gateway**: `ChatRemotePullGateway` and `SupabaseChatRemotePullGateway` correctly map `ChatRemoteConversationPage` and `ChatRemoteMessagePage` data models directly from Supabase via OkHttp, strictly isolating DTO concerns. Explicit select fields are used instead of `select=*`.
- **Exact Cursor Precision**: The composite server cursor `(server_updated_at, id)` uses a precise ISO-8601 UTC String instead of epoch milliseconds to completely prevent PostgreSQL microsecond precision truncation. This has been verified via mocked PostgREST tests to preserve sub-millisecond differences (e.g. `.123001` vs `.123456`) and correctly paginate without duplicate records or skipping.
- **Parsing Tolerance**: `assistant_cards` JSONB content is natively retained via raw JSON string extraction without Moshi mapping, preserving all current and future nested fields.
- **Identity Refresh Lifecycle**: Chat Pull accurately integrates with the Supabase identity lifecycle. For 401/403 HTTP errors, the gateway attempts exactly one controlled `forceRefreshSession` before mapping to an identity or schema fatal failure. Temporary refresh errors (e.g., network timeouts) are treated as `RetryableFailure`, and permanent refresh rejection directly maps to `FatalFailure`.
- **Zero Local Mutations**: This phase reads strictly from the server. It still does not write to Room, does not implement conflict merge logic, does not push the formal `PullStateStore` cursor, and does not run inside the production `PullCoordinator`. 

The next phase remains Phase 6C-2: Conversation Merge.

## Phase 6C-2 Chat Remote Pull Conversation Merge

Phase 6C-2 implements conversation-only remote pull merge. It does not implement Message/Card Merge and is not wired into the production pull scheduler yet.

- **Direct DAO Writing (No Push Loop)**: `ChatConversationRemoteMerger` writes remote conversation snapshots directly through `ConversationDao` and never enqueues `SyncQueue` items while applying remote state.
- **Dirty Query API**: `SyncQueueDao.countActiveTasksForEntity(...)` remains a generic operation-agnostic query for existing daily record pull behavior. Conversation merge uses `countActiveTasksForEntityAndOperation(...)` with `entityType = ai_conversation`, `operation = UPSERT_AI_CONVERSATION`, owner filtering (`ownerLocalId` or legacy `local_uninitialized`), entity id filtering, and active statuses (`PENDING`, `PROCESSING`, `FAILED_RETRYABLE`, `WAITING_FOR_AUTH`). Other chat operations such as `UPSERT_AI_CHAT_MESSAGE` do not make a conversation dirty.
- **Owner And Cursor Scope**: dirty checks use local queue owner identity (`identity.localOwnerId`). Pull cursor state uses Supabase `identity.remoteUserId`, matching `auth.uid()`. These identities are intentionally separate and must not be interchanged.
- **Tombstone Rules**: local tombstones are monotonic for ordinary Pull: local deleted plus remote active stays deleted, even when timestamps tie or remote is newer. Remote tombstones soft-delete clean active local records when `remote.updatedAt >= local.updatedAt`; old remote tombstones are ignored when local clean active is newer; dirty local records defer both remote active and remote tombstone snapshots.
- **Existing Parent Safety**: when a local conversation already exists, mutable remote changes are applied only with `UPDATE` (`updateConversationSummary` or `softDeleteConversation`). The merger inserts only when no local parent row exists, avoiding SQLite `REPLACE` delete/reinsert behavior and protecting `ai_chat_messages` from `ON DELETE CASCADE`.
- **Immutable Conflicts**: mismatched `conversationDate` or `createdAt` throws `ImmutableConflictException` out of the Room `withTransaction` block. The whole page is rolled back, the cursor is not saved, the queue is unchanged, and existing messages remain unchanged.
- **Exact-Timestamp Tie**: when local has no active matching push queue and business `updatedAt` is equal but mutable fields differ, the remote mutable state is accepted as the deterministic convergence result. This rule does not apply to immutable fields or tombstone restoration.
- **Cursor Persistence**: `ChatConversationPullStateStore` stores `serverUpdatedAt` and `id` together under the Supabase remote user id with synchronous `commit()`. If saving fails, the coordinator returns retryable failure so the page can be replayed.
- **Tests**: regression tests cover operation-specific dirty checks, generic daily-record dirty behavior, tombstone monotonicity, repeated page application, exact timestamp ties, old remote rows, soft delete, message row preservation, immutable conflict page rollback, cursor user isolation, and refresh user-id mismatch blocking.

### Phase 6C-2 Regression Log

Executed on 2026-06-21 with `JAVA_HOME = C:\Program Files\Android\Android Studio\jbr`:

- `./gradlew --stop`: SUCCESS, stopped 1 Gradle daemon.
- `./gradlew clean`: SUCCESS.
- `./gradlew :core:database:testDebugUnitTest`: SUCCESS; task was `NO-SOURCE`.
- `./gradlew :core:data:testDebugUnitTest`: SUCCESS; task was `NO-SOURCE`.
- `./gradlew :core:sync:testDebugUnitTest`: SUCCESS; formal run reused the build cache after the same source set had already passed in a focused pre-regression run.
- `./gradlew :app:testDebugUnitTest`: SUCCESS; formal run reused the build cache after the same source set had already passed in a focused pre-regression run.
- `./gradlew :app:assembleDebug`: SUCCESS; debug APK assembled. Gradle warned that `libandroidx.graphics.path.so` could not be stripped and was packaged as-is.
- `./gradlew test`: SUCCESS.

Phase 6C-3 has now implemented Message/Card Merge. Production Pull lifecycle integration, global `PullCoordinator` integration, `SyncScheduler` integration, UI changes, AI prompt changes, and Edge Function changes remain out of scope.

## Phase 6C-3 Chat Remote Pull Message/Card Merge

Phase 6C-3 implements local Message Pull merge capability behind explicit classes only:

- `ChatMessagePullCoordinator`
- `ChatMessagePullStateStore`
- `ChatMessageRemoteMerger`
- `ChatMessageCardMergePolicy`

It is not connected to the production `PullCoordinator`, `SyncScheduler`, WorkManager flow, UI, AI prompt, Edge Function, or login system.

### Message Merge Flow

The page flow is:

1. `ChatMessagePullCoordinator` reads `identity.remoteUserId`.
2. It reads the independent message cursor from `ChatMessagePullStateStore`.
3. It calls `ChatRemotePullGateway.fetchMessagePage(...)`.
4. It passes the full page to `ChatMessageRemoteMerger`.
5. The merger runs one Room `withTransaction` for the entire page.
6. After successful transaction commit only, the coordinator saves `(serverUpdatedAt, id)` for the same Supabase remote user id.

Network fetch is outside the Room transaction. Cursor save failure is retryable and leaves the page replayable.

### Parent And Orphan Rules

Each remote message must reference an existing local parent conversation. Active parents and tombstoned parents are accepted. Missing parents throw `MissingParentConversationException`, roll back the full page, and prevent cursor advancement. The merger never creates a synthetic conversation and never inserts orphan messages.

### Dirty Query

Message dirty detection uses the operation-specific DAO query:

`countActiveTasksForEntityAndOperation(ownerLocalId, entityType = ai_chat_message, entityLocalId = messageId, operation = UPSERT_AI_CHAT_MESSAGE)`.

The SQL filters owner (`identity.localOwnerId` or legacy `local_uninitialized`), entity type, entity id, operation, and active statuses: `PENDING`, `PROCESSING`, `FAILED_RETRYABLE`, `WAITING_FOR_AUTH`. `DONE`, `FAILED_FATAL`, other owners, other entity types, and other operations do not make the message dirty. Dirty local messages are deferred; their queue rows are not deleted or modified. Remote apply creates no new queue rows.

### Final Text And Placeholder Rules

For existing rows:

- User final text is immutable. Any remote user text mismatch is `ImmutableMessageContentConflict`.
- Assistant local placeholder plus remote final is allowed when immutable fields match. It completes the same local row and does not create a second message.
- Assistant final plus remote placeholder is invalid remote data and halts the page.
- Assistant final plus remote final with different text is `ImmutableMessageContentConflict`.
- Same final text may still merge cards, content JSON, and suggested replies.

Streaming state is in memory and keyed by conversation in `RemoteAiDraftRepository`; Message Merge does not depend on Compose/UI state and does not call repository update paths. A remote final can complete the persistent placeholder row directly through DAO remote-apply update.

Unsupported future `schemaVersion` values are fatal. They are not silently downgraded.

### Tombstone Rules

Remote `ai_chat_messages.deleted_at` is monotonic. The local `AiChatMessageEntity` in Room version 11 added `updatedAt` and `deletedAt` columns. Remote message tombstones map directly to the `deletedAt` column, preserving original text, `assistantCardsJson`, `suggestedRepliesJson`, and original `contentJson`.

Rules:

- Local tombstone plus remote active stays tombstoned.
- Clean active local plus remote tombstone applies a tombstone when the remote timestamp is not older than the local comparable timestamp.
- Old remote tombstone with newer local active is ignored.
- Dirty local plus remote active/tombstone is deferred.
- Missing local plus remote tombstone inserts a marked tombstone message row to preserve cursor-reset monotonicity.

No hard delete is used. Tombstoning a message never deletes DailyRecord data, meals, food entries, weight data, or card payload history.

### Card JSON Merge

`ChatMessageCardMergePolicy` parses `assistantCardsJson` as a generic JSON tree, not as domain DTOs. Cards align by `id`; same id with different `type` throws `CardMergeConflictException` and rolls back the page. Unknown card types and unknown fields are preserved. Nested objects, `pendingOriginalCard`, `meals`, `weightKg`, null, `{}`, and `[]` retain their semantic distinctions. JSON key order is ignored for equality.

`show_confirm_card` state order is `pending < cancelled < confirmed`. `confirmed` wins over `cancelled`, terminal states never return to `pending`, and repeated application is idempotent.

`date_mismatch_guard_card` states are `pending`, `approved`, and `cancelled`. Terminal states never return to `pending`. `approved` versus `cancelled` resolves to `approved` only when the merged nested original show-confirm card is `confirmed`; otherwise `cancelled` wins. The nested original card remains preserved for history in both outcomes.

Cards without a special state machine keep complete JSON and choose the clean newer-or-tie snapshot deterministically while preserving missing fields from the other side.

### Side Effects

Remote Message Merge can modify only `AiChatMessageEntity` rows and message pull cursor state. It does not call:

- `DayZeroViewModel`
- `ConfirmFoodRecordUseCase`
- AI repositories
- interaction result handlers
- `ChatSyncQueueWriter`
- `SyncScheduler`
- ordinary `AiDraftRepository` insert/update paths

Pulling a `show_confirm_card(state = confirmed)` restores chat history only. It does not write DailyRecord data or enqueue business sync.

### Cursor Scope

Message cursor storage is separate from conversation cursor storage. Both save exact `(serverUpdatedAt, id)` ISO-8601 strings, but each has its own SharedPreferences file. Cursor keys use Supabase `identity.remoteUserId`, matching `auth.uid()`. Dirty checks use the local queue owner `identity.localOwnerId`; the two identities are intentionally not interchangeable.

### Phase 6C-3 Focused Test Coverage

In addition to merger tests (`ChatMessageRemoteMergerTest`, `ChatMessagePullCoordinatorTest`, and `ChatMessageCardMergePolicyTest`), new dedicated verification tests have been added to fully close Phase 6C-3 verification gaps:

1. **Migration Verification (`Migration10to11Test`)**:
   - Creates a real SQLite database file, sets up the full V10 database schema (including `daily_records`, `sync_queue`, `conversations`, and `ai_chat_messages` tables, foreign keys, and indexes), sets version to 10, inserts 8 historical records covering user message, assistant message, card-only message, null/empty/array `contentJson`, unknown card fields, and multiple messages per conversation.
   - Upgrades database to V11 using `Room.databaseBuilder` and `DayZeroDatabase.MIGRATION_10_11` with `.allowMainThreadQueries()`.
   - Asserts that all rows and columns are correctly preserved, `updatedAt` is updated to match `createdAt`, `deletedAt` is default-initialized to `null`, and indexes/foreign keys exist (validated via SQLite PRAGMA queries).

2. **Repository Tombstone Competition Testing (`RemoteAiDraftRepositoryTombstoneTest`)**:
   - Asserts repository-level mutation boundaries on a real database instance:
     - **Streaming Final**: Submitting final text to a locally tombstoned placeholder affects 0 rows, preserving `deletedAt`, keeping text empty, hiding from active queries, generating no sync queue entries, and leaving conversation summary untouched.
     - **Fallback Final**: Submitting fallback response to a tombstoned placeholder behaves identically (affects 0 rows).
     - **Card Update**: Proves that lookups for tombstoned cards return null, and updates affect 0 rows with no sync queue tasks or summary modifications.
     - **Active Message normal update (Positive Control)**: Verifies normal updates still work (updates text, advances `updatedAt`, generates sync queue entry with `ai_chat_message` entity type, and updates conversation summary).

3. **Chat Sync Backfill Testing (`DayZeroChatSyncBackfillTest`)**:
   - Verifies `ChatBackfillCoordinator` behaves correctly:
     - **Persisted `updatedAt`**: Serializes payloads with exact persisted `updatedAt` (formatted as ISO-8601 UTC string), never using current time.
     - **Tombstone Backfill**: Outputs correct `deletedAt` in backfill payload.
     - **Placeholder Skip**: Skips empty assistant placeholder messages (does not enqueue).
     - **Card-only Final**: Successfully enqueues assistant messages that contain only cards (empty text).
     - **Idempotency**: Re-running backfill coalesces rather than duplicates sync tasks, keeping the payload stable.

### Phase 6C-3 Regression Log

Executed on 2026-06-21 with `JAVA_HOME = C:\Program Files\Android\Android Studio\jbr`:

- `./gradlew --stop`: SUCCESS, stopped Gradle daemons.
- `./gradlew clean`: SUCCESS.
- `./gradlew :core:database:testDebugUnitTest --rerun-tasks`: SUCCESS, ran and passed Room schema validation tests.
- `./gradlew :core:data:testDebugUnitTest --rerun-tasks`: SUCCESS, ran and passed data module tests (NO-SOURCE, passed).
- `./gradlew :core:sync:testDebugUnitTest --rerun-tasks`: SUCCESS, ran and passed sync module tests.
- `./gradlew :app:testDebugUnitTest --rerun-tasks`: SUCCESS, ran and passed all VM, migration, repository tombstone race, and backfill tests.
- `./gradlew :app:assembleDebug`: SUCCESS, debug build successfully compiled.
- `./gradlew test --rerun-tasks`: SUCCESS, full test regression passed successfully.

The next phase is Phase 6D: production lifecycle orchestration for Chat Pull (currently not started, production pull lifecycle integration is pending).
