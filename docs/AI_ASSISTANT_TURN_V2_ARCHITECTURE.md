# AI Assistant Turn V2 Architecture

## 1. Architecture refactor background

The old DayZero AI runtime flow was built around a multi-entry routing chain:

- `HybridIntentRouter`
- `classify-user-intent`
- `generate-checkin-draft`
- `AiCompanionReplyRepository`
- related intent-routing and summary branches

That design created three core problems:

- Too many runtime layers, making the send path hard to reason about.
- Semantic errors could accumulate across classification, branching, draft generation, and reply generation.
- It was difficult to verify whether the new architecture was actually active, because the old chain could still behave like a hidden fallback.

The new architecture decision is explicit:

- The old main chain is retired as the primary runtime design.
- Runtime fallback to the old chain is not allowed.
- Future AI behavior must be built around `assistant-turn-v2` as the single orchestration entrypoint.

## 2. Core principles of the new architecture

- All user-initiated input must enter `assistant-turn-v2`.
- AI no longer performs top-level intent classification as the main runtime strategy.
- The default AI behavior is to generate a natural-language `reply`.
- Tool invocation is an optional action beyond `reply`, not the main entry mode.
- After every user-initiated input, the first carrying response must be an AI-generated `reply`.
- The client must not fabricate fixed AI copy for normal user-initiated messages.
- If no tool is needed, `actions` must be an empty array.
- `reply` is the natural fallback. The system must not fall back to the legacy runtime chain.

## 3. Reply rules

- Every user-initiated input must receive an AI-generated `reply`.
- The `reply` after user-initiated input must come from the model, not client-side hardcoded text.
- Pure operation feedback after a completed card interaction may be client-generated fixed text, such as:
  - "Record saved"
  - "Cancelled"
- If a card interaction still needs further reasoning, the client must send the `interaction_result` back into `assistant-turn-v2`.
- In that case, AI must continue the flow by generating the next `reply` and optional `action`.

## 4. Tool invocation principles

- The only structured judgment AI really needs is:
  - whether this turn needs a tool
  - which tool should be called
- The architecture does not require separate top-level handling for small talk, comfort, consultation, venting, or general chat.
- Those conversational cases should be handled naturally through `reply`.
- Tools are only responsible for UI interaction, draft display, confirmation surfaces, or read-only query actions.
- Tools must not write Room messages directly.
- Real writes for add, edit, delete, food log, and weight log must happen only after explicit user confirmation and must be executed by the client.

## 5. Food logging sensitivity principle

- A user mentioning what they ate today does not automatically mean they want it recorded.
- Direct entry into the record tool is allowed only when the user clearly indicates recording intent, for example:
  - "help me record"
  - "log this"
  - "enter this"
  - "note it down"
  - "add this to today"
  - "count this in"
- If the user is only sharing what they ate, the flow should use `ask_record_intent_card` to ask whether the user wants to record it.
- DayZero should not be over-sensitive. Mentioning food alone must not automatically produce a `DraftCard`.

## 6. Tool chain principles

- Tools may form multi-step chains.
- Example: after `ask_record_intent_card`, if the user chooses "help me record", the next round must re-enter `assistant-turn-v2`, and AI then returns `reply + ask_meal_type_card` or `reply + show_food_draft_card`.
- Example: after `ask_meal_type_card`, the next round must re-enter `assistant-turn-v2`, and AI then returns `reply + show_food_draft_card`.
- Tool chaining must not secretly use the legacy runtime logic.
- The assistant remains the only orchestration layer even when multiple tools participate across turns.

## 7. Phase development flow

### Phase 1: pure AI chat single entry

- Keep only `assistant-turn-v2` reply.
- Do not connect tools.
- Do not render cards.
- Do not write food or weight records.
- Verify that the old chain has been disconnected from the runtime send flow.

### Phase 2: protocol shell (已验收完成)

- Change the response shape to `reply + actions`.
- Keep `actions` as an empty array for now.
- Verify that the JSON protocol is stable.

### Phase 3A-0: debug tool 单次调用验证 (已验收完成)

- Add fake tools first, such as `debug_show_choice_card`.
- Verify that Kimi can reliably choose tools under the right conditions (action judgment only).
- Currently, the ONLY allowed action is `debug_show_choice_card`.
- Real business tools, real UI cards, and real database writes are NOT yet connected.
- Verify that tool click results can return to `assistant-turn-v2` as `interaction_result` (future step).

### Phase 3A-1: debug action 客户端解析与 UI 渲染 (已验收完成)

- Client officially supports parsing and rendering `debug_show_choice_card`.
- Added interactive Debug Choice Card UI which is rendered directly under the AI's reply message using the action payload (title, message, options).
- When a debug option is clicked, it prints a debug log showing `interactionId`, `optionId`, and `optionLabel` and displays a local confirmation Toast.
- No interaction result is sent back to the AI server in this phase, and no real business tools/writes are executed.
- If an unknown action type is received, a protocol error is surfaced and logged, with no legacy fallback.

