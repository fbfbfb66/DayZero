import { assertEquals } from "@std/assert";
import { handler } from "./main.ts";
import { buildRequest, createLoggerStub, createTestConfig } from "./test_helpers.ts";
import { StructuredLogger } from "./logger.ts";

const AI_ENDPOINTS = [
  { method: "POST", old: "/assistant-turn-v2", new: "/api/ai/assistant-turn-v2" },
  {
    method: "POST",
    old: "/assistant-turn-v2-stream",
    new: "/api/ai/assistant-turn-v2-stream",
  },
];

function hasRequestId(response: Response): boolean {
  const value = response.headers.get("X-Request-Id");
  return typeof value === "string" && value.length > 0;
}

Deno.test("health endpoint returns ok", async () => {
  const response = await handler(
    buildRequest("GET", "/health"),
    createTestConfig(),
    createLoggerStub() as never,
  );

  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.status, "ok");
  assertEquals(hasRequestId(response), true);
});

Deno.test("ready endpoint returns ready with valid config", async () => {
  const response = await handler(
    buildRequest("GET", "/ready"),
    createTestConfig(),
    createLoggerStub() as never,
  );

  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.status, "ready");
  assertEquals(hasRequestId(response), true);
});

Deno.test("ready endpoint returns 503 with missing Kimi config", async () => {
  const response = await handler(
    buildRequest("GET", "/ready"),
    createTestConfig({ kimiApiKey: "" }),
    createLoggerStub() as never,
  );

  assertEquals(response.status, 503);
  const body = await response.json();
  assertEquals(body.errorCode, "MISSING_KIMI_API_KEY");
  assertEquals(hasRequestId(response), true);
});

Deno.test("GET on POST AI endpoint returns 405", async () => {
  const response = await handler(
    buildRequest("GET", "/api/ai/assistant-turn-v2"),
    createTestConfig(),
    createLoggerStub() as never,
  );

  assertEquals(response.status, 405);
  assertEquals(hasRequestId(response), true);
});

Deno.test("POST on health returns 405", async () => {
  const response = await handler(
    buildRequest("POST", "/health"),
    createTestConfig(),
    createLoggerStub() as never,
  );

  assertEquals(response.status, 405);
  assertEquals(hasRequestId(response), true);
});

Deno.test("unknown path returns 404", async () => {
  const response = await handler(
    buildRequest("GET", "/unknown"),
    createTestConfig(),
    createLoggerStub() as never,
  );

  assertEquals(response.status, 404);
  assertEquals(hasRequestId(response), true);
});

Deno.test("OPTIONS request returns ok", async () => {
  const response = await handler(
    buildRequest("OPTIONS", "/api/ai/assistant-turn-v2"),
    createTestConfig(),
    createLoggerStub() as never,
  );

  assertEquals(response.status, 200);
  assertEquals(response.headers.get("Access-Control-Allow-Origin"), "*");
  assertEquals(hasRequestId(response), true);
});

Deno.test("AI endpoints reject missing userText", async () => {
  for (const endpoint of AI_ENDPOINTS) {
    const response = await handler(
      buildRequest(endpoint.method, endpoint.new, { date: "2026-06-26" }),
      createTestConfig(),
      createLoggerStub() as never,
    );

    assertEquals(response.status, 400, `expected 400 for ${endpoint.new}`);
    assertEquals(hasRequestId(response), true, `missing request id for ${endpoint.new}`);
  }
});

Deno.test("old and new AI paths are both recognized", async () => {
  for (const endpoint of AI_ENDPOINTS) {
    for (const path of [endpoint.old, endpoint.new]) {
      // Invalid turnType stops before Kimi while still proving the route is recognized.
      const response = await handler(
        buildRequest(endpoint.method, path, {
          date: "2026-06-26",
          userText: "hi",
          turnType: "invalid_turn",
        }),
        createTestConfig(),
        createLoggerStub() as never,
      );

      assertEquals(
        response.status,
        400,
        `expected 400 for ${path}`,
      );
      const body = await response.json();
      assertEquals(body.errorCode, "INVALID_TURN_TYPE");
      assertEquals(hasRequestId(response), true, `missing request id for ${path}`);
    }
  }
});

Deno.test("inbound X-Request-Id is reused when valid", async () => {
  const response = await handler(
    buildRequest("GET", "/health", undefined, { "X-Request-Id": "valid-req-123" }),
    createTestConfig(),
    createLoggerStub() as never,
  );

  assertEquals(response.headers.get("X-Request-Id"), "valid-req-123");
});

Deno.test("inbound X-Request-Id is replaced when invalid", async () => {
  const response = await handler(
    buildRequest("GET", "/health", undefined, { "X-Request-Id": "bad id!" }),
    createTestConfig(),
    createLoggerStub() as never,
  );

  const resolved = response.headers.get("X-Request-Id");
  assertEquals(typeof resolved, "string");
  assertEquals(resolved !== "bad id!", true);
});

Deno.test("auth failure logs only safe fields and no token", async () => {
  const logs: string[] = [];
  const originalWarn = console.warn;
  console.warn = (message: string) => logs.push(message);

  try {
    const logger = new StructuredLogger("test", "warn");
    const response = await handler(
      buildRequest("POST", "/api/ai/assistant-turn-v2", { date: "2026-06-26", userText: "hi" }, {
        "Authorization": "Bearer sensitive-token-leak-marker",
      }),
      createTestConfig({ enableAuth: true }),
      logger,
    );

    assertEquals(response.status, 401);
    assertEquals(logs.length, 1);
    const entry = JSON.parse(logs[0]);
    assertEquals(entry.message, "auth failed");
    assertEquals(entry.errorCode, "JWT_PARSE_FAILED");
    assertEquals(entry.httpStatus, 401);
    assertEquals(entry.routePath, "/api/ai/assistant-turn-v2");
    assertEquals(entry.authorization, undefined);
    assertEquals(entry.userIdDigest, undefined);
    assertEquals(logs.join("\n").includes("sensitive-token-leak-marker"), false);
  } finally {
    console.warn = originalWarn;
  }
});
