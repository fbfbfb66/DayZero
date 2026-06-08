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
- **Next Phase**: The real save to the database will be implemented in **Phase 4C**.
