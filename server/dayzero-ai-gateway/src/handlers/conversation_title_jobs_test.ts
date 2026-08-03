import { assertEquals, assertStringIncludes } from "@std/assert";
import { handler } from "../main.ts";
import { buildRequest, createLoggerStub, createTestConfig } from "../test_helpers.ts";
import { parseTitleJobRequest } from "./conversation_title_jobs.ts";

const VALID_BODY = {
  requestId: "title-request-123",
  conversationId: "00000000-0000-4000-8000-000000000010",
  firstUserMessageId: "00000000-0000-4000-8000-000000000011",
  firstUserText: "帮我记录午餐",
};

Deno.test("title job parser rejects overlong input before persistence", () => {
  const result = parseTitleJobRequest({
    ...VALID_BODY,
    firstUserText: "x".repeat(2_001),
  });
  assertEquals(result.ok, false);
  if (!result.ok) {
    assertEquals(result.status, 413);
    assertEquals(result.errorCode, "TEXT_TOO_LONG");
  }
});

Deno.test("title route persists through dedicated RPC before returning 202", async () => {
  const originalFetch = globalThis.fetch;
  let requestedUrl = "";
  let rpcBody = "";
  globalThis.fetch = ((input: string | URL | Request, init?: RequestInit) => {
    requestedUrl = String(input);
    rpcBody = String(init?.body ?? "");
    return Promise.resolve(
      new Response(
        JSON.stringify({
          accepted: true,
          jobId: "00000000-0000-4000-8000-000000000099",
          status: "accepted",
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );
  }) as typeof fetch;
  try {
    const response = await handler(
      buildRequest("POST", "/api/ai/conversation-title-jobs", VALID_BODY, {
        "Authorization": "Bearer user-token",
      }),
      createTestConfig({ supabasePublishableKey: "publishable-test-key" }),
      createLoggerStub() as never,
    );
    assertEquals(response.status, 202);
    assertStringIncludes(requestedUrl, "enqueue_ai_conversation_title_job");
    assertStringIncludes(rpcBody, '"p_first_user_text":"帮我记录午餐"');
    const body = await response.json();
    assertEquals(body.status, "accepted");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

Deno.test("title route maps not-yet-synced parent to retryable 409", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (() =>
    Promise.resolve(
      new Response(
        JSON.stringify({ accepted: false, errorCode: "CONVERSATION_NOT_READY" }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    )) as typeof fetch;
  try {
    const response = await handler(
      buildRequest("POST", "/api/ai/conversation-title-jobs", VALID_BODY, {
        "Authorization": "Bearer user-token",
      }),
      createTestConfig({ supabasePublishableKey: "publishable-test-key" }),
      createLoggerStub() as never,
    );
    assertEquals(response.status, 409);
    const body = await response.json();
    assertEquals(body.retryable, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
