# DayZero Project Context

## Current status

- **Current Codebase Checkpoint (2026-07-27, commit `bb328d6`) — Package migration, media sync, and AI Gateway committed to `main`**. The working-tree changes for the unified `com.goings.dayzero` application identity, cross-device media sync over Supabase Storage (Room 13 → `media_assets` remote state + `MediaPullCoordinator`/`SupabaseMediaRemotePullGateway`), and the `server/dayzero-ai-gateway` G2/G2-F1 production hardening have been committed, closing the implementation-vs-delivery gap. `:app:assembleDebug` succeeds; `:app:testDebugUnitTest` and root `test` retain only the 2 documented timezone-baseline failures, with no new failures.
- **Major Architecture Refactor Complete (Multi-module + Hilt)**. The project has been split from a single large `:app` module into a maintainable layered module graph: `:app`, `:core:model`, `:core:domain`, `:core:database`, `:core:network`, `:core:data`, `:core:sync`, `:core:ui`, `:feature:ai-record`, `:feature:calendar`, and `:feature:trends`.
- **Hilt Dependency Injection Enabled**. `DayZeroApplication` is annotated with `@HiltAndroidApp`, `MainActivity` is an `@AndroidEntryPoint`, and `DayZeroViewModel` is now an `@HiltViewModel`. Manual dependency construction in the old `DayZeroViewModel.Factory` has been removed and replaced with constructor injection plus `DayZeroHiltModule`.
- **Android Application Identity Migrated to `com.goings.dayzero` (2026-07-12)**. The project package name and all module namespaces were unified from the legacy `com.example` / `com.aistudio.dayzero.djwqop` identities to `com.goings.dayzero`. Source directories, `package`/`import` declarations, Gradle `namespace`/`applicationId`, and the debug-install PowerShell script were updated. No business logic, database schema, Supabase config, or AI Gateway behavior was changed. A fresh `:app:assembleDebug` build succeeded; `:app:testDebugUnitTest` and core/feature tests passed except for 3 pre-existing UI rendering failures in `PinnedPhotoStripUiTest` tied to prior working-tree changes in `AssistantCardRenderer.kt`.
- **Release APK Generated for Alibaba Cloud App Filing (2026-07-12)**. A formal release APK was built by Android Studio at `app/release/app-release.apk` (≈16 MB) using a release signing certificate (`C=CN, ST=Yunnan, L=Kunming, O=DayZero, OU=DayZero, CN=Goings`). The APK is signed with APK Signature Scheme v2, package name `com.goings.dayzero`, certificate MD5 `d7:4c:5d:f4:0e:d7:92:07:81:fa:dd:67:fc:a4:8a:24`, and RSA public key (Base64 SubjectPublicKeyInfo) `MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAt2h423skD7pggEsRwekf+8i9YnM/9zdbFU6eYrLtmZ0gqoeQAPmKiMDvadj1bYifow6jApderEHoU8a7rFJBKfWRYK1hOSkvudLn/h1Q3QVHtacMWa1NxCy0BbYfGFzdYj4JijR5GNsX/5l9PIpMf8or0krsR2E2ZKEoP3daU4F1uX803EWksILjrSsji+GKwyHcwc3VWMDzZ5LRnesf68TMsaVMvHVBzJJ85YCXh14P/tZE7Km6s4BMXvPmwGBpC4eJhjUTAwAJ/uaaF8tsH18mBKIw3lpc4VG1YtiouUbDWWIwVX+gL0wOAuqiCgi2+l1ymGlf20Ivrb0iWXycmQIDAQAB`. No keystore password or private key was logged.
- **Photo Assignment Editor — implemented and committed (Phase 4B-1-R reality audit closed, 2026-07-10; code committed 2026-07-11)**. User reported "编辑模式还没有" (no photo-edit mode on device). Read-only audit found the *entire* editor is present and correctly wired in source — entry `FoodDraftConfirmCard.kt:292` ("整理照片 · N 张") gated by `AssistantCardRenderer.kt:155-171`; origin resolution via deterministic `assistantPlaceholderId` pairing in `PhotoEditorCardResolver`; full-screen overlay host `AiRecordScreen.kt:602-620` reusing `PhotoViewerOverlay`; ViewModel session + save in `AiRecordViewModel`; atomic persistence in `RoomFoodCardPhotoAssignmentRepository` (preserves unknown JSON/nutrition/weight, supports `date_mismatch_guard_card.pendingOriginalCard`, exactly-once sync enqueue); DI at `DayZeroHiltModule.kt:326/337`. **The first broken link was delivery, not code**: the whole feature had been uncommitted working-tree changes (`photoeditor/` dir + UseCase + Repo untracked; wiring files modified-uncommitted; empty git history) that were never compiled into the installed APK. The fix was a fresh `app-debug.apk` build with no source re-implementation. On 2026-07-11 all of the photo-editor source, wiring, persistence, Edge Function helpers, and tests were committed in `2bd958e Add and update feature implementations`, resolving the working-tree/delivery gap. Verified: all photo-editor module tests + `:app:assembleDebug` PASS; `:app:testDebugUnitTest` = only the 2 whitelisted timezone-baseline failures, no new failures. Status: `READY_FOR_PHOTO_EDITOR_DEVICE_RETEST` (user must install a freshly built APK and retest on device before declaring `PHASE_4B_COMPLETE`; `DEVICE_TEST_PASSED` is NOT declared). No Edge/Prompt/Schema/Room-version/MealEntry/Calendar/cloud-sync change beyond what is already committed.
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
- **Current Build Baseline (2026-07-27)**: `:app:assembleDebug` and the targeted core/feature unit-test tasks pass. `:app:testDebugUnitTest` and root `test` exit non-zero only because of the two documented timezone-fragile tests; this is not recorded as an all-green root suite. The baseline was re-verified after the checkpoint commit `bb328d6`.
- **Cross-Device Media Sync Implemented**. Room schema migrated to version 13 with `remoteSyncState`, `remoteMasterPath`, and `remoteThumbnailPath` on `media_assets`. The sync layer pushes/pulls image bytes to/from a private Supabase Storage bucket (`media-assets`) using `SupabaseRemoteSyncGateway`, `MediaSyncQueueWriter/PayloadBuilder`, `MediaPullCoordinator`, `MediaRemoteMerger`, and `SupabaseMediaRemotePullGateway`. UI shows a download progress state (`RemotePending`) for images not yet pulled to the local device.
- **Nutrition Capsule Phase C3 Complete**. The `FoodDraftConfirmCard` now renders an animated nutrition capsule with progress rings and entry animations, shown only when at least one food item has all four finite, non-negative nutrient fields and the displayed totals are greater than zero.
- **Audit P1/P2 Safe Local Fixes Applied**. Tightened `backup_rules.xml`/`data_extraction_rules.xml`, fixed sync-queue edge cases, and hardened `RoomChatMediaTransactionRepository`/`RoomFoodCardConfirmationRepository` persistence boundaries. These fixes were committed in `8f22bbd` and included in the `bb328d6` checkpoint.
- **Calendar / Navigation / Chat UI Polish**. Calendar transitions no longer flicker, dynamic placeholders are shown, and the AI-record button appears only for the current day. Bottom navigation and input-bar transitions are smoothed, with premium parallax horizontal page transitions. Conversation detail defaults to bottom-scroll on entry and intelligently pauses auto-scroll when the user manually scrolls during AI reply generation.
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
- **Fast Fallback (15s Timeout)** *(Superseded 2026-07-10 by the dynamic upstream timeout — see the v17/v28 bullet below; kept as history)*: Reduced the Deno streaming fetch abort timeout in `assistant-turn-v2-stream` from **35 seconds** to **15 seconds**. If Kimi API hangs or suffers from high TTFT, Deno will abort after 15s, triggering immediate client fallback to the non-streaming `assistant-turn-v2` endpoint, saving 20 seconds of empty waiting time.
- **Kimi Latency Analysis**: Identified that high latency is 100% caused by Kimi (Moonshot API `kimi-k2.6`) response time and network routing between Supabase (outside China) and Moonshot (inside China). Deno edge function execution overhead is negligible (< 2ms).
- `assistant-turn-v2-stream` (**Version 17**, `stream_compact_v7_deterministic_multi_meal_photo_assignment`) is the current primary AI runtime entrypoint. `assistant-turn-v2` (**Version 28**, `compact_v8_deterministic_multi_meal_photo_assignment`) remains as the compatibility fallback. Both are `ACTIVE` with `verify_jwt=false`. (The Version 15/24 and `_v4/_v5` values elsewhere in this doc are historical.)
- **Dynamic upstream timeout replaces the old fixed 15s (2026-07-10, deployed v17/v28)**. The streaming Kimi-fetch abort is no longer a flat 15s. It is now `selectStreamHeaderTimeoutMs` = clamp(25s, 15s + 10s/MiB of decoded attachment bytes, 50s) in `supabase/functions/_shared/assistant_upstream_timeout.ts`; text-only requests keep 15s. On abort the stream emits a `{code:"UPSTREAM_HEADER_TIMEOUT", retryable:true}` SSE error; the non-streaming fallback got a matching 50s total-timeout that returns HTTP 504 `UPSTREAM_TOTAL_TIMEOUT`. This fixed the "picture reply is not streamed, it just pops out" symptom, whose root cause was: the old 15s only wrapped connect+upload+Kimi vision prefill up to response headers (not the body), so real multi-image requests were aborted at 15s, converted to a client `ProtocolException` ("协议错误"), and silently routed to the one-shot fallback (no typewriter effect). See DEVELOPMENT_LOG "Vision Runtime Forensic Audit + Dynamic Upstream Timeout Deployment (2026-07-10)".
- **Vision-latency correction (2026-07-10)**: the dominant multi-image cost is Moonshot's *vision prefill* (pixel-bound), not upload bytes. In streaming mode Moonshot withholds response headers until prefill completes (verified: `upstreamHeadersMs ≈ kimiTimeToFirstTokenMs`). Reducing JPEG *quality* does not help prefill; only reducing *pixels* does. The 2026-07-10 derivative reduction (1280px→1024px, ~36% fewer pixels) helps in normal periods but cannot overcome Moonshot's peak-congestion vision windows (evening ~19:00–23:00 CST), where even a single 500KB/1280px image can exceed 25s to headers. This is an upstream-capacity/temporal condition, not a client-fixable one.
- **Photo Feature Phase 2B-3 is complete**: `PHASE_2B_3_COMPLETE`. The product/device acceptance baseline is `READY_FOR_PHASE_4A_1`; `P2_6_VERIFIED`, `BASELINE_READY_FOR_PHASE_4A_1`, `READY_FOR_PHASE_4A_1`.
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
- **Phase 2B-3C1-F1: Android Vision Orchestration Targeted Fixes complete**. Addressed independent-verification findings in `:app` Vision orchestration: `VisionAssistantTurnOrchestrator` is now a required non-null dependency of `DayZeroViewModel`; streaming fallback eligibility covers `IOException`, `ProtocolException`, `JsonDataException`, and transient `HttpException` (408/429/5xx); `DayZeroViewModel` uses per-attempt ownership (`activeVisionAttemptId`) so only the owning attempt can set or clear `isAnalyzing`; cleanup failures are logged and cannot mask the original exception or `CancellationException`. Tests expanded with a fallback exception matrix and `DayZeroViewModelVisionAttemptOwnershipTest`. Edge Function source was left untouched (baseline SHA-256 verified). UI image send remains intercepted until end-to-end verification.
- **Phase 2B-3C2A: Real Image Send + Vision Turn Wiring complete**. Removed the temporary image-send UI interception in `AiRecordScreen`. The detail screen now routes messages with attachments through `SendUserMessageWithMediaUseCase`; on local commit success it emits `MediaMessageCommitted`, which `AppNavigation` consumes to start `VisionAssistantTurnOrchestrator` for the persisted user message. Attachment drafts are cleaned only after a successful local commit, and a minimal `VisionRetryCard` is shown when the vision turn reaches a terminal error. Added `AiRecordMediaSendTest` covering text/image split, commit success/failure, double-click protection, conflict handling, and draft isolation. Verified by `:app:assembleDebug`, safe install on Pixel_10_Pro AVD, and app launch without crash.
- **Historical Phase 2B-3C2B-F1 report — superseded by the F2 real-device correction below; it is not a current completion claim**. Diagnosed the Vision picture stream "no streaming" symptom as the Edge Function's hard 15 s Kimi fetch timeout; added safe, data-free diagnostic logging to `VisionAssistantTurnOrchestrator` so TTFT, delta count, fallback reason, and duration can be verified on-device without logging Base64, paths, or payloads. Gated new AI message sends (text and media) on `NetworkAvailabilityProvider` so users cannot submit messages while offline. Fixed weight float precision by centralizing `formatWeightKg`/`normalizeWeightKg` in `:core:model` and applying them in `FoodDraftConfirmCard` input, display, and the `AssistantTurnV2ResponseMapper` boundary. Replaced the generic typing indicator for vision assistant placeholders with a dedicated `VisionImageRecognizingIndicator` (shimmer beam, reduced-motion static fallback, accessibility). Updated `AiRecordPhase2ATest`, `AiRecordMediaSendTest`, `DayZeroConversationPhase2Test`, and added `WeightFormatterTest`, `VisionPlaceholderDetectorTest`, `DayZeroViewModelNetworkGateTest`, and `VisionAssistantTurnOrchestratorTest`. Historical status was `VISION_STREAM_TIMEOUT_CONFIRMED_REQUIRES_EDGE_DECISION`; F2 disproved that timeout claim.

