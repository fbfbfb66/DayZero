import { assertEquals } from "@std/assert";
import { parseRequestBody } from "./request_parser.ts";

Deno.test("parseRequestBody accepts minimal valid text-only request", () => {
  const result = parseRequestBody({ date: "2026-06-26", userText: "hello" });
  assertEquals(result.ok, true);
  if (!result.ok) return;
  assertEquals(result.request.userText, "hello");
  assertEquals(result.request.turnType, "user_message");
  assertEquals(result.request.date, "2026-06-26");
});

Deno.test("parseRequestBody defaults turnType to user_message", () => {
  const result = parseRequestBody({ date: "2026-06-26", userText: "hello" });
  assertEquals(result.ok, true);
  if (!result.ok) return;
  assertEquals(result.request.turnType, "user_message");
});

Deno.test("parseRequestBody rejects missing userText", () => {
  const result = parseRequestBody({ date: "2026-06-26" });
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.status, 400);
  assertEquals(result.body.error, "Missing userText");
});

Deno.test("parseRequestBody rejects non-string userText", () => {
  const result = parseRequestBody({ date: "2026-06-26", userText: 123 });
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.status, 400);
  assertEquals(result.body.errorCode, "INVALID_USER_TEXT");
});

Deno.test("parseRequestBody rejects invalid turnType", () => {
  const result = parseRequestBody({
    date: "2026-06-26",
    userText: "hello",
    turnType: "unknown",
  });
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.status, 400);
  assertEquals(result.body.errorCode, "INVALID_TURN_TYPE");
});

Deno.test("parseRequestBody rejects non-string date", () => {
  const result = parseRequestBody({ date: 123, userText: "hello" });
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.status, 400);
  assertEquals(result.body.errorCode, "INVALID_DATE");
});

Deno.test("parseRequestBody rejects non-array recentMessages", () => {
  const result = parseRequestBody({
    date: "2026-06-26",
    userText: "hello",
    recentMessages: "not-array",
  });
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.status, 400);
  assertEquals(result.body.errorCode, "INVALID_RECENT_MESSAGES");
});

Deno.test("parseRequestBody accepts valid interaction_result", () => {
  const result = parseRequestBody({
    date: "2026-06-26",
    userText: "selected lunch",
    turnType: "interaction_result",
    interactionResult: {
      interactionId: "card-1",
      actionType: "ask_missing_info_card",
      selectedOptionId: "lunch",
      selectedOptionLabel: "Lunch",
    },
  });
  assertEquals(result.ok, true);
});

Deno.test("parseRequestBody rejects interaction_result missing interactionResult", () => {
  const result = parseRequestBody({
    date: "2026-06-26",
    userText: "selected lunch",
    turnType: "interaction_result",
  });
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.status, 400);
  assertEquals(result.body.errorCode, "MISSING_INTERACTION_RESULT");
});

Deno.test("parseRequestBody rejects non-object interactionResult", () => {
  const result = parseRequestBody({
    date: "2026-06-26",
    userText: "selected lunch",
    turnType: "interaction_result",
    interactionResult: "not-object",
  });
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.status, 400);
  assertEquals(result.body.errorCode, "MISSING_INTERACTION_RESULT");
});

Deno.test("parseRequestBody rejects non-object todayRecord", () => {
  const result = parseRequestBody({
    date: "2026-06-26",
    userText: "hello",
    todayRecord: "not-object",
  });
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.status, 400);
  assertEquals(result.body.errorCode, "INVALID_TODAY_RECORD");
});

Deno.test("parseRequestBody rejects non-object pendingDraft", () => {
  const result = parseRequestBody({
    date: "2026-06-26",
    userText: "hello",
    pendingDraft: ["not-object"],
  });
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.status, 400);
  assertEquals(result.body.errorCode, "INVALID_PENDING_DRAFT");
});

Deno.test("parseRequestBody preserves unknown fields in rawBody", () => {
  const result = parseRequestBody({
    date: "2026-06-26",
    userText: "hello",
    unknownFutureField: "value",
  });
  assertEquals(result.ok, true);
  if (!result.ok) return;
  assertEquals(result.request.rawBody.unknownFutureField, "value");
});
