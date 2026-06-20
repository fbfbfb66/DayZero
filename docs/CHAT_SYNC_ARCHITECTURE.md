# DayZero Chat Sync Architecture

Phase 6A established the remote schema and client-side data contract for AI conversation sync. Phase 6B adds local enqueue, Chat Push, and historical Chat Backfill. Chat Pull, multi-device merge, chat deletion UI, search, rename, pinning, and login/account binding are still not implemented.

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

## Conflict Rules For Future Phases

Conversation mutable fields (`title`, `last_message_preview`, `last_activity_at`, `deleted_at`) use deterministic last server version wins. `deleted_at` is a tombstone and old active uploads must not casually revive it. `conversation_date` is fixed after creation.

Message `role`, `created_at`, and `conversation_id` are immutable after creation. User text and final AI text are treated as immutable in normal operation. `assistant_cards` may change when a user edits, confirms, cancels, approves, or cancels a guard. Confirmed/cancelled cards must not be overwritten by older pending states, and date guards must not regress from approved/cancelled to pending.

## Phase Boundary

Phase 6B implements Chat Push and Chat Backfill only. It does not implement Chat Pull, multi-device merge, chat deletion UI, remote hard delete, account binding, or anonymous identity recovery after uninstall.

## Phase 6C-1 Chat Pull Transport

Phase 6C-1 implements the pure network transport for Chat Pull without integrating it into the production application lifecycle.
- **Pull Gateway**: `ChatRemotePullGateway` and `SupabaseChatRemotePullGateway` correctly map `ChatRemoteConversationPage` and `ChatRemoteMessagePage` data models directly from Supabase via OkHttp, strictly isolating DTO concerns. Explicit select fields are used instead of `select=*`.
- **Exact Cursor Precision**: The composite server cursor `(server_updated_at, id)` uses a precise ISO-8601 UTC String instead of epoch milliseconds to completely prevent PostgreSQL microsecond precision truncation. This has been verified via mocked PostgREST tests to preserve sub-millisecond differences (e.g. `.123001` vs `.123456`) and correctly paginate without duplicate records or skipping.
- **Parsing Tolerance**: `assistant_cards` JSONB content is natively retained via raw JSON string extraction without Moshi mapping, preserving all current and future nested fields.
- **Identity Refresh Lifecycle**: Chat Pull accurately integrates with the Supabase identity lifecycle. For 401/403 HTTP errors, the gateway attempts exactly one controlled `forceRefreshSession` before mapping to an identity or schema fatal failure. Temporary refresh errors (e.g., network timeouts) are treated as `RetryableFailure`, and permanent refresh rejection directly maps to `FatalFailure`.
- **Zero Local Mutations**: This phase reads strictly from the server. It still does not write to Room, does not implement conflict merge logic, does not push the formal `PullStateStore` cursor, and does not run inside the production `PullCoordinator`. 

The next phase remains Phase 6C-2: Conversation Merge.
