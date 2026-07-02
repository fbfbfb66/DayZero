# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

DayZero is a native Android app (Kotlin + Jetpack Compose) for diet/weight tracking with an AI chat assistant. It uses a multi-module Gradle architecture, Room for local persistence, and Supabase (Postgres + Edge Functions + Auth) for the AI backend and background data sync. The AI model is Kimi, called from Supabase Edge Functions (Deno/TypeScript) in `supabase/functions/`.

## Build, lint, test

Gradle wrapper is not present in this listing; use `gradlew`/`gradlew.bat` if available, otherwise `gradle`.

- Build debug APK: `./gradlew :app:assembleDebug`
- Run all JVM unit tests (all modules): `./gradlew test`
- Run unit tests for one module: `./gradlew :core:data:testDebugUnitTest` (or `:core:database:testDebugUnitTest`, `:core:sync:testDebugUnitTest`, `:app:testDebugUnitTest`, `:feature:ai-record:testDebugUnitTest`, etc.)
- Run a single test class: `./gradlew :app:testDebugUnitTest --tests "com.example.DayZeroConversationPhase2Test"`
- Force re-run tests (ignore cache): add `--rerun-tasks`
- Screenshot/Roborazzi tests exist under `app/src/test/screenshots` (module uses the `roborazzi` Gradle plugin)

Before considering a change to sync, database migrations, or AI chat flow complete, the project convention (see `docs/DATA_SYNC_ARCHITECTURE.md`) is to run the full regression set: `:core:database:testDebugUnitTest`, `:core:data:testDebugUnitTest`, `:core:sync:testDebugUnitTest`, `:app:testDebugUnitTest`, and `:app:assembleDebug`.

### Local setup

- Create a `.env` file (see `.env.example`) with `GEMINI_API_KEY`, `SUPABASE_URL`, `SUPABASE_ANON_KEY`. The Secrets Gradle plugin (`app/build.gradle.kts`) injects these into `BuildConfig`/manifest placeholders — never hardcode keys in source.
- Only the Supabase anon/publishable key belongs on-device. The Kimi API key and any Supabase `service_role` key live only in Supabase Edge Function secrets, never in the Android app.
- Release signing reads `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` from env vars.

### Supabase edge functions

Edge Functions live in `supabase/functions/*` (Deno/TS) with colocated `*_test.ts` files. Migrations are plain SQL in `supabase/migrations/`, each paired with a verification script in `supabase/verification/`.

## Module architecture

Standard Android multi-module layering, top-down dependency direction (`app`/`feature:*` → `core:data` → `core:domain`/`core:network`/`core:database` → `core:model`):

- `core:model` — plain domain models (`domain.model.*`), no Android/framework deps.
- `core:domain` — repository interfaces (`domain.repository.*`) and use cases (`domain.usecase.*`); the contract layer other modules implement/consume.
- `core:database` — Room: `DayZeroDatabase`, DAOs (`data.local.dao.*`), entities, and migrations. Migration correctness is checked with dedicated tests, e.g. `Migration10to11Test`.
- `core:network` — Retrofit/OkHttp: DTOs (`data.remote.dto.*`) and remote mappers (`data.remote.mapper.*`) for calling Supabase Edge Functions.
- `core:sync` — background sync engine: push/pull/backfill coordinators, sync queue processing.
- `core:data` — repository implementations (`Room*Repository`, `Remote*Repository`, `Fake*Repository` for tests) wiring domain contracts to database/network.
- `core:ui` — shared Compose UI components/theme.
- `feature:ai-record`, `feature:calendar`, `feature:trends` — feature-scoped Compose screens.
- `app` — application module: `DayZeroViewModel`, `di/DayZeroHiltModule.kt` (Hilt DI graph), `ui/AppNavigation.kt` (Compose Navigation graph), `assistant/` (AI orchestration glue).

Hilt is the DI framework; almost all bindings for repositories, coordinators, and use cases are centralized in `app/src/main/java/com/example/di/DayZeroHiltModule.kt` — check there first when tracing how an interface is wired to its implementation.

## AI assistant architecture (critical — read before touching chat/AI code)

Full details: `docs/AI_ASSISTANT_TURN_V2_ARCHITECTURE.md`. Key rules:

- **Single entrypoint**: all user-initiated AI input goes through the `assistant-turn-v2` protocol family. `assistant-turn-v2-stream` is the primary runtime endpoint (streaming); `assistant-turn-v2` is the non-streaming fallback. The legacy chain (`HybridIntentRouter`, `classify-user-intent`, `generate-checkin-draft`, `AiCompanionReplyRepository`) is retired and **must never be reintroduced or used as a fallback**.
- **Protocol shape**: every response is `reply` (AI-generated natural language) plus an optional `actions[]` array. If no tool is needed, `actions` is empty — the client never fabricates fixed AI copy for user-initiated turns. Fixed client-side text is only allowed for deterministic post-confirmation feedback (e.g. "Record saved").
- **Tools never write directly**: tools (`show_confirm_card`, `ask_record_intent_card`, `ask_missing_info_card`, etc.) only drive UI/confirmation surfaces. Real writes (food, weight, meal records) happen **only after explicit user confirmation**, executed client-side into Room.
- **Food logging sensitivity**: mentioning food does not imply a save intent. Use `ask_record_intent_card` to confirm intent before showing a `show_confirm_card` draft.
- **Server-side action normalization**: the Edge Function (`supabase/functions/assistant-turn-v2-stream/`) expands compact Kimi output (`{r, a: [...]}`) into the full public `reply + actions[]` protocol server-side, so Kimi only emits business data (food items, amounts, calories) — never full card template JSON (title/message/button copy/interactionId are deterministic template fields filled by the server/client).
- **Date-mismatch guard**: if a card's owning `conversation.conversationDate` differs from device-local "today," the client wraps it in a local-only `date_mismatch_guard_card` with `pendingOriginalCard`. This guard is never sent to Kimi; it is a pure Room state transition. Final writes always target the conversation's fixed date, not `LocalDate.now()`.

## Local-first data & sync architecture (critical — read before touching repositories/sync)

Full details: `docs/DATA_SYNC_ARCHITECTURE.md` and `docs/CHAT_SYNC_ARCHITECTURE.md`. Key rules:

- **Room is the only UI-facing source of truth.** Screens observe Room via Flow; no screen should ever enter a Supabase-driven loading state. Supabase is purely a background sync target, isolated behind the `RemoteSyncGateway` interface — never call Supabase directly from ViewModels, Compose UI, or ordinary record repositories.
- **Business success != sync success.** A Room write completing is the success signal for the user. Sync failures must never change confirmed/cancelled UI state, block the UI, show a Toast, or force the user to repeat an action.
- **Identity abstraction**: repositories depend on `CurrentIdentityProvider` / `AppIdentity` (`localOwnerId`, `remoteUserId`, `canRemoteSync`), not on Supabase Auth types directly. When `canRemoteSync` is false, remote upload is silently skipped (logged, no UI). Missing remote login must never block local record creation. `localOwnerId` is a client-side convenience id only — it is **not** a security authority; RLS policies must use `auth.uid()`.
- **Sync queue**: writes enqueue into the single `sync_queue` table (not a second framework) within the same Room transaction as the business write. Operations include `UPSERT_AI_CONVERSATION`/`UPSERT_AI_CHAT_MESSAGE` (chat) alongside the daily-record/meal/food/weight operations. Conversations are always pushed before their messages.
- **Soft delete only**: business entities and chat rows use `deletedAt`/`deleted_at` tombstones. No hard-delete Android client permission exists on chat tables.
- **Chat sync cursor**: pull uses composite `(server_updated_at, id)` pagination per owner, distinct from business timestamps (`created_at`/`updated_at`). `assistant_cards` is raw `jsonb` and must round-trip unknown/future fields untouched.
- **Logging convention**: sync code logs with the `DayZeroSync` prefix.
- Do not use `SET session_replication_role = 'replica'` or similar trigger-bypassing SQL in ordinary migrations — that pattern is reserved for a documented one-time manual data-recovery operation (see the bottom of `docs/DATA_SYNC_ARCHITECTURE.md`) and must not become a general migration pattern.

## Other docs worth checking before large changes

- `docs/AI_CHAT_BEHAVIOR_DESIGN.md` — chat UX/behavior design.
- `docs/AI_RECORD_FLOW_AUDIT.md` — audit notes on the AI record flow.
- `docs/SUPABASE_SCHEMA_VERIFICATION.md` — how remote schema changes are verified.
- `docs/DEVELOPMENT_LOG.md` — chronological log of completed phases (Chinese); useful for understanding why a piece of code exists before changing it.