### Phase 3B: interaction_result 回传到 assistant-turn-v2 (已验收完成)

- Implement client-side click event mapping to return user's choice back to `assistant-turn-v2` endpoint as an `interaction_result` message.
- Verify that AI can parse the result and continue the conversation chain.

### Phase 4: real tools connected one by one

- `ask_record_intent_card` (Phase 4A - 已完成)
- `ask_missing_info_card` (Phase 4B-1 - 已完成)
- `show_food_draft_card`
- `show_weight_confirm_card`
- `show_summary_card`
- `show_delete_confirm_card`
- `show_edit_confirm_card`

## 8. Prohibitions

- Do not restore legacy fallback.
- Do not let `HybridIntentRouter` become the main entrypoint again.
- Do not rebuild `classify-user-intent -> generate-checkin-draft` as the serial AI main chain.
- Do not let the client generate fixed AI replies for user-initiated input.
- Do not generate a `DraftCard` just because the user mentioned food.
- Do not let AI write directly to the database.

## 9. Acceptance criteria

- Any user input receives an AI `reply`.
- When no tool is needed, the response contains only `reply` (or `reply + actions` as per Phase 2).
- When a tool is needed, the response shape is `reply + action`.
- Tool interaction results return to `assistant-turn-v2`.
- Legacy-chain logs no longer appear in the AI record page send flow.
- Protocol errors are surfaced directly and do not trigger legacy fallback.

## Current implementation note

As of 2026-06-08, DayZero Phase 2 and Phase 3 have been fully verified and completed:

- **Protocol Shell Stable**: The `assistant-turn-v2` edge function is verified and stable, returning `{"reply": "...", "actions": []}`.
- **Strict Validation**: The client parses `reply + actions` protocol successfully. `actions` is strictly validated. Unsupported actions or invalid payloads trigger a protocol error.
- **Clean Logs**: Only `assistant-turn-v2` and `AssistantTurnResponse` related logs appear in the runtime send flow. No legacy classes are called.
- **Phase 3A-0 & 3A-1 Completed**: Client-side parsing and UI rendering for `debug_show_choice_card` have been fully implemented.
- **Phase 3B Completed**: The user's selection on the debug card is correctly formatted as `interaction_result` and posted back to `assistant-turn-v2`. Kimi generates the follow-up transition reply correctly (using system prompt version `tool_debug_v3`).
- **Phase 4A Completed**: The real business tool `ask_record_intent_card` has been fully integrated.
  - Client handles parsing and rendering of `ask_record_intent_card`.
  - Supabase Edge Function `assistant-turn-v2` has been successfully updated to prompt version `record_intent_v1` and deployed to remote.
  - The `ask_record_intent_card` tool is fully wired in the remote prompt. It only queries the user's intent to record, without generating a `DraftCard` or writing to the database.
  - It handles only the intent of recording and returns the `interaction_result` without creating a `DraftCard` or writing to the DB.
- **Phase 4B-1 Completed**: The `ask_missing_info_card` tool has been fully integrated.
  - Client handles parsing and rendering of `ask_missing_info_card`.
  - Supabase Edge Function `assistant-turn-v2` updated to prompt version `missing_info_v1`.
  - Currently, `ask_missing_info_card` only supports querying `mealType`.
  - This phase does NOT generate a `DraftCard`, does NOT write to the database, and does NOT estimate calories.
- **Phase 4B-2 Completed**: The `show_confirm_card` tool has been fully integrated.
  - Client parses and renders `show_confirm_card`.
  - Edge function prompt updated to `confirm_card_v1`.
  - Currently only supports `confirmType=food_record`.
  - This phase ONLY displays the confirmation draft card without writing to the database.
- **Phase 4C Completed**: Real database writing for `show_confirm_card` (food_record) has been implemented on the client.
  - When the user clicks confirm, the client reads the `PayloadSummary` from the card and deterministically writes the `DailyRecord` and `MealEntry` mapping directly into the Room Database using the existing local repositories.
  - When the user clicks confirm or cancel, the `state` of the `show_confirm_card` is updated locally (`confirmed` or `cancelled`) and saved to the database. The card UI reacts to this state by hiding the action buttons and displaying a fixed status message ("已确认记录" or "已取消记录").
  - Feedback for confirmation or cancellation is deterministically generated locally by the client, without hitting Kimi.
  - The old AI check-in flow components (HybridIntentRouter, IntentClassifier, etc.) remain fully deprecated and bypassed.
- **Next Phase**: Begin implementing weight tracking (e.g., `weight_record`) or daily summary tools under the new V2 architecture.

## Streaming card latency optimization handoff

### Context for the next AI/engineer

The current app path is:

- Primary runtime endpoint: `assistant-turn-v2-stream`
- Fallback endpoint: `assistant-turn-v2`
- Retired endpoint: `ai-assistant-turn` must not be restored
- Android receives streaming natural-language text first, then renders cards after final actions arrive
- Android public card models and Compose card components should remain unchanged

