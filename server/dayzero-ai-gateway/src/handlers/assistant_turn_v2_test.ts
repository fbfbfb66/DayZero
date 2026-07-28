import { assertEquals } from "@std/assert";
import { handleAssistantTurnV2 } from "./assistant_turn_v2.ts";
import { buildRequest, createLoggerStub, createTestConfig } from "../test_helpers.ts";

const VALID_BASE64_4B = "dGVzdA==";

type CapturedRequest = {
  url: string | URL | Request;
  init?: RequestInit;
  body: Record<string, unknown>;
};

function setupKimiMocks(responseContent: string) {
  const originalFetch = globalThis.fetch;
  const captured: CapturedRequest[] = [];

  globalThis.fetch = (
    url: string | URL | Request,
    init?: RequestInit,
  ): Promise<Response> => {
    captured.push({
      url,
      init,
      body: JSON.parse(init?.body as string),
    });
    return Promise.resolve(
      new Response(
        JSON.stringify({
          choices: [{
            message: { content: responseContent },
          }],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );
  };

  return {
    captured,
    restore: () => {
      globalThis.fetch = originalFetch;
    },
  };
}

function buildBaseRequest(body: unknown): Request {
  return buildRequest("POST", "/assistant-turn-v2", body);
}

Deno.test("fallback handler: text-only request keeps string user content", async () => {
  const mocks = setupKimiMocks(JSON.stringify({ r: "ok", a: [] }));
  try {
    const response = await handleAssistantTurnV2(
      buildBaseRequest({ date: "2026-06-26", userText: "hello" }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-1",
    );

    assertEquals(response.status, 200);
    const responseBody = await response.json();
    assertEquals(responseBody.reply, "ok");
    assertEquals(responseBody.actions, []);

    assertEquals(mocks.captured.length, 1);
    const outbound = mocks.captured[0].body;
    const messages = outbound.messages as Record<string, unknown>[];
    assertEquals(messages.length, 2);
    assertEquals(messages[0].role, "system");
    assertEquals(messages[1].role, "user");
    assertEquals(typeof messages[1].content, "string");
    assertEquals((messages[1].content as string).includes("User:hello"), true);
    assertEquals(outbound.model, "kimi-k2.6");
  } finally {
    mocks.restore();
  }
});

Deno.test("fallback handler: single image uses array content", async () => {
  const mocks = setupKimiMocks(JSON.stringify({ r: "got it", a: [] }));
  try {
    const response = await handleAssistantTurnV2(
      buildBaseRequest({
        date: "2026-06-26",
        userText: "look at this",
        attachments: [{
          mediaId: "m1",
          mimeType: "image/jpeg",
          base64: VALID_BASE64_4B,
        }],
      }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-2",
    );

    assertEquals(response.status, 200);

    const outbound = mocks.captured[0].body;
    const messages = outbound.messages as Record<string, unknown>[];
    const content = messages[1].content as Record<string, unknown>[];
    assertEquals(Array.isArray(content), true);
    assertEquals(content.length, 2);
    assertEquals(content[0].type, "text");
    assertEquals(
      (content[0] as { text: string }).text.includes("User:look at this"),
      true,
    );
    assertEquals(
      (content[0] as { text: string }).text.includes("attachment_1: image 1"),
      true,
    );
    assertEquals(content[1], {
      type: "image_url",
      image_url: { url: `data:image/jpeg;base64,${VALID_BASE64_4B}` },
    });
  } finally {
    mocks.restore();
  }
});

Deno.test("fallback handler: multiple images preserve order", async () => {
  const mocks = setupKimiMocks(JSON.stringify({ r: "ok", a: [] }));
  try {
    await handleAssistantTurnV2(
      buildBaseRequest({
        date: "2026-06-26",
        userText: "two photos",
        attachments: [
          { mediaId: "m1", mimeType: "image/jpeg", base64: VALID_BASE64_4B },
          { mediaId: "m2", mimeType: "image/jpeg", base64: VALID_BASE64_4B },
        ],
      }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-3",
    );

    const outbound = mocks.captured[0].body;
    const messages = outbound.messages as Record<string, unknown>[];
    const content = messages[1].content as Record<string, unknown>[];
    assertEquals(content.length, 3);
    const ids = content.slice(1).map((part) =>
      (part as { image_url: { url: string } }).image_url.url
    );
    assertEquals(ids, [
      `data:image/jpeg;base64,${VALID_BASE64_4B}`,
      `data:image/jpeg;base64,${VALID_BASE64_4B}`,
    ]);
  } finally {
    mocks.restore();
  }
});

Deno.test("fallback handler: image-only request rejected without text", async () => {
  const mocks = setupKimiMocks(JSON.stringify({ r: "ok", a: [] }));
  try {
    const response = await handleAssistantTurnV2(
      buildBaseRequest({
        date: "2026-06-26",
        userText: "",
        attachments: [{
          mediaId: "m1",
          mimeType: "image/jpeg",
          base64: VALID_BASE64_4B,
        }],
      }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-4",
    );

    assertEquals(response.status, 400);
    const body = await response.json();
    assertEquals(body.errorCode, "EMPTY_VISION_TEXT");
    assertEquals(mocks.captured.length, 0);
  } finally {
    mocks.restore();
  }
});

Deno.test("fallback handler: invalid base64 rejected", async () => {
  const mocks = setupKimiMocks(JSON.stringify({ r: "ok", a: [] }));
  try {
    const response = await handleAssistantTurnV2(
      buildBaseRequest({
        date: "2026-06-26",
        userText: "look",
        attachments: [{
          mediaId: "m1",
          mimeType: "image/jpeg",
          base64: "not-base64!",
        }],
      }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-5",
    );

    assertEquals(response.status, 400);
    const body = await response.json();
    assertEquals(body.errorCode, "INVALID_ATTACHMENT_BASE64");
    assertEquals(mocks.captured.length, 0);
  } finally {
    mocks.restore();
  }
});

Deno.test("fallback handler: oversized attachment rejected", async () => {
  const mocks = setupKimiMocks(JSON.stringify({ r: "ok", a: [] }));
  try {
    // Base64 length must be a multiple of 4. 641 KiB decoded -> 875180 chars.
    const targetBytes = 640 * 1024 + 1;
    const base64Length = Math.ceil(targetBytes * 4 / 3 / 4) * 4;
    const oversized = "A".repeat(base64Length);
    const response = await handleAssistantTurnV2(
      buildBaseRequest({
        date: "2026-06-26",
        userText: "look",
        attachments: [{
          mediaId: "m1",
          mimeType: "image/jpeg",
          base64: oversized,
        }],
      }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-6",
    );

    assertEquals(response.status, 413);
    const body = await response.json();
    assertEquals(body.errorCode, "ATTACHMENT_TOO_LARGE");
    assertEquals(mocks.captured.length, 0);
  } finally {
    mocks.restore();
  }
});

Deno.test("fallback handler: interaction_result with attachments rejected", async () => {
  const mocks = setupKimiMocks(JSON.stringify({ r: "ok", a: [] }));
  try {
    const response = await handleAssistantTurnV2(
      buildBaseRequest({
        date: "2026-06-26",
        userText: "lunch",
        turnType: "interaction_result",
        interactionResult: {
          interactionId: "card-1",
          actionType: "ask_missing_info_card",
          selectedOptionId: "lunch",
          selectedOptionLabel: "午餐",
        },
        attachments: [{
          mediaId: "m1",
          mimeType: "image/jpeg",
          base64: VALID_BASE64_4B,
        }],
      }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-7",
    );

    assertEquals(response.status, 400);
    const body = await response.json();
    assertEquals(body.errorCode, "ATTACHMENTS_NOT_ALLOWED_FOR_TURN_TYPE");
    assertEquals(mocks.captured.length, 0);
  } finally {
    mocks.restore();
  }
});

Deno.test("fallback handler: interaction_result meal selection creates confirm action", async () => {
  const mocks = setupKimiMocks(JSON.stringify({ r: "ok", a: [] }));
  try {
    const response = await handleAssistantTurnV2(
      buildBaseRequest({
        date: "2026-06-29",
        userText: "selected lunch",
        turnType: "interaction_result",
        interactionResult: {
          interactionId: "meal-card",
          actionType: "ask_missing_info_card",
          selectedOptionId: "lunch",
          selectedOptionLabel: "Lunch",
          continuationContext: {
            schemaVersion: 1,
            recognizedFoods: [{
              name: "apple",
              amountText: "1 item",
              calories: 95,
              proteinG: 0.5,
            }],
          },
        },
      }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-8",
    );

    assertEquals(response.status, 200);
    const body = await response.json();
    assertEquals(body.actions.length, 1);
    assertEquals(body.actions[0].type, "show_confirm_card");
    assertEquals(body.actions[0].payload.meals[0].mealType, "lunch");
  } finally {
    mocks.restore();
  }
});

Deno.test("fallback handler: missing userText returns 400", async () => {
  const response = await handleAssistantTurnV2(
    buildBaseRequest({ date: "2026-06-26" }),
    createTestConfig(),
    createLoggerStub() as never,
    "req-9",
  );

  assertEquals(response.status, 400);
  const body = await response.json();
  assertEquals(body.error, "Missing userText");
});

Deno.test("fallback handler: wrong method returns 405", async () => {
  const response = await handleAssistantTurnV2(
    buildRequest("GET", "/assistant-turn-v2"),
    createTestConfig(),
    createLoggerStub() as never,
    "req-10",
  );

  assertEquals(response.status, 405);
});

function setupKimiStatusResponse(status: number, body: string) {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (
    _url: string | URL | Request,
    _init?: RequestInit,
  ): Promise<Response> => {
    return Promise.resolve(
      new Response(body, {
        status,
        headers: { "Content-Type": "application/json" },
      }),
    );
  };
  return () => {
    globalThis.fetch = originalFetch;
  };
}

Deno.test("fallback handler: Kimi 429 returns safe UPSTREAM_RATE_LIMITED and no raw body", async () => {
  const sensitiveMarker = "RATE_LIMIT_SENSITIVE_MARKER_429";
  const restore = setupKimiStatusResponse(
    429,
    JSON.stringify({ error: { message: sensitiveMarker } }),
  );
  try {
    const response = await handleAssistantTurnV2(
      buildBaseRequest({ date: "2026-06-26", userText: "hello" }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-kimi-429",
    );

    assertEquals(response.status, 502);
    const body = await response.json();
    assertEquals(body.errorCode, "UPSTREAM_RATE_LIMITED");
    assertEquals(body.retryable, true);
    const text = JSON.stringify(body);
    assertEquals(text.includes(sensitiveMarker), false);
  } finally {
    restore();
  }
});

Deno.test("fallback handler: Kimi 500 returns safe UPSTREAM_UNAVAILABLE and no raw body", async () => {
  const sensitiveMarker = "INTERNAL_ERROR_SENSITIVE_MARKER_500";
  const restore = setupKimiStatusResponse(
    500,
    JSON.stringify({ error: { message: sensitiveMarker } }),
  );
  try {
    const response = await handleAssistantTurnV2(
      buildBaseRequest({ date: "2026-06-26", userText: "hello" }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-kimi-500",
    );

    assertEquals(response.status, 502);
    const body = await response.json();
    assertEquals(body.errorCode, "UPSTREAM_UNAVAILABLE");
    assertEquals(body.retryable, true);
    assertEquals(JSON.stringify(body).includes(sensitiveMarker), false);
  } finally {
    restore();
  }
});

Deno.test("fallback handler: Kimi 400 returns safe UPSTREAM_PROTOCOL_ERROR and no raw body", async () => {
  const sensitiveMarker = "BAD_REQUEST_SENSITIVE_MARKER_400";
  const restore = setupKimiStatusResponse(
    400,
    JSON.stringify({ error: { message: sensitiveMarker } }),
  );
  try {
    const response = await handleAssistantTurnV2(
      buildBaseRequest({ date: "2026-06-26", userText: "hello" }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-kimi-400",
    );

    assertEquals(response.status, 502);
    const body = await response.json();
    assertEquals(body.errorCode, "UPSTREAM_PROTOCOL_ERROR");
    assertEquals(body.retryable, false);
    assertEquals(JSON.stringify(body).includes(sensitiveMarker), false);
  } finally {
    restore();
  }
});

Deno.test("fallback handler: Kimi non-JSON error body returns safe UPSTREAM_UNAVAILABLE", async () => {
  const restore = setupKimiStatusResponse(503, "<html>error</html>");
  try {
    const response = await handleAssistantTurnV2(
      buildBaseRequest({ date: "2026-06-26", userText: "hello" }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-kimi-html",
    );

    assertEquals(response.status, 502);
    const body = await response.json();
    assertEquals(body.errorCode, "UPSTREAM_UNAVAILABLE");
    assertEquals(body.retryable, true);
    assertEquals(JSON.stringify(body).includes("<html>"), false);
  } finally {
    restore();
  }
});