- **AI Gateway Phase G2 / G2-F1 — Local production hardening complete and security-accepted for controlled deployment (2026-07-12)**. Strengthened JWT/JWKS verification in `server/dayzero-ai-gateway`: ES256-only, required `kid`/`exp`/`sub`/`iss`/`aud`, single JWKS refresh on unknown `kid`, production fail-fast when `ENABLE_AUTH=false`, fixed `AuthErrorCode` enums, and irreversible SHA-256 user-id digest for logs. Logger now uses an explicit allow-list and drops objects/arrays/exceptions so that `sub`, `userText`, `recentMessages`, `todayRecord`, `interactionResult`, `prompt`, Base64, data URLs, `Authorization`, `detail`, `message`, `cause`, `stack`, and `imagePath` cannot leak. Added `APP_ENV`, `SUPABASE_JWKS_URL`, `SUPABASE_ISSUER`, `SUPABASE_AUDIENCE` configuration with legacy `SUPABASE_URL`/`SUPABASE_JWT_AUDIENCE` compatibility. Created production templates: `Dockerfile` with OCI revision/source-hash labels, `docker-compose.production.yml`, `nginx.production.conf.template` (HTTPS 443, HTTP→HTTPS redirect, `/ready`, `/api/ai/assistant-turn-v2[-stream]`, legacy paths, full SSE buffering controls, `X-Accel-Buffering no`, `proxy_next_upstream off`), `deployment.manifest.template.yml`, and `docs/DEPLOYMENT_AND_ROLLBACK_20260712.md`. The initial independent security audit (`docs/PHASE_G2_SECURITY_ACCEPTANCE_20260712.md`) was `NOT_ACCEPTABLE_FOR_DEPLOYMENT` due to incomplete log redaction, raw error/Kimi-body leakage, readiness/healthcheck misconfiguration, JWKS timeout/concurrency gaps, and missing ACME/rollback runbook. A G2-F1 fix round (`docs/PHASE_G2_F1_COMPLETION_REPORT_20260712.md`) addressed every Critical/High finding: whitelist logging, safe upstream error enums, readiness using canonical config, `/ready`-based healthcheck, JWKS 4s timeout + single-flight + 5s cooldown, `.dockerignore`, digest-only Compose image references, and executable v1 rollback. Independent reverification (`docs/PHASE_G2_F1_SECURITY_REVERIFICATION_20260712.md`) concluded `ACCEPTABLE_FOR_CONTROLLED_DEPLOYMENT`. G2-F1 is **not deployed** to ECS; real domain, DNS, and production HTTPS certificates are still pending.

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
- Primary Edge Function: `assistant-turn-v2-stream` (**Version 17**, dynamic header timeout clamp(25s, 15s+10s/MiB, 50s); text-only stays 15s)
- Fallback Edge Function: `assistant-turn-v2` (**Version 28**, 50s total upstream timeout → HTTP 504 `UPSTREAM_TOTAL_TIMEOUT`)
- Retired Edge Function: `ai-assistant-turn` should stay deleted/unused
- Remote status: `ACTIVE`
- Remote current prompt versions: `assistant-turn-v2-stream` uses `stream_compact_v7_deterministic_multi_meal_photo_assignment`; `assistant-turn-v2` uses `compact_v8_deterministic_multi_meal_photo_assignment`. (v17/v28 only changed timeout logic; prompt version strings are unchanged from v16/v27.)
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
- **[Conversation](file:///D:/Goings/APPProjects/DayZero/core/model/src/main/java/com/goings/dayzero/domain/model/ai/Conversation.kt)** (in `:core:model`):
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
- **[ConversationEntity](file:///D:/Goings/APPProjects/DayZero/core/database/src/main/java/com/goings/dayzero/data/local/entity/ConversationEntity.kt)** (in `:core:database`):
  Room entity mapped to the `conversations` table. Has indices on `conversationDate` and `lastActivityAt`.
- **[AiChatMessageEntity](file:///D:/Goings/APPProjects/DayZero/core/database/src/main/java/com/goings/dayzero/data/local/entity/AiChatMessageEntity.kt)** (in `:core:database`):
  Modified to add `conversationId: String` which has a foreign key constraint referencing `conversations(id)` with `ON DELETE CASCADE`. Indexes are added for `conversationId` and `(conversationId, createdAt)`.

### 2. Room Migration (9 -> 10)
Implemented in **[DayZeroDatabase](file:///D:/Goings/APPProjects/DayZero/core/database/src/main/java/com/goings/dayzero/data/local/database/DayZeroDatabase.kt)**, the migration safely ports existing single-stream chat records into grouped conversations:
- **Group by Date**: Queries all existing `ai_chat_messages` and groups them by natural date using the device's system default timezone (`ZoneId.systemDefault()`).
- **Stable UUID Generation**: For each date group, a stable conversation UUID is generated deterministically via:
  `UUID.nameUUIDFromBytes("dayzero-legacy-ai-chat-${'$'}date".toByteArray(StandardCharsets.UTF_8)).toString()`
  This prevents duplicate conversations if migration/re-entry happens.
- **Title and Preview Extrapolations**:
  - The conversation **title** is extracted from the first user message in that date's group (truncated to a maximum of 32 characters), falling back to a neutral title (e.g., `6月8日的对话`) if no user text is found.
  - The conversation **preview text** is set to the last non-empty message text or `"这条对话包含一张记录卡片"` if it only contains interactive/checkin cards.
- **Orphan Prevention**: After inserting conversations and creating the new `ai_chat_messages` table with the foreign key constraint, the migration checks that no messages are left with a null/orphaned `conversationId`.

### 3. DAO & Repository Layer API
- **[ConversationDao](file:///D:/Goings/APPProjects/DayZero/core/database/src/main/java/com/goings/dayzero/data/local/dao/ConversationDao.kt)**:
  Exposes queries to insert, fetch by ID, observe all active conversations sorted by `lastActivityAt DESC, createdAt DESC`, update summary titles/previews, and soft delete.
- **[ConversationRepository](file:///D:/Goings/APPProjects/DayZero/core/domain/src/main/java/com/goings/dayzero/domain/repository/ConversationRepository.kt)** & **[RoomConversationRepository](file:///D:/Goings/APPProjects/DayZero/core/data/src/main/java/com/goings/dayzero/data/repository/RoomConversationRepository.kt)**:
  Domain repository interface and Room-backed implementation for managing conversations.
- **[AiDraftRepository](file:///D:/Goings/APPProjects/DayZero/core/domain/src/main/java/com/goings/dayzero/domain/repository/AiDraftRepository.kt)**:
  Expanded to support conversation-scoped operations:
  - `fun observeChatMessages(conversationId: String): Flow<List<AiChatMessage>>`: Observes messages belonging to a specific conversation.
  - `suspend fun createConversationWithFirstMessage(text: String, now: Long): String?`: Atomically inserts a new conversation and its first user message in a single database transaction.
  - `suspend fun getRecentChatMessages(conversationId: String, limit: Int): List<AiChatMessage>`: Fetches the recent messages for conversation context extraction.
  - `suspend fun findMessageByAssistantCardId(cardId: String): AiChatMessage?`: Looks up the chat message containing the card ID to route interactions correctly.
  - `suspend fun insertChatMessage(conversationId: String, message: AiChatMessage)`: Inserts a message in the designated conversation and updates its preview summary.
- **[RemoteAiDraftRepository](file:///D:/Goings/APPProjects/DayZero/core/data/src/main/java/com/goings/dayzero/data/repository/RemoteAiDraftRepository.kt)**:
  Now accepts the full `database` instance to support safe, multi-table transactions (`database.withTransaction {}`). Both `createConversationWithFirstMessage` and `clearChatMessages` are executed transactionally.

### 4. Use Cases & ViewModels
- **[CreateConversationWithFirstMessageUseCase](file:///D:/Goings/APPProjects/DayZero/core/domain/src/main/java/com/goings/dayzero/domain/usecase/CreateConversationWithFirstMessageUseCase.kt)** (in `:core:domain`):
  Validates user input text and delegates new conversation creation to the repository layer.
- **[AiRecordViewModel](file:///D:/Goings/APPProjects/DayZero/feature/ai-record/src/main/java/com/goings/dayzero/ui/screens/AiRecordViewModel.kt)** (in `:feature:ai-record`):
  Exposes reactive state objects:
  - `AiConversationHistoryState`: Holds active conversations list, sorted by `lastActivityAt DESC`.
  - `AiConversationDetailState`: Holds current conversation details and message list.
  - State restoration: Uses `SavedStateHandle` to preserve and restore `conversationId` across process death.
  - Creation events: Exposes `events: SharedFlow<AiRecordConversationEvent>` to signal successful conversation initiation to the UI layer.
- **[DayZeroViewModel](file:///D:/Goings/APPProjects/DayZero/app/src/main/java/com/goings/dayzero/DayZeroViewModel.kt)**:
  - Tracks `activeConversationId` in `AppState` and includes it in all outgoing client messages.
  - Pinning for Asynchronous Streams: During network call initiation, the target conversation ID is captured to ensure streaming/fallback updates write back to the original placeholder even if the user switches active conversations mid-stream.
  - Interaction Routing: Option clicks and confirm/cancel actions retrieve the original conversation ID via `findMessageByAssistantCardId(interactionId)` to guarantee that database records are updated in the correct conversation thread.

### 5. Testing & Verification
- **[DayZeroConversationMigrationTest](file:///D:/Goings/APPProjects/DayZero/app/src/test/java/com/goings/dayzero/DayZeroConversationMigrationTest.kt)** (Phase 1):
  Verifies Room database migration 9->10, Natural Day grouping, UUID stability, and legacy detail preservation.
- **[DayZeroConversationPhase2Test](file:///D:/Goings/APPProjects/DayZero/app/src/test/java/com/goings/dayzero/DayZeroConversationPhase2Test.kt)** (Phase 2):
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
This configuration caused `ComponentActivity` to register as a launcher activity inside the final debug APK. As a result, the Android OS created two launcher icons on the system home screen for the single application package (`com.goings.dayzero`).

### Fix
- The `<intent-filter>` block has been removed from `androidx.activity.ComponentActivity` in `feature/ai-record/src/debug/AndroidManifest.xml`.
- The activity itself remains registered to ensure Compose test rules and Robolectric/device tests function correctly without warning/failure.
- Running the ordinary run configuration now only creates a single launcher icon for the app.
- To clean up any stale launcher state, run:
  ```powershell
  adb uninstall com.goings.dayzero
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
- **Problem**: App crashed immediately on startup during Hilt initialization with `java.lang.ClassCastException: com.goings.dayzero.data.sync.SupabaseRemotePullGateway cannot be cast to com.goings.dayzero.data.sync.ChatRemotePullGateway`.
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
- **[SupabaseChatRemotePullGateway.kt](file:///D:/Goings/APPProjects/DayZero/core/sync/src/main/java/com/goings/dayzero/data/sync/SupabaseChatRemotePullGateway.kt)**: Made `parseRemoteTime` internal and updated it to parse using `OffsetDateTime` first, falling back to `Instant.parse`, and throwing the exception upon failure.
- **[SupabaseRemotePullGateway.kt](file:///D:/Goings/APPProjects/DayZero/core/sync/src/main/java/com/goings/dayzero/data/sync/SupabaseRemotePullGateway.kt)**: Implemented the same robust parser.
- **[SupabaseChatRemotePullGatewayTest.kt](file:///D:/Goings/APPProjects/DayZero/core/sync/src/test/java/com/goings/dayzero/data/sync/SupabaseChatRemotePullGatewayTest.kt)**: Added datetime parsing tests covering UTC `Z`, offsets (`+00:00`, `+08:00`, `-05:00`), varying sub-second precision (including microsecond formats), invalid formats, and blank inputs. Verified that failures do not fallback to system execution time.
- **[SyncHealthReporterChatPullTest.kt](file:///D:/Goings/APPProjects/DayZero/core/sync/src/test/java/com/goings/dayzero/data/sync/SyncHealthReporterChatPullTest.kt)**: Added a test verifying that subsequent successful pulls clear the fatal failure count and restore health status, and fixed the missing `assertNull` import compilation error.

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
- **[FoodEntry](file:///D:/Goings/APPProjects/DayZero/core/model/src/main/java/com/goings/dayzero/domain/model/FoodEntry.kt)** 包含 `carbohydratesG`, `proteinG`, `fatG`, `fiberG` (Float? = null)。
- **[ConfirmCardItem](file:///D:/Goings/APPProjects/DayZero/core/model/src/main/java/com/goings/dayzero/domain/model/ai/assistant/AiChatCard.kt)** 新增上述四个字段。
- **[ConfirmCardItemDto](file:///D:/Goings/APPProjects/DayZero/core/network/src/main/java/com/goings/dayzero/data/remote/dto/assistant/AiChatCardDto.kt)** 及 **[AssistantActionItemDto](file:///D:/Goings/APPProjects/DayZero/core/network/src/main/java/com/goings/dayzero/data/remote/dto/assistant/AssistantActionDto.kt)** 补充上述四个字段，使用 Moshi 进行序列化。
- **[AssistantTurnV2ResponseMapper](file:///D:/Goings/APPProjects/DayZero/core/network/src/main/java/com/goings/dayzero/data/remote/mapper/AssistantTurnV2ResponseMapper.kt)** 及 **[AiAssistantRemoteMapper](file:///D:/Goings/APPProjects/DayZero/core/network/src/main/java/com/goings/dayzero/data/remote/mapper/AiAssistantRemoteMapper.kt)** 完整实现双向往返映射。
  - 历史 Card JSON 缺少营养字段时，正常解析得到 `null`，不崩溃且不转为 0。
  - `date_mismatch_guard_card` 中嵌套的 `pendingOriginalCard` 能对称传输并不丢营养字段。

### 3. 本地 mealsJson 存储与兼容性
- **存储方案**: 本地 Room 保持不变，不需要 Room Migration 且未改变 Database version，没有独立的本地 meals/food_entries/weight_records Room 表。
- **Room counts 说明**: 文档历史中的 `daily_records=3, meals=6, food_entries=9, weight_records=3` 并非 Room 中独立表行数。`daily_records` 指 Room 中记录实体行数，其余是指业务对象在 `mealsJson` 中的计数、Backfill 扫描任务计数或远端 Supabase 数据库表行数。
- **mealsJson 兼容**: 借助 Moshi，新/旧 `mealsJson` 反序列化为 `FoodEntry` 时能对未知键得到 `null`，保存时正常序列化并保留 0 与 null 的独立语义。

### 4. 同步对称性 (Sync Payload, Push, Pull & Backfill)
- **[SyncPayloadBuilder](file:///D:/Goings/APPProjects/DayZero/core/sync/src/main/java/com/goings/dayzero/data/sync/SyncPayloadBuilder.kt)**: `foodPayload` 输出 `carbsG`, `proteinG`, `fatG`, `fiberG`，显式支持 `null -> JSONObject.NULL`，确保能够被客户端清空及修改。
- **[SupabaseRemoteSyncGateway](file:///D:/Goings/APPProjects/DayZero/core/sync/src/main/java/com/goings/dayzero/data/sync/SupabaseRemoteSyncGateway.kt)**: `foodEntryBody` 新增 `fiber_g` 列的写入，向 Supabase 发送 Push 请求时，值保留 null/0/正数。
- **[SupabaseRemotePullGateway](file:///D:/Goings/APPProjects/DayZero/core/sync/src/main/java/com/goings/dayzero/data/sync/SupabaseRemotePullGateway.kt)**: `FoodEntryRemoteDto` 增加 `fiberG` 字段；`foodEntryFromJson` 在从 JSON 解析时，读取 `fiber_g` 并支持缺失字段兼容 (返回 null)。
- **[PullCoordinator](file:///D:/Goings/APPProjects/DayZero/core/sync/src/main/java/com/goings/dayzero/data/sync/PullCoordinator.kt)**: `buildMeals` 在构造 `FoodEntry` 领域模型时，成功映射 remote 的四个营养字段，实现了 Pull 流程的数据流补齐。
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
- 新增 `NutritionCapsuleCalculator`，位置：`core/ui/src/main/java/com/goings/dayzero/ui/components/ai/NutritionCapsuleCalculator.kt`。
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
  - `core/ui/src/main/java/com/goings/dayzero/ui/components/ai/FoodDraftConfirmCard.kt`
  - `core/ui/src/main/java/com/goings/dayzero/ui/components/ai/NutritionCapsuleCalculator.kt`
  - `core/ui/build.gradle.kts`
  - `feature/ai-record/src/main/java/com/goings/dayzero/ui/screens/AiRecordScreen.kt`
  - `feature/ai-record/src/main/java/com/goings/dayzero/ui/screens/AssistantCardRenderer.kt`
  - `app/src/main/java/com/goings/dayzero/ui/AppNavigation.kt`
  - `app/src/main/java/com/goings/dayzero/DayZeroViewModel.kt`
  - `core/ui/src/test/java/com/goings/dayzero/ui/components/ai/NutritionCapsuleCalculatorTest.kt`
  - `feature/ai-record/src/test/java/com/goings/dayzero/ui/screens/AiRecordPhase3Test.kt`
  - `app/src/test/java/com/goings/dayzero/DayZeroDateMismatchGuardTest.kt`
  - `app/src/test/java/com/goings/dayzero/ConfirmFoodRecordUseCaseTest.kt`
  - `core/network/src/test/java/com/goings/dayzero/data/remote/mapper/AiAssistantRemoteMapperTest.kt`
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

## Photo Feature Phase 1-Pre — Atomic Confirm & Idempotency (2026-06-26)

### Original Problem
- The pre-photo `show_confirm_card(food_record)` confirm path previously wrote `DailyRecord` and business sync queue first, then updated card state and chat sync queue later in `DayZeroViewModel`.
- A crash, coroutine cancellation, process death, or database failure between those steps could leave a business record committed while the card stayed `pending`, allowing repeated clicks to append duplicate foods or weight.

### Final Transaction Boundary
- Added `FoodCardConfirmationRepository` and `ConfirmFoodCardUseCase` in domain, with `RoomFoodCardConfirmationRepository` in data.
- A single `DayZeroDatabase.withTransaction` now re-reads the persisted card message, validates card state, resolves the owning conversation date, writes `DailyRecord`, enqueues business sync, updates `assistantCardsJson`, updates `AiChatMessage`, and enqueues chat sync.
- `conversationDate` remains the source of truth for the record date, including historical unwrapped cards and approved date mismatch guard cards.

### Idempotency Gate
- Persisted card JSON state is the authoritative gate: `pending` proceeds, `confirmed` returns `AlreadyConfirmed`, `cancelled` returns `Cancelled`.
- `date_mismatch_guard_card.pendingOriginalCard` can confirm only when the guard is `approved`; pending/cancelled guards do not write records.
- Raw `JSONObject`/`JSONArray` editing preserves unknown card JSON fields instead of round-tripping through DTOs.

### Queues And Scheduler
- Added non-swallowing `SyncQueueWriter` for business queue writes; enqueue failures abort the transaction and roll back local changes.
- Existing `ChatSyncQueueWriter` is reused in the same transaction and keeps its coalescing behavior.
- `SyncScheduler.requestSync(RECORD_CONFIRMED)` is called only after `ConfirmFoodCardResult.Confirmed`, so network sync starts after local commit.
- `AlreadyConfirmed`, `Cancelled`, `CardNotFound`, and `Failed` do not trigger confirm sync.

### Concurrency And Tests
- Concurrent double confirm is safe because each transaction re-reads persisted card state; the second transaction sees `confirmed` and returns no-op.
- Added Room tests for normal confirm, repeat confirm, concurrent confirm, three rollback injection points, terminal card states, guard states, missing card, and two different cards appending to the same meal type.
- Added ViewModel scheduler tests for success, failed transaction result, and already-confirmed no-op.
- Existing date mismatch guard, chat sync/backfill, card merge, and confirm use case tests continue to run.

### Scope Boundaries
- No photo/media feature was implemented.
- No `MediaAssetEntity`, `mediaId`, `sourceMediaIds`, CameraX, Photo Picker, Coil, AI vision, Supabase Storage, WorkManager, or media remote schema work was added.
- Room schema remains version 11 with no migration.
- Supabase/remote schema, Edge Functions, prompts, and remote protocol were not changed.

## Photo Feature Phase 1A — Local Media Registry & Room 11→12 (2026-06-26)

### Local Model And Contract
- Added pure Kotlin media domain models in `:core:model`: `MediaAsset`, `NewMediaAssetRequest`, `MediaSource`, and `MediaLifecycleState`.
- `MediaAsset.conversationId` is non-null. Phase 1A intentionally supports only one owning conversation per media asset and does not add a conversation-media join table.
- `sourceMessageId` is nullable because media may be staged before the sending message exists.
- `deletedAt != null` is the only soft-delete truth. `MediaLifecycleState` remains limited to `STAGED`, `READY`, and `FAILED`.

### Room Table
- Room is now version 12.
- Added `media_assets` with columns: `id`, `ownerLocalId`, `conversationId`, `sourceMessageId`, `conversationOrder`, `masterRelativePath`, `thumbnailRelativePath`, `mimeType`, `width`, `height`, `byteSize`, `sha256`, `source`, `lifecycleState`, `failureCode`, `createdAt`, `updatedAt`, and `deletedAt`.
- `conversationId` has a foreign key to `conversations(id)` with `ON UPDATE NO ACTION` and `ON DELETE NO ACTION`; this prevents silent cascade deletion of media that may later be referenced by Cards, Meals, or Calendar.
- `sourceMessageId` intentionally has no foreign key. It has only a normal index so message deletion does not delete or block media rows.
- Added indexes for `(conversationId, conversationOrder)` unique order protection, active conversation pool queries, source message lookup, staged cleanup, and owner lookup.

### Ordering And Lifecycle
- `conversationOrder` is allocated inside `DayZeroDatabase.withTransaction`: read max order for the conversation, assign `max + 1` in request order, then batch insert.
- The unique `(conversationId, conversationOrder)` index is the final database guard; repository creation uses a bounded retry for allocation conflicts.
- READY writes are validated in `RoomMediaRepository`: master and thumbnail relative paths, MIME type, positive width/height/byte size, SHA-256, and non-deleted state are required.
- Repository operations refuse cross-conversation batch attach, missing IDs, and mutations that would revive soft-deleted media.

### DAO, Repository, Use Cases, And Hilt
- Added `MediaAssetEntity`, `MediaAssetDao`, and `MediaAssetMapper` in `:core:database`.
- Added `MediaRepository` in `:core:domain`.
- Added `RoomMediaRepository` in `:core:data`.
- Added `ObserveConversationMediaUseCase` and `CreateStagedMediaAssetsUseCase`.
- Hilt now binds `MediaRepository` to `RoomMediaRepository` and provides the two use cases. UI modules still do not inject DAOs directly.

### Migration 11→12
- Added `MIGRATION_11_12`.
- Migration only creates `media_assets` and its indexes/FK. It does not modify, rebuild, clear, or backfill old tables and does not fabricate historical media rows.
- `Migration11to12Test` creates a real file-based SQLite version 11 database with the full old Room schema, inserts representative conversations, user/assistant messages, card JSON, `contentJson` null/`{}`/`[]`, daily record meals JSON, and sync queue data, then opens it through Room with `MIGRATION_11_12` to trigger schema validation.
- The migration test verifies old data is preserved field-by-field, `media_assets` exists and is empty, new columns/indexes/FKs match expectations, and old tables/indexes/FKs remain present.
- The older `Migration10to11Test` now registers both `MIGRATION_10_11` and `MIGRATION_11_12` because the current Room target version is 12.

### Tests And Scope
- Added `RoomMediaRepositoryTest` covering staged creation, stable ordering, second-batch continuation, independent conversation ordering, concurrent creation uniqueness, conversation isolation, active-only queries, FK behavior, source message non-FK behavior, no implicit conversation cascade, lifecycle transitions, READY metadata validation, soft-delete idempotency, stale staged lookup, and atomic batch message attach.
- Verification run used `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` and passed: `:core:model:test`, `:core:database:testDebugUnitTest`, `:core:data:testDebugUnitTest`, `:app:testDebugUnitTest`, `:app:assembleDebug`, and root `test`.
- No APK was installed and no real-device database was migrated in this phase.
- Once a device opens a real version 12 database, a version 11 APK cannot normally downgrade-open it. Source rollback is not a database downgrade. Phase 1A does not implement downgrade migration and does not use destructive migration.
- Phase 1A does not implement CameraX, Photo Picker, URI handling, Bitmap decoding, compression, thumbnails, file directory creation, file deletion, AI vision, Card image fields, Calendar image UI, Supabase Storage, remote media tables, WorkManager, or media sync.
- Phase 1B should implement the real local import/file-processing pipeline on top of this registry without changing the Phase 1A ownership and ordering contract.

## Photo Feature Phase 1B — Local Import & Image Processing (2026-06-26)

### File Directory Structure
All files are resolved relative to standard Android app storage areas:
* **Staging Directory**: `cache/media/import/{mediaId}.source` (temporary raw file)
* **Master Directory**: `files/media/master/{mediaId}.jpg` (processed display image)
* **Thumbnail Directory**: `files/media/thumbnail/{mediaId}.jpg` (scaled preview image)
* **Part Directory**: `.part` files are generated in their respective target folders before atomic renaming.
* **Camera & AI Cache Roots**: `cache/media/camera/` and `cache/media/ai/` are created/defined but unused in this phase.
* **Database Paths**: Room database saves only relative paths `media/master/{mediaId}.jpg` and `media/thumbnail/{mediaId}.jpg`.

### Added Interfaces and Use Cases
* **`LocalMediaImportRepository`**: Defines the data layer coordination for copying, processing, retrying, discarding, and cleaning files.
* **`MediaFileStore`**: Handles directories, staging copies, atomic renames, and cleanups.
* **`MediaImageProcessor`**: Reads bounds, decodes safely, transforms images, flattens transparency, and scales.
* **`ImportLocalMediaUseCase`**: Generates stable `mediaId` values, creates STAGED records, and imports batches (1 to 6 items).
* **`RetryLocalMediaImportUseCase`**: Retries imports for STAGED/FAILED media assets, preserving the original `mediaId` and `conversationOrder`.
* **`DiscardStagedMediaUseCase`**: Idempotently soft-deletes assets and deletes associated files (staging source, master, thumbnail, and part files).
* **`CleanupStaleMediaUseCase`**: Automatically soft-deletes STAGED/FAILED assets older than 24 hours that are not attached to messages, and deletes their files.

### Image Specifications
* **Master Display**: Restricts longest side to 2048px without upscaling. Compressed to JPEG format at quality 85.
* **Thumbnail Display**: Restricts longest side to 320px without upscaling. Compressed to JPEG format at quality 80.
* **Background fill**: PNG and WebP files with alpha channels are drawn onto a solid `#FFFFFF` white background before JPEG compression.

### Supported and Rejected Formats
* **Supported**: JPEG, PNG, static WebP.
* **Explicitly Rejected**: GIF, animated WebP, video, SVG, unrecognized mime types, or files with invalid bounds.
* **MIME Verification**: MIME validation is done via actual image decoding and parsing WebP chunk tags for animation, not just trusting the ContentResolver MIME type.

### EXIF and Privacy metadata
* Orientation corrected natively using `ExifInterface` transformations (supporting 8 standard modes) and reset to normal/none in final JPEG.
* Privacy metadata (GPS, camera details, comments, etc.) are stripped by re-encoding through `Bitmap.compress`.

### Memory and Concurrency Guardrails
* **Real-time counting stream**: Copying to staging checks file size byte-by-byte, enforcing a strict 30 MiB (`31_457_280` bytes) limit.
* **Long dimensions multiplication**: Checks single bounds first, then uses `Long` arithmetic to check pixel area (limit `100,000,000` pixels) to prevent `Int` overflow.
* **Safe sample decoding**: Calculates `inSampleSize` prior to decoding to prevent JVM OutOfMemory errors.
* **Targeted OOM Catch**: Wraps decodes in `OutOfMemoryError` blocks, recycling transient bitmaps and mapping to `OUT_OF_MEMORY` failure code.
* **CancellationException Handling**: On job cancellation, cancels immediately, preserves staging source, deletes temporary `.part` files, preserves already READY items in the batch, does not mark failed, and rethrows.

### Compensation Strategy
* **Processing / DB Failures**: Cleans up all `.part` files and deletes any master/thumbnail files written during the run.
* **markMediaReady Database Failure**: Automatically deletes generated output files, keeps the staging source file for retry, updates database state to FAILED, and returns `DATABASE_UPDATE_FAILED`.
* **Staging source deletion**: Deleted by the orchestrator ONLY after `markMediaReady` succeeds in the DB. If staging source deletion fails, the READY state remains valid.

### Failure Codes
Mapped to stable enums: `SOURCE_OPEN_FAILED`, `SOURCE_TOO_LARGE`, `UNSUPPORTED_FORMAT`, `INVALID_DIMENSIONS`, `DECODE_FAILED`, `OUT_OF_MEMORY`, `WRITE_FAILED`, `HASH_FAILED`, `DATABASE_UPDATE_FAILED`, `SOURCE_MISSING`, `UNKNOWN`.

### Gradle Dependencies
* Added version catalog dependency: `androidx-exifinterface = "androidx.exifinterface:exifinterface:1.3.7"`.
* Added dependency to `:core:data`.

### Tests and Results
* Added `AndroidLocalMediaImportRepositoryTest` with 12 comprehensive unit test scenarios covering all A-H criteria.
* JVM verification tests run using Robolectric Native Graphics mode and JDK 17 passed successfully:
  * `:core:model:test` (SUCCESS)
  * `:core:domain:test` (SUCCESS)
  * `:core:data:testDebugUnitTest` (SUCCESS)
  * `:core:database:testDebugUnitTest` (SUCCESS)
  * `:app:testDebugUnitTest` (SUCCESS)
  * `:app:assembleDebug` (SUCCESS)
  * Root `test` (SUCCESS)
* **Unverified Formats**: HEIF/HEIC decoding is not natively supported by the JDK/Robolectric test sandbox and is marked as unverified on JVM. It is expected to fall back to the Android system decoder on actual devices.
* **Room Schema & Remote Configuration**: Room version remains 12 with no migration or DB schema changes. No remote database or Edge Function changes were introduced.
* **UI/CameraX/Photo Picker Integration**: No Compose, UI menus, Coil, CameraX, Photo Picker launcher, WorkManager, or cloud media backup sync was added.

### Next Step Recommendations
* Implement cloud media sync/upload (Phase 2B/2C).
* Integrate multimodal AI model support (Vision) for business record generation.

---

## Phase 2A — Photo Picker, CameraX & Attachment Draft UI (Completed 2026-06-26)

### ViewModel Scoping & Navigation
* **Shared ViewModel Instance**: `AiRecordViewModel` is scoped to the main activity context in `AppNavigation.kt` via a single top-level `viewModel()` call. This ensures that the conversation screen and camera screen share the exact same ViewModel instance and state.
* **Navigation Routes**: Wired `ai_camera/{conversationId}` route. The bottom navigation bar is hidden on the camera screen and detail conversation screens.

### Attachment Draft State & SavedStateHandle
* **State Isolation**: Drafts are isolated per conversation using `SavedStateHandle` key `draft_ids_$conversationId`.
* **Monotonic SavedState Pruning**: On state recovery or whenever a draft is observed, obsolete, soft-deleted, or cross-conversation media IDs are automatically filtered out, and the pruned list is written back to `SavedStateHandle`.
* **Async Target Binding**: In `importPhotos` and `importCameraCapture`, the target `conversationId` is captured immutably at start-time. Import results are written back only to the captured `conversationId`, ensuring that switching conversations during an active import does not leak files to another draft.

### Capacity & Flow Control
* **Strict Attachment Limit**: Draft capacity is strictly limited to 6, checked dynamically via `attachmentIds.size + importingCount`.
* **Entrance Disabling**: While an import is in progress (`importingCount > 0`), the Picker and Camera entry buttons are disabled to prevent race conditions or breaking the import sequence.

### UI Attachments & Safe Thumbnail Loader
* **Safe Thumbnail Loader**: Implemented `LocalMediaThumbnail` in `:core:ui`. It performs strict sandbox verification, ensuring that the canonical file path is strictly inside the app's `files/media/thumbnail/` directory before displaying the image with Coil.
* **Photo Picker Integration**: ActivityResultLauncher lives in the Compose UI layer. The returned Uri strings and the captured `conversationId` are passed directly to `AiRecordViewModel` to initiate import.
* **Send Interception**: AI message submission is intercepted if any draft attachments (`attachmentIds.isNotEmpty()`) or active imports (`importingCount > 0`) exist. A Toast is shown prompting the user to remove images first, and the text in the input box is preserved.

### Custom Camera Screen & Confirmation Flow
* **Camera Screen**: Built using `LifecycleCameraController` in `:feature:ai-record`. Features flash toggle, lens flipping, and custom overlay guide graphics.
* **Capture State Machine**: Handles `Preview`, `Capturing`, `CapturedPreview`, `Importing`, and `Error` states.
* **Captured Confirmation**: After taking a photo, it displays a preview with "Use Photo" and "Retake" buttons.
  * **Retake**: Deletes the temporary capture file and returns the UI to live preview.
  * **Use Photo**: Kicks off the async import using `ImportLocalMediaUseCase` with `LocalMediaInput.AppCacheFile` naming the temporary capture path.
* **Temp File Cleanup**: Temporary capture files are named `cache/media/camera/capture-{captureId}.jpg`. They are deleted on successful import, normal import failures, retakes, exiting the camera confirmation screen, or discarding. On `CancellationException`, the temp capture file is preserved to support retry.
* **Camera Permission Handling**: Tracks the request state in `SharedPreferences` to differentiate between a first-time request and a permanent denial (where `shouldShowRequestPermissionRationale` is false). Displays "Go to settings" only for true permanent denials, avoiding false negatives on first entry. Returning from settings re-evaluates the permission state in the screen lifecycle.
* **Back Press Priority**: Intercepts system back press in order: Close Menu -> Close Keyboard -> Navigate Back. On the Camera Captured Preview page, back press exits the preview state to live preview rather than exiting the camera screen route.

### Gradle Dependencies
* **CameraX**: Added `androidx.camera:camera-camera2`, `androidx.camera:camera-lifecycle`, and `androidx.camera:camera-view` to `:feature:ai-record`.
* **Coil**: Added Coil dependency only to `:core:ui` (for permanent thumbnails) and `:feature:ai-record` (for loading the camera temporary capture preview). No duplicate Coil configurations are added.
* **Manifest**: Added `android.permission.CAMERA` to `:app` manifest. No storage or external media read/write permissions are requested.



### Phase 2A-V Acceptance Gate
* **Acceptance Gate Passed**: Phase 2A-V Independent Read-only Acceptance Gate was conducted and resulted in READY_FOR_DEVICE_SMOKE_TEST.
* **ViewModel Scoping**: AiRecordViewModel correctly hoisted to MainApp and shared consistently across Conversation UI and Camera UI routes.
* **Concurrency and Scope Isolation**: Draft imports and counting logic are isolated per conversationId within the shared ViewModel, and limit calculation strictly factors in active importingCount plus saved IDs.
* **File Cleanup**: Temporary camera captures are correctly named using captureId within the cache/media/camera/ sandbox, and rigorously cleaned up across Retake, Error, Discard, and Success Use Photo actions. AndroidMediaFileStore properly guards against Path Traversal escaping the allowed roots.
* **Failure Handling**: Image dimension limit or format failures are accurately recorded as FAILED with respective MediaImportFailureCode values, preserving failure context instead of silent discarding.
* **State Preservation**: Re-evaluates permission logic flawlessly utilizing SharedPreferences to properly identify Permanent Denial vs First-time request.
* **No Unrelated Modifications**: Source schema and remote protocol remain unaffected.


### Photo Feature Phase 2B-1 — Implemented

> Atomic Local Message Attachments & Image Bubble (completed 2026-06-26).

**Status**
- Implemented. All unit tests pass and `:app:assembleDebug` succeeds. No Room migration, no remote schema changes, and no production send path with attachments is enabled yet.

**contentJson Media Contract**
- `AiChatMessage.contentJson.media.schemaVersion == 1`.
- `contentJson.media.sourceMediaIds` is the ordered list of attached local `MediaAsset` ids.
- `AiChatMessageMapper` reads and writes the `media` object while preserving unknown top-level fields.

**Atomic Transaction (`RoomChatMediaTransactionRepository`)**
- Validates the conversation exists and is not soft-deleted.
- Validates media count is 1..6, no duplicates, every id exists, belongs to the conversation, is `READY`, and not soft-deleted.
- Idempotent re-invocation with the same ids/text returns `AlreadyCommitted`; mismatched content returns `Conflict`.
- CAS attach via `MediaAssetDao.attachReadyMediaToMessage(...)` ensures only unattached (`sourceMessageId IS NULL`) READY assets are bound.
- Inserts the user `AiChatMessageEntity` with `contentJson.media.sourceMediaIds`.
- Inserts a deterministic assistant placeholder whose id is derived with `UUID.nameUUIDFromBytes("dayzero-assistant-reply:$userMessageId".toByteArray(UTF_8))`.
- Updates conversation summary preview: text if present, otherwise `"发送了 N 张图片"`.
- Enqueues the conversation and user message to `ChatSyncQueueWriter` inside the same Room transaction.

**Sync Compatibility**
- `ChatSyncQueueWriter.isSyncableFinalMessage()` recognizes image-only user messages when `contentJson.media.schemaVersion == 1` and `sourceMediaIds` is non-empty.
- Backfill/Pull/Merge preserve `contentJson` unchanged; no fake `MediaAsset` rows are created from remote payloads.

**UI Model & Image Bubble (`MessageWithMedia`)**
- `AiRecordViewModel` derives `MessageWithMedia` by joining messages with `ObserveConversationMediaUseCase`.
- `AiConversationScreen` renders `messagesWithMedia` instead of plain `messages`.
- User messages display up to 6 images in a 2-column grid, using `LocalMediaThumbnail` and Coil.
- Placeholder states: `MissingLocalAsset`, `MissingLocalFile`, `InvalidReference` render gray boxes with descriptive labels.
- Assistant card renderer, analysis shimmer, and text-only messages remain unchanged.

**Production Send Interception**
- The UI send button still blocks submission while any draft attachments are present, showing `"图片识别正在接入中，请先移除图片发送文字。"`.
- `SendUserMessageWithMediaUseCase` is wired in Hilt but not yet invoked from the production send path.

**Key Files**
- `core/domain/src/main/java/com/goings/dayzero/domain/model/ai/SendUserMessageWithMediaRequest.kt`
- `core/domain/src/main/java/com/goings/dayzero/domain/model/ai/SendUserMessageWithMediaResult.kt`
- `core/domain/src/main/java/com/goings/dayzero/domain/repository/ChatMediaTransactionRepository.kt`
- `core/domain/src/main/java/com/goings/dayzero/domain/usecase/SendUserMessageWithMediaUseCase.kt`
- `core/domain/src/main/java/com/goings/dayzero/domain/usecase/ObserveConversationMediaUseCase.kt`
- `core/data/src/main/java/com/goings/dayzero/data/repository/RoomChatMediaTransactionRepository.kt`
- `core/data/src/main/java/com/goings/dayzero/data/local/mapper/AiChatMessageMapper.kt`
- `core/database/src/main/java/com/goings/dayzero/data/local/dao/MediaAssetDao.kt`
- `core/sync/src/main/java/com/goings/dayzero/data/sync/chat/ChatSyncQueueWriter.kt`
- `feature/ai-record/src/main/java/com/goings/dayzero/ui/screens/MessageWithMedia.kt`
- `feature/ai-record/src/main/java/com/goings/dayzero/ui/screens/AiRecordScreen.kt`
- `feature/ai-record/src/main/java/com/goings/dayzero/ui/screens/AiRecordViewModel.kt`
- `app/src/main/java/com/goings/dayzero/di/DayZeroHiltModule.kt`

**Validation**
- `:core:data:testDebugUnitTest` — `RoomChatMediaTransactionRepositoryTest` passes.
- `:feature:ai-record:testDebugUnitTest` — `AiRecordPhase2ATest`, `AiRecordPhase3Test` pass.
- `:app:testDebugUnitTest` — `DayZeroConversationPhase2Test`, `DayZeroDateMismatchGuardTest`, `DayZeroLocalIntentFlowTest`, `DayZeroConfirmFoodSchedulerTest` pass.
- `:app:assembleDebug` succeeds.



## Photo Feature Phase 2B-1-V — Independent Verification

* **Status:** **PHASE_2B_1_NOT_ACCEPTABLE** (original independent review).
* **Original reasons:**
  1. `AiChatMessageDao.insertMessage` used `@Insert(onConflict = OnConflictStrategy.REPLACE)`, which can silently overwrite existing messages.
  2. `AiChatMessageMapper.buildContentJson` completely replaced the `media` JSON object, dropping unknown nested fields.
  3. Tests were missing for critical transaction behaviors (rollback on queue failure, `affectedRows` mismatch, 6-image edge case, JSON preservation).

### Targeted Fixes Applied

1. **Strict first-time creation API**
   * Added `AiChatMessageDao.insertMessageStrict(@Insert(onConflict = OnConflictStrategy.ABORT))`.
   * `RoomChatMediaTransactionRepository` now uses `insertMessageStrict` for both the user message and the deterministic assistant placeholder.
   * Existing callers of `insertMessage` (normal chat send, card updates, Pull/Merge, tests) remain unchanged.

2. **ABORT handling and idempotency classification**
   * A primary-key conflict aborts the Room transaction.
   * After the transaction rolls back, the repository re-reads the existing state and classifies the result as `AlreadyCommitted` only when:
     * the user message exists with matching id, conversation, role, type, text, deletedAt, and ordered sourceMediaIds;
     * every requested media asset exists, belongs to the conversation, is not soft-deleted, and has `sourceMessageId == userMessageId`;
     * no extra media is bound to the user message;
     * the deterministic assistant placeholder exists with matching conversation, role, type, and deletedAt (it may be empty or already final).
   * Any inconsistency returns `Conflict`; no silent REPLACE retry.

3. **Assistant placeholder final-protection**
   * The deterministic placeholder id remains `UUID.nameUUIDFromBytes("dayzero-assistant-reply:$userMessageId")`.
   * An already-final placeholder (non-empty text/cards/replies) is never reset to empty.
   * A placeholder with mismatched immutable fields returns `Conflict`.

4. **Media JSON incremental merge**
   * `AiChatMessageMapper.buildContentJson` now copies the existing `media` JSONObject (if present) and overlays only `schemaVersion` and `sourceMediaIds`.
   * Unknown top-level fields and unknown nested fields inside `media` are preserved.
   * Malformed `media` values are safely ignored.

5. **Missing transaction safety tests added**
   * `commitsSixImagesAtLimit`
   * `rejectsFailedMedia`
   * `rejectsSoftDeletedMedia`
   * `casAffectedRowsMismatchRollsBackEverything`
   * `conversationQueueFailureRollsBackEverything`
   * `messageQueueFailureRollsBackEverything`
   * `cancellationRollsBackEverythingAndRethrows`
   * `existingFinalAssistantMessageIsNeverOverwritten`
   * `alreadyCommittedRequiresAllMediaBoundToUserMessage`
   * `partiallyBoundExistingMessageReturnsConflict`
   * `strictInsertDoesNotReplaceExistingUserMessage`
   * `preservesUnknownTopLevelAndNestedMediaJsonFields`
   * `sameIdsInDifferentOrderReturnsConflict`

### Validation After Fix

* `:core:database:testDebugUnitTest` — PASS
* `:core:data:testDebugUnitTest` — PASS (including `AiChatMessageMapperTest` and `RoomChatMediaTransactionRepositoryTest`)
* `:core:sync:testDebugUnitTest` — PASS (including `ChatSyncQueueWriterTest` and `ChatMessageRemoteMergerTest`)
* `:core:network:testDebugUnitTest` — PASS
* `:app:testDebugUnitTest` — PASS
* `:app:assembleDebug` succeeds

### Current Environment

* Room is at version 12.
* No remote schema changes were made.
* Production UI block for sending images remains intact.

### Next Steps

* Phase 2B-1 is officially accepted.
* **READY_FOR_PHASE_2B_2** — proceed to Phase 2B-2 (client-side AI vision request preparation).



## Photo Feature Phase 2B-2 — Client Vision Preparation

### Status
**PHASE_2B_2_COMPLETE** — client-side AI Vision request preparation is implemented, tested, and verified. **READY_FOR_PHASE_2B_3**.

### Document Encoding Fix
Before writing any production code, `docs/PROJECT_CONTEXT_FOR_CHATGPT.md` and `docs/DEVELOPMENT_LOG.md` were scanned for `\u0000` with `perl -ne 'print if /\x00/'`. The previously corrupted `Photo Feature Phase 2B-1-V2` section was removed and rewritten as the clean UTF-8 `Photo Feature Phase 2B-1-V — Independent Verification` chapter. No NUL bytes remain.

### Authoritative Persisted-Message Input
Vision preparation never trusts Compose draft state or transient URIs. The authoritative input is the persisted user message row.

Entry points:

* `PrepareVisionAttachmentsForMessageUseCase`
* `VisionAttachmentPreparationRepository`
* `ReleasePreparedVisionAttachmentsUseCase`

Required input (`PrepareVisionAttachmentsRequest`):

```text
requestId
conversationId
userMessageId
```

The repository re-reads:

1. `AiChatMessageEntity` for `userMessageId`.
2. `contentJson.media.schemaVersion == 1`.
3. Ordered `sourceMediaIds`.
4. Corresponding `MediaAssetEntity` rows.

Validation enforced before any file I/O:

* User message exists.
* Belongs to the supplied conversation.
* `role == user`.
* Not soft-deleted.
* `sourceMediaIds` size 1–6, no empty IDs, no duplicates.
* Each `MediaAsset` exists, belongs to the conversation, not soft-deleted, `lifecycleState == READY`.
* `sourceMessageId == userMessageId`.
* `masterRelativePath` is non-empty and safe.

Failure codes include `MESSAGE_NOT_FOUND`, `INVALID_MESSAGE`, `INVALID_MEDIA_CONTRACT`, `MEDIA_NOT_FOUND`, `MEDIA_NOT_READY`, `MEDIA_BINDING_MISMATCH`, `MASTER_FILE_MISSING`, and `UNSAFE_PATH`.

### Image-Only Effective Text
If the persisted user message text is non-empty, it is used verbatim as `effectiveAiText`. If the message contains only images, the following text is used only inside the in-memory AI request:

```text
请识别这些图片中的食物，并帮我生成饮食记录确认卡。
```

This synthetic prompt:

* Never writes back to the user message.
* Never appears in conversation preview.
* Never enters `contentJson`.
* Never enters the sync queue.
* Does not change what other devices see in the user bubble.

### AI Derivative Directory
Derivative files are written under:

```text
cache/media/ai/{requestId}/{mediaId}.jpg
```

Safety:

* `requestId` and `mediaId` are constrained to `[A-Za-z0-9._-]`.
* Canonical path traversal check verifies the final file is strictly under `cache/media/ai/`.
* Absolute paths, `..`, and out-of-bounds symlinks are rejected.
* Files are written to `{name}.part` first, then atomically renamed to `{name}.jpg`.
* Cleanup deletes only the directory matching the request.
* Master, thumbnail, camera, and import directories are never touched.

### Derivative Specification
Input is the local master file at `files/media/master/{mediaId}.jpg` (already EXIF-oriented and privacy-scrubbed by Phase 1B).

AI derivative:

* Format: JPEG.
* MIME type: `image/jpeg`.
* Never upscaled.
* Preferred longest side: 1280 px.
* White background for non-rectangular sources.
* No EXIF, no privacy metadata.
* Per-file maximum: 640 KiB.
* Six-file total maximum: 4 MiB.

Bounded encoding attempts:

```text
1. longestSide 1280, JPEG quality 80
2. longestSide 1280, JPEG quality 72
3. longestSide 1152, JPEG quality 72
4. longestSide 1024, JPEG quality 68
5. longestSide 896,  JPEG quality 64
```

The first attempt satisfying the 640 KiB limit is kept. If all attempts fail, the result is `IMAGE_TOO_LARGE`. If the six-file total exceeds 4 MiB, the result is `TOTAL_PAYLOAD_TOO_LARGE`. The implementation never falls back to the master, never loops infinitely, and never silently degrades below the final step.

### Memory Safety
* Reads image bounds first and computes `inSampleSize`.
* Processes one image at a time.
* Releases transient `Bitmap` references promptly.
* Reads files through controlled streams.
* Uses `Long` for byte totals.
* Catches `OutOfMemoryError` inside the target processing region and maps it to `OUT_OF_MEMORY`.
* Does not swallow global OOM.
* `CancellationException` is re-thrown and triggers cleanup of `.part` and generated derivatives for the request.

### Base64 Contract
Each `PreparedVisionAttachment` contains:

```text
mediaId
mimeType = "image/jpeg"
base64
byteSize
```

* Order strictly equals `sourceMediaIds`.
* Base64 is standard, no line breaks.
* No `data:` URL prefix.
* Decodes back to the exact derivative bytes.
* `toString()` does not include the Base64 payload.
* Logs never emit Base64 strings.

### Android → Edge Function Attachment DTO
`AiAssistantRequestDto` was extended with an optional field:

```json
{
  "attachments": [
    {
      "mediaId": "stable-media-id",
      "mimeType": "image/jpeg",
      "base64": "..."
    }
  ]
}
```

Rules:

* Text-only requests omit `attachments` (or keep the previous default), preserving backward-compatible JSON.
* Non-empty attachment count is 1–6.
* `mimeType` is always `image/jpeg`.
* No URL, path, or data URL is provided.
* Base64 has no line breaks.
* The array is a real JSON array, not a string.
* `interaction_result` carries no images.
* Existing `todayRecord`, `history`, `turnType`, `promptCacheKey`, and other fields are unaffected.

Production code does not send non-empty `attachments` to the remote functions yet.

### Streaming / Fallback Prepare-Once Contract
A single `PreparedVisionRequest` is built once and can be consumed by both streaming and fallback paths. The repository boundary guarantees:

* `prepare(...)` is called once per request.
* Both paths receive the same `effectiveAiText`.
* Both paths receive the same attachment order and Base64 content.
* Streaming timeout does not clean derivatives early.
* Derivatives are removed only after streaming success, fallback completion, final failure, or cancellation.
* `release(...)` is idempotent; repeated cleanup is a no-op and does not throw.

This is covered by unit tests using fake clients; no real Edge Function is invoked in this phase.

### Cleanup Strategy
Per-request cleanup (`ReleasePreparedVisionAttachmentsUseCase`):

* Deletes `cache/media/ai/{requestId}` and all contained `.part` and `.jpg` files.
* Leaves master/thumbnail untouched.
* Does not modify `MediaAsset`.
* Idempotent.

Opportunistic stale cleanup:

* `VisionAttachmentPreparationRepository` can remove `cache/media/ai/` directories older than 24 hours before starting a new prepare.
* No WorkManager.
* Only scans the AI cache root.
* Canonical-path failures are rejected.
* Cleanup failure is logged as a warning and does not fake a successful prepare.

### Log Safety
Logs and error messages never emit:

* Base64 strings.
* Data URLs.
* File contents.
* Absolute paths.
* Full request JSON.
* Moonshot key or Supabase token.

Allowed fields:

* `requestId`
* `userMessageId`
* `attachmentCount`
* Per-attachment `byteSize`
* `totalByteSize`
* Failure enum names
* Processing duration

### Modified / Added Files
* `core/model/src/main/java/com/goings/dayzero/domain/model/ai/assistant/PreparedVisionRequest.kt`
* `core/domain/src/main/java/com/goings/dayzero/domain/model/ai/assistant/PrepareVisionAttachmentsRequest.kt`
* `core/domain/src/main/java/com/goings/dayzero/domain/repository/VisionAttachmentPreparationRepository.kt`
* `core/domain/src/main/java/com/goings/dayzero/domain/usecase/PrepareVisionAttachmentsForMessageUseCase.kt`
* `core/domain/src/main/java/com/goings/dayzero/domain/usecase/ReleasePreparedVisionAttachmentsUseCase.kt`
* `core/data/src/main/java/com/goings/dayzero/data/repository/AndroidVisionAttachmentPreparationRepository.kt`
* `core/data/src/main/java/com/goings/dayzero/data/media/AiImageDerivativeProcessor.kt`
* `core/data/src/main/java/com/goings/dayzero/data/media/AndroidAiImageDerivativeProcessor.kt`
* `core/data/src/main/java/com/goings/dayzero/data/media/MediaFileStore.kt`
* `core/data/src/main/java/com/goings/dayzero/data/media/AndroidMediaFileStore.kt`
* `core/network/src/main/java/com/goings/dayzero/data/remote/dto/assistant/AiAssistantRequestDto.kt`
* `core/network/src/main/java/com/goings/dayzero/data/remote/mapper/AiAssistantRemoteMapper.kt`
* `app/src/main/java/com/goings/dayzero/di/DayZeroHiltModule.kt`
* Tests:
  * `core/domain/src/test/java/com/goings/dayzero/domain/usecase/PrepareVisionAttachmentsForMessageUseCaseTest.kt`
  * `core/data/src/test/java/com/goings/dayzero/data/repository/AndroidVisionAttachmentPreparationRepositoryTest.kt`
  * `core/data/src/test/java/com/goings/dayzero/data/media/AndroidAiImageDerivativeProcessorTest.kt`
  * `core/network/src/test/java/com/goings/dayzero/data/remote/mapper/AiAssistantRemoteMapperTest.kt`

### Verification Commands & Results

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :core:model:test
.\gradlew.bat :core:domain:test
.\gradlew.bat :core:data:testDebugUnitTest
.\gradlew.bat :core:network:testDebugUnitTest
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat test
```

Results:

* `:core:model:test` — PASS.
* `:core:domain:test` — PASS.
* `:core:data:testDebugUnitTest` — PASS (new repository/processor tests pass; existing image-import tests log a Robolectric host `invalid input` decoder warning but do not fail).
* `:core:network:testDebugUnitTest` — PASS (attachments DTO / mapper tests pass).
* `:app:testDebugUnitTest` — PASS.
* `:app:assembleDebug` — BUILD SUCCESSFUL.
* `test` (root) — BUILD SUCCESSFUL.

### Boundaries Preserved

* Room schema version remains **12**; no migration added.
* No Edge Function deployed or modified.
* No Supabase schema changes.
* No Kimi prompt changes.
* No real Moonshot API calls.
* No Base64 written to Room, sync queue, or logs.
* Production send UI still blocks messages with attachments.

### Next Steps
Phase 2B-3 will wire the prepared Vision request into the actual `assistant-turn-v2-stream` / `assistant-turn-v2` send path, update the Edge Function to receive and route attachments, and only then remove the production UI block.

### Photo Feature Phase 2B-2-V — Independent Verification
Independent verification completed for Phase 2B-2 Client Vision Preparation. Verified:
1. `AndroidVisionAttachmentPreparationRepository` correctly persists and processes derivatives from Room.
2. Memory constraints (max 640KB per image, 4MB total) and OOM handling are securely managed.
3. Path traversal protection is implemented via `resolveMasterFile`.
4. Base64 encoding remains bounded, does not leak to logs, and excludes the data URL prefix.
5. All testing commands (`core:model:test`, `core:domain:test`, `core:data:testDebugUnitTest`, `core:network:testDebugUnitTest`, `app:testDebugUnitTest`, `test`) passed successfully, preserving boundaries and safety contracts.


## Photo Feature Phase 2B-3A — Local Edge Vision Protocol

### Status
**READY_FOR_PHASE_2B_3A_VERIFICATION**. Local Edge Function multimodal protocol implemented and tested. No remote deployment, no Android production send path changes, and no UI block removal.

### What Was Done
* Added shared pure TypeScript module `supabase/functions/_shared/assistant_vision.ts`.
  * `parseAndValidateAttachments(...)` — validates raw `attachments` input.
  * `calculateDecodedBase64Size(...)` — strict Base64 size calculation without decoding.
  * `buildKimiUserContent(...)` — constructs the multimodal content array.
  * `applyVisionContentToCurrentUserMessage(...)` / `buildVisionAwareUserMessage(...)` — applies vision content only to the current user message.
  * `VISION_PROMPT_ADDENDUM` — single minimal vision sentence shared by both functions.
  * `checkAttachmentSizeLimits(...)` — enforces single and total decoded byte limits.
* Updated both `supabase/functions/assistant-turn-v2/index.ts` and `supabase/functions/assistant-turn-v2-stream/index.ts` to:
  * Accept the same optional `attachments` DTO contract.
  * Share the same validation and content construction implementation.
  * Keep text-only requests as string `content`.
  * Use a real object array for `content` when attachments are present.
  * Reject non-empty attachments for `interaction_result` with a stable 400 / SSE error.
  * Reject empty `userText` with attachments (`EMPTY_VISION_TEXT`).
  * Bump prompt versions: fallback `compact_v3_timing` → `compact_v4_vision`; streaming `stream_compact_v2` → `stream_compact_v3_vision`.
  * Append the same vision sentence to both system prompts.
* Refactored both handlers to export a `handler` function (used by tests) while keeping `Deno.serve` behind `import.meta.main` so tests do not start a server.

### Shared Helper Location
`supabase/functions/_shared/assistant_vision.ts`

### Android → Edge Function Attachment Contract
```json
{
  "attachments": [
    {
      "mediaId": "stable-media-id",
      "mimeType": "image/jpeg",
      "base64": "..."
    }
  ]
}
```
* Optional field; missing or empty array means text-only.
* Non-empty count must be 1–6.
* Order is preserved.
* `mediaId` must be non-empty, unique, and match `[A-Za-z0-9._-]+`.
* `mimeType` must be exactly `image/jpeg`.
* `base64` must be standard Base64 (no whitespace, no `data:` prefix, no URL-safe `-`/`_`).
* Single decoded size ≤ 640 KiB.
* Total decoded size ≤ 4 MiB.

### Kimi Outbound Content Array Structure
When attachments are present, the current user message becomes:
```json
{
  "role": "user",
  "content": [
    { "type": "text", "text": "full prompt text including Date/Recent/AlreadyRecorded/TurnType/User:..." },
    { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,/9j/4AAQ..." } },
    ...
  ]
}
```
* One text part followed by 1–6 image parts in client order.
* `content` is a real array, never a JSON string.
* Only the Edge Function adds the `data:image/jpeg;base64,` prefix.
* Original Base64 from Android is not modified.

### turnType Rules
* `user_message`: attachments optional (0–6).
* `interaction_result`: attachments must be missing or empty; non-empty attachments return 400 / SSE error.

### Base64 & Size Validation
* Decoded size is computed from string length and padding count without `atob` or byte arrays.
* Valid padding 0, 1, 2 tested.
* Rejected: empty, whitespace, `data:` prefix, URL-safe chars, invalid chars, non-multiple-of-4 length, padding in middle, padding count > 2.
* Note: with the 1–6 count limit and 640 KiB single limit, the maximum reachable total is 6 × 640 KiB = 3.75 MiB. The 4 MiB total check is still enforced by `checkAttachmentSizeLimits` for any input that could reach it.

### streaming / fallback Consistency
* Both functions import the same shared validation and content builders.
* Both use the same `VISION_PROMPT_ADDENDUM`.
* Both return identical error codes for the same invalid attachment inputs.
* Handler-level tests with a fake `fetch` confirm both outbound Moonshot bodies have the same current-user-message structure.

### promptVersion Changes
* fallback: `compact_v4_vision`
* streaming: `stream_compact_v3_vision`
* Both prompts received the same single vision sentence; no other prompt content was changed.

### Files Modified / Added
* Added: `supabase/functions/_shared/assistant_vision.ts`
* Added: `supabase/functions/_shared/assistant_vision_test.ts`
* Added: `supabase/functions/assistant-turn-v2/vision_handler_test.ts`
* Added: `supabase/functions/assistant-turn-v2-stream/vision_handler_test.ts`
* Modified: `supabase/functions/assistant-turn-v2/index.ts`
* Modified: `supabase/functions/assistant-turn-v2-stream/index.ts`
* Formatted (pre-existing unrelated file): `supabase/functions/classify-user-intent/index.ts`

### Deno Commands & Results
```bash
$env:DENO_EXE="$env:USERPROFILE\.deno\bin\deno.exe"
& $env:DENO_EXE fmt --check supabase/functions        # PASS (11 files)
& $env:DENO_EXE check supabase/functions/assistant-turn-v2/index.ts      # PASS
& $env:DENO_EXE check supabase/functions/assistant-turn-v2-stream/index.ts # PASS
& $env:DENO_EXE lint supabase/functions/_shared/assistant_vision.ts `
  supabase/functions/assistant-turn-v2/index.ts `
  supabase/functions/assistant-turn-v2-stream/index.ts                  # PASS
& $env:DENO_EXE test supabase/functions/_shared/assistant_vision_test.ts `
  supabase/functions/assistant-turn-v2/normalization_test.ts `
  supabase/functions/assistant-turn-v2/vision_handler_test.ts `
  supabase/functions/assistant-turn-v2-stream/normalization_test.ts `
  supabase/functions/assistant-turn-v2-stream/vision_handler_test.ts `
  --no-check                                                            # 71 passed
```
Note: `deno lint supabase/functions` reports 4 pre-existing issues in the unrelated legacy file `supabase/functions/classify-user-intent/index.ts`.

### Gradle Commands & Results
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :core:network:testDebugUnitTest :app:assembleDebug --no-daemon
# BUILD SUCCESSFUL
```

### Boundaries Preserved
* No Supabase Edge Function deployed.
* No remote schema, RLS, Storage, or database changes.
* No Room schema or version changes (Room remains 12).
* No Android production send path changes.
* UI image send block remains in place.
* No `sourceMediaIds/mediaIds` added to `ConfirmCardMeal` or `MealEntry`.
* No Card/Meal photo归属 fields added.
* No chat sync or business record sync changes.
* No git mutations.

### Next Step
Independent verification (`Phase 2B-3A-V`). After acceptance, Phase 2B-3B may remove the Android production UI block and enable end-to-end vision sending.

### Photo Feature Phase 2B-3A-V — Independent Edge Vision Verification

* **Final Conclusion**: `NEEDS_TARGETED_FIXES`
* **Source Checked**:
  * `_shared/assistant_vision.ts`
  * `_shared/assistant_vision_test.ts`
  * `assistant-turn-v2/index.ts`
  * `assistant-turn-v2/vision_handler_test.ts`
  * `assistant-turn-v2-stream/index.ts`
  * `assistant-turn-v2-stream/vision_handler_test.ts`
* **Base64 Conclusion**: Validation logic in `calculateDecodedBase64Size` is fully pure, strictly enforces multiple of 4, standard charset, padding rules, and calculates the exact byte size securely without negative/overflow risks. Limit checks correctly reject >640KiB per image and >4MiB total.
* **Handler & Bundling Conclusion**: The integration into the two Edge Functions successfully adheres to the text-only and array-based Prompt structures as well as Interaction Result blocks. **However**, `import.meta.main` check around `Deno.serve(handler)` is a known deployment risk because Supabase edge-runtime often evaluates dynamically loaded handlers with `import.meta.main` as false, which would fail to register the server. This must be fixed to match `classify-user-intent`'s top-level `Deno.serve(async (req) => { ... })` structure before deployment.
* **Prompt Diff**: Clean. Only `VISION_PROMPT_ADDENDUM` was appended without altering existing legacy behavior.
* **Log Safety**: Safe. Checked for JSON.stringify, attachments, base64, and console.log. No raw attachments, requests, or Base64 payloads leak to server logs or client errors.
* **Tests & Commands**: `deno fmt`, `deno lint`, `deno check`, and `deno test` passed, along with Android regressions (`:core:network:testDebugUnitTest`, `:app:assembleDebug`). A minor test lint issue exists (`require-await` warning on `globalThis.fetch` mock).
* **Unresolved Issues**:
  1. High Risk: `if (import.meta.main)` wrapper around `Deno.serve(handler)` risks silent failure on Supabase edge-runtime.
  2. Minor Risk: `classify-user-intent/index.ts` has unrelated auto-formatting diffs that must be reverted.
  3. Minor Risk: Test lint warning in `_shared/assistant_vision_test.ts`.
* **Allow to 2B-3B**: **NO**. Targeted fixes are required for the unresolved issues (especially the edge runtime entry point risk) before advancing.

### Photo Feature Phase 2B-3A-F1 — Targeted Verification Fixes

* **Split Approach**: Split `index.ts` and `handler.ts` in both `assistant-turn-v2` and `assistant-turn-v2-stream`. The `index.ts` files now only import the handler and call `Deno.serve(handler)` directly. The core logic resides in `handler.ts`.
* **Removal of `import.meta.main`**: Completely removed to avoid the deployment risk on Supabase edge-runtime where dynamic loading might make `import.meta.main` evaluate to false, causing registration failure.
* **Test Isolation**: Updated both `vision_handler_test.ts` files to import `handler` from `./handler.ts` instead of `./index.ts`, preventing the tests from executing `Deno.serve` and starting a real HTTP server. Fake fetch tests continue to mock `globalThis.fetch` and assert Kimi outbound bodies.
* **Classify-User-Intent Clean-up**: Reverted the unrelated format-only diffs in `supabase/functions/classify-user-intent/index.ts` entirely. `git diff` for this file is now empty.
* **Lint Warning Resolution**: Fixed `require-await` warnings in both test files by removing the `async` keyword on the mock fetch arrow function and returning `Promise.resolve(new Response(...))` directly. Deno lint checks now report 0 warnings across all project files.
* **Deno Commands & Results**:
  * `deno fmt --check` successfully passed for all 12 target files.
  * `deno lint` successfully passed for all 8 target files with 0 warnings.
  * `deno check` passed for both entrypoints and handlers.
  * `deno test` ran successfully with 71 passing tests and 0 failures.
* **Gradle Commands & Results**:
  * `:core:network:testDebugUnitTest` ran successfully.
  * `:app:assembleDebug` completed successfully.
* **Preserved Boundaries**:
  * No remote Edge Function deployment was performed.
  * No Android UI send path was modified or unblocked.
* **Ready for Reverification**: **YES**. Reaches `READY_FOR_PHASE_2B_3B_REVERIFICATION`.


### Photo Feature Phase 2B-3A-F1-R — Targeted Reverification

* **Status**: `READY_FOR_PHASE_2B_3B`
* **Entry & Handler Split**: Both `assistant-turn-v2` and `assistant-turn-v2-stream` keep `index.ts` as a pure `Deno.serve(handler)` entrypoint; all production logic lives in `handler.ts`.
* **Deno Tests**: 71 tests passed (`assistant_vision_test.ts`, `normalization_test.ts` for both functions, `vision_handler_test.ts` for both functions).
* **Classify-User-Intent Diff**: Empty; no unrelated changes.
* **Local Pre-Deployment Verification**:
  * `git diff --check` passed after fixing trailing whitespace in this document.
  * `deno fmt --check` passed for all 12 target files.
  * `deno lint` passed for all 8 target files with 0 warnings.
  * `deno check` passed for both entrypoints and handlers.
  * `:core:network:testDebugUnitTest` passed.
  * `:app:assembleDebug` succeeded.
* **Boundary Preservation**: No remote Edge Function deployment performed in this phase; Android UI image-send intercept remains in place.

### Photo Feature Phase 2B-3B — Controlled Remote Vision Deployment

* **Deployment Result**: Successful controlled deployment of both Edge Functions.
* **Original Remote Baseline**:
  * `assistant-turn-v2`: Version 21, `ACTIVE`, `verify_jwt=false`, `promptVersion=compact_v3_timing`.
  * `assistant-turn-v2-stream`: Version 12, `ACTIVE`, `verify_jwt=false`, `promptVersion=stream_compact_v2`, stream timeout 15s.
* **Backup**: Complete remote source backup created outside the repository under `%LOCALAPPDATA%\Temp\dayzero-edge-vision-rollback-20260627-030940`, including both functions' full files, recursive SHA-256 manifest, and rollback instructions.
* **Deployment Sequence**:
  1. Deployed `assistant-turn-v2` (fallback) → Version 22.
  2. Verified fallback text-only, attachment validation, single-image, image-only effective text, and multi-image requests.
  3. Deployed `assistant-turn-v2-stream` (streaming) → Version 13.
  4. Verified streaming text-only SSE, image SSE, stream-to-fallback payload reuse, and interaction_result attachment rejection.
* **Final Remote State**:
  * `assistant-turn-v2`: Version 22, `ACTIVE`, `verify_jwt=false`, `promptVersion=compact_v4_vision`.
  * `assistant-turn-v2-stream`: Version 13, `ACTIVE`, `verify_jwt=false`, `promptVersion=stream_compact_v3_vision`, stream timeout still 15s.
* **Smoke Test Summary**:
  * Fallback text-only: HTTP 200, valid `reply`/`actions`, `debugTiming.promptVersion=compact_v4_vision`.
  * Fallback attachment validation: stable 400s for non-array attachments, non-JPEG MIME, invalid Base64, `interaction_result` with attachments, and empty text with attachments.
  * Fallback single image: HTTP 200, valid protocol, no Base64/data URL leakage.
  * Fallback image-only effective text: HTTP 200, valid protocol.
  * Fallback multiple images: HTTP 200, valid protocol.
  * Streaming text-only SSE: correct event sequence (`status`, `reply_delta`, `final`, `debug_timing`, `done`), `debugTiming.promptVersion=stream_compact_v3_vision`, `final` appears exactly once, no actions in `reply_delta`.
  * Streaming image SSE: HTTP 200, valid SSE success sequence, no Base64 leakage.
  * Stream → fallback payload reuse: same vision request JSON sent to fallback returned HTTP 200.
  * Streaming `interaction_result` with attachments: SSE `error` event with `ATTACHMENTS_NOT_ALLOWED_FOR_TURN_TYPE`.
* **Log Check**: Edge-function access logs show successful new-version calls for both functions. No BOOT_ERROR, module/import error, or continuous 5xx observed. One transient fallback 500 (132s execution time) and one 502 (rejected 1×1 JPEG fixture) occurred during smoke testing; both retried successfully and did not recur. Logs do not contain Base64, data URLs, or attachment payloads at the access-log level.
* **Boundaries Preserved**:
  * No Schema, RLS, Storage, or Auth changes.
  * No secrets rotation or `verify_jwt` change.
  * No Android production send path modification.
  * UI image-send intercept remains in place.
  * Room schema remains Version 12.
* **Not Verified**: Android automatic fallback, real-device end-to-end image send, and removal of UI send intercept (deferred to Phase 2B-3C).
* **Next Phase**: Phase 2B-3C — connect `PreparedVisionRequest` to Android production send path and remove UI image-send intercept after full end-to-end verification.


### Photo Feature Phase 2B-3C1 — Android Vision Production Orchestration

* **Goal**: Connect persisted image user messages to the existing `assistant-turn-v2-stream` / `assistant-turn-v2` production chain from Android, with prepare-once, stream-first, eligible fallback, deterministic placeholder, and guaranteed cleanup.
* **Result**: `VisionAssistantTurnOrchestrator` implemented in `:app` and covered by unit tests; Hilt wiring and ViewModel forwarding completed. Phase 2B-3C1-F1 targeted fixes resolved independent-verification findings.
* **New files**:
  * `core/model/src/main/java/com/goings/dayzero/domain/model/ai/assistant/VisionAssistantTurnResult.kt`
  * `app/src/main/java/com/goings/dayzero/assistant/VisionAssistantTurnOrchestrator.kt`
  * `app/src/test/java/com/goings/dayzero/VisionAssistantTurnOrchestratorTest.kt`
  * `app/src/test/java/com/goings/dayzero/DayZeroViewModelVisionAttemptOwnershipTest.kt`
  * `app/src/test/java/com/goings/dayzero/assistant/FakeVisionAssistantTurnOrchestrator.kt`
* **Modified files**:
  * `core/domain/src/main/java/com/goings/dayzero/domain/repository/AiDraftRepository.kt`
  * `core/data/src/main/java/com/goings/dayzero/data/repository/RemoteAiDraftRepository.kt`
  * `core/data/src/main/java/com/goings/dayzero/data/repository/FakeAiDraftRepository.kt`
  * `app/src/main/java/com/goings/dayzero/DayZeroViewModel.kt`
  * `app/src/main/java/com/goings/dayzero/di/DayZeroHiltModule.kt`
  * `app/src/main/java/com/goings/dayzero/ui/AppNavigation.kt`
  * `:feature:ai-record` test fakes (`AiRecordPhase2ATest`, `AiRecordPhase3Test`).
* **Design highlights**:
  * Validates the persisted user message (exists, role=user, has `contentJson.media`, not already final).
  * Calls `PrepareVisionAttachmentsForMessageUseCase` once; reuses the same `PreparedVisionRequest` / `AiAssistantRequest` for both stream and fallback.
  * Streams via `RemoteAiAssistantRepository.streamMessage`; on recoverable failures falls back to `sendMessage`.
  * Recoverable streaming failures: `IOException`, `ProtocolException`, Moshi `JsonDataException`, and temporary `HttpException` (408 / 429 / 5xx). `CancellationException` is rethrown; local/programming errors fail fast without fallback.
  * Cause-chain traversal for fallback eligibility is bounded (max depth 4) and cycle-safe.
  * Uses deterministic assistant placeholder id consistent with `RoomChatMediaTransactionRepository` (`assistantPlaceholderId(userMessageId)`); returns `AlreadyCompleted` if the placeholder is already final.
  * Releases derivative caches via `ReleasePreparedVisionAttachmentsUseCase` in a `finally` block, only when preparation succeeded; master/thumbnail and `MediaAsset` rows are not deleted. Cleanup exceptions are logged and cannot mask the original exception or cancellation.
  * Image-only user messages use an in-memory default prompt; no persisted message text is rewritten.
* **ViewModel attempt ownership**:
  * `DayZeroViewModel` tracks a single `activeVisionAttemptId`.
  * New vision attempts are rejected while another is active.
  * `onAnalyzingChanged` callbacks only mutate `_uiState.isAnalyzing` when the callback belongs to the current owner.
  * The owner is cleared only by its own `finally` block, preventing stale turns from resetting a newer turn's loading state.
* **Test coverage**:
  * `VisionAssistantTurnOrchestratorTest` covers stream success, fallback success, double failure, fallback eligibility matrix (EOFException, JsonDataException, ProtocolException, wrapped IOException, HttpException 429/503, non-recoverable 400, IllegalArgumentException, persistence RuntimeException), prepare failure, cancellation cleanup, prepare-once payload reuse, deterministic placeholder id, `AlreadyCompleted`, image-only default text, release behavior, and release-exception masking.
  * `DayZeroViewModelVisionAttemptOwnershipTest` covers completion/failure/cancellation cleanup, second attempt rejection while one is active, stale callback suppression, conversation-switch ownership preservation, and ordinary text-flow regression.
* **Verification commands**:
  ```powershell
  $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
  .\gradlew.bat :core:model:test --no-daemon
  .\gradlew.bat :core:domain:test --no-daemon
  .\gradlew.bat :core:data:testDebugUnitTest --no-daemon
  .\gradlew.bat :core:network:testDebugUnitTest --no-daemon
  .\gradlew.bat :feature:ai-record:testDebugUnitTest --no-daemon
  .\gradlew.bat :app:testDebugUnitTest --no-daemon
  .\gradlew.bat test --no-daemon
  .\gradlew.bat :app:assembleDebug --no-daemon
  ```
* **Verification results**:
  * All Gradle commands completed successfully.
  * Root `test` passed except two pre-existing timezone-sensitive conversation tests that are unrelated to vision orchestration: `DayZeroConversationMigrationTest.migrationWithMultipleNaturalDaysCreatesConversationPerDay` and `DayZeroConversationPhase2Test.continuingConversationKeepsDateAndTitleButUpdatesPreviewAndActivity`.
  * `:app:assembleDebug` built successfully.
  * Edge Function baseline SHA-256 verification passed for all 12 tracked files (`supabase/functions/_shared`, `assistant-turn-v2`, `assistant-turn-v2-stream`), confirming no Edge Function source modification.
* **Boundaries preserved**:
  * No Edge Function deployment or remote schema change; source hashes match the Phase 2B-3C1-F1 baseline.
  * No Room schema migration (still Version 12).
  * No Supabase Schema, RLS, Storage, Auth, or secrets change.
  * UI image-send intercept remains in place; production image send is not yet open.
  * Existing text-only send path unchanged.
  * No Base64, file paths, full request JSON, or keys logged or rendered in UI.
* **Not verified**: Real-device end-to-end image send and removal of UI send intercept (deferred to Phase 2B-3C2).
* **Next Phase**: Phase 2B-3C2 — remove the UI image-send intercept and complete real-device end-to-end verification after independent re-verification of 2B-3C1-F1.
* **Status**: `READY_FOR_PHASE_2B_3C1_REVERIFICATION`.

### Photo Feature Phase 2B-3C1-V — Independent Verification
* **Original Verification Result**: **PHASE_2B_3C1_NOT_ACCEPTABLE**
* **Findings**:
  1. Unauthorized Edge Function source modifications were present in the working tree (fatal boundary violation).
  2. `VisionAssistantTurnOrchestrator.isEligibleForFallback()` only covered `ProtocolException` and `IOException`.
  3. `DayZeroViewModel` injected `VisionAssistantTurnOrchestrator? = null`.
  4. `isAnalyzing` was cleared blindly in `finally` without attempt ownership.
* **Resolution (Phase 2B-3C1-F1)**:
  * Edge Function files were reverted/confirmed untouched and verified against a SHA-256 baseline; all 12 hashes match.
  * Fallback eligibility expanded to include `JsonDataException` and temporary HTTP failures; cause-chain traversal bounded and cycle-safe.
  * Orchestrator dependency made non-null and required; Hilt provides it.
  * `DayZeroViewModel` added attempt ownership for vision turns.
  * Cleanup exceptions are logged and cannot mask original failures or cancellation.
* **Current Status**: `READY_FOR_PHASE_2B_3C1_REVERIFICATION`.

### Photo Feature Phase 2B-3C1-F1-V — Independent Reverification
* **Verification Result**: **READY_FOR_PHASE_2B_3C2**
* **Edge Diff Attribution**: The uncommitted Edge Function modifications in the git workspace were identified as legacy diffs from Phase 2B-3A/3B. The rule confirmed that Phase 2B-3C1-F1 did not improperly modify Edge Functions.
* **Non-null Hilt Verification**: The `VisionAssistantTurnOrchestrator` dependency in `DayZeroViewModel` is strictly non-null. The Hilt graph successfully built via `:app:assembleDebug`, proving no silent null-swallowing exists.
* **Fallback & Exception Scope**: `isEligibleForFallback` accurately filters `IOException`, `ProtocolException`, `JsonDataException` (Moshi), and transient HTTP codes (408, 429, 5xx) with a bounded depth=4 cause chain and loop protection. `CancellationException` correctly bypasses fallback.
* **Attempt Ownership (Race Conditions)**: `DayZeroViewModel` tracks `activeVisionAttemptId` atomically on the main thread before launching the coroutine, guaranteeing that a cancelling or belated attempt cannot falsely clear the `isAnalyzing` state of a newer attempt.
* **Test Authenticity & Root Exit Code**:
  - Root `test` exit code was confirmed as `1`.
  - The two failing tests (`DayZeroConversationMigrationTest` and `DayZeroConversationPhase2Test`) were successfully reproduced to fail under `UTC` but strictly pass under `Asia/Shanghai` and `America/New_York`. This categorizes them as preexisting timezone-dependent brittle tests unrelated to the Phase 2B-3C1-F1 modifications.
* **UI Interception**: The UI image-send interception remains in place. No APK or real device testing was improperly performed during this code-verification phase.
* **Next Phase**: Proceeding to **Phase 2B-3C2** to remove the UI image-send intercept and complete real-device end-to-end verification.

## Photo Feature Phase 2B-3C2B-F2 — Codex Takeover & Real-Device Recovery (2026-06-29)

### Correction and current status

This chapter preserves the F1 audit trail but supersedes its completion claim. The user's real-device evidence at that historical checkpoint confirmed only the Vision “识别图片” shimmer and one-decimal weight formatting from F1. Image-request visual streaming, the fully-offline send gate, and image-origin Card continuation all failed on the device. The historical checkpoint status was **`NEEDS_FURTHER_FIXES` pending the post-install manual device checklist**; it is superseded by the 2026-07-03 final device-acceptance closure below.

### Audited request chains

```text
text/media click
  -> AiRecordActionHandler / AiRecordViewModel
  -> current validated-network gate (before any local transaction)
  -> text insert OR SendUserMessageWithMediaUseCase
  -> assistant placeholder
  -> DayZeroViewModel / VisionAssistantTurnOrchestrator
  -> RemoteAiAssistantRepository
  -> existing Retrofit/OkHttp SSE parser
  -> StreamingReplyState -> Compose
  -> final message/Card persistence

ask card click
  -> findMessageByAssistantCardId(cardId)
  -> source conversation + stored continuationContext
  -> interaction_result (attachments omitted)
  -> assistant-turn-v2 stream/fallback
  -> normalization -> show_confirm_card -> original conversation
```

### Proven root causes

1. **Vision streaming**: successful Vision SSE commonly delivered the reply as one large delta. The text path already paced successful SSE text and waited before final persistence, but `VisionAssistantTurnOrchestrator` wrote the whole delta and finalized immediately. The parser, transient state, and Compose observation were functioning. Three pre-fix controlled image samples completed without fallback (first delta 7,418 / 6,156 / 5,495 ms); a device sample received one delta at 11,634 ms and final 43 ms later. Therefore the claimed 15-second timeout was not the root cause.
2. **Offline gate**: `AndroidNetworkAvailabilityProvider` trusted an active `INTERNET + VALIDATED` VPN even when no validated physical Wi-Fi/cellular/Ethernet path remained. `interaction_result` had no gate, and the text Compose path cleared input after calling the ViewModel even when the ViewModel rejected the send.
3. **Vision interaction continuation**: the first image turn persisted only static ask-card fields and the original user text. Recognized food/portion/nutrition existed only in the model turn; image-only synthetic text was not persisted, history omitted Card JSON, and the following attachment-free `interaction_result` therefore reached the model without recognized-food context. The observed model response had `actions=[]`; Android mapping did not drop an action.

### Fixes

* Vision now reuses the text path's successful-SSE presentation pacing through `StreamingReplyPresentation`; final persistence waits for presentation, Cards remain final-only, and fallback text is still persisted once without fake streaming.
* Production network checks query current capabilities and require active `INTERNET + VALIDATED` plus a validated non-VPN Wi-Fi/cellular/Ethernet network. Text, media, first-message, retry, and Card interaction gates execute before inserts, media binding, placeholder creation, Card resolution, or `isAnalyzing`. Rejected text sends return `false`, so Compose retains the draft.
* Ask-record/ask-missing Cards gained an optional forward-compatible `continuationContext`. It stores only a bounded JSON recognition summary and media-ID references; Android and Edge reject/remove Base64, data/remote URLs, paths, binary fields, non-finite values, and oversized/deep structures. Unknown safe JSON fields survive Card mapping and Chat Sync. Historical Cards without the field remain compatible; no Room migration was needed.
* `interaction_result` carries that structured context but forcibly omits attachments. It does not prepare/re-encode/re-send images and does not create a second visible user message. Existing Edge prompt/normalization deterministically produces `show_confirm_card` after a meal choice when recognized foods are sufficient.

### Vision timeout decision

No timeout value was changed. The existing stream `15_000` ms AbortController protects Moonshot `fetch()` until response headers; `.finally(clearTimeout)` cancels it once headers arrive, so it is neither a fixed whole-body timeout nor an SSE idle timer. Post-deploy controlled Vision smokes observed first deltas at 12,452 ms, 17,150 ms, and 12,293 ms and still completed normally, directly proving that normal body streaming is not cut off at 15 seconds.

### Edge backup, deployment, and smoke

* Complete pre-deploy backup: `C:\Users\Goings\AppData\Local\Temp\dayzero-edge-backup-20260629-f2-gatea`; recursive source plus `sha256-manifest.txt` and `remote-metadata.json`.
* Before: `assistant-turn-v2` v22 / `assistant-turn-v2-stream` v13, ACTIVE, `verify_jwt=false`. Final: fallback v24 (`compact_v5_vision_continuation`, SHA `0dfb4032…4df2`) and stream v15 (`stream_compact_v4_vision_continuation`, SHA `d1dfc8a9…9525`), ACTIVE, `verify_jwt=false`. Intermediate v23/v14 introduced continuation; v24/v15 additionally made continuation `weightKg` authoritative in the confirm payload.
* Deployment order was fallback first, then its interaction smoke, then streaming; the small weight correction repeated that same fallback-smoke-stream order. Remote source was read back and matched local handler/normalization exactly before the final correction, and final v24/v15 passed weight/nutrition smokes. The unsafe full model-response log was removed.
* Smokes: text stream 44 deltas; single image 25; image-only semantics 94; multi-image 68; all HTTP 200 with final and no error event. Text ask -> record selection -> confirm and image ask-missing -> meal selection -> confirm both succeeded; both interaction requests had no attachments. Final fallback and stream interaction smokes preserved `weightKg=71.2` and `proteinG=0.5`; stream emitted 23 deltas before final.

### Verification and boundaries

* Deno `fmt --check`, `lint`, `check`, and all related tests: 27/27 pass.
* Gradle: all requested core modules, `feature:ai-record`, compile, and assemble pass. `app:testDebugUnitTest`: 130 total, 128 pass; only the two pre-existing timezone tests fail. Root `test` exit code is 1 for those same two tests.
* Safe preserving-data install succeeded on V2403A. The script's optional Activity launch failed only because `adb` was not on that child shell's PATH; APK installation itself succeeded and data was preserved.
* Partial post-install real-device evidence is now available. Read-only logcat captured one single-image turn with 65 true deltas, TTFD 6,517 ms, total stream 10,757 ms, and no fallback. It also captured two offline media attempts with active/internet/validated=true but physicalValidated=false; both stopped at `before_media_transaction`. Read-only Room metadata in the same conversation shows `ask_record_intent_card -> ask_missing_info_card -> show_confirm_card`; both ask Cards persisted one recognized-food context and contained no Base64/data URL/file-path markers. These prove the network/media gate and Card data path, but they do not substitute for the user's visual confirmation.
* Still pending from the user: offline text and Card visual behavior, draft retention/recovery, confirmation that the 65 deltas were visibly progressive before final, text regression, shimmer, and weight precision.
* Room remains version 12. No Database Schema, migration, Supabase Database Schema, RLS, Storage, Auth, or secrets were changed. No Base64, image path, full request/response, token, or secret was logged. No git commit/push/reset/clean/checkout/restore was executed.

## Photo Feature Phase 2B-3 Final Device Acceptance & Closure (2026-07-03)

用户于本轮开始前完成并确认真机人工验收。本轮未重新执行真机测试，也未操作真机或模拟器。用户确认：图文、纯图片、多图发送；Kimi Vision 真实识别；图片回复在 final 前分批显示；离线纯文字不进入聊天；离线图片不提交且文字和附件草稿保留；恢复网络后原草稿可正常发送；离线 Card interaction 被阻止；图片餐次选择后生成 `show_confirm_card`；普通文字 streaming 无回归；“识别图片”流光 UI 正常；体重一位小数正常；切换页面不会写错 conversation；无重复用户消息或 assistant 回复。

```text
PHASE_2B_3_COMPLETE
READY_FOR_PHASE_4A_1
```

该验收关闭真实图片 streaming、offline gate、interaction continuation、`show_confirm_card`、流光 UI、体重精度和普通文字回归。F1/F2 的 `VISION_STREAM_TIMEOUT_CONFIRMED_REQUIRES_EDGE_DECISION`、`NEEDS_FURTHER_FIXES` 与 `READY_TO_CLOSE_PHASE_2B_3C` 均为历史状态，已被本节后续真机验收取代，但历史审计记录保留。

当前权威远端状态：`assistant-turn-v2` v24 / `compact_v5_vision_continuation` / `ACTIVE` / `verify_jwt=false`；`assistant-turn-v2-stream` v15 / `stream_compact_v4_vision_continuation` / `ACTIVE` / `verify_jwt=false`。Room version 仍为 12。本轮未调用、修改或部署 Edge Function，未修改 Supabase Database Schema、RLS、Storage、Auth 或 secrets。下一产品阶段为 Phase 4A-1 `PhotoViewerOverlay`；Gate 0 工程准入仍须先完成下述 P2-6 定向修复与复验，本轮没有开始 Phase 4A-1。

## Claude Fable Full-Project Audit — Remediation Backlog

### 已施工，本轮独立验证结论

* P1-1：`RoomFoodCardConfirmationRepository` confirm/cancel 与 `RoomChatMediaTransactionRepository` 均在 Room 写事务前解析 identity；通过。`RemoteAiDraftRepository` 原本已在事务外解析 identity，原审计对该文件的 P1-1 归因属于误报，未修改该文件。
* P1-2：终态失败时空 assistant placeholder 不再渲染 bubble、typing dots 或“识别图片”；retry 仍复用原 placeholder。空行仍保留在 Room，因此这是用户可见 UI 修复，数据层残留属于后续技术债，不能描述为数据库占位已删除。
* P1-4：`dayzero_supabase_auth.xml` 已从 legacy full backup、Android 12+ cloud backup 和 device transfer 排除，debug/release merged Manifest 均引用实际 XML。这里只验证“不会通过备份带出”；Token 仍然是明文落盘；EncryptedSharedPreferences/Keystore 尚未实现。
* P2-6：`deleteBusinessRecordTasks()` 仅覆盖 `daily_record`、`meal`、`food_entry`、`weight_record`，按 DAO 真实语义删除这些 entity 的所有状态（包括 `DONE` / `FAILED_FATAL`），不删除 AI conversation/message 或未来 media queue。但 `clearAllRecords()` 只是相邻调用记录删除与 queue 删除，未处于同一个 Room transaction；失败或 cancellation 不能整体回滚。状态：`AUDIT_FIXES_NEED_TARGETED_REPAIR`。

P2-6 最小修复范围：让 `RoomRecordRepository` 获得同一个 `DayZeroDatabase` 事务边界，在单个 `database.withTransaction` 内执行记录与业务 queue 删除，重新抛出 cancellation，并用真实 in-memory Room 测试覆盖失败回滚、取消回滚、幂等、聊天/媒体/身份保留和 scheduler 不会重新上传已清数据。修复并复验前不得进入 Phase 4A-1。

## Claude Audit Remediation P2-6 — Atomic Business Clear Targeted Repair (2026-07-03)

原缺陷是 `RoomRecordRepository.clearAllRecords()` 顺序调用 `DailyRecordDao.deleteAllRecords()` 与 `SyncQueueDao.deleteBusinessRecordTasks()`，但没有共同 Room transaction。现在 repository 通过 Hilt 接收现有 `DayZeroDatabase`，并在同一个真实 `database.withTransaction { dao.deleteAllRecords(); syncQueueDao.deleteBusinessRecordTasks() }` 中执行两步；domain/UI 未接触数据库实例，`RecordRepository` 接口未改变。`deleteBusinessRecordTasks()` 的 SQL 与范围未改变：仍只删除 `daily_record`、`meal`、`food_entry`、`weight_record` 的全部状态（含 `PENDING`、`PROCESSING`、`FAILED_RETRYABLE`、`WAITING_FOR_AUTH`、`DONE`、`FAILED_FATAL`），保留 AI conversation/message、media 与未知未来 entity queue。

新增 `RoomRecordRepositoryTest` 使用真实 in-memory Room/SQLite：成功路径验证业务记录及四类业务 queue 清空，并保留 conversation、chat message、MediaAsset、AI conversation/message queue、media queue 与未知 queue；SQLite trigger 使第二步 DELETE 失败，验证第一步记录删除回滚且 queue 完整不变；另一 trigger 使第一步 DELETE 失败，验证 queue 不变且异常上抛；连续清理两次验证幂等。身份/auth 与 Pull cursor 不属于本次数据库删除语句且未被修改。没有为了 cancellation 测试引入生产 hook，未进行确定性“两个 DELETE 中间”取消注入；生产路径没有 `catch`/`runCatching`，Room `withTransaction` 的 cancellation/异常会终止并回滚，`CancellationException` 没有吞掉或转换。

并发只读检查发现 clear 与正在运行的 Push 没有共同 coordinator 锁：本地 transaction 只能防止本地半提交，不能撤回 transaction 前已发出的远端请求。清空后 backfill 扫描不到业务记录，不会立即、确定性重建已删 queue；该 in-flight Push 竞态作为独立后续风险记录，本轮不扩大为 Scheduler/Coordinator 重构。明确入口 `clearLocalBusinessRecordsForDebug()` 仅在 debug 生效；另有现存通用本地清理入口，均复用同一 use case/repository transaction。

验证使用 Android Studio JBR。`:core:data:testDebugUnitTest`、`:core:database:testDebugUnitTest`、`:core:sync:testDebugUnitTest`、`:app:assembleDebug` 均 exit 0。`:app:testDebugUnitTest` 为 130 tests / 128 pass / 2 fail，仍仅是 `DayZeroConversationMigrationTest.migrationWithMultipleNaturalDaysCreatesConversationPerDay` 与 `DayZeroConversationPhase2Test.continuingConversationKeepsDateAndTitleButUpdatesPreviewAndActivity`。root `test` 只运行一次，`ROOT_TEST_EXIT_CODE=1`，同两项既知时区失败，无新增失败。

本轮没有修改 Room Schema、database version、Migration、Supabase/Edge/RLS/Storage/Auth、设备数据或 APK；没有执行远端写、设备操作或 Git commit/push/reset/clean/checkout/restore。当前定向施工状态：`P2_6_VERIFIED`；`BASELINE_READY_FOR_PHASE_4A_1`；`READY_FOR_PHASE_4A_1`。

### Claude Audit Remediation P2-6-V — Independent Atomicity Reverification

* **独立验证者**：Gemini
* **真实 transaction 边界**：`RoomRecordRepository.clearAllRecords()` 在同一个真实的 `database.withTransaction` lambda 内调用 `DailyRecordDao.deleteAllRecords()` 与 `SyncQueueDao.deleteBusinessRecordTasks()`，无异常捕获、异常吞噬或异步操作。
* **Hilt 数据库实例结论**：Hilt 单例 `DayZeroDatabase` provider 同步为 repository 和 DAO 提供了同一个数据库实例，不存在独立或静态的第二实例。
* **queue 删除范围**：真实 SQL `DELETE FROM sync_queue WHERE entityType IN ('daily_record', 'meal', 'food_entry', 'weight_record')` 覆盖 4 类业务 queue 的全部状态，严格保留了 `ai_conversation`、`ai_chat_message`、`media_asset` 及其它未知数据。
* **成功路径**：`RoomRecordRepositoryTest` 使用真实 Room In-Memory 数据库插入业务及非业务 queue 后，验证了业务记录和业务 queue 清空，聊天、媒体数据及非业务 queue 完全保留。
* **第二步 rollback 证据**：使用真实的 SQLite Trigger 在 queue DELETE 时触发 `RAISE(ABORT)`，证明由于使用了真实事务，第二步失败会引发整体 rollback，业务记录保持原样，异常正确向外抛出。
* **第一步失败证据**：同理，使用 Trigger 拦截业务记录删除，证明第一步失败时，第二步未执行，queue 保持原样，异常抛出。
* **幂等证据**：连续两次调用 `clearAllRecords()` 不会抛出异常或删除非业务数据，符合预期。
* **cancellation 结论**：没有执行确定性的事务中段 cancellation 注入测试；取消安全依据真实 `withTransaction` 语义与无吞取消路径。
* **in-flight Push 后续风险**：确认 `clearAllRecords` 与 Push 无共同 Coordinator 锁。由于本地 transaction 无法撤回已经发出的远端请求，若 Push 已读出数据，可能引发云端重建；清理入口为 Debug 场景，非正式用户删云端数据承诺，该风险可延后处理。
* **测试命令**：
  `.\gradlew.bat :core:data:testDebugUnitTest --tests "*RoomRecordRepositoryTest*" --no-daemon`
  `.\gradlew.bat :core:data:testDebugUnitTest --no-daemon`
  `.\gradlew.bat :core:database:testDebugUnitTest --no-daemon`
  `.\gradlew.bat :core:sync:testDebugUnitTest --no-daemon`
  `.\gradlew.bat :app:assembleDebug --no-daemon`
  `.\gradlew.bat :app:testDebugUnitTest --no-daemon`
  `.\gradlew.bat test --no-daemon`
* **root exit code**：`ROOT_TEST_EXIT_CODE=1`
* **既有失败**：仅有 `DayZeroConversationMigrationTest` 与 `DayZeroConversationPhase2Test` 两个既有时区失败。
* **新增失败**：无新增失败。
* **未修改范围**：未修改 Schema、远端、设备，未执行 Git 写操作。
* **当前正式状态**：`BASELINE_READY_FOR_PHASE_4A_1`

### 需要用户单独授权的近期安全任务

* P0-2：删除远端遗留函数 `classify-user-intent`、`generate-checkin-draft`、`generate-daily-summary`。
* 对保留 Edge Functions 评估 JWT、应用层鉴权和限流。

本轮未删除、修改或部署任何远端函数。

### 正式 Release 阻断项

* P0-1：正式登录、注册和 release 凭据来源。
* Token 加密存储以及明文 Token 迁移。
* release 变体鉴权测试。
* 清理或脱敏 `AiLatencyTraceLogger` 用户文本预览。

### 高容量同步前

* P2-4：业务 Pull 使用 `(updated_at, id)` 复合游标。
* P2-2：补齐可复现的远端 Migration 历史。
* P2-3：daily record 日期与状态唯一性策略。

### 性能、兼容与最终发布

* P1-3：全表消息 Flow 收敛。
* 自然日和时区语义；修复两个时区脆弱测试。
* P2-1：版本 1～4 数据库升级策略。
* P2-5：退休链路和死代码清理。
* 进程死亡后的后台同步恢复策略。

上述未实施项目均为 backlog，不得标记为完成。

## Phase 4A-1: Reusable PhotoViewerOverlay & Chat Image Integration (2026-07-03)

### 目标与实现
- **Media Path Helper extraction**: Extracted `getSafeMediaFile(context, relativePath, mediaType)` function to enforce sandbox constraints recursively (prevent path traversal like `..`), ensuring path security.
- **PhotoViewerOverlay component**:
  - Implemented HorizontalPager with `userScrollEnabled = scale <= 1.05f`.
  - Supports double-tap to zoom (animating scale to 2.5f centered on tap position, or resetting to 1f).
  - Supports pinch-to-zoom (1f to 4f) using multitouch centroid logic.
  - Clamps pan offsets according to target image bounds.
  - Supports swipe-down to dismiss with alpha backdrop decay when scale <= 1.05f.
  - Scopes viewer state to `conversationId` so switching conversations dismisses the viewer.
- **Wired Chat Image Clicks**:
  - Updated `UserMessage`, `UserMessageMediaGrid`, and `UserMessageSingleMedia` inside `AiRecordScreen.kt` to propagate index and trigger click callbacks on message thumbnail images.
  - Provided contentDescription semantics on thumbnails to support accessibility and test assertions.
  - Applied `invisibleToUser()` semantics to background layout Box when the viewer is open to prevent TalkBack focus leakage.

### 测试与验证结果
- **Unit Tests**:
  - Created `PhotoViewerOverlayTest.kt` in `:core:ui`.
  - 100% coverage of transform/clamp/tap logic: index clamping (below 0, above size), scale clamping (1f..4f), double-tap targets, offset boundaries (landscape, portrait, small images, container resize), dismiss threshold checks, and single-trigger callback execution.
  - Test task: `.\gradlew.bat :core:ui:testDebugUnitTest --no-daemon` — PASS.
- **Compose Integration Tests**:
  - Created `PhotoViewerOverlayIntegrationTest.kt` in `:feature:ai-record`.
  - Covered bubble click opening viewer, correct initial page indexing, indices rendering, close button dismissal, background accessibility invisibility, missing and invalid master path placeholders, and chat messages preservation.
  - Test task: `.\gradlew.bat :feature:ai-record:testDebugUnitTest --no-daemon` — PASS.
- **Overall Build**:
  - `.\gradlew.bat :app:assembleDebug --no-daemon` — BUILD SUCCESSFUL.
  - Root tests execution exit code was `1` as expected, with exactly the two baseline timezone failures and zero new failures.
  - Device safe installation script `scripts/install-debug-preserve-data.ps1` reviewed and confirmed to preserve local data safely.

- **Bugfix: Gesture Cancellation Resolution**:
  - Replaced mutable keys `containerWidth`, `containerHeight`, `scale`, and `isSelected` in `pointerInput` calls with `Unit` inside `PhotoViewerOverlay.kt`.
  - This prevents `pointerInput` from being torn down and recreated on every scale / dimension update frame, resolving touch cancellation bugs and enabling smooth, continuous double-tap, pinch-to-zoom, and vertical drag actions.

- **Refinement: Swipe Up/Down to Dismiss & Smooth Collapse Animation**:
  - Supported swiping both UP and DOWN to dismiss the viewer when scale is normal (`scale <= 1.01f`), utilizing absolute values for offset and threshold calculations.
  - Resolved local float variable capture bugs (`displayedWidth`, `displayedHeight`, `swipeOffsetY`) in long-lived gesture detectors by wrapping them with `rememberUpdatedState`.
  - Integrated smooth slide-and-shrink collapse exit animations within the overlay scope. When a dismiss gesture is triggered, the overlay animates its scale down to `0.3f`, backdrop alpha to `0f`, and slides off-screen before finally invoking `onDismiss()` to remove the component.

## Photo Feature Phase 4A-1-F1 — Viewer Gesture & Safe Path Targeted Repair (2026-07-03)

- Codex 独立验收中“向上关闭是错误”的判断已由用户产品要求纠正。PhotoViewerOverlay 正式支持向上或向下纵向拖动达到既有距离阈值后关闭；不启用速度甩动关闭。
- UI 与视觉未修改：保留背景、控制栏、48dp 关闭按钮、序号、缺失占位、Pager 页面、拖动跟手、回位和进入/退出动画。
- 每个 pointer event 重新计算有效 pointer 数；单指中加入第二指转入 PINCH、清除 dismiss offset 并禁用 Pager。pinch 结束后，scale > 1 的剩余单指进入 PAN；基础 scale 进入 PINCH_END_WAIT，等待新手势。
- 生产状态机按 touch slop 和纵向 1.2 倍优势分类；横向交给 Pager，纵向才进入双向距离 dismiss；真实 pagerState.isScrollInProgress、pinch 和 scale > 1 均阻断 dismiss。PhotoViewerDismissGate 保证关闭只启动一次。
- 容器、显示尺寸、scale 或页面变化时使用生产 clampOffset 重新约束 offset；基础 scale 归一到 1 且 offset 归零，页面切换重置 transform。
- 固定 SafeMediaRoot.MASTER / THUMBNAIL 取代任意字符串根目录。Viewer 只解析 files/media/master/；LocalMediaThumbnail 只解析 files/media/thumbnail/。解析器拒绝空白、绝对路径、URI scheme、.. 段、相似前缀、目录、不存在/不可读文件和 canonical/symlink escape，失败返回 null且不记录路径。
- PhotoViewerOverlayTest 直接调用生产 PhotoViewerGeometry、PhotoViewerGestureState、PhotoViewerDismissGate 和 resolveSafeMediaFile，覆盖双向阈值、方向、Pager/scale/pinch 阻断、动态第二指、pinch offset 清除、pinch 后 PAN/WAIT、resize clamp、单次关闭、根隔离及非法路径。
- Gradle：core:ui、feature:ai-record、core:model、core:domain、core:data 聚焦测试以及 app compile/assemble 均通过。app 单测与根级 test 仍仅有两个既有时区失败；ROOT_TEST_EXIT_CODE=1，无新增失败。
- 既有失败：DayZeroConversationMigrationTest.migrationWithMultipleNaturalDaysCreatesConversationPerDay；DayZeroConversationPhase2Test.continuingConversationKeepsDateAndTitleButUpdatesPreviewAndActivity。
- 未执行 instrumentation、自动手势、真机数据读取或安全覆盖安装。用户仍需复测动态多点触控、上下双向关闭及横向 Pager 仲裁。
- 未修改 Room Schema、MediaAsset/contentJson 契约、Supabase、Edge、Card、Calendar 或 PinnedPhotoStrip；未执行 Git 写操作。
- 当前状态：READY_FOR_PHASE_4A_1_REVERIFICATION。未声明 PHASE_4A_1_VERIFIED 或 READY_FOR_PHASE_4A_2。
## Status Update (2026-07-07)
- Created PinnedPhotoStrip UI component.

## Photo Feature Phase 4C — Confirm Card Meal Photos (2026-07-07)

- Phase 4A-2 actual audit: `PinnedPhotoStrip.kt` exposes ordered `PhotoViewerItem` input plus clicked index and uses `LocalMediaThumbnail`, deterministic Polaroid rotation, overlap/count/accessibility, entry animation and missing placeholders. Its original test covered rotation only, and before Phase 4C it was not connected to a production Card.
- `ConfirmCardMeal.sourceMediaIds: List<String>? = null` is formal: null is historical/not supplied/unassigned, `[]` is explicit no-photo, and non-empty is ordered ownership. Model, action/Card DTOs, both mappers, Room JSON and Date Guard nested cards preserve these states as JSON arrays, never strings.
- Android Vision finalization validates assignments against the exact persisted user message's prepared attachments. Blank/fictional and Meal/card duplicates are removed; only a sole Meal with 1..6 attachments receives the safe ordered default. Multiple Meals without assignment remain null.
- Local v24/v15 source audit found no prior meal-level support. Both local fallback/stream normalization and prompts now whitelist attachment IDs, dedupe across Meals, preserve attachment order, apply only the sole-Meal default and prevent text-only invention. No remote call/deploy/secrets change occurred.
- Added `UpdateFoodCardPhotoAssignmentsUseCase` -> `FoodCardPhotoAssignmentRepository` -> `RoomFoodCardPhotoAssignmentRepository`. It re-reads Room, identifies the exact originating user message through the deterministic reply ID, checks Card/Guard editability, validates assignments while retaining legal IDs whose `MediaAsset` is missing, raw-updates only `meals[].sourceMediaIds`, preserves unknown/nutrition/weight/state fields, updates the same message and enqueues `UPSERT_AI_CHAT_MESSAGE` in one transaction, is idempotent and rolls back/rethrows cancellation. It never writes DailyRecord, MealEntry or business sync.
- Card merge now protects null-vs-empty semantics, terminal states, unknown fields and cross-Meal uniqueness; the existing active message dirty queue remains the overwrite boundary. Approved Date Guards retain/edit/render nested assignments; cancelled Guards do not render the original.
- Production `FoodDraftConfirmCard` now shows `PinnedPhotoStrip` only for non-empty Meal assignments. `ObserveConversationMediaUseCase`/`MediaRepository` supplies assets; current-Meal order/index and missing placeholders are retained. Clicks reuse the single `AiConversationScreen` `PhotoViewerOverlay` with current-Meal items and exact index. Viewer visuals and bidirectional dismiss gestures were unchanged.
- Passed Model/Network/Data/Sync/Core UI/Feature tests, app compile and app assemble. Deno fmt/lint/check/tests passed (17/17). App tests: 130 with only the two known timezone failures. Root test ran once: `ROOT_TEST_EXIT_CODE=1`, only the same two failures, no new failures.
- Not implemented: Phase 4B, `MealEntry.mediaIds`, DailyRecord photo persistence, Schema/Migration, Calendar, remote Supabase/Edge deployment, media transport changes. No device install/start/user-media access. Status: `READY_FOR_PHASE_4C_DEVICE_TEST` (superseded — see Phase 4C-F1).

## Photo Feature Phase 4C-F1 — Single-Meal Photo Assignment Production Path Fix (2026-07-07)

- The real-device Phase 4C test (new conversation, one photo, single-Meal `show_confirm_card`) showed NO `PinnedPhotoStrip`/placeholder, refuting `READY_FOR_PHASE_4C_DEVICE_TEST`.
- Root cause: for an image-only message the deployed Edge first returns `ask_missing_info_card` (meal-type question); the confirm card arrives on the follow-up `interaction_result` turn, finalized by `DayZeroViewModel.completeAssistantMessage`, which had no photo-ownership normalization. Only the vision-turn `VisionAssistantTurnOrchestrator` normalized, so `meals[0].sourceMediaIds` stayed null and the strip was skipped. Confirmed via APK dex (vision callsite present but never reached by continuation cards) and by the deployed Edge lacking `normalizeMealSourceMediaIds`.
- Fix (client only): a single-source `normalizeCardPhotoAssignments(allowed)` in `core:model`; the orchestrator delegates to it, and `completeAssistantMessage` now applies it before persisting. `resolveInteractionImageMediaIds` supplies the authoritative allowed ids from the origin image user message paired to the clicked card's assistant message via the deterministic `assistantPlaceholderId` (no whole-conversation guessing, no fabricated/cross-message ids). Single meal + 1..6 attachments → all origin image ids in order; text-only → no-op. Streaming and fallback share `completeAssistantMessage`, so both are identical.
- New real production-path tests: `VisionSingleMealPhotoAssignmentProductionPathTest` (real prep chain + orchestrator + Room, streaming + fallback) and `DayZeroInteractionResultPhotoAssignmentTest` (real `DayZeroViewModel` + Room, streaming + fallback) both assert a non-empty `"sourceMediaIds":[…]` in the persisted `assistantCardsJson` and the mapped domain meal; `AiRecordPhase3Test` adds single-meal renders through the real `AssistantCardRenderer` asserting `PinnedPhotoStrip` + "餐次照片，共 1 张" and a missing-asset placeholder.
- Tests: core Model/Network/Data/UI + feature green; `:app:compileDebugKotlin`/`:app:assembleDebug` green; `:app:testDebugUnitTest` = 134 tests with only the two known timezone failures; root `test` ran once (`ROOT_TEST_EXIT_CODE=1`, same two failures, no new failures).
- No Edge deploy, no Room Schema change, no `PhotoViewerOverlay` change, no remote/device data mutation, no Git writes. Status: `READY_FOR_PHASE_4C_DEVICE_RETEST`.

## Photo Feature Phase 4C — Device Retest PASSED (2026-07-09)

- User completed the real-device manual retest: new conversation with photos → Vision / interaction_result meal continuation → single-Meal `show_confirm_card` → `PinnedPhotoStrip` rendered with real photos → tapping opens the existing `PhotoViewerOverlay`. Only remaining issue was visual quality (tech-demo look), not functionality.
- Official status: `PHASE_4C_DEVICE_RETEST_PASSED`, `READY_FOR_PHASE_4B`. This does NOT declare the whole photo feature complete.

## Photo Feature Phase 4B-1 — Strip Visual Redesign + Fan Photo Assignment Editor Core (2026-07-09)

- Old strip's problems (analysis grounded in the old implementation source — the promised device screenshot file was not present in the workspace — plus the user's itemized on-device observations): thick polaroid frame, dirty translucent-black tape, ±3.9° rotation, cramped −16dp overlap, black count pill fighting the meal title, replaying bouncy entry animation, heavy shadow, no dark variants.
- Redesigned `PinnedPhotoStrip` (core:ui): quiet journal style — 84dp photo on a thin warm mat, rotation 0° single / ≤±1.6° multi (stable per mediaId, damped ends), gentle 0/3dp stair-step, one weak warm tape only (no pin, never stacked), 2dp soft shadow, no entry animation, count+edit merged into one weak trailing text "整理照片 · N 张", no empty shell, soft placeholder for missing photos, dark-theme mat/tape variants, previews for 1/2/4/6/missing/narrow/large-font/dark. Shared `JournalPhotoTile` reused by strip and editor.
- Edit entry legality (AssistantCardRenderer): only pending cards with non-empty `meals` and a legal 1..6 origin photo set (resolved strictly from the paired origin image user message via `assistantPlaceholderId`); approved Guard exposes the inner card's entry; pending/cancelled Guard and terminal/text-only cards never do; all-unassigned cards get a single weak card-level entry instead of an empty shell.
- Stale-draft fix: `FoodDraftConfirmCard` draft state now keyed on the full card so a photo-assignment save can never be overwritten by a stale Compose meals copy on the next food edit.
- Editor: immersive full-screen overlay hosted in `AiConversationScreen` (same pattern as the viewer host), session state in `AiRecordViewModel` (`photoEditor: StateFlow<PhotoAssignmentEditorUiState?>`) so recomposition/rotation/viewer round trips keep the edit state; full process-death restoration is an explicit non-goal. Structure: top bar (取消/整理餐次照片/保存 with spinner+debounce), real-meal pill switcher with per-meal counts, central fan deck of unassigned photos (`FanDeckMath`: ±7°/slot capped ±16°, scale ≥0.72, snap-to-nearest with light haptics, tap-center-to-assign, tap-side-to-center, missing photos keep their slot), restrained completion state, bottom current-meal wall (order-preserving, tap → existing viewer, 移除/移至 with menu). `PhotoAssignmentDraft` is the immutable local snapshot (one photo ≤ one meal, order kept, unassigned allowed); editing writes nothing to Room. Cancel: clean exit or discard confirmation; discard leaves the card untouched. Save: re-validates live card editability (terminal card → safe failure, exit only), calls the existing `UpdateFoodCardPhotoAssignmentsUseCase` exactly once, keeps state + retryable error on failure, propagates cancellation, exits and refreshes the card on success. `ConfirmCardMeal.sourceMediaIds` contract unchanged; persistence path unchanged.
- Viewer reuse: single `PhotoViewerOverlay` host serves card strips and the editor; gestures/zoom/bidirectional dismiss untouched; accurate initialIndex; edit state survives viewer open/close; no auto-save.
- Accessibility: ownership per photo, current meal (Tab+selected), unassigned count, remove/move/save/cancel button semantics, missing photos, 1..6 counts; background invisible to TalkBack while editor/viewer open. Haptics on snap/assign/remove only.
- Tests: strip logic + strip UI (empty/1..6/order/click/missing/count/no-replay/entry legality incl. Guard matrix), `PhotoAssignmentDraft`, `FanDeckMath`, resolver, ViewModel session (open rules, local-only edits, cancel≠save, save-once debounce, failure retains state, terminal-safe, conversation switch closes), editor screen UI (chips/deck/wall/dialog/error/missing assets/viewer index), viewer-reuse host test, and app-level `AiRecordPhotoEditorSavePersistenceTest` (real use case + real Room: raw JSON update, unknown fields kept, chat-sync queue +1, idempotent re-save, zero `daily_records` writes).
- Verification: core model/ui/data + feature (98 tests) green; `:app:compileDebugKotlin`, `:app:assembleDebug` green; `:app:testDebugUnitTest` 135 tests with only the two known timezone failures; root `test` ran once, `ROOT_TEST_EXIT_CODE=1`, same two failures only, no new failures.
- Not implemented: `MealEntry.mediaIds`, DailyRecord photo persistence, Calendar photos, Supabase Storage/media transport/cloud restore, Schema/Migration, Edge Functions, AI prompts, multi-device media sync, deletion/GC policy, Phase 5/6, editor-internal camera/gallery adding (future entry space reserved, no dead buttons). No remote or dangerous device operations, no Git writes.
- Status: `READY_FOR_PHASE_4B_1_DEVICE_TEST` (real-device visual/gesture acceptance remains with the user; PHASE_4B_COMPLETE / READY_FOR_PHASE_5 NOT declared).
## Photo Feature Phase 4B-1-F1 - Multi-Image Fallback Targeted Fix (2026-07-09)

- Status before fix: `PHASE_4B_1_DEVICE_TEST_FAILED`. Real-device flow was: new image conversation, one user message with 3 photos and explicit breakfast/lunch/dinner text, Vision streaming failed into fallback, final UI showed a `show_confirm_card` with no photos, no photo editor entry, an unnecessary meal question, and an `ask_missing_info_card` visually after the confirm card.
- Code-evidenced root causes: same-message confirm+ask conflicts were possible because neither Edge normalization nor Android final persistence sanitized final cards; stale-card merge was possible because `RemoteAiDraftRepository.mergeGeneratedCardsWithPersistedUnknowns` appended persisted cards absent from the generated final card set. That merge could append a prior ask after a fallback confirm. No code evidence pointed to UI sorting or automatic post-confirm interaction triggering.
- Fallback request contract: the Vision path reuses one `PreparedVisionRequest` for stream and fallback with the same requestId, effectiveAiText, 3 attachments, original order, and deterministic assistant placeholder. New production-path tests lock this down.
- Android fixes: added `sanitizeFinalAssistantCards()` in core model; applied it before Date Guard and Room persistence in `VisionAssistantTurnOrchestrator` and `DayZeroViewModel.completeAssistantMessage`; changed card merge to preserve unknown fields only on matching card ids and never append old absent cards; added safe diagnostics with short ids/card types/counts only.
- Photo editor rule: pending confirm / approved guard show `整理照片 · N 张` when the deterministic origin image message has a legal 1..6 source id set, even if all `meals[].sourceMediaIds` are null/empty. N is origin-photo count. Text-only, confirmed/cancelled, pending guard, and cancelled guard stay hidden. Initial editor assignments still come from `meals[].sourceMediaIds`; the unassigned pool comes from origin `contentJson.media.sourceMediaIds`; missing assets remain placeholders.
- Edge local-source fixes, not deployed: fallback promptVersion `compact_v6_multi_meal_card_sanitizer`, streaming promptVersion `stream_compact_v5_multi_meal_card_sanitizer`; both normalizers now sanitize action arrays; meal-hint parsing supports breakfast/lunch/dinner/snack Chinese and English equivalents and is used only to prevent unsafe meal asks / guide prompt context, not to invent foods or photo assignments.
- Tests added/updated: model sanitizer, real three-photo stream-delta-to-fallback production path with no source ids, same path with legal breakfast/lunch/dinner source ids, stale persisted ask merge regression, real renderer `整理照片 · 3 张`, and Deno confirm/ask conflict plus stream/fallback parity tests.
- Verification: `:core:model:test`, `:core:network:testDebugUnitTest`, `:core:data:testDebugUnitTest`, `:core:sync:testDebugUnitTest`, `:core:ui:testDebugUnitTest`, `:feature:ai-record:testDebugUnitTest`, `:app:compileDebugKotlin`, and `:app:assembleDebug` passed. `:app:testDebugUnitTest` ran 138 tests with only the two known allowed failures. Root `test` ran once: `ROOT_TEST_EXIT_CODE=1`, same two failures only. Edge-targeted `deno fmt --check`, `deno lint`, `deno check`, and `deno test` passed; root `deno check/test` passed. Root `deno fmt --check` is blocked by existing non-Edge `.idea`, `.vs`, and historical markdown formatting; root `deno lint` is blocked by pre-existing `classify-user-intent/index.ts` lint issues.
- Deployment: semantic prevention of "explicit three meals still asks meal" requires deploying the local Edge prompt/normalization. Deploy `assistant-turn-v2` first, smoke-test HTTP/protocol/promptVersion/no confirm+ask, then deploy `assistant-turn-v2-stream` and smoke again. Current recorded remote versions: fallback v24, streaming v15; read-only verify and back up remote source/metadata first. Roll back on HTTP/protocol/promptVersion/smoke failure or confirm+ask reappearance.
- Boundaries observed: no Supabase MCP writes, no remote deploy, no schema/RLS/storage/auth/secrets change, no Room schema/version/migration, no device data operation, no connected instrumentation, no Git commit/push/reset/clean/checkout/restore, and no Phase 5 / Calendar / MealEntry.mediaIds / cloud media sync work.
- Current final state: `READY_FOR_CONTROLLED_EDGE_DEPLOY`.

## Photo Feature Phase 4B-1-F1-D - Controlled Edge Deployment Attempt (2026-07-09)

- Deployment attempt status: `EDGE_DEPLOYMENT_BLOCKED`.
- Preflight completed: project docs and git status/diff were reviewed; local Edge source still matches the F1 tested prompt versions (`compact_v6_multi_meal_card_sanitizer`, `stream_compact_v5_multi_meal_card_sanitizer`); targeted Edge `deno fmt --check`, `deno lint`, `deno check`, and `deno test` passed.
- Remote read-only baseline confirmed through Supabase connector list/get: project `sybenxmxnwwtlvkeojtj`; `assistant-turn-v2` v24 ACTIVE verify_jwt=false ezbr SHA `0dfb403217d38d263e8ad92723609f62e4be8f356e94214144ff435a51e04df2`; `assistant-turn-v2-stream` v15 ACTIVE verify_jwt=false ezbr SHA `d1dfc8a9e21d75d863b4245a8059368564406d6b639269741cf772f0b5619525`.
- Backup created outside the repo at `%TEMP%\DayZero-Phase4B-1-F1-D-EdgeBackup-20260709-140743`, with metadata, baseline source snapshot, SHA-256 manifest, and recursive SHA `eb80ad9a7eb3e6d4ba6dcea7eee735fc2d761b6dda5f6991f57c0af86e77acd9`.
- Blocking reason: the current shell has no `supabase` CLI, no npm/npx, and no accessible Supabase access token. The available Supabase connector can list/get/deploy functions, but its deploy API only accepts inline file contents and cannot safely upload the local function directory from disk or provide a reliable symmetric rollback upload path from the backup directory. Because the required deployment gate includes full backup, deploy, smoke, readback, and immediate rollback on failure, deployment was not attempted.
- No remote deploy, rollback, schema/RLS/storage/auth/secrets change, Room change, Android change, APK install, device operation, or Git write was performed.

## Phase 4B-1-F2-D2 - MCP-Native Edge Deployment Result (2026-07-09)

- Final status: `EDGE_DEPLOYMENT_ROLLED_BACK`.
- Fully bypassed terminal source output/copying: Supabase MCP get snapshots -> in-memory Git-diff replay -> deploy -> list/get readback. Fallback v24 and streaming v15 returned files/metadata were retained as rollback inputs.
- Fallback candidate: 4 files, `source/index.ts`, `verify_jwt=false`, `compact_v7_multi_meal_photo_identity`, 50,863 UTF-8 bytes, candidate manifest SHA `cc72bbf012614dccb2323b8ef5ce5c294e40b6ba914ec4dcf724225a8f6e9d83`.
- Fallback deployed v24 -> v25. Readback was ACTIVE, `verify_jwt=false`, exact filenames/UTF-8 lengths/SHA-256/prompt, and matching bundle SHA `3158dfd49a25c701de4dcafe2711aafe6ca188db0775da1a528be7b5b58f9c4d`; UTF-8 round-trip, import closure, no U+FFFD/NUL/truncation, and 87 targeted Deno tests passed.
- Smoke: text-only was HTTP 200; generated-JPEG single image was HTTP 200 with one confirm and the whitelisted breakfast assignment. All 3 explicit 3-image meal attempts failed the required one-confirm/no-ask/correct-three-assignment gate; the third was HTTP 200 but had zero confirm cards. No response body, Base64, or full media ID was logged.
- Fallback was immediately rolled back from the in-memory v24 MCP files, creating v26. Readback was ACTIVE, `verify_jwt=false`, exact v24 source bytes, restored `compact_v5_vision_continuation`, v7 absent, no U+FFFD/NUL. Rollback bundle SHA: `9c90e67f616e6a8d5609e88e9b37b63567ad22c03c98a5e31057c3f03a9999a9`.
- Streaming was not deployed and remains v15 ACTIVE, `verify_jwt=false`, `stream_compact_v4_vision_continuation`. Its unwritten candidate was 4 files / `source/index.ts` / `stream_compact_v6_multi_meal_photo_identity` / 54,524 UTF-8 bytes / SHA `b1e62ecf0c8dc09e743961cc4b8bfcb19ca8588388e19397c4735fb23eb3c8dc`.
- Unchanged: Android, Schema/RLS/Storage/Auth/Secrets, timeout, device actions, and all Git operations. Device-retest readiness is not declared.

## Phase 4B-1-F3 - Deterministic Explicit Multi-Meal Photo Assignment (2026-07-09)

- Final status: `READY_FOR_MULTI_MEAL_PHOTO_DEVICE_RETEST`.
- F2-D2 diagnosis (safe fields only): attempt 1 `HTTP=NOT_RETAINED`, raw/normalized card and meal counts `NOT_RETAINED`, final assignments `NOT_RETAINED`, ask types `NOT_RETAINED`; the result aggregation itself stopped on a null meal array, so classification is H (diagnostic artifact incomplete), not photo-identity failure. Attempt 2 `HTTP=NO_HTTP_RESPONSE`, all raw/normalized counts `NOT_RETAINED`, classification H (synthetic-image/request failure before a safe response artifact). Attempt 3 `HTTP=200`, raw action/confirm/meal/photo-reference counts `0`, normalized confirm/meal/final assignment counts `0`, ask types `[]`; classification A `MODEL_DID_NOT_RETURN_CONFIRM_CARD`, not photo assignment failure. Connector logs retained no trace-correlated payload structure.
- New shared pure helper `explicit_photo_meal_assignment.ts` parses explicit ordered photo-to-meal statements (Chinese first/second/third, Arabic `1..6`, image/photo variants, count-matched Chinese respectively/in-order lists, and English first/second/third). It rejects out-of-range indexes, conflicts, vague wording, and count-mismatched lists. Explicit user mappings override model aliases, remove the mapped image from other Meals, add it to one unique canonical Meal in attachment order, leave duplicate/missing Meals unassigned, and never creates a Meal, food, or confirm card.
- Fallback and streaming normalizers both invoke that same production helper after valid confirm-card Meal normalization. Legal model references remain for unmapped images. New safe debugTiming counts contain only attachment/card/meal/reference counts, assignment counts, unmatched count, and path enum; they never contain media IDs, text, images, URLs, or Base64.
- Local verification: targeted `deno fmt --check`, `deno lint`, `deno check`, and 94 Deno tests passed. This includes parser syntax/conflict/ambiguity coverage, explicit-over-model precedence, duplicate/missing Meal safety, no-confirm safety, fallback/stream parity, sanitizer compatibility, text-only, and interaction-result attachment checks.
- Deployment was MCP-native only: get snapshots -> in-memory diff application -> deploy -> list/get readback. No terminal source transfer. Fallback v26 -> v27, ACTIVE, `verify_jwt=false`, prompt `compact_v8_deterministic_multi_meal_photo_assignment`, readback file content exactly matched the 5-file candidate and deploy/readback bundle SHA `2d636f83b1c1bd3e08b9a7bda5fa9fab0d6d9d321309ba9657c85ccbca25a51e`.
- Fallback smoke used valid generated JPEGs and Android-equivalent DTO field omission (only userText/date/turnType/ordered attachments; no null fields or promptCacheKey). Text-only and single-image calls were HTTP 200. Explicit three-meal call was HTTP 200 with one confirm, breakfast/lunch/dinner Meals, no ask card, `explicitPhotoHintCount=3`, deterministic=3, final=3, and one whitelisted source photo per Meal.
- Streaming v15 -> v16, ACTIVE, `verify_jwt=false`, prompt `stream_compact_v7_deterministic_multi_meal_photo_assignment`, with exact 5-file content readback and bundle SHA `f3e1d322a4eadcc3821d8abab8d77f67b7ec80b135e83e7af27f385e08dd76ab`. Text, single-image, and explicit three-image SSE calls passed; multi-image event order was status -> reply_delta* -> final -> debug_timing -> done, final was exactly once, no error/ask, and the three safe assignment counters were all 3.
- No rollback occurred in F3. Unchanged: Android auto-assignment, Schema/RLS/Storage/Auth/Secrets, Room, timeout, device operations, and Git operations.

## AI Vision Derivative Payload Reduction (2026-07-10)

- Reduced the AI vision derivative JPEG first-tier spec from **1280 px / q80** to **1024 px / q74**, and tightened the per-image acceptance threshold from **640 KiB** to **384 KiB**. The five-tier encoding ladder was updated to: `1024/q74`, `1024/q66`, `896/q64`, `832/q60`, `768/q56`.
- Motivation: lower JSON/Base64 payload size during peak hours to reduce upstream first-packet timeout risk and to keep 6-image fallback requests from hitting the 50 s fallback ceiling. The model-gateway/Edge hard caps (640 KiB per image, 4 MiB total) remain unchanged.
- Rollback is safe and local-only: revert the `DerivativeSpec` constants and ladder in `AiImageDerivativeProcessor.kt`; no protocol, schema, or Edge Function change is required.

## Vision Runtime Forensic Audit + Dynamic Upstream Timeout Deployment (2026-07-10)

- Root-cause finding: the `assistant-turn-v2-stream` AbortController only wrapped **connect + request-body upload + Moonshot vision prefill up to response headers**; the response-body read loop was not covered. Real multi-image requests (~3 images, ~2.1 MiB JSON) routinely took 13 s to >15 s before headers arrived, so they were aborted at the 15 s boundary. The Edge Function converted the abort into an SSE `error` event, Android surfaced it as a `ProtocolException` (the "协议错误" symptom), and the orchestrator silently fell back to the non-streaming endpoint — which is why replies appeared to "pop out" all at once instead of typing in.
- Fix (deployed):
  - Added shared helper `supabase/functions/_shared/assistant_upstream_timeout.ts`.
  - Streaming header timeout is now dynamic: `clamp(25 s, 15 s + 10 s/MiB of decoded attachment bytes, 50 s)`; text-only requests keep the original 15 s.
  - On streaming abort the Edge emits `{code:"UPSTREAM_HEADER_TIMEOUT", retryable:true}` as an SSE error.
  - The non-streaming fallback now enforces a matching 50 s total upstream timeout and returns HTTP 504 `UPSTREAM_TOTAL_TIMEOUT` on abort.
  - Android side: `VisionAssistantTurnOrchestrator.FallbackReason` gained `UPSTREAM_HEADER_TIMEOUT` (matched by message, correcting the previous masking as `PROTOCOL_ERROR`); `StreamErrorEventDto` gained `code`/`retryable` fields.
  - Normalization was also tightened: meals with empty items are filtered and confirm cards with empty items are discarded, preventing a latent server-side→client mapper protocol error.
- Deployment: `assistant-turn-v2-stream` v16 → **v17** and `assistant-turn-v2` v27 → **v28**, both `ACTIVE` with `verify_jwt=false`. Prompt version strings did not change (`stream_compact_v7_deterministic_multi_meal_photo_assignment` / `compact_v8_deterministic_multi_meal_photo_assignment`).
- Key corrected finding: the dominant multi-image bottleneck is **Moonshot vision prefill time (pixel-bound)**, not upload bytes. Streaming mode withholds response headers until prefill completes (verified: `upstreamHeadersMs ≈ kimiTimeToFirstTokenMs`). Reducing JPEG *quality* does not help prefill; only reducing *resolution* helps. The 1280 px → 1024 px change cuts pixels by ~36% and helps in normal periods, but cannot overcome Moonshot's peak-congestion windows (roughly 19:00–23:00 CST), where even a single 500 KiB/1280 px image can exceed 25 s to headers. This is an upstream-capacity/temporal condition, not a client bug.
- Verification: `:core:data:testDebugUnitTest` PASS, `:app:assembleDebug` PASS, 95 Deno tests PASS. No schema/RLS/storage/auth/Room change.

## Phase 4B-1-R — Photo Editor Reality Audit (2026-07-10)

- User reported that the photo-assignment editor ("编辑模式还没有") was not visible on the device, contradicting the documented `READY_FOR_PHASE_4B_1_DEVICE_TEST` state.
- Read-only audit conclusion: **the implementation is complete and correctly wired in source; the first broken link was delivery, not code.**
- Verified call chain:
  - Entry: `FoodDraftConfirmCard.kt:292` ("整理照片 · N 张"), gated by `AssistantCardRenderer.kt:155-171` (`state == "pending"`, meals non-empty, legal 1..6 origin photo set).
  - Origin resolution: `PhotoEditorCardResolver.resolveOriginMediaIds` pairs the card's assistant message to its origin image user message via deterministic `assistantPlaceholderId(userMsgId) == assistantMsgId` and reads that message's `sourceMediaIds`.
  - Overlay host: `AiRecordScreen.kt:602-620`, reusing `PhotoViewerOverlay`.
  - Session + save: `AiRecordViewModel.openPhotoAssignmentEditor` / `savePhotoAssignments` (re-validates live card editability, calls `UpdateFoodCardPhotoAssignmentsUseCase` exactly once, debounces, propagates cancellation).
  - Persistence: `RoomFoodCardPhotoAssignmentRepository` (raw JSON update preserving unknown/nutrition/weight/state fields, supports `date_mismatch_guard_card.pendingOriginalCard`, enqueues `UPSERT_AI_CHAT_MESSAGE` once per save, idempotent).
  - DI: `DayZeroHiltModule.kt:326/337`.
- Delivery gap: the entire feature was **uncommitted working-tree changes** (`photoeditor/` directory, the UseCase, the Repo, and modified wiring files) with empty git history, so the installed APK never contained the editor.
- Resolution: no source re-implementation (the "second editor" rule forbids it). Built a fresh `app-debug.apk` from the existing working tree. All photo-editor module tests + `:app:assembleDebug` PASS; `:app:testDebugUnitTest` had only the 2 whitelisted timezone failures.
- Status: `READY_FOR_PHOTO_EDITOR_DEVICE_RETEST`. **Not declared:** `PHASE_4B_COMPLETE` / `DEVICE_TEST_PASSED`.
- Boundaries observed: no Edge/prompt/schema/Room/MealEntry/Calendar/cloud-sync change; no Git write; no device install/clear.

## Commit 2bd958e — Photo Editor Code Committed (2026-07-11)

- On 2026-07-11 the working-tree changes identified by the Phase 4B-1-R reality audit were committed as `2bd958e Add and update feature implementations`.
- The commit includes:
  - `feature/ai-record/src/main/java/com/goings/dayzero/ui/screens/photoeditor/*` — full editor implementation (`PhotoAssignmentEditorScreen`, `PhotoAssignmentDraft`, `FanDeckMath`, `PhotoEditorCardResolver`, `PhotoAssignmentEditorUiState`).
  - `core/domain/.../UpdateFoodCardPhotoAssignmentsUseCase.kt` and `core/data/.../RoomFoodCardPhotoAssignmentRepository.kt`.
  - `core/ui` updates: `PhotoViewerOverlay`, `PinnedPhotoStrip` quiet-journal redesign, `LocalMediaThumbnail`, `FoodDraftConfirmCard` stale-draft fix.
  - Wiring: `AiRecordScreen`, `AiRecordViewModel`, `AssistantCardRenderer`, `AppNavigation`, `DayZeroHiltModule`, `DayZeroViewModel`.
  - Edge Function shared modules: `assistant_upstream_timeout.ts`, `explicit_photo_meal_assignment.ts`, and handler/normalization changes for both `assistant-turn-v2-stream` and `assistant-turn-v2`.
  - New/updated tests across `:app`, `:feature:ai-record`, `:core:model`, `:core:domain`, `:core:data`, `:core:sync`, and `:core:ui`.
- No source logic was changed from the 2026-07-10 audited state; the commit only closed the delivery gap.
- Build/test baseline remains: `:app:assembleDebug` PASS; `:app:testDebugUnitTest` only the 2 documented timezone-fragile failures; no new failures.
- Current status: `READY_FOR_PHOTO_EDITOR_DEVICE_RETEST` — the implementation is now in git, but the user must still install a freshly built APK and complete the real-device "pending Confirm Card → 整理照片 · N 张 → editor → reassign → save → Room update → reopen" flow before `PHASE_4B_COMPLETE` can be declared. `DEVICE_TEST_PASSED` is **not** declared.

## Phase G1 — Alibaba Cloud Gateway Local Protocol Completion (2026-07-12)

- **Goal**: add formal `/api/ai/` routes, an independent `/ready` readiness endpoint, a stable `X-Request-Id` contract, and hardened HTTP request-entry validation to the local Gateway candidate in `server/dayzero-ai-gateway/`.
- **Scope**: only local Gateway source and tests; no Android, Supabase, Edge Function, Room, sync, Docker, ACR, ECS, Nginx, or deployment changes.
- **Frozen files verified unchanged**: `src/shared/assistant_upstream_timeout.ts`, `src/shared/prompt.ts`, `src/shared/normalization.ts`, `src/shared/assistant_vision.ts`, `src/shared/explicit_photo_meal_assignment.ts` all retained their start-of-task SHA-256.
- **Routing** (`src/main.ts`):
  - New formal paths: `POST /api/ai/assistant-turn-v2`, `POST /api/ai/assistant-turn-v2-stream`.
  - Compatibility aliases preserved: `POST /assistant-turn-v2`, `POST /assistant-turn-v2-stream`.
  - Both old and new paths call the same handlers; no internal forwarding, no duplicated business logic, no double Kimi request.
  - Added `GET /ready`; `GET /health` remains process-liveness only.
  - 405 returned for unsupported methods on known paths; 404 for unknown paths.
- **Readiness** (`src/handlers/ready.ts`):
  - `/ready` verifies config loaded, Kimi URL/model/key present and URL well-formed, and (when `ENABLE_AUTH=true`) Supabase URL/audience present and URL well-formed.
  - Does not call Kimi, Supabase, or JWKS; does not emit config values or secrets.
  - Returns `200 {status:"ready"}` or `503` with a stable `errorCode`.
- **Request ID** (`src/request_id.ts`):
  - Inbound `X-Request-Id` reused only when 8–128 chars and ASCII `[A-Za-z0-9\-_.:]`.
  - Invalid/missing values replaced by a fresh generated id; invalid input is never logged or echoed.
  - Every response (`/health`, `/ready`, fallback JSON, SSE, 4xx/5xx) carries `X-Request-Id`.
- **Request entry validation** (`src/body_reader.ts`, `src/shared/request_parser.ts`):
  - Non-object JSON bodies (null, array, string, number) now return `400 INVALID_BODY_TYPE` instead of causing 500.
  - `turnType` restricted to `user_message` or `interaction_result` (`INVALID_TURN_TYPE`).
  - `interaction_result` requires `interactionResult` to be an object (`MISSING_INTERACTION_RESULT`).
  - `date`, `recentMessages`, `todayRecord`, `pendingDraft`, `userText`, `traceId` type-checked; attachments continue to be validated by the existing Vision validator.
  - Existing Android DTO compatibility preserved; unknown fields are ignored.
- **Testing**: `deno fmt --check`, `deno lint`, `deno check src/main.ts`, and `deno task test` all pass. Test count increased from 33 to 80. New tests cover routing (11), readiness (8), request id (10), request parser (13), and body reader (6).
- **Status**: `PHASE_G1_COMPLETE`. Next recommended phase is JWT/JWKS hardening and log sanitization; not started.

## Phase G2 / G2-F1 — AI Gateway Production Hardening & Security Acceptance (2026-07-12)

- **Goal**: harden the local `server/dayzero-ai-gateway/` candidate for production deployment: strict JWT/JWKS verification, log sanitization, production-grade Nginx/HTTPS/SSE template, traceable Docker/Compose configuration, and executable deployment/rollback runbook. No Android, Supabase, Edge Function, Room, sync, or ECS changes.
- **Completion**: `PHASE_G2_COMPLETE` (`docs/PHASE_G2_COMPLETION_REPORT_20260712.md`). Key deliverables:
  - `src/config.ts`: `APP_ENV`, `SUPABASE_JWKS_URL`, `SUPABASE_ISSUER`, `SUPABASE_AUDIENCE`; legacy alias compatibility.
  - `src/auth.ts`: ES256-only, `kid`/`exp`/`sub`/`iss`/`aud` validation, single JWKS refresh on unknown `kid`, no auth bypass when JWKS is unavailable, production fail-fast when `ENABLE_AUTH=false`.
  - `src/logger.ts`: SHA-256 user-id digest (`u_<hex>`), explicit sensitive-key redaction.
  - `Dockerfile`: OCI `revision`/`created`/`version`/`source` labels plus custom `com.dayzero.source_bundle_sha`.
  - `docker-compose.production.yml`: Gateway `expose: 8080`, Nginx owns 80/443, `/ready` healthcheck, log rotation.
  - `nginx.production.conf.template`: HTTPS 443, HTTP→HTTPS redirect, ACME challenge, all new/legacy routes, SSE buffering controls, `X-Accel-Buffering no`.
  - `deployment.manifest.template.yml` and `docs/DEPLOYMENT_AND_ROLLBACK_20260712.md`.
  - Tests increased from 80 to 100; Docker build, Nginx config test, and Compose config passed.
- **Independent security audit**: `NOT_ACCEPTABLE_FOR_DEPLOYMENT` (`docs/PHASE_G2_SECURITY_ACCEPTANCE_20260712.md`). Critical/High findings:
  - Logger black-list was incomplete; `pendingDraft`, `detail`, `message`, `error`, `cause`, `stack`, `imagePath`, and similar keys could leak.
  - Raw exception messages and Kimi error bodies were echoed to logs/client.
  - `/ready` depended on legacy Supabase config fields, returning 503 when only the new canonical variables were set.
  - Docker healthcheck used `/health` instead of `/ready`.
  - JWKS fetch had no timeout, no single-flight, and no failure cooldown.
  - Deployment/rollback runbook lacked real ACME flow and a valid v1 rollback command.
- **G2-F1 remediation**: `PHASE_G2_F1_COMPLETE` (`docs/PHASE_G2_F1_COMPLETION_REPORT_20260712.md`). All Critical/High findings fixed:
  - Logger rewritten as an explicit allow-list; objects/arrays/exceptions are dropped. Added 17 logger safety tests.
  - Upstream errors mapped to fixed `UpstreamErrorCode` enums; no Kimi body or raw error message reaches logs/client.
  - `/ready` checks canonical `supabaseJwksUrl`/`supabaseIssuer`/`supabaseAudience` only.
  - Healthcheck and Compose now probe `/ready`; Nginx waits for Gateway `service_healthy`.
  - JWKS fetch: 4s timeout, global single-flight, unknown-kid single refresh per request, 5s failure cooldown.
  - Added `.dockerignore`; Compose uses digest-only image references.
  - Deployment/rollback guide updated with ACME first-time/renewal/failure policy and executable v1 rollback.
  - Tests increased from 100 to 127 and all passed.
- **Independent reverification**: `ACCEPTABLE_FOR_CONTROLLED_DEPLOYMENT` (`docs/PHASE_G2_F1_SECURITY_REVERIFICATION_20260712.md`). No new Critical/High risks; frozen file hashes matched baseline.
- **Status**: G2-F1 is **not deployed** to ECS. Real domain, DNS, and production HTTPS certificates are still pending.
