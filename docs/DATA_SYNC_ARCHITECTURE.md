# DayZero Data Sync Architecture

DayZero uses Room as the local source of truth. User-facing screens observe Room only, and no screen should enter a Supabase-driven loading state for normal record display.

Supabase is the remote sync source. It is used for background synchronization of durable business data, not as an ordinary page data source.

## Current Stage

Supabase anonymous Auth session handling is now refresh-first. If a saved anonymous session's access token is expired or within the proactive refresh window, the app exchanges the saved refresh token through `/auth/v1/token?grant_type=refresh_token` and atomically persists the returned access token, refresh token, user id, and expiry. Because Supabase refresh tokens rotate, the old refresh token is never kept after a successful refresh.

The app only calls anonymous signup when there is no saved session, no refresh token, and no local blocked identity marker. Temporary refresh failures such as network errors, timeouts, rate limits, and HTTP 5xx pause Push/Pull and leave the old session untouched for retry. Permanent refresh rejection, such as an invalid or revoked refresh token, marks the cloud identity as unavailable and stops automatic Push/Pull rather than creating a new anonymous `user_id`.

This does not solve uninstall / clear-app-data recovery for anonymous users. If Room, local owner id, and the saved Supabase session are all deleted, the app cannot prove ownership of the old anonymous `user_id`; a formal login or recovery mechanism remains future work.

This stage establishes the local-first sync foundation for:

- Daily records
- Meals
- Food entries
- Weight records

Chat transcripts are not synced in this stage. Local AI conversations now exist only as a Room data foundation: `conversations` owns `ai_chat_messages` through a non-null `conversationId`, with UUIDs and millisecond timestamps chosen so future Supabase tables can map them directly. No chat/conversation Supabase tables, Edge Functions, Push/Pull/Backfill code, or sync queue operations have been added yet.

AI conversation phase 2 adds local domain logic only: first-message conversation creation is atomic, messages and AI replies are isolated by `conversationId`, and client AI context reads only the target conversation's recent messages. This does not change remote sync scope; chat transcripts and conversations still have no Supabase sync path.

AI conversation phase 3 wires the local conversation model into visible UI only: AI home, history list, and conversation detail all observe Room-backed local state by `conversationId`. This still does not add Supabase tables, Edge Function changes, Push/Pull/Backfill handling, or sync queue entries for chat transcripts.

AI conversation phase 4 adds a local date-mismatch guard for `show_confirm_card(food_record)` and binds final food/meal/weight writes to the owning `conversation.conversationDate`. This changes only the local confirmation target date and existing Room record write input. The resulting `DailyRecord`, meals, food entries, and weight records continue through the existing sync queue exactly as before; conversations, chat messages, and local guard cards still have no Supabase sync path.

The AI runtime is not changed in this stage. `assistant-turn-v2-stream` remains the primary AI entry, `assistant-turn-v2` remains fallback, and Kimi prompts/protocols are unchanged. AI returns replies and actions only; the client performs deterministic database writes after user confirmation.

This stage does not add a user-visible login system. It does not add phone, email, or WeChat login pages, and it does not require Supabase Auth before local records work.

## Identity Layer

The app reserves an identity abstraction for future remote accounts:

- `CurrentIdentityProvider` is the interface repositories depend on.
- `LocalIdentityProvider` is the current implementation.
- On first app launch, `LocalIdentityProvider` creates a stable `localOwnerId` and stores it in local persistent storage.
- App restarts reuse the same `localOwnerId`.
- Missing remote login must never block local record creation.

`AppIdentity` contains:

- `localOwnerId: String`
- `remoteUserId: String?`
- `authProvider: String`
- `canRemoteSync: Boolean`

Current stage values:

- `remoteUserId = null`
- `authProvider = "local"`
- `canRemoteSync = false`

When `canRemoteSync` is false, remote upload is skipped silently. The app logs `DayZeroSync: remote sync skipped: waiting for auth` and does not show UI prompts, Toasts, loading states, or change confirmed business state.

## Local-First Contract

Business state and sync state are separate:

- Business success means the Room write completed.
- Sync success means a background worker eventually uploaded or reconciled the data.
- Sync failure must not change confirmed/cancelled card state.
- Sync failure must not block UI, show Toasts, or make the user repeat a successful local action.

## Data Flow

```text
show_confirm_card confirm
  -> Room transaction / repository write
  -> local business record visible from Room
  -> CurrentIdentityProvider supplies AppIdentity
  -> sync_queue enqueue with ownerLocalId
  -> SyncCoordinator.runOnce trigger in background
  -> RemoteSyncGateway abstraction
  -> Supabase upsert / reconcile
```

The UI continues to observe Room. Supabase writes happen later through queued operations, prepared for idempotent upsert by stable local/client ids.

Future Supabase Auth or other auth providers should be added by replacing or composing an `IdentityProvider`. Supabase Auth SDK calls must not be scattered through ViewModels, UI, or record repositories.

## Remote Sync Gateway

Remote sync is isolated behind `RemoteSyncGateway`:

- `canSync(identity)`
- `upsertDailyRecord(payload)`
- `upsertMeal(payload)`
- `upsertFoodEntry(payload)`
- `upsertWeightRecord(payload)`
- `softDeleteRecord(payload)`

The current implementation is `NoopRemoteSyncGateway`. It does not upload to Supabase or any other server. When `AppIdentity.canRemoteSync` is false it returns `Skipped("waiting_for_auth")`.

DB-SYNC-6 adds `SupabaseRemoteSyncGateway`, still behind the same interface. It uses the configured Supabase URL and anon key, writes through REST/PostgREST endpoints, and never exposes Supabase calls to Compose UI, ViewModels, or ordinary record repositories. The gateway maps structured `SyncPayload` fields into `daily_records`, `meals`, `food_entries`, and `weight_records`.

`SupabaseAnonymousIdentityProvider` is a background-only remote identity adapter. It attempts to reuse a stored anonymous Supabase session, then attempts anonymous sign-in if needed. Failure leaves `canRemoteSync = false`; local Room writes continue normally.

When Supabase config is missing or still set to TODO placeholders, the app uses `NoopRemoteSyncGateway`. When config is available, the sync coordinator receives `SupabaseRemoteSyncGateway`.

## Sync Coordinator

`LocalFirstSyncCoordinator.runOnce()` is the worker-ready sync skeleton:

- reads due items from `SyncQueueDao`
- parses each item with `SyncPayloadParser`
- dispatches by operation to `RemoteSyncGateway`
- marks `DONE`, `FAILED_RETRYABLE`, `FAILED_FATAL`, or `WAITING_FOR_AUTH`
- logs with the `DayZeroSync` prefix
- continues the batch if a single item fails

`WAITING_FOR_AUTH` keeps items local and eligible for a later retry after `nextAttemptAt`, avoiding rapid loops while no remote identity exists.

## Remote Identity Boundary

Remote database tables reserve:

- `user_id` for the remote authenticated user id.
- `client_id` for the stable local record id used by idempotent upsert.
- `local_owner_id` only as a client migration/binding helper.

`localOwnerId` is not a remote security authority and must not be used as the basis for RLS authorization. RLS policies must continue to use remote auth identity such as `auth.uid()`.

## Delete Model

Business entities reserve `deletedAt` for soft delete synchronization. The remote sync model should not depend on physical deletes as the only representation of removal.

## Logging

New sync foundation logs use the `DayZeroSync` prefix, including enqueue start/success/error, Room migration start/success/error, and pending queue counts.
