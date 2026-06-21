# DayZero Supabase Schema Verification

This document is the DB-SYNC-11A verification checklist for the remote sync schema. The Android app must continue to use Room as the local source of truth. Supabase Table Editor is only for observing schema and data during development; it is not a business-data source for UI. SQL migrations are the versioned source of truth.

## Expected Tables

The app expects these public tables:

- `daily_records`
- `meals`
- `food_entries`
- `weight_records`
- `ai_conversations`
- `ai_chat_messages`

The current canonical migration source of truth is:

- `supabase/migrations/20260619023000_dayzero_core_records_schema.sql`
- `supabase/migrations/20260621060000_dayzero_ai_chat_sync_schema.sql` for Phase 6A chat schema

The older `20260618120000_dayzero_core_records.sql` file is a historical design draft and does not define the current canonical field names.

## Canonical Common Fields

Each business table must include:

- `id uuid primary key default gen_random_uuid()`
- `user_id uuid not null references auth.users(id) on delete cascade`
- `client_id text not null`
- `created_at timestamptz not null default now()`
- `updated_at timestamptz not null default now()`
- `deleted_at timestamptz null`
- `schema_version int not null default 1`

Each business table must include `unique(user_id, client_id)` for idempotent PostgREST upsert.

## Canonical Table Fields

`daily_records`:

- `local_date date not null`
- `timezone text null`
- `note text null`

`meals`:

- `daily_record_client_id text not null`
- `meal_type text null`
- `logged_at timestamptz null`
- `display_order int null`

`food_entries`:

- `meal_client_id text not null`
- `name text not null`
- `amount_text text null`
- `grams numeric null`
- `calories numeric null`
- `protein_g numeric null`
- `carbs_g numeric null`
- `fat_g numeric null`
- `confidence numeric null`
- `source text null`

`weight_records`:

- `local_date date not null`
- `measured_at timestamptz null`
- `weight_kg numeric not null`
- `source text null`

## Canonical Field Names

Push and pull must use the same canonical remote fields:

- `daily_records.local_date`
- `meals.daily_record_client_id`
- `food_entries.meal_client_id`
- `food_entries.amount_text`
- `food_entries.grams`
- `food_entries.calories`
- `food_entries.protein_g`
- `food_entries.carbs_g`
- `food_entries.fat_g`
- `weight_records.local_date`
- `weight_records.measured_at`
- `weight_records.weight_kg`

Do not write new remote rows using these legacy names:

- `record_date`
- `quantity`
- `estimated_calories`
- `measured_date`

Temporary legacy compatibility exists only in the Android pull parser so old/manual rows can still be read:

- `daily_records.record_date` -> `local_date`
- `food_entries.quantity` -> `amount_text`
- `food_entries.estimated_calories` -> `calories`
- `weight_records.measured_date` -> `local_date`

This compatibility layer is temporary. After real Supabase data is confirmed to use only the canonical schema, remove the alias reads from `SupabaseRemotePullGateway`.

## Relation Reconstruction

Remote pull must rebuild local relationships from stable client ids:

- `daily_records.client_id` identifies the local daily record.
- `meals.daily_record_client_id` points to `daily_records.client_id`.
- `food_entries.meal_client_id` points to `meals.client_id`.
- `weight_records.local_date` attaches weight to a local daily record by date in the current local aggregate model.

Remote pull must not depend on remote auto-increment ids or Table Editor row order.

## RLS And Policies

RLS must be enabled on every business table:

- `alter table public.daily_records enable row level security;`
- `alter table public.meals enable row level security;`
- `alter table public.food_entries enable row level security;`
- `alter table public.weight_records enable row level security;`

Each table must have policies that restrict rows to `(select auth.uid()) = user_id` for:

- `select using ((select auth.uid()) = user_id)`
- `insert with check ((select auth.uid()) = user_id)`
- `update using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id)`
- `delete using ((select auth.uid()) = user_id)`

Postgres RLS requires a matching `select` policy for updates to find rows. Do not remove select policies when debugging update failures.

## Data API Access

Recent Supabase projects may not expose new tables to the Data API automatically. If PostgREST returns table-access errors while RLS policies are correct, check the project's Data API settings and grants. When granting access to `anon` or `authenticated`, RLS must remain enabled.

