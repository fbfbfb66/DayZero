# DayZero Data Sync Architecture

DayZero uses Room as the local source of truth. User-facing screens observe Room only, and no screen should enter a Supabase-driven loading state for normal record display.

Supabase is the remote sync source. It is used for background synchronization of durable business data, not as an ordinary page data source.

## Current Stage

This stage establishes the local-first sync foundation for:

- Daily records
- Meals
- Food entries
- Weight records

Chat transcripts are not synced in this stage.

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
