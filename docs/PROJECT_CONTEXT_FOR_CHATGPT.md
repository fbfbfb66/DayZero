# DayZero Project Context

## Current status

- As of 2026-06-08, Phase 2, Phase 3A-0, Phase 3A-1, Phase 3B, and Phase 4A are complete.
- The project is now at Phase 4B.
- `assistant-turn-v2` is the only active AI main runtime entrypoint. Legacy fallback is strictly prohibited.
- Room chat persistence is fully enabled. User messages, AI replies, and cards (like `DebugChoiceCardPayload` and `AskRecordIntentCardPayload`) are fully persistent.
- Fixed a serialization bug where `AskRecordIntentCardPayload` was dropped when saving to the Room database. The fix adds direct mapping in `AiAssistantRemoteMapper.kt` and `originalText` to `AiAssistantTurnDto.kt`'s `AiChatCardDto`.
- Legacy card UI composables (`DraftCard`, `ChoiceCardMessage`, `ConfirmedSummaryCard`) have been restored as Composable definitions at the bottom of `AiRecordScreen.kt` but remain unreferenced (disconnected from active rendering) to keep the V2 chat loop clean until Phase 4.

## Current Phase Features (Phase 4A Complete)

- **JSON Protocol**: Edge function `assistant-turn-v2` returns `{"reply": "...", "actions": []}`.
- **Protocol Validation**: `RemoteAiAssistantRepository` strictly validates the response. If `reply` is missing or `actions` contains invalid items, it throws `ProtocolException("协议错误")` which is surfaced in the UI.
- **Turn Type**: Requests support `turnType = "user_message"` (for normal chat) and `turnType = "interaction_result"` (when clicking card options).
- **Tools Supported**: Currently allows `debug_show_choice_card` and `ask_record_intent_card`. Any other action triggers a protocol error.
- **Interaction Result**: Clicking `ask_record_intent_card` options triggers `sendInteractionResult` in `DayZeroViewModel`, posting `interaction_result` back to `assistant-turn-v2`. Kimi receives this and generates a follow-up reply (prompt version `record_intent_v1`).
- **No Real Writes**: No real food records, weights, summaries, edits, or deletes are written or executed in Phase 4A.

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
- `DayZeroAiV2: parse AssistantTurnResponse start / success / error`
- `DayZeroAiV2: action parse start / success`
- `DayZeroAiV2: action type = debug_show_choice_card`
- `DayZeroAiV2: render debug_show_choice_card`
- `DayZeroAiV2: debug option clicked interactionId=... optionId=... optionLabel=...`
- `DayZeroAiV2: interaction_result created / send to assistant-turn-v2 / success / error`

## Supabase

- Project: `sybenxmxnwwtlvkeojtj` (`DayZero`)
- Edge Function: `assistant-turn-v2`
- Remote status: `ACTIVE`
- Current version: `6` (Prompt version: `record_intent_v1`)
- `verify_jwt=false`

## Architecture reference

- Canonical architecture reference is `docs/AI_ASSISTANT_TURN_V2_ARCHITECTURE.md`.
- Next step is **Phase 4B: ask_missing_info_card / show_confirm_card**.