The migration grants `select`, `insert`, `update`, and `delete` on the four business tables to `authenticated` and revokes table privileges from unauthenticated `anon`. Android anonymous sign-in uses an authenticated session. The Android client may use only the configured publishable/anon key. A `service_role` key must never be stored in Android source, resources, generated build config, logs, or runtime preferences.

For Phase 6A chat tables, the migration grants only `select`, `insert`, and `update` to `authenticated` and revokes all table privileges from `anon`. No hard-delete policy or grant is added for `ai_conversations` or `ai_chat_messages`; future deletion sync must use `deleted_at` tombstones.

## Phase 6A Chat Tables

`ai_conversations` includes `id`, `user_id default auth.uid()`, `conversation_date`, `title`, `last_message_preview`, `created_at`, `updated_at`, `last_activity_at`, `deleted_at`, `server_updated_at`, `schema_version`, and `unique(id, user_id)`.

`ai_chat_messages` includes `id`, `user_id default auth.uid()`, `conversation_id`, `role`, `message_type`, `text`, `content_json`, `assistant_cards`, `suggested_replies_json`, `created_at`, `updated_at`, `deleted_at`, `server_updated_at`, `schema_version`, and a composite foreign key `(conversation_id, user_id)` to `ai_conversations(id, user_id)`.

Required chat indexes:

- `ai_conversations_user_server_cursor_idx` on `(user_id, server_updated_at, id)`
- `ai_conversations_user_active_activity_idx` on `(user_id, last_activity_at desc, id)` where `deleted_at is null`
- `ai_conversations_user_deleted_at_idx` on `(user_id, deleted_at)`
- `ai_conversations_user_conversation_date_idx` on `(user_id, conversation_date)`
- `ai_chat_messages_user_server_cursor_idx` on `(user_id, server_updated_at, id)`
- `ai_chat_messages_conversation_owner_fk_idx` on `(conversation_id, user_id)`
- `ai_chat_messages_conversation_order_idx` on `(user_id, conversation_id, created_at, id)`
- `ai_chat_messages_user_deleted_at_idx` on `(user_id, deleted_at)`

RLS policies:

- `ai_conversations_select_own`
- `ai_conversations_insert_own`
- `ai_conversations_update_own`
- `ai_chat_messages_select_own`
- `ai_chat_messages_insert_own_conversation`
- `ai_chat_messages_update_own_conversation`

Message insert/update policies require both `auth.uid() = user_id` and an owned parent conversation. The composite foreign key provides the same ownership guard at the database constraint layer.

The server cursor for chat is `server_updated_at`, not business `updated_at`. Future Pull must use `(server_updated_at, id)` as a two-part cursor.

## Auth Configuration

DayZero currently relies on Supabase Anonymous Sign-Ins for invisible remote identity. In the Supabase Dashboard for project `sybenxmxnwwtlvkeojtj`, enable:

- Authentication -> Providers -> Anonymous Sign-Ins

If this is disabled, `auth/v1/signup` returns `anonymous_provider_disabled`, the app remains local-first, and remote push/pull is skipped or delayed. This is an Auth project setting, not a SQL migration setting.

## Sync Semantics

`client_id` is the stable local entity id used for idempotent upsert. The gateway sends PostgREST writes with `on_conflict=user_id,client_id`.

`updated_at` is written by the Android gateway and also protected by a database trigger for updates.

Remote pull depends on `updated_at` being sortable and present on every synced table. Incremental pull uses `updated_at > cursor` and orders by `updated_at.asc`.

Current pull cursors are per table and use `updated_at` only. The Android pull coordinator detects the unsafe case where a page has `hasMore=true` and multiple rows share the same `updated_at`; in that case it records a partial pull failure and does not advance that table cursor. TODO: if real production data reaches this edge case, upgrade the cursor model to `updated_at + client_id`.

Deletes are soft deletes:

- Set `deleted_at` to a timestamp.
- Set `updated_at` to the current timestamp.
- Do not use SQL `DELETE` for normal record sync.

## Push DTO Field Mapping

`SupabaseRemoteSyncGateway` writes:

- daily record: `user_id`, `client_id`, `local_date`, `timezone`, `note`, common fields
- meal: `user_id`, `client_id`, `daily_record_client_id`, `meal_type`, `logged_at`, `display_order`, common fields
- food entry: `user_id`, `client_id`, `meal_client_id`, `name`, `amount_text`, `grams`, `calories`, `protein_g`, `carbs_g`, `fat_g`, `confidence`, `source`, common fields
- weight record: `user_id`, `client_id`, `local_date`, `measured_at`, `weight_kg`, `source`, common fields

