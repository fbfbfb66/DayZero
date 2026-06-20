# DayZero Chat Sync Architecture

Phase 6A establishes the remote schema and client-side data contract for future AI conversation sync. It does not turn on chat Push, Pull, Backfill, WorkManager scheduling, UI changes, or runtime merge behavior.

## Current Runtime Behavior

Room remains the only runtime source for AI conversations and chat messages:

- Local table `conversations` owns AI conversation metadata.
- Local table `ai_chat_messages` owns persisted user/assistant messages.
- Every local message has a non-null `conversationId`.
- Streaming reply deltas remain in memory and are not part of this sync contract.

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

## Conflict Rules For Future Phases

Conversation mutable fields (`title`, `last_message_preview`, `last_activity_at`, `deleted_at`) use deterministic last server version wins. `deleted_at` is a tombstone and old active uploads must not casually revive it. `conversation_date` is fixed after creation.

Message `role`, `created_at`, and `conversation_id` are immutable after creation. User text and final AI text are treated as immutable in normal operation. `assistant_cards` may change when a user edits, confirms, cancels, approves, or cancels a guard. Confirmed/cancelled cards must not be overwritten by older pending states, and date guards must not regress from approved/cancelled to pending.

## Phase Boundary

Phase 6A does not implement Chat Push, Chat Pull, Chat Backfill, chat sync scheduling, multi-device merge, chat deletion UI, remote deletion flow, account binding, or anonymous identity recovery after uninstall.

The next phase is Phase 6B: Chat Push plus Backfill.