The latest latency traces show that streaming text is working, but card display becomes slow when the stream action payload is incomplete:

- Example user input: "我今天中午吃了螺狮粉"
  - First text token: about 3.7s
  - Stream completed: about 5.8s
  - Stream response already had `actionsCount=1`
  - Client then threw `ProtocolException`
  - Fallback to old `assistant-turn-v2` completed around 14.1s
- Example interaction result: user clicked "帮我记录"
  - First text token: about 3.8s
  - Stream completed: about 6.0s
  - Stream response already had `actionsCount=1`
  - Client then threw `ProtocolException`
  - Fallback completed around 19.8s

Important conclusion: card rendering itself is fast, usually tens of milliseconds. The delay is caused by invalid/incomplete streamed action payloads triggering a second non-streaming Kimi request.

### Optimization goal

Do not make Kimi output a full UI card JSON whenever possible. Kimi should output:

- Natural reply text
- Tool/action decision
- Only the minimal business data that actually requires AI reasoning or extraction

The server should normalize that short action into the existing full public `reply + actions` protocol before Android sees it.

Android should still receive the existing complete card payloads, so current UI/card parsing stays compatible.

### Target response strategy

Keep compact outer JSON from Kimi:

```json
{
  "r": "我先帮你整理成一版记录，你确认一下就好。",
  "a": [
    {
      "type": "show_confirm_card",
      "mealType": "lunch",
      "items": [
        {
          "name": "螺蛳粉",
          "amountText": "1碗",
          "calories": 600
        }
      ]
    }
  ]
}
```

Then the Edge Function expands it into the existing public protocol:

- `reply`
- `actions[]`
- `interactionId` / `id`
- full `payload`
- `debugTiming`

### Server-side action normalization rules

Implement normalization inside `supabase/functions/assistant-turn-v2-stream/index.ts` before emitting the final event.

The stream function should accept both:

- Full legacy actions already shaped for Android
- Short AI actions that need server-side completion

For `ask_record_intent_card`, Kimi only needs:

- `type`
- optionally `originalText`

Server fills:

- `interactionId`
- `payload.title`
- `payload.message`
- `payload.originalText`
- options: `record`, `chat_only`, `not_now`

For `ask_missing_info_card`, Kimi only needs:

- `type`
- `field` when available, default `mealType`
- optionally `originalText`

Server fills:

- `interactionId`
- `payload.title`
- `payload.message`
- `payload.field`
- `payload.originalText`
- options: `breakfast`, `lunch`, `dinner`, `snack`

For `show_confirm_card`, Kimi should only generate business data:

- meal type(s)
- food names
- amount text
- estimated calories
- optional weight

Server fills:

- `id`
- `payload.confirmType = "food_record"`
- `payload.title`
- `payload.message`
- `payload.date`
- `payload.weightKg`
- `payload.totalCalories`
- `payload.meals[]`
- `calorieConfidence = "estimated"` when missing
- buttons: `confirm`, `cancel`
- `payload.originalText`

If Kimi returns a single `mealType + items[]`, server should wrap it into `meals[]`.

If Kimi returns multiple meals, server should preserve them and only fill missing template fields.

### Fields that should not require Kimi

Do not ask Kimi to generate these unless there is a business reason:

- Card title
- Card message
- Button labels
- Static option sets
- `confirmType`
- `interactionId`
- `date`
- `calorieConfidence` default
- `state`
- UI template copy

These should be deterministic server/client template fields.

### Fields that Kimi may need to generate

Kimi should focus on:

- Whether a card is needed
- Which card type is needed
- Meal type inference when present in user text
- Food item names
- Amount/portion text
- Estimated calories
- Optional weight extraction
- Whether multiple meals are present
- Natural reply text

### Fallback policy

Avoid fallback when the short action can be safely normalized.

Fallback to `assistant-turn-v2` should be reserved for cases where:

- The streamed JSON is not parseable
- `reply` is blank
- action type is unsupported
- required business data for a confirm card is truly missing, e.g. no usable food items

Do not fallback merely because title/message/options/buttons are missing; those are template fields and should be filled by the stream function.

### Acceptance criteria

- For a food mention like "我今天中午吃了螺蛳粉":
  - First text still streams quickly
  - `assistant-turn-v2-stream` final has `actionsCount=1`
  - No `remote_repository_stream_failed_fallback_start`
  - `fallbackUsed=false`
  - `ask_record_intent_card` appears immediately after stream completion
- For clicking "帮我记录":
  - No fallback
  - `show_confirm_card` appears after stream completion
  - Android receives the same full public payload shape as before
- Trace should show card latency dominated by one Kimi stream, not stream plus a second non-streaming Kimi request.

### Important implementation warning

Do not solve this by making Kimi output a very long full UI JSON again. That works functionally but wastes tokens and can slow stream completion. The intended fix is short AI business actions plus deterministic Edge Function normalization.