The gateway must not write `record_date`, `quantity`, `estimated_calories`, `daily_record_id`, `meal_id`, `raw_estimate_json`, or `service_role` credentials.

## Pull DTO Field Mapping

`SupabaseRemotePullGateway` reads:

- daily record: `client_id`, `local_date`, `timezone`, `note`, `created_at`, `updated_at`, `deleted_at`, `schema_version`
- meal: `client_id`, `daily_record_client_id`, `meal_type`, `logged_at`, `display_order`, `created_at`, `updated_at`, `deleted_at`, `schema_version`
- food entry: `client_id`, `meal_client_id`, `name`, `amount_text`, `grams`, `calories`, `protein_g`, `carbs_g`, `fat_g`, `confidence`, `source`, `created_at`, `updated_at`, `deleted_at`, `schema_version`
- weight record: `client_id`, `local_date`, `measured_at`, `weight_kg`, `source`, `created_at`, `updated_at`, `deleted_at`, `schema_version`

Single malformed rows should be skipped with `DayZeroRemote` logs. They must not crash the whole pull or block UI.

## Table Editor Checklist

For each table, verify:

- The table exists in `public`.
- Required common fields exist with expected types.
- Table-specific fields exist with expected types.
- `deleted_at` exists and is nullable.
- `updated_at` exists and is `timestamptz`.
- `unique(user_id, client_id)` exists.
- RLS is enabled.
- Select/insert/update/delete policies use `(select auth.uid()) = user_id`.
- The `authenticated` role can select/insert/update/delete through the Data API.
- `client_id` is not null and is stable across retries.
- `schema_version` exists and defaults to `1`.
- `updated_at` can be filtered and sorted through PostgREST.
- `deleted_at` is returned by select queries.

For relation reconstruction, verify:

- `meals.daily_record_client_id` exists.
- `food_entries.meal_client_id` exists.
- `weight_records.local_date` exists.
- Remote pull does not depend on remote auto-increment ids.

## SQL Editor And Migration Execution

Preferred deployment is applying `supabase/migrations/20260619023000_dayzero_core_records_schema.sql` to project `sybenxmxnwwtlvkeojtj` through Supabase MCP or SQL Editor.

For Phase 6A chat schema, also apply `supabase/migrations/20260621060000_dayzero_ai_chat_sync_schema.sql`.

If automated Postgres/RLS integration tests are unavailable, run `supabase/verification/20260621060000_verify_ai_chat_sync_schema.sql` after applying the migration and record which checks were executed manually. Do not mark the schema as deployed or verified unless the SQL has actually run against the target Supabase project.

Phase 6A deployment note: on 2026-06-21, `20260621060000_dayzero_ai_chat_sync_schema.sql` was applied to Supabase project `sybenxmxnwwtlvkeojtj` via MCP. Static read-back verification confirmed both tables, primary keys, the composite message owner foreign key, RLS enabled, owner policies, server cursor indexes, trigger installation, and grants limited to `authenticated` select/insert/update.

Phase 6B RLS probe note: on 2026-06-21, two real anonymous authenticated sessions were created with the publishable anon key. Verified through PostgREST that user A could insert/read/update an owned conversation, user B could not read user A's conversation rows, user B's attempted update did not change user A's row, user B was rejected with HTTP 403 when attempting to attach a chat message to user A's conversation, and unauthenticated access returned HTTP 401. A powershell script confirmed that modifying `user_id` to another user resulted in HTTP 403 and the row retained its original `user_id`. Attempting a hard DELETE also resulted in HTTP 403 and the row was unaffected. The probe row was successfully tombstoned after verification.

If using SQL Editor:

1. Open project `sybenxmxnwwtlvkeojtj`.
2. Paste the migration SQL exactly.
3. Run it once.
4. Verify the four public tables appear.
5. Do not make untracked schema edits in Table Editor.

## Idempotency Verification

To verify duplicate upsert behavior:

1. Use one authenticated anonymous user.
2. Upsert a row with a fixed `client_id`.
3. Upsert the same logical row again with the same `user_id` and `client_id`.
4. Confirm the table still has one row for that pair.
5. Confirm `updated_at` changes on the second write.

## RLS Verification

To verify isolation:

