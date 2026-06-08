# DayZero Project Context

## Current status

- **Phase 4D-1 is complete**. Real database writing for `show_confirm_card` (`food_record`) has been fully implemented on the client side, now supporting multiple meals (`meals[]`) and optional weight recording (`weightKg`).
- When the user clicks "确认记录", the client directly parses the `PayloadSummary` (or user's locally edited DraftCard state), assembles `MealEntry` and `FoodEntry`, and writes to the Room database via `RecordRepository`. This bypasses further Kimi interaction.
- The UI card states (`pending`, `confirmed`, `cancelled`) are updated and persisted into the chat history. The card action buttons are conditionally hidden once a resolution state is reached. DraftCard items can be locally edited or deleted before confirmation.
- Feedback messages like "好的，已为你处理：" are generated deterministically by the client without hitting the LLM network.
- `assistant-turn-v2` is the only active AI main runtime entrypoint. Legacy fallback is strictly prohibited.
- Room chat persistence is fully enabled. User messages, AI replies, and cards are fully persistent. 

## Current Phase Features (Phase 4D-1 Complete)

- **JSON Protocol**: Edge function `assistant-turn-v2` returns `{"reply": "...", "actions": []}`. Text replies are optional; if missing, client will fallback to a default prompt. Action identification now uses `id` instead of `interactionId`.
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
- Edge Function: `assistant-turn-v2`
- Remote status: `ACTIVE`
- Prompt version: `confirm_card_v1`
- `verify_jwt=false`

## Architecture reference

- Canonical architecture reference is `docs/AI_ASSISTANT_TURN_V2_ARCHITECTURE.md`.
- Next step is **Weight Tracking (e.g., `weight_record`) or Daily Summary Tools** under the new V2 architecture.
