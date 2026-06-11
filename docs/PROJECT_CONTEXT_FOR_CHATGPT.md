# DayZero Project Context

## Current status

- **Phase 4D-1 is complete**. Real database writing for `show_confirm_card` (`food_record`) has been fully implemented on the client side, now supporting multiple meals (`meals[]`) and optional weight recording (`weightKg`).
- When the user clicks "确认记录", the client directly parses the `PayloadSummary` (or user's locally edited DraftCard state), assembles `MealEntry` and `FoodEntry`, and writes to the Room database via `RecordRepository`. This bypasses further Kimi interaction.
- The UI card states (`pending`, `confirmed`, `cancelled`) are updated and persisted into the chat history. The card action buttons are conditionally hidden once a resolution state is reached. DraftCard items can be locally edited or deleted before confirmation.
- Feedback messages like "好的，已为你处理：" are generated deterministically by the client without hitting the LLM network.
- `assistant-turn-v2-stream` is the current primary AI runtime entrypoint. `assistant-turn-v2` remains as a temporary compatibility fallback. The retired `ai-assistant-turn` endpoint must not be restored.
- Room chat persistence is fully enabled. User messages, AI replies, and cards are fully persistent. 

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
- Primary Edge Function: `assistant-turn-v2-stream`
- Fallback Edge Function: `assistant-turn-v2`
- Retired Edge Function: `ai-assistant-turn` should stay deleted/unused
- Remote status: `ACTIVE`
- Stream prompt version: `stream_compact_v1`
- `verify_jwt=false`

## Architecture reference

- Canonical architecture reference is `docs/AI_ASSISTANT_TURN_V2_ARCHITECTURE.md`.
- Next step is **Weight Tracking (e.g., `weight_record`) or Daily Summary Tools** under the new V2 architecture.

## GPT handoff: streaming card latency optimization

The current important optimization task is to fix slow card appearance in the streaming AI chat path.

Observed trace behavior:

- Streaming text works and first text appears in about 3-4 seconds.
- For card-producing turns, `assistant-turn-v2-stream` often completes around 5-6 seconds and already reports `actionsCount=1`.
- The Android client then throws a protocol error because the streamed action payload is incomplete.
- The client falls back to `assistant-turn-v2`, causing a second Kimi request.
- This makes cards appear around 14-20 seconds instead of shortly after the stream completes.
- Card rendering itself is fast, normally tens of milliseconds. The slowness is the fallback round trip.

Root cause:

- Kimi is currently encouraged to return compact JSON.
- The outer compact format is fine: `{ "r": "...", "a": [...] }`.
- But `a[]` sometimes becomes too short and lacks the complete public card payload expected by Android.
- Android validates existing card schemas strictly, so missing template fields trigger fallback.

Do not fix this by making Kimi output long full UI JSON again.

Correct fix:

- Let Kimi output short business actions.
- Let the Supabase stream function normalize those short actions into the existing full Android card payload.
- Android public models and Compose card UI should remain unchanged.

Kimi should generate only:

- Natural reply text.
- Whether a card is needed.
- Card type.
- Food item names.
- Portion/amount text.
- Estimated calories.
- Meal type inference.
- Optional weight.
- Multiple-meal structure when present.

The Edge Function should deterministically fill:

- Card title.
- Card message.
- Static buttons/options.
- `interactionId` / `id`.
- `confirmType`.
- `date`.
- `calorieConfidence = "estimated"` when missing.
- `state` defaults.
- `meals[]` wrapper when Kimi gives `mealType + items[]`.

Normalization rules:

- `ask_record_intent_card`: Kimi may return only `type` and optional `originalText`. Server fills title, message, originalText, and options `record/chat_only/not_now`.
- `ask_missing_info_card`: Kimi may return only `type`, optional `field`, optional `originalText`. Server fills title, message, `field=mealType`, and options `breakfast/lunch/dinner/snack`.
- `show_confirm_card`: Kimi returns food business data only: meal type(s), item names, amount text, calories, optional weight. Server fills the complete `food_record` confirm card payload, including buttons `confirm/cancel`.

Fallback should happen only when:

- Stream JSON is not parseable.
- Reply is blank.
- Action type is unsupported.
- Confirm card has no usable food items.

Fallback should not happen just because title/message/options/buttons are missing; those are template fields.

Acceptance criteria:

- Food mention flow: no `remote_repository_stream_failed_fallback_start`.
- `fallbackUsed=false`.
- Card appears after stream completion, not after a second non-streaming request.
- Android still receives the same full public payload shape as before.