1. Create or use two different authenticated anonymous users.
2. Insert rows for user A.
3. Query as user B through PostgREST.
4. Confirm user B cannot select user A rows.
5. Confirm user B cannot update or soft-delete user A rows.

Phase 6B chat-specific RLS checks should additionally confirm:

1. User B cannot insert `ai_chat_messages` using user A's `conversation_id`.
2. A client cannot mutate `user_id`.
3. The Android client cannot hard delete `ai_conversations` or `ai_chat_messages`.

## Real Supabase Verification Checklist

Use this checklist for real-device verification:

1. Start the app on a real device with the configured Supabase URL and Android anon/publishable key.
2. Confirm logs show a real anonymous identity from `SupabaseAnonymousIdentityProvider`.
3. If anonymous sign-in fails with `anonymous_provider_disabled`, enable Anonymous Sign-Ins in the Supabase Dashboard before continuing.
4. Confirm Android source, resources, generated build config, logs, and preferences do not contain a `service_role` key.
5. In the app, send `中午吃了一份鸡蛋肠粉`, wait for the AI reply and `show_confirm_card`, then tap confirm.
6. Confirm Room has a local daily record, meal, and food entry. UI must show the record from Room without waiting for Supabase.
7. Confirm `sync_queue` is created and processed.
8. In Supabase Table Editor, check `daily_records` for the new row.
9. Check `meals` for the related row and confirm `daily_record_client_id` is present.
10. Check `food_entries` and confirm `meal_client_id` is present.
11. If the confirmation includes weight, check `weight_records`.
12. For each row, confirm `user_id`, `client_id`, `updated_at`, `deleted_at`, and `schema_version` exist.
13. Confirm `user_id` matches the authenticated anonymous user id for the device session.
14. Sort each table by `updated_at` and confirm rows can be ordered for incremental pull.
15. Repeat sync with the same `client_id` and confirm `unique(user_id, client_id)` prevents duplicate rows.
16. Use the debug-only local business-record clear helper, keeping identity/session/chat/config intact.
17. Trigger initial restore.
18. Confirm pull reads `daily_records`, `meals`, `food_entries`, and `weight_records`.
19. Confirm Room is repopulated and UI displays restored records through Room.
20. Confirm initial restore does not generate new push `sync_queue` tasks.

## Phase 6B Chat Push Verification

For Chat Push and Backfill, verify:

1. Create a conversation and send one user message.
2. Wait for one final assistant reply.
3. Confirm `ai_conversations` has one row for that conversation id.
4. Confirm `ai_chat_messages` has the final user message and final assistant message.
5. Confirm no empty assistant placeholder row was uploaded.
6. Edit/confirm/cancel a card and confirm the same remote message row updates rather than inserting a new row.
7. Restart the app and retry sync; confirm duplicate rows are not created.
8. Run Chat Backfill and confirm existing local conversations/messages are enqueued and pushed after conversations.

Phase 6B Chat Push and Backfill have been fully verified on a real device. The following checklist was completed:
- **Verified**: Created new conversation (`PHASE6B_PUSH_TEST_1`) and sent user message. The new conversation row is successfully inserted in `ai_conversations` and the messages in `ai_chat_messages`.
- **Verified**: The assistant final reply is unique. No empty placeholder or `reply_delta` messages are uploaded.
- **Verified**: The `assistant_cards` field contains native JSONB without double-encoding.
- **Verified**: Confirming/editing card state updates the *same* remote message ID, instead of creating duplicate message rows.
- **Verified**: Post-restart sync queue execution is idempotent. Repeating backfill does not create duplicate rows; the remote database tables contain exactly 3 conversations and 16 messages and remain stable.
- **Verified**: Chat push is executed automatically in the background by `SyncScheduler`.
- **Verified**: Remote API security restrictions are enforced; any client-side attempt to mutate `user_id` or perform SQL `DELETE` operations is rejected with `HTTP 403 Forbidden`.
- **Note**: Chat Pull integration into the production lifecycle is still not implemented.

## Phase 6C-1 Chat Pull Transport Verification

Phase 6C-1 read access and pagination mechanisms have been verified against the Supabase deployment. The following checklist was completed:
- **Verified**: Configured `SupabaseChatRemotePullGateway` correctly reads `ai_conversations` and `ai_chat_messages` using the anonymous authenticated session.
- **Verified**: Only rows belonging to the current user are retrieved, confirming RLS policies work during read operations.
- **Verified**: Composite cursor `(server_updated_at, id)` generates correct PostgREST `or` filters resulting in stable multi-page iteration with no duplicates.
- **Verified**: Microsecond precision for `server_updated_at` is preserved correctly by strictly using ISO-8601 UTC strings instead of epoch milliseconds, preventing truncation. Verifications handled edge cases like sub-millisecond differences (e.g. `.123001` vs `.123456`) successfully.
- **Verified**: `deleted_at` timestamps correctly parse into tombstones without row exclusion at the network level.
- **Verified**: Raw JSONB extraction successfully extracts the `assistant_cards` field exactly as it exists in Supabase without field truncation or Moshi coercion errors.
- **Verified**: 401/403 HTTP errors properly trigger exactly one controlled `forceRefreshSession` and retry. Temporary and permanent identity errors correctly map to `RetryableFailure` and `FatalFailure`.
- **Note**: No local database modifications were made. The results are contained purely within remote data models.

## Phase 6C-2 Conversation Merge Verification

Phase 6C-2 is client-side Room merge work and does not change the Supabase schema, RLS policies, grants, triggers, indexes, or migrations.

- **Verified locally**: Conversation dirty detection uses the dedicated `countActiveTasksForEntityAndOperation(...)` query with owner, entity type, entity id, `UPSERT_AI_CONVERSATION`, and active status filters. The generic `countActiveTasksForEntity(...)` query remains operation-agnostic for existing daily record pull behavior.
- **Verified locally**: `local_uninitialized` is treated only as a legacy local queue owner fallback. Supabase cursor scope uses `identity.remoteUserId`, matching `auth.uid()`, and is not mixed with local owner ids.
- **Verified locally**: Tombstone merge is monotonic. Local deleted conversations are not resurrected by ordinary remote active pull; clean active conversations apply new remote tombstones; old remote tombstones are ignored; dirty local rows defer remote active and tombstone snapshots.
- **Verified locally**: Existing local conversation parents are updated with `UPDATE` only, avoiding `REPLACE` cascade risk for `ai_chat_messages`.
- **Verified locally**: Immutable conversation conflicts throw out of the Room page transaction, rolling back prior rows in that page and preventing cursor advancement.
- **Verified locally**: Exact timestamp ties accept remote mutable fields only when local has no active matching push queue and immutable fields match. This rule does not restore tombstones.
- **Regression run**: On 2026-06-21, `./gradlew clean`, `:core:database:testDebugUnitTest`, `:core:data:testDebugUnitTest`, `:core:sync:testDebugUnitTest`, `:app:testDebugUnitTest`, `:app:assembleDebug`, and `./gradlew test` all completed successfully.
- **Note**: Phase 6C-3 Message/Card Merge has not started, and conversation pull is not yet wired into the production sync scheduler lifecycle.
## Partial Pull Verification

When validating pull reliability, check these failure modes:

- If `daily_records` fails, the daily cursor must not advance.
- If `meals` fails, the meals cursor must not advance and the local daily aggregate must not be rewritten with missing meals.
- If `food_entries` fails, the food cursor must not advance and the local daily aggregate must not be rewritten with missing foods.
- If `weight_records` fails, the weight cursor must not advance and restore should be reported as partial.
- If a remote child row lacks its parent relation client id, the pull coordinator must increment `skippedMissingParentCount` and continue.
- Single bad records or one table failure must not crash the app, block UI, or affect local confirmed records.

## Manual Sync Order

Manual sync should run this background sequence:

1. Push pending local queue tasks.
2. Backfill local unsynced records into the queue.
3. Push pending queue tasks again.
4. Pull safe remote incremental updates.
5. Refresh sync health.

This order keeps local changes ahead of remote pull, reduces avoidable conflicts, and still keeps UI local-first.

## Current Non-Goals

The current stage does not implement:

- Multi-device conflict merging.
- Realtime.
- Social features.
- Full account binding UI.
- Chat transcript sync.
- Chat Push/Pull/Backfill. Phase 6A adds only `ai_conversations` and `ai_chat_messages` schema plus client DTO contracts.
- AI runtime changes.
- `assistant-turn-v2-stream` protocol changes.
- Restoring the old Intent chain.
- UI directly requesting Supabase for business display.
- Any Android use of `service_role`.
- Domestic server migration.
